package me.erguotou.homehub.util

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

/**
 * Zero-dependency plain-text extraction for OOXML documents.
 *
 * There is no lightweight native Office renderer on Android: Apache POI costs
 * ~11 MB and OOMs on mid-range phones, Tencent TBS (TbsReaderView) requires
 * downloading a 40 MB+ kernel, and the WebView + bundled-JS route needs three
 * vendored JS engines with shaky CJK fidelity. An `.docx/.xlsx/.pptx` is just
 * a ZIP of XML though, so this object reads the interesting parts with
 * `java.util.zip` + the platform `XmlPullParser` and renders a readable text
 * version on the device — offline, instantly, no extra APK weight. Anything
 * we cannot parse (`.doc/.xls/.ppt`, ODF, WPS, iWork) is handed to the system.
 *
 * Every extraction is bounded ([MAX_CHARS], [MAX_ROWS], [MAX_ENTRY_BYTES]) so a
 * hostile or simply huge document cannot exhaust memory.
 */
object OfficeText {

    private const val MAX_CHARS = 400_000
    private const val MAX_ROWS = 400
    private const val MAX_COLS = 40
    private const val MAX_ENTRY_BYTES = 24 * 1024 * 1024

    /**
     * Text-ish rendering of [name]'s content, or null when the format is not
     * supported / the document carries no extractable text (e.g. a scan).
     */
    fun extract(name: String, bytes: ByteArray): String? = runCatching {
        val text = when (FileKinds.ext(name)) {
            "docx", "docm" -> word(bytes)
            "xlsx", "xlsm" -> sheet(bytes)
            "pptx", "pptm", "ppsx" -> slides(bytes)
            else -> null
        }?.trim()
        text?.takeIf { it.isNotEmpty() }
    }.getOrNull()

    // ────────────────────────────────── Word ─────────────────────────────────

