package dev.plume

import java.util.Calendar
import java.util.Random
import java.util.TimeZone
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Where the day is at the weather location. */
internal enum class Phase { DAWN, DAY, GOLDEN, SUNSET, DUSK, NIGHT }

/** The weather, as far as the colours care. */
internal enum class Air { CLEAR, CLOUDY, RAIN, STORM, FOG, SNOW }

internal data class Mood(val phase: Phase, val air: Air)

/** From today's [sunrise] and [sunset]: dawn ±40 min around sunrise, golden hour, sunset, dusk, then night. */
internal fun phaseAt(now: Long, sunrise: Long, sunset: Long): Phase = when {
    now >= sunrise - 40 * MINUTE_MS && now < sunrise + 40 * MINUTE_MS -> Phase.DAWN
    now >= sunset - 70 * MINUTE_MS && now < sunset - 10 * MINUTE_MS -> Phase.GOLDEN
    now >= sunset - 10 * MINUTE_MS && now < sunset + 30 * MINUTE_MS -> Phase.SUNSET
    now >= sunset + 30 * MINUTE_MS && now < sunset + 80 * MINUTE_MS -> Phase.DUSK
    now >= sunrise && now < sunset -> Phase.DAY
    else -> Phase.NIGHT
}

/** By the clock, where the sun gives no answer. */
internal fun phaseAtHour(hour: Int): Phase = when (hour) {
    6 -> Phase.DAWN
    in 7..16 -> Phase.DAY
    17 -> Phase.GOLDEN
    18 -> Phase.SUNSET
    19 -> Phase.DUSK
    else -> Phase.NIGHT
}

internal fun airFor(sky: Sky?): Air = when (sky) {
    null, Sky.CLEAR, Sky.MOSTLY_CLEAR, Sky.PARTLY_CLOUDY -> Air.CLEAR
    Sky.CLOUDY -> Air.CLOUDY
    Sky.FOG -> Air.FOG
    Sky.DRIZZLE, Sky.RAIN -> Air.RAIN
    Sky.THUNDER -> Air.STORM
    Sky.SNOW -> Air.SNOW
}

internal fun moodAt(now: Long, lat: Double, lon: Double, zone: TimeZone, sky: Sky?): Mood {
    val sun = sunTimes(now, lat, lon, zone)
    val phase = if (sun != null) phaseAt(now, sun.first, sun.second)
    else phaseAtHour(Calendar.getInstance(zone).apply { timeInMillis = now }.get(Calendar.HOUR_OF_DAY))
    return Mood(phase, airFor(sky))
}

/**
 * How a mood paints the wallpaper: a wash from [top] through [mid] to [bottom] (ARGB, alpha = strength) over the
 * picture darkened by [dim] (0..1), plus [drops] raindrops on the glass.
 */
internal class MoodLook(val top: Int, val mid: Int, val bottom: Int, val dim: Float, val drops: Int)

private fun argb(a: Int, rgb: Int) = (a.coerceIn(0, 255) shl 24) or (rgb and 0xFFFFFF)

private fun mixRgb(c: Int, to: Int, t: Float): Int {
    fun ch(s: Int) = (((c shr s) and 0xFF) * (1 - t) + ((to shr s) and 0xFF) * t).toInt().coerceIn(0, 255) shl s
    return (c and 0xFF000000.toInt()) or ch(16) or ch(8) or ch(0)
}

/** Weather turns the phase's colours (mood.* in telepath.properties) toward its own and thickens them; rain and storms add drops. */
internal fun lookFor(mood: Mood): MoodLook {
    val base = when (mood.phase) {
        Phase.DAWN -> BuildConfig.MOOD_DAWN
        Phase.DAY -> BuildConfig.MOOD_DAY
        Phase.GOLDEN -> BuildConfig.MOOD_GOLDEN
        Phase.SUNSET -> BuildConfig.MOOD_SUNSET
        Phase.DUSK -> BuildConfig.MOOD_DUSK
        Phase.NIGHT -> BuildConfig.MOOD_NIGHT
    }
    val dim = when (mood.phase) {
        Phase.NIGHT -> 0.12f
        Phase.DUSK -> 0.08f
        Phase.SUNSET -> 0.03f
        else -> 0f
    }
    val (to, t, thicker, darker, drops) = when (mood.air) {
        Air.CLEAR -> Weathering(0, 0f, 0, 0f, 0)
        Air.CLOUDY -> Weathering(0x8C939E, 0.4f, 0x0C, 0.04f, 0)
        Air.RAIN -> Weathering(0x50657D, 0.55f, 0x16, 0.08f, 18)
        Air.STORM -> Weathering(0x2E3644, 0.65f, 0x22, 0.14f, 26)
        Air.FOG -> Weathering(0xD0D4DA, 0.6f, 0x24, 0f, 0)
        Air.SNOW -> Weathering(0xE2ECF7, 0.45f, 0x10, 0f, 0)
    }
    val c = base.map { col ->
        val a = (col ushr 24) + thicker
        argb(min(a, 0x66), mixRgb(col, to, t))
    }
    return MoodLook(c[0], c[1], c[2], min(0.25f, dim + darker), drops)
}

