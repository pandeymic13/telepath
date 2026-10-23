package dev.plume

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * How the clock and Up next read over the wallpaper, with nothing laid over the photo: white text with a soft shadow
 * where the photo behind them is darker, ink text where it's bright, and only where it's too mixed for either (bright
 * and dark side by side) a small glass plate like the dock's.
 */
internal enum class HeaderInk { LIGHT, DARK, PLATE }

internal const val INK_TEXT = 0xFF14171B.toInt()

/** WCAG contrast the text needs: 3:1 for the large clock, 4.5:1 for the small lines (date, Up next). */
internal const val LARGE_TEXT_CONTRAST = 3.0
internal const val SMALL_TEXT_CONTRAST = 4.5

/**
 * The share of the photo behind the text allowed to fall short: a few bright or dark specks don't spoil it. Set from
 * the 50 slideshow photos: those up to 0.12 still read cleanly, those from 0.15 on are too busy without the plate.
 */
internal const val MAX_SHORTFALL = 0.13

/**
 * How much the soft shadow under white text darkens the photo right around its letters (the white style always has
 * it), measured as the share of the photo's light it takes away there.
 */
internal const val SHADOW_DARKENING = 0.35

private val SRGB_TO_LINEAR = DoubleArray(256) { v ->
    val s = v / 255.0
    if (s <= 0.04045) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
}

/** WCAG relative luminance of an sRGB colour, 0..1. */
internal fun relativeLuminance(c: Int) =
    0.2126 * SRGB_TO_LINEAR[(c shr 16) and 0xFF] + 0.7152 * SRGB_TO_LINEAR[(c shr 8) and 0xFF] + 0.0722 * SRGB_TO_LINEAR[c and 0xFF]

internal fun contrastRatio(a: Double, b: Double) = (max(a, b) + 0.05) / (min(a, b) + 0.05)

/**
 * The top [h] rows of a [w]-wide, [pictureH]-tall picture as luminance, one byte per pixel, square-root coded so dark
 * areas keep their detail. Kept for the wallpaper on show (about 0.6 MB at 1080p), so the header can be looked at
 * again when its text changes.
 */
internal class LumaBand(val w: Int, val h: Int, private val q: ByteArray, val pictureH: Int = h) {
    fun luminance(x: Int, y: Int): Double {
        val v = (q[y * w + x].toInt() and 0xFF) / 255.0
        return v * v
    }

    /** The luminance of every pixel in [left, right) × [top, bottom), clipped to the band. */
    fun samples(left: Int, top: Int, right: Int, bottom: Int): DoubleArray {
        val x0 = left.coerceIn(0, w)
        val x1 = right.coerceIn(x0, w)
        val y0 = top.coerceIn(0, h)
        val y1 = bottom.coerceIn(y0, h)
        val out = DoubleArray((x1 - x0) * (y1 - y0))
        var k = 0
        for (y in y0 until y1) for (x in x0 until x1) out[k++] = luminance(x, y)
        return out
    }

    companion object {
        /** From ARGB rows fed in strips by [read] (first row, row count, into an array [w] wide). */
        fun of(w: Int, h: Int, pictureH: Int = h, read: (y: Int, rows: Int, into: IntArray) -> Unit): LumaBand {
            val q = ByteArray(w * h)
            val strip = IntArray(w * 32)
            var y = 0
            while (y < h) {
                val rows = min(32, h - y)
                read(y, rows, strip)
                for (i in 0 until w * rows) q[y * w + i] = (sqrt(relativeLuminance(strip[i])) * 255).roundToInt().toByte()
                y += rows
            }
            return LumaBand(w, h, q, pictureH)
        }
    }
}

/**
 * Where a rect on a [viewW]×[viewH] view lies on the [pictureW]×[pictureH] picture it shows centre-cropped (ImageView's
 * CENTER_CROP), in picture pixels: left, top, right, bottom.
 */
internal fun centerCropRect(viewW: Int, viewH: Int, pictureW: Int, pictureH: Int,
                            left: Int, top: Int, right: Int, bottom: Int): IntArray {
    val scale = max(viewW / pictureW.toDouble(), viewH / pictureH.toDouble())
    val dx = (viewW - pictureW * scale) / 2
    val dy = (viewH - pictureH * scale) / 2
    // The 1e-6 keeps exact pixel edges from slipping by one through rounding noise in the scale.
    return intArrayOf(floor((left - dx) / scale + 1e-6).toInt(), floor((top - dy) / scale + 1e-6).toInt(),
        ceil((right - dx) / scale - 1e-6).toInt(), ceil((bottom - dy) / scale - 1e-6).toInt())
}

