package me.erguotou.homehub.update

import com.google.gson.Gson
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/**
 * 更新资源的取用通道。
 *
 * 国内直连 `raw.githubusercontent.com` 会被直接拒绝建立连接（实测 curl 错误码 7，
 * 55 ms 就返回），但套一层自建加速前缀能正常拿到内容。所以每个地址都准备两份
 * 候选：直连优先，失败后再走加速 —— 哪天直连通了也不会多绕一跳。
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

    /** 展开成「直连 → 加速」两条候选。 */
    fun candidates(url: String): List<String> = listOf(url, PROXY_PREFIX + url)
}

/** 取更新资源（清单与安装包）。 */
class UpdateClient(private val client: OkHttpClient = defaultClient()) {

    /** 拉取版本清单；所有通道都失败时返回 null。 */
    fun fetchManifest(): UpdateManifest? {
        val gson = Gson()
        for (base in UpdateSource.MANIFESTS) {
            for (url in UpdateSource.candidates(base)) {
                val text = runCatching {
                    client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                        if (response.isSuccessful) response.body?.string() else null
                    }
                }.getOrNull()
                if (text.isNullOrBlank()) continue
                val parsed = runCatching { gson.fromJson(text, UpdateManifest::class.java) }
                    .getOrNull()
                if (parsed != null && parsed.versionCode > 0) return parsed
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
        for (candidate in UpdateSource.candidates(url)) {
            val ok = runCatching { downloadOnce(candidate, target, onProgress) }
                .getOrDefault(false)
            if (ok && target.isFile) return true
        }
        return false
    }

    private fun downloadOnce(
        url: String,
        target: File,
        onProgress: (Long, Long) -> Unit,
    ): Boolean {
        // 半成品留在 .part 上：全量包接近 100 MB，慢链路上很容易中断，
        // 下次进来自动从断点续 —— 只有服务端回 206 才认可这段已有数据。
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
