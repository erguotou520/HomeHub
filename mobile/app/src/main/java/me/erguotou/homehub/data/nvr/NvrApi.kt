package me.erguotou.homehub.data.nvr

import okhttp3.OkHttpClient
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * NVR client API.
 *
 * 手机端只看不管理，所以读接口全部走**数据面** `api/nvr/...`（摄像头列表 /
 * 录像段 / 设置 / 回放 / 实时），带上共享令牌 `X-Device-Token` —— 后台「监控 →
 * 手机访问令牌」里设的那个值，App 在 设置 → 服务器 → 访问令牌 里填同一个。
 * 服务端没配令牌时该头不发，行为与从前一致（WireGuard 当边界）。
 * 下面那些 `api/admin/nvr/...` 的写接口保留着，但手机端不再调用它们：摄像头的
 * 增删改、保留策略、清理都在 HomeHub 后台完成。
 */
interface NvrApi {

    data class Camera(
        val id: Long,
        val name: String,
        val label: String?,
        /** 主码流 RTSP 地址。在数据面上是**打码**的（凭据被换成 `<掩码>`，形如 `rtsp:` + `//<掩码>@host`）。 */
        val rtsp_url: String,
        /**
         * 子码流 RTSP 地址；为空表示由主码流推导（`/stream1` → `/stream2`）。
         *
         * 同样打码，且手机端**不再直连 RTSP** —— 只用来展示后台配了什么。
         */
        val rtsp_sub_url: String? = null,
        /** 实时预览默认拉哪一路：`sub`（默认）或 `main`。 */
        val preview_stream: String = "sub",
        val enabled: Boolean,
        val record_stream: Int,
        val record_audio: Boolean,
        val status: String,
        val restart_count: Int,
        val last_error: String?,
        val last_segment_at: Long?,
        val today_segments: Int,
        val total_size_bytes: Long,
        val total_segments: Int
    )

    data class CameraList(val cameras: List<Camera>)

    data class CameraBody(
        val name: String? = null,
        val label: String?,
        val rtsp_url: String,
        val record_stream: Int,
        val record_audio: Boolean,
        val enabled: Boolean
    )

    /** create_camera returns only {id, name, created}; the UI re-lists. */
    data class CameraCreated(val id: Long, val name: String, val created: Boolean? = null)

    data class CameraUpdated(val id: Long, val name: String, val updated: Boolean? = null)

    data class Restarted(val restarted: Boolean)

    data class Updated(val updated: Boolean)

    data class Segment(
        val id: Long,
        val camera_id: Long,
        val path: String,
        val start_time: Long,
        val end_time: Long,
        val duration: Double,
        val size_bytes: Long,
        val video_codec: String?,
        val width: Int?,
        val height: Int?,
        /** 0 = unknown/未打分, 1 = still(静止), 2 = active(有活动/人物)。 */
        val motion: Long = 0,
        /** scdet 场景变化帧占比(0..1)；旧数据为 0。 */
        val motion_score: Double = 0.0
    )

    data class SegmentPage(
        val segments: List<Segment>,
        val next_before: Long?,
        val limit: Int
    )

    data class TimelineHour(
        val hour_start: Long,
        val hour_end: Long,
        val segments: Int,
        val bytes: Long
    )

    data class TimelineHours(val hours: List<TimelineHour>)

    data class Settings(
        val enabled: Boolean,
        val record_dir: String,
        val timezone: String,
        val segment_secs: Int,
        val expire_interval_mins: Int,
        val retain_days: Int,
        val max_gb: Int,
        val maintain_interval_secs: Int,
        val video: Video,
        val total_size_bytes: Long,
        val ffmpeg_available: Boolean
    )
    data class Video(
        val source: String,
        val scale: String,
        val fps: Int,
        val crf: Int,
        val maxrate: String,
        val bufsize: String,
        val encoder: String,
        val preset: String,
        val audio: Boolean
    )

    data class SettingsBody(
        val enabled: Boolean,
        val retain_days: Int,
        val max_gb: Int,
        val segment_secs: Int,
        val timezone: String
    )

    data class CleanupResult(val removed: Int, val remaining: Int)

    /**
     * PTZ 指令体。`action` 只有 `move`（持续移动，需配合 `stop`）和 `stop`。
     *
     * 方向遵循 ONVIF 约定：`x` 负=左、正=右；`y` 负=上、正=下；`z` 负=拉远、正=拉近。
     */
    data class PtzBody(
        val action: String,
        val x: Double = 0.0,
        val y: Double = 0.0,
        val z: Double = 0.0
    )

