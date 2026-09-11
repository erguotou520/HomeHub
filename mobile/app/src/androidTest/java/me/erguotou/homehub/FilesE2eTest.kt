package me.erguotou.homehub

import android.app.Application
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.erguotou.homehub.data.Prefs
import me.erguotou.homehub.ui.screens.files.FilesScreen
import me.erguotou.homehub.ui.screens.files.FilesViewModel
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.net.HttpURLConnection
import java.net.URL

/**
 * End-to-end coverage for the 文件 tab against a *live* HomeHub server.
 *
 * Unlike [ViewerGestureTest] (pure UI, fakes) this one exercises the real
 * pipeline: real HTTP, real directory listing, real OOXML text extraction,
 * real PdfRenderer. It therefore cannot pick sensible defaults, so every
 * target is passed in and each case self-skips when its target is missing or
 * the server is unreachable — that keeps the class harmless on a machine
 * without the dev server.
 *
 * ```
 * adb shell am instrument -w -r -e class me.erguotou.homehub.FilesE2eTest \
 *   -e serverHost 192.168.202.92 -e serverDir docs -e serverPath _e2e \
 *   -e imageProbe photos:2025/01:shanghai.png \
 *   -e videoProbe photos:2025:clip-test.mp4 \
 *   -e pdfProbe   docs:a/c:通用识别.pdf \
 *   me.erguotou.homehub.test/androidx.test.runner.AndroidJUnitRunner
 * ```
 *
 * `serverDir/serverPath` should hold `note.md`, a valid `sample.docx` and a
 * `sub/` folder. Each probe is `dir:path:filename` and points at a real
 * file that already exists on the server (read-only — nothing is created).
 */
