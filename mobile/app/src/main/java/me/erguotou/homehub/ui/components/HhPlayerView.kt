package me.erguotou.homehub.ui.components

import android.content.Context
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.ImageView
import androidx.core.content.ContextCompat
import androidx.media3.ui.PlayerView
import androidx.media3.ui.R as Media3R
import me.erguotou.homehub.R

/**
 * [PlayerView] with a gesture layer and a fullscreen button driven from Compose.
 *
 * Why the gestures live here instead of in a Compose `pointerInput` Box: a
 * full-size Box stacked over an `AndroidView` wins every Compose hit test, so
 * the interop view underneath never sees a touch — play/pause, the seek bar and
 * the settings gear all go dead. [dispatchTouchEvent] is the opposite approach:
 * every event still reaches Media3 unchanged, and only the two gestures we
 * actually own (double tap, long press) are swallowed, and only *after* they
 * have been recognised.
 *
 * Media3's own fullscreen button is kept for its look and its place in the
 * control bar, but its click listener is replaced: the library keeps an
 * internal `isFullScreen` boolean that cannot be set from outside, which makes
 * it impossible to keep the icon in sync when something other than the button
 * (the Back key) leaves fullscreen. [showFullscreenEntered] paints the state
 * instead. A no-op [setFullscreenButtonClickListener] is still installed by
 * the caller because that is what makes Media3 reveal the button at all.
 */
class HhPlayerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : PlayerView(context, attrs, defStyleAttr) {

    /** What a press-and-hold on one half of the video is doing right now. */
    enum class Hold { NONE, REWIND, FORWARD }

    /** Double tap callback: -1 for the left half, +1 for the right half. */
    var onDoubleTapSeek: ((Int) -> Unit)? = null

    /** Hold state changes, including the [Hold.NONE] that ends one. */
    var onHoldChanged: ((Hold) -> Unit)? = null

    /**
     * Pixels at the top that gestures must keep clear of. The viewer's chrome
     * (back / filename / actions) floats there when not in fullscreen; its
     * buttons consume their own touches, but the gaps between them would
     * otherwise count as video area.
     */
    var gestureTopInsetPx: Int = 0

    private var consuming = false
    private var downDispatched = false
    private var hold = Hold.NONE
    private var lastEntered: Boolean? = null

    /**
     * Set by a recognised double tap, cleared by the next finger-down. Covers
     * the window where the detector's long-press timer is still armed but the
     * finger has already left (see [onDoubleTap]).
     */
    private var holdSuppressed = false