/** The share of [l] against which text of luminance [text] falls below [need]; [shade] darkens the photo first. */
private fun shortfall(l: DoubleArray, text: Double, need: Double, shade: Double = 0.0) =
    if (l.isEmpty()) 0.0 else l.count { contrastRatio(it * (1 - shade), text) < need } / l.size.toDouble()

/**
 * The header's look over a wallpaper, from the luminance behind its large text (the clock) and behind each of its small
 * lines (the date, the Up next title and time): white text (judged with its shadow) or ink text where at most
 * [tolerance] of the photo behind each line falls short of the contrast it needs, the one with less falling short
 * (white on a tie); else the glass plate. Each line is judged on its own, so a date over a busy patch can't pass on
 * the strength of clear sky behind Up next.
 */
internal fun headerInk(large: DoubleArray, vararg small: DoubleArray, tolerance: Double = MAX_SHORTFALL,
                       shade: Double = SHADOW_DARKENING): HeaderInk {
    fun worst(text: Double, shade: Double) = max(shortfall(large, text, LARGE_TEXT_CONTRAST, shade),
        small.maxOfOrNull { shortfall(it, text, SMALL_TEXT_CONTRAST, shade) } ?: 0.0)
    val light = worst(1.0, shade)
    val dark = worst(relativeLuminance(INK_TEXT), 0.0)
    return when {
        min(light, dark) > tolerance -> HeaderInk.PLATE
        light <= dark -> HeaderInk.LIGHT
        else -> HeaderInk.DARK
    }
}

/** White (true) or ink for a small icon straight on the photo, whichever keeps 3:1 against more of what's behind it. */
internal fun iconIsLight(behind: DoubleArray): Boolean =
    shortfall(behind, 1.0, LARGE_TEXT_CONTRAST) <= shortfall(behind, relativeLuminance(INK_TEXT), LARGE_TEXT_CONTRAST)

/** The soft dark shadow under white header text. */
internal const val HEADER_SHADOW = 0x99000000.toInt()

/**
 * The header's colours: the clock and Up next title ([text]), the date and Up next time ([text2]), the shadow under
 * them (transparent for none), the rule between them, the glass plate's opacity (0-255) and the network icon.
 */
internal data class HeaderStyle(val text: Int, val text2: Int, val shadow: Int, val rule: Int, val plate: Int, val icon: Int) {
    /** Part way ([t], 0-1) to [to], for the change from one photo to the next. */
    fun mix(to: HeaderStyle, t: Float) = HeaderStyle(lerpArgb(text, to.text, t), lerpArgb(text2, to.text2, t),
        lerpArgb(shadow, to.shadow, t), lerpArgb(rule, to.rule, t), (plate + (to.plate - plate) * t).roundToInt(),
        lerpArgb(icon, to.icon, t))
}

/**
 * The colours for [ink]: white with a shadow, ink, or the theme's own [themeText] and [themeText2] on the glass plate.
 * With no photo behind (null: a plain colour, or the picture still loading) the theme's colours suit its ground colour.
 * The network icon is white or ink by [iconLight].
 */
internal fun headerStyle(ink: HeaderInk?, iconLight: Boolean, themeText: Int, themeText2: Int): HeaderStyle {
    val themeRule = (themeText and 0x00FFFFFF) or 0x4D000000
    val icon = if (iconLight) 0xE6FFFFFF.toInt() else 0xB314171B.toInt()
    return when (ink) {
        HeaderInk.LIGHT -> HeaderStyle(0xFFFFFFFF.toInt(), 0xE6FFFFFF.toInt(), HEADER_SHADOW, 0x59FFFFFF, 0, icon)
        HeaderInk.DARK -> HeaderStyle(INK_TEXT, 0xD914171B.toInt(), 0, 0x4D14171B, 0, icon)
        HeaderInk.PLATE -> HeaderStyle(themeText, themeText2, 0, themeRule, 255, icon)
        null -> HeaderStyle(themeText, themeText2, 0, themeRule, 0, themeText2)
    }
}
