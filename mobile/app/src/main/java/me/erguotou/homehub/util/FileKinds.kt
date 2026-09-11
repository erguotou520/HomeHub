package me.erguotou.homehub.util

/**
 * How the 文件 tab should present one entry.
 *
 * The server already buckets every listing entry with `media_kind`
 * (`dir` / `image` / `video` / `music` / `file`); that is the primary signal.
 * The residual `file` bucket is refined here by extension so the client can
 * pick a viewer without asking the server again.
 */
enum class FileKind {
    /** Photos — full screen zoomable viewer (the album viewer's image page). */
    IMAGE,

    /** Videos — full screen Media3 player (the album viewer's video page). */
    VIDEO,

    /** Audio — Media3 player without video surface. */
    AUDIO,

    /** Plain text-ish files (txt/md/ini/json/log/code/…) — full screen text viewer. */
    TEXT,

    /** PDF — rendered on device with the platform `PdfRenderer`. */
    PDF,

    /** Word/Excel/PowerPoint (and friends) — text extraction, or hand off. */
    OFFICE,

    /** Everything else (archives, binaries, unknown) — hand off to the system. */
    OTHER;

    /** True when the file tab can render this in-app. */
    val viewable: Boolean
        get() = this != OTHER
}

object FileKinds {

    private val IMAGE = setOf(
        "jpg", "jpeg", "jpe", "jfif", "png", "gif", "webp", "bmp", "heic", "heif",
        "avif", "tif", "tiff"
    )

    private val VIDEO = setOf(
        "mp4", "m4v", "mkv", "webm", "mov", "avi", "wmv", "flv", "ts", "m2ts",
        "mts", "3gp", "3g2", "mpg", "mpeg", "m2v", "rmvb", "rm", "vob", "ogv"
    )

    private val AUDIO = setOf(
        "mp3", "flac", "m4a", "aac", "wav", "ogg", "oga", "opus", "wma", "ape",
        "alac", "aiff", "amr", "mka"
    )

    private val TEXT = setOf(
        "txt", "text", "md", "markdown", "mdx", "rst", "ini", "cfg", "conf", "config",
        "log", "json", "jsonl", "xml", "yaml", "yml", "toml", "csv", "tsv", "properties",
        "env", "sh", "bash", "zsh", "fish", "bat", "cmd", "ps1", "sql", "kt", "kts",
        "java", "py", "js", "mjs", "cjs", "ts", "tsx", "jsx", "html", "htm", "css",
        "scss", "less", "gradle", "pro", "rs", "go", "c", "h", "cc", "cpp", "hpp",
        "rb", "php", "pl", "lua", "swift", "dart", "vue", "svelte", "makefile",
        "dockerfile", "gitignore", "gitattributes", "editorconfig", "patch", "diff",
        "srt", "vtt", "ass", "ssa", "lrc", "nfo", "m3u", "m3u8", "url", "desktop",
        "license", "readme"
    )

    private val PDF = setOf("pdf")

    /** OOXML zips we can pull text/tables out of with `java.util.zip` alone. */
    private val OOXML = setOf("docx", "docm", "xlsx", "xlsm", "pptx", "pptm", "ppsx")

    /** Office family we hand to another app (binary OLE, ODF, WPS, iWork). */
    private val OFFICE_OTHER = setOf(
        "doc", "dot", "xls", "xlt", "ppt", "pot", "pps", "rtf",
        "odt", "ods", "odp", "ott", "ots", "otp",
        "wps", "wpt", "et", "ett", "dps", "dpt",
        "pages", "numbers", "key"
    )

    /** Lowercase extension, "" when the name has none. */
    fun ext(name: String): String = name.substringAfterLast('.', "").lowercase()

