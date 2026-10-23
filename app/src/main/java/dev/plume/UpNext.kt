package dev.plume

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.text.SpannableString
import android.text.Spanned
import android.text.format.DateFormat
import android.text.style.ForegroundColorSpan
import android.util.Log
import android.view.View
import android.widget.TextView
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.time.ZoneId
import java.util.Calendar
import java.util.TimeZone
import java.util.concurrent.Executors

/**
 * The "Up next" line beside the clock: the live or next match or session of what telepath.properties follows
 * (upnext.*): teams by their TheSportsDB IDs, the Champions League and World Cup semi-finals and finals, and F1
 * qualifying, sprint and race sessions. Fixtures come from Jolpica (F1) and TheSportsDB's free public key at most every
 * 6 hours while Home is visible, and are kept in preferences, so the line shows at once and offline too.
 */
internal class UpNext(private val activity: Activity) {
    private val prefs = activity.getSharedPreferences("home", Activity.MODE_PRIVATE)
    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val block = activity.findViewById<View>(R.id.upNext)
    private val rule = activity.findViewById<View>(R.id.upNextRule)
    private val title = activity.findViewById<TextView>(R.id.upNextTitle)
    private val line = activity.findViewById<TextView>(R.id.upNextWhen)
    private var events = decodeEvents(prefs.getString("sp_events", null))
    private var busy = false
    private var closed = false

    /** On unless turned off on the TV (or by upnext.enabled), and only with something to follow. */
    fun isOn() = FOLLOWS_ANYTHING && !prefs.getBoolean("upnext_off", !BuildConfig.UPNEXT_ON)

    fun setOn(on: Boolean) {
        prefs.edit().putBoolean("upnext_off", !on).apply()
        tick()
    }

    fun resume() = tick()

    fun close() {
        closed = true
        worker.shutdownNow()
    }

    /** Once a minute while Home shows: the line moves on (Tomorrow → Today → Live now), and stale fixtures are fetched. */
    fun tick() {
        render()
        refresh()
    }

    private fun render() {
        if (closed) return
        val now = System.currentTimeMillis()
        val e = if (isOn()) upNext(events, now) else null
        block.visibility = if (e == null) View.GONE else View.VISIBLE
        rule.visibility = block.visibility
        if (e == null) return
        title.text = e.title
        line.text = describe(e, now)
        block.contentDescription = activity.getString(R.string.up_next) + ": " + e.title + ", " + line.text
    }