    private fun word(bytes: ByteArray): String? {
        val entries = readEntries(bytes) { it == "word/document.xml" }
        val xml = entries["word/document.xml"] ?: return null
        val sb = StringBuilder()
        val parser = newParser(xml)
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.local()) {
                    "t" -> sb.append(parser.nextText())
                    "tab" -> sb.append('\t')
                    "br", "cr" -> sb.append('\n')
                }
                XmlPullParser.END_TAG -> when (parser.local()) {
                    // A table cell is emitted inline as `a | b | c`; paragraphs
                    // and rows each start a new line.
                    "tc" -> sb.append(" | ")
                    "p", "tr" -> sb.append('\n')
                }
            }
            if (sb.length > MAX_CHARS) break
            event = parser.next()
        }
        return sb.toString()
    }

    // ───────────────────────────────── Excel ────────────────────────────────

    private fun sheet(bytes: ByteArray): String? {
        val entries = readEntries(bytes) { name ->
            name == "xl/sharedStrings.xml" || (name.startsWith("xl/worksheets/") && name.endsWith(".xml"))
        }
        val shared = entries["xl/sharedStrings.xml"]?.let { parseSharedStrings(it) } ?: emptyList()
        val sheets = entries.keys
            .filter { it.startsWith("xl/worksheets/") }
            .sortedBy { trailingNumber(it) }
        if (sheets.isEmpty()) return null

        val sb = StringBuilder()
        for ((index, name) in sheets.withIndex()) {
            if (sb.length > MAX_CHARS) break
            if (index > 0) sb.append('\n')
            sb.append("── 工作表 ${index + 1} ──\n")
            parseSheet(entries.getValue(name), shared, sb)
        }
        return sb.toString()
    }

    private fun parseSharedStrings(xml: ByteArray): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        var inItem = false
        val parser = newParser(xml)
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.local()) {
                    "si" -> { inItem = true; sb.setLength(0) }
                    "t" -> if (inItem) sb.append(parser.nextText())
                }
                XmlPullParser.END_TAG -> if (parser.local() == "si" && inItem) {
                    out += sb.toString()
                    inItem = false
                }
            }
            event = parser.next()
        }
        return out
    }

    private fun parseSheet(xml: ByteArray, shared: List<String>, sb: StringBuilder) {
        val cells = arrayOfNulls<String>(MAX_COLS)
        var col = 0
        var type: String? = null
        var rowCount = 0
        val parser = newParser(xml)
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.local()) {
                    "row" -> { cells.fill(null); rowCount++ }
                    "c" -> {
                        type = parser.getAttributeValue(null, "t")
                        col = columnOf(parser.getAttributeValue(null, "r")).coerceIn(0, MAX_COLS - 1)
                        cells[col] = null
                    }
                    "v" -> {
                        val raw = parser.nextText()
                        cells[col] = if (type == "s") {
                            raw.toIntOrNull()?.let { shared.getOrNull(it) } ?: ""
                        } else raw
                    }
                    // Inline string cell: <c t="inlineStr"><is><t>text</t></is></c>
                    "t" -> cells[col] = (cells[col] ?: "") + parser.nextText()
                }
                XmlPullParser.END_TAG -> if (parser.local() == "row") {
                    // Keep column alignment: pad the gaps up to the last
                    // filled cell instead of collapsing them away.
                    val last = cells.indexOfLast { it != null }
                    if (last >= 0) {
                        val line = (0..last).joinToString(" | ") { cells[it] ?: "" }
                        if (line.isNotBlank()) sb.append(line.trimEnd()).append('\n')
                    }
                }
            }
            if (rowCount > MAX_ROWS || sb.length > MAX_CHARS) return
            event = parser.next()
        }
    }

    /** "AB12" → 27 (0-based column index). */
    private fun columnOf(ref: String?): Int {
        if (ref.isNullOrEmpty()) return 0
        var value = 0
        for (ch in ref) {
            if (ch !in 'A'..'Z') break
            value = value * 26 + (ch - 'A' + 1)
        }
        return value - 1
    }

    // ─────────────────────────────── PowerPoint ─────────────────────────────

    private fun slides(bytes: ByteArray): String? {
        val entries = readEntries(bytes) { it.startsWith("ppt/slides/slide") && it.endsWith(".xml") }
        if (entries.isEmpty()) return null
        val ordered = entries.keys.sortedBy { trailingNumber(it) }
        val sb = StringBuilder()
        ordered.forEachIndexed { index, name ->
            sb.append("── 第 ${index + 1} 页 ──\n")
            val parser = newParser(entries.getValue(name))
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> when (parser.local()) {
                        "t" -> sb.append(parser.nextText())
                        "br" -> sb.append('\n')
                    }
                    XmlPullParser.END_TAG -> if (parser.local() == "p") sb.append('\n')
                }
                if (sb.length > MAX_CHARS) return sb.toString()
                event = parser.next()
            }
            sb.append('\n')
        }
        return sb.toString()
    }

    // ──────────────────────────────── plumbing ──────────────────────────────

    /** Read the ZIP entries [wanted] asks for, bounded in size. */
    private fun readEntries(
        bytes: ByteArray,
        wanted: (String) -> Boolean
    ): Map<String, ByteArray> {
        val out = LinkedHashMap<String, ByteArray>()
        var total = 0
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (!entry.isDirectory && wanted(entry.name)) {
                    val data = zip.readBytes()
                    total += data.size
                    if (total > MAX_ENTRY_BYTES) break
                    out[entry.name] = data
                }
                zip.closeEntry()
            }
        }
        return out
    }

    private fun newParser(xml: ByteArray): XmlPullParser =
        Xml.newPullParser().apply {
            runCatching { setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false) }
            setInput(ByteArrayInputStream(xml), null)
        }

    /** Local tag name, so `w:t` and `a:t` both read as `t`. */
    private fun XmlPullParser.local(): String = (name ?: "").substringAfterLast(':')

    private fun trailingNumber(path: String): Int =
        Regex("(\\d+)").findAll(path).lastOrNull()?.value?.toIntOrNull() ?: 0
}
