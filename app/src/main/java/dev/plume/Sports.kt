package dev.plume

import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit
import java.util.Locale

internal const val MINUTE_MS = 60_000L
internal const val HOUR_MS = 60 * MINUTE_MS
internal const val DAY_MS = 24 * HOUR_MS

/**
 * One fixture or session for the "Up next" line beside the clock. A Test match is one event over [days] days,
 * each day's play [window] long; everything else is a single [window] from [start] (epoch millis).
 */
internal class SportEvent(
    val id: String,
    val title: String,
    val detail: String,
    val start: Long,
    val window: Long,
    val days: Int = 1,
) {
    val end get() = start + (days - 1) * DAY_MS + window

    fun isLive(now: Long) = (0 until days).any { d -> now >= start + d * DAY_MS && now < start + d * DAY_MS + window }

    /** When play next begins after [now] (a later day of a Test included), or null if it won't. */
    fun nextStart(now: Long): Long? = (0 until days).map { start + it * DAY_MS }.firstOrNull { it > now }

    /** 1 on the first day; for a Test in progress, the day being (or next to be) played. */
    fun dayAt(now: Long) = ((maxOf(now, start) - start) / DAY_MS).toInt().coerceIn(0, days - 1) + 1
}

/** What the line shows: a live event (the latest to start), else whichever begins next. */
internal fun upNext(events: List<SportEvent>, now: Long): SportEvent? =
    events.filter { it.isLive(now) }.maxByOrNull { it.start }
        ?: events.mapNotNull { e -> e.nextStart(now)?.let { e to it } }.minByOrNull { it.second }?.first

/** The second line's "when": "Today, 9:30 pm", "Tomorrow, 2:30 am", "Sat, 9:30 pm" this week, else "Sat 31 Oct, 9:30 pm". */
internal fun whenText(at: Long, now: Long, zone: ZoneId, use24: Boolean, today: String, tomorrow: String,
                      locale: Locale = Locale.getDefault()): String {
    val t = Instant.ofEpochMilli(at).atZone(zone)
    val days = ChronoUnit.DAYS.between(Instant.ofEpochMilli(now).atZone(zone).toLocalDate(), t.toLocalDate())
    val day = when (days) {
        0L -> today
        1L -> tomorrow
        in 2L..6L -> t.format(DateTimeFormatter.ofPattern("EEE", locale))
        else -> t.format(DateTimeFormatter.ofPattern("EEE d MMM", locale))
    }
    return day + ", " + t.format(DateTimeFormatter.ofPattern(if (use24) "HH:mm" else "h:mm a", locale))
}

// ---------- F1 (Jolpica, the Ergast successor) ----------

/** "United States Grand Prix" → "United States GP". */
internal fun grandPrixName(race: String) = race.replace("Grand Prix", "GP").trim()

/** How long a session usually runs, so "Live now" ends with it. */
internal fun f1Window(session: String) = when (session) {
    F1_RACE -> 2 * HOUR_MS
    F1_SPRINT_QUALIFYING -> 45 * MINUTE_MS
    else -> HOUR_MS
}

internal const val F1_QUALIFYING = "Qualifying"
internal const val F1_SPRINT_QUALIFYING = "Sprint Qualifying"
internal const val F1_SPRINT = "Sprint"
internal const val F1_RACE = "Race"

/** Jolpica's "2026-10-25" and "20:00:00Z" (UTC) as epoch millis; null if either is missing or malformed. */
internal fun jolpicaTime(date: String?, time: String?): Long? = try {
    if (date.isNullOrBlank() || time.isNullOrBlank()) null
    else LocalDate.parse(date).atTime(LocalTime.parse(time.trim().removeSuffix("Z").take(8))).toInstant(ZoneOffset.UTC).toEpochMilli()
} catch (e: DateTimeParseException) {
    null
}

// ---------- TheSportsDB (football and cricket) ----------

/**
 * TheSportsDB's kick-off in UTC. strTimestamp comes as "2026-10-25T16:30:00" (sometimes with an offset) or, for
 * some cricket, "22-10-2026 07:00:00"; failing that, the date and "16:30:00". Null when there's no usable date.
 */
internal fun sportsDbTime(timestamp: String?, date: String?, time: String?): Long? {
    timestamp?.let { stamp(it.trim()) }?.let { return it }
    if (date.isNullOrBlank()) return null
    val clock = time?.trim()?.take(8)?.takeIf { Regex("\\d{2}:\\d{2}(:\\d{2})?").matches(it) } ?: "00:00:00"
    return stamp(date.trim() + "T" + clock)
}

private val ISO_STAMP = Regex("^(\\d{4})-(\\d{2})-(\\d{2})[T ](\\d{2}):(\\d{2})(?::(\\d{2}))?")
private val DAY_FIRST_STAMP = Regex("^(\\d{2})-(\\d{2})-(\\d{4})[T ](\\d{2}):(\\d{2})(?::(\\d{2}))?")

