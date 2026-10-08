package me.erguotou.homehub.update

import android.content.pm.PackageInstaller
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 安装结局判定的单测。
 *
 * 这两件事都**不会自己报错**，只会让用户看到一句莫名其妙的红字，所以钉住它们：
 *
 * 1. ColorOS 把安装接过去自己做之后，我们这条会话收到的是 ABORTED，而包已经换好了。
 *    只看状态码就会把一次成功读成「已取消安装」。
 * 2. 结果广播会迟到（甚至晚到系统专门冷启进程来投递它），界面必须只认自己等的那一次。
 */
class InstallOutcomeTest {

    private val target = 10003L
    private val attempt = 7

    private fun resolve(
        status: Int,
        installed: Long? = null,
        targetCode: Long = target,
        attemptId: Int = attempt,
        statusMessage: String? = "INSTALL_FAILED_ABORTED: User rejected permissions",
    ) = ApkInstaller.resolve(
        status = status,
        statusMessage = statusMessage,
        attempt = attemptId,
        targetVersionCode = targetCode,
        installedVersionCode = installed,
    )

    @Test
    fun `包已经换成目标版本时，ABORTED 也算成功`() {
        // 真机实测的那一幕：ColorOS 的安装引导页装完 1.0.3，回来的是 STATUS_FAILURE_ABORTED。
        val outcome = resolve(PackageInstaller.STATUS_FAILURE_ABORTED, installed = target)
        assertTrue("已安装版本就是目标版本，不该判失败", outcome.success)
        assertEquals(ApkInstaller.SUCCESS_MESSAGE, outcome.message)
    }

    @Test
    fun `版本已经更高时同样算成功`() {
        // 万一用户自己先装了个更新的包，也没必要报旧的那次失败。
        assertTrue(resolve(PackageInstaller.STATUS_FAILURE_ABORTED, installed = target + 5).success)
    }

    @Test
    fun `真的取消仍然是失败`() {
        val outcome = resolve(PackageInstaller.STATUS_FAILURE_ABORTED, installed = target - 1)
        assertFalse(outcome.success)
        assertEquals("已取消安装", outcome.message)
    }

    @Test
    fun `读不到已安装版本时不敢当成成功`() {
        assertFalse(resolve(PackageInstaller.STATUS_FAILURE_ABORTED, installed = null).success)
    }

    @Test
    fun `目标版本未知时不敢当成成功`() {
        // 清单缺字段（字段一律可空）时版本号是 0，此时任何「已安装版本」都不该被当成达标。
        assertFalse(resolve(PackageInstaller.STATUS_FAILURE_ABORTED, installed = target, targetCode = 0).success)
    }

    @Test
    fun `成功状态码不看版本也成功`() {
        val outcome = resolve(PackageInstaller.STATUS_SUCCESS, installed = target - 1)
        assertTrue(outcome.success)
    }

    @Test
    fun `失败原因按状态码翻译，认不出才回落到系统文案`() {
        assertEquals("系统阻止了这次安装", resolve(PackageInstaller.STATUS_FAILURE_BLOCKED).message)
        assertEquals("存储空间不足", resolve(PackageInstaller.STATUS_FAILURE_STORAGE).message)
        // 认不出的状态码：系统给了文案就用系统的
        assertEquals("BOOM", resolve(status = 99, statusMessage = "BOOM").message)
        // 认不出、系统也没给（或只给了空白）：才兜底成自己的文案
        assertEquals("安装未完成", resolve(status = 99, statusMessage = "   ").message)
        assertEquals("安装未完成", resolve(status = 99, statusMessage = null).message)
    }

    @Test
    fun `结局带着自己的编号`() {
        assertEquals(attempt, resolve(PackageInstaller.STATUS_SUCCESS, installed = target).attempt)
    }

    @Test
    fun `只有正在等的那一次结局才被采纳`() {
        val mine = InstallOutcome(attempt, success = false, message = "已取消安装")
        assertTrue(ApkInstaller.isApplicable(mine, awaiting = attempt))

        // 上一次尝试的迟到广播：界面正在等 8，来的是 7 —— 必须丢掉，否则会把
        // 当前这次的进度界面顶成一条红字。
        assertFalse(ApkInstaller.isApplicable(mine, awaiting = attempt + 1))

        // 没在等任何结果（新建进程里躺着的旧值就属于这种）：一样丢掉。
        assertFalse(ApkInstaller.isApplicable(mine, awaiting = ApkInstaller.NO_ATTEMPT))
        assertFalse(ApkInstaller.isApplicable(null, awaiting = attempt))
    }
}
