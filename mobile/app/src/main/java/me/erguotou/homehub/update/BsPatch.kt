package me.erguotou.homehub.update

import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile

/**
 * 应用 bsdiff 补丁（标准 BSDIFF40 容器）。
 *
 * 为什么必须做二进制差分，而不是「只下发变化的几个文件」：APK 的 v2/v3 签名覆盖
 * 整个文件的字节，任何形式的重新打包都会让签名失效，而客户端手里没有私钥。
 * 二进制差分把新包**逐字节**还原出来，签名因此仍然有效 —— 这是它能被系统当成
 * 合法升级包接受的前提，也是[apply]对结果不做任何"修补"的原因。
 *
 * 容器的三段数据都是 bzip2 压缩的，而 JDK 与 Android 都没有 bzip2 实现，
 * 所以依赖 commons-compress（只用到 [BZip2CompressorInputStream]）。
 */
object BsPatch {

    private const val BLOCK = 1 shl 16
    private const val HEADER = 32L
    private val MAGIC = "BSDIFF40".toByteArray(Charsets.US_ASCII)

    /** 补丁不可用（头部损坏、数据截断、与旧包结构不符）。 */
    class PatchException(message: String) : Exception(message)

    /**
     * 把 [patch] 应用到 [old]，完整结果写入 [destination]。
     *
     * 注意：补丁与旧包不匹配时这里**不会**报错 —— 格式依然合法，只会静默产出
     * 错误内容。所以调用方拿到结果后必须再核对一次 sha256 才能交给安装器。
     */
    fun apply(old: File, patch: File, destination: File) {
        val (ctrlLen, diffLen, newSize) = readHeader(patch)
        val extraLen = patch.length() - HEADER - ctrlLen - diffLen
        if (extraLen < 0) throw PatchException("补丁长度与头部声明不符")

        // 三段各自开一个文件句柄并定位到自己的起点。**不能**共用一个顺序流：
        // bzip2 解压器并不会在构造时读完整个段，它按需读取，共用一个流会让
        // 后一段从错误的位置开始（表现为 "Stream is not in the BZip2 format"）。
        openSection(patch, HEADER, ctrlLen).use { ctrlIn ->
            openSection(patch, HEADER + ctrlLen, diffLen).use { diffIn ->
                openSection(patch, HEADER + ctrlLen + diffLen, extraLen).use { extraIn ->
                    val ctrl = BZip2CompressorInputStream(ctrlIn, true)
                    val diff = BZip2CompressorInputStream(diffIn, true)
                    val extra = BZip2CompressorInputStream(extraIn, true)

                    RandomAccessFile(old, "r").use { oldFile ->
                        FileOutputStream(destination).buffered(BLOCK).use { out ->
                            expand(ctrl, diff, extra, oldFile, out, newSize)
                        }
                    }
                }
            }
        }
    }

    private data class Header(val ctrlLen: Long, val diffLen: Long, val newSize: Long)

    private fun readHeader(patch: File): Header {
        val header = ByteArray(HEADER.toInt())
        FileInputStream(patch).use { readFully(it, header, 0, HEADER.toInt()) }
        if (!header.copyOf(8).contentEquals(MAGIC)) {
            throw PatchException("不是 bsdiff 补丁：文件头不匹配")
        }
        val ctrlLen = readOffset(header, 8)
        val diffLen = readOffset(header, 16)
        val newSize = readOffset(header, 24)
        if (ctrlLen < 0 || diffLen < 0 || newSize < 0) {
            throw PatchException("补丁头部损坏")
        }
        return Header(ctrlLen, diffLen, newSize)
    }

    /** 打开 [file] 里从 [offset] 开始、长度为 [limit] 的一段。 */
    private fun openSection(file: File, offset: Long, limit: Long): InputStream {
        val fis = FileInputStream(file)
        return try {
            fis.channel.position(offset)
            BoundedStream(fis.buffered(BLOCK), limit)
        } catch (e: Exception) {
            fis.close()
            throw e
        }
    }

    private fun expand(
        ctrl: InputStream,
        diff: InputStream,
        extra: InputStream,
        old: RandomAccessFile,
        out: OutputStream,
        newSize: Long,
    ) {
        val oldSize = old.length()
        val ctrlBuf = ByteArray(24)
        val buf = ByteArray(BLOCK)
        val oldBuf = ByteArray(BLOCK)
        var newPos = 0L
        var oldPos = 0L

        while (newPos < newSize) {
            readFully(ctrl, ctrlBuf, 0, ctrlBuf.size)
            val diffLen = readOffset(ctrlBuf, 0)
            val extraLen = readOffset(ctrlBuf, 8)
            val seek = readOffset(ctrlBuf, 16)
            if (diffLen < 0 || extraLen < 0 || newPos + diffLen > newSize) {
                throw PatchException("补丁控制块损坏")
            }

            var left = diffLen
            while (left > 0) {
                val n = minOf(left, BLOCK.toLong()).toInt()
                readFully(diff, buf, 0, n)
                // 与旧包逐字节相加。落在旧包之外的部分保持原值 —— bsdiff 正是
                // 用这种方式表达"新插入的内容"。
                val from = maxOf(oldPos, 0L)
                val to = minOf(oldPos + n, oldSize)
                if (from < to) {
                    val span = (to - from).toInt()
                    old.seek(from)
                    old.readFully(oldBuf, 0, span)
                    val shift = (from - oldPos).toInt()
                    for (i in 0 until span) {
                        buf[shift + i] = (buf[shift + i] + oldBuf[i]).toByte()
                    }
                }
                out.write(buf, 0, n)
                oldPos += n
                newPos += n
                left -= n
            }

            // extra 段是全新内容，原样抄过去。
            left = extraLen
            while (left > 0) {
                val n = minOf(left, BLOCK.toLong()).toInt()
                readFully(extra, buf, 0, n)
                out.write(buf, 0, n)
                newPos += n
                left -= n
            }

            oldPos += seek
        }
    }

    /** bsdiff 的变长整数：8 字节小端，但最高字节的最高位当作符号位。 */
    private fun readOffset(buf: ByteArray, at: Int): Long {
        var value = buf[at + 7].toLong() and 0x7f
        for (i in 6 downTo 0) {
            value = (value shl 8) or (buf[at + i].toLong() and 0xff)
        }
        return if (buf[at + 7].toLong() and 0x80L != 0L) -value else value
    }

    private fun readFully(input: InputStream, buf: ByteArray, off: Int, len: Int) {
        var done = 0
        while (done < len) {
            val n = input.read(buf, off + done, len - done)
            if (n < 0) throw PatchException("补丁数据意外截断")
            done += n
        }
    }

    /** 把底层流限制在 [limit] 字节内。 */
    private class BoundedStream(
        private val source: InputStream,
        private var remaining: Long,
    ) : InputStream() {

        override fun read(): Int {
            if (remaining <= 0) return -1
            val b = source.read()
            if (b >= 0) remaining--
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (remaining <= 0) return -1
            val n = source.read(b, off, minOf(len.toLong(), remaining).toInt())
            if (n > 0) remaining -= n
            return n
        }

        override fun available(): Int = minOf(remaining, Int.MAX_VALUE.toLong()).toInt()

        override fun close() = source.close()
    }
}
