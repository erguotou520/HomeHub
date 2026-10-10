package me.erguotou.homehub.ui.screens.monitor

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.view.SurfaceView
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.PixelCopy
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.Cameraswitch
import androidx.compose.material.icons.outlined.CenterFocusStrong
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FiberManualRecord
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material.icons.outlined.FullscreenExit
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material.icons.outlined.VolumeOff
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material.icons.outlined.ZoomIn
import androidx.compose.material.icons.outlined.ZoomOut
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import me.erguotou.homehub.data.nvr.NvrApi
import me.erguotou.homehub.data.nvr.NvrClient
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 监控预览页（监控 tab 的全部内容）。
 *
 * 版面（自上而下）：摄像头切换 chip → 居中的「实时 / 历史」胶囊 → （历史）日期 +
 * 24 小时录像导轨 → 16:9 视频台 → 工具栏。
 *
 * 取流：
 *  - 实时：**只走服务端 HLS 中继**（`/api/nvr/live/{cam}?stream=sub|main`）。手机端
 *    拿到的 `rtsp_url` 是**打码**的（凭据被换成 `<掩码>`），直连 RTSP 必然鉴权
 *    失败；而且手机通常不在摄像头网段。是不是子码流由服务端的 `preview_stream`
 *    决定，这里只负责传 `?stream=`。
 *  - 历史：`/api/nvr/segments/{id}`，服务端支持 Range；一段放完自动续下一段；
 *    倍速由本地 ExoPlayer 完成，播放器自带控件一律关闭（进度/播放由导轨承担）。
 *
 * 云台：走服务端代理 `POST /api/nvr/cameras/{cam}/ptz`（服务端有摄像头凭据且能网络
 * 可达），手机端不再直连 ONVIF。
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun MonitorPreviewScreen(
    initialCamera: NvrApi.Camera? = null,
    onFullscreenChange: (Boolean) -> Unit = {},
    vm: MonitorViewModel = viewModel()
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val ui by vm.ui.collectAsStateWithLifecycle()

    // ─────────────────────────── 摄像头选择 ───────────────────────────
    val enabledCams = remember(ui.cameras) { ui.cameras.filter { it.enabled } }
    var selectedId by remember { mutableStateOf(initialCamera?.id) }
    var camera by remember {
        mutableStateOf(enabledCams.firstOrNull { it.id == selectedId } ?: enabledCams.firstOrNull())
    }
    LaunchedEffect(ui.cameras) {
        val pick = enabledCams.firstOrNull { it.id == selectedId } ?: enabledCams.firstOrNull()
        if (pick != null) {
            if (selectedId == null || pick.id != selectedId) selectedId = pick.id
            if (pick.id != camera?.id) camera = pick
        }
    }

    // ─────────────────────────── tab / 播放状态 ───────────────────────────
    var tab by remember { mutableStateOf(0) } // 0 = 实时, 1 = 历史
    val isLiveTab = tab == 0

    /** 实时看主码流还是子码流。默认跟随后台的 `preview_stream`（一般是子码流）。 */
    var useMain by remember(camera?.id) { mutableStateOf(camera?.preview_stream == "main") }
    var muted by remember { mutableStateOf(false) }
    var paused by remember { mutableStateOf(false) }
    var playingSegment by remember { mutableStateOf<NvrApi.Segment?>(null) }
    var pendingSeek by remember { mutableStateOf<Long?>(null) }
    var playerError by remember { mutableStateOf<String?>(null) }
    var railMsg by remember { mutableStateOf<String?>(null) }
    var speed by remember { mutableStateOf(1f) }
    var ptzOpen by remember { mutableStateOf(false) }
    var ptzError by remember(camera?.name) { mutableStateOf<String?>(null) }
    var playhead by remember { mutableStateOf(0L) }
    var fullscreen by remember { mutableStateOf(false) }
    var pickerOpen by remember { mutableStateOf(false) }

    var dayOffset by remember { mutableStateOf(ui.dayOffset) }

    val isReplay = playingSegment != null
    val hlsUrl = remember(camera, useMain) {
        camera?.let { vm.liveHlsUrl(it, main = useMain) } ?: ""
    }
    val url: String = when {
        isReplay -> vm.playbackUrl(playingSegment!!)
        isLiveTab -> hlsUrl
        else -> "" // 历史 tab 未选段：画面保持黑
    }
    val hasUrl = url.isNotBlank()

    val zone = java.time.ZoneId.systemDefault()
    val dayStart = remember(dayOffset) {
        java.time.LocalDate.now(zone).minusDays(dayOffset.toLong()).atStartOfDay(zone).toEpochSecond()
    }

    // 全屏时藏掉底部导航栏（Root 的 Scaffold 底部栏跟着这个开关）。
    LaunchedEffect(fullscreen) { onFullscreenChange(fullscreen) }
    DisposableEffect(Unit) {
        onDispose { onFullscreenChange(false) }
    }

    // ─────────────────────────── 播放器 ───────────────────────────
    val player = remember { ExoPlayer.Builder(context).build() }
    DisposableEffect(Unit) {
        onDispose {
            player.stop()
            player.release()
        }
    }

    // 数据面要共享令牌（后台「监控 → 手机访问令牌」），播放器设不了「请求头」以外
    // 的位置，`?token=` 又会漏进代理日志，所以两路播放器都挂默认请求头。
    // 令牌为空时传空 map，服务端没开校验时行为与从前一致。
    val deviceToken = remember { vm.deviceToken() }
    val requestHeaders = remember(deviceToken) {
        if (deviceToken.isEmpty()) emptyMap()
        else mapOf(NvrClient.DEVICE_TOKEN_HEADER to deviceToken)
    }

    // HLS 中继在服务端数据面上。超时要放宽：服务端第一次请求要现场拉起 ffmpeg
    // 并等播放列表稳定（最长 8s），默认 8s 读超时会让它误判成网络错误。
    val hlsFactory = remember(requestHeaders) {
        HlsMediaSource.Factory(
            DefaultHttpDataSource.Factory()
                .setConnectTimeoutMs(10_000)
                .setReadTimeoutMs(30_000)
                .setDefaultRequestProperties(requestHeaders)
        )
    }

    // 回放分片走普通 MP4 数据源，同样要把令牌带上。
    val mp4Factory = remember(requestHeaders) {
        DefaultMediaSourceFactory(
            DefaultHttpDataSource.Factory()
                .setConnectTimeoutMs(10_000)
                .setReadTimeoutMs(30_000)
                .setDefaultRequestProperties(requestHeaders)
        )
    }

    LaunchedEffect(url, isReplay) {
        if (!hasUrl) {
            player.stop()
            return@LaunchedEffect
        }
        playerError = null
        player.stop()
        val source = if (isReplay) {
            mp4Factory.createMediaSource(MediaItem.fromUri(url))
        } else {
            hlsFactory.createMediaSource(MediaItem.fromUri(url))
        }
        player.setMediaSource(source)
        player.volume = if (muted) 0f else 1f
        player.setPlaybackSpeed(if (isReplay) speed else 1f)
        player.prepare()
        // 从导轨切段时携带的目标时刻：源换好之后立刻定位
        val target = pendingSeek
        val seg = playingSegment
        if (target != null) {
            if (isReplay && seg != null) {
                player.seekTo((target - seg.start_time).coerceAtLeast(0L) * 1000L)
            }
            pendingSeek = null
        }
        player.playWhenReady = !paused
    }

    LaunchedEffect(muted) { player.volume = if (muted) 0f else 1f }
    LaunchedEffect(paused) { if (paused) player.pause() else player.playWhenReady = true }
    LaunchedEffect(speed, isReplay) { player.setPlaybackSpeed(if (isReplay) speed else 1f) }

    // 历史 tab 未选段：输入源为空 → 什么都没在播
    val historyIdle = !isLiveTab && !isReplay

    // 历史播放位置（每 0.25s 刷新，驱动导轨指针与上方时间标签；倍速播放时同样跟得上）
    //
    // `dayOffset` 必须进 key：`dayStart` 是 `remember(dayOffset)` 算出来的，不重启这个
    // effect 的话闭包里会一直是旧的那天。
    LaunchedEffect(isReplay, playingSegment?.id, isLiveTab, dayOffset) {
        if (isLiveTab) return@LaunchedEffect
        while (isActive) {
            val seg = playingSegment
            val pos = player.currentPosition
            playhead = when {
                seg != null -> seg.start_time + (if (pos > 0) pos / 1000L else 0L)
                // 没在播的段：今天停在「此刻」，看往日时停在当天 00:00。
                // 以前一律用「此刻」，切到往日会被下面 rail 的 coerceIn 钳成 23:59:59，
                // 指针贴在最右边，看着像故障。
                dayOffset == 0 -> System.currentTimeMillis() / 1000L
                else -> dayStart
            }
            delay(250)
        }
    }

    // ──────────────────────── 自动续播下一段 ────────────────────────
    fun advanceFrom(seg: NvrApi.Segment) {
        val list = vm.ui.value.daySegments
        val next = list.firstOrNull { it.start_time > seg.start_time }
        if (next != null) {
            pendingSeek = null
            playingSegment = next
            playhead = next.start_time
            playerError = null
        } else {
            paused = true
            railMsg = "这一天后面没有更多录像了"
        }
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState != Player.STATE_ENDED) return
                val seg = playingSegment
                if (seg != null) advanceFrom(seg)
            }

            override fun onPlayerError(error: PlaybackException) {
                playerError = if (playingSegment != null) {
                    "回放异常：${error.errorCodeName}"
                } else {
                    "实时播放失败：${error.errorCodeName}"
                }
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    // ─────────────────────────── 云台（走服务端代理） ───────────────────────────
    var ptzBusy by remember { mutableStateOf(false) }

    // PtzPad 的按住回调天然是 Float（0f / ±1f 的方向量），这里收 Float 再转 Double，
    // 免得每个方向键常量都写 `.toDouble()`。
    fun ptzMove(x: Float, y: Float, z: Float) {
        val cam = camera ?: return
        scope.launch(Dispatchers.IO) {
            runCatching { vm.ptz(cam.name, "move", x.toDouble(), y.toDouble(), z.toDouble()) }
                .onFailure { e -> withContext(Dispatchers.Main) { ptzError = friendlyMsg(e) } }
                .onSuccess { withContext(Dispatchers.Main) { ptzError = null } }
        }
    }
    fun ptzStop() {
        val cam = camera ?: return
        scope.launch(Dispatchers.IO) { runCatching { vm.ptz(cam.name, "stop") } }
    }

    // ─────────────────────────── 历史 tab 数据 ───────────────────────────
    LaunchedEffect(camera?.id, isLiveTab, dayOffset) {
        val cam = camera ?: return@LaunchedEffect
        if (!isLiveTab) vm.loadDaySegments(cam, dayOffset)
    }

    // 导轨选中：同一段内直接 seek；跨段则换源并记住目标时刻
    fun commitSeek(t: Long, seg: NvrApi.Segment?) {
        if (seg == null) {
            railMsg = "该时间点没有录像"
            return
        }
        railMsg = null
        val cur = playingSegment
        if (cur != null && cur.id == seg.id) {
            player.seekTo((t - seg.start_time).coerceAtLeast(0L) * 1000L)
            paused = false
            player.playWhenReady = true
        } else {
            pendingSeek = t
            playingSegment = seg
            paused = false
        }
    }

    // ─────────────────────────── 截图 / 录制 ───────────────────────────
    var snapMsg by remember { mutableStateOf<String?>(null) }

    /**
     * 「截图进行中」必须独立于 [snapMsg]。
     *
     * 以前用 `snapMsg != null` 当繁忙标志，而失败分支只赋值、不清空 → 一次失败就把截图
     * 按钮永久禁用（enabled = !snapshotBusy），这正是「截图功能不完整」的一半原因。
     */
    var snapBusy by remember { mutableStateOf(false) }
    var recState by remember { mutableStateOf(RecState()) }
    var talkOpen by remember { mutableStateOf(false) }
    var pvRef by remember { mutableStateOf<PlayerView?>(null) }

    val recSession = remember { mutableMapOf<String, Any?>().also { it["vm"] = vm } }
    recSession["onTick"] = { b: Long -> recState = recState.copy(captured = b) }
    recSession["onError"] = { msg: String -> recState = RecState(error = msg) }

    fun onSnapshot() {
        if (snapBusy) return
        val pv = pvRef
        if (pv == null) {
            snapMsg = "暂没有视频帧，等画面出来后再试"
            return
        }
        snapBusy = true
        scope.launch {
            val shot = captureFrame(pv)
            val msg = shot.fold(
                onSuccess = { bmp ->
                    if (saveSnapshot(context, bmp, camera)) "截图已保存到相册" else "截图保存失败"
                },
                onFailure = { e -> "截图失败：${e.message}" }
            )
            withContext(Dispatchers.Main) {
                snapBusy = false
                snapMsg = msg
                delay(2500)
                snapMsg = null
            }
        }
    }

    /** 工具栏上的「录制」：实时录直播流，历史录当前这一段。 */
    fun onRecordToggle() {
        val cam = camera ?: return
        if (isLiveTab) {
            if (recState.recording) {
                recState = recState.copy(saving = true)
                scope.launch { stopRecording(recSession, context) { s -> recState = s } }
            } else {
                startLiveRecording(recSession, cam, useMain)
                recState = RecState(recording = true, startedAt = System.currentTimeMillis() / 1000, kind = RecordKind.Live)
            }
        } else {
            val seg = playingSegment
            if (seg == null) {
                railMsg = "先选一段录像再录"
                return
            }
            if (recState.recording) {
                val end = playhead.coerceAtMost(seg.start_time + seg.duration.toLong())
                recState = recState.copy(recording = false, saving = true)
                scope.launch {
                    val ok = saveClip(context, vm, seg, recState.startedAt, end)
                    recState = if (ok) {
                        RecState()
                    } else {
                        RecState(error = "录像保存失败，请重试")
                    }
                }
            } else {
                recState = RecState(
                    recording = true,
                    startedAt = playhead.coerceAtLeast(seg.start_time),
                    kind = RecordKind.Clip
                )
            }
        }
    }

    val cam = camera
    if (cam == null) {
        EmptyMonitor(loading = ui.loading, error = ui.error)
        return
    }

    // 全屏时不吃状态栏内边距（视频要顶到边），普通模式要避开状态栏 —— Root 的
    // Scaffold 用的是 WindowInsets(0)，其它 tab 靠各自的 TopAppBar 让位，监控 tab
    // 没有 TopAppBar，不补这一段的话顶部那排摄像头 chip 会被状态栏压住点不到。
    val inset = if (fullscreen) Modifier else Modifier.statusBarsPadding()

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(modifier = Modifier.fillMaxSize().then(inset)) {
            if (!fullscreen) {
                // ── 摄像头切换 + 状态 ──
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FlowRow(
                        modifier = Modifier.weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        enabledCams.forEach { c ->
                            FilterChipMini(
                                text = c.label ?: c.name,
                                selected = c.id == cam.id,
                                onClick = {
                                    if (c.id != cam.id) {
                                        selectedId = c.id
                                        playingSegment = null
                                        pendingSeek = null
                                        playhead = 0L
                                        ptzOpen = false
                                        railMsg = null
                                        ptzError = null
                                    }
                                }
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(statusColor(cam.status))
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        statusLabel(cam.status),
                        style = MaterialTheme.typography.labelMedium,
                        color = statusColor(cam.status)
                    )
                }

                // ── 居中的 实时 / 历史 ──
                Box(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    contentAlignment = Alignment.Center
                ) {
                    TabPill(
                        isLive = isLiveTab,
                        onSelect = { live ->
                            if (live == isLiveTab) return@TabPill
                            tab = if (live) 0 else 1
                            // 切 tab 自动清理回放资源（原来的「退出回放」按钮已去掉）
                            playingSegment = null
                            pendingSeek = null
                            paused = false
                            ptzOpen = false
                            railMsg = null
                            playerError = null
                            recState = RecState()
                            snapMsg = null
                        }
                    )
                }

                // ── 历史：日期选择（点日期弹日历） ──
                if (!isLiveTab) {
                    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        DayPicker(
                            onPrev = {
                                dayOffset += 1
                                playingSegment = null
                                pendingSeek = null
                                playhead = 0L
                                railMsg = null
                                vm.setDayOffset(dayOffset)
                            },
                            onNext = {
                                dayOffset -= 1
                                playingSegment = null
                                pendingSeek = null
                                playhead = 0L
                                railMsg = null
                                vm.setDayOffset(dayOffset)
                            },
                            onPick = { pickerOpen = true },
                            label = dayLabel(dayOffset)
                        )
                    }
                }
            }

            // ── 视频台 ──
            val stageModifier = if (fullscreen) {
                Modifier.fillMaxWidth().weight(1f)
            } else {
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(16.dp))
            }
            Box(modifier = stageModifier.background(Color.Black)) {
                if (hasUrl) {
                    PlayerStage(player = player, onPlayerView = { pvRef = it })
                } else {
                    Text(
                        if (historyIdle) "点下面的时间轴选择一段录像" else "正在连接…",
                        color = Color.White.copy(alpha = 0.6f),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.align(Alignment.Center)
                    )
                }

                if (isLiveTab && !isReplay) {
                    LiveBadge(
                        recording = cam.status == "recording",
                        streamLabel = if (useMain) "实时 · 主码流" else "实时 · 子码流",
                        modifier = Modifier.align(Alignment.TopStart),
                        onClick = {
                            // 录制中不切流：录音源跟着变会得到拼接不上的文件
                            if (!recState.recording) useMain = !useMain
                        }
                    )
                }
                if (recState.recording) {
                    RecordingBadge(
                        captured = recState.captured,
                        label = if (recState.kind == RecordKind.Live) "REC" else "REC 片段",
                        modifier = Modifier.align(Alignment.TopEnd).padding(6.dp)
                    )
                }

                // 全屏切换：白色图标、无背景遮罩
                Icon(
                    if (fullscreen) Icons.Outlined.FullscreenExit else Icons.Outlined.Fullscreen,
                    contentDescription = if (fullscreen) "退出全屏" else "全屏",
                    tint = Color.White,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(8.dp)
                        .size(26.dp)
                        .shadow(3.dp, CircleShape)
                        .clickable { fullscreen = !fullscreen }
                )

                if (fullscreen) {
                    // 全屏时工具栏浮在画面上（半透明底，保证在亮画面上也看得清）
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .background(Color.Black.copy(alpha = 0.45f))
                            .padding(bottom = 34.dp)
                    ) {
                        if (isLiveTab && ptzOpen) {
                            PtzPad(
                                onMove = { x, y, z -> ptzMove(x, y, z) },
                                onStop = { ptzStop() },
                                onClose = { ptzOpen = false },
                                busy = ptzBusy,
                                error = ptzError,
                                onDark = true
                            )
                        } else {
                            ToolBar(
                                isLive = isLiveTab,
                                onDark = true,
                                muted = muted,
                                onMute = { muted = !muted },
                                paused = paused,
                                onPlayPause = { paused = !paused },
                                onPtz = { ptzOpen = true; ptzError = null },
                                onCall = { talkOpen = true },
                                onSnapshot = { onSnapshot() },
                                snapshotBusy = snapBusy,
                                recording = recState.recording,
                                onRecord = { onRecordToggle() },
                                saveBusy = recState.saving,
                                speed = speed,
                                onSpeed = { speed = it }
                            )
                        }
                    }
                }
            }

            if (!fullscreen) {
                if (!isLiveTab) {
                    Spacer(modifier = Modifier.height(8.dp))
                    DayRail(
                        segments = ui.daySegments,
                        loading = ui.dayLoading,
                        dayStart = dayStart,
                        playhead = playhead,
                        onCommit = { t, seg -> commitSeek(t, seg) }
                    )
                }

                val hint = railMsg ?: ptzError ?: snapMsg ?: recState.error
                if (hint != null) {
                    Text(
                        hint,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                // ── 工具栏 / 云台盘（原地替换，不弹窗；云台只有实时才有） ──
                if (isLiveTab && ptzOpen) {
                    PtzPad(
                        onMove = { x, y, z -> ptzMove(x, y, z) },
                        onStop = { ptzStop() },
                        onClose = { ptzOpen = false },
                        busy = ptzBusy,
                        error = ptzError,
                        onDark = false
                    )
                } else {
                    ToolBar(
                        isLive = isLiveTab,
                        onDark = false,
                        muted = muted,
                        onMute = { muted = !muted },
                        paused = paused,
                        onPlayPause = { paused = !paused },
                        onPtz = { ptzOpen = true; ptzError = null },
                        onCall = { talkOpen = true },
                        onSnapshot = { onSnapshot() },
                        snapshotBusy = snapBusy,
                        recording = recState.recording,
                        onRecord = { onRecordToggle() },
                        saveBusy = recState.saving,
                        speed = speed,
                        onSpeed = { speed = it }
                    )
                }
            }
        }

        if (talkOpen) {
            IntercomPanel(camera = cam, onDismiss = { talkOpen = false }, ptzStop = { ptzStop() })
        }
    }

    // ─────────────────────────── 日期选择器 ───────────────────────────
    if (pickerOpen) {
        val today = java.time.LocalDate.now(zone)
        val initial = remember(pickerOpen) {
            today.minusDays(dayOffset.toLong())
                .atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
        }
        val state = rememberDatePickerState(initialSelectedDateMillis = initial)
        DatePickerDialog(
            onDismissRequest = { pickerOpen = false },
            confirmButton = {
                TextButton(onClick = {
                    val ms = state.selectedDateMillis
                    if (ms != null) {
                        // DatePicker 给的是 UTC 当天零点 → 换算成"距今天几天"
                        val picked = java.time.Instant.ofEpochMilli(ms)
                            .atZone(java.time.ZoneOffset.UTC).toLocalDate()
                        val off = java.time.temporal.ChronoUnit.DAYS.between(picked, today)
                            .toInt()
                            .coerceAtLeast(0)
                        dayOffset = off
                        vm.setDayOffset(off)
                        playingSegment = null
                        pendingSeek = null
                        playhead = 0L
                        railMsg = null
                    }
                    pickerOpen = false
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { pickerOpen = false }) { Text("取消") } }
        ) {
            DatePicker(state = state, showModeToggle = false)
        }
    }
}

