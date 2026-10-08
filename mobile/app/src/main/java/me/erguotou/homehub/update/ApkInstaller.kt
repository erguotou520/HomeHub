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
import java.util.concurrent.atomic.AtomicInteger

/**
 * 一次安装尝试的结局。
 *
 * [attempt] 是这次尝试的编号。结果广播可能**迟到很久**，晚到系统为了投递它专门把
 * 已经退出的进程重新拉起来（2026-10-08 真机实测：13:43:32 换包，13:43:35
 * `Start proc ... for broadcast {InstallResultReceiver}`）。界面必须靠编号认出
 * 「这是我正在等的那一次」，否则上一次尝试的失败会在任意时刻被重放给用户。
 */
data class InstallOutcome(
    val attempt: Int,
    val success: Boolean,
    val message: String,
)

/** 提交安装会话的结果。 */
sealed interface InstallStart {
    /** 已交给系统，结果稍后从 [ApkInstaller.result] 出来，编号是 [attempt]。 */
    data class Submitted(val attempt: Int) : InstallStart

    /** 连安装会话都没建起来，[reason] 直接给用户看。 */
    data class Refused(val reason: String) : InstallStart
}

/**
 * 用 PackageInstaller 会话安装 APK。
 *
 * 不走 `ACTION_VIEW` 那套：targetSdk 34 起 `ACTION_INSTALL_PACKAGE` 已不可用，
 * 会话 API 还能拿到明确的安装结果。无论走哪条路，系统都会弹一次确认框 ——
 * 普通应用无法静默安装，这是平台限制，不是实现上的取舍。
 */
object ApkInstaller {

    /** 安装成功的提示文案，界面在「成功的结局」上直接显示它。 */
    const val SUCCESS_MESSAGE = "已更新到新版本"

    /** 没有安装尝试在进行中。界面用它当「等待中的编号」，任何结果都对不上。 */
    const val NO_ATTEMPT = 0

    private const val EXTRA_ATTEMPT = "me.erguotou.homehub.install.attempt"
    private const val EXTRA_TARGET_VERSION = "me.erguotou.homehub.install.targetVersion"

    private val counter = AtomicInteger(NO_ATTEMPT)

    private val _result = MutableStateFlow<InstallOutcome?>(null)

    /** 最近一次安装的结局，界面按 [isApplicable] 挑出属于自己的那一条。 */
    val result: StateFlow<InstallOutcome?> = _result

    fun clear() {
        _result.value = null
    }

    /** 供安装结果广播回填。 */
    internal fun publish(outcome: InstallOutcome) {
        _result.value = outcome
    }

    /**
     * 这条结局是不是界面**正在等**的那一次。
     *
     * 两处不匹配都算不适用：没在等任何结果（[NO_ATTEMPT]），或编号不是当前这次。
     * 迟到的那条广播正是靠这里被挡在界面之外。
     */
    internal fun isApplicable(outcome: InstallOutcome?, awaiting: Int): Boolean =
        outcome != null && awaiting != NO_ATTEMPT && outcome.attempt == awaiting

