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
    /** "photo" | "video" — server serialises media_kind. */
    @SerializedName("media_kind") val mediaKind: String = "photo",
    /** Video length in milliseconds; null for photos or when ffprobe is absent. */
    @SerializedName("duration_ms") val durationMs: Long? = null,
    @SerializedName("video_codec") val videoCodec: String? = null,
    val url: String = "",
    @SerializedName("thumb_url") val thumbUrl: String = "",
    val tags: List<PhotoTag> = emptyList()
) {
    val isVideo: Boolean get() = mediaKind == "video"
}

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
    @SerializedName("skipped") val skipped: Boolean = false,
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

/** One step of a file-level image edit (`/api/images/transform/...`). */
data class ImageOp(
    val op: String,
    val angle: Int? = null,
    val width: Int? = null,
    val height: Int? = null,
    val quality: Int? = null
)

data class TransformRequest(val ops: List<ImageOp>)

data class TransformResult(
    val width: Int = 0,
    val height: Int = 0,
    val size: Long = 0,
    val lossless: Boolean = false
)

data class TransformResponse(
    val success: Boolean = false,
    val result: TransformResult? = null,
    @SerializedName("has_original") val hasOriginal: Boolean = false
)

data class ImageExif(
    val width: Int = 0,
    val height: Int = 0,
    val size: Long = 0,
    val orientation: Int = 1,
    @SerializedName("has_original") val hasOriginal: Boolean = false
)

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
    /** 过滤后的总条数：整个结果集，不是这一页。 */
    val total: Long = 0,
    @SerializedName("has_more") val hasMore: Boolean = false,
    /** 本页最后一条的 `(taken_at, id)`，原样回传作为下一页的游标。 */
    @SerializedName("next_before") val nextBefore: Long? = null,
    @SerializedName("next_before_id") val nextBeforeId: Long? = null
) {
    /**
     * 游标要么齐、要么当没有。
     *
     * 半截游标当成有效的话，下一页会从头开始重复发第一页 —— 请求全部成功、界面
     * 看着正常，内容却停在原处。
     */
    val cursor: Pair<Long, Long>?
        get() {
            val t = nextBefore ?: return null
            val i = nextBeforeId ?: return null
            return t to i
        }
}

/** Natural-language semantic search result (`/api/photos/semantic`). */
data class SemanticResponse(
    val items: List<PhotoItem> = emptyList(),
    val total: Long = 0,
    /** photo_id -> similarity string (e.g. "0.557"). */
    val scores: Map<String, String> = emptyMap()
)

data class TimelineResponse(
    val groups: List<TimelineGroup> = emptyList(),
    /**
     * Whether the server has more photos older than [nextBefore]. False for a
     * client that did not ask for a page (`limit` absent): the server then
     * answers with the whole library in one body.
     */
    @SerializedName("has_more") val hasMore: Boolean = false,
    /** `(taken_at, id)` of this page's oldest item — feed back as the next cursor. */
    @SerializedName("next_before") val nextBefore: Long? = null,
    @SerializedName("next_before_id") val nextBeforeId: Long? = null
) {
    /** Null when there is nothing to continue from. */
    val cursor: Pair<Long, Long>?
        get() {
            val takenAt = nextBefore ?: return null
            val id = nextBeforeId ?: return null
            return takenAt to id
        }
}
data class TreeResponse(val groups: List<TreeGroup> = emptyList())
/**
 * 分类列表的一页。
 *
 * `has_more` / `next_*` 只在客户端带了 `limit` 时才有内容 —— 不带 limit 的调用
 * （还没升级的服务端也算）会一次给全部，`hasMore` 便一直是 false。
 *
 * 游标是**三元组**：同一个标签会同时以 object 和 scene 两种 kind 出现（真库
 * 455 条里有 11 个），只按 `(照片数, 标签)` 定序时这些行并列，游标会在并列处
 * 漏掉一条。
 */
data class TagsResponse(
    val tags: List<TagSummary> = emptyList(),
    @SerializedName("has_more") val hasMore: Boolean = false,
    @SerializedName("next_count") val nextCount: Long? = null,
    @SerializedName("next_tag") val nextTag: String? = null,
    @SerializedName("next_kind") val nextKind: String? = null
) {
    /** 三个字段齐了才算游标；半截一律当没有。 */
    val cursor: TagCursor?
        get() {
            val count = nextCount ?: return null
            val tag = nextTag ?: return null
            val kind = nextKind ?: return null
            return TagCursor(count, tag, kind)
        }
}

data class TagCursor(val count: Long, val tag: String, val kind: String)

/** 人物列表的一页。`id` 在 person_groups 里唯一，所以二元组就够定序。 */
data class PeopleResponse(
    val people: List<PersonGroup> = emptyList(),
    @SerializedName("has_more") val hasMore: Boolean = false,
    @SerializedName("next_count") val nextCount: Long? = null,
    @SerializedName("next_id") val nextId: Long? = null
) {
    val cursor: PersonCursor?
        get() {
            val count = nextCount ?: return null
            val id = nextId ?: return null
            return PersonCursor(count, id)
        }
}

data class PersonCursor(val count: Long, val id: Long)
data class GeoResponse(val points: List<GeoPoint> = emptyList())

/** `GET /api/geo/reverse` — place name for a map cluster centre. */
data class GeoReverseResponse(
    /** "北京市 东城区 …"; null when the provider could not resolve it. */
    @SerializedName("label") val label: String? = null,
    @SerializedName("cached") val cached: Boolean = false
)
data class SearchResponse(val hits: List<SearchHit> = emptyList())
data class DuplicatesResponse(val groups: List<DuplicateGroup> = emptyList())
data class TrashResponse(val entries: List<TrashEntry> = emptyList())
data class DocumentResponse(val content: String = "")

data class UploadOffsetResponse(val offset: Long = 0)

data class UploadCompleteRequest(
    val dir: String,
    val path: String = "",
    val name: String,
    val total: Long? = null,
    /**
     * "skip" = discard when a duplicate exists, "keep" = store both copies.
     *
     * The wire name is snake_case like the rest of the API
     * (`server/src/handlers/upload.rs` deserialises `on_duplicate`); sending
     * `onDuplicate` made serde drop the field and silently fall back to "keep",
     * so the user's duplicate policy never took effect.
     */
    @SerializedName("on_duplicate") val onDuplicate: String = "keep"
)
