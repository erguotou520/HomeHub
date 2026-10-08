package me.erguotou.homehub.update

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 更新卡片与启动弹框共用的状态。
 *
 * 状态用 [MutableStateFlow]：下载进度回调跑在 IO 线程上，每秒会来几十次，
 * 从后台线程写 StateFlow 是安全的，写普通 Compose 状态则有快照隐患。
 */
sealed interface UpdateUiState {

    /**
     * 干净的默认态。
     *
     * 应用进到界面上、或刚升完级重启回来，都该是这个样子：只说明当前版本，
     * 外加一个手动检查的入口 —— 不残留上一轮的任何提示。
     */
    data object Idle : UpdateUiState

    data object Checking : UpdateUiState

    /** 真的没有新版。只有手动检查会给这个反馈，自动检查静默回 [Idle]。 */
    data object UpToDate : UpdateUiState

    data class Available(val manifest: UpdateManifest, val delta: UpdateDelta?) : UpdateUiState

    data class Working(
        val stage: UpdateStage,
        val done: Long,
        val total: Long,
        val incremental: Boolean,
    ) : UpdateUiState

    data class Failed(val message: String) : UpdateUiState
}

/** 一次检查的结论：卡片显示什么，以及要不要弹框问用户。 */
internal data class CheckOutcome(val state: UpdateUiState, val prompt: Boolean)

/**
 * 检查结果 → (卡片状态, 要不要弹框)。
 *
 * 两条规则的差别全在「是不是用户点的」上：
 *
 * - **自动检查**（进应用时那一次）全程静默。网络不通、或本来就没有新版，都什么
 *   也不说 —— 一进应用就弹「无法获取更新信息」是纯噪音。只有确实有新版才弹框。
 * - **手动检查**是用户主动点的，必须给个交代：没有新版说「已是最新版本」，失败
 *   说失败。但都不弹框 —— 卡片就在眼前，再盖一层对话框只会碍事。
 */
internal fun checkOutcome(
    manifest: UpdateManifest?,
    manual: Boolean,
    currentVersionCode: Long,
): CheckOutcome = when {
    manifest == null -> if (manual) {
        CheckOutcome(UpdateUiState.Failed("无法获取更新信息，请检查网络"), prompt = false)
    } else {
        CheckOutcome(UpdateUiState.Idle, prompt = false)
    }

    !manifest.isNewerThan(currentVersionCode) -> if (manual) {
        CheckOutcome(UpdateUiState.UpToDate, prompt = false)
    } else {
        CheckOutcome(UpdateUiState.Idle, prompt = false)
    }

    else -> CheckOutcome(UpdateUiState.Available(manifest, delta = null), prompt = !manual)
}

/**
 * 更新的应用级状态。
 *
 * 这些状态原先住在设置页那张卡片的 `remember` 里，现在有两处要消费它 —— 启动后
 * 那个「要不要升级」的弹框，和设置页的卡片 —— 所以提到这里来。
 *
 * 放在 ViewModel 而不是 `remember`：下载 99 MB / 合并增量包是个长协程，不该因为
 * 一次 Activity 重建就被砍掉；同时 ViewModel 活在进程里、比界面久，正好用来保证
 * 「每个进程只自动检查一次」。
 */
class UpdateViewModel(application: Application) : AndroidViewModel(application) {

    private val manager = UpdateManager(application)

    private val _state = MutableStateFlow<UpdateUiState>(UpdateUiState.Idle)
    val state: StateFlow<UpdateUiState> = _state

    /** 非 null 表示「自动检查发现了这个版本」，弹框据此显示；处理完置回 null。 */
    private val _prompt = MutableStateFlow<UpdateManifest?>(null)
    val prompt: StateFlow<UpdateManifest?> = _prompt

    val currentVersionName: String = manager.currentVersionName
    val currentVersionCode: Long = manager.currentVersionCode

    /** 正在等哪一次安装的结局；[ApkInstaller.NO_ATTEMPT] 表示没在等。 */
    private var awaiting = ApkInstaller.NO_ATTEMPT

    /**
     * 已经落盘且校验通过的安装包。
     *
     * 留着是为了「重试」：用户把系统安装框取消了，重试就该直接把安装会话再递一次，
     * 而不是重新下 99 MB 全量包。
     */
    private var prepared: Prepared? = null

    private data class Prepared(val apk: File, val targetVersionCode: Long)

    private var autoChecked = false

