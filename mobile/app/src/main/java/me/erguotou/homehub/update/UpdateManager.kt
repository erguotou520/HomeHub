package me.erguotou.homehub.update

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.erguotou.homehub.BuildConfig
import java.io.File
import java.security.MessageDigest

/** 准备过程的阶段，用来给界面换文案。 */
enum class UpdateStage { DOWNLOADING, PATCHING, VERIFYING }

/** 准备结果。 */
sealed interface UpdateResult {
    /** [apk] 已落盘且校验通过，可以交给安装器。 */
    data class Ready(val apk: File, val incremental: Boolean) : UpdateResult

    data class Failed(val reason: String) : UpdateResult
}

/**
 * 更新流程的业务逻辑：查清单、决定走增量还是全量、下载、校验。
 *
 * 增量优先，但**任何一步对不上都静默退回全量**：合并出来的包与官方全量包的
 * sha256 必须完全一致，因为 v2/v3 签名覆盖整个文件字节，差一个字节系统就会
 * 拒装。宁可多下一次全量，也不能把装不上的包递给用户。
 */
class UpdateManager(private val context: Context) {

    private val client = UpdateClient()

    val currentVersionCode: Long = BuildConfig.VERSION_CODE.toLong()
    val currentVersionName: String = BuildConfig.VERSION_NAME

    /** 本机已安装 APK 的 sha256。增量包只对得上这个指纹的包才可用。 */
    private val installedSha256: String? by lazy {
        sha256Of(File(context.applicationInfo.sourceDir))
    }

    /** 拉取清单。返回 null 表示网络不通或清单不可用。 */
    suspend fun check(): UpdateManifest? = withContext(Dispatchers.IO) { client.fetchManifest() }

    /**
     * 找出可用于本机当前版本的增量包，没有就返回 null（改走全量）。
     *
     * 先按版本号做廉价筛选，命中之后才去算本机 APK 的指纹 —— 那要读完 97 MB，
     * 绝大多数检查（没有增量可用时）不该付这个代价。
     */
    fun deltasMatch(manifest: UpdateManifest): UpdateDelta? {
        if (manifest.deltas.orEmpty().none { it.fromVersionCode == currentVersionCode }) return null
        return manifest.deltaFor(currentVersionCode, installedSha256)
    }

    /** 下载并准备好可安装的 APK。 */
    suspend fun prepare(
        manifest: UpdateManifest,
        onStage: (UpdateStage) -> Unit,
        onProgress: (Long, Long) -> Unit,
    ): UpdateResult = withContext(Dispatchers.IO) {
        val artifact = manifest.apk
        if (artifact == null || artifact.url.isBlank()) {
            return@withContext UpdateResult.Failed("清单里没有安装包信息")
        }

        val work = File(context.cacheDir, "update").apply { mkdirs() }
        val apk = File(work, "homehub-${manifest.versionName ?: manifest.versionCode}.apk")
        if (apk.exists()) apk.delete()
        // 连同上次残留的 .part 一起清掉，让这次下载从头开始。
        //
        // 看起来浪费了 UpdateClient 的断点续传，但那套续传是**单次调用内**用的：
        // 第一条通道下到一半断了，换第二条通道接着下。跨用户操作复用就不安全了 ——
        // 上一个 .part 可能是代理返回错误页时写下的（HTTP 200 而非 206），
        // 再拿它去续，只会把垃圾接在垃圾后面，最后卡在「校验失败」反复重试。
        // 相比之下，重下一次 99 MB 是明确、可见、用户能理解的代价。
        File("${apk.absolutePath}.part").takeIf { it.exists() }?.delete()

        // 增量优先：对不上（没有该旧版本的补丁、或本机包指纹不同）就落到全量。
        val delta = deltasMatch(manifest)
        if (delta != null) {
            val viaDelta = applyDelta(delta, artifact, manifest, apk, onStage, onProgress)
            if (viaDelta != null) return@withContext viaDelta
        }

        onStage(UpdateStage.DOWNLOADING)
        onProgress(0, -1)
        if (!client.download(artifact.url, apk, onProgress)) {
            return@withContext UpdateResult.Failed("下载安装包失败")
        }
        onStage(UpdateStage.VERIFYING)
        if (!apk.hasDigest(artifact.sha256)) {
            apk.delete()
            return@withContext UpdateResult.Failed("安装包校验失败，请稍后重试")
        }
        UpdateResult.Ready(apk, incremental = false)
    }

    /** 增量路径。任何一步不可靠都返回 null，由调用方退回全量。 */
    private fun applyDelta(
        delta: UpdateDelta,
        artifact: UpdateArtifact,
        manifest: UpdateManifest,
        apk: File,
        onStage: (UpdateStage) -> Unit,
        onProgress: (Long, Long) -> Unit,
    ): UpdateResult? {
        val work = apk.parentFile ?: return null

        onStage(UpdateStage.DOWNLOADING)
        onProgress(0, -1)
        val patch = File(work, "delta-${delta.fromVersionCode}-to-${manifest.versionCode}.patch")
        if (patch.exists()) patch.delete()
        if (!client.download(delta.url, patch, onProgress)) {
            patch.delete()
            return null
        }
        if (!patch.hasDigest(delta.sha256)) {
            patch.delete()
            return null
        }

        onStage(UpdateStage.PATCHING)
        val merged = runCatching {
            BsPatch.apply(File(context.applicationInfo.sourceDir), patch, apk)
        }.isSuccess
        patch.delete()
        if (!merged) {
            apk.delete()
            return null
        }

        onStage(UpdateStage.VERIFYING)
        // 合并结果必须与全量包逐字节一致，否则签名失效、系统拒装。
        if (!apk.hasDigest(artifact.sha256)) {
            apk.delete()
            return null
        }
        return UpdateResult.Ready(apk, incremental = true)
    }

    companion object {
        /** 用 sha256 判断文件是否与清单声明的一致。声明为空时一律判失败。 */
        fun File.hasDigest(expected: String): Boolean {
            if (expected.isBlank() || !isFile) return false
            return sha256Of(this)?.equals(expected, ignoreCase = true) == true
        }

        fun sha256Of(file: File): String? = runCatching {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(1 shl 20)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    digest.update(buffer, 0, n)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        }.getOrNull()
    }
}
