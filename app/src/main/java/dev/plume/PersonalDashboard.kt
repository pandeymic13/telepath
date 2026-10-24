package dev.plume

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.TransitionDrawable
import android.media.tv.TvContract
import android.media.tv.TvInputInfo
import android.media.tv.TvInputManager
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.text.format.DateFormat
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Space
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.abs

/** What the widgets ask of the home screen: navigation, and settings pages in its side panel. */
internal interface DashboardHost {
    fun openHomeSettings(from: View)
    fun openGoogleTv()
    fun openTvSettings()
    fun configureWeather()
    fun configureWatchlist()
    /** New weather arrived (or the place changed): the mood tint may follow it. */
    fun weatherUpdated()
    fun showInfo(title: String, rows: List<Pair<String, String>>, note: String?, actionLabel: String?, action: (() -> Unit)?)
    /** A small menu above [anchor], as for holding OK on an app. */
    fun menu(anchor: View, items: List<Pair<String, () -> Unit>>)
}

private val QUOTE_ALIASES = mapOf(
    "^NSEI" to "NIFTY 50", "^BSESN" to "SENSEX", "^NSEBANK" to "NIFTY Bank",
    "^GSPC" to "S&P 500", "^IXIC" to "Nasdaq", "^DJI" to "Dow Jones",
)
private val COMPANY_SUFFIX = Regex("(,?\\s+(Corporation|Corp\\.?|Incorporated|Inc\\.?|Limited|Ltd\\.?|plc|N\\.V\\.|S\\.A\\.|AG|SE|Aktiengesellschaft))+$",
    RegexOption.IGNORE_CASE)

/**
 * Friendly quote names: known indices, else Yahoo's name without company suffixes (its short name, or its long one when
 * only that has small letters: "Reliance Industries", not "RELIANCE INDUSTRIES LTD"), else the bare ticker.
 */
internal fun quoteName(symbol: String, shortName: String?, longName: String? = null): String {
    QUOTE_ALIASES[symbol.uppercase(Locale.ROOT)]?.let { return it }
    val names = listOfNotNull(shortName, longName).map { it.trim().replace(COMPANY_SUFFIX, "").trim() }.filter { it.isNotEmpty() }
    return names.firstOrNull { name -> name.any { it.isLowerCase() } } ?: names.firstOrNull()
        ?: symbol.removePrefix("^").substringBefore('.')
}

/** "+1.3%" or "−0.8%" (a true minus sign), from the price and the previous close. */
internal fun changeText(price: Double, previous: Double): String {
    val pct = (price / previous - 1) * 100
    val shown = String.format(Locale.US, "%.1f", abs(pct))
    return (if (pct < 0 && shown != "0.0") "\u2212" else "+") + shown + "%"
}

internal fun currencyPrefix(code: String?): String = when (code?.uppercase(Locale.ROOT)) {
    null, "" -> ""
    "USD" -> "$"
    "INR" -> "₹"
    "EUR" -> "€"
    "GBP" -> "£"
    "JPY" -> "¥"
    else -> "$code "
}

/** "HDMI 3 (eARC/ARC)" → 3; null for inputs that aren't numbered HDMI ports. */
internal fun hdmiNumber(label: String): Int? =
    Regex("^HDMI\\s*(\\d+)\\b", RegexOption.IGNORE_CASE).find(label.trim())?.groupValues?.get(1)?.toIntOrNull()

/** True for "HDMI 1" and "HDMI1 (ARC)": the port telepath shows first unless inputs.primary.port says another. */
internal fun isHdmiOneLabel(label: String) = hdmiNumber(label) == 1

/** The input shown first and always lit: HDMI [port] (inputs.primary.port in telepath.properties; 0 for none). */
internal fun isPrimaryInput(label: String, port: Int = BuildConfig.PRIMARY_INPUT_PORT) = port > 0 && hdmiNumber(label) == port

/** Inputs row order: the primary input ([port]) first, the other HDMI ports by number, then everything else. */
internal fun inputRank(label: String, port: Int = BuildConfig.PRIMARY_INPUT_PORT): Int = when (val n = hdmiNumber(label)) {
    null -> 1000
    port -> 0
    else -> n
}

/**
 * The primary input's name and icon key: those set in telepath.properties ([name], [icon]), else the name its device
 * gives over HDMI-CEC ([device]) or the port's own ([port]), and the icon those names suggest.
 */
internal fun primaryInputLook(name: String, icon: String, device: String?, port: String): Pair<String, String> {
    val shown = name.trim().ifEmpty { device ?: port }
    return shown to if (icon == "auto") hdmiIcon(shown, name.isNotBlank() || device != null) else icon
}