    /** 成功时服务端只回 `{"ok":true}`。 */
    data class PtzResult(val ok: Boolean = false)

    @GET("api/nvr/cameras")
    suspend fun cameras(): CameraList

    /**
     * 云台。**走服务端代理**：手机拿到的 `rtsp_url` 是打码的、且常常不在摄像头网段，
     * 直连 ONVIF 必然 `NotAuthorized`。失败时服务端把摄像头自己的错误文案原样带回，
     * Retrofit 会抛 HTTP 400。
     */
    @POST("api/nvr/cameras/{cam}/ptz")
    suspend fun ptz(@Path("cam") cam: String, @Body body: PtzBody): PtzResult

    @POST("api/admin/nvr/cameras")
    suspend fun addCamera(@Body body: CameraBody): CameraCreated

    @PUT("api/admin/nvr/cameras/{id}")
    suspend fun updateCamera(@Path("id") id: Long, @Body body: CameraBody): CameraUpdated

    @DELETE("api/admin/nvr/cameras/{id}")
    suspend fun deleteCamera(@Path("id") id: Long): Response<Unit>

    @POST("api/admin/nvr/cameras/{id}/restart")
    suspend fun restartCamera(@Path("id") id: Long): Restarted

    @GET("api/nvr/segments")
    suspend fun segments(
        @Query("camera_id") cameraId: Long?,
        @Query("camera") camera: String?,
        @Query("limit") limit: Int = 50,
        @Query("before") before: Long? = null,
        @Query("from") from: Long? = null,
        @Query("to") to: Long? = null,
        @Query("direction") direction: String? = null
    ): SegmentPage

    @GET("api/admin/nvr/segments/{cam}/timeline")
    suspend fun timeline(@Path("cam") cam: String): TimelineHours

    @DELETE("api/admin/nvr/segments/{id}")
    suspend fun deleteSegment(@Path("id") id: Long): Response<Unit>

    @GET("api/nvr/settings")
    suspend fun settings(): Settings

    @PUT("api/admin/nvr/settings")
    suspend fun updateSettings(@Body body: SettingsBody): Updated

    @POST("api/admin/nvr/cleanup")
    suspend fun cleanup(): CleanupResult

    // ── admin login (no Bearer yet) ──
    data class LoginReq(val password: String)
    data class LoginResp(val token: String, val expires_in: Long)

    @POST("api/admin/login")
    suspend fun login(@Body req: LoginReq): LoginResp
}

/**
 * Retrofit client for [NvrApi].
 *
 * 读接口在服务端的数据面上，要 `runtime.security.device-token`（后台可以留空 = 不校验）。
 * 令牌每次请求现读 `Prefs`，所以设置页改完立刻生效，不用重建客户端。
 * [token] 是留给写接口的扩展点（当前手机端不调写接口），一旦有值就自动挂上
 * Bearer 头。
 */