// ─────────────────────────── 空状态 ───────────────────────────

/**
 * 空态 = 没有可用的摄像头。
 *
 * [error] 单独显示：空列表和「拉不到列表」看起来一样，但后者是故障（服务器地址
 * 写错、服务端没起、接口 404），只显示「还没有接入监控」会把故障说成正常状态。
 */
@Composable
private fun EmptyMonitor(loading: Boolean, error: String? = null) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        if (loading) {
            CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 3.dp)
            Spacer(modifier = Modifier.height(12.dp))
            Text("正在读取监控列表…", style = MaterialTheme.typography.bodyMedium)
            return@Column
        }
        Icon(
            Icons.Outlined.Cameraswitch,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(56.dp)
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text("还没有接入监控", style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            "在 HomeHub 后台添加并启用摄像头后，这里会自动出现画面",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (!error.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                "读取失败：$error",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center
            )
        }
    }
}

// ─────────────────────────── 小控件 ───────────────────────────

/** 摄像头 chip（比 FilterChip 矮一点，多路时更省纵向空间）。 */
@Composable
private fun FilterChipMini(text: String, selected: Boolean, onClick: () -> Unit) {
    val bg = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
    val fg = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(bg)
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(text, style = MaterialTheme.typography.labelMedium, color = fg, maxLines = 1)
    }
}

