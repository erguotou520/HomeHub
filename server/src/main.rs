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

    // Runtime settings live in SQLite so the admin UI can change them; the
    // YAML copy provides the defaults on first boot.
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

    let detector = services::ml::build(&config.runtime.ml);
    let queue = TaskQueue::new(pool.clone(), registry.clone(), store.clone(), detector);

    let audit_writer = services::audit::spawn(pool.clone(), &config);

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
    };

    let app = Router::new()
        // ── admin API ──
        .merge(handlers::admin::routes())
        .merge(handlers::admin::maintenance_routes())
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

/// Serve the built admin SPA (single-container deployment).
///
/// Set `ADMIN_DIST` to the `admin/dist` directory; when it is missing the
/// server stays API-only and unknown paths return 404.
async fn spa_fallback(uri: axum::http::Uri) -> axum::response::Response {
    use axum::http::{header, StatusCode};
    use axum::response::IntoResponse;

    let dist = std::path::PathBuf::from(
        std::env::var("ADMIN_DIST").unwrap_or_else(|_| "../admin/dist".to_string()),
    );
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
                [(header::CONTENT_TYPE, mime.to_string()), (header::CACHE_CONTROL, cache.into())],
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

/// Read runtime settings from SQLite, falling back to the YAML defaults.
async fn load_runtime_settings(
    pool: &db::Db,
    config: &Config,
) -> anyhow::Result<RuntimeSettings> {
    match db::get_setting(pool, "runtime_settings").await? {
        Some(raw) => match serde_json::from_str::<RuntimeSettings>(&raw) {
            Ok(s) => Ok(s),
            Err(e) => {
                tracing::warn!("stored runtime settings are invalid ({}), using defaults", e);
                Ok(config.runtime.clone())
            }
        },
        None => {
            let serialized = serde_json::to_string(&config.runtime)?;
            db::set_setting(pool, "runtime_settings", &serialized).await?;
            Ok(config.runtime.clone())
        }
    }
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
