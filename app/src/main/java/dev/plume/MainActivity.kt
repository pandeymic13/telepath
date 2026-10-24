package dev.plume

import android.animation.ArgbEvaluator
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Activity
import android.app.Dialog
import android.app.PendingIntent
import android.app.role.RoleManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageInstaller
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.database.SQLException
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.TransitionDrawable
import android.media.tv.TvContract
import android.media.tv.TvInputInfo
import android.media.tv.TvInputManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.provider.Settings
import android.text.InputType
import android.text.SpannableString
import android.text.TextUtils
import android.text.style.BulletSpan
import android.util.LruCache
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.Window
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors
import org.json.JSONObject

/** The fallback day: 07:00 to 18:59, for places and days where sunrise and sunset can't be worked out. */
internal fun isNightHour(hour: Int) = hour !in 7..18

/** Light/dark from the launcher's theme setting; shared with the Gallery screensaver. Auto follows the sun. */
internal fun prefersDark(prefs: SharedPreferences) = when (prefs.getString("theme", "light")) {
    "dark" -> true
    "auto" -> autoDark(System.currentTimeMillis(), prefs.getFloat("geo_lat", fallbackLat()).toDouble(),
        prefs.getFloat("geo_lon", fallbackLon()).toDouble(), TimeZone.getDefault())
    else -> false
}

/** "1.10" > "1.9" > "1.2.1" > "1.2": compared number by number, missing parts count as 0. */
internal fun newerVersion(a: String, b: String): Boolean {
    val x = a.split('.').map { it.toIntOrNull() ?: 0 }
    val y = b.split('.').map { it.toIntOrNull() ?: 0 }
    for (i in 0 until maxOf(x.size, y.size)) {
        val d = x.getOrElse(i) { 0 } - y.getOrElse(i) { 0 }
        if (d != 0) return d > 0
    }
    return false
}

/** "external" covers every attached volume (USB drives too) from Android 10; before that only the main one. */
internal val PHOTOS: Uri by lazy { // lazy: unit tests load this file without Android
    if (Build.VERSION.SDK_INT >= 29) MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
    else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
}

/** Photo ids in a "folder:<bucket id>:<name>" choice. */
internal fun folderPhotos(cr: ContentResolver, choice: String): List<Long> =
    cr.query(PHOTOS, arrayOf(MediaStore.Images.Media._ID), "${MediaStore.Images.Media.BUCKET_ID} = ?", arrayOf(choice.split(':')[1]), null)
        ?.use { c -> List(c.count) { c.moveToNext(); c.getLong(0) } } ?: emptyList()

/**
 * Sort key of a Watch Next row: what you started comes before next episodes (type 1; other types aren't shown, see
 * [isStartedWatchNext]), then newest first. Some apps write the time in seconds, not milliseconds (Wholphin does, for
 * what is playing): a number that small is read as seconds, or it would sort a thousand times older than every other row.
 */
internal fun watchSortKey(type: Long, engaged: Long): Long {
    val millis = if (engaged in 1 until 100_000_000_000L) engaged * 1000 else engaged
    return (if (type == 0L) 1L shl 50 else 0L) + millis
}

/**
 * Newest first across apps (ties keep their order), each app's [perApp] newest ahead of the rest: one app's ten
 * shows can't bury the others. The rest follow, newest first, so an app on its own isn't held to [perApp].
 */
internal fun <T> newestPerApp(items: List<Pair<Long, T>>, perApp: Int, app: (T) -> String?): List<T> {
    val shown = HashMap<String?, Int>()
    val (front, rest) = items.sortedByDescending { it.first }.map { it.second }.partition { shown.merge(app(it), 1, Int::plus)!! <= perApp }
    return front + rest
}

/** Layout lock PIN, stored as "salt:sha256". Keeps it out of plain sight in a prefs backup; not real security. */
internal fun hashPin(pin: String, salt: String = ByteArray(8).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }) =
    salt + ":" + MessageDigest.getInstance("SHA-256").digest((salt + pin).toByteArray()).joinToString("") { "%02x".format(it) }

internal fun pinMatches(stored: String, pin: String) = hashPin(pin, stored.substringBefore(':')) == stored

private val SET_TOP_WORDS = listOf("sky", "tivo", "xfinity", "comcast", "directv", "dish", "stb", "set-top", "settop", "cable", "satellite", "freesat", "freeview")
private val PS_NAME = Regex("\\bps ?[2-5]\\b")
private val AUDIO_WORDS = listOf("soundbar", "sound bar", "sonos", "avr", "receiver", "home theater", "home theatre", "bose", "denon", "marantz", "onkyo", "yamaha", "harman", "jbl")

/**
 * Icon key for an HDMI tile, from its name: the CEC device name, or the name the user gave the input in TV
 * settings. Android doesn't give apps the CEC device type, so the name is all there is. One brand word covers
 * the family ("PlayStation" is PS3 to PS5, which may also call itself "PS5"). [named]: a CEC device, which is a
 * player when nothing else matches.
 */
internal fun hdmiIcon(name: String, named: Boolean): String {
    val n = name.lowercase()
    return when {
        "playstation" in n || PS_NAME.containsMatchIn(n) -> "playstation"
        "xbox" in n -> "xbox"
        "nintendo" in n || "switch" in n -> "nintendoswitch"
        "apple tv" in n || "appletv" in n -> "appletv"
        SET_TOP_WORDS.any { it in n } -> "settop"
        named -> "playback"
        else -> "hdmi"
    }
}

/** Soundbars and AV receivers name themselves over CEC too, but there's nothing to switch to. */
internal fun isAudioDevice(name: String) = name.lowercase().let { n -> AUDIO_WORDS.any { it in n } }

/** A plain HDMI port (no CEC name) is in the home row only while it has a signal, unless it was pinned by hand. */
internal fun inRow(pkg: String, offline: Boolean, hidden: Set<String>, pinned: Set<String>) =
    pkg !in hidden && (!offline || pkg in pinned)

/**
 * The apps hidden from the dock: those hidden by hand, and every installed app that isn't one of the [favourites]
 * or added since. They're all in All apps.
 */
internal fun hiddenHomeApps(installed: List<String>, hidden: Set<String>, added: Set<String>,
                            favourites: List<String> = DEFAULT_FAVOURITES) =
    hidden + installed.filter { it !in favourites && it !in added }

internal fun isContinueChannel(name: String) = name.trim().lowercase(Locale.ROOT) in
    setOf("continue watching", "watch next", "resume watching")

internal fun hasResumeProgress(position: Long, duration: Long) =
    position >= 1000 && (duration <= 0 || position < duration)

/** TvContract.WatchNextPrograms types that are something you're watching; channel rows have no type and read as 0. */
private const val WATCH_NEXT_CONTINUE = 0L
private const val WATCH_NEXT_NEXT = 1L

/**
 * A Watch Next row worth a Continue Watching card: something you started (CONTINUE) or the next episode of a series
 * you're partway through (NEXT), as Google TV's own row shows them. New-season alerts (NEW, 2) and watchlist items
 * (WATCHLIST, 3) are suggestions, so they're left out.
 */
internal fun isStartedWatchNext(type: Long) = type == WATCH_NEXT_CONTINUE || type == WATCH_NEXT_NEXT

/**
 * The next slideshow photo and what's left of the round: every photo once per round, in [shuffle]d order,
 * never the same photo twice in a row across rounds. Photos deleted since the round began are skipped; photos [added]
 * since (synced from the PC) join what's left of this round at random places, so they show up soon.
 */
internal fun nextSlide(names: List<String>, deck: List<String>, current: String?,
                       added: List<String> = emptyList(), shuffle: (List<String>) -> List<String>): Pair<String, List<String>> {
    require(names.isNotEmpty()) { "No slideshow photos." }
    var round = deck.filter { it in names }
    val fresh = added.filter { it in names && it !in round && it != current }.distinct()
    if (fresh.isNotEmpty()) round = shuffle(round + fresh)
    if (round.isEmpty()) {
        round = shuffle(names)
        if (round.size > 1 && round.first() == current) round = round.drop(1) + round.first()
    }
    return round.first() to round.drop(1)
}