/**
 * The widgets page below the favourites dock, laid out like the reference: Inputs and Shortcuts tiles,
 * then Weather and Watchlist cards.
 */
internal class PersonalDashboard(
    private val activity: Activity,
    private val section: LinearLayout,
    private val ui: UiKit,
    private val host: DashboardHost,
) {
    private val widgets = activity.getSharedPreferences("aster_widgets", Activity.MODE_PRIVATE)
    private val home = activity.getSharedPreferences("home", Activity.MODE_PRIVATE)
    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var active = false
    private var closed = false
    private var busy = false
    private var generation = 0
    private var stocksChecked = 0L
    private var weatherChecked = 0L
    private val use24 = DateFormat.is24HourFormat(activity)

    private val inputTiles = row()
    private val shortcutTiles = row()
    private val tiles = ArrayList<View>()
    private val tilePorts = HashMap<View, Port>()
    private var portsShown = "" // the inputs as last drawn, so unchanged state reports don't rebuild them
    private val weatherCard: LinearLayout
    private val watchCard: LinearLayout
    private lateinit var wxPlace: TextView
    private lateinit var wxIcon: ImageView
    private lateinit var wxTemp: TextView
    private lateinit var wxCondition: TextView
    private lateinit var wxRange: TextView
    private lateinit var wxHours: LinearLayout
    private lateinit var wxSunrise: TextView
    private lateinit var wxSunset: TextView
    private lateinit var quoteRows: LinearLayout
    private var quotes = emptyList<Quote>()

    private class Quote(val symbol: String, val name: String, val price: Double, val previous: Double,
                        val currency: String?, val index: Boolean, val time: Long)
    /** [missing]: Yahoo Finance has no such symbol (else it couldn't be reached, or its answer couldn't be read). */
    private class QuoteFailure(val symbol: String, val missing: Boolean)
    /** One watchlist refresh: [listings] are the symbols found for shares typed without their exchange. */
    private class StockResult(val quotes: List<Quote>, val listings: Map<String, String>, val failures: List<QuoteFailure>)

    private var failures = emptyList<QuoteFailure>()

    init {
        if (!widgets.contains("symbols")) {
            check(widgets.edit().putString("symbols", BuildConfig.WATCHLIST).commit()) { "Initial watchlist could not be saved." }
        }
        section.removeAllViews()
        section.clipChildren = false
        section.clipToPadding = false
        val top = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            clipChildren = false
            clipToPadding = false
            addView(group(R.string.widget_inputs, inputTiles))
            addView(Space(activity), LinearLayout.LayoutParams(0, 1, 1f))
            addView(group(R.string.widget_shortcuts, shortcutTiles))
        }
        section.addView(top, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        buildTiles()

        weatherCard = buildWeather()
        watchCard = buildWatchlist()
        val cards = LinearLayout(activity).apply {
            id = R.id.widgetCards
            orientation = LinearLayout.HORIZONTAL
            clipChildren = false
            addView(weatherCard, LinearLayout.LayoutParams(0, ui.px(152), WEATHER_SHARE.toFloat()))
            addView(watchCard, LinearLayout.LayoutParams(0, ui.px(152), (100 - WEATHER_SHARE).toFloat()).apply {
                marginStart = ui.px(CARDS_GAP)
            })
        }
        section.addView(cards, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = ui.px(22)
        })
        renderWeather(home.getString("wx_json", null).takeIf { home.getString("wx_for", null) == locationKey() })
        renderQuotes()
    }

    private fun tr(id: Int) = activity.getString(id)

    /** Focusable views in a home row: "tiles" (inputs and shortcuts), "cards" (weather, watchlist). */
    fun rowViews(name: String): List<View> = when (name) {
        "tiles" -> tiles.filter { it.isAttachedToWindow }
        "cards" -> listOf(weatherCard, watchCard)
        else -> emptyList()
    }

    /** Holding OK on a card opens its settings; on an input showing a remembered device, it offers to forget it. */
    fun hold(v: View): Boolean = when (v) {
        weatherCard -> { host.configureWeather(); true }
        watchCard -> { host.configureWatchlist(); true }
        else -> forgetMenu(v)
    }

    private fun forgetMenu(v: View): Boolean {
        val port = tilePorts[v] ?: return false
        val seen = port.remembered ?: return false
        host.menu(v, listOf(activity.getString(R.string.forget_device, seen.name) to {
            home.forgetDevice(port.info.id)
            val at = tiles.indexOf(v)
            buildTiles()
            tiles.getOrNull(at)?.requestFocus()
            Toast.makeText(activity, activity.getString(R.string.device_forgotten, seen.name), Toast.LENGTH_LONG).show()
        }))
        return true
    }

    // ---------- layout pieces ----------

    private fun row() = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        clipChildren = false
        clipToPadding = false
    }

    private fun text(size: Float, color: Int, medium: Boolean = false) = TextView(activity).apply {
        textSize = size
        setTextColor(color)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        includeFontPadding = false
        if (medium) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    private fun group(title: Int, tiles: LinearLayout) = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        clipChildren = false
        clipToPadding = false
        addView(text(12f, ui.text2, medium = true).apply {
            text = tr(title)
            setPadding(ui.px(4), 0, 0, 0)
        })
        addView(tiles, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = ui.px(9)
        })
    }

    /**
     * One HDMI port as its tile shows it. [accent]: its console's colour, if any. [remembered]: the device named on
     * this tile isn't on now; telepath remembers it from an earlier visit (hold OK on the tile to forget it).
     */
    private class Port(val info: TvInputInfo, val label: String, val icon: Int, val primary: Boolean, val active: Boolean,
                       val accent: Int?, val remembered: SeenDevice?)

    private fun ports(): List<Port> {
        val manager = activity.getSystemService(TvInputManager::class.java) ?: return emptyList()
        val all = try {
            manager.tvInputList
        } catch (e: SecurityException) {
            reportError("inputs", e)
            return emptyList()
        }
        val roots = all.filter { it.isPassthroughInput && it.parentId == null && !it.isHidden(activity) }
        return roots.sortedWith(compareBy({ inputRank(it.loadLabel(activity).toString()) }, { it.loadLabel(activity).toString() })).map { port ->
            val name = port.loadLabel(activity).toString()
            val primary = isPrimaryInput(name)
            val live = all.firstOrNull {
                it.parentId == port.id && !isAudioDevice(it.loadLabel(activity).toString())
            }?.loadLabel(activity)?.toString()?.takeIf { it.isNotBlank() }
            // A device that named itself on this port once keeps its name, icon and colours here while it's off.
            if (live != null) home.rememberDevice(port.id, live)
            val remembered = if (live == null) home.seenDevice(port.id) else null
            val device = live ?: remembered?.name
            val portName = (port.loadCustomLabel(activity)?.toString() ?: name).substringBefore(" (")
            val look = if (primary) primaryInputLook(BuildConfig.PRIMARY_INPUT_NAME, BuildConfig.PRIMARY_INPUT_ICON, device, portName) else null
            val shown = when {
                look != null -> look.first
                device != null -> device
                else -> portName
            }
            val kind = when {
                look != null -> look.second
                device != null -> hdmiIcon(device, true)
                port.type == TvInputInfo.TYPE_HDMI -> "hdmi"
                else -> "playback"
            }
            val active = try {
                manager.getInputState(port.id) == TvInputManager.INPUT_STATE_CONNECTED
            } catch (e: IllegalArgumentException) {
                false
            }
            Port(port, shown, INPUT_ICONS[kind] ?: R.drawable.ic_hdmi, primary, active, consoleAccent(kind), remembered)
        }
    }

    /** What each input tile shows, to rebuild them only when that changes. */
    private fun shownPorts(ports: List<Port>) = ports.joinToString("|") { "${it.info.id}/${it.label}/${it.active}/${it.accent}/${it.remembered != null}" }

    /**
     * Inputs then shortcuts, all one size. The shortcuts span the Watchlist card below them; when the inputs need more
     * room than that leaves, all the tiles share the content width, with a wider gap between the groups.
     */
    private fun buildTiles() {
        val ports = ports()
        portsShown = shownPorts(ports)
        val shortcuts = listOf<Triple<Int, Int, (View) -> Unit>>(
            Triple(R.drawable.ic_tv, R.string.google_tv) { _ -> host.openGoogleTv() },
            Triple(R.drawable.ic_tune, R.string.home_settings) { v -> host.openHomeSettings(v) },
            Triple(R.drawable.ic_settings, R.string.tv_settings) { _ -> host.openTvSettings() },
        )
        val gap = ui.px(10)
        val count = ports.size + shortcuts.size
        val content = activity.resources.displayMetrics.widthPixels - ui.px(80)
        val cards = content - ui.px(CARDS_GAP)
        val watchlist = cards - cards * WEATHER_SHARE / 100 // the Watchlist card's width, rounded as its row rounds it
        val spanWatchlist = (watchlist - (shortcuts.size - 1) * gap) / shortcuts.size
        val shareRow = (content - (count - 2) * gap - ui.px(28)) / count
        val width = minOf(spanWatchlist, shareRow)
        inputTiles.removeAllViews()
        shortcutTiles.removeAllViews()
        tiles.clear()
        tilePorts.clear()
        ports.forEachIndexed { i, p ->
            val tile = tile(p.icon, p.label, dim = !p.primary && !p.active, accent = p.accent) {
                open(Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(p.info.id)))
            }
            tile.contentDescription = if (p.primary) "${p.label}, ${p.info.loadLabel(activity)}" else p.label
            inputTiles.addView(tile, LinearLayout.LayoutParams(width, ui.px(66)).apply { if (i > 0) marginStart = gap })
            tiles += tile
            tilePorts[tile] = p
        }
        if (ports.isEmpty()) {
            inputTiles.addView(text(13f, ui.text2).apply {
                text = tr(R.string.no_inputs)
                gravity = Gravity.CENTER_VERTICAL
            }, LinearLayout.LayoutParams(width * 2, ui.px(66)))
        }
        shortcuts.forEachIndexed { i, (icon, label, action) ->
            val made = arrayOfNulls<View>(1)
            val view = tile(icon, tr(label), dim = false) { made[0]?.let(action) }
            made[0] = view
            view.contentDescription = tr(label)
            shortcutTiles.addView(view, LinearLayout.LayoutParams(width, ui.px(66)).apply { if (i > 0) marginStart = gap })
            tiles += view
        }
    }

    fun inputsChanged() {
        if (closed) return
        // Input services report state often; rebuild (and risk moving focus) only when what's shown changes.
        val shown = shownPorts(ports())
        if (shown == portsShown) return
        val focused = activity.currentFocus
        val at = tiles.indexOf(focused)
        buildTiles()
        if (at >= 0) tiles.getOrNull(minOf(at, tiles.size - 1))?.requestFocus()
    }

    private fun tile(icon: Int, label: String, dim: Boolean, accent: Int? = null, action: () -> Unit): View {
        val image = ImageView(activity).apply {
            setImageResource(icon)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        val name = text(12f, ui.text, medium = true).apply { gravity = Gravity.CENTER }
        name.text = label
        val tile = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(ui.px(6), ui.px(8), ui.px(6), ui.px(6))
            addView(image, LinearLayout.LayoutParams(ui.px(22), ui.px(22)))
            addView(name, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = ui.px(7)
            })
            setOnClickListener { action() }
            setTag(R.id.homeRow, "tiles")
        }
        focusTile(tile, ui, 14f, image, listOf(name), if (dim) ui.text3 else ui.text, accent = accent)
        return tile
    }

    /** Big cards stay glass when focused: the glass brightens, the rim lights and the card lifts a little. */
    private fun card(rowName: String, onClick: () -> Unit) = LinearLayout(activity).apply {
        val fade = TransitionDrawable(arrayOf(ui.glass(22f), ui.glass(22f, lit = true))).apply { isCrossFadeEnabled = true }
        background = fade
        isFocusable = true
        isClickable = true
        clipToOutline = true
        setTag(R.id.homeRow, rowName)
        setOnClickListener { onClick() }
        setOnFocusChangeListener { v, focused ->
            if (focused) fade.startTransition(200) else fade.reverseTransition(160)
            v.lift(focused, 1.025f, ui.px(12f))
        }
    }

    private fun buildWeather(): LinearLayout {
        val card = card("cards") { showWeatherDetails() }
        card.orientation = LinearLayout.HORIZONTAL
        card.setPadding(ui.px(20), ui.px(16), ui.px(18), ui.px(14))
        wxPlace = text(12f, ui.text2, medium = true)
        wxIcon = ImageView(activity)
        wxTemp = text(40f, ui.text).apply { typeface = Typeface.create("sans-serif-light", Typeface.NORMAL) }
        wxCondition = text(14f, ui.text)
        wxRange = text(12f, ui.text2)
        val now = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(wxIcon, LinearLayout.LayoutParams(ui.px(34), ui.px(34)))
            addView(wxTemp, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                marginStart = ui.px(8)
            })
        }
        val left = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(wxPlace)
            addView(now, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = ui.px(12)
            })
            addView(wxCondition, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = ui.px(10)
            })
            addView(wxRange, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = ui.px(5)
            })
        }
        card.addView(left, LinearLayout.LayoutParams(ui.px(168), LinearLayout.LayoutParams.MATCH_PARENT))
        card.addView(View(activity).apply { setBackgroundColor(ui.divider) },
            LinearLayout.LayoutParams(maxOf(1, ui.px(1)), LinearLayout.LayoutParams.MATCH_PARENT).apply {
                marginEnd = ui.px(16)
                topMargin = ui.px(2)
                bottomMargin = ui.px(2)
            })
        wxHours = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        wxSunrise = text(11f, ui.text2)
        wxSunset = text(11f, ui.text2)
        val sun = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(wxSunrise)
            addView(wxSunset, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                marginStart = ui.px(18)
            })
        }
        for ((view, kind) in listOf(wxSunrise to WeatherIcon.Kind.SUNRISE, wxSunset to WeatherIcon.Kind.SUNSET)) {
            view.setCompoundDrawablesRelative(WeatherIcon(kind, ui.text2).apply { setBounds(0, 0, ui.px(15), ui.px(15)) }, null, null, null)
            view.compoundDrawablePadding = ui.px(6)
        }
        val right = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(wxHours, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(sun, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = ui.px(6)
            })
        }
        card.addView(right, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
        card.contentDescription = tr(R.string.widget_weather)
        return card
    }

    private fun buildWatchlist(): LinearLayout {
        val card = card("cards") { showQuoteDetails() }
        card.orientation = LinearLayout.VERTICAL
        card.setPadding(ui.px(20), ui.px(16), ui.px(18), ui.px(12))
        card.addView(LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(text(12f, ui.text2, medium = true).apply { text = tr(R.string.widget_stocks) },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(text(11f, ui.text3).apply { text = tr(R.string.quote_header) })
        })
        quoteRows = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        card.addView(quoteRows, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = ui.px(10) })
        card.contentDescription = tr(R.string.widget_stocks)
        return card
    }

    // ---------- weather ----------

    private fun locationKey() = "${home.getFloat("geo_lat", fallbackLat())},${home.getFloat("geo_lon", fallbackLon())}"
    private fun city() = home.getString("geo_city", null) ?: DEFAULT_PLACE?.city ?: tr(R.string.your_area)
    private fun unit() = home.getString("wx_unit", "c")!!

    private fun skyName(code: Int) = tr(when (skyFor(code)) {
        Sky.CLEAR -> R.string.wx_clear
        Sky.MOSTLY_CLEAR -> R.string.wx_mostly_clear
        Sky.PARTLY_CLOUDY -> R.string.wx_partly_cloudy
        Sky.CLOUDY -> R.string.wx_cloudy
        Sky.FOG -> R.string.wx_fog
        Sky.DRIZZLE -> R.string.wx_drizzle
        Sky.RAIN -> R.string.wx_rain
        Sky.SNOW -> R.string.wx_snow
        Sky.THUNDER -> R.string.wx_thunderstorm
    })

    private fun JSONArray.strings() = List(length()) { getString(it) }

    private fun renderWeather(json: String?) {
        wxPlace.text = activity.getString(R.string.weather_title, city())
        val unit = unit()
        if (json == null) {
            wxIcon.setImageDrawable(WeatherIcon(WeatherIcon.Kind.CLOUD, ui.text3))
            wxTemp.text = "––°"
            wxCondition.text = tr(if (weatherChecked == 0L) R.string.widget_loading else R.string.weather_unavailable)
            wxRange.text = ""
            wxHours.removeAllViews()
            wxSunrise.visibility = View.INVISIBLE
            wxSunset.visibility = View.INVISIBLE
            return
        }
        try {
            val data = JSONObject(json)
            val current = data.getJSONObject("current")
            val daily = data.getJSONObject("daily")
            val hourly = data.getJSONObject("hourly")
            val code = current.getInt("weather_code")
            wxIcon.setImageDrawable(WeatherIcon(WeatherIcon.kindFor(skyFor(code), current.optInt("is_day", 1) == 1), ui.text))
            wxTemp.text = "${displayTemp(current.getDouble("temperature_2m"), unit)}°"
            wxCondition.text = skyName(code)
            wxRange.text = activity.getString(R.string.weather_high_low,
                displayTemp(daily.getJSONArray("temperature_2m_max").getDouble(0), unit),
                displayTemp(daily.getJSONArray("temperature_2m_min").getDouble(0), unit))
            val now = current.getString("time")
            val times = hourly.getJSONArray("time").strings()
            val temps = hourly.getJSONArray("temperature_2m")
            val codes = hourly.getJSONArray("weather_code")
            val days = hourly.optJSONArray("is_day")
            wxHours.removeAllViews()
            for (i in upcomingHours(times, now, 6)) {
                wxHours.addView(LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    addView(text(11f, ui.text2).apply { text = hourText(times[i], use24) })
                    addView(ImageView(activity).apply {
                        setImageDrawable(WeatherIcon(WeatherIcon.kindFor(skyFor(codes.getInt(i)), days?.optInt(i, 1) != 0), ui.text))
                    }, LinearLayout.LayoutParams(ui.px(20), ui.px(20)).apply { topMargin = ui.px(8) })
                    addView(text(13f, ui.text).apply { text = "${displayTemp(temps.getDouble(i), unit)}°" },
                        LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                            topMargin = ui.px(8)
                        })
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
            }
            val rises = daily.getJSONArray("sunrise").strings()
            val sets = daily.getJSONArray("sunset").strings()
            val rise = rises.firstOrNull { it > now } ?: rises.firstOrNull()
            val set = sets.firstOrNull { it > now } ?: sets.firstOrNull()
            wxSunrise.text = activity.getString(R.string.sunrise_at, rise?.let { clockText(it, use24) }.orEmpty())
            wxSunset.text = activity.getString(R.string.sunset_at, set?.let { clockText(it, use24) }.orEmpty())
            wxSunrise.visibility = View.VISIBLE
            wxSunset.visibility = View.VISIBLE
        } catch (e: JSONException) {
            reportError("weather-format", e)
            renderWeather(null)
        }
    }

    private fun weatherResult(lat: Float, lon: Float): String {
        val text = download("https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
            "&current=temperature_2m,weather_code,is_day&hourly=temperature_2m,weather_code,is_day" +
            "&daily=temperature_2m_max,temperature_2m_min,sunrise,sunset&timezone=auto&forecast_days=2")
        JSONObject(text).getJSONObject("current").getDouble("temperature_2m")
        return text
    }

    private fun showWeatherDetails() {
        val json = home.getString("wx_json", null)
        val rows = ArrayList<Pair<String, String>>()
        rows += tr(R.string.weather_location) to city()
        if (json != null) {
            try {
                val current = JSONObject(json).getJSONObject("current")
                rows += tr(R.string.weather_now) to "${displayTemp(current.getDouble("temperature_2m"), unit())}° · ${skyName(current.getInt("weather_code"))}"
            } catch (e: JSONException) {
                reportError("weather-format", e)
            }
        }
        val updated = home.getLong("wx_time", 0)
        if (updated > 0) rows += tr(R.string.weather_updated) to SimpleDateFormat(if (use24) "HH:mm" else "h:mm a", Locale.getDefault()).format(Date(updated))
        host.showInfo(tr(R.string.widget_weather), rows, tr(R.string.weather_source), tr(R.string.change_location)) { host.configureWeather() }
    }

    // ---------- watchlist ----------

    private fun renderQuotes() {
        quoteRows.removeAllViews()
        val symbols = parseSymbols(widgets.getString("symbols", "").orEmpty())
        if (symbols.isEmpty()) {
            quoteRows.addView(text(13f, ui.text2).apply { text = tr(R.string.stocks_not_configured); maxLines = 3 })
            return
        }
        if (quotes.isEmpty() && failures.isEmpty()) {
            quoteRows.addView(text(13f, ui.text2).apply { text = tr(R.string.widget_loading) })
            return
        }
        val price = NumberFormat.getNumberInstance(Locale.getDefault()).apply {
            minimumFractionDigits = 2
            maximumFractionDigits = 2
        }
        // Three rows fit the card at full size; four or five share its height in smaller type.
        val compact = symbols.size > 3
        val (size, pillPad) = when (symbols.size) {
            5 -> 13f to 1
            4 -> 14f to 2
            else -> 15f to 3
        }
        for (symbol in symbols) {
            val q = quotes.firstOrNull { it.symbol == symbol }
            val line = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            line.addView(text(size, ui.text).apply { text = q?.name ?: quoteName(symbol, null) },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            if (q == null) {
                val missing = failures.any { it.symbol == symbol && it.missing }
                line.addView(text(size - 2f, ui.text3).apply { text = tr(if (missing) R.string.quote_not_found else R.string.quote_unavailable) })
            } else {
                line.addView(text(size, ui.text).apply { text = (if (q.index) "" else currencyPrefix(q.currency)) + price.format(q.price) })
                val upDay = q.price >= q.previous
                line.addView(text(if (compact) 10f else 11f, if (upDay) ui.up else ui.down, medium = true).apply {
                    text = changeText(q.price, q.previous)
                    gravity = Gravity.CENTER
                    background = ui.pill(upDay)
                    setPadding(ui.px(6), ui.px(pillPad), ui.px(6), ui.px(pillPad))
                }, LinearLayout.LayoutParams(ui.px(if (compact) 56 else 62), LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    marginStart = ui.px(12)
                })
            }
            quoteRows.addView(line, if (compact) {
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            } else {
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ui.px(28)).apply {
                    if (quoteRows.childCount > 0) topMargin = ui.px(4)
                }
            })
        }
    }

    /** Quotes for [symbols]; a share typed without its exchange (RELIANCE) is looked up and quoted (RELIANCE.NS). */
    private fun stockResult(symbols: List<String>): StockResult {
        val found = ArrayList<Quote>()
        val listings = HashMap<String, String>()
        val failed = ArrayList<QuoteFailure>()
        for (symbol in symbols) {
            try {
                found += try {
                    quote(symbol)
                } catch (e: HttpStatusException) {
                    val listing = (if (e.status == 404) listing(symbol) else null) ?: throw e
                    quote(listing).also { listings[symbol] = listing }
                }
            } catch (e: IOException) {
                reportError("quote", e)
                failed += QuoteFailure(symbol, missing = (e as? HttpStatusException)?.status == 404)
            } catch (e: JSONException) {
                reportError("quote-format", e)
                failed += QuoteFailure(symbol, missing = false)
            } catch (e: IllegalArgumentException) {
                reportError("quote-value", e)
                failed += QuoteFailure(symbol, missing = false)
            }
        }
        return StockResult(found, listings, failed)
    }

    private fun quote(symbol: String): Quote {
        val data = JSONObject(download("https://query1.finance.yahoo.com/v8/finance/chart/" +
            URLEncoder.encode(symbol, "UTF-8") + "?interval=1d&range=1d"))
        val meta = data.getJSONObject("chart").getJSONArray("result").getJSONObject(0).getJSONObject("meta")
        val price = meta.getDouble("regularMarketPrice")
        val previous = meta.getDouble("chartPreviousClose")
        require(price.isFinite() && previous.isFinite() && previous > 0) { "Invalid quote." }
        val name = quoteName(symbol, meta.optString("shortName").ifEmpty { null }, meta.optString("longName").ifEmpty { null })
        return Quote(symbol, name, price, previous, meta.optString("currency").ifEmpty { null },
            symbol.startsWith("^") || meta.optString("instrumentType") == "INDEX", meta.getLong("regularMarketTime") * 1000L)
    }

    /**
     * The listing Yahoo Finance's search finds for [typed], a share without its exchange: RELIANCE.NS for RELIANCE.
     * Null if there is none, or the search fails (the next refresh tries again).
     */
    private fun listing(typed: String): String? {
        if (!isBareTicker(typed)) return null
        return try {
            val results = JSONObject(download("https://query1.finance.yahoo.com/v1/finance/search?q=" +
                URLEncoder.encode(typed, "UTF-8") + "&quotesCount=10&newsCount=0&listsCount=0&enableFuzzyQuery=false",
                256 * 1024)).optJSONArray("quotes") ?: return null
            listingFor(typed, (0 until results.length()).mapNotNull { results.optJSONObject(it)?.optString("symbol") })
        } catch (e: IOException) {
            reportError("quote-search", e)
            null
        } catch (e: JSONException) {
            reportError("quote-search-format", e)
            null
        }
    }

    private fun showQuoteDetails() {
        val stamp = SimpleDateFormat(if (use24) "d MMM HH:mm" else "d MMM h:mm a", Locale.getDefault())
        val price = NumberFormat.getNumberInstance(Locale.getDefault()).apply {
            minimumFractionDigits = 2
            maximumFractionDigits = 2
        }
        val rows = parseSymbols(widgets.getString("symbols", "").orEmpty()).map { symbol ->
            val q = quotes.firstOrNull { it.symbol == symbol }
            val missing = failures.any { it.symbol == symbol && it.missing }
            if (q == null) quoteName(symbol, null) to tr(if (missing) R.string.quote_not_found_detail else R.string.quote_unavailable)
            else "${q.name} ($symbol)" to "${if (q.index) "" else currencyPrefix(q.currency)}${price.format(q.price)}  " +
                "${changeText(q.price, q.previous)}  ·  ${stamp.format(Date(q.time))}"
        }
        host.showInfo(tr(R.string.widget_stocks), rows, tr(R.string.quote_disclaimer), tr(R.string.configure_stocks)) { host.configureWatchlist() }
    }

    fun symbols(): String = widgets.getString("symbols", "").orEmpty()

    /** Saves the watchlist; returns an error message, or null when saved. */
    fun saveSymbols(value: String): String? {
        val symbols = try {
            parseSymbols(value)
        } catch (e: IllegalArgumentException) {
            return e.message
        }
        if (!widgets.edit().putString("symbols", symbols.joinToString(",")).commit()) return tr(R.string.widget_save_error)
        quotes = emptyList()
        failures = emptyList()
        renderQuotes()
        changed()
        return null
    }

    // ---------- refresh ----------

    fun resume() {
        if (closed) return
        active = true
        handler.removeCallbacks(tick)
        handler.post(tick)
    }

    fun pause() {
        active = false
        handler.removeCallbacks(tick)
    }

    fun close() {
        closed = true
        pause()
        worker.shutdownNow()
    }

    /** The location or units changed: show what's cached for it at once, refetch if it's for another place. */
    fun weatherSettingsChanged() {
        val cached = home.getString("wx_json", null).takeIf { home.getString("wx_for", null) == locationKey() }
        if (cached == null) weatherChecked = 0
        renderWeather(cached)
        host.weatherUpdated()
        changed()
    }

    /** The location went back to automatic: look it up again, with the weather there. */
    fun relocate() {
        weatherChecked = 0
        changed()
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!active || closed) return
            refresh()
            handler.postDelayed(this, 60_000L)
        }
    }

    private fun refresh() {
        if (busy || closed) return
        val now = System.currentTimeMillis()
        val key = locationKey()
        val fetchWeather = now - weatherChecked >= 30 * 60_000L || home.getString("wx_for", null) != key
        val fetchStocks = now - stocksChecked >= 5 * 60_000L
        if (!fetchWeather && !fetchStocks) return
        // Automatic location: the TV's place from its internet address, looked up with the weather every 6 hours.
        val relocate = fetchWeather && !home.getBoolean("geo_manual", false) && now - home.getLong("geo_time", 0) >= 6 * 3_600_000L
        val revision = generation
        val symbols = parseSymbols(widgets.getString("symbols", "").orEmpty())
        val lat = home.getFloat("geo_lat", fallbackLat())
        val lon = home.getFloat("geo_lon", fallbackLon())
        busy = true
        worker.execute {
            val found = if (!relocate) null else try {
                locate()
            } catch (e: IOException) {
                reportError("location", e)
                null
            } catch (e: JSONException) {
                reportError("location-format", e)
                null
            }
            val place = if (found == null) key else "${found.lat},${found.lon}"
            // Not located yet (the lookup failed, and no city is set): no weather rather than the equator's, retried
            // with the lookup in 30 minutes.
            val located = found != null || home.contains("geo_lat")
            val weather = if (!fetchWeather) null else if (!located) "" else try {
                weatherResult(found?.lat ?: lat, found?.lon ?: lon)
            } catch (e: IOException) {
                reportError("weather", e)
                ""
            } catch (e: JSONException) {
                reportError("weather-format", e)
                ""
            }
            val stocks = if (fetchStocks && symbols.isNotEmpty()) stockResult(symbols) else null
            handler.post {
                busy = false
                if (closed || revision != generation) return@post
                if (found != null) {
                    home.edit().putString("geo_city", found.city).putFloat("geo_lat", found.lat)
                        .putFloat("geo_lon", found.lon).putLong("geo_time", now).apply()
                }
                if (weather != null) {
                    weatherChecked = now
                    if (weather.isNotEmpty()) {
                        home.edit().putString("wx_json", weather).putString("wx_for", place).putLong("wx_time", now).apply()
                        renderWeather(weather)
                        host.weatherUpdated()
                    } else if (home.getString("wx_for", null) != place) {
                        renderWeather(null)
                    }
                }
                if (stocks != null) {
                    if (stocks.listings.isNotEmpty()) {
                        // Keep what was found (RELIANCE.NS for RELIANCE): the list then shows it, and asks for it directly.
                        val saved = parseSymbols(widgets.getString("symbols", "").orEmpty())
                        widgets.edit().putString("symbols", saved.map { stocks.listings[it] ?: it }.distinct().joinToString(",")).apply()
                    }
                    quotes = stocks.quotes
                    failures = stocks.failures
                    stocksChecked = now
                    renderQuotes()
                }
            }
        }
    }

    /** Where the TV is, roughly, from its internet address (ipwho.is, free and keyless); null if it can't tell. */
    private fun locate(): Place? {
        val json = JSONObject(download("https://ipwho.is/?fields=success,city,latitude,longitude", 64 * 1024))
        return ipPlace(json.optBoolean("success"), json.optString("city"),
            json.optDouble("latitude", Double.NaN), json.optDouble("longitude", Double.NaN))
    }

    private fun reportError(source: String, error: Exception) {
        val status = (error as? HttpStatusException)?.let { " ${it.status}" }.orEmpty()
        android.util.Log.w("TelepathWidgets", "$source failed: ${error.javaClass.simpleName}$status")
    }

    private fun changed() {
        generation++
        busy = false
        stocksChecked = 0
        if (active) {
            handler.removeCallbacks(tick)
            handler.post(tick)
        }
    }

    private fun open(intent: Intent) {
        try {
            activity.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: ActivityNotFoundException) {
            reportError("shortcut", e)
            Toast.makeText(activity, R.string.shortcut_unavailable, Toast.LENGTH_LONG).show()
        } catch (e: SecurityException) {
            reportError("shortcut", e)
            Toast.makeText(activity, R.string.shortcut_unavailable, Toast.LENGTH_LONG).show()
        }
    }

    private companion object {
        /** The Weather card's share of the cards' row, in percent (the Watchlist has the rest), and the gap between them in dp. */
        const val WEATHER_SHARE = 59
        const val CARDS_GAP = 16
        val INPUT_ICONS = mapOf(
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