    private val detector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            // Returning true marks interest without consuming anything.
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (!gestureAllowed(e)) return false
                // A double tap never also means "hold": the detector arms its
                // long-press timer right after this callback returns, and the
                // matching UP is swallowed below, so without this flag the
                // timer would fire a phantom hold 500ms later.
                holdSuppressed = true
                onDoubleTapSeek?.invoke(sideOf(e))
                startConsuming(e)
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                if (consuming || holdSuppressed || !gestureAllowed(e)) return
                val mode = if (sideOf(e) < 0) Hold.REWIND else Hold.FORWARD
                hold = mode
                onHoldChanged?.invoke(mode)
                startConsuming(e)
            }
        }
    )

    /** Replaces Media3's fullscreen click handler with one owned by the page. */
    fun bindFullscreenButton(onClick: () -> Unit) {
        val listener = View.OnClickListener { onClick() }
        findViewById<View>(Media3R.id.exo_fullscreen)?.setOnClickListener(listener)
        // The minimal-controls variant of the same button (shown in some
        // controller states) has to agree with the main one.
        findViewById<View>(Media3R.id.exo_minimal_fullscreen)?.setOnClickListener(listener)
    }

    /** Keeps the button's icon and accessible label in sync with our state. */
    fun showFullscreenEntered(entered: Boolean) {
        if (lastEntered == entered) return
        lastEntered = entered
        val icon = if (entered) {
            Media3R.drawable.exo_styled_controls_fullscreen_exit
        } else {
            Media3R.drawable.exo_styled_controls_fullscreen_enter
        }
        val label = if (entered) {
            R.string.exo_controls_fullscreen_exit_description
        } else {
            R.string.exo_controls_fullscreen_enter_description
        }
        val drawable = ContextCompat.getDrawable(context, icon)
        listOfNotNull(
            findViewById<ImageView>(Media3R.id.exo_fullscreen),
            findViewById<ImageView>(Media3R.id.exo_minimal_fullscreen)
        ).forEach { button ->
            button.setImageDrawable(drawable)
            button.contentDescription = context.getString(label)
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (consuming) {
            if (ev.actionMasked == MotionEvent.ACTION_UP ||
                ev.actionMasked == MotionEvent.ACTION_CANCEL
            ) {
                endGesture()
            }
            return true
        }

        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            // A new touch re-enables hold; only the double tap that just
            // finished keeps it off.
            holdSuppressed = false
        }
        detector.onTouchEvent(ev)
        // onDoubleTap / onLongPress may have flipped `consuming` from inside
        // the detector; in that case this very event is ours too.
        if (consuming) return true

        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> downDispatched = true
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> downDispatched = false
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun sideOf(e: MotionEvent): Int = if (e.x < width / 2f) -1 else 1

    /**
     * The controller's own clusters belong to Media3: the bottom bar (seek bar,
     * time, play/pause, gear) and — while it is up — the centre buttons. Their
     * bounds are read from the live views rather than hard-coded so a rotated
     * or differently sized controller keeps the same exclusion.
     *
     * Without this, a double tap landing on the visible centre buttons fires
     * the button first (+15s from the skip button) and the gesture second,
     * which is why the cluster is treated as "not video".
     */
    private fun gestureAllowed(e: MotionEvent): Boolean {
        if (width <= 0 || e.y < gestureTopInsetPx) return false
        if (!isControllerFullyVisible) return true
        val selfLoc = IntArray(2)
        getLocationOnScreen(selfLoc)
        for (id in ControlBlocks) {
            val block = findViewById<View>(id) ?: continue
            if (!block.isShown || block.width <= 0) continue
            val loc = IntArray(2)
            block.getLocationOnScreen(loc)
            val left = loc[0] - selfLoc[0]
            val top = loc[1] - selfLoc[1]
            if (e.x >= left && e.x <= left + block.width &&
                e.y >= top && e.y <= top + block.height
            ) {
                return false
            }
        }
        return true
    }

    /**
     * Nothing is taken away from Media3 until a gesture is recognised. When it
     * is, a CANCEL clears any child that was mid-press (the controller's
     * buttons), and every following event is swallowed so the second tap of a
     * double tap — or the release of a hold — cannot also toggle the controls.
     *
     * The CANCEL also goes to the detector, and that is not cosmetic: a double
     * tap is recognised on the second DOWN, whose matching UP is then swallowed.
     * GestureDetector would still be sitting in its double-tap state with the
     * long-press timer armed, so half a second later it fired a phantom
     * long press — the seek and the 2x speed at the same time. ACTION_CANCEL is
     * what resets it (`cancel()` drops both the double-tap flag and the pending
     * LONG_PRESS message).
     */
    private fun startConsuming(ev: MotionEvent) {
        if (consuming) return
        consuming = true
        val cancel = MotionEvent.obtain(
            ev.downTime, ev.eventTime, MotionEvent.ACTION_CANCEL, 0f, 0f, 0
        )
        try {
            detector.onTouchEvent(cancel)
            if (downDispatched) super.dispatchTouchEvent(cancel)
        } finally {
            cancel.recycle()
        }
        downDispatched = false
    }

    private fun endGesture() {
        consuming = false
        downDispatched = false
        if (hold != Hold.NONE) {
            hold = Hold.NONE
            onHoldChanged?.invoke(Hold.NONE)
        }
    }

    private companion object {
        /** Controller clusters that must keep their own touches. */
        val ControlBlocks = intArrayOf(
            Media3R.id.exo_bottom_bar,
            Media3R.id.exo_center_controls,
            Media3R.id.exo_minimal_controls
        )
    }
}
