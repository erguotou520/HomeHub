use axum::{
    extract::{Multipart, Path, Query, State},
    http::StatusCode,
    response::IntoResponse,
    Json,
};
use serde::{Deserialize, Serialize};
use std::path::PathBuf;
use tokio::{fs, io::AsyncWriteExt};

use crate::middleware::auth::Claims;
use crate::services::file_service::{self, FileEntry};
use crate::AppState;

// ─── Response types ──────────────────────────────────────────────────────────

#[derive(Deserialize)]
pub struct ListQuery {
    pub page: Option<u32>,
    pub limit: Option<u32>,
}

#[derive(Serialize)]
pub struct ListResponse {
    pub entries: Vec<FileEntry>,
    pub total: usize,
    pub path: String,
    pub module: String,
}

// ─── Handlers ────────────────────────────────────────────────────────────────

/// GET /api/files/:module  — list all configured sub-directories for a module
pub async fn list_module_root(
    State(state): State<AppState>,
    Path(module): Path<String>,
    _claims: Claims,
) -> impl IntoResponse {
    let app_paths = state.config.get_app_paths(&module);
    if app_paths.is_empty() {
        return (
            StatusCode::NOT_FOUND,
            Json(serde_json::json!({ "error": "Module not found" })),
        )
            .into_response();
    }

    // Return sub-directory entries (one virtual entry per configured path)
    let entries: Vec<FileEntry> = app_paths
        .iter()
        .map(|ap| FileEntry {
            name: ap.name.clone(),
            path: ap.name.clone(),
            is_dir: true,
            size: None,
            modified: None,
            mime_type: None,
            thumbnail: None,
            metadata: None,
        })
        .collect();

    Json(serde_json::json!({
        "entries": entries,
        "total": entries.len(),
        "path": "",
        "module": module,
    }))
    .into_response()
}

/// GET /api/files/:module/*path  — list contents of a directory
pub async fn list_files(
    State(state): State<AppState>,
    Path((module, vpath)): Path<(String, String)>,
    _query: Query<ListQuery>,
    _claims: Claims,
) -> impl IntoResponse {
    let real_path = match state.config.resolve_path(&module, &vpath) {
        Some(p) => p,
        None => {
            return (
                StatusCode::NOT_FOUND,
                Json(serde_json::json!({ "error": "Path not found" })),
            )
                .into_response()
        }
    };

    match file_service::list_directory(real_path.to_str().unwrap_or_default(), &module).await {
        Ok(mut entries) => {
            // Fix paths: replace real filesystem paths with virtual paths
            // Virtual path = vpath + "/" + entry.name
            let vpath_prefix = if vpath.ends_with('/') {
                vpath.clone()
            } else {
                format!("{}/", vpath)
            };
            for entry in &mut entries {
                entry.path = format!("{}{}", vpath_prefix, entry.name);
            }
            Json(serde_json::json!({
                "entries": entries,
                "total": entries.len(),
                "path": vpath,
                "module": module,
            }))
            .into_response()
        }
        Err(e) => (
            StatusCode::INTERNAL_SERVER_ERROR,
            Json(serde_json::json!({ "error": e.to_string() })),
        )
            .into_response(),
    }
}

