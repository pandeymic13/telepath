package dev.plume

import android.app.Activity
import android.content.ContentUris
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.service.dreams.DreamService
import android.text.format.DateFormat
import android.view.KeyEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors
import kotlin.math.max

/**
 * Public-domain museum scans, not bundled: downloaded on demand from the repo's `gallery-1` release (the
 * files live in screensaver/) into [paintingsDir], so the APK stays under 1 MB for people who never use it. only paintings whose canvas is (within ~2-4%) 16:9, cropped
 * to exactly 1920×1080 so they fill the screen with nothing cut off and no bars. Bright ones for light mode,
 * dusk and storm ones for dark (wide night paintings are rare).
 */
private class Painting(val file: String, val title: String, val artist: String)

/** One picture in the show: a painting with its caption, or a photo (no caption) that slowly pans and zooms. */
private class Picture(val title: String, val artist: String, val moving: Boolean, val load: () -> Bitmap?)

private val LIGHT = listOf(
    Painting("andes", "The Heart of the Andes", "Frederic Edwin Church, 1859"),
    Painting("icebergs", "The Icebergs", "Frederic Edwin Church, 1861"),
    Painting("rye", "Rye", "Ivan Shishkin, 1878"),
    Painting("chimborazo", "Chimborazo", "Frederic Edwin Church, 1864"),
    Painting("hudson", "Autumn on the Hudson River", "Jasper Francis Cropsey, 1860"),
)
private val DARK = listOf(
    Painting("cotopaxi", "Cotopaxi", "Frederic Edwin Church, 1862"),
    Painting("thunderstorm", "Thunder Storm on Narragansett Bay", "Martin Johnson Heade, 1868"),
)

internal const val PAINTINGS_URL = "https://github.com/aradhyas8/Plume_Launcher/releases/download/gallery-1/"
internal const val PAINTINGS_MB = "4.7 MB"
internal val PAINTING_FILES = (LIGHT + DARK).map { "${it.file}.jpg" }
internal fun paintingsDir(c: Context) = File(c.filesDir, "paintings")
internal fun paintingsReady(c: Context) = PAINTING_FILES.all { File(paintingsDir(c), it).exists() }

/** Android/data/<app>/files/slideshow: the PC's Wallpapers folder, copied there by Sync-Wallpapers.ps1. */
internal const val SLIDESHOW_DIR = "slideshow"
internal val PHOTO_TYPES = setOf("jpg", "jpeg", "png", "webp")

/** The slideshow photos, by name. Shared by the wallpaper and the screensaver. */
internal fun slideshowFiles(c: Context): List<File> = c.getExternalFilesDir(SLIDESHOW_DIR)
    ?.listFiles { f -> f.isFile && f.extension.lowercase() in PHOTO_TYPES }?.sortedBy { it.name } ?: emptyList()

/**
 * What the screensaver shows: "slideshow" (the Wallpapers photos), "paintings", or "folder" (a photo folder on the
 * TV). Before there was a choice, a chosen folder meant photos and no folder meant paintings.
 */
internal fun screensaverSource(source: String?, folder: String?) = source ?: if (folder != null) "folder" else "paintings"

internal fun screensaverSource(prefs: SharedPreferences) =
    screensaverSource(prefs.getString("ss_source", null), prefs.getString("ss_folder", null))

/** How long each photo shows, in seconds (Home settings > Screensaver > Change photo every). Paintings hold 2 minutes. */
internal val PHOTO_SECONDS = listOf(15, 30, 60, 120, 300)
internal const val DEFAULT_PHOTO_SECONDS = 30

/** How far photos zoom while they show, and where they drift: start x, y and end x, y, as 0..1 of the spare room. */
private const val KB_ZOOM = 0.06f
private val KB_PATHS = arrayOf(
    floatArrayOf(0.5f, 0.5f, 0.3f, 0.35f),
    floatArrayOf(0.25f, 0.6f, 0.7f, 0.4f),
    floatArrayOf(0.75f, 0.35f, 0.45f, 0.6f),
    floatArrayOf(0.4f, 0.3f, 0.6f, 0.65f),
)

