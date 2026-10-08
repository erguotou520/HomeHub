package me.erguotou.homehub.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * 用 PackageInstaller 会话安装 APK。
 *
 * 不走 `ACTION_VIEW` 那套：targetSdk 34 起 `ACTION_INSTALL_PACKAGE` 已不可用，
 * 会话 API 还能拿到明确的安装结果。无论走哪条路，系统都会弹一次确认框 ——
 * 普通应用无法静默安装，这是平台限制，不是实现上的取舍。
 */
object ApkInstaller {

    /**
     * 安装成功的提示文案。
     *
     * 界面靠它区分「装好了」和「被拒了」—— 两者都要结束「进行中」这个状态，
     * 但只有后者需要把用户按回可重试的界面。改这里的字面值要同步改 [me.erguotou.homehub.ui.screens.settings.UpdateSection]。
     */
    const val SUCCESS_MESSAGE = "已更新到新版本"

    private val _result = MutableStateFlow<String?>(null)

    /** 最近一次安装结果，界面订阅它提示成功或失败。 */
    val result: StateFlow<String?> = _result

    fun clear() {
        _result.value = null
    }

    /** 供安装结果广播回填。 */
    internal fun publish(message: String) {
        _result.value = message
    }

    /**
     * 提交安装会话。返回非 null 表示连会话都没建起来（原因直接给用户看）；
     * 返回 null 表示已交给系统，结果稍后从 [result] 出来。
     */
    fun install(context: Context, apk: File): String? {
        if (!apk.isFile) return "安装包不存在"
        val installer = context.packageManager.packageInstaller
        return runCatching {
            val params = PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL
            )
            // 明确这是一次升级，系统就不会当成全新安装而清掉应用数据。
            params.setAppPackageName(context.packageName)

            val sessionId = installer.createSession(params)
            installer.openSession(sessionId).use { session ->
                session.openWrite("homehub", 0, apk.length()).use { out ->
                    apk.inputStream().use { it.copyTo(out) }
                    session.fsync(out)
                }
                val intent = Intent(context, InstallResultReceiver::class.java)
                    .setPackage(context.packageName)
                // FLAG_MUTABLE 是 API 31 才有的常量，minSdk 30 上要分岔。
                val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                } else {
                    PendingIntent.FLAG_UPDATE_CURRENT
                }
                val pending = PendingIntent.getBroadcast(context, sessionId, intent, flags)
                session.commit(pending.intentSender)
            }
            null
        }.getOrElse { it.message ?: "无法创建安装会话" }
    }
}

/** 接收 PackageInstaller 的安装结果。 */
class InstallResultReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(
            PackageInstaller.EXTRA_STATUS,
            PackageInstaller.STATUS_FAILURE,
        )
        when (status) {
            PackageInstaller.STATUS_SUCCESS ->
                ApkInstaller.publish(ApkInstaller.SUCCESS_MESSAGE)

            // 用户还没允许「安装未知应用」，需要把系统的授权页拉起来，
            // 否则安装会停在这一步，界面上什么都不发生。
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    runCatching { context.startActivity(confirm) }
                }
            }

            else -> {
                val detail = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                ApkInstaller.publish(
                    when (status) {
                        PackageInstaller.STATUS_FAILURE_ABORTED -> "已取消安装"
                        PackageInstaller.STATUS_FAILURE_BLOCKED -> "系统阻止了这次安装"
                        PackageInstaller.STATUS_FAILURE_CONFLICT ->
                            "安装冲突：请先卸载已装版本（签名不一致）"

                        PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ->
                            "系统版本不兼容，无法安装"

                        PackageInstaller.STATUS_FAILURE_STORAGE -> "存储空间不足"
                        else -> detail?.takeIf { it.isNotBlank() } ?: "安装未完成"
                    }
                )
            }
        }
    }
}
