//! HomeHub server.
//!
//! A single-process home hub: photo album, NAS file management and a
//! monitoring placeholder. WireGuard provides the network boundary, so the
//! data plane has no user system — requests are audited by peer instead.
//!
//! Layout of the HTTP surface:
//!
//! * `/api/admin/*`  – management UI API (JWT, single admin password)
//! * `/api/photos/*` – album browsing views (timeline / tree / tags / people / geo)
//! * `/api/files/*`  – NAS file management (`<dir>/<rel-path>` addressing)
//! * `/api/media/*`  – originals, 256px thumbnails, lyrics, media info
//! * `/api/trash/*`  – recycle bin
//! * `/api/search`   – FTS5 search across photos and files

use std::net::SocketAddr;
use std::sync::Arc;

use axum::Router;
use tower_http::{cors::CorsLayer, trace::TraceLayer};
use tracing_subscriber::{layer::SubscriberExt, util::SubscriberInitExt};

mod config;
mod db;
mod handlers;
mod middleware;
mod models;
mod services;
mod utils;

use config::{Config, ConfigStore, RuntimeSettings};
use middleware::audit_layer::AuditLayer;
use services::audit::AuditWriter;
use services::dirs::DirRegistry;
use services::nvr::NvrState;
use services::tasks::TaskQueue;

/// Shared application state.
#[derive(Clone)]
pub struct AppState {
    pub db: db::Db,
    /// Live configuration (directory registry can be changed at runtime).
    pub config: Arc<ConfigStore>,
    pub registry: DirRegistry,
    pub queue: TaskQueue,
    pub audit: AuditWriter,
    pub jwt_secret: String,
    pub web_url: String,
    pub started_at: i64,
    /// NVR state (None when `nvr.enabled` is false).
    pub nvr: Option<std::sync::Arc<NvrState>>,
}