/// POST /api/files/:module/*path  — upload a file (multipart/form-data)
pub async fn upload_file(
    State(state): State<AppState>,
    Path((module, vpath)): Path<(String, String)>,
    _claims: Claims,
    mut multipart: Multipart,
) -> impl IntoResponse {
    let dir_path = match state.config.resolve_path(&module, &vpath) {
        Some(p) => p,
        None => {
            return (
                StatusCode::NOT_FOUND,
                Json(serde_json::json!({ "error": "Path not found" })),
            )
                .into_response()
        }
    };

    // Ensure directory exists
    if let Err(e) = fs::create_dir_all(&dir_path).await {
        return (
            StatusCode::INTERNAL_SERVER_ERROR,
            Json(serde_json::json!({ "error": e.to_string() })),
        )
            .into_response();
    }

    let mut uploaded = Vec::new();

    while let Ok(Some(mut field)) = multipart.next_field().await {
        let file_name = field
            .file_name()
            .unwrap_or("upload")
            .to_string()
            // Sanitize: keep only the base filename
            .replace(['/', '\\'], "_")
            .replace("..", "_");

        let file_path = dir_path.join(&file_name);

        let mut file = match fs::File::create(&file_path).await {
            Ok(f) => f,
            Err(e) => {
                return (
                    StatusCode::INTERNAL_SERVER_ERROR,
                    Json(serde_json::json!({ "error": e.to_string() })),
                )
                    .into_response()
            }
        };

        let mut total_bytes: u64 = 0;
        while let Ok(Some(chunk)) = field.chunk().await {
            if let Err(e) = file.write_all(&chunk).await {
                return (
                    StatusCode::INTERNAL_SERVER_ERROR,
                    Json(serde_json::json!({ "error": e.to_string() })),
                )
                    .into_response();
            }
            total_bytes += chunk.len() as u64;
        }

        uploaded.push(serde_json::json!({
            "name": file_name,
            "size": total_bytes,
        }));
    }

    (
        StatusCode::CREATED,
        Json(serde_json::json!({ "uploaded": uploaded })),
    )
        .into_response()
}

/// PATCH /api/files/:module/*path  — rename a file
pub async fn rename_file(
    State(state): State<AppState>,
    Path((module, vpath)): Path<(String, String)>,
    _claims: Claims,
    Json(body): Json<RenameRequest>,
) -> impl IntoResponse {
    let src = match state.config.resolve_path(&module, &vpath) {
        Some(p) => p,
        None => {
            return (
                StatusCode::NOT_FOUND,
                Json(serde_json::json!({ "error": "Source path not found" })),
            )
                .into_response()
        }
    };

    // New name is just a filename, not a path
    let new_name = body.name.replace(['/', '\\'], "_");
    let dest = src
        .parent()
        .unwrap_or(&PathBuf::from("/"))
        .join(&new_name);

    match fs::rename(&src, &dest).await {
        Ok(_) => Json(serde_json::json!({ "success": true, "name": new_name })).into_response(),
        Err(e) => (
            StatusCode::INTERNAL_SERVER_ERROR,
            Json(serde_json::json!({ "error": e.to_string() })),
        )
            .into_response(),
    }
}

#[derive(Deserialize)]
pub struct RenameRequest {
    pub name: String,
}

/// DELETE /api/files/:module/*path  — delete a file or directory
pub async fn delete_file(
    State(state): State<AppState>,
    Path((module, vpath)): Path<(String, String)>,
    _claims: Claims,
) -> impl IntoResponse {
    let full_path = match state.config.resolve_path(&module, &vpath) {
        Some(p) => p,
        None => {
            return (
                StatusCode::NOT_FOUND,
                Json(serde_json::json!({ "error": "Path not found" })),
            )
                .into_response()
        }
    };

    let result = if full_path.is_dir() {
        fs::remove_dir_all(&full_path).await
    } else {
        fs::remove_file(&full_path).await
    };

    match result {
        Ok(_) => Json(serde_json::json!({ "success": true })).into_response(),
        Err(e) => (
            StatusCode::INTERNAL_SERVER_ERROR,
            Json(serde_json::json!({ "error": e.to_string() })),
        )
            .into_response(),
    }
}

/// GET /api/documents/:module/*path  — read document text content
pub async fn read_document(
    State(state): State<AppState>,
    Path((module, vpath)): Path<(String, String)>,
    _claims: Claims,
) -> impl IntoResponse {
    let full_path = match state.config.resolve_path(&module, &vpath) {
        Some(p) => p,
        None => {
            return (
                StatusCode::NOT_FOUND,
                Json(serde_json::json!({ "error": "Path not found" })),
            )
                .into_response()
        }
    };

    match fs::read_to_string(&full_path).await {
        Ok(content) => Json(serde_json::json!({ "content": content })).into_response(),
        Err(e) => (
            StatusCode::INTERNAL_SERVER_ERROR,
            Json(serde_json::json!({ "error": e.to_string() })),
        )
            .into_response(),
    }
}