    /** "Sun, 2:30 am · Qualifying", or "● Live now · Test · Day 2" with a red dot. */
    private fun describe(e: SportEvent, now: Long): CharSequence {
        val detail = if (e.days > 1) e.detail + " · " + activity.getString(R.string.day_n, e.dayAt(now)) else e.detail
        val tail = if (detail.isEmpty()) "" else " · $detail"
        if (e.isLive(now)) {
            return SpannableString("● " + activity.getString(R.string.live_now) + tail).apply {
                setSpan(ForegroundColorSpan(LIVE_RED), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        return whenText(e.nextStart(now) ?: e.start, now, ZoneId.systemDefault(), DateFormat.is24HourFormat(activity),
            activity.getString(R.string.today), activity.getString(R.string.tomorrow)) + tail
    }

    /**
     * The coming fixtures, soonest first, as (title, when · detail) rows for the settings page: the next three F1
     * sessions (a race weekend has up to four) and everything else, so the teams aren't buried under F1.
     */
    fun upcoming(): List<Pair<String, String>> {
        val now = System.currentTimeMillis()
        val next = events.filter { it.isLive(now) || it.nextStart(now) != null }
            .sortedBy { if (it.isLive(now)) 0L else it.nextStart(now)!! }
        val (f1, others) = next.partition { it.id.startsWith("f1|") }
        return (f1.take(3) + others.take(7)).sortedBy { if (it.isLive(now)) 0L else it.nextStart(now)!! }
            .map { it.title to describe(it, now).toString() }
    }

    private fun refresh() {
        val now = System.currentTimeMillis()
        if (closed || busy || !isOn() || !sportsStale(prefs.getLong("sp_time", 0), now, events)) return
        if (now - prefs.getLong("sp_try", 0) in 0 until 15 * MINUTE_MS) return // failed a moment ago: wait a little
        prefs.edit().putLong("sp_try", now).apply()
        busy = true
        val previous = events
        worker.execute {
            val found = fetch(now, previous)
            handler.post {
                busy = false
                if (closed || found == null) return@post
                events = found
                prefs.edit().putString("sp_events", encodeEvents(found)).putLong("sp_time", now).apply()
                render()
            }
        }
    }

    /** Every source in turn; one failing keeps what it gave last time. Null if none could be reached. */
    private fun fetch(now: Long, previous: List<SportEvent>): List<SportEvent>? {
        val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = now }
        val year = utc.get(Calendar.YEAR)
        val month = utc.get(Calendar.MONTH) + 1
        // Knockout rounds first: when a followed team plays a semi-final, that version (with its round) is the one kept.
        val sources = linkedMapOf<String, () -> List<SportEvent>>()
        if (BuildConfig.UPNEXT_CHAMPIONS_LEAGUE) sources["ucl"] = { knockouts("ucl", UCL, uclSeason(year, month)) }
        if (BuildConfig.UPNEXT_WORLD_CUP) worldCupYear(year, month)?.let { wc -> sources["wc"] = { knockouts("wc", WORLD_CUP, wc.toString()) } }
        if (BuildConfig.UPNEXT_F1) sources["f1"] = { formula1(now, year) }
        for (team in FOLLOWED_TEAMS) sources["team${team.id}"] = { nextFor("team${team.id}", team.id) }
        if (sources.isEmpty()) return emptyList()
        val out = ArrayList<SportEvent>()
        val failed = HashSet<String>()
        for ((name, load) in sources) {
            try {
                out += load()
            } catch (e: IOException) {
                failed += name
                Log.w(TAG, "$name fixtures unavailable: ${e.javaClass.simpleName}")
            } catch (e: JSONException) {
                failed += name
                Log.w(TAG, "$name fixtures unreadable: ${e.javaClass.simpleName}")
            }
        }
        if (failed.size == sources.size) return null
        out += previous.filter { it.id.substringBefore('|') in failed }
        return out.filter { it.end > now }.distinctBy { it.id.substringAfter('|') }.sortedBy { it.start }
    }

    private fun formula1(now: Long, year: Int): List<SportEvent> {
        val season = f1Season("current")
        // After the last race, next year's calendar (once it's published).
        return if (season.any { it.end > now }) season else f1Season((year + 1).toString())
    }

    private fun f1Season(season: String): List<SportEvent> {
        val races = JSONObject(download("https://api.jolpi.ca/ergast/f1/$season.json?limit=40", 1 shl 20))
            .getJSONObject("MRData").getJSONObject("RaceTable").getJSONArray("Races")
        val out = ArrayList<SportEvent>()
        for (i in 0 until races.length()) {
            val race = races.getJSONObject(i)
            val name = "F1 · " + grandPrixName(race.getString("raceName"))
            val key = race.optString("season") + "-" + race.optString("round")
            val sessions = listOf(
                F1_QUALIFYING to race.optJSONObject("Qualifying"),
                F1_SPRINT_QUALIFYING to (race.optJSONObject("SprintQualifying") ?: race.optJSONObject("SprintShootout")),
                F1_SPRINT to race.optJSONObject("Sprint"),
                F1_RACE to race,
            )
            for ((session, o) in sessions) {
                val start = o?.let { jolpicaTime(it.str("date"), it.str("time")) } ?: continue
                out += SportEvent("f1|$key-$session", name, session, start, f1Window(session))
            }
        }
        return out
    }

    /** A team's next fixture (the free key gives just the next one). */
    private fun nextFor(source: String, team: Int): List<SportEvent> =
        JSONObject(download("$SPORTS_DB/eventsnext.php?id=$team", 256 * 1024)).objects("events").mapNotNull { event(source, it, null) }

    /** A competition's semi-finals (round 150) and final (200), once they're scheduled. */
    private fun knockouts(source: String, league: Int, season: String): List<SportEvent> = listOf(150, 200).flatMap { round ->
        JSONObject(download("$SPORTS_DB/eventsround.php?id=$league&r=$round&s=$season", 512 * 1024)).objects("events")
            .mapNotNull { event(source, it, knockoutRound(round)) }
    }

    private fun event(source: String, o: JSONObject, round: String?): SportEvent? {
        if (isOver(o.str("strStatus"))) return null
        val id = o.str("idEvent") ?: return null
        val start = sportsDbTime(o.str("strTimestamp"), o.str("dateEvent"), o.str("strTime")) ?: return null
        val home = o.str("strHomeTeam")?.let(::teamName)
        val away = o.str("strAwayTeam")?.let(::teamName)
        val league = o.str("strLeague")
        val name = if (home != null && away != null) "$home vs $away" else o.str("strEvent") ?: return null
        if (o.str("strSport").equals("Cricket", ignoreCase = true)) {
            val format = cricketFormat(league, o.str("strEvent"))
            return SportEvent("$source|$id", name, format ?: competitionName(league), start, cricketWindow(format), if (format == "Test") 5 else 1)
        }
        val detail = listOfNotNull(competitionName(league).ifEmpty { null }, round).joinToString(" · ")
        return SportEvent("$source|$id", name, detail, start, 2 * HOUR_MS)
    }

    private fun JSONObject.str(key: String): String? =
        if (isNull(key)) null else optString(key).trim().takeIf { it.isNotEmpty() && it != "null" }

    private fun JSONObject.objects(key: String): List<JSONObject> =
        optJSONArray(key)?.let { a -> List(a.length()) { a.getJSONObject(it) } } ?: emptyList()

    private companion object {
        const val TAG = "TelepathUpNext"
        const val SPORTS_DB = "https://www.thesportsdb.com/api/v1/json/123" // TheSportsDB's public free key
        const val UCL = 4480
        const val WORLD_CUP = 4429
        const val LIVE_RED = 0xFFFF453A.toInt()
        val FOLLOWS_ANYTHING = BuildConfig.UPNEXT_F1 || BuildConfig.UPNEXT_CHAMPIONS_LEAGUE || BuildConfig.UPNEXT_WORLD_CUP ||
            FOLLOWED_TEAMS.isNotEmpty()
    }
}