/**
 * The slow pan and zoom of photo number [slide] at [t] (0..1 of its time on screen), for a [bw]×[bh] picture on a
 * [w]×[h] screen: (scale, left, top). The picture always covers the whole screen; even-numbered photos zoom in,
 * odd ones zoom out, and each drifts along its own path.
 */
internal fun panZoom(t: Float, slide: Int, bw: Int, bh: Int, w: Int, h: Int): FloatArray {
    val p = t.coerceIn(0f, 1f)
    val cover = max(w.toFloat() / bw, h.toFloat() / bh)
    val zoom = 1f + KB_ZOOM * (if (slide % 2 == 0) p else 1f - p)
    val k = cover * zoom
    val path = KB_PATHS[Math.floorMod(slide, KB_PATHS.size)]
    val ax = path[0] + (path[2] - path[0]) * p
    val ay = path[1] + (path[3] - path[1]) * p
    return floatArrayOf(k, (w - bw * k) * ax, (h - bh * k) * ay)
}

/** Where the clock sits, in 1080p units in from the bottom-right corner: it moves with every picture, against burn-in. */
private val CLOCK_NUDGE = arrayOf(intArrayOf(0, 0), intArrayOf(26, 12), intArrayOf(12, 28), intArrayOf(34, 4))

/** How far the clock's shade reaches from the bottom-right corner, as a share of the screen's width. */
internal const val CLOCK_SHADE_REACH = 0.42f

/** The shade's darkness (alpha) out from the corner: it eases off and reaches nothing at [CLOCK_SHADE_REACH]. */
internal val CLOCK_SHADE_ALPHAS = intArrayOf(0x73, 0x5A, 0x33, 0x17, 0x07, 0)
internal val CLOCK_SHADE_STOPS = floatArrayOf(0f, 0.3f, 0.55f, 0.74f, 0.88f, 1f)

/**
 * Where to paint the clock's shade on a [w]×[h] screen (left, top, right, bottom): the square around all of it, so
 * it fades out by itself everywhere. A smaller rectangle cut it off in a line across the photo.
 */
internal fun clockShadeBounds(w: Float, h: Float): FloatArray {
    val reach = w * CLOCK_SHADE_REACH
    return floatArrayOf(maxOf(0f, w - reach), maxOf(0f, h - reach), w, h)
}

/**
 * telepath's screensaver: the Wallpapers slideshow (default), the paintings or a photo folder, as chosen in
 * Home settings > Screensaver, with the time and weather in a corner. Left/Right step through the pictures; any
 * other key ends it and uncovers whatever was on screen (Home, or a paused film), as Google TV's does.
 */
class GalleryDream : DreamService() {
    private var show: GalleryShow? = null

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = true // we handle keys ourselves (see dispatchKeyEvent)
        isFullscreen = true
        show = GalleryShow(this).also { setContentView(it.view) }
    }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        if (e.keyCode == KeyEvent.KEYCODE_DPAD_LEFT || e.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
            if (e.action == KeyEvent.ACTION_DOWN) show?.step(if (e.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) 1 else -1)
            return true
        }
        if (e.action == KeyEvent.ACTION_UP) {
            if (Build.VERSION.SDK_INT >= 29) wakeUp() else finish()
        }
        return true
    }

    override fun onDetachedFromWindow() {
        show?.release()
        super.onDetachedFromWindow()
    }
}

/**
 * Home settings > Screensaver > Test screensaver: the same show in a full-screen window, because this TV has no way
 * to start a screensaver on demand. Left/Right step through the pictures; any other key closes it.
 */
class ScreensaverPreview : Activity() {
    private var show: GalleryShow? = null

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        show = GalleryShow(this).also { setContentView(it.view) }
    }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        if (e.keyCode == KeyEvent.KEYCODE_DPAD_LEFT || e.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
            if (e.action == KeyEvent.ACTION_DOWN) show?.step(if (e.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) 1 else -1)
            return true
        }
        if (e.action == KeyEvent.ACTION_UP) finish()
        return true
    }

    override fun onDestroy() {
        show?.release()
        super.onDestroy()
    }
}