private fun stamp(text: String): Long? {
    val iso = ISO_STAMP.find(text)
    val dayFirst = if (iso == null) DAY_FIRST_STAMP.find(text) else null
    val g = (iso ?: dayFirst ?: return null).groupValues
    val (y, m, d) = if (iso != null) Triple(g[1], g[2], g[3]) else Triple(g[3], g[2], g[1])
    return try {
        LocalDateTime.of(y.toInt(), m.toInt(), d.toInt(), g[4].toInt(), g[5].toInt(), g[6].ifEmpty { "0" }.toInt())
            .toInstant(ZoneOffset.UTC).toEpochMilli()
    } catch (e: DateTimeException) {
        null
    }
}

/** Finished, called off or moved: nothing to look forward to. */
internal fun isOver(status: String?): Boolean {
    val s = status?.trim()?.lowercase(Locale.ROOT).orEmpty()
    if (s.isEmpty()) return false
    return s in setOf("ft", "aet", "pen", "aot", "awd", "wo", "canc", "pst", "abd") ||
        listOf("finished", "ended", "postponed", "cancel", "abandon", "result", "won by", "drawn", "tied").any { it in s }
}

/** "Test", "ODI" or "T20I" from the competition and event names; null if neither says. */
internal fun cricketFormat(league: String?, event: String?): String? {
    val s = "${league.orEmpty()} ${event.orEmpty()}".lowercase(Locale.ROOT)
    return when {
        Regex("\\btest\\b").containsMatchIn(s) -> "Test"
        Regex("\\bodi\\b|one[ -]day").containsMatchIn(s) -> "ODI"
        Regex("\\bt20i?\\b|twenty20").containsMatchIn(s) -> "T20I"
        else -> null
    }
}

/** A day's play: Test 7.5 h (over five days), ODI 9 h, T20 4 h; unknown formats get a whole-day 8 h. */
internal fun cricketWindow(format: String?) = when (format) {
    "Test" -> 7 * HOUR_MS + 30 * MINUTE_MS
    "ODI" -> 9 * HOUR_MS
    "T20I" -> 4 * HOUR_MS
    else -> 8 * HOUR_MS
}

/** TheSportsDB names national cricket teams "India Cricket". */
internal fun teamName(name: String) = name.trim().removeSuffix(" Cricket").trim()

/** Friendlier competition names for the second line. */
internal fun competitionName(league: String?): String {
    val l = league?.trim().orEmpty()
    return when {
        l.equals("German Bundesliga", true) -> "Bundesliga"
        l.equals("UEFA Champions League", true) -> "Champions League"
        l.equals("FIFA World Cup", true) -> "World Cup"
        else -> l
    }
}

/** "Semi-final" or "Final" from TheSportsDB's knockout round numbers (150 and 200), else null. */
internal fun knockoutRound(round: Int?) = when (round) {
    150 -> "Semi-final"
    200 -> "Final"
    else -> null
}

/** The UEFA season whose semi-finals and final are next: "2026-2027" from July 2026 to June 2027. */
internal fun uclSeason(year: Int, month: Int) = if (month >= 7) "$year-${year + 1}" else "${year - 1}-$year"

/** The World Cup year whose semi-finals and final are worth asking about: only in a tournament year, until it ends. */
internal fun worldCupYear(year: Int, month: Int): Int? = if (year % 4 == 2 && month <= 7) year else null

/** Ask again every 6 hours, and within half an hour once a cached event has finished (its "next" is known by then). */
internal fun sportsStale(fetchedAt: Long, now: Long, events: List<SportEvent>): Boolean =
    now < fetchedAt || now - fetchedAt >= 6 * HOUR_MS ||
        (now - fetchedAt >= 30 * MINUTE_MS && events.any { it.end in (fetchedAt + 1)..now })

/** The cache in preferences: one event per line, tab-separated. */
internal fun encodeEvents(events: List<SportEvent>): String = events.joinToString("\n") { e ->
    listOf(e.id, e.title, e.detail, e.start.toString(), e.window.toString(), e.days.toString())
        .joinToString("\t") { it.replace('\t', ' ').replace('\n', ' ') }
}

internal fun decodeEvents(text: String?): List<SportEvent> = text.orEmpty().lines().mapNotNull { line ->
    val p = line.split('\t')
    if (p.size != 6) return@mapNotNull null
    val start = p[3].toLongOrNull() ?: return@mapNotNull null
    val window = p[4].toLongOrNull() ?: return@mapNotNull null
    val days = p[5].toIntOrNull() ?: return@mapNotNull null
    SportEvent(p[0], p[1], p[2], start, window, days)
}
