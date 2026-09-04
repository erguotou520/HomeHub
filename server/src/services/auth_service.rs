use anyhow::{anyhow, Result};
use sqlx::PgPool;

use crate::middleware::auth;
use crate::models::user::User;

pub async fn login(
    pool: &PgPool,
    username: &str,
    password: &str,
    jwt_secret: &str,
) -> Result<(String, User)> {
    let user = sqlx::query_as::<_, User>("SELECT * FROM users WHERE username = $1")
        .bind(username)
        .fetch_optional(pool)
        .await?
        .ok_or_else(|| anyhow!("Invalid credentials"))?;

    if !bcrypt::verify(password, &user.password_hash)? {
        return Err(anyhow!("Invalid credentials"));
    }

    let token = auth::create_token(user.id, &user.username, &user.role, jwt_secret)?;
    Ok((token, user))
}

pub async fn register(
    pool: &PgPool,
    username: &str,
    password: &str,
    role: &str,
) -> Result<User> {
    let existing: Option<User> = sqlx::query_as::<_, User>(
        "SELECT * FROM users WHERE username = $1",
    )
    .bind(username)
    .fetch_optional(pool)
    .await?;

    if existing.is_some() {
        return Err(anyhow!("Username already exists"));
    }

    let password_hash = bcrypt::hash(password, bcrypt::DEFAULT_COST)?;
    let role = if role == "admin" { "admin" } else { "user" };

    let user = sqlx::query_as::<_, User>(
        "INSERT INTO users (username, password_hash, role) VALUES ($1, $2, $3) RETURNING *",
    )
    .bind(username)
    .bind(password_hash)
    .bind(role)
    .fetch_one(pool)
    .await?;

    Ok(user)
}