#[tokio::main]
async fn main() -> anyhow::Result<()> {
    tracing_subscriber::registry()
        .with(tracing_subscriber::EnvFilter::new(
            std::env::var("RUST_LOG").unwrap_or_else(|_| "info".into()),
        ))
        .with(tracing_subscriber::fmt::layer())
        .init();

    let config_path =
        std::env::var("CONFIG_PATH").unwrap_or_else(|_| "config.yaml".to_string());
    let config = Config::load(&config_path)?;
    tracing::info!("loaded configuration from {}", config_path);

    // Runtime settings have two owners — see `config`'s module docs. SQLite
    // holds the sections the admin UI edits, `config.yaml` the rest.
    let pool = db::open(&config).await?;
    tracing::info!("sqlite ready at {}", config.db_path().display());

    let runtime = load_runtime_settings(&pool, &config).await?;
    let mut config = config;
    config.runtime = runtime;

    // Refuse to run with known default credentials; generate + persist strong ones.
    let hardened = crate::config::harden_credentials(&mut config);
    if hardened {
        tracing::warn!("credential defaults replaced; config.yaml rewritten");
    }
    let store = Arc::new(ConfigStore::new(config.clone(), std::path::PathBuf::from(&config_path)));
    if hardened && store.update(config.clone()).is_err() {
        tracing::error!("failed to persist hardened credentials to {}", config_path);
    }

    let registry = DirRegistry::bootstrap(pool.clone(), &config).await?;
    tracing::info!("registered {} directories", registry.all().len());

    let detector = services::ml::build(&config.runtime.ml, &config.global.data_dir);

    // Chinese-CLIP encoders + in-memory embedding store for semantic search.
    #[cfg(feature = "onnx")]
    let clip = {
        let ml = &config.runtime.ml;
        if ml.clip.enabled {
            match services::ml::clip::ClipEncoder::new(
                &ml.clip,
                ml.onnx_threads,
                std::path::Path::new(&config.global.data_dir),
            ) {
                Ok(c) => {
                    tracing::info!("clip encoders loaded ({}d)", services::embedding_store::EMBED_DIM);
                    Some(std::sync::Arc::new(c))
                }
                Err(e) => {
                    tracing::warn!("clip unavailable ({}), semantic search disabled", e);
                    None
                }
            }
        } else {
            None
        }
    };

    let embeddings = std::sync::Arc::new(services::embedding_store::EmbeddingStore::new(
        services::embedding_store::EMBED_DIM,
    ));
    // Warm the in-memory index from SQLite (persistence layer is not the hot path).
    match embeddings.preload(&pool).await {
        Ok(n) if n > 0 => tracing::info!("embedding store preloaded {} vectors", n),
        Ok(_) => {}
        Err(e) => tracing::warn!("embedding preload failed: {e}"),
    }

    let queue = TaskQueue::new(
        pool.clone(),
        registry.clone(),
        store.clone(),
        detector,
        #[cfg(feature = "onnx")]
        clip,
        embeddings,
    );

    let audit_writer = services::audit::spawn(pool.clone(), &config);

    // NVR: per-camera RTSP recorders + cache maintainer + retention cleanup.
    let nvr_state: Option<std::sync::Arc<NvrState>> = if config.runtime.nvr.enabled {
        let nvr_pool = pool.clone();
        let nvr_store = store.clone();
        services::nvr::spawn(nvr_pool, nvr_store).await
    } else {
        None
    };

    std::fs::create_dir_all(config.trash_dir()).ok();
    std::fs::create_dir_all(config.originals_dir()).ok();

    let state = AppState {
        db: pool,
        config: store,
        registry: registry.clone(),
        queue: queue.clone(),
        audit: audit_writer,
        jwt_secret: config.global.jwt_secret.clone(),
        web_url: config.global.web_url.clone(),
        started_at: db::now(),
        nvr: nvr_state,
    };

    let app = Router::new()
        // ── admin API ──
        .merge(handlers::admin::routes())
        .merge(handlers::admin::maintenance_routes())
        // ── NVR / monitoring ──
        .merge(handlers::nvr::routes(state.clone()))
        // ── data plane ──
        .merge(handlers::photos::routes())
        .merge(handlers::files::routes())
        .merge(handlers::geo::routes())
        .merge(handlers::images::routes())
        .merge(handlers::trash::routes())
        .merge(handlers::upload::routes())
        .route("/api/health", axum::routing::get(health))
        .route(
            "/api/monitor/config",
            axum::routing::get(monitor_config).put(save_monitor_config),
        )
        .fallback(spa_fallback)
        .layer(AuditLayer::new(state.audit.clone()))
        .layer(CorsLayer::permissive())
        .layer(TraceLayer::new_for_http())
        .with_state(state.clone());

    queue.start().await?;
    if let Err(e) = services::scan::spawn_watcher(registry, queue, 30) {
        tracing::warn!("file watcher disabled: {}", e);
    }

    let bind_addr = std::env::var("BIND_ADDR")
        .unwrap_or_else(|_| config.global.bind.clone());
    tracing::info!("HomeHub listening on {}", bind_addr);
    let listener = tokio::net::TcpListener::bind(&bind_addr).await?;
    axum::serve(
        listener,
        app.into_make_service_with_connect_info::<SocketAddr>(),
    )
    .await?;
    Ok(())
}

/// Resolve the admin SPA directory: `ADMIN_DIST` env wins, otherwise probe
/// both repo layouts — started from `server/` (`../admin/dist`) or from the
/// repo root (`admin/dist`).
fn admin_dist() -> std::path::PathBuf {
    if let Ok(dir) = std::env::var("ADMIN_DIST") {
        return std::path::PathBuf::from(dir);
    }
    for cand in ["../admin/dist", "admin/dist"] {
        let p = std::path::PathBuf::from(cand);
        if p.join("index.html").is_file() {
            return p;
        }
    }
    std::path::PathBuf::from("../admin/dist")
}

/// Content-Security-Policy served with the admin SPA.
///
/// `style-src` keeps `'unsafe-inline'` because the React views set inline
/// `style` attributes; scripts are restricted to same-origin bundles, which is
/// what actually shuts down an injected `<img onerror=…>`.
const CSP: &str = "default-src 'self'; \
     script-src 'self'; \
     style-src 'self' 'unsafe-inline'; \
     img-src 'self' data: blob: http: https:; \
     media-src 'self' blob: http: https:; \
     frame-src 'self' http: https:; \
     connect-src 'self' http: https:; \
     font-src 'self' data:; \
     object-src 'none'; \
     base-uri 'self'; \
     form-action 'self'; \
     frame-ancestors 'none'";