    /**
     * Classify an entry. [mediaKind] and [mimeType] come straight from the
     * server listing; either may be missing, so the extension is the fallback.
     */
    fun of(name: String, mimeType: String? = null, mediaKind: String = "file"): FileKind {
        val ext = ext(name)
        // The server's own classification wins: it is what put the file in the
        // album in the first place, so the two modules agree on "this is a photo".
        when (mediaKind) {
            "image" -> return FileKind.IMAGE
            "video" -> return FileKind.VIDEO
            "music" -> return FileKind.AUDIO
        }
        when {
            ext in IMAGE -> return FileKind.IMAGE
            ext in VIDEO -> return FileKind.VIDEO
            ext in AUDIO -> return FileKind.AUDIO
            ext in PDF -> return FileKind.PDF
            ext in OOXML || ext in OFFICE_OTHER -> return FileKind.OFFICE
            ext in TEXT -> return FileKind.TEXT
        }
        // Extension-less files (README, LICENSE, .env, Makefile …) are almost
        // always text; the server mime gives a second opinion when it has one.
        if (ext.isEmpty()) {
            return when {
                mimeType == null -> FileKind.TEXT
                mimeType.startsWith("text/") -> FileKind.TEXT
                mimeType.contains("json") || mimeType.contains("xml") -> FileKind.TEXT
                else -> FileKind.OTHER
            }
        }
        mimeType?.let { mime ->
            if (mime.startsWith("text/")) return FileKind.TEXT
            if (mime == "application/pdf") return FileKind.PDF
            if (mime.startsWith("image/")) return FileKind.IMAGE
            if (mime.startsWith("video/")) return FileKind.VIDEO
            if (mime.startsWith("audio/")) return FileKind.AUDIO
        }
        return FileKind.OTHER
    }

    /** True for OOXML files [OfficeText] can pull content out of. */
    fun canExtractOfficeText(name: String): Boolean = ext(name) in OOXML

    /**
     * MIME type for an `ACTION_VIEW` hand-off: the server's value when it has
     * one, otherwise a guess from the extension so office apps accept the file.
     */
    fun mimeOf(name: String, mimeType: String? = null): String {
        if (!mimeType.isNullOrBlank() && mimeType != "application/octet-stream") return mimeType
        return when (ext(name)) {
            "jpg", "jpeg", "jpe", "jfif" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "bmp" -> "image/bmp"
            "heic" -> "image/heic"
            "heif" -> "image/heif"
            "avif" -> "image/avif"
            "tif", "tiff" -> "image/tiff"
            "mp4", "m4v" -> "video/mp4"
            "mkv" -> "video/x-matroska"
            "webm" -> "video/webm"
            "mov" -> "video/quicktime"
            "avi" -> "video/x-msvideo"
            "3gp" -> "video/3gpp"
            "ts", "m2ts", "mts" -> "video/mp2t"
            "mp3" -> "audio/mpeg"
            "flac" -> "audio/flac"
            "m4a" -> "audio/mp4"
            "aac" -> "audio/aac"
            "wav" -> "audio/wav"
            "ogg", "oga" -> "audio/ogg"
            "opus" -> "audio/opus"
            "wma" -> "audio/x-ms-wma"
            "pdf" -> "application/pdf"
            "txt", "log", "ini", "cfg", "conf", "md", "markdown" -> "text/plain"
            "csv" -> "text/csv"
            "json" -> "application/json"
            "xml" -> "text/xml"
            "html", "htm" -> "text/html"
            "doc" -> "application/msword"
            "docx", "docm" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            "xls" -> "application/vnd.ms-excel"
            "xlsx", "xlsm" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            "ppt" -> "application/vnd.ms-powerpoint"
            "pptx", "pptm", "ppsx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
            "odt" -> "application/vnd.oasis.opendocument.text"
            "ods" -> "application/vnd.oasis.opendocument.spreadsheet"
            "odp" -> "application/vnd.oasis.opendocument.presentation"
            "rtf" -> "application/rtf"
            "zip" -> "application/zip"
            "rar" -> "application/vnd.rar"
            "7z" -> "application/x-7z-compressed"
            "tar" -> "application/x-tar"
            "gz" -> "application/gzip"
            "apk" -> "application/vnd.android.package-archive"
            else -> "*/*"
        }
    }
}
