package dev.plume

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.drawable.Drawable
import java.util.Calendar
import java.util.TimeZone
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** A weather location: the city's name and where it is. */
internal data class Place(val city: String, val lat: Float, val lon: Float)

/**
 * The weather location set in telepath.properties (weather.*), or null: found from the TV's internet address. It's
 * also where sunrise and sunset are worked out for the automatic theme and the mood colours.
 */
internal val DEFAULT_PLACE: Place? = BuildConfig.WEATHER_CITY.takeIf { it.isNotEmpty() }
    ?.let { Place(it, BuildConfig.WEATHER_LAT, BuildConfig.WEATHER_LON) }

/**
 * Where sunrise and sunset are worked out until the TV's place is known (the first moments after installing, or
 * offline): [place], else the equator in the middle of the TV's time zone [zone] (15° of longitude per hour from UTC),
 * where the sun rises near 06:00 and sets near 18:00 all year.
 */
internal fun fallbackLat(place: Place? = DEFAULT_PLACE): Float = place?.lat ?: 0f

internal fun fallbackLon(place: Place? = DEFAULT_PLACE, zone: TimeZone = TimeZone.getDefault()): Float =
    place?.lon ?: (zone.rawOffset / 3_600_000f * 15f).coerceIn(-180f, 180f)

/** The place ipwho.is found for the TV's internet address, from its answer's fields, or null if it found none usable. */
internal fun ipPlace(success: Boolean, city: String?, lat: Double, lon: Double): Place? {
    val name = city?.trim().orEmpty()
    if (!success || name.isEmpty() || lat.isNaN() || lon.isNaN() || lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
    return Place(name, lat.toFloat(), lon.toFloat())
}

internal enum class Sky { CLEAR, MOSTLY_CLEAR, PARTLY_CLOUDY, CLOUDY, FOG, DRIZZLE, RAIN, SNOW, THUNDER }

/** WMO weather codes, as Open-Meteo reports them. */
internal fun skyFor(code: Int): Sky = when (code) {
    0 -> Sky.CLEAR
    1 -> Sky.MOSTLY_CLEAR
    2 -> Sky.PARTLY_CLOUDY
    3 -> Sky.CLOUDY
    45, 48 -> Sky.FOG
    in 51..57 -> Sky.DRIZZLE
    in 61..67, in 80..82 -> Sky.RAIN
    in 71..77, 85, 86 -> Sky.SNOW
    in 95..99 -> Sky.THUNDER
    else -> Sky.CLOUDY
}

internal fun displayTemp(celsius: Double, unit: String): Int =
    (if (unit == "f") celsius * 9 / 5 + 32 else celsius).roundToInt()

/**
 * Indexes of the next [count] hourly slots strictly after [now]. Open-Meteo gives local times as
 * "yyyy-MM-ddTHH:mm", which sort as text in time order, so no parsing or time zone is needed.
 */
internal fun upcomingHours(times: List<String>, now: String, count: Int): List<Int> =
    times.indices.filter { times[it] > now }.take(count)

/** "2026-10-22T06:07" as a 12-hour "6:07 AM", or 24-hour "06:07". */
internal fun clockText(local: String, use24: Boolean): String {
    val time = local.substringAfter('T', "").take(5)
    val h = time.substringBefore(':').toIntOrNull() ?: return ""
    val m = time.substringAfter(':', "00")
    if (use24) return "%02d:%s".format(h, m)
    val suffix = if (h < 12) "AM" else "PM"
    return "${if (h % 12 == 0) 12 else h % 12}:$m $suffix"
}

/** "2026-10-22T06:00" as "6 AM" (12-hour) or "06:00". */
internal fun hourText(local: String, use24: Boolean): String {
    val h = local.substringAfter('T', "").take(2).toIntOrNull() ?: return ""
    if (use24) return "%02d:00".format(h)
    return "${if (h % 12 == 0) 12 else h % 12} ${if (h < 12) "AM" else "PM"}"
}

/**
 * Sunrise and sunset (epoch millis) for the calendar day containing [atMillis] in [zone], from the standard
 * sunrise equation (about a minute of accuracy, no network). Null where the sun doesn't rise or set that day.
 */
internal fun sunTimes(atMillis: Long, lat: Double, lon: Double, zone: TimeZone): Pair<Long, Long>? {
    val local = Calendar.getInstance(zone).apply { timeInMillis = atMillis }
    val utcDate = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
        clear()
        set(local.get(Calendar.YEAR), local.get(Calendar.MONTH), local.get(Calendar.DAY_OF_MONTH))
    }
    val n = utcDate.timeInMillis / 86_400_000L - 10_957L // whole days since 2000-01-01
    val jStar = n - lon / 360.0
    val m = Math.toRadians((357.5291 + 0.98560028 * jStar) % 360.0)
    val c = 1.9148 * sin(m) + 0.02 * sin(2 * m) + 0.0003 * sin(3 * m)
    val lambda = Math.toRadians((Math.toDegrees(m) + c + 180.0 + 102.9372) % 360.0)
    val transit = jStar + 0.0053 * sin(m) - 0.0069 * sin(2 * lambda)
    val declination = asin(sin(lambda) * sin(Math.toRadians(23.4397)))
    val phi = Math.toRadians(lat)
    val cosOmega = (sin(Math.toRadians(-0.833)) - sin(phi) * sin(declination)) / (cos(phi) * cos(declination))
    if (cosOmega < -1.0 || cosOmega > 1.0) return null
    val omega = acos(cosOmega) / (2 * PI)
    fun millis(j: Double) = ((10_957.5 + j) * 86_400_000.0).toLong()
    return millis(transit - omega) to millis(transit + omega)
}

