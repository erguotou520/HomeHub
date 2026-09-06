package me.erguotou.homehub.data

import com.google.gson.annotations.SerializedName

// ─────────────────────────────── album ────────────────────────────────

data class PhotoTag(
    val tag: String,
    val kind: String,
    val confidence: Double = 0.0
)

data class PhotoItem(
    val id: Long,
    @SerializedName("dir_id") val dirId: Long,
    @SerializedName("dir_name") val dirName: String,
    @SerializedName("rel_path") val relPath: String,
    val name: String,
    @SerializedName("taken_at") val takenAt: Long = 0,
    val width: Int? = null,
    val height: Int? = null,
    val size: Long = 0,
    val orientation: Int = 1,
    @SerializedName("gps_lat") val gpsLat: Double? = null,
    @SerializedName("gps_lng") val gpsLng: Double? = null,
    @SerializedName("camera_make") val cameraMake: String? = null,
    @SerializedName("camera_model") val cameraModel: String? = null,
    val url: String = "",
    @SerializedName("thumb_url") val thumbUrl: String = "",
    val tags: List<PhotoTag> = emptyList()
)

data class TimelineGroup(
    val key: String,
    val label: String,
    val year: Int,
    val month: Int? = null,
    val count: Long = 0,
    val items: List<PhotoItem> = emptyList()
)

data class TreeGroup(
    @SerializedName("dir_id") val dirId: Long,
    @SerializedName("dir_name") val dirName: String,
    val path: String,
    val count: Long = 0,
    val items: List<PhotoItem> = emptyList()
)

data class TagSummary(
    val tag: String,
    val kind: String,
    @SerializedName("photo_count") val photoCount: Long = 0,
    @SerializedName("cover_url") val coverUrl: String? = null
)

data class PersonGroup(
    val id: Long,
    val name: String? = null,
    @SerializedName("face_count") val faceCount: Long = 0,
    @SerializedName("photo_count") val photoCount: Long = 0,
    @SerializedName("cover_url") val coverUrl: String? = null
)

data class GeoPoint(
    val lat: Double,
    val lng: Double,
    val count: Long = 0,
    @SerializedName("thumb_url") val thumbUrl: String? = null,
    @SerializedName("photo_ids") val photoIds: List<Long> = emptyList()
)

data class PhotoDetail(
    val id: Long,
    @SerializedName("dir_name") val dirName: String,
    @SerializedName("rel_path") val relPath: String,
    @SerializedName("taken_at") val takenAt: Long = 0,
    val width: Int? = null,
    val height: Int? = null,
    val size: Long = 0,
    @SerializedName("gps_lat") val gpsLat: Double? = null,
    @SerializedName("gps_lng") val gpsLng: Double? = null,
    @SerializedName("camera_make") val cameraMake: String? = null,
    @SerializedName("camera_model") val cameraModel: String? = null,
    val url: String = "",
    val tags: List<PhotoTag> = emptyList()
)

// ─────────────────────────────── files ────────────────────────────────

data class FileEntry(
    val name: String,
    val path: String,
    @SerializedName("is_dir") val isDir: Boolean,
    val size: Long? = null,
    val modified: Long? = null,
    @SerializedName("mime_type") val mimeType: String? = null,
    @SerializedName("media_kind") val mediaKind: String = "file"
)

data class DirEntry(
    val id: Long,
    val name: String,
    val marks: List<String> = emptyList(),
    @SerializedName("is_dir") val isDir: Boolean = true
)

data class ListFilesResponse(
    val entries: List<FileEntry> = emptyList(),
    val total: Int = 0,
    val dir: String = "",
    val path: String = ""
)

data class ListDirsResponse(val entries: List<DirEntry> = emptyList())

data class UploadedFile(
    val name: String,
    val size: Long,
    @SerializedName("duplicate_of") val duplicateOf: String? = null,
    val queued: Boolean = false
)

data class UploadResponse(
    val uploaded: List<UploadedFile> = emptyList(),
    val count: Int = 0
)

// ──────────────────────────────── trash ───────────────────────────────

data class TrashEntry(
    val id: Long,
    @SerializedName("dir_name") val dirName: String,
    @SerializedName("rel_path") val relPath: String,
    @SerializedName("trash_path") val trashPath: String,
    @SerializedName("is_dir") val isDir: Int = 0,
    val size: Long = 0,
    @SerializedName("trashed_at") val trashedAt: Long = 0,
    @SerializedName("purged_due") val purgedDue: Long = 0
)

data class TrashStats(
    val count: Long = 0,
    val bytes: Long = 0,
    @SerializedName("retention_days") val retentionDays: Int = 30
)

// ─────────────────────────────── search ───────────────────────────────

data class SearchHit(
    val ftype: String,
    @SerializedName("ref_id") val refId: String,
    val title: String,
    val snippet: String? = null,
    @SerializedName("photo_id") val photoId: Long? = null,
    @SerializedName("rel_path") val relPath: String? = null,
    @SerializedName("thumb_url") val thumbUrl: String? = null
)

// ─────────────────────────────── misc ─────────────────────────────────

data class Health(val status: String, val version: String, val photos: Long = 0)

data class MonitorConfig(
    @SerializedName("frigate_url") val frigateUrl: String? = null,
    val implemented: Boolean = false,
    val note: String? = null
)

data class SimpleResponse(val success: Boolean = false)

data class RotateRequest(val angle: Int)

data class RenameRequest(val name: String)

data class CopyMoveRequest(
    val op: String,
    @SerializedName("to_dir") val toDir: String,
    @SerializedName("to_path") val toPath: String
)

data class DuplicateGroup(
    val fingerprint: String,
    val reason: String,
    val items: List<PhotoItem> = emptyList()
)

// ──────────────────────── typed response envelopes ────────────────────

data class PhotoListResponse(
    val items: List<PhotoItem> = emptyList(),
    val total: Long = 0
)

data class TimelineResponse(val groups: List<TimelineGroup> = emptyList())
data class TreeResponse(val groups: List<TreeGroup> = emptyList())
data class TagsResponse(val tags: List<TagSummary> = emptyList())
data class PeopleResponse(val people: List<PersonGroup> = emptyList())
data class GeoResponse(val points: List<GeoPoint> = emptyList())
data class SearchResponse(val hits: List<SearchHit> = emptyList())
data class DuplicatesResponse(val groups: List<DuplicateGroup> = emptyList())
data class TrashResponse(val entries: List<TrashEntry> = emptyList())
data class RotateResponse(val photo: PhotoItem)
data class DocumentResponse(val content: String = "")

data class UploadOffsetResponse(val offset: Long = 0)

data class UploadCompleteRequest(
    val dir: String,
    val path: String = "",
    val name: String,
    val total: Long? = null
)
