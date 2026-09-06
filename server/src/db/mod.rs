//! SQLite connection pool setup and helpers.

use sqlx::sqlite::{SqliteConnectOptions, SqlitePoolOptions};
use sqlx::{ SqlitePool};
use std::str::FromStr;
use std::time::Duration;

use crate::config::Config;

pub type Db = SqlitePool;

/// Open the pool, apply migrations and enable WAL.
pub async fn open(config: &Config) -> anyhow::Result<Db> {
    let url = config.database_url();
    if let Some(parent) = config.db_path().parent() {
        if !parent.as_os_str().is_empty() {
            std::fs::create_dir_all(parent).ok();
        }
    }

    let options = SqliteConnectOptions::from_str(&url)?
        .busy_timeout(Duration::from_secs(30))
        .foreign_keys(true)
        .journal_mode(sqlx::sqlite::SqliteJournalMode::Wal)
        .synchronous(sqlx::sqlite::SqliteSynchronous::Normal)
        .create_if_missing(true);

    let pool = SqlitePoolOptions::new()
        .max_connections(8)
        .acquire_timeout(Duration::from_secs(30))
        .connect_with(options)
        .await?;

    sqlx::migrate!("./migrations").run(&pool).await?;
    Ok(pool)
}

/// Tiny helper: read a JSON blob from the `settings` table.
pub async fn get_setting(db: &Db, key: &str) -> anyhow::Result<Option<String>> {
    let row: Option<(String,)> = sqlx::query_as("SELECT value FROM settings WHERE key = ?")
        .bind(key)
        .fetch_optional(db)
        .await?;
    Ok(row.map(|r| r.0))
}

pub async fn set_setting(db: &Db, key: &str, value: &str) -> anyhow::Result<()> {
    sqlx::query("INSERT INTO settings (key, value) VALUES (?, ?) \
                 ON CONFLICT(key) DO UPDATE SET value = excluded.value")
        .bind(key)
        .bind(value)
        .execute(db)
        .await?;
    Ok(())
}

/// Current unix timestamp in seconds.
pub fn now() -> i64 {
    chrono::Utc::now().timestamp()
}
