package me.erguotou.homehub.util

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * On-device coverage for the OOXML text extraction.
 *
 * `OfficeText` leans on the platform XML parser (`android.util.Xml`), so it
 * cannot run as a plain JVM test — the fixtures are real ZIPs built in memory
 * and the parser runs on the device, exactly as it does in the viewer.
 *
 * The fixtures are deliberately minimal: what matters is that paragraphs,
 * per-cell alignment and per-slide ordering survive the round trip, not that a
 * full Word document parses.
 */
@RunWith(AndroidJUnit4::class)
class OfficeTextTest {

    // ─────────────────────────────── fixtures ───────────────────────────────

    private fun zip(vararg entries: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun docx(vararg paragraphs: String) = zip(
        "word/document.xml" to """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
              <w:body>${paragraphs.joinToString("") { "<w:p><w:r><w:t>$it</w:t></w:r></w:p>" }}</w:body>
            </w:document>
        """.trimIndent()
    )

    /** One paragraph split across two runs, like Word actually writes it. */
    private val docxWithRuns = zip(
        "word/document.xml" to """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
              <w:body>
                <w:p><w:r><w:t>第一段</w:t></w:r></w:p>
                <w:p><w:r><w:t>Second</w:t></w:r><w:r><w:t xml:space="preserve"> line</w:t></w:r></w:p>
              </w:body>
            </w:document>
        """.trimIndent()
    )

    private val xlsx = zip(
        "xl/sharedStrings.xml" to """
            <sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
              <si><t>Alpha</t></si>
              <si><r><t>Be</t></r><r><t>ta</t></r></si>
            </sst>
        """.trimIndent(),
        "xl/worksheets/sheet1.xml" to """
            <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
              <sheetData>
                <row r="1"><c r="A1" t="s"><v>0</v></c><c r="B1"><v>42</v></c></row>
                <row r="2"><c r="A2" t="s"><v>1</v></c><c r="C2" t="inlineStr"><is><t>inline</t></is></c></row>
              </sheetData>
            </worksheet>
        """.trimIndent()
    )

    private val pptx = zip(
        "ppt/slides/slide1.xml" to """
            <p:sld xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main">
              <p:cSld><p:spTree><p:sp><p:txBody>
                <a:p><a:r><a:t>第一页标题</a:t></a:r></a:p>
                <a:p><a:r><a:t>bullet</a:t></a:r></a:p>
              </p:txBody></p:sp></p:spTree></p:cSld>
            </p:sld>
        """.trimIndent(),
        "ppt/slides/slide2.xml" to """
            <p:sld xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main">
              <p:cSld><p:spTree><p:sp><p:txBody>
                <a:p><a:r><a:t>第二页</a:t></a:r></a:p>
              </p:txBody></p:sp></p:spTree></p:cSld>
            </p:sld>
        """.trimIndent()
    )

    // ──────────────────────────────── cases ────────────────────────────────

    @Test
    fun docx_paragraphs_become_lines_and_runs_join() {
        val text = OfficeText.extract("notes.docx", docxWithRuns)
        assertEquals("第一段\nSecond line", text)
    }

    @Test
    fun xlsx_cells_align_by_column_reference() {
        val text = OfficeText.extract("book.xlsx", xlsx)!!
        assertTrue(text.startsWith("── 工作表 1 ──"))
        // Column B is filled on row 1, so row 2 keeps its gap before C.
        assertTrue(text.contains("Alpha | 42"))
        assertTrue(text.contains("Beta |  | inline"))
    }

    @Test
    fun pptx_slides_are_numbered_in_order() {
        val text = OfficeText.extract("deck.pptx", pptx)!!
        val first = text.indexOf("第一页标题")
        val second = text.indexOf("第二页")
        assertTrue(text.contains("── 第 1 页 ──"))
        assertTrue(text.contains("── 第 2 页 ──"))
        assertTrue(first in 0 until second)
        assertTrue(text.contains("bullet"))
    }

    @Test
    fun non_ooxml_and_broken_input_is_reported_as_unavailable() {
        // Binary OLE / ODF / plain zips have nothing we can read.
        assertNull(OfficeText.extract("legacy.doc", docx("x")))
        assertNull(OfficeText.extract("sheet.ods", xlsx))
        assertNull(OfficeText.extract("backup.zip", xlsx))
        assertNull(OfficeText.extract("broken.docx", byteArrayOf(1, 2, 3, 4)))
        // A valid package with no readable text (e.g. a scan) stays empty.
        assertNull(OfficeText.extract("scan.docx", zip("word/document.xml" to "<w:document/>")))
    }
}
