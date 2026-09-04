use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};

#[derive(Clone, Debug, sqlx::FromRow, Serialize, Deserialize)]
pub struct Share {
    pub id: i32,
    pub user_id: i32,
    pub file_path: String,
    pub app_type: String,
    pub token: String,
    pub expires_at: Option<DateTime<Utc>>,
    pub burn_after_read: bool,
    pub max_views: Option<i32>,
    pub view_count: i32,
    pub created_at: DateTime<Utc>,
}

impl Share {
    pub fn is_valid(&self) -> bool {
        // Check expiration
        if let Some(expires_at) = self.expires_at {
            if expires_at < Utc::now() {
                return false;
            }
        }

        // Check max views
        if let Some(max) = self.max_views {
            if self.view_count >= max {
                return false;
            }
        }

        // Check burn after read
        if self.burn_after_read && self.view_count > 0 {
            return false;
        }

        true
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use chrono::Duration;

    fn make_share(
        expires_at: Option<DateTime<Utc>>,
        burn_after_read: bool,
        max_views: Option<i32>,
        view_count: i32,
    ) -> Share {
        Share {
            id: 1,
            user_id: 1,
            file_path: "/test/file.txt".to_string(),
            app_type: "documents".to_string(),
            token: "abc123".to_string(),
            expires_at,
            burn_after_read,
            max_views,
            view_count,
            created_at: Utc::now(),
        }
    }

    #[test]
    fn test_valid_share() {
        let share = make_share(None, false, None, 0);
        assert!(share.is_valid());
    }

    #[test]
    fn test_expired_share() {
        let share = make_share(
            Some(Utc::now() - Duration::hours(1)),
            false,
            None,
            0,
        );
        assert!(!share.is_valid());
    }

    #[test]
    fn test_not_yet_expired_share() {
        let share = make_share(
            Some(Utc::now() + Duration::hours(24)),
            false,
            None,
            5,
        );
        assert!(share.is_valid());
    }

    #[test]
    fn test_burn_after_read_unused() {
        let share = make_share(None, true, None, 0);
        assert!(share.is_valid());
    }

    #[test]
    fn test_burn_after_read_used() {
        let share = make_share(None, true, None, 1);
        assert!(!share.is_valid());
    }

    #[test]
    fn test_max_views_not_reached() {
        let share = make_share(None, false, Some(5), 4);
        assert!(share.is_valid());
    }

    #[test]
    fn test_max_views_reached() {
        let share = make_share(None, false, Some(5), 5);
        assert!(!share.is_valid());
    }

    #[test]
    fn test_max_views_exceeded() {
        let share = make_share(None, false, Some(3), 10);
        assert!(!share.is_valid());
    }
}
