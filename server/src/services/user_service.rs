use anyhow::{anyhow, Result};
use serde::Serialize;
use sqlx::PgPool;

use crate::models::user::User;

#[derive(Serialize)]
pub struct UserInfo {
    pub id: i32,
    pub username: String,
    pub role: String,
    pub created_at: String,
}

impl From<User> for UserInfo {
    fn from(u: User) -> Self {
        UserInfo {
            id: u.id,
            username: u.username,
            role: u.role,
            created_at: u.created_at.to_rfc3339(),
        }
    }
}

pub async fn list_users(pool: &PgPool) -> Result<Vec<UserInfo>> {
    let users = sqlx::query_as::<_, User>("SELECT * FROM users ORDER BY id")
        .fetch_all(pool)
        .await?;
    Ok(users.into_iter().map(UserInfo::from).collect())
}

pub async fn update_user(
    pool: &PgPool,
    user_id: i32,
    role: Option<&str>,
    password: Option<&str>,
) -> Result<User> {
    let _ = sqlx::query_as::<_, User>("SELECT * FROM users WHERE id = $1")
        .bind(user_id)
        .fetch_optional(pool)
        .await?
        .ok_or_else(|| anyhow!("User not found"))?;

    if let Some(role) = role {
        let role = if role == "admin" { "admin" } else { "user" };
        sqlx::query("UPDATE users SET role = $1, updated_at = NOW() WHERE id = $2")
            .bind(role)
            .bind(user_id)
            .execute(pool)
            .await?;
    }

    if let Some(pwd) = password {
        let hash = bcrypt::hash(pwd, bcrypt::DEFAULT_COST)?;
        sqlx::query("UPDATE users SET password_hash = $1, updated_at = NOW() WHERE id = $2")
            .bind(hash)
            .bind(user_id)
            .execute(pool)
            .await?;
    }

    let updated = sqlx::query_as::<_, User>("SELECT * FROM users WHERE id = $1")
        .bind(user_id)
        .fetch_one(pool)
        .await?;

    Ok(updated)
}

pub async fn delete_user(pool: &PgPool, user_id: i32) -> Result<()> {
    sqlx::query("DELETE FROM users WHERE id = $1")
        .bind(user_id)
        .execute(pool)
        .await?;
    Ok(())
}