private data class Weathering(val to: Int, val t: Float, val thicker: Int, val darker: Float, val drops: Int)

private fun smooth(t: Float): Float {
    val x = t.coerceIn(0f, 1f)
    return x * x * (3 - 2 * x)
}

internal fun lerpArgb(a: Int, b: Int, t: Float): Int {
    fun ch(s: Int) = (((a ushr s) and 0xFF) * (1 - t) + ((b ushr s) and 0xFF) * t + 0.5f).toInt().coerceIn(0, 255) shl s
    return ch(24) or ch(16) or ch(8) or ch(0)
}

/** The wash at height [v] (0 top, 1 bottom). */
internal fun washAt(look: MoodLook, v: Float): Int =
    if (v < 0.5f) lerpArgb(look.top, look.mid, smooth(v / 0.5f)) else lerpArgb(look.mid, look.bottom, smooth((v - 0.5f) / 0.5f))

/** A raindrop on the glass: centre and radius in pixels. */
internal class Drop(val x: Float, val y: Float, val r: Float)

/** [count] drops for a [w]×[h] picture, mostly over its upper half where the wallpaper shows; same [seed], same drops. */
internal fun dropsFor(count: Int, w: Int, h: Int, seed: Long): List<Drop> {
    val rnd = Random(seed)
    val scale = w / 1920f
    return List(count) {
        val r = (5f + rnd.nextFloat() * rnd.nextFloat() * 9f) * scale
        val upper = rnd.nextFloat() < 0.75f
        val y = h * (if (upper) 0.05f + rnd.nextFloat() * 0.5f else 0.05f + rnd.nextFloat() * 0.9f)
        Drop(w * (0.03f + rnd.nextFloat() * 0.94f), y, max(1.5f, r))
    }
}

private fun over(c: Int, rgb: Int, a: Float): Int {
    if (a <= 0f) return c
    fun ch(s: Int) = (((c shr s) and 0xFF) * (1 - a) + ((rgb shr s) and 0xFF) * a + 0.5f).toInt().coerceIn(0, 255) shl s
    return (c and 0xFF000000.toInt()) or ch(16) or ch(8) or ch(0)
}

/**
 * Paints rows [y0] until [y0] + [rows] of a picture [h] tall, [w] wide, held in [px] (that strip only, ARGB): the
 * darkening, the wash, then the drops. Each drop is water on glass lit from above: a slightly brighter lens, a rim
 * that darkens toward its lower edge, and a small highlight at its top left.
 */
internal fun paintMood(px: IntArray, w: Int, y0: Int, rows: Int, h: Int, look: MoodLook, drops: List<Drop>) {
    val keep = 1f - look.dim
    for (row in 0 until rows) {
        val wash = washAt(look, (y0 + row) / max(1f, (h - 1).toFloat()))
        val a = (wash ushr 24) / 255f
        val wr = (wash shr 16) and 0xFF
        val wg = (wash shr 8) and 0xFF
        val wb = wash and 0xFF
        val base = row * w
        for (x in 0 until w) {
            val c = px[base + x]
            val r = ((c shr 16) and 0xFF) * keep
            val g = ((c shr 8) and 0xFF) * keep
            val b = (c and 0xFF) * keep
            px[base + x] = (c and 0xFF000000.toInt()) or
                ((r + (wr - r) * a + 0.5f).toInt().coerceIn(0, 255) shl 16) or
                ((g + (wg - g) * a + 0.5f).toInt().coerceIn(0, 255) shl 8) or
                (b + (wb - b) * a + 0.5f).toInt().coerceIn(0, 255)
        }
    }
    for (d in drops) {
        val top = max(y0, (d.y - d.r - 1).toInt())
        val bottom = min(y0 + rows - 1, (d.y + d.r + 1).toInt())
        if (top > bottom) continue
        val left = max(0, (d.x - d.r - 1).toInt())
        val right = min(w - 1, (d.x + d.r + 1).toInt())
        val hx = d.x - 0.32f * d.r
        val hy = d.y - 0.36f * d.r
        val hr = max(0.8f, 0.3f * d.r)
        for (y in top..bottom) for (x in left..right) {
            val dx = x + 0.5f - d.x
            val dy = y + 0.5f - d.y
            val dist = sqrt(dx * dx + dy * dy)
            val cover = (d.r + 0.5f - dist).coerceIn(0f, 1f)
            if (cover <= 0f) continue
            val t = dist / d.r
            val i = (y - y0) * w + x
            val below = (0.55f + 0.45f * (dy / d.r)).coerceIn(0.1f, 1f) // the lower edge holds the shadow
            var c = over(px[i], 0xFFFFFF, 0.10f * cover * (1 - t * t).coerceAtLeast(0f))
            c = over(c, 0x000000, 0.34f * cover * below * smooth((t - 0.55f) / 0.45f))
            val hd = sqrt((x + 0.5f - hx) * (x + 0.5f - hx) + (y + 0.5f - hy) * (y + 0.5f - hy)) / hr
            if (hd < 1f) c = over(c, 0xFFFFFF, 0.7f * (1 - hd * hd) * (1 - hd * hd))
            px[i] = c
        }
    }
}