/** 居中的「实时 / 历史」胶囊。 */
@Composable
private fun TabPill(isLive: Boolean, onSelect: (Boolean) -> Unit) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
    ) {
        Row(modifier = Modifier.padding(3.dp)) {
            TabPillItem("实时", isLive) { onSelect(true) }
            TabPillItem("历史", !isLive) { onSelect(false) }
        }
    }
}

@Composable
private fun TabPillItem(text: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .background(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent)
            .clickable { onClick() }
            .padding(horizontal = 28.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.onPrimary
            else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** 日期切换：左右箭头 ±1 天，**点中间日期弹日历**。 */
@Composable
private fun DayPicker(onPrev: () -> Unit, onNext: () -> Unit, onPick: () -> Unit, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onPrev, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Outlined.ChevronLeft, contentDescription = "前一天", modifier = Modifier.size(18.dp))
        }
        Surface(
            shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
            modifier = Modifier.clickable { onPick() }
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Outlined.CalendarMonth,
                    contentDescription = "选择日期",
                    modifier = Modifier.size(15.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    label,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium
                )
            }
        }
        IconButton(onClick = onNext, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Outlined.ChevronRight, contentDescription = "后一天", modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun PlayerStage(player: ExoPlayer, onPlayerView: (PlayerView) -> Unit) {
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            PlayerView(ctx).apply {
                // 播放器自带控件一律关闭：进度/暂停交给导轨与工具栏
                useController = false
                this.player = player
                onPlayerView(this)
            }
        },
        update = { pv ->
            if (pv.player !== player) pv.player = player
            pv.setUseController(false)
        }
    )
}

