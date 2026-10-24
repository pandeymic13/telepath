package dev.plume

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.view.View
import android.view.animation.PathInterpolator
import android.widget.ImageView
import android.widget.TextView

/** Fast start, long soft landing: focus, paging and fades all share it so motion feels like one system. */
internal val EASE = PathInterpolator(0.2f, 0f, 0f, 1f)

/**
 * telepath's design tokens, from the theme.* colours in telepath.properties. By default day is frosted white
 * glass with ink text and night is smoky glass with soft-white text. Focused tiles turn solid ([focusFill], with
 * [ink] content) in both themes, so focus reads from across the room on any wallpaper.
 */
internal class UiKit(val dark: Boolean, private val density: Float) {
    val text = if (dark) BuildConfig.NIGHT_TEXT else BuildConfig.DAY_TEXT
    val text2 = if (dark) BuildConfig.NIGHT_TEXT2 else BuildConfig.DAY_TEXT2
    val text3 = if (dark) BuildConfig.NIGHT_TEXT3 else BuildConfig.DAY_TEXT3
    val divider = if (dark) BuildConfig.NIGHT_DIVIDER else BuildConfig.DAY_DIVIDER
    val ink = BuildConfig.FOCUS_TEXT
    val focusFill = BuildConfig.FOCUS_FILL
    val up = if (dark) 0xFF7BE495.toInt() else 0xFF167A35.toInt()
    val upFill = if (dark) 0x3334C759 else 0x3334C759
    val down = if (dark) 0xFFFF8A80.toInt() else 0xFFC5221F.toInt()
    val downFill = if (dark) 0x33FF453A else 0x2EFF3B30

    /** Wallpaper scrims and the frosted widgets backdrop are tinted toward this. */
    val ground = if (dark) BuildConfig.NIGHT_GROUND else BuildConfig.DAY_GROUND
    val frostTint = if (dark) BuildConfig.NIGHT_FROST else BuildConfig.DAY_FROST

    fun px(dp: Float) = dp * density
    fun px(dp: Int) = (dp * density).toInt()

    /** Resting glass: translucent fill, with a hairline rim that is brighter when [lit]. */
    fun glass(radiusDp: Float, lit: Boolean = false): GradientDrawable =
        GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, when {
            dark && lit -> BuildConfig.NIGHT_GLASS_FOCUSED
            dark -> BuildConfig.NIGHT_GLASS
            lit -> BuildConfig.DAY_GLASS_FOCUSED
            else -> BuildConfig.DAY_GLASS
        }.clone()).apply {
            cornerRadius = px(radiusDp)
            setStroke(maxOf(1, px(1f).toInt()), when {
                dark && lit -> BuildConfig.NIGHT_GLASS_FOCUSED_RIM
                dark -> BuildConfig.NIGHT_GLASS_RIM
                lit -> BuildConfig.DAY_GLASS_FOCUSED_RIM
                else -> BuildConfig.DAY_GLASS_RIM
            })
            setDither(true)
        }

    /** A rim drawn over full-bleed art (banners, posters) while focused. */
    fun focusRim(radiusDp: Float): GradientDrawable = GradientDrawable().apply {
        cornerRadius = px(radiusDp)
        setStroke(px(2), 0xF2FFFFFF.toInt())
        alpha = 0
    }

    /** Pill behind a quote's change. */
    fun pill(positive: Boolean): GradientDrawable = GradientDrawable().apply {
        cornerRadius = px(8f)
        setColor(if (positive) upFill else downFill)
    }
}

/** Scales and raises a focused view; the shadow comes from translationZ so layout never moves. */
internal fun View.lift(focused: Boolean, scale: Float, raise: Float) {
    animate().scaleX(if (focused) scale else 1f).scaleY(if (focused) scale else 1f)
        .translationZ(if (focused) raise else 0f)
        .setDuration(if (focused) 220L else 170L).setInterpolator(EASE).start()
}

/**
 * Focus for a glass tile: a solid white layer fades in over the glass while its icon and labels glide
 * to ink, then everything reverses on blur. One animator drives it all, so nothing flickers mid-way.
 * [accent]: a console's own colour. The resting glass takes a tint of it, and focus fills the tile with it,
 * with a white icon and labels, instead of white with ink.
 */
