//! Path helpers shared by the services.

use std::path::{Component, Path, PathBuf};

/// Join `root` with a relative path, refusing anything that escapes the root.
pub fn safe_join(root: &Path, rel: &str) -> Option<PathBuf> {
    let mut out = root.to_path_buf();
    for comp in Path::new(rel).components() {
        match comp {
            Component::Normal(part) => out.push(part),
            Component::CurDir => {}
            Component::ParentDir => return None,
            Component::RootDir | Component::Prefix(_) => return None,
        }
    }
    Some(out)
}

/// Normalise a relative path to use `/` separators and drop leading `./`.
pub fn normalize_rel(rel: &str) -> String {
    let mut parts: Vec<String> = Vec::new();
    for comp in Path::new(rel).components() {
        match comp {
            Component::Normal(p) => parts.push(p.to_string_lossy().to_string()),
            Component::ParentDir => {
                parts.pop();
            }
            _ => {}
        }
    }
    parts.join("/")
}

/// `mtime:size` change detector.
pub fn fingerprint(mtime: i64, size: u64) -> String {
    format!("{}:{}", mtime, size)
}

/// Lowercase file extension without the dot.
pub fn ext_of(name: &str) -> String {
    Path::new(name)
        .extension()
        .map(|e| e.to_string_lossy().to_lowercase())
        .unwrap_or_default()
}

pub fn is_image(ext: &str) -> bool {
    matches!(ext, "jpg" | "jpeg" | "png" | "webp" | "gif" | "bmp")
}

pub fn is_video(ext: &str) -> bool {
    matches!(
        ext,
        "mp4" | "mkv" | "webm" | "avi" | "mov" | "m4v" | "flv" | "ts"
    )
}

pub fn is_music(ext: &str) -> bool {
    matches!(ext, "mp3" | "flac" | "m4a" | "aac" | "ogg" | "wav" | "opus")
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn refuses_traversal() {
        assert!(safe_join(Path::new("/root"), "../etc/passwd").is_none());
        assert_eq!(
            safe_join(Path::new("/root"), "a/b.jpg").unwrap(),
            PathBuf::from("/root/a/b.jpg")
        );
    }

    #[test]
    fn normalizes() {
        assert_eq!(normalize_rel("./a//b/../c.jpg"), "a/c.jpg");
    }
}