// ─────────────────────────── 角标 ───────────────────────────

@Composable
private fun LiveBadge(
    recording: Boolean,
    streamLabel: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = Color.Black.copy(alpha = 0.55f),
        modifier = modifier.padding(6.dp).clickable { onClick() }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Outlined.FiberManualRecord,
                contentDescription = null,
                tint = if (recording) Color(0xFFE53935) else Color(0xFF9E9E9E),
                modifier = Modifier.size(10.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(streamLabel, color = Color.White, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun RecordingBadge(captured: Long, label: String, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = Color(0xE53935).copy(alpha = 0.85f),
        modifier = modifier
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Outlined.Videocam, contentDescription = null, tint = Color.White, modifier = Modifier.size(12.dp))
            Spacer(modifier = Modifier.width(4.dp))
            Text("$label ${formatBytes(captured)}", color = Color.White, style = MaterialTheme.typography.labelSmall)
        }
    }
}

// ─────────────────────────── 24h 导轨 ───────────────────────────

/**
 * 一天的录像导轨，**整宽**（不再给右侧留时间位）：
 *  - 绿 = 普通录像，橙 = 有活动（motion >= 2），黄 = 画面变化明显（motion_score >= 0.35）
 *  - 当前时刻的时间文字显示在**指针正上方**，播放/拖动时跟着指针走
 *
 * 拖动只在本地更新指针与时间，松手才 [onCommit]（换段或 seek）——这样跨段拖动不会
 * 每移动一下就重新起播。
 */