/** Allowed to start an activity from the background because the Home button service is bound. */
class Updated : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        c.startActivity(Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

class MainActivity : Activity() {

    /**
     * [offline]: an HDMI port with no signal right now (see inRow). [streaming]: the app's automatic All apps group (see
     * isStreamingApp; you can move it, see groupOf). [tile]: the start of its tile file names (AppTiles.key).
     */
    private class App(val pkg: String, val label: String, val launch: Intent, val icon: Drawable, val offline: Boolean = false,
                      val streaming: Boolean = false, val tile: String? = null) {
        fun withIcon(d: Drawable) = App(pkg, label, launch, d, offline, streaming, tile)
    }

    /** An app whose tiles still have to be made: from [info], under [key], in [style]. */
    private class Pending(val info: ActivityInfo, val key: String, val style: TileStyle)

    /** The apps with their tiles for the [light] theme or the dark one; [complete]: no tile is left to make. */
    private class Loaded(val apps: List<App>, val light: Boolean, val complete: Boolean)
    /** A Continue Watching card. [hideTitle]: the title as the app gave it (hiding is keyed by it). */
    private class Prog(val title: String, val sub: String, val progress: Int, val image: String?, val intent: String?, val pkg: String?,
                       val hideTitle: String = title)

    private val prefs by lazy { getSharedPreferences("home", MODE_PRIVATE) }
    private val io = Executors.newSingleThreadExecutor()
    // Posters get their own threads: one slow image server must not hold up the rest (or the row data).
    private val posterIo = Executors.newFixedThreadPool(3)
    private val loading = java.util.Collections.synchronizedSet(HashSet<String>())
    private val ui = Handler(Looper.getMainLooper())
    private val posters = object : LruCache<String, Bitmap>(6 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    // Kept across activity rebuilds (theme switch), so the new home screen shows its apps at once; see keepApps.
    private var apps: List<App> = emptyList()
    private var appsDirty = true
    private var watchKey: String? = null
    private var trail: Pair<View, View>? = null // (where Up/Down landed, where it came from), for the way back
    private var lastApp: View? = null
    private var lastCard: View? = null
    private var editing = false
    private var grabbed: View? = null
    private var holdFired = false
    private var dark = false
    private var fg = Color.BLACK
    private var ground = Color.WHITE

    private lateinit var appRow: LinearLayout
    private lateinit var watchRow: LinearLayout
    private lateinit var watchTitle: TextView
    private val appsTitle by lazy { findViewById<TextView>(R.id.appsTitle) }
    private lateinit var watchScroll: View
    private lateinit var allApps: View
    private lateinit var allAppsSections: LinearLayout
    private lateinit var wifi: ImageView
    private var wifiRest = Color.WHITE // the header's icon colour for the current photo; focus turns it ink
    private val headerBlock by lazy { findViewById<View>(R.id.headerBlock) }
    /** The header's text: the clock and Up next title, then the date and Up next time. */
    private val headerText by lazy { listOf(R.id.clock, R.id.upNextTitle).map { findViewById<TextView>(it) } }
    private val headerText2 by lazy { listOf(R.id.date, R.id.upNextWhen).map { findViewById<TextView>(it) } }
    private lateinit var personalDashboard: PersonalDashboard
    private lateinit var upNext: UpNext
    private lateinit var kit: UiKit
    private val homeScroll by lazy { findViewById<HomeScrollView>(R.id.homeScroll) }
    private var allAppsFrom: View? = null
    private var allMoving: String? = null // the app being moved in All apps (hold OK > Move)
    private var allMovingOrder: List<String>? = null // All apps' order while moving, saved when it's placed
    private var allAppsFocus: Runnable? = null // All apps' pending focus after a rebuild; a newer rebuild cancels it

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (i.data?.schemeSpecificPart == packageName) return
            appsDirty = true // an updated or reinstalled app gets new tiles: AppTiles keys them by version and install time
            // Installed, updated or removed while Home is on screen: shown once the burst of broadcasts settles.
            ui.removeCallbacks(appsChanged)
            ui.postDelayed(appsChanged, 1500L)
        }
    }

    /** Refreshes the apps if Home is showing and not mid-edit; otherwise the next return Home does (see onResume). */
    private val appsChanged = Runnable { if (visible && appsDirty && !editing) refreshApps() }

    private val net = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(n: Network, caps: NetworkCapabilities) {
            ui.post {
                showNet(caps)
                // Online again (e.g. Wi-Fi coming up after boot): fetch any posters that failed meanwhile.
                if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) retryPosters()
            }
        }

        override fun onLost(n: Network) {
            ui.post { showNet(null) }
        }
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        if (!prefs.contains("aster_initialized")) {
            prefs.edit().putBoolean("aster_initialized", true)
                .putString("theme", "dark").putString("wp_dark", "res:Rockies (dusk)")
                .putString("seen_notes", myVersion()).apply()
        }
        if (prefs.getInt("aster_layout_version", 0) < 2) {
            check(prefs.edit().putInt("aster_layout_version", 2)
                .putString("theme", "auto").putBoolean("colour_icons", true)
                .putString("names", "never").putString("sort", "manual")
                .putString("order", DEFAULT_FAVOURITES.joinToString(","))
                .putStringSet("row_hidden", emptySet()).putStringSet("row_added", emptySet())
                .putStringSet("inputs_pinned", emptySet())
                .putStringSet("cw_off", prefs.getStringSet("cw_off", emptySet())!! - DEFAULT_FAVOURITES.toSet())
                .commit()) { "The requested home layout could not be saved." }
        }
        // telepath: the theme from telepath.properties (sun-based by default), the photo slideshow on both
        // themes, and the weather location set there (else found automatically).
        if (prefs.getInt("aster_layout_version", 0) < 3) {
            val edit = prefs.edit().putInt("aster_layout_version", 3)
                .putString("theme", BuildConfig.THEME_MODE).putString("wp_light", SLIDESHOW).putString("wp_dark", SLIDESHOW)
                .putString("names", "never").putBoolean("colour_icons", true).putString("weather", "off")
            val place = DEFAULT_PLACE
            if (place != null && !prefs.getBoolean("geo_manual", false)) {
                edit.putBoolean("geo_manual", true).putString("geo_city", place.city)
                    .putFloat("geo_lat", place.lat).putFloat("geo_lon", place.lon).remove("wx_time")
            }
            check(edit.commit()) { "The telepath defaults could not be saved." }
        }
        // The Calendar widget is gone: wipe its encrypted feed address and key if one was ever saved.
        if (prefs.getInt("aster_layout_version", 0) < 4) {
            deleteSharedPreferences("aster_private")
            try {
                java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry("aster.calendar.feed")
            } catch (e: java.security.GeneralSecurityException) {
                android.util.Log.w("telepath", "Old calendar key not removed: ${e.javaClass.simpleName}")
            } catch (e: java.io.IOException) {
                android.util.Log.w("telepath", "Old calendar key not removed: ${e.javaClass.simpleName}")
            }
            check(prefs.edit().putInt("aster_layout_version", 4).putString("mood", BuildConfig.MOOD_MODE).commit()) {
                "The telepath defaults could not be saved."
            }
        }
        // telepath's own screensaver, showing the Wallpapers slideshow, instead of Google TV's Ambient mode. The one
        // that was set is remembered, for Home settings > Screensaver > Google TV Ambient mode (ensureScreensaver
        // switches it in onResume).
        if (prefs.getInt("aster_layout_version", 0) < 5) {
            val edit = prefs.edit().putInt("aster_layout_version", 5).putString("ss_source", "slideshow").remove("ss_folder")
                .putBoolean("ss_mine", true)
            if (!galleryOn()) edit.putString("prev_screensaver", currentScreensaver())
            check(edit.commit()) { "The telepath defaults could not be saved." }
        }
        setContentView(R.layout.activity_main)
        appRow = findViewById(R.id.appRow)
        watchRow = findViewById(R.id.watchRow)
        watchTitle = findViewById(R.id.watchTitle)
        watchScroll = findViewById(R.id.watchScroll)
        allApps = findViewById(R.id.allApps)
        allAppsSections = findViewById(R.id.allAppsSections)
        wifi = findViewById(R.id.wifi)
        applyTheme()
        keepApps()
        findViewById<View>(R.id.watchEmpty).apply {
            setTag(R.id.homeRow, "cw")
            focusTile(this, kit, 16f, null, listOf(this as TextView), kit.text2, scale = 1.015f)
            setOnClickListener { showWatchCoverage() }
        }
        setUpNetIcon()
        loadWallpaper()
        personalDashboard = PersonalDashboard(this, findViewById(R.id.widgetsSection), kit, dashboardHost)
        upNext = UpNext(this)
        for (id in intArrayOf(R.id.headerBlock, R.id.clock, R.id.date, R.id.upNext, R.id.upNextTitle, R.id.upNextWhen)) {
            findViewById<View>(id).addOnLayoutChangeListener(headerMoved)
        }
        val frost = findViewById<View>(R.id.wallpaperFrost)
        val scrim = findViewById<View>(R.id.scrimBottom)
        // Scrolling to the widgets: the wallpaper frosts and Continue Watching fades away, leaving the dock on top.
        val watching = listOf(R.id.watchTitle, R.id.watchStatus, R.id.watchEmpty, R.id.watchScroll).map { findViewById<View>(it) }
        homeScroll.onScrollFraction = { f ->
            frost.alpha = f
            scrim.alpha = 1f - f * 0.6f
            watching.forEach { it.alpha = (1f - f * 1.6f).coerceIn(0f, 1f) }
        }
        io.execute { getExternalFilesDir(SLIDESHOW_DIR) } // created by the app, so files pushed into it stay readable
        if (prefs.getBoolean("reopen_settings", false)) {
            prefs.edit().remove("reopen_settings").apply()
            ui.post { openLauncherSettings(R.string.theme) }
        } else if (!homeServiceOn() && !prefs.getBoolean("setup_shown", false)) {
            // Fresh install: walk through making Home land on Plume, the one step that has to happen by hand.
            prefs.edit().putBoolean("setup_shown", true).apply()
            ui.post { startHomeSetup() }
        } else if (notesUnseen()) {
            ui.post { whatsNewCard() } // after an update; the self-updater reopens Plume, so it's seen right away
        }

        registerReceiver(packageReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        })
        // Application context: ConnectivityManager keeps its first instance (and that instance's context) in a
        // static callback handler forever, so an Activity context here leaks the whole first home screen.
        applicationContext.getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(net)
        getSystemService(TvInputManager::class.java)?.registerCallback(inputCallback, ui)

        if (checkSelfPermission(TV_LISTINGS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(TV_LISTINGS), 1)
        }
    }

    override fun onResume() {
        super.onResume()
        ensureHomeService()
        homeSetupResumed()
        if (themeDue()) {
            recreate()
            return
        }
        // Only rebuild what changed, so focus stays where it was when returning from an app.
        if (appsDirty) refreshApps() else if (appRow.childCount == 0) renderApps()
        refreshWatchNext()
        applyMood()
        upNext.resume()
        ui.removeCallbacks(themeTick)
        ui.postDelayed(themeTick, 60_000L)
        // Paintings chosen but missing (updated from a version that bundled them): fetch them.
        if (galleryOn() && screensaverSource(prefs) == "paintings" && !paintingsReady(this)) downloadPaintings {}
        ensureScreensaver()
        personalDashboard.resume()
        Bing.onChanged = bingChanged
        Bing.refresh(applicationContext, prefs)
        ui.post { updateBadge() } // after layout, so the icon has its size
    }

    private var visible = false

    /** Landing on Home: Google TV's own home is covered again, and Home opens on the wallpaper page. */
    override fun onStart() {
        super.onStart()
        visible = true
        HomeOverride.stockHomeAllowed = false
        if (homeScroll.page > 0) {
            homeScroll.show(0, animate = false)
            (lastApp?.takeIf { it.isAttachedToWindow } ?: firstDockApp())?.requestFocus()
        }
    }

    /** Auto theme flips at sunrise and sunset even while the home screen just sits there; the mood follows the day too. */
    private val themeTick = object : Runnable {
        override fun run() {
            if (themeDue()) return recreate()
            applyMood()
            upNext.tick()
            ui.postDelayed(this, 60_000L)
        }
    }

    /**
     * The other theme is due, and nothing would be lost by redrawing for it. Not during the Home button setup: its
     * popups go with the old screen, and on a fresh install the TV's place (so sunrise and sunset) often arrives then.
     * The theme follows within a minute of setup ending.
     */
    private fun themeDue() = wantDark() != dark && setupDialog == null && setupStep == 0 && homeTest == 0

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        if (code != MEDIA_REQUEST) return refreshWatchNext()
        if (results.firstOrNull() == PackageManager.PERMISSION_GRANTED) afterPhotoAccess?.invoke()
        else Toast.makeText(this, getString(R.string.photos_permission), Toast.LENGTH_LONG).show()
    }

    @Deprecated("Only used for the default-home role request")
    override fun onActivityResult(code: Int, result: Int, data: Intent?) {
        if (code == ROLE_REQUEST && settingsScrim.visibility == View.VISIBLE) renderSettings(R.string.default_launcher)
        if (code == ROLE_REQUEST && setupStep == 1) {
            setupStep = 0
            afterHomeRole()
        }
    }

    override fun onPause() {
        personalDashboard.pause()
        ui.removeCallbacks(themeTick)
        if (editing) endEdit()
        if (allMoving != null) endAllAppsMove(refresh = false)
        super.onPause()
    }

    override fun onStop() {
        visible = false
        unlockedUntil = 0L // leaving the home screen locks it again (onPause would also fire for permission dialogs)
        if (homeTest != 0) leftDuringTest = true
        // Next slideshow photo: decoded while Home is hidden, so it's already in place when Home shows again.
        if (currentWallpaper() == SLIDESHOW && !isChangingConfigurations) loadWallpaper(advance = true)
        super.onStop()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        posters.evictAll()
    }

    override fun onDestroy() {
        personalDashboard.close()
        upNext.close()
        headerFade?.cancel()
        ui.removeCallbacks(headerRecheck)
        if (Bing.onChanged === bingChanged) Bing.onChanged = null
        unregisterReceiver(packageReceiver)
        ui.removeCallbacks(appsChanged)
        applicationContext.getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(net)
        getSystemService(TvInputManager::class.java)?.unregisterCallback(inputCallback)
        upload?.stop()
        io.shutdownNow()
        posterIo.shutdownNow()
        super.onDestroy()
    }

    /**
     * Android drops our Home service from the enabled accessibility services whenever the app is
     * force-stopped, and then Home lands on Google TV. Put it back (needs the adb-granted permission),
     * unless the user turned it off.
     */
    private fun ensureHomeService() {
        if (homeServiceOn()) return prefs.edit().remove("home_off").apply() // what Android says wins
        if (prefs.getBoolean("home_off", false) || checkSelfPermission(WRITE_SECURE) != PackageManager.PERMISSION_GRANTED) return
        val me = ComponentName(this, HomeOverride::class.java).flattenToString()
        val on = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        Settings.Secure.putString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, if (on.isEmpty()) me else "$on:$me")
        Settings.Secure.putInt(contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
    }

    /** Compared as components: Settings may store us in short form (dev.plume/.HomeOverride). */
    private fun homeServiceOn(): Boolean {
        val me = ComponentName(this, HomeOverride::class.java)
        return Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            .split(':').any { ComponentName.unflattenFromString(it) == me }
    }

    private fun toggleHomeButton() = if (homeServiceOn()) turnOffHomeButton() else turnOnHomeButton()

    /** Off hands Home back to the TV's own launcher, and Plume leaves it off until switched on again. */
    private fun turnOffHomeButton() {
        prefs.edit().putBoolean("home_off", true).apply()
        HomeOverride.running?.disableSelf()
        if (homeServiceOn() && checkSelfPermission(WRITE_SECURE) == PackageManager.PERMISSION_GRANTED) {
            val me = ComponentName(this, HomeOverride::class.java)
            val on = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            Settings.Secure.putString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                on.split(':').filter { ComponentName.unflattenFromString(it) != me }.joinToString(":"))
        }
        if (homeServiceOn()) {
            Toast.makeText(this, getString(R.string.home_off_hint), Toast.LENGTH_LONG).show()
            openSettings(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        }
        renderSettings(R.string.home_button)
    }

    /** The Home key service is an accessibility service, which Android only lets the user switch on. */
    private fun turnOnHomeButton() {
        prefs.edit().remove("home_off").apply()
        ensureHomeService() // works where the adb permission was granted
        if (homeServiceOn()) return renderSettings(R.string.home_button)
        // The written path is the safety net: some Google TVs (a Xiaomi stick) answer the Accessibility link
        // with "You don't have an app that can do this", which we can't detect from here.
        Toast.makeText(this, getString(R.string.home_on_hint), Toast.LENGTH_LONG).show()
        openHomeServiceSettings()
    }

    /** False when no Settings screen opened at all. */
    private fun openHomeServiceSettings(): Boolean {
        val service = ComponentName(this, HomeOverride::class.java).flattenToString()
        val tries = listOf(
            // Straight to our own toggle (Android 12+, where the TV supports it), then the list, then Settings.
            Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS").putExtra(Intent.EXTRA_COMPONENT_NAME, service),
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS),
            Intent(Settings.ACTION_SETTINGS),
        )
        for (intent in tries) {
            try {
                startActivity(intent) // not resolveActivity(): Android 11+ hides other apps from that
                return true
            } catch (e: Exception) {
            }
        }
        return false
    }

    private fun isDefaultHome() = Build.VERSION.SDK_INT >= 29 &&
        getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_HOME)

    /**
     * Asks Android to make us the default home app (its own confirmation dialog). On Google TV the system
     * launcher still wins Home intents, which is why the Home button service exists; on other Android TVs
     * this alone is enough.
     */
    private fun makeDefaultHome() {
        val roles = if (Build.VERSION.SDK_INT >= 29) getSystemService(RoleManager::class.java) else null
        if (roles != null && roles.isRoleAvailable(RoleManager.ROLE_HOME) && !roles.isRoleHeld(RoleManager.ROLE_HOME)) {
            @Suppress("DEPRECATION")
            startActivityForResult(roles.createRequestRoleIntent(RoleManager.ROLE_HOME), ROLE_REQUEST)
        } else {
            openSettings(Settings.ACTION_HOME_SETTINGS) // Android lets apps ask for Home, not give it back
        }
    }

    // ---------- Home setup ----------
    // Popups that try each way of making Home land on Plume, stopping at the first that works: the default-home
    // role, then the Home button service, then adb help for TVs that grey out the service (Android 13+
    // "restricted settings" for sideloaded apps). Each way is checked by asking for a Home press: one that
    // reaches us arrives as a Home intent; one that doesn't sends us to the background.

    private var setupDialog: Dialog? = null
    private var setupStep = 0 // 1 role dialog open, 2 Accessibility settings open
    private var homeTest = 0 // the way being checked (1 role, 2 service) while waiting for a Home press
    private var leftDuringTest = false
    private var previewAt = -1 // debug builds: which popup the preview is on, -1 when not previewing

    private fun startHomeSetup() {
        val roles = if (Build.VERSION.SDK_INT >= 29) getSystemService(RoleManager::class.java) else null
        if (roles != null && roles.isRoleAvailable(RoleManager.ROLE_HOME) && !roles.isRoleHeld(RoleManager.ROLE_HOME)) rolePopup()
        else afterHomeRole()
    }

    private fun afterHomeRole() = if (isDefaultHome() || homeServiceOn()) testPopup(1) else tryHomeService()

    private fun tryHomeService() {
        prefs.edit().remove("home_off").apply()
        ensureHomeService() // works where the adb permission was granted
        if (homeServiceOn()) testPopup(2) else servicePopup()
    }

    /** Back from the role dialog, Accessibility settings, or another home screen after a Home press. */
    private fun homeSetupResumed() {
        if (setupStep == 2) {
            setupStep = 0
            if (homeServiceOn()) testPopup(2) else adbPopup()
        } else if (homeTest != 0 && leftDuringTest) {
            val failed = homeTest
            homeTest = 0
            if (failed == 1) tryHomeService() else adbPopup()
        }
    }

    private fun rolePopup() = homePopup(1, getString(R.string.setup_role_title), getString(R.string.setup_role_body), null,
        getString(R.string.setup_continue) to {
            setupStep = 1
            @Suppress("DEPRECATION")
            startActivityForResult(getSystemService(RoleManager::class.java).createRequestRoleIntent(RoleManager.ROLE_HOME), ROLE_REQUEST)
        },
        getString(R.string.setup_not_now) to { setupDialog?.dismiss() })

    private fun testPopup(way: Int) {
        if (previewAt < 0) {
            homeTest = way
            leftDuringTest = false
        }
        homePopup(way, getString(R.string.setup_test_title), getString(R.string.setup_test_body), null,
            getString(R.string.setup_skip_check) to { homeTest = 0; if (way == 1) tryHomeService() else adbPopup() })
    }

    private fun servicePopup() = homePopup(2, getString(R.string.setup_service_title), getString(R.string.setup_service_body), null,
        getString(R.string.setup_open_accessibility) to {
            setupStep = 2
            if (!openHomeServiceSettings()) {
                setupStep = 0
                adbPopup()
            }
        },
        getString(R.string.setup_not_now) to { setupDialog?.dismiss() })

    /** The one-time grant lets Plume switch the service on itself, past the greyed-out toggle. */
    private fun adbPopup() {
        val mono = Typeface.MONOSPACE
        val code = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(if (dark) DARK_BG else 0xFFE9EBEF.toInt())
            }
            listOfNotNull(lanAddress()?.let { "adb connect $it:5555" }, "adb shell pm grant $packageName $WRITE_SECURE").forEach {
                addView(label(it, 12f).apply { typeface = mono; maxLines = 2; ellipsize = null; setPadding(0, dp(2), 0, dp(2)) })
            }
        }
        val qr = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(ImageView(context).apply { setImageBitmap(qrBitmap(HELP_URL, dp(108))) }, LinearLayout.LayoutParams(dp(108), dp(108)))
            addView(label(getString(R.string.setup_scan_help), 11f).apply { alpha = 0.6f; setPadding(0, dp(6), 0, 0) })
        }
        val extra = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, 0)
            addView(code, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(qr, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(20) })
        }
        homePopup(3, getString(R.string.setup_adb_title), getString(R.string.setup_adb_body), extra,
            getString(R.string.setup_try_again) to { tryHomeService() },
            getString(R.string.setup_close) to { setupDialog?.dismiss() })
    }

    /**
     * Once after an update: the first three CHANGELOG.md lines of this version, so put the best ones first.
     * Closing it counts as reading the notes, which clears the dot on the Plume settings icon.
     */
    private fun whatsNewCard() {
        prefs.edit().putString("seen_notes", myVersion()).apply()
        updateBadge()
        val notes = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            getString(R.string.whats_new).split('\n').take(3).forEach {
                addView(label("", 15f).apply {
                    text = SpannableString(it).apply { setSpan(BulletSpan(dp(10), fg), 0, it.length, 0) }
                    maxLines = Int.MAX_VALUE
                    ellipsize = null
                    setLineSpacing(0f, 1.15f)
                    setPadding(0, dp(12), 0, 0)
                })
            }
        }
        homePopup(0, getString(R.string.whats_new_card_title, myVersion()), null, notes,
            getString(R.string.done) to { setupDialog?.dismiss() },
            getString(R.string.see_all_changes) to {
                setupDialog?.dismiss()
                unlock { openLauncherSettings(R.string.updates); showUpdates() }
            })
    }

    private fun donePopup() = homePopup(0, getString(R.string.setup_done_title),
        getString(if (homeServiceOn()) R.string.setup_done_body_service else R.string.setup_done_body), null,
        getString(R.string.setup_done) to { setupDialog?.dismiss() })

    /** A card in the middle of the screen over the dimmed home screen, in the hold menus' colours. */
    private fun homePopup(step: Int, title: String, body: String?, extra: View?, vararg buttons: Pair<String, () -> Unit>) {
        setupDialog?.let { setupDialog = null; it.dismiss() } // replaced, not closed: the dismiss listener leaves the flow alone
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(28), dp(24), dp(28), dp(20))
            background = if (dark) rimCard(dp(16), false) else GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(0xFFF7F8FA.toInt())
            }
            if (step > 0) addView(label(getString(R.string.setup_step, step), 12f).apply { secondary(0.6f) })
            addView(label(title, 22f).apply { setTypeface(typeface, Typeface.BOLD); setPadding(0, dp(4), 0, 0) })
            body?.let { addView(label(it, 14f).apply { maxLines = 6; secondary(0.8f); setLineSpacing(0f, 1.15f); setPadding(0, dp(10), 0, 0) }) }
            extra?.let { addView(it) }
            buttons.forEachIndexed { i, (name, action) ->
                addView(settingsRow(name, "") { if (previewAt >= 0) previewHomeSetup(previewAt + 1) else action() },
                    LinearLayout.LayoutParams(MATCH_PARENT, dp(44)).apply { topMargin = if (i == 0) dp(20) else dp(4) })
            }
        }
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(card)
        dialog.window?.run {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(dp(if (extra != null) 600 else 480), ViewGroup.LayoutParams.WRAP_CONTENT)
            setDimAmount(0.6f)
        }
        dialog.setOnDismissListener {
            if (setupDialog !== dialog) return@setOnDismissListener
            setupDialog = null // closed with Back or a button: setup stops here, and Settings can start it again
            setupStep = 0
            homeTest = 0
            previewAt = -1
        }
        setupDialog = dialog
        dialog.show()
        card.getChildAt(card.childCount - buttons.size).requestFocus()
    }

    /** Debug builds: every popup in turn, with buttons that only move on, to check the look on a TV. */
    private fun previewHomeSetup(at: Int = 0) {
        previewAt = at
        when (at) {
            0 -> rolePopup()
            1 -> testPopup(1)
            2 -> servicePopup()
            3 -> adbPopup()
            4 -> donePopup()
            else -> setupDialog?.dismiss()
        }
    }

    /** Home pressed while already home: close whatever is open and go back to the wallpaper page's first favourite. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == UPDATE_STATUS) return installStatus(intent)
        if (homeTest != 0 && intent.hasCategory(Intent.CATEGORY_HOME)) { // the Home press reached us: setup works
            homeTest = 0
            return donePopup()
        }
        if (editing) endEdit()
        if (settingsScrim.visibility == View.VISIBLE) closeLauncherSettings()
        if (allApps.visibility == View.VISIBLE) closeAllApps()
        watchScroll.scrollTo(0, 0)
        findViewById<View>(R.id.appScroll).scrollTo(0, 0)
        trail = null
        firstDockApp()?.requestFocus()
        homeScroll.show(0, animate = visible)
    }

    /** Back closes overlays; on the widgets page it goes back up to the favourites. */
    @Deprecated("Home screen: Back only closes overlays")
    override fun onBackPressed() {
        when {
            settingsScrim.visibility == View.VISIBLE -> pageBack?.invoke() ?: closeLauncherSettings()
            allApps.visibility == View.VISIBLE -> closeAllApps()
            homeScroll.page > 0 -> (lastApp?.takeIf { it.isAttachedToWindow } ?: firstDockApp())?.requestFocus()
        }
    }

    // ---------- theme ----------

    private fun wantDark() = prefersDark(prefs)

    /** The mood painted into the wallpaper on show; repainting happens only when this changes. */
    private var shownMood: Mood? = null
    private var skyAt = -1L
    private var sky: Sky? = null

    /** Mood colours: "weather" (time of day and weather, the default), "time" (time of day only) or "off". */
    private fun moodMode() = prefs.getString("mood", "weather")!!

    private fun currentMood(): Mood? {
        val mode = moodMode()
        if (mode == "off") return null
        return moodAt(System.currentTimeMillis(), prefs.getFloat("geo_lat", fallbackLat()).toDouble(),
            prefs.getFloat("geo_lon", fallbackLon()).toDouble(), TimeZone.getDefault(), if (mode == "weather") currentSky() else null)
    }

    /** The weather now at the weather location, from the widgets' forecast when it's under 3 hours old. */
    private fun currentSky(): Sky? {
        val at = prefs.getLong("wx_time", 0)
        val place = "${prefs.getFloat("geo_lat", fallbackLat())},${prefs.getFloat("geo_lon", fallbackLon())}"
        if (System.currentTimeMillis() - at > 3 * HOUR_MS || prefs.getString("wx_for", null) != place) return null
        if (at != skyAt) {
            skyAt = at
            sky = try {
                skyFor(JSONObject(prefs.getString("wx_json", null) ?: "{}").getJSONObject("current").getInt("weather_code"))
            } catch (e: org.json.JSONException) {
                null
            }
        }
        return sky
    }

    /** Repaints the wallpaper once the mood moves on: a new part of the day, or new weather. */
    private fun applyMood() {
        if (currentMood() != shownMood) loadWallpaper()
    }

    /**
     * Paints [mood] into the wallpaper picture (io thread), in strips so the extra memory stays small. Done once per
     * picture and mood, so the tint and drops cost nothing while the home screen draws.
     */
    private fun paintMoodInto(src: Bitmap, mood: Mood): Bitmap {
        val bmp = if (src.isMutable && src.config == Bitmap.Config.ARGB_8888) src
        else src.copy(Bitmap.Config.ARGB_8888, true).also { src.recycle() }
        val look = lookFor(mood)
        val drops = if (look.drops == 0) emptyList() else dropsFor(look.drops, bmp.width, bmp.height, System.currentTimeMillis() / (3 * HOUR_MS))
        val w = bmp.width
        val h = bmp.height
        val px = IntArray(w * 64)
        var y = 0
        while (y < h) {
            val rows = minOf(64, h - y)
            bmp.getPixels(px, 0, w, 0, y, w, rows)
            paintMood(px, w, y, rows, h, look, drops)
            bmp.setPixels(px, 0, w, 0, y, w, rows)
            y += rows
        }
        return bmp
    }

    private fun applyTheme() {
        dark = wantDark()
        kit = UiKit(dark, resources.displayMetrics.density)
        fg = kit.text
        ground = kit.ground
        findViewById<View>(R.id.root).setBackgroundColor(ground)
        for (id in intArrayOf(R.id.appsTitle, R.id.allAppsTitle)) findViewById<TextView>(id).setTextColor(kit.text)
        for (id in intArrayOf(R.id.watchStatus, R.id.allAppsHint)) findViewById<TextView>(id).setTextColor(kit.text2)
        watchTitle.setTextColor(kit.text)
        // Text over the bottom veil gets a soft glow in the theme's ground colour, so busy photos can't swallow it.
        for (id in intArrayOf(R.id.watchTitle, R.id.watchStatus, R.id.appsTitle)) glow(findViewById(id))
        // The clock and Up next get no veil or glow: they take white or ink text to suit each photo (updateHeaderLook).
        headerBlock.background = kit.glass(20f).apply { alpha = 0 }
        updateHeaderLook(animate = false)
        // A veil behind Continue Watching and the dock keeps them readable on any photo.
        val base = ground and 0x00FFFFFF
        findViewById<View>(R.id.scrimBottom).background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
            intArrayOf(base or (if (dark) 0xE0000000 else 0xD4000000).toInt(), base or (if (dark) 0x99000000 else 0x8C000000).toInt(), base))
            .apply { setDither(true) }
        findViewById<View>(R.id.dock).background = kit.glass(26f)
        findViewById<View>(R.id.wallpaperFrost).foreground = ColorDrawable(kit.frostTint)
    }

    // ---------- wallpapers ----------
    // Bundled ones have their fade baked in. Photos added from a phone go in Android/data/<app>/files/wallpapers/.
    // The slideshow is Android/data/<app>/files/slideshow/, filled from the PC by Sync-Wallpapers.ps1 (adb push,
    // no permission needed); every return to Home shows its next photo. Readability comes from a veil behind Continue
    // Watching and the dock (scrimBottom) and, at the top, from the header's text colour chosen per photo
    // (updateHeaderLook), so photos are shown untouched. Each theme remembers its own pick.
    // Decoded off the UI thread at 1080p (~8 MB), full colour: RGB_565 bands smooth skies.

    private fun wallpaperDir() = getExternalFilesDir("wallpapers")

    /** The slideshow first, then a plain colour, the bundled pictures, Bing, and photos added from a phone. */
    private fun wallpaperChoices(forDark: Boolean): List<Pair<String, String>> =
        listOf(wallpaperName(SLIDESHOW) to SLIDESHOW, plainWallpaper(forDark).let { wallpaperName(it) to it }) +
            BUNDLED_WALLPAPERS.keys.map { wallpaperName("res:$it") to "res:$it" } +
            (wallpaperName(Bing.CHOICE) to Bing.CHOICE) +
            (wallpaperDir()?.listFiles { f -> f.extension.lowercase() in PHOTO_TYPES }
                ?.sortedBy { it.name }?.map { it.nameWithoutExtension to "file:${it.name}" } ?: emptyList()) +
            listOfNotNull(currentWallpaper(forDark).takeIf { it.startsWith("folder:") }?.let { wallpaperName(it) to it })

    // Light gets a soft white (the light ground colour), not #FFFFFF: pure white glares on a TV.
    private fun plainWallpaper(forDark: Boolean) = if (forDark) "plain:Pure black" else "plain:Soft white"

    private fun defaultWallpaper(forDark: Boolean) = if (forDark) "res:Rockies (dusk)" else "res:Rockies (day)"

    private fun currentWallpaper(forDark: Boolean = dark) =
        prefs.getString(if (forDark) "wp_dark" else "wp_light", null) ?: defaultWallpaper(forDark)

    private fun wallpaperName(choice: String) = when {
        choice == SLIDESHOW -> slideshowFiles(this).size.let { n ->
            if (n == 0) getString(R.string.slideshow_empty)
            else getString(R.string.slideshow) + " · " + resources.getQuantityString(R.plurals.photo_count, n, n)
        }
        choice == Bing.CHOICE -> getString(R.string.bing_daily)
        choice.startsWith("folder:") -> getString(R.string.folder_name, choice.substringAfter(':').substringAfter(':'))
        else -> choice.substringAfter(':').substringBeforeLast('.').let { n -> WALLPAPER_NAMES[n]?.let { getString(it) } ?: n }
    }

    private var wallpaperGeneration = 0

    /** Luminance along the top of the wallpaper on show, for the header's look; null for a plain colour. */
    private var headerBand: LumaBand? = null
    private var headerTarget: HeaderStyle? = null
    private var headerPainted: HeaderStyle? = null
    private var headerFade: ValueAnimator? = null

    /** The header's text moved or changed size (Up next changed, 9:59 became 10:00): look again at the photo behind. */
    private val headerMoved = View.OnLayoutChangeListener { _, l, t, r, b, oldL, oldT, oldR, oldB ->
        if (l != oldL || t != oldT || r != oldR || b != oldB) {
            ui.removeCallbacks(headerRecheck)
            ui.post(headerRecheck)
        }
    }
    private val headerRecheck = Runnable { updateHeaderLook(animate = visible) }

    /** The part of [bmp] behind the header, the top quarter of the screen, as luminance (io thread). */
    private fun headerBandOf(bmp: Bitmap): LumaBand {
        val screen = resources.displayMetrics
        val rows = centerCropRect(screen.widthPixels, screen.heightPixels, bmp.width, bmp.height,
            0, 0, screen.widthPixels, screen.heightPixels / 4)[3].coerceIn(1, bmp.height)
        return LumaBand.of(bmp.width, rows, bmp.height) { y, n, into -> bmp.getPixels(into, 0, bmp.width, 0, y, bmp.width, n) }
    }

    /**
     * Colours the clock, date and Up next for the photo behind them, with no veil: white text with a soft shadow, ink
     * text, or, where the photo is too mixed for either, the theme's text on a glass plate (see HeaderLegibility). The
     * Wi-Fi icon goes white or ink on its own. On a plain colour, or before the photo is in, the theme's text suits.
     */
    private fun updateHeaderLook(animate: Boolean) {
        val band = headerBand
        val wall = findViewById<View>(R.id.wallpaper)
        if (band == null || wall.width == 0 || headerText[0].width == 0) {
            return showHeader(headerStyle(null, false, kit.text, kit.text2), animate)
        }
        val content = findViewById<ViewGroup>(R.id.content)
        fun behind(v: View): DoubleArray {
            if (v.width == 0) return DoubleArray(0)
            val r = Rect(0, 0, v.width, v.height)
            content.offsetDescendantRectToMyCoords(v, r)
            r.offset(homeScroll.left + content.left, homeScroll.top + content.top) // over the wallpaper, unscrolled
            val p = centerCropRect(wall.width, wall.height, band.w, band.pictureH, r.left, r.top, r.right, r.bottom)
            return band.samples(p[0], p[1], p[2], p[3])
        }
        val upNextShown = findViewById<View>(R.id.upNext).visibility == View.VISIBLE
        val small = if (upNextShown) listOf(headerText2[0], headerText[1], headerText2[1]) else listOf(headerText2[0])
        val ink = headerInk(behind(headerText[0]), *small.map { behind(it) }.toTypedArray())
        showHeader(headerStyle(ink, iconIsLight(behind(wifi)), kit.text, kit.text2), animate)
    }

    /** Paints [to], fading from the colours on show with [animate], in step with the wallpaper's cross-fade. */
    private fun showHeader(to: HeaderStyle, animate: Boolean) {
        if (to == headerTarget) return
        headerTarget = to
        headerFade?.cancel()
        val from = headerPainted
        if (!animate || from == null) return paintHeader(to)
        headerFade = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 700L
            addUpdateListener { paintHeader(from.mix(to, it.animatedFraction)) }
            start()
        }
    }

    private fun paintHeader(s: HeaderStyle) {
        headerPainted = s
        headerText.forEach { it.setTextColor(s.text) }
        headerText2.forEach { it.setTextColor(s.text2) }
        val shadow = Color.alpha(s.shadow) > 0
        for (v in headerText + headerText2) {
            v.setShadowLayer(if (shadow) dp(4).toFloat() else 0f, 0f, if (shadow) dp(1).toFloat() else 0f, s.shadow)
        }
        findViewById<View>(R.id.upNextRule).setBackgroundColor(s.rule)
        headerBlock.background?.alpha = s.plate
        wifiRest = s.icon
        if (!wifi.isFocused) wifi.setColorFilter(s.icon)
    }

    /**
     * The network icon at the top right: Up from Continue Watching reaches it, and OK opens the network settings.
     * Focus fades a white disc in behind it while the icon turns ink, like the tiles.
     */
    private fun setUpNetIcon() {
        val disc = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(kit.focusFill)
            alpha = 0
        }
        wifi.background = disc
        wifi.isFocusable = true
        wifi.isClickable = true
        wifi.setTag(R.id.homeRow, "top")
        wifi.setOnClickListener { openSettings(Settings.ACTION_WIFI_SETTINGS) }
        var running: ValueAnimator? = null
        wifi.setOnFocusChangeListener { v, focused ->
            running?.cancel()
            running = ValueAnimator.ofFloat(disc.alpha / 255f, if (focused) 1f else 0f).apply {
                duration = if (focused) 200L else 160L
                interpolator = EASE
                val colours = ArgbEvaluator()
                addUpdateListener {
                    val f = it.animatedValue as Float
                    disc.alpha = (f * 255).toInt()
                    wifi.setColorFilter(colours.evaluate(f, wifiRest, kit.ink) as Int)
                }
                start()
            }
            v.lift(focused, 1.12f, kit.px(8f))
        }
    }

    /**
     * [advance]: the slideshow moves on to its next photo. While Home is visible the new picture cross-fades
     * in; while it's hidden (an app is open) it simply replaces the old one, ready for the return.
     */
    private fun loadWallpaper(advance: Boolean = false) {
        val view = findViewById<ImageView>(R.id.wallpaper)
        val frost = findViewById<ImageView>(R.id.wallpaperFrost)
        val choice = currentWallpaper()
        val generation = ++wallpaperGeneration
        val mood = currentMood()
        shownMood = mood // on its way: applyMood won't start the same repaint twice
        if (choice.startsWith("plain:")) { // the root's ground colour shows through, and the theme's text suits it
            view.setImageDrawable(null)
            frost.setImageDrawable(null)
            headerBand = null
            updateHeaderLook(animate = false)
            return
        }
        val fallback = defaultWallpaper(dark)
        io.execute {
            val bmp = try {
                decodeWallpaper(choice, advance)
            } catch (e: Exception) {
                android.util.Log.w("Telepath", "wallpaper failed: ${e.javaClass.simpleName}")
                null
            } catch (e: OutOfMemoryError) { // a picture on a TV short of memory: show the default instead of crashing
                null
            } ?: decodeWallpaper(fallback)
            val painted = if (bmp == null || mood == null) bmp else try {
                paintMoodInto(bmp, mood)
            } catch (e: OutOfMemoryError) {
                bmp
            }
            val frosted = painted?.let { frostedCopy(it) }
            val band = painted?.let {
                try {
                    headerBandOf(it)
                } catch (e: OutOfMemoryError) {
                    null
                }
            }
            ui.post {
                if (generation != wallpaperGeneration) return@post
                frost.setImageBitmap(frosted)
                headerBand = band
                val old = view.drawable
                val crossfade = visible && old != null && painted != null
                updateHeaderLook(animate = crossfade)
                if (!crossfade) return@post view.setImageBitmap(painted)
                // Soft change from one photo to the next; then drop the old one so only one stays in memory.
                val fade = TransitionDrawable(arrayOf(old, BitmapDrawable(resources, painted))).apply { isCrossFadeEnabled = true }
                view.setImageDrawable(fade)
                fade.startTransition(700)
                ui.postDelayed({ if (view.drawable === fade) view.setImageBitmap(painted) }, 800)
            }
        }
    }

    private fun decodeWallpaper(choice: String, advance: Boolean = false): Bitmap? {
        BUNDLED_WALLPAPERS[choice.removePrefix("res:")]?.let { res ->
            return BitmapFactory.decodeResource(resources, res)
        }
        val bmp = when {
            choice == SLIDESHOW -> {
                val file = slideshowPhoto(advance) ?: return null
                fitWallpaper { file.inputStream() }
            }
            choice == Bing.CHOICE -> fitWallpaper { Bing.file(this).inputStream() }
            choice.startsWith("folder:") -> {
                val uri = ContentUris.withAppendedId(PHOTOS, folderPhoto(choice) ?: return null)
                fitWallpaper { contentResolver.openInputStream(uri) ?: throw IOException("gone: $uri") }
            }
            else -> {
                val file = File(wallpaperDir(), choice.removePrefix("file:"))
                fitWallpaper { file.inputStream() }
            }
        } ?: return null
        // Bing's pictures are often bright daylight, which fights the night theme: dim them in dark mode.
        if (dark && choice == Bing.CHOICE) Canvas(bmp).drawColor(Color.argb(DARK_DIM, 0, 0, 0))
        return bmp
    }

    /** The slideshow photo on show, or with [advance] the next one of this round's shuffle. io thread only. */
    private fun slideshowPhoto(advance: Boolean): File? {
        val files = slideshowFiles(this)
        if (files.isEmpty()) return null
        val names = files.map { it.name }
        val current = prefs.getString("ss_current", null)
        if (!advance && current != null && current in names) return files.first { it.name == current }
        val deck = prefs.getString("ss_deck", "").orEmpty().split('/').filter { it.isNotEmpty() }
        // Photos the slideshow hasn't seen before. Before it kept track, any of them could be new: all join the round.
        val known = prefs.getString("ss_known", null)?.split('/')?.toSet()
        val added = if (known == null) names else names.filter { it !in known }
        val (next, rest) = nextSlide(names, deck, current, added) { it.shuffled() }
        prefs.edit().putString("ss_current", next).putString("ss_deck", rest.joinToString("/"))
            .putString("ss_known", names.joinToString("/")).apply()
        return files.first { it.name == next }
    }

    /** Wallpapers page, framed by "Add from phone…" first and, once there are photos, "Remove a photo…" last. */
    private fun pickWallpaper(forDark: Boolean) {
        val row = if (forDark) R.string.dark_wallpaper else R.string.light_wallpaper
        val choices = wallpaperChoices(forDark)
        val own = choices.filter { it.second.startsWith("file:") }
        val current = currentWallpaper(forDark)
        val rows = listOf(page(getString(R.string.add_from_phone)) { uploadWallpaper(forDark) }) +
            choices.map { (name, choice) -> page(name, if (choice == current) CHOSEN else "") { setWallpaper(forDark, choice); renderSettings(row) } } +
            if (own.isEmpty()) emptyList() else listOf(page(getString(R.string.remove_photo_row)) { removeWallpaper(own, forDark) })
        showPage(if (forDark) getString(R.string.dark_wallpaper) else getString(R.string.light_wallpaper), rows, 1 + choices.indexOfFirst { it.second == current }) {
            renderSettings(row)
        }
    }

    private fun setWallpaper(forDark: Boolean, choice: String) {
        prefs.edit().putString(if (forDark) "wp_dark" else "wp_light", choice)
            .remove(photoKey(forDark)).putLong(photoAtKey(forDark), System.currentTimeMillis()).apply()
        Bing.dropIfUnused(applicationContext, prefs) // choosing another wallpaper deletes the Bing picture at once
        if (choice == Bing.CHOICE) Bing.refresh(applicationContext, prefs, force = true)
        if (forDark == dark) loadWallpaper()
    }

    private val bingChanged: () -> Unit = { if (currentWallpaper() == Bing.CHOICE) loadWallpaper() }

    // ---------- folder wallpapers ----------

    private var afterPhotoAccess: (() -> Unit)? = null // what asked, while Android's permission prompt is up
    private val deck = ArrayDeque<Long>() // photos not shown yet this round; io thread only

    private fun photoKey(forDark: Boolean = dark) = if (forDark) "wp_photo_dark" else "wp_photo_light"
    private fun photoAtKey(forDark: Boolean = dark) = if (forDark) "wp_photo_at_dark" else "wp_photo_at_light"
    /** The folder's photo on show now, or the next one in this round's shuffle. Runs on the io thread. */
    private fun folderPhoto(choice: String): Long? {
        val ids = folderPhotos(contentResolver, choice)
        val all = ids.toHashSet()
        prefs.getLong(photoKey(), -1).takeIf { it in all }?.let { return it }
        deck.removeAll { it !in all } // deleted, or another folder's
        if (deck.isEmpty()) deck.addAll(ids.shuffled())
        return deck.removeFirstOrNull()?.also { prefs.edit().putLong(photoKey(), it).apply() }
    }

    /** Every folder with photos in it, internal storage and USB drives alike: (bucket id, name, photo count). */
    private fun photoFolders(): List<Triple<String, String, Int>> {
        val found = LinkedHashMap<String, Pair<String, Int>>()
        contentResolver.query(PHOTOS, arrayOf(MediaStore.Images.Media.BUCKET_ID, MediaStore.Images.Media.BUCKET_DISPLAY_NAME),
            null, null, "${MediaStore.Images.Media.BUCKET_DISPLAY_NAME} COLLATE NOCASE")?.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0) ?: continue
                found[id] = (c.getString(1) ?: "Photos") to (found[id]?.second ?: 0) + 1
            }
        }
        return found.map { (id, v) -> Triple(id, v.first, v.second) }
    }

    private fun pickFolder(forDark: Boolean) = pickFolder({ pickWallpaper(forDark) }) { choice ->
        setWallpaper(forDark, choice)
        renderSettings(if (forDark) R.string.dark_wallpaper else R.string.light_wallpaper)
    }

    /** Folders page for wallpapers and the screensaver; [onPick] gets "folder:<bucket id>:<name>". */
    private fun pickFolder(back: () -> Unit, onPick: (String) -> Unit) {
        if (checkSelfPermission(READ_PHOTOS) != PackageManager.PERMISSION_GRANTED) {
            afterPhotoAccess = { pickFolder(back, onPick) }
            return requestPermissions(arrayOf(READ_PHOTOS), MEDIA_REQUEST)
        }
        val folders = photoFolders()
        if (folders.isEmpty()) {
            return Toast.makeText(this, getString(R.string.no_photos), Toast.LENGTH_LONG).show()
        }
        val rows = folders.map { (id, name, n) ->
            page(name, resources.getQuantityString(R.plurals.photo_count, n, n)) { onPick("folder:$id:$name") }
        }
        showPage(getString(R.string.choose_folder), rows, back = back)
    }

    private fun removeWallpaper(own: List<Pair<String, String>>, forDark: Boolean) {
        showPage(getString(R.string.remove_photo_title), own.map { (name, choice) ->
            page(name) {
                showPage(getString(R.string.delete_photo, name), listOf(
                    page(getString(R.string.cancel)) { removeWallpaper(own, forDark) },
                    page(getString(R.string.delete)) {
                        File(wallpaperDir(), choice.removePrefix("file:")).delete()
                        // A theme showing it falls back to its default.
                        prefs.edit().apply {
                            if (prefs.getString("wp_light", null) == choice) remove("wp_light")
                            if (prefs.getString("wp_dark", null) == choice) remove("wp_dark")
                        }.apply()
                        loadWallpaper()
                        pickWallpaper(forDark)
                    },
                )) { removeWallpaper(own, forDark) }
            }
        }) { pickWallpaper(forDark) }
    }

    private var upload: UploadServer? = null

    /**
     * "Add from phone": a QR code for a page served from this TV. The phone posts a photo, we turn it into a
     * wallpaper and show it at once. The server runs only while this panel is open.
     */
    private fun uploadWallpaper(forDark: Boolean) {
        val ip = lanAddress() ?: return Toast.makeText(this, getString(R.string.connect_network), Toast.LENGTH_LONG).show()
        val dir = wallpaperDir() ?: return
        val status = label(getString(R.string.waiting_photo), 14f).apply { gravity = Gravity.CENTER; maxLines = 2 }
        val server = try {
            UploadServer((1000..9999).random().toString(), File(cacheDir, "upload.tmp")) { file ->
                val saved = importWallpaper(file, dir) ?: return@UploadServer null
                ui.post {
                    setWallpaper(forDark, "file:${saved.name}")
                    status.text = getString(R.string.photo_added, saved.nameWithoutExtension)
                }
                saved.nameWithoutExtension
            }
        } catch (e: IOException) {
            return Toast.makeText(this, getString(R.string.upload_failed), Toast.LENGTH_LONG).show()
        }
        upload = server
        val url = "http://$ip:${server.port}/${server.code}"
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(4), 0, dp(12))
            addView(ImageView(this@MainActivity).apply { setImageBitmap(qrBitmap(url, dp(200))) })
            addView(label(url, 14f).apply { gravity = Gravity.CENTER; setPadding(0, dp(12), 0, dp(4)) })
            addView(label(getString(R.string.scan_hint), 12f).apply {
                gravity = Gravity.CENTER
                alpha = 0.6f
                maxLines = 3
            })
            addView(status.apply { setPadding(0, dp(12), 0, 0) })
        }
        showPage(getString(R.string.add_from_your_phone), listOf(page(getString(R.string.done)) { pickWallpaper(forDark) }), top = box) { pickWallpaper(forDark) }
        pageExit = {
            server.stop()
            if (upload === server) upload = null
        }
    }

    /** The TV's own address on the home network (IPv4: easy to read and type). */
    private fun lanAddress(): String? {
        val cm = getSystemService(ConnectivityManager::class.java)
        return cm.getLinkProperties(cm.activeNetwork)?.linkAddresses?.map { it.address }
            ?.firstOrNull { it is java.net.Inet4Address && !it.isLoopbackAddress }?.hostAddress
    }

    /** Black on white with the standard 4-module quiet zone, whole pixels per module so it stays sharp. */
    private fun qrBitmap(text: String, maxPx: Int): Bitmap {
        val m = Qr.encode(text)
        val n = m.size + 8
        val px = maxOf(1, maxPx / n)
        val bmp = Bitmap.createBitmap(n * px, n * px, Bitmap.Config.RGB_565)
        bmp.eraseColor(Color.WHITE)
        val canvas = Canvas(bmp)
        val paint = Paint().apply { color = Color.BLACK }
        for (r in m.indices) for (c in m.indices) if (m[r][c]) {
            canvas.drawRect(((c + 4) * px).toFloat(), ((r + 4) * px).toFloat(), ((c + 5) * px).toFloat(), ((r + 5) * px).toFloat(), paint)
        }
        return bmp
    }

    // ---------- launcher settings panel (top-bar button; the Settings tile is Android's) ----------

    private val settingsScrim by lazy { findViewById<View>(R.id.settingsScrim) }
    private val settingsList by lazy { findViewById<LinearLayout>(R.id.settingsList) }

    private fun openLauncherSettings(at: Int = lastSettings) {
        showPanel()
        renderSettings(at)
    }

    private fun showPanel() {
        if (settingsScrim.visibility != View.VISIBLE) panelFrom = currentFocus
        // Dark: true black, set apart from the home screen by a hairline on its left edge.
        findViewById<View>(R.id.settingsPanel).background = if (!dark) ColorDrawable(0xFFF7F8FA.toInt())
            else LayerDrawable(arrayOf(ColorDrawable(DARK_BG), ColorDrawable(PANEL_EDGE))).apply {
                setLayerGravity(1, Gravity.START or Gravity.FILL_VERTICAL)
                setLayerWidth(1, 1)
            }
        findViewById<TextView>(R.id.settingsTitle).setTextColor(fg)
        settingsScrim.visibility = View.VISIBLE
        homeFocusable(false)
    }

    private fun closeLauncherSettings() {
        pageExit?.invoke()
        pageExit = null
        pageBack = null
        settingsScrim.visibility = View.GONE
        homeFocusable(allApps.visibility != View.VISIBLE) // the PIN can open over All apps
        (panelFrom?.takeIf { it.isAttachedToWindow && it.isShown } ?: firstDockApp())?.requestFocus()
        panelFrom = null
    }

    private var panelFrom: View? = null // focus to go back to when the panel closes

    /**
     * Plume settings, as a short list of sections that each open a page. [at] is the string id of the row to
     * focus: a row inside a section opens that section on it; a section or a top-level row focuses it in the
     * main list. Rows are found by name, so adding one never moves the others.
     */
    private fun renderSettings(at: Int = 0) {
        val theme = prefs.getString("theme", "auto")!!
        val sort = prefs.getString("sort", "manual")!!
        val hidden = prefs.getStringSet("hidden_watch", emptySet())!!.size
        val unit = prefs.getString("wx_unit", "c")!!
        val cwOff = prefs.getStringSet("cw_off", emptySet())!!.count { pkg -> apps.any { it.pkg == pkg } } // uninstalled ones don't count
        val rowHidden = rowHiddenSet().count { pkg -> apps.any { it.pkg == pkg } }
        val mood = moodMode()
        val every = prefs.getInt("ss_every", DEFAULT_PHOTO_SECONDS).let { s -> if (s in PHOTO_SECONDS) s else DEFAULT_PHOTO_SECONDS }
        val clock = prefs.getBoolean("ss_clock", true)
        cityExit = null
        // Rows with more than two values also step back with Left; two-value rows flip either way.
        val steps = mutableMapOf<Int, (Int) -> Unit>(
            R.string.theme to { d ->
                prefs.edit().putString("theme", listOf("auto", "light", "dark").step(theme, d)).putBoolean("reopen_settings", true).apply()
                recreate() // colours are applied once per activity; reopen the panel after
            },
            R.string.ss_every to { d ->
                prefs.edit().putInt("ss_every", PHOTO_SECONDS.step(every, d)).apply()
                renderSettings(R.string.ss_every)
            },
            R.string.mood_colours to { d ->
                prefs.edit().putString("mood", listOf("weather", "time", "off").step(mood, d)).apply()
                applyMood()
                renderSettings(R.string.mood_colours)
            },
            R.string.temperature to { _ ->
                prefs.edit().putString("wx_unit", if (unit == "f") "c" else "f").apply()
                personalDashboard.weatherSettingsChanged()
                renderSettings(R.string.temperature)
            },
        )
        val rows = linkedMapOf<Int, Triple<String, String, () -> Unit>>(
            R.string.theme to Triple(getString(R.string.theme), getString(mapOf("light" to R.string.theme_light, "dark" to R.string.theme_dark, "auto" to R.string.theme_auto)[theme] ?: R.string.theme_auto)) {
                steps[R.string.theme]!!(1)
            },
            R.string.mood_colours to Triple(getString(R.string.mood_colours),
                getString(mapOf("time" to R.string.mood_time, "off" to R.string.off)[mood] ?: R.string.mood_weather)) {
                steps[R.string.mood_colours]!!(1)
            },
            R.string.light_wallpaper to Triple(getString(R.string.light_wallpaper), wallpaperName(currentWallpaper(false))) { pickWallpaper(false) },
            R.string.dark_wallpaper to Triple(getString(R.string.dark_wallpaper), wallpaperName(currentWallpaper(true))) { pickWallpaper(true) },
            R.string.apps_in_row to Triple(getString(R.string.apps_in_row), getString(R.string.n_of_m, apps.size - rowHidden, apps.size)) { pickRowApps() },
            R.string.edit_apps_row to Triple(getString(R.string.edit_apps_row), "") { closeLauncherSettings(); startEdit(grab = null) },
            R.string.sort_apps to Triple(getString(R.string.sort_apps), if (sort == "manual") getString(R.string.manual) else getString(R.string.most_used)) {
                prefs.edit().putString("sort", if (sort == "manual") "usage" else "manual").apply()
                renderApps()
                renderSettings(R.string.sort_apps)
            },
            R.string.cw_apps to Triple(getString(R.string.cw_apps), if (cwOff == 0) getString(R.string.all_on) else getString(R.string.n_off, cwOff)) { pickWatchSources() },
            R.string.cw_sources_status to Triple(getString(R.string.cw_sources_status), "") { showWatchCoverage { renderSettings(R.string.cw_sources_status) } },
            R.string.restore_hidden to Triple(getString(R.string.restore_hidden), if (hidden == 0) getString(R.string.none_hidden) else getString(R.string.n_hidden, hidden)) {
                prefs.edit().remove("hidden_watch").apply()
                watchKey = null
                refreshWatchNext()
                renderSettings(R.string.restore_hidden)
            },
            R.string.weather_location to Triple(getString(R.string.weather_location), weatherPlaceLabel()) { pickCity() },
            R.string.temperature to Triple(getString(R.string.temperature), if (unit == "f") "°F" else "°C") { steps[R.string.temperature]!!(1) },
            R.string.watchlist_symbols to Triple(getString(R.string.watchlist_symbols),
                personalDashboard.symbols().replace(",", ", ").ifEmpty { getString(R.string.off) }) { editSymbols { renderSettings(R.string.watchlist_symbols) } },
            R.string.up_next to Triple(getString(R.string.up_next), getString(if (upNext.isOn()) R.string.on else R.string.off)) { showUpNext() },
            R.string.widget_privacy to Triple(getString(R.string.widget_privacy), "") {
                infoPage(getString(R.string.widget_privacy), emptyList(), getString(R.string.widget_privacy_body) + "\n\n" + getString(R.string.google_home_deferred),
                    null, null) { renderSettings(R.string.widget_privacy) }
            },
            R.string.screensaver to Triple(getString(R.string.screensaver), when {
                downloading -> getString(R.string.downloading_gallery)
                !galleryOn() -> screensaverName()
                else -> screensaverLabel()
            }) { pickScreensaver() },
            R.string.ss_every to Triple(getString(R.string.ss_every), everyText(every)) { steps[R.string.ss_every]!!(1) },
            R.string.ss_clock to Triple(getString(R.string.ss_clock), toggle(clock)) {
                prefs.edit().putBoolean("ss_clock", !clock).apply()
                renderSettings(R.string.ss_clock)
            },
            R.string.test_screensaver to Triple(getString(R.string.test_screensaver), "") { testScreensaver() },
            R.string.screensaver_timing to Triple(getString(R.string.screensaver_timing), "") { openScreensaverSettings() },
            R.string.home_button to Triple(getString(R.string.home_button), toggle(homeServiceOn())) { toggleHomeButton() },
            R.string.default_launcher to Triple(getString(R.string.default_launcher), if (isDefaultHome()) getString(R.string.plume_press_change) else getString(R.string.press_to_set)) { makeDefaultHome() },
            R.string.lock_layout to Triple(getString(R.string.lock_layout), toggle(lockOn())) {
                if (!lockOn()) return@Triple setLock()
                prefs.edit().remove("lock_pin").apply()
                renderSettings(R.string.lock_layout)
            },
        )
        steps[R.string.sort_apps] = { rows[R.string.sort_apps]!!.third() }
        val on = { b: Boolean -> getString(if (b) R.string.on else R.string.off) }
        val sections = listOf(
            Triple(R.string.section_appearance, "${rows[R.string.theme]!!.second} · ${wallpaperName(currentWallpaper(dark))}",
                listOf(R.string.theme, R.string.mood_colours, R.string.light_wallpaper, R.string.dark_wallpaper)),
            Triple(R.string.section_favourites, rows[R.string.apps_in_row]!!.second,
                listOf(R.string.apps_in_row, R.string.edit_apps_row, R.string.sort_apps)),
            Triple(R.string.continue_watching, rows[R.string.cw_apps]!!.second,
                listOf(R.string.cw_apps, R.string.cw_sources_status, R.string.restore_hidden)),
            Triple(R.string.personal_widgets, "${rows[R.string.weather_location]!!.second} · ${rows[R.string.temperature]!!.second}",
                listOf(R.string.weather_location, R.string.temperature, R.string.watchlist_symbols, R.string.up_next, R.string.widget_privacy)),
            Triple(R.string.section_screensaver, rows[R.string.screensaver]!!.second,
                listOf(R.string.screensaver, R.string.ss_every, R.string.ss_clock, R.string.test_screensaver, R.string.screensaver_timing)),
            Triple(R.string.section_home, on(homeServiceOn() || isDefaultHome()), listOf(R.string.home_button, R.string.default_launcher)),
        )
        val section = sections.firstOrNull { at in it.third }
        if (section != null) {
            lastSettings = section.first
            val keys = section.third
            return showPage(getString(section.first), keys.map { rows[it]!! }, keys.indexOf(at), steps = keys.map { steps[it] }) {
                renderSettings(section.first)
            }
        }
        val top = sections.map { (key, value, keys) -> key to page(getString(key), "$value  ›") { renderSettings(keys[0]) } } +
            listOf(R.string.lock_layout).map { it to rows[it]!! } +
            listOfNotNull(if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) null
                else R.string.preview_home_setup to page(getString(R.string.preview_home_setup)) { closeLauncherSettings(); previewHomeSetup() })
        showPage(getString(R.string.plume_settings), top.map { it.second }, maxOf(0, top.indexOfFirst { it.first == at }), back = null)
    }

    /** Widget pages opened from a card close the panel on Back; from settings they return to their section. */
    private var cityExit: (() -> Unit)? = null

    private val dashboardHost = object : DashboardHost {
        override fun openAllApps(from: View) = this@MainActivity.openAllApps(from)
        override fun menu(anchor: View, items: List<Pair<String, () -> Unit>>) = holdMenu(anchor, items)
        override fun openHomeSettings(from: View) = unlock { openLauncherSettings(0) }
        override fun openGoogleTv() = this@MainActivity.openGoogleTv()
        override fun openTvSettings() = openSettings(Settings.ACTION_SETTINGS)
        override fun configureWeather() = unlock {
            showPanel()
            cityExit = { closeLauncherSettings() }
            pickCity()
        }
        override fun configureWatchlist() = unlock {
            showPanel()
            editSymbols { closeLauncherSettings() }
        }
        override fun weatherUpdated() = applyMood()
        override fun showInfo(title: String, rows: List<Pair<String, String>>, note: String?, actionLabel: String?, action: (() -> Unit)?) {
            showPanel()
            infoPage(title, rows, note, actionLabel, action, null)
        }
    }

    /** Google TV's own home screen, left alone by the Home button service until Home is pressed again. */
    private fun openGoogleTv() {
        HomeOverride.stockHomeAllowed = true
        try {
            startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                .setPackage(HomeOverride.GOOGLE_TV).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: android.content.ActivityNotFoundException) {
            HomeOverride.stockHomeAllowed = false
            Toast.makeText(this, getString(R.string.google_tv_unavailable), Toast.LENGTH_LONG).show()
        } catch (e: SecurityException) {
            HomeOverride.stockHomeAllowed = false
            Toast.makeText(this, getString(R.string.google_tv_unavailable), Toast.LENGTH_LONG).show()
        }
    }

    private fun editSymbols(back: () -> Unit) = textPage(getString(R.string.watchlist_symbols), "AAPL, ^GSPC, RELIANCE.NS",
        personalDashboard.symbols().replace(",", ", "), help = getString(R.string.stocks_setup_hint), back = back) { value ->
        personalDashboard.saveSymbols(value)
    }

    /** The fixtures the Up next line picks from, with where they come from, and a switch for the line. */
    private fun showUpNext() {
        val rows = upNext.upcoming().ifEmpty { listOf(getString(R.string.up_next) to getString(R.string.up_next_none)) }
        infoPage(getString(R.string.up_next), rows, upNextNote(),
            getString(if (upNext.isOn()) R.string.turn_off else R.string.turn_on), {
                upNext.setOn(!upNext.isOn())
                showUpNext()
            }) { renderSettings(R.string.up_next) }
    }

    /** What the Up next line follows (upnext.* in telepath.properties), then where its fixtures come from. */
    private fun upNextNote(): String {
        val followed = FOLLOWED_TEAMS.map { it.name } + listOfNotNull(
            getString(R.string.up_next_champions_league).takeIf { BuildConfig.UPNEXT_CHAMPIONS_LEAGUE },
            getString(R.string.up_next_world_cup).takeIf { BuildConfig.UPNEXT_WORLD_CUP },
            getString(R.string.up_next_f1).takeIf { BuildConfig.UPNEXT_F1 },
        )
        val head = if (followed.isEmpty()) getString(R.string.up_next_nothing_followed)
            else getString(R.string.up_next_followed, joinWithAnd(followed, getString(R.string.and_word)))
        return head + "\n\n" + getString(R.string.up_next_sources)
    }

    /** The weather location as the settings row shows it: the city chosen, or "Automatic · " where it was found. */
    private fun weatherPlaceLabel(): String {
        val city = prefs.getString("geo_city", null)
        if (prefs.getBoolean("geo_manual", false)) return city ?: DEFAULT_PLACE?.city.orEmpty()
        return getString(R.string.automatic) + (city?.let { " · $it" } ?: "")
    }

    /** One text box over Save, in the settings panel's own style. */
    private fun textPage(title: String, hint: String, initial: String, help: String, back: () -> Unit, save: (String) -> String?) {
        val faint = Color.argb(120, Color.red(fg), Color.green(fg), Color.blue(fg))
        val field = EditText(this).apply {
            id = View.generateViewId()
            setText(initial)
            setSelection(initial.length)
            this.hint = hint
            textSize = 14f
            setTextColor(fg)
            setHintTextColor(faint)
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            imeOptions = EditorInfo.IME_ACTION_DONE
            setPadding(dp(14), 0, dp(14), 0)
            background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setStroke(dp(1), Color.argb(80, Color.red(fg), Color.green(fg), Color.blue(fg)))
            }
        }
        val status = label("", 12f).apply {
            maxLines = 3
            ellipsize = null
            setPadding(dp(14), dp(8), dp(14), 0)
        }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(4), 0, 0)
            addView(label(help, 12f).apply {
                maxLines = 12
                ellipsize = null
                secondary(0.7f)
                setPadding(dp(14), 0, dp(14), dp(12))
            })
            addView(field, LinearLayout.LayoutParams(MATCH_PARENT, dp(44)))
            addView(status)
        }
        val trySave = {
            val error = save(field.text.toString().trim())
            if (error == null) back() else status.text = error
        }
        field.setOnEditorActionListener { _, _, _ -> trySave(); true }
        showPage(title, listOf(page(getString(R.string.widget_save)) { trySave() }), 0, top = top, back = back)
        settingsList.getChildAt(1).nextFocusUpId = field.id
        field.requestFocus()
    }

    /** Read-only details: label/value pairs, then paragraphs (focusable, so long text scrolls), then actions. */
    private fun infoPage(title: String, rows: List<Pair<String, String>>, note: String?, actionLabel: String?,
                         action: (() -> Unit)?, back: (() -> Unit)?) {
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(2), 0, dp(6))
        }
        for ((name, value) in rows) {
            top.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(7), dp(14), dp(7))
                addView(label(name, 14f))
                addView(label(value, 12f).apply { secondary(0.65f); maxLines = 3; ellipsize = null; setPadding(0, dp(3), 0, 0) })
            })
        }
        var lastText: View? = null
        note?.split("\n\n")?.filter { it.isNotBlank() }?.forEach { paragraph ->
            lastText = label(paragraph, 12f).apply {
                id = View.generateViewId()
                maxLines = Int.MAX_VALUE
                ellipsize = null
                alpha = 0.7f
                isFocusable = true
                setOnFocusChangeListener { v, focused -> v.alpha = if (focused) 1f else 0.7f }
                setPadding(dp(14), dp(8), dp(14), dp(2))
            }
            top.addView(lastText)
        }
        val actions = listOfNotNull(
            actionLabel?.let { name -> page(name) { action?.invoke() } },
            page(getString(R.string.done)) { back?.invoke() ?: closeLauncherSettings() },
        )
        showPage(title, actions, 0, top = top, back = back)
        lastText?.let { text -> settingsList.getChildAt(1).nextFocusUpId = text.id }
    }

    private var lastSettings = 0 // section used last, focused when settings open again

    private fun <T> List<T>.step(current: T, dir: Int) = this[((indexOf(current) + dir) % size + size) % size]

    // Pages inside the settings panel (wallpaper list, app toggles…) instead of system dialogs, so they
    // match Plume's look. Back goes up one page; from the main list it closes the panel.

    private var pageBack: (() -> Unit)? = null
    private var pageExit: (() -> Unit)? = null // cleanup when the page goes away (stops the upload server)

    private fun page(title: String, value: String = "", action: () -> Unit) = Triple(title, value, action)

    private fun showPage(title: String, rows: List<Triple<String, String, () -> Unit>>, focus: Int = 0, top: View? = null,
                         steps: List<((Int) -> Unit)?> = emptyList(), back: (() -> Unit)?) {
        pageExit?.invoke()
        pageExit = null
        pageBack = back
        findViewById<TextView>(R.id.settingsTitle).text = title
        settingsList.removeAllViews()
        top?.let { settingsList.addView(it) }
        val first = settingsList.childCount
        rows.forEachIndexed { i, (name, value, action) ->
            settingsList.addView(settingsRow(name, value, steps.getOrNull(i), action), LinearLayout.LayoutParams(MATCH_PARENT, dp(44)).apply {
                topMargin = dp(4)
            })
        }
        // Focus wraps: Up from the first row goes to the last, Down from the last to the first.
        (settingsList.parent as View).scrollTo(0, 0)
        if (rows.isEmpty()) return // a page that's all [top], like the PIN boxes, focuses itself
        val firstRow = settingsList.getChildAt(first)
        val lastRow = settingsList.getChildAt(first + rows.size - 1)
        firstRow.nextFocusUpId = lastRow.id
        lastRow.nextFocusDownId = firstRow.id
        settingsList.getChildAt(first + focus.coerceIn(0, rows.size - 1)).requestFocus()
    }

    // ---------- layout lock ----------
    // A PIN in front of Plume settings and everything that changes the home screen; apps still open as usual.
    // It keeps the layout safe from small hands, it doesn't keep apps from kids: voice search and Android
    // settings get past it, so it's called a layout lock, not parental controls.

    private fun lockOn() = prefs.contains("lock_pin")

    private var unlockedUntil = 0L // one PIN covers a few minutes of changes, until the home screen is left
    private var pinFails = 0
    private var pinWaitUntil = 0L // ponytail: in memory, so restarting Plume resets the wait; fine for kids at a remote
    private var pinBox: View? = null

    private fun locked() = lockOn() && SystemClock.elapsedRealtime() > unlockedUntil

    /** Runs [then] at once when unlocked, otherwise after the right PIN. */
    private fun unlock(then: () -> Unit) {
        if (!locked()) return then()
        showPanel()
        pinPage(getString(R.string.enter_pin), back = null) { pin ->
            val now = SystemClock.elapsedRealtime()
            if (now < pinWaitUntil) return@pinPage getString(R.string.too_many_tries, (pinWaitUntil - now) / 1000 + 1)
            if (!pinMatches(prefs.getString("lock_pin", null)!!, pin)) {
                if (++pinFails < PIN_TRIES) return@pinPage getString(R.string.wrong_pin)
                pinFails = 0
                pinWaitUntil = now + 30_000L
                return@pinPage getString(R.string.too_many_tries, 30)
            }
            pinFails = 0
            unlockedUntil = now + UNLOCKED_MS
            closeLauncherSettings()
            then()
            null
        }
    }

    private fun setLock(): Unit = pinPage(getString(R.string.choose_pin), { renderSettings(R.string.lock_layout) }) { first ->
        pinPage(getString(R.string.enter_again), { renderSettings(R.string.lock_layout) }) { again ->
            if (again != first) {
                Toast.makeText(this, getString(R.string.pins_mismatch), Toast.LENGTH_LONG).show()
                setLock()
                return@pinPage null
            }
            prefs.edit().putString("lock_pin", hashPin(first)).apply()
            unlockedUntil = SystemClock.elapsedRealtime() + UNLOCKED_MS // no PIN straight away for the one who set it
            renderSettings(R.string.lock_layout)
            Toast.makeText(this, getString(R.string.layout_locked), Toast.LENGTH_LONG).show()
            null
        }
        null
    }

    /**
     * Four digit boxes: ▲ ▼ change a digit, ◀ ▶ move between boxes, number keys type, OK enters.
     * [done] gets the PIN and returns null when it's accepted, or a message to show before trying again.
     */
    private fun pinPage(title: String, back: (() -> Unit)?, done: (String) -> String?) {
        val digits = arrayOfNulls<Int>(4)
        var at = 0
        val boxes = List(4) { label("", 24f).apply { gravity = Gravity.CENTER } }
        val status = label("", 13f).apply { gravity = Gravity.CENTER; alpha = 0.6f }
        val row = LinearLayout(this).apply {
            id = View.generateViewId()
            gravity = Gravity.CENTER
            isFocusable = true
            boxes.forEach { addView(it, LinearLayout.LayoutParams(dp(48), dp(56)).apply { marginStart = dp(6); marginEnd = dp(6) }) }
        }
        fun draw() = boxes.forEachIndexed { i, b ->
            // Only the box being changed shows its digit.
            b.text = if (digits[i] == null) "" else if (i == at) digits[i].toString() else "•"
            b.background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(Color.WHITE)
                if (i == at) setStroke(dp(2), fg)
            }
            if (dark) b.background = rimCard(dp(8), i == at)
        }
        fun submit() {
            val error = done(digits.joinToString("")) ?: return
            digits.fill(null)
            at = 0
            draw()
            status.text = error
            ObjectAnimator.ofFloat(row, View.TRANSLATION_X, 0f, -dp(12).toFloat(), dp(12).toFloat(), -dp(8).toFloat(), dp(8).toFloat(), 0f).setDuration(300).start()
        }
        row.setOnKeyListener { _, code, e ->
            val digit = when (code) {
                in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> code - KeyEvent.KEYCODE_0
                in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 -> code - KeyEvent.KEYCODE_NUMPAD_0
                else -> null
            }
            if (digit == null && code !in PIN_KEYS) return@setOnKeyListener false // Back still leaves
            if (e.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener true
            when (code) {
                KeyEvent.KEYCODE_DPAD_UP -> digits[at] = ((digits[at] ?: 0) + 1) % 10
                KeyEvent.KEYCODE_DPAD_DOWN -> digits[at] = ((digits[at] ?: 0) + 9) % 10
                KeyEvent.KEYCODE_DPAD_LEFT -> at = maxOf(0, at - 1)
                KeyEvent.KEYCODE_DPAD_RIGHT -> if (digits[at] != null) at = minOf(3, at + 1)
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER ->
                    if (digits.all { it != null }) return@setOnKeyListener true.also { submit() }
                    else if (digits[at] != null) at = minOf(3, at + 1)
                else -> {
                    digits[at] = digit
                    if (at == 3) return@setOnKeyListener true.also { draw(); submit() }
                    at++
                }
            }
            draw()
            true
        }
        draw()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(16), 0, dp(8))
            addView(row)
            addView(status.apply { setPadding(0, dp(12), 0, 0) })
            addView(label(getString(R.string.pin_hint), 12f).apply {
                maxLines = 2
                gravity = Gravity.CENTER
                alpha = 0.6f
                setPadding(0, dp(8), 0, 0)
            })
            if (lockOn()) addView(label(getString(R.string.pin_forgot), 12f).apply {
                gravity = Gravity.CENTER
                alpha = 0.6f
                maxLines = 3
                setPadding(0, dp(24), 0, 0)
            })
        }
        showPage(title, emptyList(), top = box, back = back)
        pinBox = row
        row.requestFocus()
    }

    /** A settings row; with [step], Left and Right change its value in place, and ‹ › show that while focused. */
    private fun settingsRow(title: String, value: String, step: ((Int) -> Unit)? = null, action: () -> Unit): View {
        val bg = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0, 0)).apply { cornerRadius = dp(8).toFloat() }
        return LinearLayout(this).apply {
            id = View.generateViewId()
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), 0, dp(14), 0)
            background = bg
            isFocusable = true
            isClickable = true
            val name = label(title, 14f).apply { if (dark) setTextColor(DARK_TEXT_2) }
            addView(name, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            val shown = if (value == TOGGLE_ON || value == TOGGLE_OFF) null else label(value, 13f).apply { secondary(0.6f) }
            if (shown == null) addView(switch(value == TOGGLE_ON), LinearLayout.LayoutParams(dp(36), dp(20))) else addView(shown)
            setOnClickListener { action() }
            setOnFocusChangeListener { _, focused ->
                // Focus: a lit rimCard in dark (and the title brightens), a white pill in light.
                if (dark) background = if (focused) rimCard(dp(8), true) else bg
                else bg.colors = if (focused) intArrayOf(Color.WHITE, Color.WHITE) else intArrayOf(0, 0)
                // The title glides to bright rather than snapping, so moving through the list feels smooth.
                if (dark) ValueAnimator.ofArgb(name.currentTextColor, if (focused) DARK_TEXT else DARK_TEXT_2).apply {
                    duration = 160
                    interpolator = DECELERATE
                    addUpdateListener { name.setTextColor(it.animatedValue as Int) }
                }.start()
                if (step != null) shown?.text = if (focused) "‹  $value  ›" else value
            }
            tag = step // dispatchKeyEvent calls it for Left (-1) and Right (+1)
        }
    }

    private fun toggle(on: Boolean) = if (on) TOGGLE_ON else TOGGLE_OFF

    /** A mono switch for on/off rows: filled track with the knob right when on, outlined and left when off. */
    private fun switch(on: Boolean) = FrameLayout(this).apply {
        val faint = fg and 0xFFFFFF or 0x66000000
        background = GradientDrawable().apply {
            cornerRadius = dp(10).toFloat()
            if (on) setColor(fg) else setStroke(dp(2), faint)
        }
        addView(View(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (!on) faint else if (dark) DARK_BG else Color.WHITE)
            }
        }, FrameLayout.LayoutParams(dp(14), dp(14), Gravity.CENTER_VERTICAL or if (on) Gravity.END else Gravity.START).apply {
            marginStart = dp(3)
            marginEnd = dp(3)
        })
    }

    private fun showNet(caps: NetworkCapabilities?) {
        val ethernet = caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true
        wifi.setImageResource(if (ethernet) R.drawable.ic_ethernet else R.drawable.ic_wifi)
        // Dim only the icon: the focus ring is the background and should stay full strength.
        wifi.imageAlpha = if (caps == null) 90 else 255
    }

    private fun openSettings(action: String, data: Uri? = null) {
        try {
            startActivity(Intent(action, data))
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }

    // ---------- apps row ----------

    /** Labels and icons come from each app's resources, ~200 ms on this TV: done off the UI thread. */
    private val tiles by lazy { AppTiles(applicationContext) }

    /**
     * The apps as the last home screen had them, so a rebuilt one (a theme switch) shows them at once. Tiles differ by
     * theme: after a switch each app's tile for the new theme is read from the cache (made ahead with the other one).
     */
    private fun keepApps() {
        val kept = LOADED ?: return
        val light = !dark
        if (kept.light == light) {
            apps = kept.apps
            appsDirty = !kept.complete
            return
        }
        var complete = true
        apps = kept.apps.map { a ->
            val key = a.tile ?: return@map a
            val (tile, both) = tiles.cached(key, light)
            if (!both) complete = false
            val art = tile ?: plainTile(a, light)
            a.withIcon(art?.let { BitmapDrawable(applicationContext.resources, it) } ?: ColorDrawable(Color.TRANSPARENT))
        }
        LOADED = Loaded(apps, light, complete)
        appsDirty = !complete
    }

    /** [a]'s plain tile (its icon on the theme's plate), for the moments before its own tiles are made. */
    private fun plainTile(a: App, light: Boolean): Bitmap? = try {
        a.launch.component?.let { tiles.plain(packageManager.getActivityInfo(it, 0), light) }
    } catch (e: PackageManager.NameNotFoundException) {
        null
    }

    /**
     * Apps with their tiles from the cache; tiles not made yet (new or updated apps, or a new group or tile choice)
     * are made after the dock shows, with each app's plain icon tile meanwhile, then the dock is filled in again.
     * Tiles of apps since uninstalled are cleared away.
     */
    private fun refreshApps() {
        appsDirty = false
        val light = !dark
        io.execute {
            val (list, missing) = try {
                loadApps(light)
            } catch (e: Exception) {
                return@execute
            }
            ui.post {
                if (isDestroyed) return@post
                apps = list
                LOADED = Loaded(list, light, missing.isEmpty())
                renderApps()
                refreshAllApps()
            }
            for (p in missing) {
                try {
                    tiles.make(p.info, p.key, p.style)
                } catch (e: RuntimeException) {
                    android.util.Log.w("Telepath", "tile for ${p.info.packageName} failed: ${e.javaClass.simpleName}")
                }
            }
            val again = if (missing.isEmpty()) list else try {
                loadApps(light).first
            } catch (e: Exception) {
                return@execute
            }
            val cleared = tiles.prune(again.mapNotNull { it.tile })
            if (cleared > 0) android.util.Log.i("Telepath", "Tiles: $cleared old files cleared; ${again.size} apps")
            if (missing.isEmpty()) return@execute
            ui.post {
                if (isDestroyed) return@post
                apps = again
                LOADED = Loaded(again, light, true) // tried: an app whose icon can't even be read isn't retried each time
                renderApps()
                refreshAllApps()
            }
        }
    }

    /**
     * The launchable apps with their tiles for the [light] theme or the dark one, and the apps that still need tiles
     * made (they show their plain icon tile meanwhile if it's this theme's that's missing). Each app's group comes from
     * its own manifest (category, home-screen rows, whether it came with the TV), unless you moved it.
     */
    private fun loadApps(light: Boolean): Pair<List<App>, List<Pending>> {
        val pm = packageManager
        val missing = ArrayList<Pending>()
        val plainIcons = prefs.getStringSet("tile_icon", emptySet())!!
        val (chosenStreaming, chosenUtilities) = chosenGroups()
        val list = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0)
            .map { it.activityInfo }
            .filter { it.packageName != packageName && it.packageName != TV_SETTINGS }
            .distinctBy { it.packageName }
            .mapNotNull { a ->
                val pkg = a.packageName
                val info = try {
                    pm.getPackageInfo(pkg, PackageManager.GET_PERMISSIONS)
                } catch (e: PackageManager.NameNotFoundException) {
                    return@mapNotNull null // uninstalled meanwhile
                }
                val flags = a.applicationInfo.flags
                val streaming = isStreamingApp(AppTraits(pkg, a.applicationInfo.category, info.requestedPermissions?.contains(WRITE_EPG) == true,
                    system = flags and ApplicationInfo.FLAG_SYSTEM != 0, updated = flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0))
                val style = tileStyle(pkg, inStreaming(pkg, streaming, chosenStreaming, chosenUtilities), pkg in plainIcons)
                val key = tiles.key(info, style)
                val (tile, both) = tiles.cached(key, light)
                if (!both) missing += Pending(a, key, style)
                App(
                    pkg,
                    REBRANDS[pkg]?.label ?: a.loadLabel(pm).toString(),
                    Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                        .setComponent(ComponentName(pkg, a.name)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    (tile ?: tiles.plain(a, light))?.let { BitmapDrawable(applicationContext.resources, it) } ?: ColorDrawable(Color.TRANSPARENT),
                    streaming = streaming,
                    tile = key,
                )
            }
        return list to missing
    }

    /** The apps you moved in All apps: (to Streaming, to Apps & utilities). */
    private fun chosenGroups() =
        prefs.getStringSet("group_streaming", emptySet())!! to prefs.getStringSet("group_utilities", emptySet())!!

    /** True if [a] shows under Streaming: where you moved it, else its automatic group. */
    private fun groupOf(a: App) = chosenGroups().let { (s, u) -> inStreaming(a.pkg, a.streaming, s, u) }

    /**
     * One tile per HDMI port, keyed by the port's input id. A device that names itself over CEC (the PS5 shows up
     * as "PlayStation 5") takes over its port's tile; it's listed while asleep too, and opening the tile wakes it.
     * A port without one is a tile named as in TV settings ("HDMI 2"), in the row only while it has a signal.
     */
    private fun hdmiInputs(): List<App> = try {
        val tv = getSystemService(TvInputManager::class.java)
        val inputs = tv.tvInputList.filter { it.type == TvInputInfo.TYPE_HDMI && !it.isHidden(this) }
        val devices = inputs.filter { it.parentId != null }
        val audio = devices.filter { isAudioDevice(it.loadLabel(this).toString()) }.map { it.parentId }.toSet()
        // Devices first, so distinctBy keeps the device over its port.
        (devices + inputs.filter { it.parentId == null }).filter { (it.parentId ?: it.id) !in audio }.map { input ->
            val port = input.parentId ?: input.id
            val named = input.parentId != null
            val label = (if (named) input.loadLabel(this) else input.loadCustomLabel(this) ?: input.loadLabel(this)).toString()
            App(
                HDMI + port,
                label,
                Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(port)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                getDrawable(HDMI_ICONS[hdmiIcon(label, named)]!!)!!,
                offline = !named && tv.getInputState(port) != TvInputManager.INPUT_STATE_CONNECTED,
            )
        }.distinctBy { it.pkg }
    } catch (e: Exception) {
        emptyList()
    }

    /** Inputs live in the Inputs widget now; only it needs to hear about them. */
    private val inputCallback = object : TvInputManager.TvInputCallback() {
        override fun onInputAdded(id: String) = personalDashboard.inputsChanged()
        override fun onInputRemoved(id: String) = personalDashboard.inputsChanged()
        override fun onInputStateChanged(id: String, state: Int) = personalDashboard.inputsChanged()
    }

    /** HDMI ports pinned to the row by hand: shown with or without a signal (some TVs never report one). */
    private fun pinnedInputs() = prefs.getStringSet("inputs_pinned", emptySet())!!

    private fun sortedApps(): List<App> {
        // Streaming apps first by default, then alphabetical; manual order or usage overrides.
        val byDefault = compareBy<App> { DEFAULT_ORDER.indexOf(it.pkg).let { i -> if (i < 0) Int.MAX_VALUE else i } }
            .thenBy { it.label.lowercase() }
        if (prefs.getString("sort", "manual") == "usage") {
            return apps.sortedWith(compareByDescending<App> { prefs.getInt("n_" + it.pkg, 0) }.then(byDefault))
        }
        val order = prefs.getString("order", "")!!.split(',')
        // A console not yet placed by hand goes first, so it's visible without scrolling.
        return apps.sortedWith(compareBy<App> {
            order.indexOf(it.pkg).let { i -> if (i >= 0) i else if (it.pkg.startsWith(HDMI)) -1 else Int.MAX_VALUE }
        }.then(byDefault))
    }

    /**
     * The favourites dock: up to eight banners across the frosted glass, the row scrolling for more. Banners are
     * 16:9 art that fills the whole tile, so every favourite reads at the same size. All apps comes first, a square as
     * tall as the banners, one press Left of the first favourite (where Home lands), as on Android TV.
     */
    private fun renderApps() {
        val focusedTag = currentFocus?.tag
        // Rebuilds can happen in the background (an app installed); only take focus if the dock had it. The first
        // fill takes it too, so Home opens on the first favourite rather than on Continue Watching.
        val takeFocus = currentFocus == null || currentFocus?.parent === appRow || appRow.childCount == 0
        val gap = dp(12)
        val inner = resources.displayMetrics.widthPixels - dp(80) - dp(24)
        // Eight banners and the All apps square, with a gap after each but the last, fill the glass.
        val width = minOf(dp(104), ((inner - gap * ROW_TILES) / (ROW_TILES + 9f / 16f)).toInt())
        val square = width * 9 / 16
        appRow.removeAllViews()
        val hidden = rowHiddenSet()
        val tiles = sortedApps().filter { it.pkg !in hidden }.map { bannerTile(it, kit.px(10f)) }
        // Clipping cut the focused tile's shadow off square at the dock's edge. Clip only when the row scrolls (more
        // favourites than fit), and then to the dock's own rounded glass, so the shadow fades out naturally.
        val scroll = findViewById<View>(R.id.appScroll)
        val overflow = square + tiles.size * (width + gap) > inner
        scroll.outlineProvider = if (!overflow) ViewOutlineProvider.BOUNDS else object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) = outline.setRoundRect(0, 0, view.width, view.height, kit.px(26f))
        }
        scroll.clipToOutline = overflow
        // Centred while the favourites fit; from the left once they don't, or the centred row's first tile would sit
        // partly off the left edge, out of the scroll's reach (this TV doesn't left-align a too-wide row by itself).
        (appRow.layoutParams as FrameLayout.LayoutParams).let { lp ->
            val gravity = if (overflow) Gravity.START else Gravity.CENTER_HORIZONTAL
            if (lp.gravity != gravity) {
                lp.gravity = gravity
                appRow.layoutParams = lp
            }
        }
        if (!overflow) scroll.scrollTo(0, 0)
        val all = allAppsTile(kit.px(10f))
        for (t in listOf<View>(all) + tiles) {
            t.id = View.generateViewId()
            t.setTag(R.id.homeRow, "dock")
            appRow.addView(t, if (t === all) LinearLayout.LayoutParams(square, square)
            else LinearLayout.LayoutParams(width, width * 9 / 16).apply { marginStart = gap })
            val own = t.onFocusChangeListener
            t.setOnFocusChangeListener { v, focused ->
                own?.onFocusChange(v, focused)
                if (focused) lastApp = v
            }
        }
        if (takeFocus) (if (focusedTag == ALL_APPS) all else tiles.firstOrNull { it.tag == focusedTag } ?: tiles.firstOrNull() ?: all).requestFocus()
        if (tiles.isNotEmpty() && !prefs.getBoolean("tip_move", false)) {
            prefs.edit().putBoolean("tip_move", true).apply()
            Toast.makeText(this, getString(R.string.tip_move), Toast.LENGTH_LONG).show()
        }
    }

    /** All apps in the dock: the grid symbol on the dock's tile colour, lifting on focus like the banners beside it. */
    private fun allAppsTile(radius: Float): FrameLayout = FrameLayout(this).apply {
        background = GradientDrawable().apply {
            cornerRadius = radius
            setColor(if (dark) 0xFF1B2027.toInt() else 0xFFE4E7EB.toInt())
        }
        addView(ImageView(this@MainActivity).apply {
            setImageResource(R.drawable.ic_apps)
            setColorFilter(kit.text)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }, FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
        tag = ALL_APPS
        contentDescription = getString(R.string.all_apps)
        focusArt(this, kit, radius / resources.displayMetrics.density, 1.14f)
        setOnClickListener { openAllApps(this) }
        setOnLongClickListener { true } // nothing to change about it
    }

    /** The dock's app tiles, without its All apps tile. */
    private fun dockApps(): List<View> = (0 until appRow.childCount).map { appRow.getChildAt(it) }.filter { it.tag != ALL_APPS }

    /** The first favourite, where Home and returning to the dock land; All apps is one press Left of it. */
    private fun firstDockApp(): View? = dockApps().firstOrNull() ?: appRow.getChildAt(0)

    /** Where the favourites start in the dock: after All apps. */
    private fun firstAppIndex() = if (appRow.getChildAt(0)?.tag == ALL_APPS) 1 else 0

    /** An app's tile edge to edge in a rounded 16:9 frame; focus lights a rim and lifts it. Used by the dock and All apps. */
    private fun bannerTile(a: App, radius: Float): FrameLayout {
        val art = ImageView(this).apply {
            setImageDrawable(customIcon(a.pkg) ?: a.icon.constantState?.newDrawable(resources) ?: a.icon)
            scaleType = ImageView.ScaleType.CENTER_CROP
        }
        return FrameLayout(this).apply {
            background = GradientDrawable().apply {
                cornerRadius = radius
                setColor(if (dark) 0xFF1B2027.toInt() else 0xFFE4E7EB.toInt()) // shows until a new app's tiles are made
            }
            addView(art, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            tag = a.pkg
            contentDescription = a.label
            focusArt(this, kit, radius / resources.displayMetrics.density, 1.14f)
            setOnClickListener { launch(a) }
            setOnLongClickListener { if (!holdFired) fireHold(this); true }
        }
    }


    /** Optional per-theme icons: assets/icons/<package>_dark.png, <package>_light.png, or <package>.png. */
    private fun customIcon(pkg: String): Drawable? =
        listOf("icons/${pkg}_${if (dark) "dark" else "light"}.png", "icons/$pkg.png").firstNotNullOfOrNull { path ->
            try {
                assets.open(path).use { Drawable.createFromStream(it, null) }
            } catch (e: IOException) {
                null
            }
        }

    // ---------- holding OK, and editing the apps row ----------

    /**
     * Hold detection lives here, not in View long-click: this TV's remote doesn't reliably trigger the
     * framework's long-press timer. We treat the first auto-repeat of OK as "held"; the long-click
     * listeners on tiles and cards are a fallback for remotes that send no repeats. [holdFired] stops both firing.
     */
    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        if (allMoving != null && allApps.visibility == View.VISIBLE && allAppsMoveKey(e)) return true
        val confirm = e.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || e.keyCode == KeyEvent.KEYCODE_ENTER ||
            e.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
        if (confirm) {
            if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0) holdFired = false
            if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount > 0) {
                if (!holdFired) currentFocus?.let { fireHold(it) }
                return true
            }
            if (e.action == KeyEvent.ACTION_UP && holdFired) {
                currentFocus?.isPressed = false
                return true
            }
        }
        // The settings panel is a vertical list: Left/Right step a row's value (its tag, see settingsRow) and
        // never leak focus to the home screen behind it. The PIN boxes and the city search box use them themselves.
        if (settingsScrim.visibility == View.VISIBLE && currentFocus !== pinBox && currentFocus !is EditText &&
            (e.keyCode == KeyEvent.KEYCODE_DPAD_LEFT || e.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT)
        ) {
            @Suppress("UNCHECKED_CAST")
            if (e.action == KeyEvent.ACTION_DOWN) (currentFocus?.tag as? (Int) -> Unit)?.invoke(if (e.keyCode == KeyEvent.KEYCODE_DPAD_LEFT) -1 else 1)
            return true
        }
        if (editing && editKey(e, confirm)) return true
        if (e.action == KeyEvent.ACTION_DOWN && settingsScrim.visibility != View.VISIBLE && allApps.visibility != View.VISIBLE) {
            currentFocus?.let { homeNav(it, e.keyCode) }?.let { it.requestFocus(); return true }
        }
        return super.dispatchKeyEvent(e)
    }

    /**
     * Home rows, top to bottom: Continue Watching, the favourites dock, Inputs and Shortcuts, then Weather and
     * Watchlist. Left/Right stay inside a row and stop at its ends; Up/Down move between rows and
     * land where you last were in that row, else on whatever sits nearest across. Returns the view to focus
     * (itself = stay), or null to let Android move focus (the scrolling rows snap themselves).
     */
    private fun homeNav(v: View, key: Int): View? {
        val row = v.getTag(R.id.homeRow) as? String ?: return null
        when (key) {
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                val views = rowViews(row)
                val i = views.indexOf(v)
                val j = if (key == KeyEvent.KEYCODE_DPAD_LEFT) i - 1 else i + 1
                return when {
                    i < 0 -> null
                    j !in views.indices -> v
                    row == "cw" || row == "dock" -> null
                    else -> views[j]
                }
            }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                val step = if (key == KeyEvent.KEYCODE_DPAD_UP) -1 else 1
                var at = HOME_ROWS.indexOf(row) + step
                while (at in HOME_ROWS.indices) {
                    val views = rowViews(HOME_ROWS[at])
                    if (views.isNotEmpty()) {
                        // Straight back the way you came lands where you came from; otherwise the nearest across.
                        val back = trail?.takeIf { it.first === v && it.second in views && it.second.isAttachedToWindow }?.second
                        val toConsole = row == "dock" && HOME_ROWS[at] == "tiles" && (v === firstDockApp() || v.tag == ALL_APPS)
                        val target = back ?: if (toConsole) views.first() else nearest(views, v)
                        trail = target to v
                        return target
                    }
                    at += step
                }
                return v
            }
        }
        return null
    }

    private fun rowViews(row: String): List<View> = when (row) {
        "cw" -> if (watchScroll.visibility == View.VISIBLE) {
            (0 until watchRow.childCount).mapNotNull { (watchRow.getChildAt(it) as? ViewGroup)?.getChildAt(0) }
        } else {
            listOfNotNull(findViewById<View>(R.id.watchEmpty).takeIf { it.visibility == View.VISIBLE })
        }
        "top" -> listOf<View>(wifi).filter { it.isShown }
        "dock" -> (0 until appRow.childCount).map { appRow.getChildAt(it) }
        else -> personalDashboard.rowViews(row)
    }

    /** The view in [views] whose centre is horizontally closest to [from]'s, as seen on screen. */
    private fun nearest(views: List<View>, from: View): View {
        val at = IntArray(2)
        from.getLocationOnScreen(at)
        val x = at[0] + from.width / 2
        return views.minByOrNull { view ->
            val p = IntArray(2)
            view.getLocationOnScreen(p)
            kotlin.math.abs(p[0] + view.width / 2 - x)
        } ?: views.first()
    }

    /** Overlays (All apps, launcher settings) sit on top of the home rows; keep focus from slipping behind them. */
    private fun homeFocusable(on: Boolean) {
        findViewById<ViewGroup>(R.id.content).descendantFocusability =
            if (on) ViewGroup.FOCUS_AFTER_DESCENDANTS else ViewGroup.FOCUS_BLOCK_DESCENDANTS
    }

    private fun fireHold(v: View) {
        holdFired = true
        v.cancelLongPress()
        v.isPressed = false
        onHold(v)
    }

    /** What holding OK does depends on what's focused. */
    private fun onHold(v: View) {
        val tag = v.tag as? String
        when {
            allApps.visibility == View.VISIBLE && tag != null -> allAppsMenu(v)
            v.tag == ALL_APPS -> Unit // nothing to change about All apps
            v.parent === appRow -> appMenu(v)
            v.getTag(R.id.watchRow) is Prog -> watchMenu(v, v.getTag(R.id.watchRow) as Prog)
            else -> personalDashboard.hold(v)
        }
    }

    /** Pins [tag] to the front of the favourites (unhiding it), and shows it there. */
    private fun pinToRow(tag: String) {
        val edit = prefs.edit().putStringSet("row_hidden", rowHiddenSet() - tag)
            .putStringSet("row_added", rowAddedSet() + tag)
        if (tag.startsWith(HDMI)) edit.putStringSet("inputs_pinned", pinnedInputs() + tag)
        edit.apply()
        saveOrder(listOf(tag) + sortedApps().map { it.pkg }.filter { it != tag })
        closeAllApps()
        firstDockApp()?.requestFocus() // show the app just pinned
    }

    /**
     * Hold OK in All apps: move it within its group, add the app to favourites (or remove it), move it to the other group, use its plain icon as
     * its tile (or the standard one again), App info. Locked: Open only.
     */
    private fun allAppsMenu(v: View) {
        val pkg = v.tag as String
        val app = apps.find { it.pkg == pkg } ?: return
        if (locked()) return holdMenu(v, listOf(
            getString(R.string.open) to { v.performClick(); Unit },
            getString(R.string.unlock_to_edit) to { unlock { v.requestFocus(); allAppsMenu(v) } },
        ))
        val streaming = groupOf(app)
        val plainIcon = pkg in plainIcons()
        holdMenu(v, listOfNotNull(
            getString(R.string.move) to { startAllAppsMove(pkg) },
            if (pkg in rowHiddenSet()) getString(R.string.add_to_row) to { pinToRow(pkg) }
            else getString(R.string.remove_from_row) to {
                prefs.edit().putStringSet("row_hidden", rowHiddenSet() + pkg).putStringSet("row_added", rowAddedSet() - pkg)
                    .putStringSet("inputs_pinned", pinnedInputs() - pkg).apply()
                renderApps()
                Toast.makeText(this, getString(R.string.removed_from_row), Toast.LENGTH_SHORT).show()
            },
            getString(if (streaming) R.string.move_to_utilities else R.string.move_to_streaming) to { moveApp(app, !streaming) },
            getString(if (plainIcon) R.string.use_standard_tile else R.string.use_app_icon) to { useAppIcon(pkg, !plainIcon) },
            if (pkg.startsWith(HDMI)) null else getString(R.string.app_info) to {
                openSettings(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", pkg, null))
            },
        ))
    }

    /**
     * Shows [a] under Streaming or Apps & utilities from now on, also after it's reinstalled. Its tiles are made again
     * if the group changes their style (logos for streaming apps, symbols for apps & utilities).
     */
    private fun moveApp(a: App, streaming: Boolean) {
        val (s, u) = chosenGroups()
        val (toStreaming, toUtilities) = moveGroup(a.pkg, streaming, a.streaming, s, u)
        prefs.edit().putStringSet("group_streaming", toStreaming).putStringSet("group_utilities", toUtilities).apply()
        Toast.makeText(this, getString(if (streaming) R.string.moved_to_streaming else R.string.moved_to_utilities), Toast.LENGTH_SHORT).show()
        refreshApps()
    }

    private fun plainIcons() = prefs.getStringSet("tile_icon", emptySet())!!

    /** [pkg]'s tiles become its plain app icon on the theme's plate ([on]), or its standard logo or symbol tile again. */
    private fun useAppIcon(pkg: String, on: Boolean) {
        prefs.edit().putStringSet("tile_icon", if (on) plainIcons() + pkg else plainIcons() - pkg).apply()
        refreshApps()
    }

    /** Hold OK on a row app: Move (OK places it), Remove from row (stays in All apps), its tile, App info. Locked: Open only. */
    private fun appMenu(v: View) {
        val pkg = v.tag as String
        if (locked()) return holdMenu(v, listOf(
            getString(R.string.open) to { v.performClick(); Unit },
            getString(R.string.unlock_to_edit) to { unlock { v.requestFocus(); appMenu(v) } },
        ))
        val plainIcon = pkg in plainIcons()
        holdMenu(v, listOfNotNull(
            getString(R.string.move) to { startEdit(grab = v, once = true) },
            getString(R.string.remove_from_row) to {
                val i = appRow.indexOfChild(v)
                prefs.edit().putStringSet("row_hidden", rowHiddenSet() + pkg)
                    .putStringSet("row_added", rowAddedSet() - pkg)
                    .putStringSet("inputs_pinned", pinnedInputs() - pkg).apply()
                renderApps()
                appRow.getChildAt(minOf(i, appRow.childCount - 1))?.requestFocus()
                Toast.makeText(this, getString(R.string.removed_from_row), Toast.LENGTH_SHORT).show()
            },
            if (pkg.startsWith(HDMI)) null else getString(if (plainIcon) R.string.use_standard_tile else R.string.use_app_icon) to {
                useAppIcon(pkg, !plainIcon)
            },
            if (pkg.startsWith(HDMI)) null else getString(R.string.app_info) to {
                openSettings(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", pkg, null))
            },
        ))
    }

    /** Hold OK on a Continue Watching card: hide the show, or open its app rather than the episode. */
    private fun watchMenu(v: View, p: Prog) {
        holdMenu(v, listOfNotNull(
            if (locked()) null else getString(R.string.remove_from_cw) to { hideWatch(p) },
            if (!locked()) null else getString(R.string.unlock_to_edit) to { unlock { v.requestFocus(); watchMenu(v, p) } },
            p.pkg?.let { pkg ->
                getString(R.string.open_app) to {
                    (packageManager.getLeanbackLaunchIntentForPackage(pkg) ?: packageManager.getLaunchIntentForPackage(pkg))
                        ?.let { startActivity(it) }
                    Unit
                }
            },
        ))
    }

    /** A small Plume-styled menu just above [anchor]; Back closes it. */
    private fun holdMenu(anchor: View, items: List<Pair<String, () -> Unit>>) {
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), dp(6), dp(6), dp(6))
            background = if (dark) rimCard(dp(12), false) else GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(0xFFF7F8FA.toInt())
            }
            elevation = if (dark) 0f else dp(8).toFloat()
        }
        val menu = PopupWindow(list, LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT, true)
        items.forEachIndexed { i, (name, action) ->
            list.addView(settingsRow(name, "") { menu.dismiss(); action() }, LinearLayout.LayoutParams(MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                if (i > 0) topMargin = dp(2)
            })
            list.getChildAt(i).minimumHeight = dp(40)
        }
        // As wide as the longest item, never narrower than the tile or card it opens from.
        // A very long translation wraps onto two lines instead of stretching across the screen.
        list.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        menu.width = list.measuredWidth.coerceIn(anchor.width, maxOf(anchor.width, dp(360)))
        list.measure(View.MeasureSpec.makeMeasureSpec(menu.width, View.MeasureSpec.EXACTLY), View.MeasureSpec.UNSPECIFIED)
        val at = IntArray(2).also { anchor.getLocationInWindow(it) }
        menu.elevation = if (dark) 0f else dp(8).toFloat()
        // Over the anchor's left edge, but kept inside the row's 48 dp side margin near the right of the screen.
        val x = minOf(at[0], resources.displayMetrics.widthPixels - dp(48) - menu.width)
        menu.showAtLocation(anchor, Gravity.NO_GRAVITY, x, maxOf(dp(8), at[1] - list.measuredHeight - dp(8)))
        list.getChildAt(0).requestFocus()
    }

    private fun appTileCount() = appRow.childCount

    private var moveOnce = false // moving one app from the hold menu: OK places it and editing ends

    private fun startEdit(grab: View?, once: Boolean = false) {
        editing = true
        moveOnce = once
        appsTitle.text = if (once) getString(R.string.moving_hint) else getString(R.string.editing_hint)
        appsTitle.visibility = View.VISIBLE
        (grab ?: firstDockApp())?.requestFocus()
        setGrabbed(grab)
    }

    private fun setGrabbed(v: View?) {
        grabbed?.alpha = 1f
        grabbed = v
        v?.alpha = 0.6f
    }

    private fun endEdit() {
        setGrabbed(null)
        editing = false
        moveOnce = false
        appsTitle.visibility = View.GONE
        val row = dockApps().map { it.tag as String }
        saveOrder(row + sortedApps().map { it.pkg }.filter { it !in row })
    }

    /** While editing, keys only rearrange the row; nothing launches and focus can't leave it. */
    private fun editKey(e: KeyEvent, confirm: Boolean): Boolean {
        if (e.keyCode == KeyEvent.KEYCODE_BACK) {
            if (e.action == KeyEvent.ACTION_UP) endEdit()
            return true
        }
        val v = currentFocus ?: return false
        val i = appRow.indexOfChild(v)
        if (confirm) {
            if (e.action == KeyEvent.ACTION_UP && i in firstAppIndex() until appTileCount()) {
                if (moveOnce) endEdit() else setGrabbed(if (grabbed === v) null else v)
            }
            return true
        }
        if (e.action != KeyEvent.ACTION_DOWN) return e.keyCode in EDIT_KEYS
        when (e.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> return true
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                val j = if (e.keyCode == KeyEvent.KEYCODE_DPAD_LEFT) i - 1 else i + 1
                if (j !in firstAppIndex() until appTileCount()) return true
                if (grabbed === v) {
                    // Swap layout params too, so the first tile keeps no leading margin.
                    val other = appRow.getChildAt(j)
                    val lp = v.layoutParams
                    v.layoutParams = other.layoutParams
                    other.layoutParams = lp
                    appRow.removeViewAt(i)
                    appRow.addView(v, j)
                    v.requestFocus()
                    return true
                }
                return false // not holding anything: normal focus movement within the row
            }
        }
        return false
    }

    /** Only the eight personal favourites and explicitly added apps appear; new installs stay in All apps. */
    private fun rowAddedSet() = prefs.getStringSet("row_added", emptySet())!!
    private fun rowHiddenSet() = hiddenHomeApps(apps.map { it.pkg }, prefs.getStringSet("row_hidden", emptySet())!!, rowAddedSet())

    private fun pickRowApps(focus: Int = 0) {
        // An HDMI port without a signal can't show in the row, so it's pinned from All apps instead.
        val list = sortedApps().filter { !it.offline || it.pkg in pinnedInputs() }
        val hidden = rowHiddenSet()
        showPage(getString(R.string.apps_in_row), list.mapIndexed { i, app ->
            page(app.label, getString(if (app.pkg in hidden) R.string.hidden else R.string.shown)) {
                val show = app.pkg in hidden
                prefs.edit().putStringSet("row_hidden", if (show) hidden - app.pkg else hidden + app.pkg)
                    .putStringSet("row_added", if (show) rowAddedSet() + app.pkg else rowAddedSet() - app.pkg).apply()
                renderApps()
                pickRowApps(i)
            }
        }, focus, back = { renderSettings(R.string.apps_in_row) })
    }

    /** A page of on/off rows saved as the set of keys that are off; [done] runs on Back. */
    private fun toggles(title: String, items: List<Pair<String, String>>, pref: String, on: String, off: String, focus: Int = 0, done: () -> Unit) {
        val offSet = prefs.getStringSet(pref, emptySet())!!
        showPage(title, items.mapIndexed { i, (name, key) ->
            page(name, if (key in offSet) off else on) {
                prefs.edit().putStringSet(pref, if (key in offSet) offSet - key else offSet + key).apply()
                toggles(title, items, pref, on, off, i, done)
            }
        }, focus, back = done)
    }

    private fun saveOrder(pkgs: List<String>) {
        prefs.edit().putString("order", pkgs.joinToString(",")).putString("sort", "manual").apply()
    }

    private fun launch(a: App) {
        prefs.edit().putInt("n_" + a.pkg, prefs.getInt("n_" + a.pkg, 0) + 1).putLong("t_" + a.pkg, System.currentTimeMillis()).apply()
        if (prefs.getString("sort", "manual") == "usage") appsDirty = true
        try {
            startActivity(a.launch)
        } catch (e: Exception) {
            packageManager.getLeanbackLaunchIntentForPackage(a.pkg)?.let { startActivity(it) }
        }
    }

    // ---------- all apps ----------

    /**
     * Every app as a tile with its name under it, over the frosted wallpaper: streaming apps first, then apps and
     * utilities, each under its own heading. Hold OK for favourites, the app's group and its tile. [focus]: rebuilding
     * while open (apps installed, moved or retiled), that app keeps the focus and the list keeps its place.
     */
    private fun openAllApps(from: View? = null, focus: String? = null) {
        allAppsFocus?.let { allApps.removeCallbacks(it) }
        allAppsFocus = null
        allAppsFrom = from ?: currentFocus
        val keepY = allApps.scrollY
        allAppsSections.removeAllViews()
        val cols = ALL_APPS_COLUMNS
        val gap = dp(18)
        val width = (resources.displayMetrics.widthPixels - dp(80) - gap * (cols - 1)) / cols
        val (streaming, other) = allAppsSorted().partition { groupOf(it) }
        var first: View? = null
        var target: View? = null
        for ((heading, group) in listOf(R.string.apps_streaming to streaming, R.string.apps_utilities to other)) {
            if (group.isEmpty()) continue
            allAppsSections.addView(label(getString(heading), 13f).apply {
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(kit.text2)
                setPadding(dp(2), if (allAppsSections.childCount > 0) dp(8) else 0, 0, dp(12))
            })
            val grid = GridLayout(this).apply {
                columnCount = cols
                clipChildren = false
                clipToPadding = false
            }
            group.forEachIndexed { i, a ->
                val tile = bannerTile(a, kit.px(10f)) // holding OK here opens allAppsMenu (see onHold)
                if (a.pkg == allMoving) tile.alpha = 0.6f
                if (first == null) first = tile
                if (a.pkg == focus) target = tile
                val name = label(a.label, 12f).apply {
                    gravity = Gravity.CENTER
                    setTextColor(kit.text2)
                }
                val own = tile.onFocusChangeListener
                tile.setOnFocusChangeListener { v, focused ->
                    own?.onFocusChange(v, focused)
                    name.setTextColor(if (focused) kit.text else kit.text2)
                    if (focused) revealInAllApps(v.parent as View)
                }
                grid.addView(LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    clipChildren = false
                    addView(tile, LinearLayout.LayoutParams(width, width * 9 / 16))
                    addView(name, LinearLayout.LayoutParams(width, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(9) })
                }, GridLayout.LayoutParams().apply {
                    this.width = width
                    setMargins(0, 0, if (i % cols == cols - 1) 0 else gap, dp(20))
                })
            }
            allAppsSections.addView(grid)
        }
        allApps.visibility = View.VISIBLE
        homeScroll.visibility = View.INVISIBLE
        findViewById<View>(R.id.wallpaperFrost).alpha = 1f
        homeFocusable(false)
        if (focus == null) {
            allApps.scrollTo(0, 0)
            first?.requestFocus()
        } else {
            // After layout, so the list can go back to where it was before the app is revealed. A rebuild before then
            // (a held arrow while moving an app) replaces this one: its tiles are gone by then.
            val land = Runnable {
                allAppsFocus = null
                allApps.scrollTo(0, keepY)
                (target ?: first)?.takeIf { it.isAttachedToWindow }?.requestFocus()
            }
            allAppsFocus = land
            allApps.post(land)
        }
    }

    /** All apps open while the apps changed (installed, removed, moved, retiled): built again, the same app focused. */
    private fun refreshAllApps() {
        if (allApps.visibility != View.VISIBLE) return
        openAllApps(allAppsFrom, focus = (currentFocus?.tag as? String) ?: "")
    }

    /**
     * All apps' order: as you arranged it with Move (or as it's being arranged), else the dock's. Apps you haven't
     * placed (new installs) go to the end of their group.
     */
    private fun allAppsSorted(): List<App> {
        val defaults = sortedApps()
        val saved = allMovingOrder ?: prefs.getString("all_order", "")!!.split(',').filter { it.isNotEmpty() }
        val byPkg = defaults.associateBy { it.pkg }
        return orderAllApps(defaults.map { it.pkg }, saved).mapNotNull { byPkg[it] }
    }

    /** Hold OK > Move in All apps: the arrows move [pkg] within its group, OK or Back places it. */
    private fun startAllAppsMove(pkg: String) {
        allMovingOrder = allAppsSorted().map { it.pkg }
        allMoving = pkg
        findViewById<TextView>(R.id.allAppsHint).text = getString(R.string.moving_hint_grid)
        openAllApps(allAppsFrom, focus = pkg)
    }

    /** Moves the app being moved [step] places in its group: ±1 along a row, ±a row's length up or down. */
    private fun moveInAllApps(step: Int) {
        val pkg = allMoving ?: return
        val order = allMovingOrder ?: return
        val byPkg = apps.associateBy { it.pkg }
        val streaming = byPkg[pkg]?.let { groupOf(it) } ?: return endAllAppsMove()
        val (mine, others) = order.partition { p -> byPkg[p]?.let { groupOf(it) } == streaming }
        val moved = moveInGroup(mine, pkg, step)
        if (moved == mine) return
        allMovingOrder = if (streaming) moved + others else others + moved
        openAllApps(allAppsFrom, focus = pkg)
    }

    /** Places the app being moved: All apps keeps this order from now on. [refresh]: show it in place (All apps is open). */
    private fun endAllAppsMove(refresh: Boolean = true) {
        val order = allMovingOrder
        val moved = allMoving
        allMoving = null
        allMovingOrder = null
        moved?.let { allAppsSections.findViewWithTag<View>(it)?.alpha = 1f }
        if (order != null) prefs.edit().putString("all_order", order.joinToString(",")).apply()
        findViewById<TextView>(R.id.allAppsHint).text = getString(R.string.all_apps_hint)
        if (refresh) refreshAllApps()
    }

    /** While moving an app in All apps the arrows move it, and OK or Back places it; Home works as usual. */
    private fun allAppsMoveKey(e: KeyEvent): Boolean {
        val step = when (e.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> -1
            KeyEvent.KEYCODE_DPAD_RIGHT -> 1
            KeyEvent.KEYCODE_DPAD_UP -> -ALL_APPS_COLUMNS
            KeyEvent.KEYCODE_DPAD_DOWN -> ALL_APPS_COLUMNS
            else -> 0
        }
        if (step != 0) {
            if (e.action == KeyEvent.ACTION_DOWN && (e.repeatCount == 0 || allAppsFocus == null)) moveInAllApps(step)
            return true
        }
        if (e.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || e.keyCode == KeyEvent.KEYCODE_ENTER ||
            e.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER || e.keyCode == KeyEvent.KEYCODE_BACK
        ) {
            if (e.action == KeyEvent.ACTION_UP) endAllAppsMove()
            return true
        }
        return false
    }

    /**
     * All apps scrolls so the focused app's whole cell shows, name included, with room above for a section heading:
     * left alone, the list only made the tile itself visible, leaving the name below the screen's edge.
     */
    private fun revealInAllApps(cell: View) {
        if (!cell.isAttachedToWindow) return
        val scroller = allApps as ScrollView
        val r = Rect(0, 0, cell.width, cell.height)
        scroller.offsetDescendantRectToMyCoords(cell, r)
        val top = r.top - dp(52)
        val bottom = r.bottom + dp(24)
        val y = when {
            bottom > scroller.scrollY + scroller.height -> bottom - scroller.height
            top < scroller.scrollY -> maxOf(0, top)
            else -> return
        }
        scroller.smoothScrollTo(0, y)
    }

    private fun closeAllApps() {
        if (allMoving != null) endAllAppsMove(refresh = false)
        allAppsFocus?.let { allApps.removeCallbacks(it) }
        allAppsFocus = null
        val fromDockTile = allAppsFrom?.tag == ALL_APPS
        allApps.visibility = View.GONE
        homeScroll.visibility = View.VISIBLE
        findViewById<View>(R.id.wallpaperFrost).alpha = if (homeScroll.page > 0) 1f else 0f
        homeFocusable(true)
        renderApps()
        // Back where it was opened from; the dock's All apps tile was made again, so find the new one.
        val back = if (fromDockTile) appRow.getChildAt(0) else allAppsFrom?.takeIf { it.isAttachedToWindow }
        (back ?: firstDockApp())?.requestFocus()
        allAppsFrom = null
    }

    // ---------- screensaver ----------
    // telepath's screensaver on: remember whatever was set (nothing on this TV, so Android used Google TV's Ambient
    // mode) and switch to ours; Google TV can take the setting back, so ensureScreensaver keeps it while telepath is
    // Home. Google TV Ambient mode chosen here: put the remembered one back and stop keeping ours. Needs the
    // adb-granted WRITE_SECURE_SETTINGS, like the Home service.

    private fun gallery() = ComponentName(this, GalleryDream::class.java).flattenToString()

    private fun currentScreensaver() = Settings.Secure.getString(contentResolver, "screensaver_components").orEmpty()

    /** Android uses the first screensaver listed. */
    private fun galleryOn() = currentScreensaver().substringBefore(',') == gallery()

    /** A screensaver's name; none chosen means Android's default, Google TV's Ambient mode on this TV. */
    private fun screensaverName(component: String = currentScreensaver()): String {
        if (Settings.Secure.getInt(contentResolver, "screensaver_enabled", 1) == 0) return getString(R.string.off)
        val chosen = ComponentName.unflattenFromString(component.substringBefore(','))
            ?: ComponentName.unflattenFromString(Settings.Secure.getString(contentResolver, "screensaver_default_component").orEmpty())
            ?: return getString(R.string.system_default)
        if (chosen.packageName == AMBIENT_MODE) return getString(R.string.google_ambient)
        return try {
            packageManager.getServiceInfo(chosen, 0).loadLabel(packageManager).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            getString(R.string.system_default)
        }
    }

    /** What telepath's screensaver shows, by name. */
    private fun screensaverLabel() = when (screensaverSource(prefs)) {
        "slideshow" -> getString(R.string.ss_slideshow)
        "folder" -> photoScreensaver()?.let { wallpaperName(it) } ?: getString(R.string.your_photos)
        else -> getString(R.string.gallery_paintings)
    }

    /** "30 seconds", "1 minute"… for Change photo every. */
    private fun everyText(seconds: Int) =
        if (seconds < 60) getString(R.string.ss_seconds, seconds) else resources.getQuantityString(R.plurals.minutes_n, seconds / 60, seconds / 60)

    /** The photo folder the screensaver shows when "folder" is chosen. */
    private fun photoScreensaver() = prefs.getString("ss_folder", null)

    private fun pickScreensaver(focus: Int = 0) {
        val on = galleryOn()
        val source = screensaverSource(prefs)
        val photos = slideshowFiles(this).size
        val other = if (on) prefs.getString("prev_screensaver", "").orEmpty() else currentScreensaver()
        val tick = { s: String -> if (on && source == s) " $CHOSEN" else "" }
        showPage(getString(R.string.screensaver), listOf(
            page(getString(R.string.ss_slideshow),
                (if (photos == 0) getString(R.string.ss_no_photos) else resources.getQuantityString(R.plurals.photo_count, photos, photos)) + tick("slideshow")) {
                useGallery("slideshow", null)
                pickScreensaver(0)
            },
            page(getString(R.string.gallery_paintings), if (on && source == "paintings") CHOSEN else if (downloading) getString(R.string.downloading) else PAINTINGS_MB) {
                useGallery("paintings", null)
                pickScreensaver(1)
            },
            page(getString(R.string.your_photos), if (on && source == "folder") (photoScreensaver()?.let { wallpaperName(it) }.orEmpty() + tick("folder")) else "") {
                pickFolder({ pickScreensaver(2) }) { choice ->
                    useGallery("folder", choice)
                    pickScreensaver(2)
                }
            },
            page(screensaverName(other), if (on) "" else CHOSEN) {
                if (on) stopGallery()
                pickScreensaver(3)
            },
        ), focus) { renderSettings(R.string.screensaver) }
    }

    /** telepath's screensaver on, showing [source] ("slideshow", "paintings", downloaded first if needed, or "folder"). */
    private fun useGallery(source: String, folder: String?) {
        prefs.edit().putString("ss_source", source).putString("ss_folder", folder).putBoolean("ss_mine", true).apply()
        if (source == "paintings" && !paintingsReady(this)) return downloadPaintings { useGallery("paintings", null) }
        if (source != "paintings") paintingsDir(this).deleteRecursively() // not needed: give the space back
        if (galleryOn()) return // already the screensaver: only what it shows changed
        if (checkSelfPermission(WRITE_SECURE) != PackageManager.PERMISSION_GRANTED) {
            // Without the adb permission it can't be switched from here; Android's own list names it telepath.
            Toast.makeText(this, getString(R.string.choose_gallery_hint), Toast.LENGTH_LONG).show()
            return openScreensaverSettings()
        }
        prefs.edit().putString("prev_screensaver", currentScreensaver()).apply()
        Settings.Secure.putString(contentResolver, "screensaver_components", gallery())
        Settings.Secure.putInt(contentResolver, "screensaver_enabled", 1)
    }

    /** Back to the screensaver that was set before, and stop keeping telepath's. */
    private fun stopGallery() {
        if (checkSelfPermission(WRITE_SECURE) != PackageManager.PERMISSION_GRANTED) return openScreensaverSettings()
        prefs.edit().putBoolean("ss_mine", false).apply()
        // Nothing was set before: clear it again, and Android goes back to its default (Google TV's Ambient mode).
        Settings.Secure.putString(contentResolver, "screensaver_components", prefs.getString("prev_screensaver", "").orEmpty().ifEmpty { null })
        paintingsDir(this).deleteRecursively() // another screensaver is in charge: give the space back
    }

    /**
     * Keeps telepath's screensaver set while it's chosen in Home settings and telepath is Home: Google TV can put its
     * own back (its Ambient mode settings, an update). Choosing Google TV Ambient mode in Home settings stops this.
     */
    private fun ensureScreensaver() {
        if (!prefs.getBoolean("ss_mine", false) || galleryOn()) return
        if (checkSelfPermission(WRITE_SECURE) != PackageManager.PERMISSION_GRANTED || !(isDefaultHome() || homeServiceOn())) return
        android.util.Log.i("Telepath", "Screensaver was '${currentScreensaver()}': switched back to telepath's")
        Settings.Secure.putString(contentResolver, "screensaver_components", gallery())
        Settings.Secure.putInt(contentResolver, "screensaver_enabled", 1)
    }

    /** Android's screensaver settings (timing, sleep): plain Android TV and Google TV name them differently. */
    private fun openScreensaverSettings() {
        for (action in listOf(Settings.ACTION_DREAM_SETTINGS, "com.google.android.tv.settings.ambient")) {
            try {
                return startActivity(Intent(action))
            } catch (e: Exception) {
            }
        }
        openSettings(Settings.ACTION_SETTINGS)
    }

    @Volatile private var downloading = false

    /**
     * Fetches the Gallery paintings into a temporary folder and only swaps it in once every file arrived,
     * so a dropped connection never leaves a half-filled Gallery behind.
     */
    private fun downloadPaintings(then: () -> Unit) {
        if (downloading) return
        downloading = true
        Toast.makeText(this, getString(R.string.downloading_paintings, PAINTINGS_MB), Toast.LENGTH_SHORT).show()
        Thread {
            val part = File(filesDir, "paintings.part").apply { deleteRecursively(); mkdirs() }
            val ok = try {
                for (f in PAINTING_FILES) openStream(PAINTINGS_URL + f).use { input ->
                    File(part, f).outputStream().use { input.copyTo(it) }
                }
                paintingsDir(this).deleteRecursively()
                part.renameTo(paintingsDir(this))
            } catch (e: Exception) {
                android.util.Log.w("Plume", "paintings download failed ($e)")
                part.deleteRecursively()
                false
            }
            ui.post {
                downloading = false
                if (ok) then() else Toast.makeText(this, getString(R.string.paintings_failed), Toast.LENGTH_LONG).show()
                if (settingsScrim.visibility == View.VISIBLE) renderSettings(R.string.screensaver)
            }
        }.start()
    }

    /**
     * Shows the screensaver now. telepath's runs in a full-screen preview (this TV has no "dream now" entry point);
     * another one through SystemUI's, where the TV has one.
     */
    private fun testScreensaver() {
        if (galleryOn()) return startActivity(Intent(this, ScreensaverPreview::class.java))
        try {
            startActivity(Intent(Intent.ACTION_MAIN).setClassName("com.android.systemui", "com.android.systemui.Somnambulator"))
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.cant_dream), Toast.LENGTH_SHORT).show()
        }
    }

    // ---------- updates (GitHub releases) ----------
    // Checked once a day from the home screen; a new version is announced once, then waits in settings.
    // The APK streams straight into an install session, so there's no temporary file to clean up.

    @Volatile private var checking = false
    @Volatile private var updating = false

    private fun myVersion() = packageManager.getPackageInfo(packageName, 0).versionName.orEmpty()

    /** The newer version a check found, if any (null again once it's installed). */
    private fun availableUpdate() = prefs.getString("upd_ver", null)?.takeIf { newerVersion(it, myVersion()) }

    private fun checkUpdate(force: Boolean) {
        if (force) Toast.makeText(this, getString(R.string.no_self_update), Toast.LENGTH_LONG).show()
    }

    /** What's new in this version (the CHANGELOG.md section the build put in), with check or install on top. */
    private fun showUpdates() {
        prefs.edit().putString("seen_notes", myVersion()).apply()
        updateBadge()
        val ver = availableUpdate()
        showPage(getString(R.string.whats_new_title, myVersion()), listOf(
            if (ver != null) page(getString(R.string.install_update, ver)) { installUpdate(ver) }
            else page(getString(R.string.check_updates)) { checkUpdate(force = true) },
        ), back = { renderSettings(R.string.updates) })
        // Focusable, lit on focus, so Down walks through notes longer than the panel and it scrolls along.
        getString(R.string.whats_new).split('\n').forEach {
            settingsList.addView(label("", 13f).apply {
                text = SpannableString(it).apply { setSpan(BulletSpan(dp(10), fg), 0, it.length, 0) }
                maxLines = Int.MAX_VALUE
                ellipsize = null
                alpha = 0.7f
                isFocusable = true
                setOnFocusChangeListener { v, focused -> v.alpha = if (focused) 1f else 0.7f }
                setPadding(dp(14), dp(10), dp(14), dp(2))
            })
        }
        settingsList.getChildAt(0).nextFocusDownId = View.NO_ID // showPage stops Down at its last row
        settingsList.getChildAt(settingsList.childCount - 1).let { it.id = View.generateViewId(); it.nextFocusDownId = it.id }
    }

    /** Notes the user hasn't opened since updating. A fresh install has nothing new to tell. */
    private fun notesUnseen(): Boolean {
        val info = packageManager.getPackageInfo(packageName, 0)
        return info.firstInstallTime != info.lastUpdateTime && prefs.getString("seen_notes", null) != info.versionName
    }

    private val badge by lazy { GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(BADGE) } }

    /** telepath doesn't update itself (each build is its owner's), so there is no settings icon to badge. */
    private fun updateBadge() = Unit

    private fun installUpdate(ver: String) {
        if (updating) return
        if (!packageManager.canRequestPackageInstalls()) {
            Toast.makeText(this, getString(R.string.allow_installs), Toast.LENGTH_LONG).show()
            return openSettings(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))
        }
        updating = true
        renderSettings(R.string.updates)
        val installer = packageManager.packageInstaller
        io.execute {
            val ok = try {
                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                params.setAppPackageName(packageName)
                // Android 12+ can skip the confirm box when we're the app's installer; otherwise Android asks.
                if (Build.VERSION.SDK_INT >= 31) params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
                val id = installer.createSession(params)
                installer.openSession(id).use { session ->
                    try {
                        openStream("https://github.com/$REPO/releases/download/v$ver/Plume.apk").use { input ->
                            session.openWrite("Plume.apk", 0, -1).use { out ->
                                input.copyTo(out)
                                session.fsync(out)
                            }
                        }
                    } catch (e: Exception) {
                        session.abandon()
                        throw e
                    }
                    val done = PendingIntent.getActivity(this, 0,
                        Intent(this, MainActivity::class.java).setAction(UPDATE_STATUS),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
                    session.commit(done.intentSender)
                }
                true
            } catch (e: Exception) {
                android.util.Log.w("Plume", "update download failed ($e)")
                false
            }
            ui.post {
                if (!ok) {
                    updating = false
                    Toast.makeText(this, getString(R.string.update_download_failed), Toast.LENGTH_LONG).show()
                }
                if (settingsScrim.visibility == View.VISIBLE) renderSettings(R.string.updates)
            }
        }
    }

    /** The install session reports back here: show Android's confirm box, or say why it failed. */
    private fun installStatus(intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)?.let { startActivity(it) }
            return
        }
        updating = false
        if (status != PackageInstaller.STATUS_SUCCESS) {
            val why = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
            android.util.Log.w("Plume", "update install failed: $status $why")
            if (status != PackageInstaller.STATUS_FAILURE_ABORTED) {
                Toast.makeText(this, getString(R.string.update_install_failed), Toast.LENGTH_LONG).show()
            }
        }
        if (settingsScrim.visibility == View.VISIBLE) renderSettings(R.string.updates)
    }

    // ---------- weather location (the Weather widget fetches and shows the forecast) ----------

    // An IP's location is wherever the provider routes it, sometimes a whole region off (Rome showing as
    // Sicily), so you can pick the city instead. Open-Meteo's geocoding, also free and keyless.

    private class City(val name: String, val where: String, val lat: Float, val lon: Float)

    /** Automatic first, then the search box; a search lists what it found under them. */
    private fun pickCity(query: String = "", found: List<City>? = null, status: String = "") {
        val field = EditText(this).apply {
            id = View.generateViewId()
            setText(query)
            setSelection(query.length)
            hint = getString(R.string.city_hint)
            textSize = 14f
            setTextColor(fg)
            setHintTextColor(Color.argb(120, Color.red(fg), Color.green(fg), Color.blue(fg)))
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setPadding(dp(14), 0, dp(14), 0)
            background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setStroke(dp(1), Color.argb(80, Color.red(fg), Color.green(fg), Color.blue(fg)))
            }
            setOnEditorActionListener { v, _, _ -> searchCity(v.text.toString().trim()); true }
        }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(4), 0, 0)
            addView(field, LinearLayout.LayoutParams(MATCH_PARENT, dp(44)))
            if (status.isNotEmpty()) addView(label(status, 12f).apply { alpha = 0.6f; setPadding(dp(14), dp(8), 0, 0) })
        }
        val manual = prefs.getBoolean("geo_manual", false)
        val city = prefs.getString("geo_city", null)
        val fixed = listOf(page(getString(R.string.automatic_ip), if (manual) "" else CHOSEN) { setAutomaticPlace() }) +
            listOfNotNull(DEFAULT_PLACE?.let { p ->
                page(p.city, if (manual && city == p.city && prefs.getFloat("geo_lat", Float.NaN) == p.lat) CHOSEN else "") {
                    setCity(p.city, p.lat, p.lon)
                }
            })
        val rows = fixed + found.orEmpty().map { c -> page(c.name, c.where) { setCity(c.name, c.lat, c.lon) } }
        showPage(getString(R.string.weather_location), rows, if (found.isNullOrEmpty()) 0 else fixed.size, top = top,
            back = cityExit ?: { renderSettings(R.string.weather_location) })
        // showPage pins Up on the first row to itself; here Up goes to the search box.
        settingsList.getChildAt(1).nextFocusUpId = field.id
        if (found.isNullOrEmpty()) field.requestFocus()
        // Results in: put the keyboard away, it would cover them.
        else getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(field.windowToken, 0)
    }

    /** The weather location; with the automatic theme it also sets where sunrise and sunset are reckoned. */
    private fun setCity(name: String, lat: Float, lon: Float) {
        prefs.edit().putBoolean("geo_manual", true).putString("geo_city", name)
            .putFloat("geo_lat", lat).putFloat("geo_lon", lon).apply()
        personalDashboard.weatherSettingsChanged()
        (cityExit ?: { renderSettings(R.string.weather_location) })()
    }

    /** Back to the automatic weather location: looked up again from the TV's internet address, with its weather. */
    private fun setAutomaticPlace() {
        prefs.edit().remove("geo_manual").remove("geo_time").apply()
        personalDashboard.relocate()
        (cityExit ?: { renderSettings(R.string.weather_location) })()
    }

    private fun searchCity(query: String) {
        if (query.isEmpty()) return
        pickCity(query, status = getString(R.string.searching))
        io.execute {
            val found = try {
                val r = getJson("https://geocoding-api.open-meteo.com/v1/search?count=8&format=json" +
                    "&language=${java.util.Locale.getDefault().language}&name=${URLEncoder.encode(query, "UTF-8")}")
                val list = r.optJSONArray("results")
                (0 until (list?.length() ?: 0)).map { i ->
                    val c = list!!.getJSONObject(i)
                    City(c.getString("name"), listOf(c.optString("admin1"), c.optString("country")).filter { it.isNotEmpty() }.joinToString(", "),
                        c.getDouble("latitude").toFloat(), c.getDouble("longitude").toFloat())
                }
            } catch (e: Exception) {
                null
            }
            ui.post {
                // Only if the page is still open: Back may have left it while the search ran.
                if (settingsScrim.visibility != View.VISIBLE || findViewById<TextView>(R.id.settingsTitle).text.toString() != getString(R.string.weather_location)) return@post
                when {
                    found == null -> pickCity(query, status = getString(R.string.search_failed))
                    found.isEmpty() -> pickCity(query, status = getString(R.string.no_places, query))
                    else -> pickCity(query, found)
                }
            }
        }
    }

    private fun getJson(url: String) = JSONObject(openStream(url).use { it.readBytes().decodeToString() })

    // ---------- continue watching (Android TV Watch Next) ----------

    private fun refreshWatchNext() {
        if (checkSelfPermission(TV_LISTINGS) != PackageManager.PERMISSION_GRANTED) {
            showWatchNext(emptyList())
            showWatchError(getString(R.string.cw_permission))
            return
        }
        val eligible = watchApps()
        io.execute {
            val list = try {
                queryWatchNext(eligible)
            } catch (e: SecurityException) {
                return@execute watchQueryFailed(e)
            } catch (e: SQLException) {
                return@execute watchQueryFailed(e)
            } catch (e: IllegalArgumentException) {
                return@execute watchQueryFailed(e)
            }
            ui.post {
                watchProblem = null
                findViewById<View>(R.id.watchStatus).visibility = View.GONE
                findViewById<TextView>(R.id.watchEmpty).setText(R.string.cw_empty)
                showWatchNext(list)
            }
        }
    }

    private fun watchQueryFailed(error: RuntimeException) {
        android.util.Log.w("AsterHome", "Continue Watching provider query failed", error)
        ui.post {
            if (watchKey == null) showWatchNext(emptyList())
            showWatchError(getString(R.string.cw_read_error))
        }
    }

    private fun showWatchError(message: String) {
        watchProblem = message
        findViewById<TextView>(R.id.watchStatus).apply { text = message; visibility = View.VISIBLE }
        if (watchList.isEmpty()) findViewById<TextView>(R.id.watchEmpty).text = message
    }

    /**
     * The apps whose own home-screen rows are read for Continue Watching: the eight you first chose and every app you
     * add to favourites since (an installed app joins as soon as you add it). Read from settings alone, so it's known
     * before the apps have loaded.
     */
    private fun watchApps(): Set<String> = DEFAULT_FAVOURITES.toSet() + rowAddedSet()

    /**
     * Which apps share resume entries, in the settings panel: those of [watchApps] that are installed, and any other
     * app seen publishing. [back] null: Back closes the panel.
     */
    private fun showWatchCoverage(back: (() -> Unit)? = null) {
        val rows = (watchApps() + publishingPkgs).mapNotNull { pkg -> apps.find { it.pkg == pkg } }.map { app ->
            val pkg = app.pkg
            val status = when {
                checkSelfPermission(TV_LISTINGS) != PackageManager.PERMISSION_GRANTED -> getString(R.string.cw_permission)
                watchProblem != null -> watchProblem!!
                pkg in prefs.getStringSet("cw_off", emptySet())!! -> getString(R.string.off)
                watchCoverage[pkg] != null -> getString(R.string.cw_entry_count, watchCoverage.getValue(pkg))
                pkg == YOUTUBE_PKG -> getString(R.string.cw_youtube_private)
                pkg == NETFLIX -> getString(R.string.cw_netflix_private)
                else -> getString(R.string.cw_not_published)
            }
            app.label to status
        }
        showPanel()
        infoPage(getString(R.string.cw_sources_status), rows, getString(R.string.cw_provider_explanation), null, null, back)
    }

    /**
     * Two sources, same columns:
     * 1. The system Watch Next list (JioHotstar, Prime Video, CloudStream…), newest engagement first.
     * 2. Apps' own home-screen channels: any named "Continue Watching", and the other rows of the [eligible] apps (see
     *    [watchApps]) for items with resume progress. Channel rows carry no timestamps, so they use the time the app
     *    was last opened from here, keeping the app's own order among themselves.
     * Newest first across apps, each app's [PER_APP] newest ahead of the rest, so one app with ten shows can't bury
     * the others. Only what you started: an app's suggestion rows (YouTube's Recommended, Subscriptions) add nothing
     * without a watch position. YouTube shares its resume points only with Google TV's own home, and Netflix shares
     * none, so neither shows up unless that changes.
     */
    private fun queryWatchNext(eligible: Set<String>): List<Prog> {
        val hidden = prefs.getStringSet("hidden_watch", emptySet())!!
        val off = prefs.getStringSet("cw_off", emptySet())!!
        val watchNext = readPrograms(TvContract.WatchNextPrograms.CONTENT_URI, hidden)
        val chans = watchChannels(eligible)
        val channels = chans.flatMap { ch ->
            readPrograms(TvContract.buildPreviewProgramsUriForChannel(ch.id), hidden, ch.resumeOnly)
                .map { (_, p) -> watchSortKey(0, prefs.getLong("t_${p.pkg}", 0)) to p }
        }
        val all = (watchNext + channels).distinctBy { (it.second.pkg to (it.second.intent ?: it.second.title)) }
        watchCoverage = all.mapNotNull { it.second.pkg }.groupingBy { it }.eachCount()
        android.util.Log.i("AsterHome", "Continue Watching source counts: " +
            eligible.joinToString { "$it=${watchCoverage[it] ?: 0}" } + ", channels read=${chans.size}")
        publishingPkgs = all.mapNotNull { it.second.pkg }.toSet()
        return newestPerApp(all.filter { it.second.pkg !in off }.distinctBy { it.second.intent ?: it.second.title }, PER_APP) { it.pkg }
            .take(20)
    }

    /** Apps last seen publishing, so the settings list includes them even without the permission check. */
    @Volatile private var publishingPkgs = emptySet<String>()
    @Volatile private var watchCoverage = emptyMap<String, Int>()
    private var watchProblem: String? = null

    /**
     * Apps that can feed Continue Watching: anything allowed to write TV listings, plus anything
     * actually publishing now. Built from installed apps each time, so new apps appear on their own.
     */
    private fun watchSources(): List<App> = apps.filter { a ->
        a.pkg in DEFAULT_FAVOURITES || a.pkg in publishingPkgs || try {
            packageManager.getPackageInfo(a.pkg, PackageManager.GET_PERMISSIONS)
                .requestedPermissions?.contains(WRITE_EPG) == true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }.sortedBy { it.label.lowercase() }

    private fun pickWatchSources() {
        val sources = watchSources()
        if (sources.isEmpty()) {
            Toast.makeText(this, getString(R.string.no_cw_apps), Toast.LENGTH_SHORT).show()
            return
        }
        toggles(getString(R.string.cw_apps), sources.map { it.label to it.pkg }, "cw_off", getString(R.string.on), getString(R.string.off)) {
            watchKey = null
            refreshWatchNext()
            renderSettings(R.string.cw_apps)
        }
    }

    private class Chan(val id: Long, val pkg: String, val name: String, val resumeOnly: Boolean)

    /**
     * The channels worth reading: every app's "Continue Watching" row, and the other rows of the [eligible] apps (only
     * items with resume progress count from those). Each app's row name is read once, its newest copy: Apple TV
     * re-creates its row dozens of times, and reading every copy took over a second on each return Home.
     */
    private fun watchChannels(eligible: Set<String>): List<Chan> {
        val newest = LinkedHashMap<String, Chan>()
        val cursor = contentResolver.query(TvContract.Channels.CONTENT_URI, null, null, null, null)
            ?: throw SQLException("TV channel provider returned no cursor")
        cursor.use { c ->
            val name = c.getColumnIndex("display_name")
            val pkg = c.getColumnIndex("package_name")
            val id = c.getColumnIndex("_id")
            while (c.moveToNext()) {
                val app = c.getString(pkg) ?: continue
                val display = c.getString(name).orEmpty().trim()
                val namedResume = isContinueChannel(display)
                if (app == packageName || (!namedResume && app !in eligible)) continue
                val key = app + "/" + display.lowercase(Locale.ROOT)
                val chan = Chan(c.getLong(id), app, display, !namedResume)
                if ((newest[key]?.id ?: Long.MIN_VALUE) < chan.id) newest[key] = chan
            }
        }
        return newest.values.toList()
    }

    /** Pairs of (sort key, item), see [watchSortKey]; channel rows have no time of their own and get the type "continue". */
    private fun readPrograms(uri: Uri, hidden: Set<String>, resumeOnly: Boolean = false): List<Pair<Long, Prog>> {
        val out = ArrayList<Pair<Long, Prog>>()
        val cursor = contentResolver.query(uri, null, null, null, null)
            ?: throw SQLException("TV program provider returned no cursor")
        cursor.use { c ->
            fun str(col: String) = c.getColumnIndex(col).let { if (it < 0 || c.isNull(it)) null else c.getString(it) }
            fun num(col: String) = c.getColumnIndex(col).let { if (it < 0 || c.isNull(it)) 0L else c.getLong(it) }
            while (c.moveToNext()) {
                if (str("browsable") == "0") continue
                val type = num("watch_next_type")
                if (!isStartedWatchNext(type)) continue
                val raw = str("title") ?: continue
                if (hideKey(str("package_name"), raw) in hidden) continue
                // CloudStream puts the episode in the title ("S4:E3 The Show"): show the name, with the episode below.
                val (title, titleSeason, titleEpisode) = splitEpisodeTitle(raw)
                val season = str("season_display_number") ?: titleSeason
                val episode = str("episode_display_number") ?: titleEpisode
                val duration = num("duration_millis")
                val position = num("last_playback_position_millis")
                if (resumeOnly && !hasResumeProgress(position, duration)) continue
                val sub = listOfNotNull(
                    if (season != null && episode != null) getString(R.string.season_episode, season, episode) else null,
                    // Under a second in means "not really started": no "time left", no bar.
                    if (duration > 0 && position in 1000 until duration) timeLeft(duration - position) else null,
                ).joinToString(" - ")
                val progress = if (duration > 0 && position >= 1000) (position * 100 / duration).toInt().coerceIn(0, 100) else 0
                val prog = Prog(title, sub, progress, str("thumbnail_uri") ?: str("poster_art_uri"), str("intent_uri"), str("package_name"),
                    hideTitle = raw)
                out += watchSortKey(type, num("last_engagement_time_utc_millis")) to prog
            }
        }
        return out
    }

    private fun hideKey(pkg: String?, title: String) = "$pkg/$title"

    /** We can't delete another app's Watch Next row, so hiding is a local filter. */
    private fun hideWatch(p: Prog) {
        val hidden = prefs.getStringSet("hidden_watch", emptySet())!! + hideKey(p.pkg, p.hideTitle)
        prefs.edit().putStringSet("hidden_watch", hidden).apply()
        Toast.makeText(this, getString(R.string.hidden_from_cw), Toast.LENGTH_SHORT).show()
        refreshWatchNext()
    }

    /** Small colour source-app icon for a Continue Watching card's details line (a rebranded app's own, see REBRANDS). */
    private fun sourceIcon(pkg: String?): Drawable? {
        if (pkg == null) return null
        tiles.icon(pkg, light = !dark)?.let { return BitmapDrawable(resources, it) }
        return try {
            packageManager.getApplicationIcon(pkg).constantState?.newDrawable(resources)?.mutate()
                ?: packageManager.getApplicationIcon(pkg)
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }
    }

    private fun timeLeft(ms: Long): String {
        val min = ms / 60_000
        return if (min >= 60) getString(R.string.time_left_hm, min / 60, min % 60) else getString(R.string.time_left_m, min)
    }

    private fun showWatchNext(list: List<Prog>) {
        val key = list.joinToString("|") { "${it.title}/${it.sub}/${it.progress}/${it.image}" }
        if (key == watchKey) { // unchanged: keep views (and focus) as they are, just fill in missing posters
            retryPosters()
            return
        }
        watchKey = key
        watchList = list
        val focusedAt = watchRow.indexOfChild(currentFocus?.parent as? View)
        watchRow.removeAllViews()
        val visible = if (list.isEmpty()) View.GONE else View.VISIBLE
        watchTitle.visibility = View.VISIBLE
        findViewById<View>(R.id.watchEmpty).visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        watchScroll.visibility = visible
        // ~16 ms per card here and only five show at once: those now, the next ones on the next frame.
        addCardsTo(CW_CARDS - 1)
        ui.post { if (watchKey == key) addCardsTo(CARDS_AHEAD - 1) } // unless a newer list replaced this one
        if (focusedAt >= 0) {
            ((watchRow.getChildAt(focusedAt.coerceAtMost(watchRow.childCount - 1)) as? LinearLayout)?.getChildAt(0)
                ?: firstDockApp())?.requestFocus()
        }
    }

    private var watchList = emptyList<Prog>()

    /**
     * Cards exist up to index [last] only: the four on screen and the four after them, more as focus moves on.
     * Every card costs drawing time on each frame of the home screen (20 dropped ~6% of frames on the test
     * TV, 8 about 2%), on screen or not, so the rest of the list waits until focus gets near.
     */
    private fun addCardsTo(last: Int) {
        while (watchRow.childCount <= minOf(last, watchList.size - 1)) {
            watchRow.addView(watchCard(watchList[watchRow.childCount], watchRow.childCount > 0))
        }
    }

    /** Card size in pixels: five across the content width at 16:9, so the row leaves the top half to the wallpaper. */
    private fun cardSize(): Pair<Int, Int> {
        val w = (resources.displayMetrics.widthPixels - dp(80) - dp(CW_GAP) * (CW_CARDS - 1)) / CW_CARDS
        return w to w * 9 / 16
    }

    /**
     * Five cards across. The artwork fills the card in landscape (see composeCard); progress runs along its bottom
     * edge; the title and details (source app, episode, time left) are always shown underneath. A long title scrolls
     * while its card is focused.
     */
    private fun watchCard(p: Prog, gapBefore: Boolean): View {
        val (w, h) = cardSize()
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            layoutParams = LinearLayout.LayoutParams(w, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                if (gapBefore) marginStart = dp(CW_GAP)
            }
        }
        val img = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_XY }
        val title = label(p.title, 13f).apply {
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            isSingleLine = true
            marqueeRepeatLimit = -1
            setPadding(dp(2), dp(9), dp(2), 0)
            glow(this)
        }
        val frame = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(if (dark) 0xFF161A20.toInt() else 0xFFDCE0E5.toInt())
            }
            addView(img, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            if (p.progress > 0) addView(ProgressBar(this@MainActivity, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 100
                progress = p.progress
                progressDrawable = progressBarDrawable()
            }, FrameLayout.LayoutParams(MATCH_PARENT, dp(3), Gravity.BOTTOM).apply { setMargins(dp(10), 0, dp(10), dp(8)) })
            contentDescription = p.title
            setOnClickListener { open(p) }
            setOnLongClickListener { if (!holdFired) fireHold(this); true }
            setTag(R.id.watchRow, p) // holding OK hides it (see onHold)
            setTag(R.id.homeRow, "cw")
        }
        focusArt(frame, kit, 10f, 1.07f) { focused ->
            title.ellipsize = if (focused) TextUtils.TruncateAt.MARQUEE else TextUtils.TruncateAt.END
            title.isSelected = focused
            if (focused) {
                lastCard = frame
                addCardsTo(watchRow.indexOfChild(col) + CARDS_AHEAD)
            }
        }
        col.addView(frame, LinearLayout.LayoutParams(w, h))
        col.addView(title, LinearLayout.LayoutParams(MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        val icon = sourceIcon(p.pkg)
        if (p.sub.isNotEmpty() || icon != null) {
            col.addView(label(p.sub, 11f).apply {
                setTextColor(kit.text2)
                glow(this)
                setPadding(dp(2), dp(4), dp(2), 0)
                icon?.let {
                    it.setBounds(0, 0, dp(13), dp(13))
                    setCompoundDrawablesRelative(it, null, null, null)
                    compoundDrawablePadding = dp(6)
                }
            }, LinearLayout.LayoutParams(MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        loadPoster(img, p.image)
        return col
    }

    private fun progressBarDrawable(): Drawable {
        fun bar(color: Int) = GradientDrawable().apply { cornerRadius = dp(2).toFloat(); setColor(color) }
        return LayerDrawable(arrayOf(
            bar(0x59FFFFFF),
            ClipDrawable(bar(Color.WHITE), Gravity.START, ClipDrawable.HORIZONTAL),
        )).apply {
            setId(0, android.R.id.background)
            setId(1, android.R.id.progress)
        }
    }

    private fun open(p: Prog) {
        prefs.edit().putLong("t_${p.pkg}", System.currentTimeMillis()).apply()
        try {
            startActivity(Intent.parseUri(p.intent, Intent.URI_INTENT_SCHEME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            p.pkg?.let { packageManager.getLeanbackLaunchIntentForPackage(it) }?.let { startActivity(it) }
        }
    }

    private fun loadPoster(v: ImageView, uri: String?) {
        if (uri == null) return
        posters.get(uri)?.let { v.setImageBitmap(it); return }
        v.tag = uri
        if (!loading.add(uri)) return // already on its way
        val (w, h) = cardSize()
        posterIo.execute {
            val bmp = try {
                poster(uri)?.let { composeCard(it, w, h) }
            } catch (e: Exception) {
                android.util.Log.w("TvHome", "poster failed: $uri ($e)") // retried by retryPosters()
                null
            } finally {
                loading.remove(uri)
            } ?: return@execute
            posters.put(uri, bmp)
            ui.post { if (v.tag == uri) v.setImageBitmap(bmp) }
        }
    }

    /** Cards whose poster never arrived (offline, server timeout) try again; loaded ones are left alone. */
    private fun retryPosters() {
        for (i in 0 until watchRow.childCount) {
            val img = ((watchRow.getChildAt(i) as? ViewGroup)?.getChildAt(0) as? ViewGroup)?.getChildAt(0) as? ImageView ?: continue
            if (img.drawable == null) loadPoster(img, img.tag as? String)
        }
    }

    /**
     * The card picture: a wide sibling of the app's art when there is one (CloudStream only publishes portrait
     * posters, but their hosts keep 16:9 backgrounds; YouTube's thumbnails come letterboxed), else the art itself.
     */
    private fun poster(uri: String): Bitmap? {
        for (wide in landscapeCandidates(uri)) {
            try {
                decode(wide)?.let { return it }
            } catch (e: IOException) {
                android.util.Log.i("TvHome", "no wide art at $wide (${e.javaClass.simpleName})")
            }
        }
        return decode(uri)
    }

    /** Downsample to card size in RGB_565 so a dozen posters stay around 1-2 MB. */
    private fun decode(uri: String): Bitmap? {
        val bytes = openStream(uri).use { it.readBytes() }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= dp(220)) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }

    /**
     * Card art at the card's exact size, always filling it in landscape: wide art is centred, and art narrower than
     * the card (a portrait poster with no wide version) is cropped from near its top, where faces usually are,
     * rather than stretched out of shape.
     */
    private fun composeCard(src: Bitmap, w: Int, h: Int): Bitmap {
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565)
        val narrow = src.width.toLong() * h < src.height.toLong() * w
        val r = cropRect(src.width, src.height, w, h, if (narrow) 0.3f else 0.5f)
        Canvas(out).drawBitmap(src, Rect(r[0], r[1], r[2], r[3]), Rect(0, 0, w, h), Paint(Paint.FILTER_BITMAP_FLAG))
        src.recycle()
        return out
    }

    private fun openStream(uri: String): InputStream =
        if (uri.startsWith("http")) {
            (URL(uri).openConnection() as HttpURLConnection).apply {
                connectTimeout = 5000
                readTimeout = 5000
            }.inputStream
        } else {
            contentResolver.openInputStream(Uri.parse(uri)) ?: throw IOException("no stream for $uri")
        }

    // ---------- shared view bits ----------

    /**
     * A dark-mode card on true black: near-black glass with a hairline rim, brighter along the top edge as if
     * lit from above. [lit] is the focused look: brighter glass and rim.
     */
    private fun rimCard(radius: Int, lit: Boolean): Drawable {
        val body = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, if (lit) DARK_CARD_LIT else DARK_CARD).apply {
            cornerRadius = radius.toFloat()
            setStroke(1, if (lit) RIM_SIDE_LIT else RIM_SIDE) // 1px, not 1dp: a hairline stays crisp
            setDither(true) // near black the gradient has ~8 steps; dithered, they don't show as bands
        }
        return LayerDrawable(arrayOf(body, ColorDrawable(if (lit) RIM_TOP_LIT else RIM_TOP))).apply {
            setLayerGravity(1, Gravity.TOP or Gravity.FILL_HORIZONTAL)
            setLayerHeight(1, 1)
            setLayerInsetLeft(1, radius)
            setLayerInsetRight(1, radius)
        }
    }

    /** Supporting text: the secondary grey in dark mode, [lightAlpha] of the main colour in light mode. */
    private fun TextView.secondary(lightAlpha: Float) = if (dark) setTextColor(DARK_TEXT_2) else alpha = lightAlpha

    private fun label(text: String, size: Float) = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(fg)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    /** A soft halo in the theme's ground colour behind text that sits directly on the wallpaper. */
    private fun glow(v: TextView) = v.setShadowLayer(dp(6).toFloat(), 0f, dp(1).toFloat(),
        (ground and 0x00FFFFFF) or (if (dark) 0xB3000000.toInt() else 0xCC000000.toInt()))

    private companion object {
        const val TV_LISTINGS = "android.permission.READ_TV_LISTINGS"
        const val WRITE_SECURE = "android.permission.WRITE_SECURE_SETTINGS"
        const val ROLE_REQUEST = 2
        val ROW_ALIGN = linkedMapOf( // setting -> its name, and the row's gravity in the scroller
            "left" to (R.string.align_left to Gravity.START),
            "centre" to (R.string.align_centre to Gravity.CENTER_HORIZONTAL),
            "right" to (R.string.align_right to Gravity.END),
        )
        const val BADGE = 0xFFFF453A.toInt()
        // Dark mode tokens, for AMOLED: true black, and near-black cards that read as cards because their edge
        // catches light (brightest along the top), the way light-mode cards use a soft shadow.
        const val DARK_BG = 0xFF000000.toInt() // home ground, settings panel, inset boxes
        const val DARK_SURFACE = 0xFF0B0B0B.toInt() // placeholders behind images
        const val DARK_TEXT = 0xFFF5F5F5.toInt()
        const val DARK_TEXT_2 = 0xFF7A7A7A.toInt() // values, unfocused rows, supporting text
        val DARK_CARD = intArrayOf(0xB0202D3A.toInt(), 0xA00D1721.toInt())
        val DARK_CARD_LIT = intArrayOf(0xD0354A5E.toInt(), 0xC01C2A38.toInt())
        const val RIM_TOP = 0x17FFFFFF // white 9%
        const val RIM_SIDE = 0x0AFFFFFF // white 4%
        const val RIM_TOP_LIT = 0x38FFFFFF // white 22%
        const val RIM_SIDE_LIT = 0x17FFFFFF
        const val PANEL_EDGE = 0x0FFFFFFF // white 6%, the settings panel's left edge
        val HELP_URL = helpUrl()
        const val TOGGLE_ON = "\u0000on" // row values drawn as a switch instead of text
        const val TOGGLE_OFF = "\u0000off"
        const val DARK_DIM = 115 // alpha of the black laid over the Bing picture in dark mode (45%)
        const val PIN_TRIES = 5 // wrong PINs before a 30 s wait
        const val UNLOCKED_MS = 5 * 60_000L
        const val MEDIA_REQUEST = 3
        const val PER_APP = 3 // Continue Watching items per app ahead of the rest
        const val CARDS_AHEAD = 8 // Continue Watching cards built past the focused one (see addCardsTo)
        val READ_PHOTOS = if (Build.VERSION.SDK_INT >= 33) "android.permission.READ_MEDIA_IMAGES" else "android.permission.READ_EXTERNAL_STORAGE"
        /** Minutes between folder photos. */
        val PHOTO_EVERY = linkedMapOf(
            1 to R.string.every_1, 5 to R.string.every_5, 10 to R.string.every_10, 15 to R.string.every_15, 30 to R.string.every_30,
            60 to R.string.every_60, 1440 to R.string.every_1440,
        )
        const val CHOSEN = "✓"
        const val REPO = "aradhyas8/Plume_Launcher"
        const val UPDATE_STATUS = "dev.plume.UPDATE_STATUS"
        const val WRITE_EPG ="com.android.providers.tv.permission.WRITE_EPG_DATA"
        const val TV_SETTINGS = "com.android.tv.settings"
        const val NETFLIX = "com.netflix.ninja"
        const val AMBIENT_MODE = "com.google.android.apps.tv.dreamx" // Google TV's screensaver
        const val HDMI = "hdmi:" // App.pkg prefix for HDMI device tiles; the rest is the TV input id
        const val ALL_APPS = "telepath:all_apps" // the dock's All apps tile; never an app's package
        const val ROW_TILES = 8
        const val ALL_APPS_COLUMNS = 6
        const val GRID_GAP = 16 // dp; shared by the apps row and the cards so their columns line up
        const val CW_CARDS = 5 // Continue Watching cards across the screen
        const val CW_GAP = 16 // dp between Continue Watching cards: the scroller's 16 dp overhang, so the next card stays hidden
        /** Home rows top to bottom, as tagged with R.id.homeRow; Up/Down step through them in this order. */
        val HOME_ROWS = listOf("top", "cw", "dock", "tiles", "cards")
        const val SLIDESHOW = "slideshow" // wallpaper choice: the slideshow folder's photos, one per visit
        /** Shown names for the wallpaper keys saved in prefs (the keys stay English). */
        val WALLPAPER_NAMES = mapOf(
            "Rockies (day)" to R.string.wp_rockies_day, "Rockies (dusk)" to R.string.wp_rockies_dusk,
            "Pure black" to R.string.wp_pure_black, "Soft white" to R.string.wp_soft_white,
        )
        val BUNDLED_WALLPAPERS = linkedMapOf(
            "Rockies (day)" to R.drawable.wallpaper,
            "Rockies (dusk)" to R.drawable.wallpaper_dark,
        )
        val PIN_KEYS = setOf(
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
        )
        val EDIT_KEYS = setOf(
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
        )
        val DECELERATE = android.view.animation.DecelerateInterpolator(1.5f)
        @Volatile var LOADED: Loaded? = null

        val DEFAULT_ORDER = DEFAULT_FAVOURITES

        /** HDMI tile icons by hdmiIcon key: brand marks (Simple Icons, CC0), the rest from Material Symbols (Apache 2.0). */
        val HDMI_ICONS = mapOf(
            "playstation" to R.drawable.logo_playstation,
            "xbox" to R.drawable.logo_xbox,
            "nintendoswitch" to R.drawable.logo_nintendoswitch,
            "appletv" to R.drawable.logo_appletv,
            "settop" to R.drawable.ic_settop,
            "playback" to R.drawable.ic_playback,
            "hdmi" to R.drawable.ic_hdmi,
        )
    }
}
