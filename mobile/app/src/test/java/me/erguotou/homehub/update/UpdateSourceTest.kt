package me.erguotou.homehub.update

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 通道排序的单测。
 *
 * 这段逻辑看着无害，但它是「每次下载少等 15 秒」的全部依据，而且**错了不会报错** ——
 * 只是退化成每次都先赌直连。所以要有个测试把它钉住：无论偏好是多少，候选集合都必须
 * 恰好包含两条通道且不重复、不丢失。
 */
class UpdateSourceTest {

    private val sample = "https://github.com/owner/repo/releases/download/v1/app.apk"

    @Test
    fun `默认顺序是直连在前`() {
        val ordered = UpdateSource.orderedCandidates(sample, preferred = 0)
        assertEquals(listOf(0, 1), ordered.map { it.first })
        assertEquals(sample, ordered[0].second)
        assertEquals(UpdateSource.PROXY_PREFIX + sample, ordered[1].second)
    }

    @Test
    fun `偏好加速时它排到最前，直连退居兜底`() {
        val ordered = UpdateSource.orderedCandidates(sample, preferred = 1)
        assertEquals(listOf(1, 0), ordered.map { it.first })
        assertEquals(UpdateSource.PROXY_PREFIX + sample, ordered[0].second)
        assertEquals(sample, ordered[1].second)
    }

    @Test
    fun `任何输入下都不重不漏，且序号指向的地址固定`() {
        // 越界、负数、乱值都应对齐到「默认顺序」，而不是抛异常或丢通道 ——
        // 那会让更新功能在最需要它的时候彻底哑掉。
        for (preferred in listOf(-1, 0, 1, 2, 99, Int.MIN_VALUE, Int.MAX_VALUE)) {
            val ordered = UpdateSource.orderedCandidates(sample, preferred)
            assertEquals("preferred=$preferred 通道数不对", 2, ordered.size)
            assertEquals("preferred=$preferred 通道号重复", listOf(0, 1), ordered.map { it.first }.sorted())
            // 关键：通道号必须始终指向同一条地址，不能因为重排而错位。
            for ((channel, url) in ordered) {
                assertEquals(
                    "preferred=$preferred 下通道 $channel 指向了错误的地址",
                    UpdateSource.candidates(sample)[channel],
                    url,
                )
            }
        }
    }
}