@Composable
private fun DayRail(
    segments: List<NvrApi.Segment>,
    loading: Boolean,
    dayStart: Long,
    playhead: Long,
    onCommit: (Long, NvrApi.Segment?) -> Unit
) {
    val dayEnd = dayStart + 24 * 3600
    val span = 24 * 3600L

    if (loading && segments.isEmpty()) {
        Box(modifier = Modifier.fillMaxWidth().height(52.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        }
        return
    }

    val colored = remember(segments, dayStart) {
        segments.map { s ->
            val start = max(s.start_time, dayStart)
            val end = min(s.start_time + s.duration.toLong(), dayEnd)
            if (end > start) {
                val color = when {
                    s.motion_score >= 0.35 -> Color(0xFFFFD54F)
                    s.motion >= 2 -> Color(0xFFFFB74D)
                    else -> Color(0xFF81C784)
                }
                Triple(start, end, color)
            } else null
        }.filterNotNull()
    }

    var dragTime by remember { mutableStateOf<Long?>(null) }
    val shown = (dragTime ?: playhead).coerceIn(dayStart, dayEnd - 1)

    val segmentAt: (Long) -> NvrApi.Segment? = { t ->
        segments.firstOrNull { t >= it.start_time && t < it.start_time + it.duration.toLong() }
    }

    var railW by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val labelW = 76.dp
    val labelWpx = with(density) { labelW.toPx() }

    // 指针配色：**不能只用 Color.White** —— 轨道底色是 surfaceVariant@35%，浅色主题下
    // 实测 (241,240,250)，纯白线压上去对比度约 1.0，等于没画（指针明明在，用户看不见）。
    // 用「浅色描边 + 深色主线」两层，在浅色轨道和橙/黄/绿录像段上都能分辨；深色主题下
    // 两个颜色自动对调，同样成立。
    val pointerHalo = MaterialTheme.colorScheme.surface
    val pointerCore = MaterialTheme.colorScheme.onSurface

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(70.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                .onSizeChanged { railW = it.width }
                .pointerInput(dayStart, segments) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        down.consume()
                        val w = size.width.toFloat().coerceAtLeast(1f)
                        fun at(x: Float) =
                            (dayStart + (x / w * span)).toLong().coerceIn(dayStart, dayEnd - 1)

                        var t = at(down.position.x)
                        dragTime = t
                        while (true) {
                            val ev = awaitPointerEvent()
                            val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                            if (!ch.pressed) break
                            t = at(ch.position.x)
                            dragTime = t
                            ch.consume()
                        }
                        dragTime = null
                        onCommit(t, segmentAt(t))
                    }
                }
        ) {
            Canvas(modifier = Modifier.matchParentSize()) {
                // 上 20dp 留给时间文字，导轨从 20dp 往下
                val labelH = 20.dp.toPx()

                // 录像段
                colored.forEach { (s, e, color) ->
                    val x0 = size.width * (s - dayStart).toFloat() / span
                    val x1 = size.width * (e - dayStart).toFloat() / span
                    drawRoundRect(
                        color = color,
                        topLeft = Offset(x0, labelH + 2f),
                        size = Size((x1 - x0).coerceAtLeast(3f), size.height - labelH - 6f),
                        cornerRadius = CornerRadius(2.5f, 2.5f)
                    )
                }

                // 整点刻度（6 的倍数更长）。同样不能画白色 —— 浅色轨道上根本看不见。
                for (h in 0..24) {
                    val x = size.width * h / 24f
                    val long = h % 6 == 0
                    drawLine(
                        color = pointerCore.copy(alpha = if (long) 0.35f else 0.13f),
                        start = Offset(x, size.height - if (long) 9f else 5f),
                        end = Offset(x, size.height),
                        strokeWidth = 2f
                    )
                }

                // 当前位置指针 + 顶端小圆点（描边在下、主线在上，见 pointerHalo 注释）
                val mx = size.width * (shown - dayStart).toFloat() / span
                drawLine(
                    color = pointerHalo,
                    start = Offset(mx, labelH),
                    end = Offset(mx, size.height),
                    strokeWidth = 8f
                )
                drawLine(
                    color = pointerCore,
                    start = Offset(mx, labelH),
                    end = Offset(mx, size.height),
                    strokeWidth = 3f
                )
                drawCircle(pointerHalo, radius = 7f, center = Offset(mx, labelH))
                drawCircle(pointerCore, radius = 4.5f, center = Offset(mx, labelH))
            }

            // 时间文字：跟着指针走，钳在轨道内
            if (railW > 0) {
                val px = railW * (shown - dayStart).toFloat() / span
                val x = (px - labelWpx / 2f).coerceIn(0f, (railW - labelWpx).coerceAtLeast(0f))
                Text(
                    fmtHms(shown),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .width(labelW)
                        .offset { IntOffset(x.roundToInt(), 0) }
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            listOf("00", "06", "12", "18", "24").forEach {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

// ─────────────────────────── 工具栏 ───────────────────────────

/**
 * 工具栏。实时：云台 / 声音 / 暂停 / 通话 / 截图 / 录制；
 * 历史：倍速 / 声音 / 暂停 / 截图 / 录制。
 *
 * [onDark] 用于全屏时浮在视频上的场景：Material 的 onSurfaceVariant 在浅色主题下
 * 是深色，压在视频上看不清，所以强制白色。
 */
@Composable
private fun ToolBar(
    isLive: Boolean,
    onDark: Boolean,
    muted: Boolean,
    onMute: () -> Unit,
    paused: Boolean,
    onPlayPause: () -> Unit,
    onPtz: () -> Unit,
    onCall: () -> Unit,
    onSnapshot: () -> Unit,
    snapshotBusy: Boolean,
    recording: Boolean,
    onRecord: () -> Unit,
    saveBusy: Boolean,
    speed: Float,
    onSpeed: (Float) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        if (isLive) {
            ActionButton(null, "云台", onPtz, icon = Icons.Outlined.CenterFocusStrong, onDark = onDark)
            ActionButton(
                null,
                "声音",
                onMute,
                icon = if (muted) Icons.Outlined.VolumeOff else Icons.Outlined.VolumeUp,
                onDark = onDark
            )
            ActionButton(
                null,
                if (paused) "播放" else "暂停",
                onPlayPause,
                icon = if (paused) Icons.Outlined.PlayArrow else Icons.Outlined.Pause,
                onDark = onDark
            )
            ActionButton(null, "通话", onCall, icon = Icons.Outlined.Call, onDark = onDark)
            ActionButton(
                null,
                "截图",
                onSnapshot,
                icon = if (snapshotBusy) null else Icons.Outlined.PhotoCamera,
                enabled = !snapshotBusy,
                onDark = onDark
            )
            ActionButton(
                null,
                if (recording) "停止" else "录制",
                onRecord,
                icon = Icons.Outlined.Videocam,
                enabled = !saveBusy,
                highlight = recording,
                onDark = onDark
            )
        } else {
            SpeedButton(speed, onSpeed, onDark)
            ActionButton(
                null,
                "声音",
                onMute,
                icon = if (muted) Icons.Outlined.VolumeOff else Icons.Outlined.VolumeUp,
                onDark = onDark
            )
            ActionButton(
                null,
                if (paused) "播放" else "暂停",
                onPlayPause,
                icon = if (paused) Icons.Outlined.PlayArrow else Icons.Outlined.Pause,
                onDark = onDark
            )
            ActionButton(
                null,
                "截图",
                onSnapshot,
                icon = if (snapshotBusy) null else Icons.Outlined.PhotoCamera,
                enabled = !snapshotBusy,
                onDark = onDark
            )
            ActionButton(
                null,
                if (recording) "停止" else "录制",
                onRecord,
                icon = Icons.Outlined.Videocam,
                enabled = !saveBusy,
                highlight = recording,
                onDark = onDark
            )
        }
    }
}

/** 倍速：0.5 / 0.75 / 1 / 1.25 / 1.5 / 2 / 3 / 4 / 8。 */
private val SPEEDS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f, 3f, 4f, 8f)

@Composable
private fun SpeedButton(speed: Float, onPick: (Float) -> Unit, onDark: Boolean) {
    var open by remember { mutableStateOf(false) }
    Box {
        ActionButton(null, speedLabel(speed), { open = true }, icon = Icons.Outlined.Speed, onDark = onDark)
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            SPEEDS.forEach { s ->
                DropdownMenuItem(
                    text = {
                        Text(
                            speedLabel(s),
                            fontWeight = if (s == speed) FontWeight.SemiBold else FontWeight.Normal
                        )
                    },
                    onClick = {
                        open = false
                        onPick(s)
                    }
                )
            }
        }
    }
}

@Composable
private fun ActionButton(
    _unused: ImageVector?,
    label: String,
    onClick: () -> Unit,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    highlight: Boolean = false,
    onDark: Boolean = false
) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 8.dp, vertical = 5.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // `highlight` 只决定配色，不能决定要不要渲染内容 —— 否则传 false 的按钮
        // 会变成 0 尺寸空盒子（既看不见也点不到）。
        val tint = when {
            highlight -> Color(0xFFE53935)
            !enabled -> Color.White.copy(alpha = 0.35f).takeIf { onDark }
                ?: MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
            onDark -> Color.White
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        }
        if (icon != null) {
            Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(20.dp))
            Spacer(modifier = Modifier.height(2.dp))
            Text(label, color = tint, style = MaterialTheme.typography.labelSmall, maxLines = 1)
        } else {
            Box(modifier = Modifier.size(20.dp)) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            }
        }
    }
}

