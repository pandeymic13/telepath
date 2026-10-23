package dev.plume

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Rect
import android.util.AttributeSet
import android.view.View
import android.widget.ScrollView

/** Where the widgets page rests: the bottom of the Weather/Watchlist row [gap] above the screen's bottom edge. */
internal fun widgetsPageY(cardsBottom: Int, gap: Int, viewport: Int, maxScroll: Int) =
    (cardsBottom + gap - viewport).coerceIn(0, maxOf(0, maxScroll))

/**
 * The home screen scrolls by page, not by item. Page 0 is the wallpaper page (clock, Continue Watching,
 * dock on the bottom edge); page 1 brings the dock to the top with Inputs, Shortcuts, Weather and Watchlist
 * under it, like the reference. The focused view's row picks the page.
 */
class HomeScrollView(context: Context, attrs: AttributeSet?) : ScrollView(context, attrs) {
    /** 0 on the wallpaper page, 1 on the widgets page; the home screen frosts its wallpaper with it. */
    var onScrollFraction: ((Float) -> Unit)? = null
    var page = 0
        private set
    private var animator: ValueAnimator? = null
    private val gap = (30 * resources.displayMetrics.density).toInt()

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val home = findViewById<View>(R.id.homePage) ?: return
        if (h > 0 && home.layoutParams.height != h) post { home.layoutParams = home.layoutParams.apply { height = h } }
    }

    /** Scrolling follows pages (see [show]), never "keep the focused view in sight". */
    override fun computeScrollDeltaToGetChildRectOnScreen(rect: Rect) = 0

    override fun requestChildFocus(child: View?, focused: View?) {
        super.requestChildFocus(child, focused)
        if (focused != null) show(pageOf(focused), animate = isLaidOut)
    }

    private fun pageOf(v: View) = when (v.getTag(R.id.homeRow)) {
        "tiles", "cards" -> 1
        else -> 0
    }

    fun show(target: Int, animate: Boolean) {
        page = target
        val y = targetY(target)
        animator?.cancel()
        if (!animate || y == scrollY) {
            scrollTo(0, y)
            return
        }
        animator = ValueAnimator.ofInt(scrollY, y).apply {
            duration = 460L
            interpolator = EASE
            addUpdateListener { scrollTo(0, it.animatedValue as Int) }
            start()
        }
    }

    private fun maxScroll() = maxOf(0, (getChildAt(0)?.height ?: 0) - height)

    private fun targetY(p: Int): Int = when (p) {
        0 -> 0
        1 -> {
            val cards = findViewById<View>(R.id.widgetCards)
            if (cards == null || !cards.isLaidOut) maxScroll() else {
                val r = Rect(0, 0, cards.width, cards.height)
                offsetDescendantRectToMyCoords(cards, r)
                widgetsPageY(r.bottom, gap, height, maxScroll())
            }
        }
        else -> maxScroll()
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        if (animator?.isRunning != true) scrollTo(0, targetY(page))
    }

    override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
        super.onScrollChanged(l, t, oldl, oldt)
        val full = targetY(1)
        onScrollFraction?.invoke(if (full <= 0) 0f else (t.toFloat() / full).coerceIn(0f, 1f))
    }
}