/// Serve the built admin SPA (single-container deployment).
///
/// Set `ADMIN_DIST` to the `admin/dist` directory; when it is missing the
/// server stays API-only and unknown paths return 404.
async fn spa_fallback(uri: axum::http::Uri) -> axum::response::Response {
    use axum::http::{header, StatusCode};
    use axum::response::IntoResponse;

    let dist = admin_dist();
    let requested = uri.path().trim_start_matches('/');
    let target = if requested.is_empty() {
        dist.join("index.html")
    } else {
        match crate::services::paths::safe_join(&dist, requested) {
            Some(p) if p.is_file() => p,
            _ => dist.join("index.html"),
        }
    };

    match tokio::fs::read(&target).await {
        Ok(body) => {
            let mime = mime_guess::from_path(&target).first_or_octet_stream();
            let cache = if requested.starts_with("assets/") {
                "public, max-age=31536000, immutable"
            } else {
                "no-cache"
            };
            (
                StatusCode::OK,
                [
                    (header::CONTENT_TYPE, mime.to_string()),
                    (header::CACHE_CONTROL, cache.to_string()),
                    // Defence in depth for the admin SPA: no inline scripts, no
                    // framing. (Only meaningful on document responses; harmless
                    // elsewhere, so it is attached unconditionally.)
                    (
                        axum::http::HeaderName::from_static("content-security-policy"),
                        CSP.to_string(),
                    ),
                ],
                body,
            )
                .into_response()
        }
        Err(_) => (
            StatusCode::NOT_FOUND,
            "HomeHub API is running; the admin UI is not built (see admin/README.md)",
        )
            .into_response(),
    }
}

/// Compose the runtime settings the process runs with.
///
/// Two owners, no overlap:
///
/// * SQLite holds the sections the admin UI edits (`tasks`, `alerts`,
///   `backup`), so a save from the browser survives a restart.
/// * `config.yaml` holds the rest (`ml`, `compression`, `video`, `originals`,
///   `trash`, `audit`). Nothing in the UI can change those, so the file is
///   re-applied on **every** start — editing it and restarting works, which it
///   did not when the stored blob simply won outright.
///
/// On a fresh database the file also seeds the SQLite-owned sections, so a
/// pre-provisioned `config.yaml` still bootstraps a new instance.
async fn load_runtime_settings(
    pool: &db::Db,
    config: &Config,
) -> anyhow::Result<RuntimeSettings> {
    let mut settings = match db::get_setting(pool, "runtime_settings").await? {
        Some(raw) => match serde_json::from_str::<RuntimeSettings>(&raw) {
            Ok(stored) => stored,
            Err(e) => {
                tracing::warn!("stored runtime settings are invalid ({}), falling back to the file", e);
                config.runtime.clone()
            }
        },
        None => config.runtime.clone(),
    };

    settings.apply_file_sections(&config.runtime);

    // Keep the stored blob equal to what is actually running, so whoever reads
    // the table is not misled by a stale copy of the file-owned sections.
    db::set_setting(pool, "runtime_settings", &serde_json::to_string(&settings)?).await?;
    Ok(settings)
}

async fn health(axum::extract::State(state): axum::extract::State<AppState>) -> axum::Json<serde_json::Value> {
    let photo_count: (i64,) = sqlx::query_as("SELECT COUNT(*) FROM photo_assets")
        .fetch_one(&state.db)
        .await
        .unwrap_or((0,));
    axum::Json(serde_json::json!({
        "status": "ok",
        "version": env!("CARGO_PKG_VERSION"),
        "photos": photo_count.0,
        "uptime": db::now() - state.started_at,
    }))
}

/// Monitoring is a placeholder in v1: the config is stored, nothing connects.
async fn monitor_config(
    axum::extract::State(state): axum::extract::State<AppState>,
) -> axum::Json<serde_json::Value> {
    let raw = db::get_setting(&state.db, "monitor_config")
        .await
        .ok()
        .flatten()
        .unwrap_or_else(|| "{}".to_string());
    let value: serde_json::Value = serde_json::from_str(&raw).unwrap_or_else(|_| serde_json::json!({}));
    axum::Json(serde_json::json!({
        "config": value,
        "implemented": false,
        "note": "监控模块本期仅保留入口与配置骨架",
    }))
}

async fn save_monitor_config(
    axum::extract::State(state): axum::extract::State<AppState>,
    axum::Json(body): axum::Json<serde_json::Value>,
) -> axum::Json<serde_json::Value> {
    let serialized = serde_json::to_string(&body).unwrap_or_else(|_| "{}".to_string());
    let _ = db::set_setting(&state.db, "monitor_config", &serialized).await;
    axum::Json(serde_json::json!({ "success": true }))
}