// ─────────────────────────── 云台盘（原地替换工具栏） ───────────────────────────

/**
 * 云台方向盘：按住才动（服务端发 ONVIF ContinuousMove，松手发 Stop）。
 *
 * 设备**不支持 `GotoHomePosition`**（实测 `ter:ActionNotSupported`），所以没有「回中」，
 * 中间那颗是「停止」。
 */
@Composable
private fun PtzPad(
    onMove: (Float, Float, Float) -> Unit,
    onStop: () -> Unit,
    onClose: () -> Unit,
    busy: Boolean,
    error: String?,
    onDark: Boolean
) {
    val fg = if (onDark) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
    val fgLabel = if (onDark) Color.White.copy(alpha = 0.75f) else MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("云台控制", style = MaterialTheme.typography.labelMedium, color = fgLabel)
            Spacer(modifier = Modifier.weight(1f))
            if (error != null) {
                Text(
                    error,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFFFF8A80),
                    maxLines = 1,
                    modifier = Modifier.width(160.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .clickable { onClose() }
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Outlined.Close, contentDescription = "收起云台", modifier = Modifier.size(14.dp), tint = fg)
                Spacer(modifier = Modifier.width(4.dp))
                Text("收起", style = MaterialTheme.typography.labelSmall, color = fg)
            }
        }
        PtzRow(fg, onDark) {
            HoldPadCell(46.dp, Icons.Outlined.ZoomOut, { onMove(0f, 0f, -0.3f) }, onStop, "缩小", fg, onDark)
            HoldPadCell(46.dp, Icons.Outlined.ArrowUpward, { onMove(0f, -0.5f, 0f) }, onStop, "上", fg, onDark)
            HoldPadCell(46.dp, Icons.Outlined.ZoomIn, { onMove(0f, 0f, 0.3f) }, onStop, "放大", fg, onDark)
        }
        PtzRow(fg, onDark) {
            // 左键必须是回退箭头：和右键共用 ArrowForward 的话，图标方向是反的。
            HoldPadCell(46.dp, Icons.AutoMirrored.Outlined.ArrowBack, { onMove(-0.5f, 0f, 0f) }, onStop, "左", fg, onDark)
            HoldPadCell(46.dp, Icons.Outlined.Stop, { onStop() }, {}, "停止", fg, onDark)
            HoldPadCell(46.dp, Icons.AutoMirrored.Outlined.ArrowForward, { onMove(0.5f, 0f, 0f) }, onStop, "右", fg, onDark)
        }
        PtzRow(fg, onDark) {
            Spacer(Modifier.size(46.dp))
            HoldPadCell(46.dp, Icons.Outlined.ArrowDownward, { onMove(0f, 0.5f, 0f) }, onStop, "下", fg, onDark)
            Spacer(Modifier.size(46.dp))
        }
        if (busy) {
            Spacer(modifier = Modifier.height(4.dp))
            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
        }
    }
}

@Composable
private fun PtzRow(fg: Color, onDark: Boolean, content: @Composable () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically
    ) { content() }
}

/** 按住才动（ONVIF ContinuousMove + 松手 Stop）。 */
@Composable
private fun HoldPadCell(
    size: androidx.compose.ui.unit.Dp,
    icon: ImageVector,
    onHold: () -> Unit,
    onRelease: () -> Unit,
    label: String,
    fg: Color,
    onDark: Boolean
) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (onDark) Color.White.copy(alpha = 0.14f)
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
            )
            .pointerInput(label) {
                awaitEachGesture {
                    awaitFirstDown()
                    onHold()
                    do {
                        val event = awaitPointerEvent()
                        if (event.changes.none { it.pressed }) {
                            onRelease()
                            break
                        }
                    } while (true)
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = label, tint = fg, modifier = Modifier.size(22.dp))
    }
}

// ─────────────────────────── 工具函数 ───────────────────────────

private enum class RecordKind { Live, Clip }

private data class RecState(
    val recording: Boolean = false,
    val startedAt: Long = 0L,
    val captured: Long = 0L,
    val saving: Boolean = false,
    val error: String? = null,
    val kind: RecordKind = RecordKind.Live
)

private fun friendlyMsg(e: Throwable): String {
    val raw = e.message ?: e::class.java.simpleName
    return raw.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.take(160) ?: raw
}

private fun speedLabel(v: Float): String =
    if (v % 1f == 0f) "${v.toInt()}.0×" else "${v}×"

private fun statusLabel(status: String): String = when (status) {
    "recording" -> "录像中"
    "starting" -> "启动中"
    "reconnecting" -> "重连中"
    "stopped" -> "已停止"
    "error" -> "异常"
    "offline" -> "离线"
    else -> status
}

private fun statusColor(status: String): Color = when (status) {
    "recording" -> Color(0xFF2E7D32)
    "starting", "reconnecting" -> Color(0xFFEF6C00)
    "stopped" -> Color(0xFF9E9E9E)
    else -> Color(0xFFC62828)
}

/** epoch 秒 → "HH:mm:ss"（本地时区），导轨用。 */
private fun fmtHms(epochSeconds: Long): String =
    SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(epochSeconds * 1000L))

private fun dayLabel(offset: Int): String {
    val zone = java.time.ZoneId.systemDefault()
    val day = java.time.LocalDate.now(zone).minusDays(offset.toLong())
    val label = SimpleDateFormat("MM-dd", Locale.getDefault())
        .format(Date(day.atStartOfDay(zone).toInstant().toEpochMilli()))
    return when (offset) {
        0 -> "今天 $label"
        1 -> "昨天 $label"
        else -> "$label ${day.year}"
    }
}

// ─────────────────────────── 截图 ───────────────────────────

/**
 * 抓当前视频帧。
 *
 * `PlayerView` 默认渲染到 **SurfaceView**（不是 TextureView），`bitmap` 那条路永远
 * 拿不到东西 —— 这正是「截图」以前报「暂没有视频帧」的原因。SurfaceView 必须用
 * [PixelCopy]，它能把 surface 上已经合成好的像素拷回 Bitmap。
 */
