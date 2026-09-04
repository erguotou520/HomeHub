use serde::Deserialize;
use std::fs;
use std::path::Path;

#[derive(Debug, Clone, Deserialize)]
pub struct Config {
    pub global: GlobalConfig,
    pub apps: AppsConfig,
    #[serde(default)]
    pub database: DatabaseConfig,
}

#[derive(Debug, Clone, Deserialize)]
pub struct GlobalConfig {
    #[serde(rename = "web-url")]
    pub web_url: String,
    #[serde(rename = "jwt-secret", default = "default_jwt_secret")]
    pub jwt_secret: String,
}

fn default_jwt_secret() -> String {
    "change-me-in-production".to_string()
}

#[derive(Debug, Clone, Deserialize)]
pub struct AppsConfig {
    #[serde(default)]
    pub album: Vec<String>,
    #[serde(default)]
    pub documents: Vec<String>,
    #[serde(default)]
    pub videos: Vec<String>,
    #[serde(default)]
    pub music: Vec<String>,
}

#[derive(Debug, Clone, Deserialize)]
pub struct DatabaseConfig {
    #[serde(default = "default_db_host")]
    pub host: String,
    #[serde(default = "default_db_port")]
    pub port: u16,
    #[serde(default = "default_db_name")]
    pub name: String,
    #[serde(default = "default_db_user")]
    pub user: String,
    #[serde(default)]
    pub password: String,
}

fn default_db_host() -> String {
    "localhost".to_string()
}
fn default_db_port() -> u16 {
    5432
}
fn default_db_name() -> String {
    "home_nas".to_string()
}
fn default_db_user() -> String {
    "postgres".to_string()
}

impl Default for DatabaseConfig {
    fn default() -> Self {
        Self {
            host: default_db_host(),
            port: default_db_port(),
            name: default_db_name(),
            user: default_db_user(),
            password: String::new(),
        }
    }
}

/// Parsed app path entry: display name -> real filesystem path
#[derive(Debug, Clone)]
pub struct AppPath {
    pub name: String,
    pub path: String,
}

impl Config {
    pub fn load<P: AsRef<Path>>(path: P) -> anyhow::Result<Self> {
        let content = fs::read_to_string(path)?;
        let config: Config = serde_yaml::from_str(&content)?;
        Ok(config)
    }

    pub fn database_url(&self) -> String {
        format!(
            "postgres://{}:{}@{}:{}/{}",
            self.database.user,
            self.database.password,
            self.database.host,
            self.database.port,
            self.database.name
        )
    }

    /// Parse a list of "name:path" strings into AppPath entries
    pub fn parse_app_paths(paths: &[String]) -> Vec<AppPath> {
        paths
            .iter()
            .filter_map(|s| {
                let s = s.trim();
                if s.is_empty() {
                    return None;
                }
                if let Some((name, path)) = s.split_once(':') {
                    Some(AppPath {
                        name: name.trim().to_string(),
                        path: path.trim().to_string(),
                    })
                } else {
                    let path = s.trim_end_matches('/');
                    let name = path.rsplit('/').next().unwrap_or(path);
                    Some(AppPath {
                        name: name.to_string(),
                        path: s.to_string(),
                    })
                }
            })
            .collect()
    }

    /// Get all configured paths for the given module name
    pub fn get_app_paths(&self, module: &str) -> Vec<AppPath> {
        let raw = match module {
            "album" => &self.apps.album,
            "videos" => &self.apps.videos,
            "music" => &self.apps.music,
            "documents" => &self.apps.documents,
            _ => return vec![],
        };
        Self::parse_app_paths(raw)
    }

    /// Resolve a virtual path to a real filesystem path.
    /// Virtual path format: "<sub_name>/<relative_path...>"
    /// Returns None if unresolvable.
    pub fn resolve_path(&self, module: &str, virtual_path: &str) -> Option<std::path::PathBuf> {
        let paths = self.get_app_paths(module);
        if paths.is_empty() {
            return None;
        }

        let virtual_path = virtual_path.trim_start_matches('/');

        for app_path in &paths {
            let prefix = format!("{}/", app_path.name);
            if virtual_path.starts_with(&prefix) {
                let rel = &virtual_path[prefix.len()..];
                return Some(std::path::Path::new(&app_path.path).join(rel));
            }
            if virtual_path == app_path.name {
                return Some(std::path::PathBuf::from(&app_path.path));
            }
        }

        // Single path config: use directly
        if paths.len() == 1 && !virtual_path.is_empty() {
            return Some(std::path::Path::new(&paths[0].path).join(virtual_path));
        }

        None
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_parse_app_paths() {
        let paths = vec![
            "photos:/mnt/nas/photos".to_string(),
            "/mnt/nas/documents".to_string(),
        ];
        let parsed = Config::parse_app_paths(&paths);
        assert_eq!(parsed[0].name, "photos");
        assert_eq!(parsed[0].path, "/mnt/nas/photos");
        assert_eq!(parsed[1].name, "documents");
        assert_eq!(parsed[1].path, "/mnt/nas/documents");
    }

    #[test]
    fn test_resolve_path_with_sub() {
        let config = Config {
            global: GlobalConfig {
                web_url: "http://localhost:8485".to_string(),
                jwt_secret: "secret".to_string(),
            },
            apps: AppsConfig {
                album: vec!["photos:/mnt/nas/photos".to_string()],
                videos: vec![],
                music: vec![],
                documents: vec![],
            },
            database: DatabaseConfig::default(),
        };

        let p = config.resolve_path("album", "photos/2024/vacation.jpg");
        assert_eq!(
            p.unwrap().to_string_lossy(),
            "/mnt/nas/photos/2024/vacation.jpg"
        );
    }
}