#[derive(Deserialize)]
pub struct WriteDocumentRequest {
    pub content: String,
}

/// PUT /api/documents/:module/*path  — write document text content
pub async fn write_document(
    State(state): State<AppState>,
    Path((module, vpath)): Path<(String, String)>,
    _claims: Claims,
    Json(body): Json<WriteDocumentRequest>,
) -> impl IntoResponse {
    let full_path = match state.config.resolve_path(&module, &vpath) {
        Some(p) => p,
        None => {
            return (
                StatusCode::NOT_FOUND,
                Json(serde_json::json!({ "error": "Path not found" })),
            )
                .into_response()
        }
    };

    // Create parent directories if needed
    if let Some(parent) = full_path.parent() {
        if let Err(e) = fs::create_dir_all(parent).await {
            return (
                StatusCode::INTERNAL_SERVER_ERROR,
                Json(serde_json::json!({ "error": e.to_string() })),
            )
                .into_response();
        }
    }

    match fs::write(&full_path, &body.content).await {
        Ok(_) => Json(serde_json::json!({ "success": true })).into_response(),
        Err(e) => (
            StatusCode::INTERNAL_SERVER_ERROR,
            Json(serde_json::json!({ "error": e.to_string() })),
        )
            .into_response(),
    }
}

#[derive(Deserialize)]
pub struct CreateDirRequest {
    pub name: String,
}

/// POST /api/mkdir/:module/*path  — create a new directory
pub async fn create_dir(
    State(state): State<AppState>,
    Path((module, vpath)): Path<(String, String)>,
    _claims: Claims,
    Json(body): Json<CreateDirRequest>,
) -> impl IntoResponse {
    let parent = match state.config.resolve_path(&module, &vpath) {
        Some(p) => p,
        None => {
            return (
                StatusCode::NOT_FOUND,
                Json(serde_json::json!({ "error": "Parent path not found" })),
            )
                .into_response()
        }
    };

    let dir_name = body.name.replace(['/', '\\'], "_");
    let new_dir = parent.join(&dir_name);

    match fs::create_dir_all(&new_dir).await {
        Ok(_) => Json(serde_json::json!({ "success": true, "name": dir_name })).into_response(),
        Err(e) => (
            StatusCode::INTERNAL_SERVER_ERROR,
            Json(serde_json::json!({ "error": e.to_string() })),
        )
            .into_response(),
    }
}

/// POST /api/mkdir/:module  — create directory at module root (using name in body)
pub async fn create_dir_root(
    State(state): State<AppState>,
    Path(module): Path<String>,
    _claims: Claims,
    Json(body): Json<CreateDirRequest>,
) -> impl IntoResponse {
    let app_paths = state.config.get_app_paths(&module);
    if app_paths.is_empty() {
        return (
            StatusCode::NOT_FOUND,
            Json(serde_json::json!({ "error": "Module not found" })),
        )
            .into_response();
    }
    // Decode sub_name/rest from body.name or path
    // body.name format: "sub_name/new_dir" or just create under first app path
    let parts: Vec<&str> = body.name.splitn(2, '/').collect();
    let (sub_name, dir_name) = if parts.len() == 2 {
        (parts[0], parts[1])
    } else {
        (".", body.name.as_str())
    };
    let base = app_paths.iter().find(|ap| ap.name == sub_name)
        .or_else(|| app_paths.first());
    let parent = match base {
        Some(ap) => std::path::PathBuf::from(&ap.path),
        None => return (StatusCode::NOT_FOUND,
            Json(serde_json::json!({ "error": "Base path not found" }))).into_response(),
    };
    let safe_name = dir_name.replace(['/', '\\'], "_");
    let new_dir = parent.join(&safe_name);
    match fs::create_dir_all(&new_dir).await {
        Ok(_) => Json(serde_json::json!({ "success": true, "name": safe_name })).into_response(),
        Err(e) => (StatusCode::INTERNAL_SERVER_ERROR,
            Json(serde_json::json!({ "error": e.to_string() }))).into_response(),
    }
}
