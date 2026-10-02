package me.erguotou.homehub.update

/**
 * 版本清单，由 CI 在打 tag 时生成，落在仓库 main 分支的 `release/latest.json`
 * （同一份也作为 Release 资产，多一条备用通道）。
 *
 * 走静态文件而不是 API：没有服务端要维护、没有限流、也能被加速前缀直接代理。
 *
 * 字段一律可空：清单由 CI 生成，一旦字段缺失或写错，反序列化不应该把 App 打崩，
 * 而应该让调用方看到"没数据"并走降级路径。
 */
data class UpdateManifest(
    val versionName: String? = null,
    val versionCode: Long = 0,
    val tag: String? = null,
    val releaseUrl: String? = null,
    val apk: UpdateArtifact? = null,
    val deltas: List<UpdateDelta>? = null,
) {
    /** 相对 [current] 是否有更新。 */
    fun isNewerThan(current: Long): Boolean = versionCode > current

    /**
     * 找到能用于 [currentCode] 的增量包。
     *
     * 除了版本号，还要求本机 APK 的指纹与生成补丁时那份**逐字节一致** ——
     * 补丁是针对特定字节序列生成的，指纹对不上就必须退回全量。
     */
    fun deltaFor(currentCode: Long, currentSha256: String?): UpdateDelta? {
        val want = currentSha256?.lowercase() ?: return null
        return deltas.orEmpty().firstOrNull {
            it.fromVersionCode == currentCode && it.fromSha256.lowercase() == want
        }
    }
}

/** 全量安装包。 */
data class UpdateArtifact(
    val url: String = "",
    val size: Long = 0,
    val sha256: String = "",
)

/** 相对某个历史版本的二进制差分。 */
data class UpdateDelta(
    val fromVersionCode: Long = 0,
    val fromSha256: String = "",
    val url: String = "",
    val size: Long = 0,
    val sha256: String = "",
)