private suspend fun captureFrame(pv: PlayerView): Result<Bitmap> {
    var last: Throwable = IllegalStateException("截图失败")
    // PixelCopy 在「刚 seek/换源」时会偶发 SOURCE_NO_DATA（解码器 flush 完还没吐新帧），
    // 实测同一段代码会时成时败 → 重试两次基本必成；每步再套超时，免得对端不回调时挂死。
    repeat(3) { i ->
        val r = withTimeoutOrNull(2500) { captureFrameOnce(pv) }
            ?: Result.failure(IllegalStateException("取帧超时"))
        if (r.isSuccess) return r
        last = r.exceptionOrNull() ?: last
        if (i < 2) delay(250)
    }
    return Result.failure(last)
}

private suspend fun captureFrameOnce(pv: PlayerView): Result<Bitmap> {
    val surface = pv.videoSurfaceView
        ?: return Result.failure(IllegalStateException("没有视频输出视图"))
    return when (surface) {
        is TextureView -> surface.bitmap?.let { Result.success(it) }
            ?: Result.failure(IllegalStateException("TextureView 暂无位图"))
        is SurfaceView -> {
            if (surface.width <= 0 || surface.height <= 0) {
                return Result.failure(
                    IllegalStateException("视图未布局 ${surface.width}x${surface.height}")
                )
            }
            suspendCancellableCoroutine { cont ->
                val bmp = Bitmap.createBitmap(surface.width, surface.height, Bitmap.Config.ARGB_8888)
                try {
                    PixelCopy.request(surface, bmp, { result ->
                        if (result == PixelCopy.SUCCESS) {
                            cont.resume(Result.success(bmp))
                        } else {
                            cont.resume(
                                Result.failure(
                                    IllegalStateException(
                                        "PixelCopy ${pixelCopyError(result)}" +
                                            " (view ${surface.width}x${surface.height})"
                                    )
                                )
                            )
                        }
                    }, Handler(Looper.getMainLooper()))
                } catch (e: Exception) {
                    cont.resume(Result.failure(e))
                }
            }
        }
        else -> Result.failure(
            IllegalStateException("不支持的视图 ${surface.javaClass.simpleName}")
        )
    }
}

/** PixelCopy 的失败码在系统里没有公开的名字表，自己映射一份，方便上屏定位。 */
private fun pixelCopyError(code: Int): String = when (code) {
    PixelCopy.ERROR_UNKNOWN -> "UNKNOWN"
    PixelCopy.ERROR_TIMEOUT -> "TIMEOUT"
    PixelCopy.ERROR_SOURCE_NO_DATA -> "SOURCE_NO_DATA"
    PixelCopy.ERROR_SOURCE_INVALID -> "SOURCE_INVALID"
    PixelCopy.ERROR_DESTINATION_INVALID -> "DESTINATION_INVALID"
    else -> "code=$code"
}

private suspend fun saveSnapshot(context: Context, bmp: Bitmap, camera: NvrApi.Camera?): Boolean =
    withContext(Dispatchers.IO) {
        runCatching {
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val name = "homehub_${camera?.name ?: "cam"}_$stamp.jpg"
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.DATE_TAKEN, System.currentTimeMillis())
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/HomeHub")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: return@runCatching false
            resolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
                ?: return@runCatching false
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            true
        }.getOrDefault(false)
    }

// ─────────────────────────── 实时 HLS 录制 ───────────────────────────

/**
 * 抓服务端 `/api/nvr/live/{cam}?stream=` 的 HLS 列表，把 init + 每个新 `.m4s` 追加写到
 * 临时文件；停止时整体 remux 进相册（不需要存储权限）。会话放在 [session] 里，
 * 跨重组存活。
 */