class NvrClient(
    private val prefs: me.erguotou.homehub.data.Prefs,
    private val baseClient: OkHttpClient
) {
    @Volatile
    private var token: String? = null

    @Volatile
    private var api: NvrApi? = null

    @Volatile
    private var deviceHttp: OkHttpClient? = null

    fun tokenOrNull(): String? = token

    /** Bearer-attaching client for raw (non-Retrofit) requests. */
    fun bearerClient(): OkHttpClient = client()

    /**
     * 数据面用的原始客户端：带上 `X-Device-Token`。
     *
     * 分片下载、整段抓取、HLS 录制循环都走它。令牌在**拦截器里每次现读**，
     * 所以手机上改完令牌不用重启 App；没配令牌时这个头根本不发。
     */
    fun deviceClient(): OkHttpClient {
        deviceHttp?.let { return it }
        val built = baseClient.newBuilder()
            .addInterceptor { chain ->
                val b = chain.request().newBuilder()
                val t = prefs.deviceToken.trim()
                if (t.isNotEmpty()) b.header(DEVICE_TOKEN_HEADER, t)
                // 手机端今天不调写接口；一旦 login() 拿到管理员 JWT，这里也一并带上，
                // 免得「数据面走 deviceClient、写接口走 Bearer」两套客户端来回切。
                val jwt = token
                if (!jwt.isNullOrBlank()) b.header("Authorization", "Bearer $jwt")
                chain.proceed(b.build())
            }
            .build()
        deviceHttp = built
        return built
    }

    /** Base URL without trailing slash, e.g. `http://host:8485`. */
    fun baseUrl(): String = prefs.baseUrl().trimEnd('/')

    private fun client(): OkHttpClient {
        val t = token
        if (t.isNullOrBlank()) return baseClient
        return baseClient.newBuilder()
            .addInterceptor { chain ->
                val req = chain.request().newBuilder()
                    .header("Authorization", "Bearer $t")
                    .build()
                chain.proceed(req)
            }
            .build()
    }

    private fun api(): NvrApi {
        api?.let { return it }
        val retrofit = retrofit2.Retrofit.Builder()
            .baseUrl(me.erguotou.homehub.data.HttpClientFactory.withTrailingSlash(prefs.baseUrl()))
            // 数据面（cameras/segments/settings）在服务端配了令牌后会 401，
            // 所以 REST 也必须走 deviceClient()：它会带上 X-Device-Token。
            .client(deviceClient())
            .addConverterFactory(retrofit2.converter.gson.GsonConverterFactory.create())
            .build()
        return retrofit.create(NvrApi::class.java).also { api = it }
    }

    /**
     * Obtain an admin token for the write endpoints. Unused today (the phone
     * is read-only); kept as the single entry point should the app ever need
     * to create cameras or change retention.
     */
    suspend fun login(password: String) {
        // Login must not carry a (possibly stale) Authorization header.
        val bare = retrofit2.Retrofit.Builder()
            .baseUrl(me.erguotou.homehub.data.HttpClientFactory.withTrailingSlash(prefs.baseUrl()))
            .client(baseClient)
            .addConverterFactory(retrofit2.converter.gson.GsonConverterFactory.create())
            .build()
            .create(NvrApi::class.java)
        val resp = bare.login(NvrApi.LoginReq(password))
        token = resp.token
        api = null // rebuild with the fresh token
    }

    fun invalidate() {
        token = null
        api = null
    }

    /** 数据面共享令牌（后台「监控 → 手机访问令牌」），空 = 服务端没开校验。 */
    fun deviceToken(): String = prefs.deviceToken.trim()

    suspend fun cameras(): NvrApi.CameraList = api().cameras()
    suspend fun ptz(cam: String, action: String, x: Double = 0.0, y: Double = 0.0, z: Double = 0.0) =
        api().ptz(cam, NvrApi.PtzBody(action = action, x = x, y = y, z = z))
    suspend fun addCamera(body: NvrApi.CameraBody) = api().addCamera(body)
    suspend fun updateCamera(id: Long, body: NvrApi.CameraBody) = api().updateCamera(id, body)
    suspend fun deleteCamera(id: Long) {
        val r = api().deleteCamera(id)
        r.body()
        if (!r.isSuccessful) throw IllegalStateException("delete camera: HTTP ${r.code()}")
    }
    suspend fun restartCamera(id: Long) = api().restartCamera(id)
    suspend fun segments(
        cameraId: Long?,
        camera: String?,
        limit: Int = 50,
        before: Long? = null,
        from: Long? = null,
        to: Long? = null,
        direction: String? = null
    ): NvrApi.SegmentPage = api().segments(cameraId, camera, limit, before, from, to, direction)
    suspend fun timeline(cam: String) = api().timeline(cam)
    suspend fun deleteSegment(id: Long) {
        val r = api().deleteSegment(id)
        if (!r.isSuccessful) throw IllegalStateException("delete segment: HTTP ${r.code()}")
    }
    suspend fun settings() = api().settings()
    suspend fun updateSettings(body: NvrApi.SettingsBody) = api().updateSettings(body)
    suspend fun cleanup() = api().cleanup()

    /**
     * 回放 / 下载某个录像段的地址。
     *
     * 在**数据面**上，凭据走请求头（`X-Device-Token`），所以 URL 里不再拼
     * `?token=` —— 播放器（Media3）能用 `setDefaultRequestProperties` 带头，
     * 而 URL 里的凭据会漏进代理日志。服务端支持 Range。
     */
    fun segmentUrl(segmentId: Long): String =
        "${prefs.baseUrl().trimEnd('/')}/api/nvr/segments/$segmentId"

    companion object {
        /**
         * 数据面共享令牌的头名，服务端读 `x-device-token`（大小写不敏感）。
         * Media3 的 `DefaultHttpDataSource` 也用同一个名字。
         */
        const val DEVICE_TOKEN_HEADER = "X-Device-Token"
    }
}