@RunWith(AndroidJUnit4::class)
class FilesE2eTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val args: Bundle = InstrumentationRegistry.getArguments()
    private val host: String? = args.getString("serverHost")
    private val port: Int = args.getString("serverPort")?.toIntOrNull() ?: 8485
    private val dirName: String = args.getString("serverDir") ?: "docs"
    private val dirPath: String = args.getString("serverPath") ?: ""

    private lateinit var app: Application

    @Before
    fun configureAgainstLiveServer() {
        assumeTrue("缺少 -e serverHost=<ip>，跳过端到端用例", host != null)
        app = InstrumentationRegistry.getInstrumentation()
            .targetContext.applicationContext as Application
        assumeTrue("服务端 $host:$port 不可达，跳过端到端用例", reachable())
        Prefs(app).apply {
            serverAddress = host!!
            serverPort = this@FilesE2eTest.port
            useHttps = false
        }
    }

    private fun reachable(): Boolean = try {
        (URL("http://$host:$port/api/health").openConnection() as HttpURLConnection).run {
            connectTimeout = 3_000
            readTimeout = 3_000
            responseCode in 200..299
        }
    } catch (_: Exception) {
        false
    }

    // ───────────────────────────── helpers ─────────────────────────────

    /** Mount the real screen and navigate it to [dir]/[path]. */
    private fun mount(dir: String, path: String): FilesViewModel {
        val vm = FilesViewModel(app)
        rule.setContent { FilesScreen(vm = vm) }
        rule.waitUntil(20_000) { vm.state.value.dirs.isNotEmpty() }
        rule.runOnIdle { vm.open(dir, path) }
        rule.waitUntil(20_000) {
            !vm.state.value.loading && vm.state.value.entries.isNotEmpty()
        }
        return vm
    }

    private fun showFiles(): FilesViewModel = mount(dirName, dirPath)

    /** Wait until a text (substring match) shows up somewhere in the tree. */
    private fun awaitText(text: String, timeoutMillis: Long = 15_000) {
        rule.waitUntil(timeoutMillis) {
            rule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /** Wait until a contentDescription shows up somewhere in the tree. */
    private fun awaitContentDescription(text: String, timeoutMillis: Long = 15_000) {
        rule.waitUntil(timeoutMillis) {
            rule.onAllNodesWithContentDescription(text).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /** Something inside the open dialog — the list behind keeps its own nodes. */
    private fun inDialog(text: String) =
        rule.onNode(hasText(text) and hasAnyAncestor(isDialog()))

    /** `dir:path:filename`, or null when the probe was not supplied. */
    private fun probe(key: String): Triple<String, String, String>? {
        val raw = args.getString(key) ?: return null
        val parts = raw.split(":")
        return if (parts.size == 3 && parts.all { it.isNotBlank() }) {
            Triple(parts[0], parts[1], parts[2])
        } else {
            null
        }
    }

    /**
     * The viewer's top bar carries a "返回" button that the list never shows
     * ("返回上级" is a different string), so it is a reliable "did the full
     * screen viewer open" probe for pages that expose no other semantics.
     */
    private fun assertViewerOpen() {
        awaitContentDescription("返回")
        rule.onNodeWithContentDescription("返回").assertIsDisplayed()
    }

    // ─────────────────────── ① 目录 / 文本 / Office ───────────────────────

    @Test
    fun listsTheDirectory() {
        showFiles()
        rule.onNodeWithText("note.md").assertIsDisplayed()
        rule.onNodeWithText("sample.docx").assertIsDisplayed()
        rule.onNodeWithText("sub").assertIsDisplayed()
    }

    @Test
    fun textFileOpensFullscreenTextViewer() {
        showFiles()
        rule.onNodeWithText("note.md").performClick()
        awaitText("全屏文本查看器")
        rule.onNodeWithText("全屏文本查看器", substring = true).assertIsDisplayed()
    }

    @Test
    fun docxOpensInlineOfficePreview() {
        showFiles()
        rule.onNodeWithText("sample.docx").performClick()
        awaitText("纯文本抽取预览", 20_000)
        rule.onNodeWithText("纯文本抽取预览", substring = true).assertIsDisplayed()
        // 正文来自 word/document.xml，多 run 的句子必须拼成一行
        rule.onNodeWithText("HomeHub 端到端验证文档", substring = true).assertIsDisplayed()
        rule.onNodeWithText("这是被拆成多个 run 的句子", substring = true).assertIsDisplayed()
    }

    // ─────────────────── ① 图片 / 视频 / PDF（真实文件）───────────────────

    /** 图片必须走相册那套内联查看器，而不是丢给系统应用。 */
    @Test
    fun imageOpensInInlineViewer() {
        val (dir, path, name) = probe("imageProbe")
            ?: run { assumeTrue("未提供 -e imageProbe，跳过", false); return }
        mount(dir, path)
        rule.onNodeWithText(name).performClick()
        assertViewerOpen()
        // ImagePage 把文件名挂在 contentDescription 上，列表行只是 Text，
        // 所以这个节点能证明「图片查看器真的渲染了这张图」。
        awaitContentDescription(name)
        rule.onNodeWithContentDescription(name).assertIsDisplayed()
    }

    /** 视频必须复用相册的 Media3 播放页（内联），不是跳第三方播放器。 */
    @Test
    fun videoOpensInInlinePlayer() {
        val (dir, path, name) = probe("videoProbe")
            ?: run { assumeTrue("未提供 -e videoProbe，跳过", false); return }
        mount(dir, path)
        rule.onNodeWithText(name).performClick()
        assertViewerOpen()
    }

    /** PDF 走平台 PdfRenderer 内联渲染（不是交系统应用）。 */
    @Test
    fun pdfRendersInline() {
        val (dir, path, name) = probe("pdfProbe")
            ?: run { assumeTrue("未提供 -e pdfProbe，跳过", false); return }
        mount(dir, path)
        rule.onNodeWithText(name).performClick()
        assertViewerOpen()
        // PdfPage 给每页渲染结果挂了「第 N 页」的 contentDescription；
        // 只有真的用 PdfRenderer 渲出位图才会有这个节点。
        awaitContentDescription("第 1 页", 25_000)
        rule.onNodeWithContentDescription("第 1 页").assertIsDisplayed()
    }

    // ─────────────────────── ② 复制/移动到 ───────────────────────

    @Test
    fun copyPickerBrowseFoldersAndFiles() {
        showFiles()
        rule.onNodeWithText("note.md").performTouchInput { longClick() }
        awaitText("已选 1")

        rule.onNodeWithText("复制").performClick()
        awaitText("复制到")
        // 目录下已有文件应当可见（不只是子目录）
        inDialog("legacy.doc").assertIsDisplayed()

        // 下钻到 sub/
        rule.onAllNodesWithText("sub").onLast().performClick()
        awaitText("inner.txt")
        rule.onNodeWithText("inner.txt").assertIsDisplayed()
    }

    // ─────────────────────── ③ 删除二次确认 ───────────────────────

    @Test
    fun deleteAsksForConfirmation() {
        showFiles()
        rule.onNodeWithText("note.md").performTouchInput { longClick() }
        awaitText("已选 1")

        rule.onNodeWithText("删除").performClick()
        awaitText("删除这一项？")
        rule.onNodeWithText("删除后先进入回收站，30 天后自动清理。").assertIsDisplayed()
        inDialog("取消").assertIsDisplayed()
    }
}