    /**
     * 提交安装会话。返回 [InstallStart.Refused] 表示连会话都没建起来；
     * 返回 [InstallStart.Submitted] 表示已交给系统，结果稍后从 [result] 出来。
     *
     * [targetVersionCode] 会随广播一起回传，用来和「已安装版本」对账 —— 见 [resolve]。
     */
    fun install(context: Context, apk: File, targetVersionCode: Long): InstallStart {
        if (!apk.isFile) return InstallStart.Refused("安装包不存在")
        val installer = context.packageManager.packageInstaller
        val attempt = counter.incrementAndGet()
        var sessionId = -1
        return runCatching {
            val params = PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL
            )
            // 明确这是一次升级，系统就不会当成全新安装而清掉应用数据。
            params.setAppPackageName(context.packageName)

            sessionId = installer.createSession(params)
            installer.openSession(sessionId).use { session ->
                session.openWrite("homehub", 0, apk.length()).use { out ->
                    apk.inputStream().use { it.copyTo(out) }
                    session.fsync(out)
                }
                val intent = Intent(context, InstallResultReceiver::class.java)
                    .setPackage(context.packageName)
                    .putExtra(EXTRA_ATTEMPT, attempt)
                    .putExtra(EXTRA_TARGET_VERSION, targetVersionCode)
                // FLAG_MUTABLE 是 API 31 才有的常量，minSdk 30 上要分岔。
                val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                } else {
                    PendingIntent.FLAG_UPDATE_CURRENT
                }
                val pending = PendingIntent.getBroadcast(context, sessionId, intent, flags)
                session.commit(pending.intentSender)
            }
            InstallStart.Submitted(attempt)
        }.getOrElse { error ->
            // 会话建好又没提交成功：丢掉它，别在系统里留一个半截会话。
            if (sessionId >= 0) runCatching { installer.abandonSession(sessionId) }
            InstallStart.Refused(error.message ?: "无法创建安装会话")
        }
    }

    /** 把安装结果广播翻成 [InstallOutcome]。只有 [InstallResultReceiver] 该调用它。 */
    internal fun outcomeOf(context: Context, intent: Intent): InstallOutcome = resolve(
        status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE),
        statusMessage = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE),
        attempt = intent.getIntExtra(EXTRA_ATTEMPT, NO_ATTEMPT),
        targetVersionCode = intent.getLongExtra(EXTRA_TARGET_VERSION, 0L),
        installedVersionCode = installedVersionCode(context),
    )

    /** 这台机器上真正装着哪个版本。读不到就返回 null，由 [resolve] 决定怎么退让。 */
    internal fun installedVersionCode(context: Context): Long? = runCatching {
        context.packageManager
            .getPackageInfo(context.packageName, 0)
            .longVersionCode
    }.getOrNull()

    /**
     * 判定一次安装尝试到底成没成。
     *
     * **不能只看广播里的状态码。** ColorOS 的安装器会把安装从应用会话里「接过去」
     * 自己做（真机日志里是 `com.oplus.appdetail/.model.guide.ui.InstallGuideActivity`
     * → `.../model/finish.InstallFinishActivity`），于是包**装成功了**，我们这条会话
     * 收到的却是 `STATUS_FAILURE_ABORTED`。界面照着翻译就成了「已取消安装」，而同一
     * 屏的「当前版本」明明已经写着 1.0.3（2026-10-08 实机复现）。
     *
     * 所以把「已安装版本」当客观事实先对一次账：只要它已经达到目标版本，无论状态码
     * 说什么都算成功。读不到版本、或目标版本未知时不敢这么判，老老实实按状态码走。
     */
    internal fun resolve(
        status: Int,
        statusMessage: String?,
        attempt: Int,
        targetVersionCode: Long,
        installedVersionCode: Long?,
    ): InstallOutcome {
        val reached = installedVersionCode != null &&
            targetVersionCode > 0L &&
            installedVersionCode >= targetVersionCode
        if (status == PackageInstaller.STATUS_SUCCESS || reached) {
            return InstallOutcome(attempt, success = true, message = SUCCESS_MESSAGE)
        }
        val message = when (status) {
            PackageInstaller.STATUS_FAILURE_ABORTED -> "已取消安装"
            PackageInstaller.STATUS_FAILURE_BLOCKED -> "系统阻止了这次安装"
            PackageInstaller.STATUS_FAILURE_CONFLICT ->
                "安装冲突：请先卸载已装版本（签名不一致）"

            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "系统版本不兼容，无法安装"
            PackageInstaller.STATUS_FAILURE_STORAGE -> "存储空间不足"
            else -> statusMessage?.takeIf { it.isNotBlank() } ?: "安装未完成"
        }
        return InstallOutcome(attempt, success = false, message = message)
    }
}

/** 接收 PackageInstaller 的安装结果。 */
class InstallResultReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(
            PackageInstaller.EXTRA_STATUS,
            PackageInstaller.STATUS_FAILURE,
        )

        // 用户还没允许「安装未知应用」，需要把系统的授权页拉起来，否则安装会停在
        // 这一步，界面上什么都不发生。这不是结局，别当结局上报。
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
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
            return
        }

        ApkInstaller.publish(ApkInstaller.outcomeOf(context, intent))
    }
}
