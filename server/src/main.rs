#![allow(dead_code)]

mod config;
mod handlers;
mod middleware;
mod models;
mod services;
mod utils;

use axum::{
    Router,
    routing::{delete, get, patch, post, put},
};
use sqlx::postgres::PgPoolOptions;
use std::sync::Arc;
use tower_http::{cors::CorsLayer, trace::TraceLayer};
use tracing_subscriber::{layer::SubscriberExt, util::SubscriberInitExt};

use config::Config;

/// Shared application state injected into every handler via axum State extractor
#[derive(Clone)]
pub struct AppState {
    pub pool: sqlx::PgPool,
    pub config: Arc<Config>,
    pub jwt_secret: String,
    pub web_url: String,
}

#[tokio::main]
async fn main() -> anyhow::Result<()> {
    // Initialize structured logging
    tracing_subscriber::registry()
        .with(tracing_subscriber::EnvFilter::new(
            std::env::var("RUST_LOG").unwrap_or_else(|_| "info".into()),
        ))
        .with(tracing_subscriber::fmt::layer())
        .init();

    // Load configuration
    let config_path = std::env::var("CONFIG_PATH").unwrap_or_else(|_| "../config.yaml".into());
    let config = Config::load(&config_path).expect("Failed to load config");
    tracing::info!("Loaded configuration from {}", config_path);

    // Build database connection pool
    let db_url = std::env::var("DATABASE_URL").unwrap_or_else(|_| config.database_url());
    let pool = PgPoolOptions::new()
        .max_connections(10)
        .connect(&db_url)
        .await
        .expect("Failed to connect to database");
    tracing::info!("Connected to database");

    // Run pending migrations
    sqlx::migrate!("./migrations")
        .run(&pool)
        .await
        .expect("Failed to run database migrations");
    tracing::info!("Database migrations complete");

    // Ensure at least one admin user exists
    ensure_default_admin(&pool).await;

    let state = AppState {
        pool,
        config: Arc::new(config.clone()),
        jwt_secret: config.global.jwt_secret.clone(),
        web_url: config.global.web_url.clone(),
    };

    // Build router
    let app = Router::new()
        // Auth (public)
        .route("/api/auth/login", post(handlers::auth::login))
        // Auth (protected)
        .route("/api/auth/me", get(handlers::auth::me))
        .route("/api/auth/register", post(handlers::auth::register))
        // Files – list & upload
        .route("/api/files/:module", get(handlers::files::list_module_root))
        .route("/api/files/:module/*path", get(handlers::files::list_files))
        .route("/api/files/:module/*path", post(handlers::files::upload_file))
        .route("/api/files/:module/*path", patch(handlers::files::rename_file))
        .route("/api/files/:module/*path", delete(handlers::files::delete_file))
        // Documents (read / write content)
        .route("/api/documents/:module/*path", get(handlers::files::read_document))
        .route("/api/documents/:module/*path", put(handlers::files::write_document))
        // Mkdir
        .route("/api/mkdir/:module", post(handlers::files::create_dir_root))
        .route("/api/mkdir/:module/*path", post(handlers::files::create_dir))
        // Media (stream, thumbnail, lyrics, info) – :module = album|videos|music|documents
        .route("/api/media/stream/:module/*path", get(handlers::media::stream_media))
        .route("/api/media/thumbnail/:module/*path", get(handlers::media::get_thumbnail))
        .route("/api/media/lyrics/:module/*path", get(handlers::media::get_lyrics))
        .route("/api/media/info/:module/*path", get(handlers::media::get_media_info))
        // Shares
        .route("/api/shares", post(handlers::share::create_share))
        .route("/api/shares", get(handlers::share::list_shares))
        .route("/api/shares/:id", delete(handlers::share::delete_share))
        .route("/s/:token", get(handlers::share::access_share))
        // Users (admin)
        .route("/api/users", get(handlers::users::list_users))
        .route("/api/users", post(handlers::auth::register))
        .route("/api/users/:id", patch(handlers::users::update_user))
        .route("/api/users/:id", delete(handlers::users::delete_user))
        // Middleware
        .layer(CorsLayer::permissive())
        .layer(TraceLayer::new_for_http())
        .with_state(state);

    let bind_addr = std::env::var("BIND_ADDR").unwrap_or_else(|_| "0.0.0.0:8485".into());
    tracing::info!("Starting server on {}", bind_addr);
    let listener = tokio::net::TcpListener::bind(&bind_addr).await?;
    axum::serve(listener, app).await?;
    Ok(())
}

/// Create a default admin user if no users exist
async fn ensure_default_admin(pool: &sqlx::PgPool) {
    let count: (i64,) = sqlx::query_as("SELECT COUNT(*) FROM users")
        .fetch_one(pool)
        .await
        .unwrap_or((0,));

    if count.0 == 0 {
        let hash = bcrypt::hash("admin123", bcrypt::DEFAULT_COST).unwrap();
        sqlx::query(
            "INSERT INTO users (username, password_hash, role) VALUES ('admin', $1, 'admin')",
        )
        .bind(hash)
        .execute(pool)
        .await
        .ok();
        tracing::info!("Created default admin user (username: admin, password: admin123)");
    }
}