/** Automatic theme: dark between sunset and sunrise at the weather location; 19:00 to 07:00 if the sun gives no answer. */
internal fun autoDark(now: Long, lat: Double, lon: Double, zone: TimeZone): Boolean {
    val sun = sunTimes(now, lat, lon, zone) ?: return isNightHour(Calendar.getInstance(zone).apply { timeInMillis = now }.get(Calendar.HOUR_OF_DAY))
    return now < sun.first || now >= sun.second
}

/**
 * Monochrome weather glyphs drawn on a 24-unit grid, so they stay crisp at any size and take the
 * theme's text colour. Shapes are filled; a cloud in front of the sun or moon cuts a thin gap around itself.
 */
internal class WeatherIcon(private val kind: Kind, color: Int) : Drawable() {
    enum class Kind { SUN, MOON, SUN_CLOUD, MOON_CLOUD, CLOUD, FOG, DRIZZLE, RAIN, SNOW, THUNDER, SUNRISE, SUNSET }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; style = Paint.Style.FILL }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = 1.7f
    }
    private val cut = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.6f
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        val s = minOf(b.width(), b.height()) / 24f
        if (s <= 0f) return
        val layer = canvas.saveLayer(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat(), null)
        canvas.translate(b.left + (b.width() - 24 * s) / 2, b.top + (b.height() - 24 * s) / 2)
        canvas.scale(s, s)
        when (kind) {
            Kind.SUN -> sun(canvas, 12f, 12f, 4.4f, 7f, 9.6f)
            Kind.MOON -> {
                moon(canvas, 11f, 13f, 7.6f)
                star(canvas, 18.6f, 5.4f, 2.2f)
            }
            Kind.SUN_CLOUD -> {
                sun(canvas, 9f, 9f, 3.4f, 5.3f, 7.1f)
                frontCloud(canvas, 2.5f)
            }
            Kind.MOON_CLOUD -> {
                moon(canvas, 9f, 9.5f, 5.6f)
                frontCloud(canvas, 2.5f)
            }
            Kind.CLOUD -> canvas.drawPath(cloud(0f, 1f), fill)
            Kind.FOG -> {
                canvas.drawPath(cloud(0f, -1.5f), fill)
                canvas.drawLine(4f, 19.5f, 20f, 19.5f, line)
                canvas.drawLine(6.5f, 22.5f, 17.5f, 22.5f, line)
            }
            Kind.DRIZZLE -> {
                canvas.drawPath(cloud(0f, -2.5f), fill)
                for (x in floatArrayOf(8f, 12f, 16f)) canvas.drawCircle(x, 20.5f, 1.05f, fill)
            }
            Kind.RAIN -> {
                canvas.drawPath(cloud(0f, -2.5f), fill)
                for (x in floatArrayOf(8.5f, 12.5f, 16.5f)) canvas.drawLine(x, 18.5f, x - 1.4f, 22.5f, line)
            }
            Kind.SNOW -> {
                canvas.drawPath(cloud(0f, -2.5f), fill)
                for (x in floatArrayOf(8f, 12f, 16f)) {
                    canvas.drawLine(x - 1.3f, 20.5f, x + 1.3f, 20.5f, line)
                    canvas.drawLine(x, 19.2f, x, 21.8f, line)
                }
            }
            Kind.THUNDER -> {
                canvas.drawPath(cloud(0f, -2.5f), fill)
                canvas.drawPath(Path().apply {
                    moveTo(13f, 16.5f); lineTo(9.6f, 21f); lineTo(12f, 21f); lineTo(10.8f, 24f)
                    lineTo(14.6f, 19.2f); lineTo(12.2f, 19.2f); close()
                }, fill)
            }
            Kind.SUNRISE, Kind.SUNSET -> {
                canvas.drawArc(RectF(7.5f, 12.5f, 16.5f, 21.5f), 180f, 180f, true, fill)
                canvas.drawLine(3f, 18.5f, 21f, 18.5f, line)
                canvas.drawLine(4.5f, 14f, 6f, 15f, line)
                canvas.drawLine(19.5f, 14f, 18f, 15f, line)
                val up = kind == Kind.SUNRISE
                val tip = if (up) 3f else 10f
                val tail = if (up) 10f else 3f
                canvas.drawLine(12f, tail, 12f, tip, line)
                canvas.drawLine(9.6f, if (up) 5.4f else 7.6f, 12f, tip, line)
                canvas.drawLine(14.4f, if (up) 5.4f else 7.6f, 12f, tip, line)
            }
        }
        canvas.restoreToCount(layer)
    }

    private fun sun(c: Canvas, x: Float, y: Float, r: Float, ray0: Float, ray1: Float) {
        c.drawCircle(x, y, r, fill)
        for (i in 0 until 8) {
            val a = i * PI / 4
            c.drawLine(x + (ray0 * cos(a)).toFloat(), y + (ray0 * sin(a)).toFloat(),
                x + (ray1 * cos(a)).toFloat(), y + (ray1 * sin(a)).toFloat(), line)
        }
    }

    private fun moon(c: Canvas, x: Float, y: Float, r: Float) {
        val disc = Path().apply { addCircle(x, y, r, Path.Direction.CW) }
        val bite = Path().apply { addCircle(x + r * 0.62f, y - r * 0.48f, r * 0.82f, Path.Direction.CW) }
        disc.op(bite, Path.Op.DIFFERENCE)
        c.drawPath(disc, fill)
    }

    private fun star(c: Canvas, x: Float, y: Float, r: Float) {
        c.drawPath(Path().apply {
            moveTo(x, y - r); quadTo(x, y, x + r, y); quadTo(x, y, x, y + r); quadTo(x, y, x - r, y); quadTo(x, y, x, y - r); close()
        }, fill)
    }

    /** Cloud body: three overlapping discs on a rounded base. */
    private fun cloud(dx: Float, dy: Float): Path {
        val p = Path().apply { addRoundRect(RectF(3.5f + dx, 11.5f + dy, 20.5f + dx, 18f + dy), 3.3f, 3.3f, Path.Direction.CW) }
        p.op(Path().apply { addCircle(8.6f + dx, 12.2f + dy, 3.9f, Path.Direction.CW) }, Path.Op.UNION)
        p.op(Path().apply { addCircle(13.6f + dx, 10.4f + dy, 5f, Path.Direction.CW) }, Path.Op.UNION)
        p.op(Path().apply { addCircle(17.6f + dx, 13.4f + dy, 3.2f, Path.Direction.CW) }, Path.Op.UNION)
        return p
    }

    private fun frontCloud(c: Canvas, dy: Float) {
        val p = cloud(1f, dy)
        c.drawPath(p, cut)
        c.drawPath(p, fill)
    }

    override fun setAlpha(alpha: Int) {
        fill.alpha = alpha
        line.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(filter: ColorFilter?) {
        fill.colorFilter = filter
        line.colorFilter = filter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT

    companion object {
        fun kindFor(sky: Sky, day: Boolean) = when (sky) {
            Sky.CLEAR -> if (day) Kind.SUN else Kind.MOON
            Sky.MOSTLY_CLEAR, Sky.PARTLY_CLOUDY -> if (day) Kind.SUN_CLOUD else Kind.MOON_CLOUD
            Sky.CLOUDY -> Kind.CLOUD
            Sky.FOG -> Kind.FOG
            Sky.DRIZZLE -> Kind.DRIZZLE
            Sky.RAIN -> Kind.RAIN
            Sky.SNOW -> Kind.SNOW
            Sky.THUNDER -> Kind.THUNDER
        }
    }
}