    init {
        // 安装结局的唯一消费者。
        //
        // 这条广播**可能迟到很久**：换包会先把进程杀掉，广播随后才到，系统于是
        // 事后冷启一个进程专门投递它（真机日志：13:43:32 换包 → 13:43:35
        // `Start proc ... for broadcast {InstallResultReceiver}`）。那一刻没有
        // 任何界面在等结果，值就躺在 `ApkInstaller.result` 里；等用户过几分钟
        // 打开应用，这里一并收掉 —— 编号对不上就静默丢弃，绝不重放给用户。
        viewModelScope.launch {
            ApkInstaller.result.collect { outcome ->
                if (outcome == null) return@collect
                if (!ApkInstaller.isApplicable(outcome, awaiting)) {
                    ApkInstaller.clear()
                    return@collect
                }
                awaiting = ApkInstaller.NO_ATTEMPT
                ApkInstaller.clear()
                _state.value = if (outcome.success) {
                    UpdateUiState.Idle
                } else {
                    UpdateUiState.Failed(outcome.message)
                }
                // 装好了，弹框收工；失败则留着，让用户当场看见失败原因。
                if (outcome.success) {
                    _prompt.value = null
                    prepared = null
                }
            }
        }
    }

    /**
     * 进应用后自动检查一次，每个进程只跑一次。
     *
     * 由界面在解锁之后调用（锁屏期间弹升级框没有意义）。
     */
    fun autoCheck() {
        if (autoChecked) return
        autoChecked = true
        check(manual = false)
    }

    fun check(manual: Boolean = true) {
        _state.value = UpdateUiState.Checking
        viewModelScope.launch {
            val manifest = manager.check()
            var outcome = checkOutcome(manifest, manual, manager.currentVersionCode)
            val available = outcome.state as? UpdateUiState.Available
            if (available != null) {
                // 只有版本号命中才去算本机 APK 的指纹 —— 那要读完 99 MB，得挪到
                // IO 上去，放在主线程会让界面卡住好几秒。
                val delta = withContext(Dispatchers.IO) { manager.deltasMatch(available.manifest) }
                outcome = outcome.copy(state = UpdateUiState.Available(available.manifest, delta))
            }
            _state.value = outcome.state
            // 清单指向的版本换了，上一次准备出来的包就不再是对应关系了。
            if (prepared?.targetVersionCode != (outcome.state as? UpdateUiState.Available)?.manifest?.versionCode) {
                prepared = null
            }
            if (outcome.prompt) {
                _prompt.value = (outcome.state as UpdateUiState.Available).manifest
            }
        }
    }

    /** 弹框里点了「立即升级」，或卡片里点了「下载并安装」。 */
    fun startUpdate() {
        val available = _state.value as? UpdateUiState.Available ?: return
        val manifest = available.manifest
        val delta = available.delta
        _state.value = UpdateUiState.Working(
            stage = UpdateStage.DOWNLOADING,
            done = 0,
            total = delta?.size ?: (manifest.apk?.size ?: -1),
            incremental = delta != null,
        )
        viewModelScope.launch {
            val result = manager.prepare(
                manifest = manifest,
                onStage = { stage ->
                    val prev = _state.value
                    if (prev is UpdateUiState.Working) _state.value = prev.copy(stage = stage)
                },
                onProgress = { done, total ->
                    val prev = _state.value
                    if (prev is UpdateUiState.Working) _state.value = prev.copy(done = done, total = total)
                },
            )
            when (result) {
                is UpdateResult.Failed -> _state.value = UpdateUiState.Failed(result.reason)
                is UpdateResult.Ready -> submit(Prepared(result.apk, manifest.versionCode))
            }
        }
    }

    /**
     * 失败之后的「重试」。
     *
     * 包已经准备好（多半是用户在系统安装框上点了取消）就只把会话再递一次 ——
     * 重下 99 MB 去装同一个包没有任何意义；否则老老实实重新检查一遍。
     */
    fun retry() {
        val ready = prepared
        if (ready != null && ready.apk.isFile) {
            submit(ready)
            return
        }
        check()
    }

    /** 把准备就绪的包交给系统安装器，并记住在等哪一次结局。 */
    private fun submit(ready: Prepared) {
        prepared = ready
        // 目标版本随会话一起交给系统，结局回来时用它和「已安装版本」对账。
        when (val start = ApkInstaller.install(getApplication(), ready.apk, ready.targetVersionCode)) {
            is InstallStart.Refused -> _state.value = UpdateUiState.Failed(start.reason)
            is InstallStart.Submitted -> {
                awaiting = start.attempt
                // 球在系统那边了（ColorOS 还要连点三层），文案如实说。
                _state.value = UpdateUiState.Working(
                    stage = UpdateStage.INSTALLING,
                    done = 0,
                    total = -1,
                    incremental = false,
                )
            }
        }
    }

    /** 「稍后」/「后台继续」/「知道了」：收起弹框，下载与安装照旧。 */
    fun dismissPrompt() {
        _prompt.value = null
    }
}
