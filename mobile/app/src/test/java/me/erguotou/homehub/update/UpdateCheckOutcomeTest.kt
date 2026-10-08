package me.erguotou.homehub.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「检查一次之后该显示什么、要不要弹框」的单测。
 *
 * 这张表就是用户明确要的行为，而且错了两头都很难看：自动检查话太多（一进应用就
 * 报网络错、或每次都弹已经知道的版本），或者手动检查不给交代（点了「检查更新」
 * 界面毫无反应）。所以六种组合一条不落钉住。
 */
class UpdateCheckOutcomeTest {

    private val current = 10003L

    private fun manifest(code: Long) = UpdateManifest(versionName = "1.0.4", versionCode = code)

    private fun decide(manifest: UpdateManifest?, manual: Boolean) =
        checkOutcome(manifest, manual, currentVersionCode = current)

    @Test
    fun `自动检查发现新版本：卡片显示检测到新版本，并且弹框`() {
        val outcome = decide(manifest(10004), manual = false)
        assertTrue(outcome.prompt)
        assertTrue(outcome.state is UpdateUiState.Available)
        assertEquals("1.0.4", (outcome.state as UpdateUiState.Available).manifest.versionName)
    }

    @Test
    fun `自动检查没有新版本：安静地回到默认态，不弹框`() {
        // 关键点：不是 UpToDate。刚升完级重启回来，界面不该自己跳出一句
        // 「已是最新版本」—— 那是用户点「检查更新」才该得到的回应。
        val outcome = decide(manifest(current), manual = false)
        assertFalse(outcome.prompt)
        assertEquals(UpdateUiState.Idle, outcome.state)
    }

    @Test
    fun `自动检查拉不到清单：安静地回到默认态，不弹框`() {
        val outcome = decide(null, manual = false)
        assertFalse(outcome.prompt)
        assertEquals(UpdateUiState.Idle, outcome.state)
    }

    @Test
    fun `手动检查发现新版本：卡片显示，但不弹框`() {
        // 卡片就在眼前，再盖一层对话框只会碍事。
        val outcome = decide(manifest(10004), manual = true)
        assertFalse(outcome.prompt)
        assertTrue(outcome.state is UpdateUiState.Available)
    }

    @Test
    fun `手动检查没有新版本：说已是最新`() {
        assertEquals(UpdateUiState.UpToDate, decide(manifest(current), manual = true).state)
    }

    @Test
    fun `手动检查失败：说出失败`() {
        val state = decide(null, manual = true).state
        assertTrue(state is UpdateUiState.Failed)
    }

    @Test
    fun `清单里的版本号不高于本机时都不算有新版`() {
        // 清单字段一律可空，解析失败时 versionCode 是 0；那种"清单"不能被当成升级。
        for (code in listOf(0L, 1L, current - 1, current)) {
            assertFalse("versionCode=$code 不该被判成有新版", decide(manifest(code), manual = true).state is UpdateUiState.Available)
        }
    }
}