/**
 * Paints edge to edge on a SurfaceView at the UI's 1080p. Not 4K: this TV's display hardware shows a 4K app
 * surface as black (screenshots still show it, since they composite on the GPU).
 * Each picture fills the screen, holds, then cross-fades to the next. Paintings stay still and show their title for a
 * few seconds; photos drift and zoom slowly (a 1080p bitmap drawn through a matrix: trivial for the GPU).
 * About 8 MB per decoded picture; one in memory, two during a cross-fade, plus the surface's buffers.
 * Outgoing pictures are recycled as soon as they're off screen, and everything when the show ends.
 */
internal class GalleryShow(private val context: Context) : SurfaceHolder.Callback {
    private val prefs = context.getSharedPreferences("home", Context.MODE_PRIVATE)
    private val items = pictures()
    private val holdMs = if (items.first().moving) {
        prefs.getInt("ss_every", DEFAULT_PHOTO_SECONDS).coerceIn(5, 3600) * 1000L
    } else HOLD_MS
    private val showClock = prefs.getBoolean("ss_clock", true)
    private val io = Executors.newSingleThreadExecutor()
    val view = SurfaceView(context).also { it.holder.addCallback(this) }

    /**
     * The chosen pictures, else the next best: the Wallpapers slideshow, the paintings (once downloaded), a photo
     * folder; the two bundled wallpapers if there's nothing else yet, so the show is never empty. Photos are shuffled.
     */
    private fun pictures(): List<Picture> {
        val order: List<() -> List<Picture>> = when (screensaverSource(prefs)) {
            "folder" -> listOf({ folder() }, { slideshow() }, { paintingsList() })
            "paintings" -> listOf({ paintingsList() }, { slideshow() }, { folder() })
            else -> listOf({ slideshow() }, { paintingsList() }, { folder() })
        }
        for (source in order) source().takeIf { it.isNotEmpty() }?.let { return it }
        return listOf(R.drawable.wallpaper, R.drawable.wallpaper_dark).map { id ->
            Picture("", "", true) { BitmapFactory.decodeResource(context.resources, id) }
        }
    }

    private fun slideshow() = slideshowFiles(context).shuffled().map { f ->
        Picture("", "", true) { fitWallpaper { f.inputStream() } }
    }

    private fun folder(): List<Picture> {
        val chosen = prefs.getString("ss_folder", null) ?: return emptyList()
        val ids = try {
            folderPhotos(context.contentResolver, chosen)
        } catch (e: Exception) { // photo access taken away
            emptyList()
        }
        return ids.shuffled().map { id ->
            val uri = ContentUris.withAppendedId(PHOTOS, id)
            Picture("", "", true) { fitWallpaper { context.contentResolver.openInputStream(uri) ?: throw IOException("gone: $uri") } }
        }
    }

    private fun paintingsList(): List<Picture> {
        if (!paintingsReady(context)) return emptyList()
        return (if (prefersDark(prefs)) DARK else LIGHT).map { p ->
            Picture(p.title, p.artist, false) { File(paintingsDir(context), "${p.file}.jpg").inputStream().use { BitmapFactory.decodeStream(it) } }
        }
    }

    private class Slide(val bmp: Bitmap, val painting: Picture, val number: Int, val start: Long, val fadeMs: Long)

