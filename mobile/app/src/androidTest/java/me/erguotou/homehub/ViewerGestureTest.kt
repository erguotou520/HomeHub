package me.erguotou.homehub

import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.test.swipeLeft
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.erguotou.homehub.data.PhotoItem
import me.erguotou.homehub.ui.screens.album.PhotoViewerScreen
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real multi-touch coverage for the album viewer.
 *
 * `sendevent` cannot inject a two-finger gesture without root, so the pinch
 * path is only reachable through the Compose test harness. Pinch has no
 * directly observable state, so the zoom is asserted through its effect: the
 * pager locks itself once an image is zoomed (`userScrollEnabled = !pageZoomed`),
 * which we detect by swiping afterwards and checking the page did not move.
 */
@RunWith(AndroidJUnit4::class)
class ViewerGestureTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private fun photo(i: Int) = PhotoItem(
        id = i.toLong(),
        dirId = 1,
        dirName = "photos",
        relPath = "p$i.png",
        name = "p$i.png",
        url = "photos/p$i.png"
    )

    private fun video(i: Int) = PhotoItem(
        id = 100L + i,
        dirId = 1,
        dirName = "photos",
        relPath = "v$i.mp4",
        name = "v$i.mp4",
        url = "photos/v$i.mp4",
        mediaKind = "video"
    )

    private fun showViewer() {
        val photos = (0 until 3).map(::photo)
        show(photos)
    }

    private fun show(photos: List<PhotoItem>) {
        rule.setContent {
            PhotoViewerScreen(
                photos = photos,
                initialIndex = 0,
                urlResolver = { "" },
                mediaUrlResolver = { "" },
                onRotate = { _, _, _ -> },
                onFlip = { _, _, _ -> },
                onRestore = { _, _ -> },
                onDismiss = { }
            )
        }
        rule.waitForIdle()
    }

    /** Spread both pointers apart, which is a pinch-to-zoom-in. */
    private fun androidx.compose.ui.test.SemanticsNodeInteraction.pinchOut() {
        performTouchInput {
            val c = center
            pinch(
                start0 = Offset(c.x - 30f, c.y),
                end0 = Offset(c.x + 260f, c.y),
                start1 = Offset(c.x + 30f, c.y),
                end1 = Offset(c.x - 260f, c.y),
                durationMillis = 300
            )
        }
        rule.waitForIdle()
    }

    /** Baseline: a plain horizontal swipe does page between photos. */
    @Test
    fun swipePagesBetweenPhotos() {
        showViewer()
        rule.onNodeWithText("1 / 3").assertIsDisplayed()
        rule.onNodeWithContentDescription("p0.png").performTouchInput { swipeLeft() }
        rule.waitForIdle()
        rule.onNodeWithText("2 / 3").assertIsDisplayed()
    }

    /**
     * After a two-finger pinch-out the image is zoomed, so the pager must
     * refuse to page. If pinch were broken the swipe below would land on 2 / 3.
     */
    @Test
    fun pinchZoomLocksPaging() {
        showViewer()
        val image = rule.onNodeWithContentDescription("p0.png")
        image.pinchOut()
        image.performTouchInput { swipeLeft() }
        rule.waitForIdle()
        rule.onNodeWithText("1 / 3").assertIsDisplayed()
    }

    /**
     * Baseline for the video page: the player must not swallow a plain swipe,
     * otherwise the pinch assertion below would pass for the wrong reason.
     */
    @Test
    fun swipePagesOverVideo() {
        show(listOf(video(0), photo(1)))
        rule.onNodeWithText("1 / 2").assertIsDisplayed()
        rule.onRoot().performTouchInput { swipeLeft() }
        rule.waitForIdle()
        rule.onNodeWithText("2 / 2").assertIsDisplayed()
    }

    /**
     * The video page deliberately has NO Compose gesture layer over the
     * PlayerView: a full-size `pointerInput` box wins the hit test against an
     * `AndroidView`, so every tap on the controller (play, seek bar, settings)
     * was swallowed and videos looked unplayable. Pinch zoom is therefore a
     * photo-only affordance, and a pinch over the video must NOT lock the
     * pager — otherwise the swipe below would land on 1 / 2.
     *
     * This is the inverse of the old `pinchZoomLocksPagingOnVideo`, which
     * asserted the pre-`e1e6c1a` behaviour and had been failing ever since the
     * layer was removed to unbreak playback.
     */
    @Test
    fun pinchOnVideoDoesNotLockPaging() {
        show(listOf(video(0), photo(1)))
        rule.onRoot().pinchOut()
        rule.onRoot().performTouchInput { swipeLeft() }
        rule.waitForIdle()
        rule.onNodeWithText("2 / 2").assertIsDisplayed()
    }
}
