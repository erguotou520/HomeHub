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
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
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
 * pipeline: real HTTP, real directory listing, real OOXML text extraction. It
 * therefore cannot pick sensible defaults, so the target is passed in and the
 * whole class self-skips when it is missing or unreachable — that keeps it
 * harmless on a machine without the dev server.
 *
 * ```
 * adb shell am instrument -w -e class me.erguotou.homehub.FilesE2eTest \
 *   -e serverHost 192.168.202.92 -e serverDir docs -e serverPath _e2e \
 *   me.erguotou.homehub.test/androidx.test.runner.AndroidJUnitRunner
 * ```
 *
 * Fixtures expected inside `serverDir/serverPath` (see the repo's local docs):
 * `note.md`, `sample.docx` (valid OOXML), `legacy.doc`, and a `sub/inner.txt`.
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

    /** Mount the real screen and navigate it to the fixture directory. */
    private fun showFiles(): FilesViewModel {
        val vm = FilesViewModel(app)
        rule.setContent { FilesScreen(vm = vm) }
        rule.waitUntil(20_000) { vm.state.value.dirs.isNotEmpty() }
        rule.runOnIdle { vm.open(dirName, dirPath) }
        rule.waitUntil(20_000) {
            !vm.state.value.loading && vm.state.value.entries.isNotEmpty()
        }
        return vm
    }

    /** Wait until a text (substring match) shows up somewhere in the tree. */
    private fun awaitText(text: String, timeoutMillis: Long = 15_000) {
        rule.waitUntil(timeoutMillis) {
            rule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /** Something inside the open dialog — the list behind keeps its own nodes. */
    private fun inDialog(text: String) =
        rule.onNode(hasText(text) and hasAnyAncestor(isDialog()))

    // ① 目录能列出来
    @Test
    fun listsTheDirectory() {
        showFiles()
        rule.onNodeWithText("note.md").assertIsDisplayed()
        rule.onNodeWithText("sample.docx").assertIsDisplayed()
        rule.onNodeWithText("sub").assertIsDisplayed()
    }

    // ① 纯文本 -> 全屏文本查看器（真实字节流）
    @Test
    fun textFileOpensFullscreenTextViewer() {
        showFiles()
        rule.onNodeWithText("note.md").performClick()
        awaitText("全屏文本查看器")
        rule.onNodeWithText("全屏文本查看器", substring = true).assertIsDisplayed()
    }

    // ① Office -> 内联文本抽取预览（真实 docx）
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

    // ② 复制/移动到 -> 可下钻、且能看到目录下的文件
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

    // ③ 删除必须弹框确认
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
