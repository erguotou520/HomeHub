package me.erguotou.homehub.update

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assume
import org.junit.Test
import java.io.File

/**
 * 补丁应用器的往返测试，数据由真实的 `bsdiff` 命令生成（见 resources/bspatch）。
 *
 * 用例覆盖了 bsdiff 三种控制块组合：原地替换（diff+extra）、插入（extra+正
 * seek）、以及跨 64 KB 缓冲边界的连续区段。旧包 120000 字节、新包 126750
 * 字节、补丁 17228 字节 —— 补丁比全量小 86%，正是增量下发的收益所在。
 */
class BsPatchTest {

    private fun resource(name: String): ByteArray =
        checkNotNull(javaClass.classLoader?.getResourceAsStream(name)) { "缺少测试资源 $name" }
            .use { it.readBytes() }

    private fun temp(bytes: ByteArray): File =
        File.createTempFile("bspatch-src", ".bin").apply {
            writeBytes(bytes)
            deleteOnExit()
        }

    private fun output(): File =
        File.createTempFile("bspatch-out", ".bin").apply { deleteOnExit() }

    @Test
    fun `逐字节还原出目标文件`() {
        val old = temp(resource("bspatch/old.bin"))
        val patch = temp(resource("bspatch/out.patch"))
        val expected = resource("bspatch/new.bin")
        val out = output()

        BsPatch.apply(old, patch, out)

        assertArrayEquals(expected, out.readBytes())
    }

    /**
     * 这是调用方必须做 sha256 校验的原因：补丁与旧包不匹配时格式依然合法，
     * 应用过程不会报错，只是安静地产出一份错误内容。
     */
    @Test
    fun `旧包不匹配时不报错但结果错误`() {
        val wrong = temp(ByteArray(120_000) { 0x5A })
        val patch = temp(resource("bspatch/out.patch"))
        val expected = resource("bspatch/new.bin")
        val out = output()

        BsPatch.apply(wrong, patch, out)

        assertFalse("旧包不匹配却还原出了正确内容，说明用例本身失效了", expected.contentEquals(out.readBytes()))
    }

    @Test
    fun `文件头不匹配时明确报错`() {
        val old = temp(resource("bspatch/old.bin"))
        val junk = temp(ByteArray(64) { 0x41 })

        assertThrows(BsPatch.PatchException::class.java) {
            BsPatch.apply(old, junk, output())
        }
    }

    @Test
    fun `补丁被截断时报错而不是产出半个文件`() {
        val old = temp(resource("bspatch/old.bin"))
        val full = resource("bspatch/out.patch")
        val truncated = temp(full.copyOf(full.size / 2))

        assertThrows(Exception::class.java) {
            BsPatch.apply(old, truncated, output())
        }
    }

    /**
     * 真实 APK（约 93 MB）上的往返，顺带给出耗时。
     *
     * 默认跳过：素材太大不适合入库，也就进不了 CI。但改动 [BsPatch] 的算法后
     * 本地必须跑一次 —— 仓库里的 120 KB 样例覆盖不到大文件下的分块与内存行为。
     *
     * 素材怎么造：把一个旧 APK 存成 old.apk，改动后重新构建存成 new.apk，
     * 再 `bsdiff old.apk new.apk real.patch`，并把目录指给 HOMEHUB_BSDIFF_DIR。
     */
    @Test
    fun `真实 APK 增量合并`() {
        val dir = System.getenv("HOMEHUB_BSDIFF_DIR")?.let(::File)
        Assume.assumeTrue("未设置 HOMEHUB_BSDIFF_DIR，跳过", dir?.isDirectory == true)
        val old = File(dir, "old.apk")
        val patch = File(dir, "real.patch")
        val expected = File(dir, "new.apk")
        Assume.assumeTrue("素材不齐", old.isFile && patch.isFile && expected.isFile)

        val out = output()
        val started = System.currentTimeMillis()
        BsPatch.apply(old, patch, out)
        val cost = System.currentTimeMillis() - started

        assertEquals("长度应与目标一致", expected.length(), out.length())
        assertEquals(
            "内容必须逐字节一致，否则 v2/v3 签名失效、系统拒装",
            UpdateManager.sha256Of(expected),
            UpdateManager.sha256Of(out),
        )
        println("[真实 APK] 增量合并耗时 ${cost} ms")
    }
}
