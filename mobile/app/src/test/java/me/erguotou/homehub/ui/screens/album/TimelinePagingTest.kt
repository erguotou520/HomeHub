package me.erguotou.homehub.ui.screens.album

import me.erguotou.homehub.data.PhotoItem
import me.erguotou.homehub.data.TimelineGroup
import me.erguotou.homehub.data.TimelineResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 时间轴分页合并的单测。
 *
 * 这段逻辑看着琐碎，出错的方式却都很安静：跨页的同一天会变成两个同日标题，
 * 而 LazyVerticalGrid 遇到重复 key 会直接崩。所以这里钉住三件事 ——
 * 拼接后标题不重复、条目一条不丢，以及半截游标不会被当成「还有下一页」。
 */
class TimelinePagingTest {

    private fun photo(id: Long, takenAt: Long) = PhotoItem(
        id = id,
        dirId = 1,
        dirName = "photos",
        relPath = "2026/$id.jpg",
        name = "$id.jpg",
        takenAt = takenAt
    )

    /**
     * 一页内容：一天的若干张。`count` 是这一天的总数 ——
     * 服务端每页都报「全天」的数字，所以跨页那天的 count 两边一致。
     */
    private fun page(key: String, count: Long, ids: List<Long>, newestAt: Long): List<TimelineGroup> =
        listOf(
            TimelineGroup(
                key = key,
                label = key,
                year = 2026,
                month = 10,
                count = count,
                items = ids.mapIndexed { i, id -> photo(id, newestAt - i) }
            )
        )

    @Test
    fun `跨页的同一天合并成一个标题`() {
        val loaded = page("2026-10-01", count = 4, ids = listOf(10, 9), newestAt = 1000)
        val next = page("2026-10-01", count = 4, ids = listOf(8, 7), newestAt = 998)

        val merged = mergeTimeline(loaded, next)

        assertEquals(1, merged.size)
        assertEquals("2026-10-01", merged[0].key)
        assertEquals(listOf(10L, 9L, 8L, 7L), merged[0].items.map { it.id })
        assertEquals(4L, merged[0].count)
    }

    @Test
    fun `不同天的页直接接在后面`() {
        val loaded = page("2026-10-01", count = 2, ids = listOf(10, 9), newestAt = 1000)
        val next = page("2026-09-30", count = 3, ids = listOf(8, 7, 6), newestAt = 900)

        val merged = mergeTimeline(loaded, next)

        assertEquals(listOf("2026-10-01", "2026-09-30"), merged.map { it.key })
        assertEquals(5L, merged.sumOf { it.count })
    }

    @Test
    fun `空页不改变已有内容`() {
        val loaded = page("2026-10-01", count = 2, ids = listOf(10, 9), newestAt = 1000)

        assertEquals(loaded, mergeTimeline(loaded, emptyList()))
        assertEquals(loaded, mergeTimeline(emptyList(), loaded))
    }

    @Test
    fun `分页遍历后与一次全量完全相同`() {
        // 一个四天的库，其中 10-01 有 5 张，会被切在中间。
        val whole = listOf(
            page("2026-10-02", count = 2, ids = listOf(12, 11), newestAt = 1000),
            page("2026-10-01", count = 5, ids = listOf(10, 9, 8, 7, 6), newestAt = 900),
            page("2026-09-30", count = 1, ids = listOf(5), newestAt = 800)
        ).flatten()

        // 每页 3 条地切碎（第一页正好跨天），再按同样的顺序合并回来。
        val pages = listOf(
            page("2026-10-02", 2, listOf(12, 11), 1000) +
                page("2026-10-01", 5, listOf(10), 900),
            page("2026-10-01", 5, listOf(9, 8), 899),
            page("2026-10-01", 5, listOf(7, 6), 897),
            page("2026-09-30", 1, listOf(5), 800)
        )

        val merged = pages.fold(emptyList<TimelineGroup>()) { acc, p -> mergeTimeline(acc, p) }

        assertEquals(
            whole.flatMap { g -> g.items.map { it.id } },
            merged.flatMap { g -> g.items.map { it.id } }
        )
        assertEquals(whole.map { it.count }, merged.map { it.count })

        // 网格的 key：日期标题 + 每张照片。重复 key 会让 LazyVerticalGrid 崩。
        val keys = merged.flatMap { g -> listOf("h-${g.key}") + g.items.map { it.id } }
        assertEquals(keys.size, keys.distinct().size)
    }

    @Test
    fun `半截游标不被当成下一页`() {
        // 只给了 next_before 没给 next_before_id 时位置是不确定的。
        // 当成「还有下一页」会拿着错的位置去请求，宁可当成到底。
        assertNull(TimelineResponse(nextBefore = 100, nextBeforeId = null).cursor)
        assertNull(TimelineResponse(hasMore = true, nextBefore = null, nextBeforeId = 5).cursor)
        assertEquals(100L to 5L, TimelineResponse(nextBefore = 100, nextBeforeId = 5).cursor)
    }
}
