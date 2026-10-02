package me.erguotou.homehub.update

import com.google.gson.Gson
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * 更新资源的取用通道。
 *
 * 国内直连 `raw.githubusercontent.com` 会被直接拒绝建立连接（实测 curl 错误码 7，
 * 3 ms 就返回），但套一层自建加速前缀能正常拿到内容。所以每个地址都准备两份
 * 候选：直连优先，失败后再走加速 —— 哪天直连通了也不会多绕一跳。
 *
 * 「直连优先」只是**首次**的默认值；一旦某条通道被证实可用，后续请求就记着它，
 * 见 [UpdateSource.orderedCandidates] 的说明。
 */
object UpdateSource {
    const val REPO = "erguotou520/HomeHub"
    const val PROXY_PREFIX = "https://proxy.erguotou.me/"
    private const val BRANCH = "main"

    /** 清单的原始地址，按优先级排列：raw 优先，Release 资产兜底。 */
    val MANIFESTS = listOf(
        "https://raw.githubusercontent.com/$REPO/$BRANCH/release/latest.json",
        "https://github.com/$REPO/releases/latest/download/latest.json",
    )

    /** 展开成「直连 → 加速」两条候选。下标即通道号：0 直连，1 加速。 */
    fun candidates(url: String): List<String> = listOf(url, PROXY_PREFIX + url)

    /**
     * 返回 `通道号 to 地址`，把 [preferred] 提到最前，其余保持原顺序兜底。
     *
     * 直连失败有两种形态，代价天差地别：**拒绝**是毫秒级返回，**黑洞**要等到
     * connectTimeout 用完。实测本机 curl 直连 raw 是前者（3 ms），直连
     * github.com 是后者（20 s 仍未建连），而走加速 1.1 s 就回来了。所以
     * 一旦确定某条通道可用，就不该每次再拿 15 s 去赌另一条。
     */
    fun orderedCandidates(url: String, preferred: Int): List<Pair<Int, String>> {
        val all = candidates(url)
        val order = if (preferred in 1..all.lastIndex) {
            listOf(preferred) + all.indices.filter { it != preferred }
        } else {
            all.indices.toList()
        }
        return order.map { it to all[it] }
    }
}

/** 取更新资源（清单与安装包）。 */
class UpdateClient(private val client: OkHttpClient = defaultClient()) {

    /**
     * 每个主机记住「上次成功的通道下标」，下次优先用它。
     *
     * 按主机而不是全局记：不同域名的可达性可以完全相反。本机实测
     * `raw.githubusercontent.com` 直连被**拒绝**（3 ms 就返回），
     * `github.com` 直连却是**黑洞**（20 s 仍未建连）—— 全局记一个值的话，
     * raw 直连成功会把 github.com 也钉在直连上，于是每次下载都白等 15 s。
     */
    private val preferredByHost = ConcurrentHashMap<String, Int>()

    private fun preferredFor(url: String): Int =
        url.toHttpUrlOrNull()?.host?.let { preferredByHost[it] } ?: 0

    private fun remember(url: String, channel: Int) {
        url.toHttpUrlOrNull()?.host?.let { preferredByHost[it] = channel }
    }

    /** 拉取版本清单；所有通道都失败时返回 null。 */
    fun fetchManifest(): UpdateManifest? {
        val gson = Gson()
        for (base in UpdateSource.MANIFESTS) {
            for ((channel, url) in UpdateSource.orderedCandidates(base, preferredFor(base))) {
                val text = runCatching {
                    client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                        if (response.isSuccessful) response.body?.string() else null
                    }
                }.getOrNull()
                if (text.isNullOrBlank()) continue
                val parsed = runCatching { gson.fromJson(text, UpdateManifest::class.java) }
                    .getOrNull()
                if (parsed != null && parsed.versionCode > 0) {
                    remember(base, channel)
                    return parsed
                }
            }
        }
        return null
    }

    /**
     * 下载 [url] 到 [target]，支持断点续传。任一条通道成功即返回 true。
     *
     * 返回 true 只表示字节下完了，**不表示内容正确** —— 完整性由调用方拿清单里的
     * sha256 判定，因为加速前缀挡在中间时，中间环节返回的内容不可全信。
     */
    fun download(url: String, target: File, onProgress: (Long, Long) -> Unit): Boolean {
        for ((channel, candidate) in UpdateSource.orderedCandidates(url, preferredFor(url))) {
            val ok = runCatching { downloadOnce(candidate, target, onProgress) }
                .getOrDefault(false)
            if (ok && target.isFile) {
                remember(url, channel)
                return true
            }
        }
        return false
    }

    private fun downloadOnce(
        url: String,
        target: File,
        onProgress: (Long, Long) -> Unit,
    ): Boolean {
        // 半成品留在 .part 上，好让**同一次下载**里的下一条通道能接着下 ——
        // 全量包接近 100 MB，慢链路上中途断掉太常见，不能每次都从头来。
        // 只有服务端回 206 才认可这段已有数据；回 200 说明对方忽略了 Range，
        // 那时必须丢掉旧字节、从头写。
        // 跨用户操作是否复用 .part 由调用方决定，见 UpdateManager.prepare。
        val part = File("${target.absolutePath}.part")
        val already = if (part.isFile) part.length() else 0L

        val request = Request.Builder().url(url).apply {
            if (already > 0) header("Range", "bytes=$already-")
        }.build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return false
            val resuming = response.code == 206 && already > 0
            if (!resuming && part.exists()) part.delete()
            val start = if (resuming) already else 0L
            val body = response.body ?: return false
            val declared = body.contentLength()
            // contentLength 已经是剩余部分；总长要加上已续的部分。
            val total = if (declared > 0) declared + start else -1L

            FileOutputStream(part, resuming).buffered().use { out ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(1 shl 16)
                    var written = start
                    onProgress(written, total)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        written += n
                        onProgress(written, total)
                    }
                }
            }
        }

        if (target.exists()) target.delete()
        if (!part.renameTo(target)) {
            part.copyTo(target, overwrite = true)
            part.delete()
        }
        return true
    }

    companion object {
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            // 这是两次读到数据之间的等待上限，不是整个下载的时限：
            // 全量包在慢链路上要跑很久，但不该静默卡住超过一分钟。
            .readTimeout(60, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .build()
    }
}