    // Shared between the loader and the render thread; swaps and draws hold [lock], so a bitmap is
    // never recycled mid-draw.
    private val lock = Any()
    @Volatile private var current: Slide? = null
    @Volatile private var previous: Slide? = null
    @Volatile private var running = false
    private var index = if (items.first().moving) 0 else items.indices.random() // photos come shuffled
    private var shown = 0
    @Volatile private var advanceAt = 0L
    private var render: Thread? = null

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val matrix = Matrix()
    private val title = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; setShadowLayer(12f, 0f, 2f, 0x99000000.toInt()) }
    private val artist = Paint(title)

    override fun surfaceCreated(holder: SurfaceHolder) {
        running = true
        load(index)
        render = Thread(::loop, "gallery").apply { start() }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        running = false
        render?.join(500)
    }

    fun step(by: Int) {
        index = (index + by + items.size) % items.size
        load(index)
    }

    fun release() {
        io.shutdownNow()
        synchronized(lock) {
            running = false
            current?.bmp?.recycle()
            previous?.bmp?.recycle()
            current = null
            previous = null
        }
    }

    private fun load(i: Int) {
        val p = items[i]
        io.execute {
            val bmp = try {
                p.load()
            } catch (e: Exception) {
                null
            } ?: return@execute
            synchronized(lock) {
                if (!running) { // dream ended while decoding
                    bmp.recycle()
                    return@execute
                }
                previous?.bmp?.recycle() // stepped again mid-fade: that one won't be drawn any more
                // The first picture fades up from black: keep that short or the screen just looks off.
                val fade = if (current == null) FIRST_FADE_MS else FADE_MS
                previous = current
                current = Slide(bmp, p, shown++, System.currentTimeMillis(), fade)
                advanceAt = System.currentTimeMillis() + holdMs
            }
        }
    }

    private fun loop() {
        while (running) {
            val now = System.currentTimeMillis()
            if (advanceAt in 1..now) {
                advanceAt = 0
                step(1)
            }
            val holder = view.holder
            val canvas = try {
                holder.lockHardwareCanvas()
            } catch (e: Exception) {
                null
            } ?: return
            try {
                draw(canvas, now)
            } finally {
                holder.unlockCanvasAndPost(canvas)
            }
            Thread.sleep(FRAME_MS) // 30 fps is plenty for motion this slow, and spares the GPU
        }
    }

    private fun draw(c: Canvas, now: Long) = synchronized(lock) {
        c.drawColor(Color.BLACK)
        if (!running) return
        val cur = current ?: return
        val fade = ((now - cur.start).toFloat() / cur.fadeMs).coerceIn(0f, 1f)
        previous?.let {
            if (fade < 1f) drawSlide(c, it, ((1f - fade) * 255).toInt(), now) else {
                it.bmp.recycle() // faded out: free it now rather than whenever GC gets to it
                previous = null
            }
        }
        drawSlide(c, cur, (fade * 255).toInt(), now)

        // Title for the first seconds of each painting, fading in and out.
        val t = now - cur.start
        val a = when {
            t < FADE_MS -> t.toFloat() / FADE_MS
            t < CAPTION_MS -> 1f
            t < CAPTION_MS + FADE_MS -> 1f - (t - CAPTION_MS).toFloat() / FADE_MS
            else -> 0f
        }
        if (a > 0f && cur.painting.title.isNotEmpty()) {
            val u = c.height / 1080f // design in 1080p units, draw at panel resolution
            title.textSize = 30f * u
            artist.textSize = 22f * u
            title.alpha = (a * 235).toInt()
            artist.alpha = (a * 190).toInt()
            c.drawText(cur.painting.title, 96f * u, c.height - 96f * u, title)
            c.drawText(cur.painting.artist, 96f * u, c.height - 60f * u, artist)
        }
        if (showClock) drawClock(c, now, cur.number)
    }

    /** Paintings are already the screen's shape and simply cover it; photos drift and zoom (see [panZoom]). */
    private fun drawSlide(c: Canvas, s: Slide, alpha: Int, now: Long) {
        paint.alpha = alpha
        if (!s.painting.moving) return c.drawBitmap(s.bmp, null, RectF(0f, 0f, c.width.toFloat(), c.height.toFloat()), paint)
        val m = panZoom((now - s.start).toFloat() / (holdMs + 2 * FADE_MS), s.number, s.bmp.width, s.bmp.height, c.width, c.height)
        matrix.setScale(m[0], m[0])
        matrix.postTranslate(m[1], m[2])
        c.drawBitmap(s.bmp, matrix, paint)
    }

    private val timePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.RIGHT
        typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        alpha = 230
        textAlign = Paint.Align.RIGHT
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val shade = Paint()
    private val use24 = DateFormat.is24HourFormat(context)
    private var clockSecond = -1L
    private var timeText = ""
    private var dateText = ""
    private var weatherAt = 0L
    private var weather: Pair<String, WeatherIcon>? = null

    /**
     * The time, with the date and weather under it, in the bottom-right corner over a soft shade (any photo stays
     * readable). Render thread only.
     */
    private fun drawClock(c: Canvas, now: Long, slide: Int) {
        val u = c.height / 1080f
        if (now / 1000 != clockSecond) {
            clockSecond = now / 1000
            timeText = DateFormat.format(if (use24) "HH:mm" else "h:mm", now).toString()
            dateText = DateFormat.format("EEEE, d MMMM", now).toString()
        }
        if (now - weatherAt > 60_000L) {
            weatherAt = now
            weather = currentWeather()
        }
        if (shade.shader == null) {
            shade.shader = RadialGradient(c.width.toFloat(), c.height.toFloat(), c.width * CLOCK_SHADE_REACH,
                CLOCK_SHADE_ALPHAS.map { it shl 24 }.toIntArray(), CLOCK_SHADE_STOPS, Shader.TileMode.CLAMP)
            shade.isDither = true // no banding in the dark, smooth fade
        }
        val (shadeLeft, shadeTop, shadeRight, shadeBottom) = clockShadeBounds(c.width.toFloat(), c.height.toFloat())
        c.drawRect(shadeLeft, shadeTop, shadeRight, shadeBottom, shade)
        val nudge = CLOCK_NUDGE[slide % CLOCK_NUDGE.size]
        val right = c.width - (88 + nudge[0]) * u
        val base = c.height - (80 + nudge[1]) * u
        timePaint.textSize = 104f * u
        timePaint.setShadowLayer(14f * u, 0f, 2f * u, 0x80000000.toInt())
        linePaint.textSize = 30f * u
        linePaint.setShadowLayer(10f * u, 0f, 1.5f * u, 0x99000000.toInt())
        c.drawText(timeText, right, base - 54f * u, timePaint)
        var x = right
        weather?.let { (temp, icon) ->
            c.drawText(temp, x, base, linePaint)
            x -= linePaint.measureText(temp) + 10f * u
            val s = 32f * u
            icon.setBounds((x - s).toInt(), (base - 26f * u).toInt(), x.toInt(), (base + 6f * u).toInt())
            icon.draw(c)
            x -= s + 22f * u
        }
        c.drawText(dateText, x, base, linePaint)
    }

    /** "25°" and its icon, from the Weather widget's forecast while it's under 3 hours old and for the chosen place. */
    private fun currentWeather(): Pair<String, WeatherIcon>? {
        val place = "${prefs.getFloat("geo_lat", fallbackLat())},${prefs.getFloat("geo_lon", fallbackLon())}"
        if (System.currentTimeMillis() - prefs.getLong("wx_time", 0) > 3 * HOUR_MS || prefs.getString("wx_for", null) != place) return null
        return try {
            val now = JSONObject(prefs.getString("wx_json", null) ?: return null).getJSONObject("current")
            val temp = displayTemp(now.getDouble("temperature_2m"), prefs.getString("wx_unit", "c") ?: "c")
            "$temp°" to WeatherIcon(WeatherIcon.kindFor(skyFor(now.getInt("weather_code")), now.optInt("is_day", 1) == 1), Color.WHITE)
        } catch (e: JSONException) {
            null
        }
    }

    private companion object {
        const val HOLD_MS = 2 * 60_000L // paintings
        const val FADE_MS = 2_500L
        const val FIRST_FADE_MS = 600L
        const val CAPTION_MS = 8_000L
        const val FRAME_MS = 33L
    }
}