private fun startLiveRecording(session: MutableMap<String, Any?>, camera: NvrApi.Camera, main: Boolean) {
    val vm = session["vm"] as? MonitorViewModel ?: return
    val base = vm.baseUrl()
    // 无尾斜杠：带斜杠匹配不到 live 路由，会拿到后台 SPA 的 HTML。
    val playlistUrl = "$base/api/nvr/live/${camera.name}?stream=${if (main) "main" else "sub"}"
    val origin = runCatching { java.net.URI(playlistUrl) }
        .getOrNull()
        ?.let { "${it.scheme}://${it.authority}" }
        ?: base
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val tmp = try {
        File.createTempFile("hh_rec_", ".mp4", null).also { it.deleteOnExit() }
    } catch (e: Exception) {
        (session["onError"] as? ((String) -> Unit))?.invoke("无法创建临时文件：${e.message}")
        return
    }
    val http = try {
        vm.deviceClient()
    } catch (e: Exception) {
        tmp.delete()
        (session["onError"] as? ((String) -> Unit))?.invoke("无法构建 HTTP 客户端：${e.message}")
        return
    }

    session["loop"]?.let { (it as? Job)?.cancel() }
    session["last"] = null
    val job = scope.launch {
        var firstFragment = true
        try {
            while (isActive) {
                val playlist = runCatching {
                    http.newCall(Request.Builder().url(playlistUrl).build()).execute().use {
                        if (!it.isSuccessful) throw IllegalStateException("HTTP ${it.code}")
                        it.body?.string().orEmpty()
                    }
                }.getOrElse { e ->
                    delay(2000)
                    e.message ?: "playlist 请求失败"
                }
                if (!playlist.contains("#EXTM3U")) {
                    // 还没准备好（服务端现场拉 ffmpeg，最长等 8s），安静重试
                    delay(2000)
                    continue
                }
                var parsed = false
                playlist.lineSequence().forEach { line ->
                    val l = line.trim()
                    if (l.startsWith("#EXT-X-MAP")) {
                        val uri = l.substringAfter("URI=\"").substringBefore("\"")
                        if (firstFragment) {
                            fetchFragment(http, origin, playlistUrl, uri, tmp)
                            firstFragment = false
                        }
                        parsed = true
                    } else if (l.isNotBlank() && !l.startsWith("#")) {
                        val last = session["last"] as? String
                        if (l != last) {
                            session["last"] = l
                            fetchFragment(http, origin, playlistUrl, l, tmp)
                            parsed = true
                        }
                    }
                }
                val tick = session["onTick"] as? ((Long) -> Unit)
                tick?.invoke(tmp.length())
                if (parsed) delay(300) else delay(1000)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            (session["onError"] as? ((String) -> Unit))?.invoke("录制中断：${e.message}")
        }
    }
    session["loop"] = job
    session["tmpDir"] = tmp
}

/**
 * 拉一个媒体分片追加到 [tmp]。
 *
 * 服务端会把播放列表里的相对 URI 改写成**绝对路径**（`/api/nvr/live/main/seg*.m4s`），
 * 所以这里要按三种形态解析，不能一律 `"$base/$name"` —— 那样会拼出
 * `.../live/main//api/nvr/live/main/seg*.m4s`。
 *
 * **必须追加写**：`File.outputStream()` 是新建流（会截断），用它的话每来一个分片都会
 * 把前面录到的内容清掉，最后只剩最后一个分片。
 */
private suspend fun fetchFragment(
    http: OkHttpClient,
    origin: String,
    playlistUrl: String,
    name: String,
    tmp: File
) {
    val base = playlistUrl.substringBefore('?')
    val fragUrl = when {
        name.startsWith("http://") || name.startsWith("https://") -> name
        name.startsWith("/") -> origin + name
        else -> "$base/$name"
    }
    http.newCall(Request.Builder().url(fragUrl).build()).execute().use { resp ->
        if (!resp.isSuccessful) throw IllegalStateException("fragment $name → HTTP ${resp.code}")
        resp.body?.byteStream()?.use { input ->
            java.io.FileOutputStream(tmp, /* append = */ true).use { input.copyTo(it) }
        }
    }
}

private suspend fun stopRecording(
    session: MutableMap<String, Any?>,
    context: Context,
    onDone: (RecState) -> Unit
) {
    (session["loop"] as? Job)?.cancel()
    val tmp = session["tmpDir"] as? File
    withContext(Dispatchers.Main) {
        onDone(RecState(saving = true, captured = tmp?.length() ?: 0L))
    }
    val ok = withContext(Dispatchers.IO) {
        runCatching {
            if (tmp == null || tmp.length() == 0L) throw IllegalStateException("还没有采到任何数据")
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val name = "homehub_rec_$stamp.mp4"
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.DATE_TAKEN, System.currentTimeMillis())
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/HomeHub")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("无法写入媒体库")
            resolver.openOutputStream(uri)?.use { out -> tmp.inputStream().use { it.copyTo(out) } }
                ?: throw IllegalStateException("无法打开媒体库输出")
            values.clear()
            values.put(MediaStore.Video.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            true
        }.getOrDefault(false)
    }
    tmp?.delete()
    withContext(Dispatchers.Main) {
        onDone(if (ok) RecState() else RecState(error = "录像保存失败，请重试"))
    }
}

// ─────────────────────────── 历史片段录制（裁剪） ───────────────────────────

/**
 * 把正在回放的这一段里 `[startSec, endSec]` 存进相册。
 *
 * 做法：整段下到缓存 → `MediaExtractor` + `MediaMuxer` 无损裁剪（关键帧对齐，不
 * 重编码）→ 写进 MediaStore。裁剪失败时退化成「存整段」，至少不会丢东西。
 */
private suspend fun saveClip(
    context: Context,
    vm: MonitorViewModel,
    seg: NvrApi.Segment,
    startSec: Long,
    endSec: Long
): Boolean = withContext(Dispatchers.IO) {
    val src = File(context.cacheDir, "hh_clip_src_${seg.id}.mp4")
    val dst = File(context.cacheDir, "hh_clip_out_${seg.id}.mp4")
    try {
        val http = vm.deviceClient()
        // 整段拉下来（服务端支持 Range，这里要的就是整段）
        http.newCall(Request.Builder().url(vm.segmentUrl(seg.id)).build()).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("片段下载失败 HTTP ${resp.code}")
            resp.body?.byteStream()?.use { input ->
                src.outputStream().use { input.copyTo(it) }
            } ?: throw IllegalStateException("片段下载为空")
        }
        if (src.length() == 0L) return@withContext false

        val fromUs = ((startSec - seg.start_time).coerceAtLeast(0L)) * 1_000_000L
        val toUs = ((endSec - seg.start_time).coerceAtLeast(0L)) * 1_000_000L
        val trimmed = toUs - fromUs > 1_000_000L && trimMp4(src, dst, fromUs, toUs)
        val out = if (trimmed) dst else src

        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "homehub_clip_$stamp.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.DATE_TAKEN, System.currentTimeMillis())
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/HomeHub")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: return@withContext false
        resolver.openOutputStream(uri)?.use { o -> out.inputStream().use { it.copyTo(o) } }
            ?: return@withContext false
        values.clear()
        values.put(MediaStore.Video.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        true
    } catch (e: Exception) {
        false
    } finally {
        src.delete()
        dst.delete()
    }
}

/**
 * 无损裁剪一个 MP4：[fromUs, toUs]（相对文件起点）。
 *
 * `SEEK_TO_PREVIOUS_SYNC` 会把起点回退到关键帧，所以开头可能多出不到一个 GOP 的
 * 内容 —— 换来的是输出一定能从头解码，比精确到毫秒但花屏划算。时间戳按第一个
 * 写入的采样归零，MediaMuxer 要求 PTS 单调。
 */
private fun trimMp4(src: File, dst: File, fromUs: Long, toUs: Long): Boolean {
    var extractor: MediaExtractor? = null
    var muxer: MediaMuxer? = null
    return try {
        extractor = MediaExtractor().apply { setDataSource(src.absolutePath) }
        var bufSize = 1 shl 20
        val trackMap = HashMap<Int, Int>()
        for (i in 0 until extractor.trackCount) {
            val fmt = extractor.getTrackFormat(i)
            val mime = fmt.getString(MediaFormat.KEY_MIME) ?: continue
            if (!mime.startsWith("video/") && !mime.startsWith("audio/")) continue
            if (fmt.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                bufSize = max(bufSize, fmt.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE))
            }
        }
        muxer = MediaMuxer(dst.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        for (i in 0 until extractor.trackCount) {
            val fmt = extractor.getTrackFormat(i)
            val mime = fmt.getString(MediaFormat.KEY_MIME) ?: continue
            if (!mime.startsWith("video/") && !mime.startsWith("audio/")) continue
            trackMap[i] = muxer.addTrack(fmt)
        }
        if (trackMap.isEmpty()) return false
        for (i in trackMap.keys) extractor.selectTrack(i)
        extractor.seekTo(fromUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

        muxer.start()
        val buf = java.nio.ByteBuffer.allocate(bufSize)
        val info = MediaCodec.BufferInfo()
        var base = -1L
        while (true) {
            val size = extractor.readSampleData(buf, 0)
            if (size < 0) break
            val t = extractor.sampleTime
            if (t > toUs) break
            if (base < 0) base = t
            val idx = extractor.sampleTrackIndex
            val outTrack = trackMap[idx]
            if (outTrack == null) {
                // 没被映射的轨道（例如不需要的音频）直接跳过，但必须 advance 否则死循环
                extractor.advance()
                continue
            }
            info.set(0, size, t - base, extractor.sampleFlags)
            muxer.writeSampleData(outTrack, buf, info)
            extractor.advance()
        }
        muxer.stop()
        dst.length() > 0
    } catch (e: Exception) {
        false
    } finally {
        runCatching { extractor?.release() }
        runCatching { muxer?.release() }
    }
}

/** DFS [PlayerView] 子树找 [TextureView]（仅在渲染到 TextureView 时有用）。 */
private fun findTextureView(root: View): TextureView? {
    if (root is TextureView) return root
    if (root is ViewGroup) {
        for (i in 0 until root.childCount) {
            findTextureView(root.getChildAt(i))?.let { return it }
        }
    }
    return null
}
