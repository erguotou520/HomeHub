package me.erguotou.homehub.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The 文件 tab picks a viewer from this matrix, so a wrong answer here means a
 * file opens in the wrong surface (a PDF dumped into the text reader, a photo
 * handed to an office app). Pure logic, no Android — runs as a plain JVM test.
 */
class FileKindsTest {

    @Test
    fun `server media kind wins over the extension`() {
        // These are the files the album scanner indexed, so both modules have
        // to agree on what they are even if the name looks odd.
        assertEquals(FileKind.IMAGE, FileKinds.of("scan.bin", null, "image"))
        assertEquals(FileKind.VIDEO, FileKinds.of("clip.dat", null, "video"))
        assertEquals(FileKind.AUDIO, FileKinds.of("song.dat", null, "music"))
    }

    @Test
    fun `extension decides when the server only says file`() {
        assertEquals(FileKind.IMAGE, FileKinds.of("IMG_0001.JPG"))
        assertEquals(FileKind.IMAGE, FileKinds.of("burst.heic"))
        assertEquals(FileKind.VIDEO, FileKinds.of("movie.mkv"))
        assertEquals(FileKind.VIDEO, FileKinds.of("cam.webm"))
        assertEquals(FileKind.AUDIO, FileKinds.of("album.flac"))
        assertEquals(FileKind.PDF, FileKinds.of("manual.PDF"))
        assertEquals(FileKind.TEXT, FileKinds.of("notes.md"))
        assertEquals(FileKind.TEXT, FileKinds.of("app.ini"))
        assertEquals(FileKind.TEXT, FileKinds.of("board.csv"))
        assertEquals(FileKind.TEXT, FileKinds.of("MainActivity.kt"))
        assertEquals(FileKind.TEXT, FileKinds.of("movie.srt"))
        assertEquals(FileKind.OTHER, FileKinds.of("backup.zip"))
        assertEquals(FileKind.OTHER, FileKinds.of("app.apk"))
    }

    @Test
    fun `extensionless files are text unless the server says otherwise`() {
        assertEquals(FileKind.TEXT, FileKinds.of("README"))
        assertEquals(FileKind.TEXT, FileKinds.of("Makefile"))
        assertEquals(FileKind.TEXT, FileKinds.of("LICENSE"))
        assertEquals(FileKind.OTHER, FileKinds.of("blob", "application/octet-stream"))
    }

    @Test
    fun `a text mime type rescues an unknown extension`() {
        assertEquals(FileKind.TEXT, FileKinds.of("weird.abc", "text/plain"))
        assertEquals(FileKind.TEXT, FileKinds.of("weird.abc", "application/json"))
        assertEquals(FileKind.IMAGE, FileKinds.of("weird.abc", "image/png"))
        assertEquals(FileKind.PDF, FileKinds.of("weird.abc", "application/pdf"))
        assertEquals(FileKind.OTHER, FileKinds.of("weird.abc", "application/octet-stream"))
    }

    @Test
    fun `office splits into extractable ooxml and handoff formats`() {
        assertEquals(FileKind.OFFICE, FileKinds.of("report.docx"))
        assertEquals(FileKind.OFFICE, FileKinds.of("book.xlsx"))
        assertEquals(FileKind.OFFICE, FileKinds.of("deck.pptx"))

        assertTrue(FileKinds.canExtractOfficeText("report.docx"))
        assertTrue(FileKinds.canExtractOfficeText("book.XLSX"))
        assertTrue(FileKinds.canExtractOfficeText("deck.pptm"))

        // Binary OLE / ODF have no ZIP we can read — the system app gets them.
        assertFalse(FileKinds.canExtractOfficeText("legacy.doc"))
        assertFalse(FileKinds.canExtractOfficeText("sheet.ods"))
        assertEquals(FileKind.OFFICE, FileKinds.of("legacy.doc"))
        assertEquals(FileKind.OFFICE, FileKinds.of("sheet.ods"))
        assertFalse(FileKinds.canExtractOfficeText("report.docx.txt"))
    }

    @Test
    fun `handoff mime is something office apps accept`() {
        assertEquals("application/pdf", FileKinds.mimeOf("a.pdf"))
        assertEquals(
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            FileKinds.mimeOf("a.docx")
        )
        assertEquals(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            FileKinds.mimeOf("a.xlsx")
        )
        assertEquals("video/x-matroska", FileKinds.mimeOf("a.mkv"))
        assertEquals("text/plain", FileKinds.mimeOf("notes.ini"))
        // The server's own value wins…
        assertEquals("image/heic", FileKinds.mimeOf("a.bin", "image/heic"))
        // …but the generic octet-stream is not trusted over the extension.
        assertEquals("image/jpeg", FileKinds.mimeOf("a.jpg", "application/octet-stream"))
        assertEquals("*/*", FileKinds.mimeOf("mystery"))
    }

    @Test
    fun `only OTHER is handed to another app`() {
        FileKind.values().forEach { kind ->
            assertEquals(kind != FileKind.OTHER, kind.viewable)
        }
    }
}