internal fun focusTile(tile: View, ui: UiKit, radiusDp: Float, icon: ImageView?, labels: List<TextView>, restText: Int,
                       restIcon: Int = restText, scale: Float = 1.07f, accent: Int? = null,
                       onFocus: ((Boolean) -> Unit)? = null) {
    val white = GradientDrawable().apply {
        cornerRadius = ui.px(radiusDp)
        setColor(accent ?: ui.focusFill)
        alpha = 0
    }
    val tint = accent?.let {
        GradientDrawable().apply {
            cornerRadius = ui.px(radiusDp)
            setColor(Color.argb(if (ui.dark) 0x66 else 0x40, Color.red(it), Color.green(it), Color.blue(it)))
        }
    }
    tile.background = LayerDrawable(listOfNotNull(ui.glass(radiusDp), tint, white).toTypedArray())
    val focusInk = if (accent != null) Color.WHITE else ui.ink
    tile.isFocusable = true
    tile.isClickable = true
    tile.clipToOutline = true
    icon?.setColorFilter(restIcon)
    labels.forEach { it.setTextColor(restText) }
    var running: ValueAnimator? = null
    tile.setOnFocusChangeListener { v, focused ->
        running?.cancel()
        val from = white.alpha / 255f
        running = ValueAnimator.ofFloat(from, if (focused) 1f else 0f).apply {
            duration = if (focused) 200L else 160L
            interpolator = EASE
            val colours = ArgbEvaluator()
            addUpdateListener {
                val f = it.animatedValue as Float
                white.alpha = (f * 255).toInt()
                icon?.setColorFilter(colours.evaluate(f, restIcon, focusInk) as Int)
                val ink = colours.evaluate(f, restText, focusInk) as Int
                labels.forEach { l -> l.setTextColor(ink) }
            }
            start()
        }
        v.lift(focused, scale, ui.px(10f))
        onFocus?.invoke(focused)
    }
}

/** Focus for full-bleed art: a bright rim fades in while the art scales up and casts a shadow. */
internal fun focusArt(frame: View, ui: UiKit, radiusDp: Float, scale: Float, onFocus: ((Boolean) -> Unit)? = null) {
    val rim = ui.focusRim(radiusDp)
    frame.foreground = rim
    frame.isFocusable = true
    frame.isClickable = true
    frame.clipToOutline = true
    var running: ValueAnimator? = null
    frame.setOnFocusChangeListener { v, focused ->
        running?.cancel()
        running = ValueAnimator.ofInt(rim.alpha, if (focused) 255 else 0).apply {
            duration = if (focused) 200L else 150L
            interpolator = EASE
            addUpdateListener { rim.alpha = it.animatedValue as Int }
            start()
        }
        v.lift(focused, scale, ui.px(14f))
        onFocus?.invoke(focused)
    }
}

/**
 * A soft, frosted copy of [src]: shrunk to a few hundred pixels and box-blurred. Shown stretched to full
 * screen, the GPU's filtering smooths it into frosted glass for ~100 KB, instead of a live full-screen blur.
 */
internal fun frostedCopy(src: Bitmap): Bitmap {
    val w = 160
    val h = maxOf(1, w * src.height / maxOf(1, src.width))
    val small = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    Canvas(small).drawBitmap(src, Rect(0, 0, src.width, src.height), Rect(0, 0, w, h), Paint(Paint.FILTER_BITMAP_FLAG))
    val px = IntArray(w * h)
    small.getPixels(px, 0, w, 0, 0, w, h)
    repeat(3) { boxBlur(px, w, h, 3) }
    small.setPixels(px, 0, w, 0, 0, w, h)
    return small
}

/** One horizontal and one vertical box-blur pass of [r] pixels over ARGB [px]. */
internal fun boxBlur(px: IntArray, w: Int, h: Int, r: Int) {
    val tmp = IntArray(px.size)
    fun pass(src: IntArray, dst: IntArray, len: Int, lines: Int, step: Int, lineStep: Int) {
        for (line in 0 until lines) {
            for (i in 0 until len) {
                var a = 0; var rr = 0; var g = 0; var b = 0; var n = 0
                for (k in -r..r) {
                    val j = (i + k).coerceIn(0, len - 1)
                    val c = src[line * lineStep + j * step]
                    a += c ushr 24; rr += (c shr 16) and 0xFF; g += (c shr 8) and 0xFF; b += c and 0xFF; n++
                }
                dst[line * lineStep + i * step] = ((a / n) shl 24) or ((rr / n) shl 16) or ((g / n) shl 8) or (b / n)
            }
        }
    }
    pass(px, tmp, w, h, 1, w)
    pass(tmp, px, h, w, w, 1)
}

/** Draws [d] into a [w]×[h] bitmap; used for icon-only apps' composed banners. */
internal fun Drawable.toBitmap(w: Int, h: Int): Bitmap {
    val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val old = Rect(bounds)
    setBounds(0, 0, w, h)
    draw(Canvas(out))
    bounds = old
    return out
}
