package me.erguotou.homehub.ui.screens.monitor

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import me.erguotou.homehub.data.HttpClientFactory
import me.erguotou.homehub.data.Prefs
import me.erguotou.homehub.data.nvr.NvrApi
import me.erguotou.homehub.data.nvr.NvrClient

/**
 * UI state for the monitor tab.
 *
 * 没有登录态，但**有共享令牌**：服务端数据面（摄像头列表 / 录像段 / 回放 / 实时）
 * 要 `runtime.security.device-token`，App 在 设置 → 服务器 → 访问令牌 里填同一个；
 * 后台没配令牌时留空即可（服务端就不校验）。摄像头的增删改、保留策略仍然只在
 * HomeHub 后台做。
 *
 * 取流与云台**都不直连摄像头**：手机拿到的 `rtsp_url` 是打码的（凭据被换成 `<掩码>`），
 * 而且手机通常不在摄像头网段 —— 直连 RTSP / ONVIF 必然失败。实时画面走服务端
 * HLS 中继（`/api/nvr/live/{cam}?stream=`），云台走服务端代理
 * （`POST /api/nvr/cameras/{cam}/ptz`）。
 */
data class MonitorUiState(
    // cameras
    val loading: Boolean = false,
    val error: String? = null,
    val cameras: List<NvrApi.Camera> = emptyList(),

    // settings
    val settings: NvrApi.Settings? = null,

    // segments for the selected camera
    val segmentsLoading: Boolean = false,
    val segments: List<NvrApi.Segment> = emptyList(),
    val nextBefore: Long? = null,

    // same-day rail: segments covering the picked day (+3h look-back)
    val dayOffset: Int = 0,
    val daySegmentsCameraId: Long? = null,
    val daySegments: List<NvrApi.Segment> = emptyList(),
    val dayLoading: Boolean = false,

    // one-shot action feedback
    val notice: String? = null
)

class MonitorViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = Prefs(app)

    /** One NvrClient for the process; survives tab switches. */
    private val nvr = NvrClient(
        prefs,
        HttpClientFactory.create(prefs)
    )

    private val _ui = MutableStateFlow(MonitorUiState())
    val ui: StateFlow<MonitorUiState> = _ui.asStateFlow()

    val isServerConfigured: Boolean get() = prefs.isServerConfigured()

    init {
        // 进 tab 就有数据，不用等用户操作。
        if (isServerConfigured) refresh()
    }

    fun setNotice(msg: String?) {
        _ui.value = _ui.value.copy(notice = msg)
    }

    fun consumeNotice() {
        _ui.value = _ui.value.copy(notice = null)
    }

    // ─────────────────────────── cameras ───────────────────────────

    fun refresh() {
        if (!isServerConfigured) return
        viewModelScope.launch {
            _ui.value = _ui.value.copy(loading = true, error = null)
            runCatching { nvr.cameras() }
                .onSuccess { list ->
                    _ui.value = _ui.value.copy(loading = false, cameras = list.cameras)
                }
                .onFailure { e ->
                    _ui.value = _ui.value.copy(loading = false, error = friendly(e))
                }
            // settings is independent of the camera list result
            runCatching { nvr.settings() }
                .onSuccess { s -> _ui.value = _ui.value.copy(settings = s) }
                .onFailure { /* non-fatal: keep stale settings */ }
        }
    }

    fun addCamera(name: String, label: String, rtspUrl: String, recordStream: Int, recordAudio: Boolean) {
        if (!isServerConfigured) return
        viewModelScope.launch {
            _ui.value = _ui.value.copy(loading = true, error = null)
            runCatching {
                nvr.addCamera(
                    NvrApi.CameraBody(
                        name = name,
                        label = label.ifBlank { null },
                        rtsp_url = rtspUrl,
                        record_stream = recordStream,
                        record_audio = recordAudio,
                        enabled = true
                    )
                )
            }.onSuccess {
                _ui.value = _ui.value.copy(loading = false, notice = "已添加 ${it.name}")
                refresh()
            }.onFailure { e ->
                _ui.value = _ui.value.copy(loading = false, error = friendly(e))
            }
        }
    }

    fun updateCamera(camera: NvrApi.Camera, label: String, enabled: Boolean, recordAudio: Boolean) {
        if (!isServerConfigured) return
        viewModelScope.launch {
            runCatching {
                nvr.updateCamera(
                    camera.id,
                    NvrApi.CameraBody(
                        label = label,
                        rtsp_url = camera.rtsp_url,
                        record_stream = camera.record_stream,
                        record_audio = recordAudio,
                        enabled = enabled
                    )
                )
            }.onSuccess { refresh() }
                .onFailure { e ->
                    _ui.value = _ui.value.copy(error = friendly(e))
                }
        }
    }

    fun deleteCamera(camera: NvrApi.Camera) {
        if (!isServerConfigured) return
        viewModelScope.launch {
            runCatching { nvr.deleteCamera(camera.id) }
                .onSuccess {
                    _ui.value = _ui.value.copy(notice = "已删除 ${camera.name}")
                    refresh()
                }
                .onFailure { e ->
                    _ui.value = _ui.value.copy(error = friendly(e))
                }
        }
    }

    fun restartCamera(camera: NvrApi.Camera) {
        if (!isServerConfigured) return
        viewModelScope.launch {
            runCatching { nvr.restartCamera(camera.id) }
                .onSuccess {
                    _ui.value = _ui.value.copy(notice = "已重启 ${camera.name} 录像")
                    refresh()
                }
                .onFailure { e ->
                    _ui.value = _ui.value.copy(error = friendly(e))
                }
        }
    }

    // ─────────────────────────── settings ───────────────────────────

    fun saveSettings(retainDays: Int, maxGb: Int) {
        val s = _ui.value.settings ?: return
        if (!isServerConfigured) return
        viewModelScope.launch {
            runCatching {
                nvr.updateSettings(
                    NvrApi.SettingsBody(
                        enabled = true,
                        retain_days = retainDays,
                        max_gb = maxGb,
                        segment_secs = s.segment_secs,
                        timezone = s.timezone
                    )
                )
            }.onSuccess {
                _ui.value = _ui.value.copy(notice = "已保存")
                runCatching { nvr.settings() }
                    .onSuccess { _ui.value = _ui.value.copy(settings = it) }
            }.onFailure { e ->
                _ui.value = _ui.value.copy(error = friendly(e))
            }
        }
    }

    fun runCleanup() {
        if (!isServerConfigured) return
        viewModelScope.launch {
            runCatching { nvr.cleanup() }
                .onSuccess { r ->
                    _ui.value = _ui.value.copy(notice = "清理完成：删除 ${r.removed}，剩余 ${r.remaining}")
                    runCatching { nvr.settings() }
                        .onSuccess { _ui.value = _ui.value.copy(settings = it) }
                }
                .onFailure { e ->
                    _ui.value = _ui.value.copy(error = friendly(e))
                }
        }
    }

    // ─────────────────────────── segments ───────────────────────────

    fun loadSegments(camera: NvrApi.Camera, before: Long? = null) {
        if (!isServerConfigured) return
        viewModelScope.launch {
            _ui.value = _ui.value.copy(segmentsLoading = true)
            runCatching { nvr.segments(cameraId = camera.id, camera = null, limit = 50, before = before) }
                .onSuccess { page ->
                    _ui.value = _ui.value.copy(
                        segmentsLoading = false,
                        segments = if (before == null) page.segments else _ui.value.segments + page.segments,
                        nextBefore = page.next_before
                    )
                }
                .onFailure { e ->
                    _ui.value = _ui.value.copy(segmentsLoading = false, error = friendly(e))
                }
        }
    }

    fun deleteSegment(segment: NvrApi.Segment) {
        if (!isServerConfigured) return
        viewModelScope.launch {
            runCatching { nvr.deleteSegment(segment.id) }
                .onSuccess {
                    _ui.value = _ui.value.copy(
                        segments = _ui.value.segments.filterNot { it.id == segment.id }
                    )
                }
                .onFailure { e ->
                    _ui.value = _ui.value.copy(error = friendly(e))
                }
        }
    }

    /** 回放地址（数据面，无凭据；服务端支持 Range）。 */
    fun playbackUrl(segment: NvrApi.Segment): String = nvr.segmentUrl(segment.id)

    /** 同上，按 id 取。历史录制要整段下载时用。 */
    fun segmentUrl(segmentId: Long): String = nvr.segmentUrl(segmentId)

    /**
     * 服务端 HLS 中继地址 —— 手机端**唯一**的实时取流通道。
     *
     * 手机拿到的 `rtsp_url` 是打码的、且通常不在摄像头网段，直连 RTSP 必然失败；
     * 拉哪一路由 `stream` 决定（服务端的 `preview_stream` 只是默认值，这里显式传，
     * 因为用户可以点角标临时切）。
     *
     * **不能带尾斜杠**：`/api/nvr/live/main/` 匹配不到任何路由，会被后台的 SPA
     * 静态兜底接走，返回首页 HTML。播放列表里的分片 URI 已由服务端改写成绝对
     * 路径（`/api/nvr/live/main/seg000001.m4s`），不依赖请求 URL 的尾斜杠。
     */
    fun liveHlsUrl(camera: NvrApi.Camera, main: Boolean = camera.preview_stream == "main"): String =
        "${baseUrl()}/api/nvr/live/${camera.name}?stream=${if (main) "main" else "sub"}"

    /**
     * 云台，走服务端代理。
     *
     * [action] 只有 `move`（持续移动，松手要补一发 `stop`）和 `stop`；方向按 ONVIF
     * 约定：x 负=左/正=右，y 负=上/正=下，z 负=拉远/正=拉近。失败会抛 HTTP 400，
     * 异常消息里是摄像头自己回的 ONVIF 故障码（例如 `ter:ActionNotSupported` 表示
     * 这路能力设备没实现，不是网络问题）。
     */
    suspend fun ptz(camName: String, action: String, x: Double = 0.0, y: Double = 0.0, z: Double = 0.0) {
        nvr.ptz(camName, action, x, y, z)
    }

    /** 数据面用的原始客户端：自动带 `X-Device-Token`，用于直取分片/整段。 */
    fun deviceClient(): OkHttpClient = nvr.deviceClient()

    /** 数据面共享令牌（后台「监控 → 手机访问令牌」），空 = 服务端没开校验。 */
    fun deviceToken(): String = nvr.deviceToken()

    /** Base URL without trailing slash. */
    fun baseUrl(): String = nvr.baseUrl()

    fun setDayOffset(offset: Int) {
        _ui.value = _ui.value.copy(dayOffset = offset)
    }

    /**
     * 取 [camera] 在第 [offset] 天的全部录像段，供 24 小时导轨使用。
     *
     * 一天最多 1440 段（60s/段），而接口单页上限 500 → 必须翻页，否则导轨只覆盖
     * 前面那几个小时，用户拖到后半段会看到「没有录像」。
     */
    fun loadDaySegments(camera: NvrApi.Camera, offset: Int? = null) {
        if (!isServerConfigured) return
        viewModelScope.launch {
            val off = offset ?: _ui.value.dayOffset
            if (_ui.value.daySegmentsCameraId == camera.id &&
                _ui.value.dayOffset == off && _ui.value.daySegments.isNotEmpty()
            ) return@launch

            val zone = java.time.ZoneId.systemDefault()
            val dayStart = java.time.LocalDate.now(zone).minusDays(off.toLong()).atStartOfDay(zone).toEpochSecond()
            val dayEnd = dayStart + 24 * 3600

            _ui.value = _ui.value.copy(
                dayLoading = true,
                daySegmentsCameraId = camera.id,
                dayOffset = off,
                daySegments = emptyList()
            )

            var all = emptyList<NvrApi.Segment>()
            var before: Long? = null
            var pages = 0
            var err: String? = null
            while (pages < 8) {
                val got = runCatching {
                    nvr.segments(
                        cameraId = camera.id, camera = null, limit = 500,
                        before = before, from = dayStart, to = dayEnd
                    )
                }
                val body = got.getOrNull()
                if (body == null) {
                    err = friendly(got.exceptionOrNull() ?: IllegalStateException("加载失败"))
                    break
                }
                all = all + body.segments
                pages++
                val next = body.next_before
                if (body.segments.size < 500 || next == null) break
                before = next
            }

            _ui.value = _ui.value.copy(
                dayLoading = false,
                daySegments = all.sortedBy { it.start_time }
            )
            if (err != null) _ui.value = _ui.value.copy(notice = "当天录像加载失败：$err")
        }
    }
}

/** One-line human-readable error text for snackbar/error surfaces. */
private fun friendly(e: Throwable): String {
    // 401 只有一个可操作的含义：令牌没填或和后台不一致。直接说清楚去哪儿改，
    // 比抛一个 "HTTP 401 Unauthorized" 有用得多。
    if (e is retrofit2.HttpException && e.code() == 401) {
        return "访问被拒绝(401)：请在 设置 → 服务器 → 访问令牌 里填写与后台一致的值"
    }
    val raw = e.message ?: e::class.java.simpleName
    val first = raw.lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: raw
    val line = if (first.startsWith("HTTP ")) first else raw.take(160)
    return if (line.length > 160) line.take(160) + "…" else line
}
