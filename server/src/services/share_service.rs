use anyhow::{anyhow, Result};
use serde::Serialize;
use sqlx::PgPool;
use uuid::Uuid;
use chrono::{Duration, Utc};

use crate::models::share::Share;

#[derive(Serialize)]
pub struct ShareInfo {
    pub id: i32,
    pub file_path: String,
    pub app_type: String,
    pub token: String,
    pub url: Option<String>,
    pub expires_at: Option<String>,
    pub burn_after_read: bool,
    pub max_views: Option<i32>,
    pub view_count: i32,
    pub created_at: String,
}

impl From<Share> for ShareInfo {
    fn from(s: Share) -> Self {
        ShareInfo {
            id: s.id,
            file_path: s.file_path,
            app_type: s.app_type,
            token: s.token,
            url: None,
            expires_at: s.expires_at.map(|d| d.to_rfc3339()),
            burn_after_read: s.burn_after_read,
            max_views: s.max_views,
            view_count: s.view_count,
            created_at: s.created_at.to_rfc3339(),
        }
    }
}

#[derive(Serialize)]
pub struct SharedFileInfo {
    pub file_path: String,
    pub app_type: String,
    pub is_valid: bool,
}

pub async fn create_share(
    pool: &PgPool,
    user_id: i32,
    file_path: &str,
    app_type: &str,
    expires_in_hours: Option<i64>,
    burn_after_read: bool,
    max_views: Option<i32>,
) -> Result<Share> {
    let token: String = Uuid::new_v4()
        .to_string()
        .replace('-', "")
        .chars()
        .take(16)
        .collect();

    let expires_at = expires_in_hours.map(|h| Utc::now() + Duration::hours(h));

    let share = sqlx::query_as::<_, Share>(
        r#"INSERT INTO shares
           (user_id, file_path, app_type, token, expires_at, burn_after_read, max_views)
           VALUES ($1, $2, $3, $4, $5, $6, $7)
           RETURNING *"#,
    )
    .bind(user_id)
    .bind(file_path)
    .bind(app_type)
    .bind(&token)
    .bind(expires_at)
    .bind(burn_after_read)
    .bind(max_views)
    .fetch_one(pool)
    .await?;

    Ok(share)
}

pub async fn list_user_shares(pool: &PgPool, user_id: i32) -> Result<Vec<ShareInfo>> {
    let shares = sqlx::query_as::<_, Share>(
        "SELECT * FROM shares WHERE user_id = $1 ORDER BY created_at DESC",
    )
    .bind(user_id)
    .fetch_all(pool)
    .await?;

    Ok(shares.into_iter().map(ShareInfo::from).collect())
}

pub async fn delete_share(pool: &PgPool, share_id: i32, user_id: i32) -> Result<()> {
    let share = sqlx::query_as::<_, Share>("SELECT * FROM shares WHERE id = $1")
        .bind(share_id)
        .fetch_optional(pool)
        .await?
        .ok_or_else(|| anyhow!("Share not found"))?;

    if share.user_id != user_id {
        return Err(anyhow!("Not authorized"));
    }

    sqlx::query("DELETE FROM shares WHERE id = $1")
        .bind(share_id)
        .execute(pool)
        .await?;

    Ok(())
}

pub async fn access_share(pool: &PgPool, token: &str) -> Result<SharedFileInfo> {
    let share = sqlx::query_as::<_, Share>("SELECT * FROM shares WHERE token = $1")
        .bind(token)
        .fetch_optional(pool)
        .await?
        .ok_or_else(|| anyhow!("Share not found"))?;

    if !share.is_valid() {
        return Err(anyhow!("Share has expired or reached view limit"));
    }

    // Increment view count
    sqlx::query("UPDATE shares SET view_count = view_count + 1 WHERE id = $1")
        .bind(share.id)
        .execute(pool)
        .await?;

    Ok(SharedFileInfo {
        file_path: share.file_path,
        app_type: share.app_type,
        is_valid: true,
    })
}

