package me.erguotou.homehub.ui.screens.album

import me.erguotou.homehub.data.GeoPoint
import me.erguotou.homehub.data.PersonGroup
import me.erguotou.homehub.data.PhotoItem
import me.erguotou.homehub.data.TagSummary
import me.erguotou.homehub.data.TimelineGroup
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「该显示加载中，还是该显示空状态」的单测。
 *
 * AlbumScreen 的规矩是一条：**全屏 spinner 只在当前视图确实没东西可显示时出现**，
 * 即 `loading && !hasContent`。前半截是显然的，会出错的全在后半截 —— 状态里同时
 * 躺着好几个视图的数据，判据必须取屏幕上真正显示的那一份。取错了不会报错，只会
 * 先闪一下「还没有标签」再跳成数据，而且只在第一次进入时闪，很难再遇到。
 *
 * 反过来的一条同样要守：请求真的返回空（`loading == false`）时，空状态就是正确
 * 答案，不能拿 spinner 盖住它 —— 那会变成永远转圈。
 */
class AlbumContentStateTest {

    private fun photo(id: Long) = PhotoItem(
        id = id,
        dirId = 1,
        dirName = "photos",
        relPath = "2026/$id.jpg",
        name = "$id.jpg"
    )

    private val timeline = listOf(
        TimelineGroup(key = "2026-10-08", label = "10月8日", year = 2026, month = 10, count = 1, items = listOf(photo(1)))
    )
    private val tags = listOf(TagSummary(tag = "猫", kind = "object", photoCount = 3))
    private val treeFolders = listOf(
        TreeNode(dirId = 1, label = "photos", dirName = "photos", path = "", count = 1)
    )
    private val people = listOf(PersonGroup(id = 7, name = "小明", photoCount = 2))
    private val points = listOf(GeoPoint(lat = 31.2, lng = 121.4, count = 5, photoIds = listOf(1, 2)))

    @Test
    fun `时间轴的照片不能冒充分类的内容`() {
        // 现场：时间轴早已加载好，用户第一次点「分类」，标签还在路上。
        // 旧判据看的是 `groups`，非空 → 守卫放行 → 落到空的标签列表上闪一下空状态。
        val state = AlbumUiState(view = AlbumView.TAGS, loading = true, groups = timeline)

        assertFalse(state.hasContent)
    }

    @Test
    fun `切换视图后各自的数据只认自己那一份`() {
        // 五份数据同时在 state 里，每个视图只该看见自己的。
        val all = AlbumUiState(
            view = AlbumView.TAGS,
            loading = true,
            groups = timeline,
            tags = tags,
            treeFolders = treeFolders,
            people = people,
            points = points,
        )

        assertTrue(all.hasContent)
        assertTrue(all.copy(view = AlbumView.TIMELINE).hasContent)
        assertTrue(all.copy(view = AlbumView.TREE).hasContent)
        assertTrue(all.copy(view = AlbumView.PEOPLE).hasContent)
        assertTrue(all.copy(view = AlbumView.GEO).hasContent)
        assertTrue(all.copy(view = AlbumView.TAGS).hasContent)

        // 只清掉当前视图那一份，其余照旧。
        assertFalse(all.copy(tags = emptyList()).hasContent)
        assertTrue(all.copy(tags = emptyList(), view = AlbumView.PEOPLE).hasContent)
    }

    @Test
    fun `分类下第一次点某个分类，看的是筛选结果而不是标签列表`() {
        // 点标签时 `tags` 非空（它就摆在屏幕上）、`filtered` 还空着。
        // 判据若取了 `tags`，就会放行到空的照片网格上闪「没有照片」。
        val loading = AlbumUiState(
            view = AlbumView.TAGS,
            loading = true,
            tags = tags,
            activeFilter = "猫",
        )
        assertFalse(loading.hasContent)

        val arrived = loading.copy(loading = false, filtered = listOf(photo(1), photo(2)))
        assertTrue(arrived.hasContent)
    }

    @Test
    fun `筛选态不理会别的视图的数据`() {
        // 时间轴上还有一大堆照片，但屏幕上现在显示的是筛选结果。
        val state = AlbumUiState(
            view = AlbumView.TIMELINE,
            loading = true,
            groups = timeline,
            tags = tags,
            activeFilter = "地点 上海市",
        )

        assertFalse(state.hasContent)
    }

    @Test
    fun `清掉筛选后回到列表，旧数据还在就不闪 spinner`() {
        // clearFilter(): activeFilter 与 filtered 一起清掉，再重新拉当前视图。
        // 标签列表还在内存里 —— 这正是要保留的缓存优先。
        val state = AlbumUiState(
            view = AlbumView.TAGS,
            loading = true,
            tags = tags,
            activeFilter = null,
            filtered = emptyList(),
        )

        assertTrue(state.hasContent)
    }

    @Test
    fun `接口真的返回空时，空状态才是答案`() {
        // loading 落下之后 hasContent 仍为假 → 守卫不再接管 → 屏幕落到空状态。
        // 若这里为真，界面会永远停在「加载中」。
        val empty = AlbumUiState(view = AlbumView.TAGS, loading = false, groups = timeline)
        assertFalse(empty.hasContent)

        assertFalse(AlbumUiState(view = AlbumView.TREE, loading = false).hasContent)
        assertFalse(AlbumUiState(view = AlbumView.PEOPLE, loading = false, people = emptyList()).hasContent)
        assertFalse(AlbumUiState(view = AlbumView.GEO, loading = false, points = emptyList()).hasContent)
        assertFalse(AlbumUiState(view = AlbumView.TIMELINE, loading = false, groups = emptyList()).hasContent)

        // 筛选命中零张也是「真空」，同样要老老实实显示空状态。
        val noHit = AlbumUiState(view = AlbumView.TAGS, loading = false, activeFilter = "不存在")
        assertFalse(noHit.hasContent)
    }

    @Test
    fun `初始态没有内容`() {
        assertFalse(AlbumUiState().hasContent)
    }

    @Test
    fun `目录层文件夹与照片任一到齐都算有内容`() {
        // 这一层的两份数据是分开到的：先有子文件夹，照片还在路上。
        val foldersOnly = AlbumUiState(
            view = AlbumView.TREE,
            loading = true,
            treeFolders = treeFolders
        )
        assertTrue(foldersOnly.hasContent)

        val photosOnly = AlbumUiState(
            view = AlbumView.TREE,
            loading = true,
            treePhotos = listOf(photo(1))
        )
        assertTrue(photosOnly.hasContent)
    }

    @Test
    fun `钻进下一层时两份旧数据都清掉，屏幕交给 spinner`() {
        // 换层不是切视图，是换一份数据集：留着上一层的缩略图会让人以为没点动。
        val drilling = AlbumUiState(view = AlbumView.TREE, loading = true)
        assertFalse(drilling.hasContent)

        // 数据到了（哪怕是空目录）就该落定，不能变成永远转圈。
        val arrived = drilling.copy(loading = false, treeFolders = treeFolders)
        assertTrue(arrived.hasContent)
    }
}
