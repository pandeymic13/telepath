package dev.plume

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale
import java.util.TimeZone

class RoundFourTest {
    private val ist = ZoneId.of("Asia/Kolkata")
    private fun utc(y: Int, m: Int, d: Int, h: Int, min: Int = 0) = LocalDateTime.of(y, m, d, h, min).toInstant(ZoneOffset.UTC).toEpochMilli()

    // ---------- Up next ----------

    @Test fun liveEventsComeFirstThenTheSoonest() {
        val quali = SportEvent("f1|q", "F1 · United States GP", "Qualifying", utc(2026, 10, 24, 21), HOUR_MS)
        val bayern = SportEvent("bayern|1", "Freiburg vs Bayern Munich", "Bundesliga", utc(2026, 10, 25, 16, 30), 2 * HOUR_MS)
        val events = listOf(bayern, quali)
        assertEquals(quali, upNext(events, utc(2026, 10, 23, 12)))
        assertEquals(quali, upNext(events, utc(2026, 10, 24, 21, 30))) // live
        assertEquals(bayern, upNext(events, utc(2026, 10, 24, 22, 1))) // qualifying over
        assertNull(upNext(events, utc(2026, 10, 25, 18, 31)))
    }

    @Test fun aTestIsLiveDuringEachDaysPlayAndWaitsOvernight() {
        val test = SportEvent("india|t", "India vs England", "Test", utc(2026, 11, 1, 4), cricketWindow("Test"), days = 5)
        assertTrue(test.isLive(utc(2026, 11, 3, 6)))
        assertFalse(test.isLive(utc(2026, 11, 3, 13)))
        assertEquals(utc(2026, 11, 4, 4), test.nextStart(utc(2026, 11, 3, 13)))
        assertEquals(4, test.dayAt(utc(2026, 11, 4, 5)))
        assertEquals(test, upNext(listOf(test), utc(2026, 11, 3, 13)))
        assertNull(test.nextStart(utc(2026, 11, 5, 5)))
    }

    @Test fun whenTextSaysTodayTomorrowWeekdayOrDate() {
        val now = utc(2026, 10, 23, 6) // Fri 23 Oct, 11:30 IST
        val l = Locale.US
        assertEquals("Today, 9:30 PM", whenText(utc(2026, 10, 23, 16), now, ist, false, "Today", "Tomorrow", l))
        assertEquals("Tomorrow, 10:00 PM", whenText(utc(2026, 10, 24, 16, 30), now, ist, false, "Today", "Tomorrow", l))
        assertEquals("Sun, 02:30", whenText(utc(2026, 10, 24, 21), now, ist, true, "Today", "Tomorrow", l))
        assertEquals("Sun 1 Nov, 9:30 AM", whenText(utc(2026, 11, 1, 4), now, ist, false, "Today", "Tomorrow", l))
    }

    @Test fun sourceTimesAreReadAsUtc() {
        assertEquals(utc(2026, 10, 24, 21), jolpicaTime("2026-10-24", "21:00:00Z"))
        assertNull(jolpicaTime("2026-10-24", null))
        assertEquals(utc(2026, 10, 25, 16, 30), sportsDbTime("2026-10-25T16:30:00", null, null))
        assertEquals(utc(2026, 10, 25, 16, 30), sportsDbTime("2026-10-25T16:30:00+00:00", null, null))
        assertEquals(utc(2026, 10, 22, 7), sportsDbTime("22-10-2026 07:00:00", "2026-10-22", "07:00:00")) // cricket's day-first form
        assertEquals(utc(2026, 10, 25, 7), sportsDbTime("not a time", "2026-10-25", "07:00:00"))
        assertEquals(utc(2026, 10, 25, 7), sportsDbTime(null, "2026-10-25", "07:00:00"))
        assertEquals(utc(2026, 10, 25, 0), sportsDbTime(null, "2026-10-25", "TBC"))
        assertNull(sportsDbTime(null, null, "07:00:00"))
    }

    @Test fun cricketFormatsAndNames() {
        assertEquals("T20I", cricketFormat("Twenty20 International", "India vs Australia"))
        assertEquals("ODI", cricketFormat("One Day International", null))
        assertEquals("Test", cricketFormat("ICC World Test Championship", null))
        assertEquals("T20I", cricketFormat(null, "3rd T20I"))
        assertNull(cricketFormat("Indian Premier League", "Contest"))
        assertEquals("India", teamName("India Cricket"))
        assertEquals("United States GP", grandPrixName("United States Grand Prix"))
        assertEquals("Bundesliga", competitionName("German Bundesliga"))
        assertEquals("Semi-final", knockoutRound(150))
        assertEquals("Final", knockoutRound(200))
        assertNull(knockoutRound(16))
    }

    @Test fun finishedOrCalledOffEventsAreSkipped() {
        listOf("Match Finished", "FT", "AET", "PEN", "Postponed", "Cancelled", "Abandoned", "No Result").forEach { assertTrue(it, isOver(it)) }
        listOf(null, "", "Not Started", "NS", "1H", "Live").forEach { assertFalse("$it", isOver(it)) }
    }

    @Test fun seasonsAndTournamentYears() {
        assertEquals("2026-2027", uclSeason(2026, 10))
        assertEquals("2025-2026", uclSeason(2026, 5))
        assertEquals(2026, worldCupYear(2026, 6))
        assertNull(worldCupYear(2026, 10))
        assertNull(worldCupYear(2027, 3))
        assertEquals(2030, worldCupYear(2030, 1))
    }

    @Test fun refreshEverySixHoursOrSoonAfterAnEventEnds() {
        val fetched = utc(2026, 10, 25, 12)
        val match = SportEvent("bayern|1", "A vs B", "Bundesliga", utc(2026, 10, 25, 13), 2 * HOUR_MS)
        assertFalse(sportsStale(fetched, utc(2026, 10, 25, 14), listOf(match)))
        assertTrue(sportsStale(fetched, utc(2026, 10, 25, 15, 30), listOf(match)))
        assertTrue(sportsStale(fetched, utc(2026, 10, 25, 18), emptyList()))
        assertTrue(sportsStale(fetched, utc(2026, 10, 25, 11), emptyList()))
    }

    @Test fun eventCacheRoundTrips() {
        val e = SportEvent("india|9", "India vs England", "Test", 123L, 456L, 5)
        val back = decodeEvents(encodeEvents(listOf(e)) + "\nbroken line")
        assertEquals(1, back.size)
        assertEquals(listOf("india|9", "India vs England", "Test"), listOf(back[0].id, back[0].title, back[0].detail))
        assertEquals(listOf(123L, 456L, 5L), listOf(back[0].start, back[0].window, back[0].days.toLong()))
        assertTrue(decodeEvents(null).isEmpty())
    }

    // ---------- moods ----------

    @Test fun phasesFollowTheSun() {
        val rise = utc(2026, 10, 23, 0, 41) // 06:11 IST
        val set = utc(2026, 10, 23, 12, 20) // 17:50 IST
        assertEquals(Phase.NIGHT, phaseAt(rise - 50 * MINUTE_MS, rise, set))
        assertEquals(Phase.DAWN, phaseAt(rise - 30 * MINUTE_MS, rise, set))
        assertEquals(Phase.DAWN, phaseAt(rise + 30 * MINUTE_MS, rise, set))
        assertEquals(Phase.DAY, phaseAt(rise + 3 * HOUR_MS, rise, set))
        assertEquals(Phase.GOLDEN, phaseAt(set - 60 * MINUTE_MS, rise, set))
        assertEquals(Phase.SUNSET, phaseAt(set, rise, set))
        assertEquals(Phase.DUSK, phaseAt(set + 45 * MINUTE_MS, rise, set))
        assertEquals(Phase.NIGHT, phaseAt(set + 2 * HOUR_MS, rise, set))
        assertEquals(Phase.SUNSET, moodAt(set + 5 * MINUTE_MS, 17.385, 78.4867, TimeZone.getTimeZone(ist), null).phase) // Hyderabad
    }

    @Test fun weatherShapesTheLook() {
        assertEquals(Air.RAIN, airFor(skyFor(61)))
        assertEquals(Air.STORM, airFor(skyFor(95)))
        assertEquals(Air.CLEAR, airFor(skyFor(2)))
        assertEquals(Air.FOG, airFor(skyFor(45)))
        val clear = lookFor(Mood(Phase.SUNSET, Air.CLEAR))
        val rain = lookFor(Mood(Phase.SUNSET, Air.RAIN))
        assertEquals(0, clear.drops)
        assertTrue(rain.drops in 10..30)
        assertTrue(rain.dim > clear.dim)
        // Sunset is warm: red well above blue at the top of the screen.
        assertTrue(((clear.top shr 16) and 0xFF) > (clear.top and 0xFF) + 100)
        Phase.values().forEach { p -> Air.values().forEach { a -> assertTrue((lookFor(Mood(p, a)).top ushr 24) <= 0x66) } }
    }

    @Test fun paintingWashesEveryRowAndDrawsDropsOnlyInTheStrip() {
        val w = 40
        val px = IntArray(w * 10) { 0xFF808080.toInt() }
        val look = lookFor(Mood(Phase.SUNSET, Air.CLEAR))
        paintMood(px, w, 0, 10, 10, look, emptyList())
        assertTrue(px.all { ((it shr 16) and 0xFF) > (it and 0xFF) }) // warmer everywhere
        val plain = IntArray(w * 10) { 0xFF808080.toInt() }
        paintMood(plain, w, 100, 10, 200, MoodLook(0, 0, 0, 0f, 1), listOf(Drop(20f, 30f, 6f)))
        assertTrue(plain.all { it == 0xFF808080.toInt() }) // the drop is outside rows 100..109
        paintMood(plain, w, 25, 10, 200, MoodLook(0, 0, 0, 0f, 1), listOf(Drop(20f, 30f, 6f)))
        assertTrue(plain.any { it != 0xFF808080.toInt() })
        assertEquals(dropsFor(5, 1920, 1080, 7).map { it.x }, dropsFor(5, 1920, 1080, 7).map { it.x })
    }

    // ---------- All apps ----------

    @Test fun streamingAppsAreGroupedTogether() {
        assertTrue(isStreamingApp(AppTraits("com.netflix.ninja")))
        assertTrue(isStreamingApp(AppTraits("com.fancode.tv")))
        assertTrue(isStreamingApp(AppTraits("some.new.player", category = 2))) // CATEGORY_VIDEO
        assertFalse(isStreamingApp(AppTraits("com.esaba.downloader", category = 7)))
        assertFalse(isStreamingApp(AppTraits("com.sony.dtv.timers", publishesToHome = true, system = true)))
    }

    // ---------- Continue Watching art ----------

    @Test fun widePicturesForCloudStreamAndYouTube() {
        assertEquals(listOf("https://images.metahub.space/background/medium/tt0000001/img"),
            landscapeCandidates("https://wsrv.nl/?url=https://images.metahub.space/poster/small/tt0000001/img"))
        assertEquals(listOf("https://artworks.thetvdb.com/banners/movies/100/backgrounds/100.jpg"),
            landscapeCandidates("https://wsrv.nl/?url=https://artworks.thetvdb.com/banners/movies/100/posters/100.jpg"))
        assertEquals(listOf("https://artworks.thetvdb.com/banners/fanart/original/70001-1.jpg"),
            landscapeCandidates("https://wsrv.nl/?url=https%3A%2F%2Fartworks.thetvdb.com%2Fbanners%2Fposters%2F70001-2.jpg&w=300"))
        assertEquals(listOf("https://i.ytimg.com/vi/abcDEF12345/mqdefault.jpg"),
            landscapeCandidates("https://i.ytimg.com/vi/abcDEF12345/sddefault_custom_2.jpg?sqp=CNi16dYG&rs=AOn4CLDe"))
        assertTrue(landscapeCandidates("https://i.ytimg.com/vi/abcDEF12345/mqdefault.jpg").isEmpty())
        assertTrue(landscapeCandidates("https://img1.hotstarext.com/image/upload/f_auto,w_544/x-h").isEmpty())
    }

    @Test fun cloudStreamEpisodeTitlesSplit() {
        assertEquals(Triple("The Show", "4", "3"), splitEpisodeTitle("S4:E3 The Show"))
        assertEquals(Triple("A Film", null, null), splitEpisodeTitle("A Film"))
    }

    @Test fun youTubesRowsOnlyAddVideosYouStarted() {
        // Its Recommended, Subscriptions, Trending and Music rows are suggestions: an item there counts only with a
        // watch position, and YouTube gives none (a recommended video, 15:24 long, nothing watched).
        for (row in listOf("Recommended for you", "Subscriptions", "Trending", "YouTube Music")) assertFalse(isContinueChannel(row))
        assertFalse(hasResumeProgress(0, 924_000))
        assertTrue(hasResumeProgress(300_000, 924_000)) // one actually started would show
    }

    @Test fun newSeasonAlertsAndWatchlistItemsAreNotContinueWatching() {
        assertTrue(isStartedWatchNext(0)) // CONTINUE: started
        assertTrue(isStartedWatchNext(1)) // NEXT: the next episode of a series you're watching, as Google TV shows it
        assertFalse(isStartedWatchNext(2)) // NEW: Prime Video's "new season" cards
        assertFalse(isStartedWatchNext(3)) // WATCHLIST
    }

    @Test fun portraitPostersCropFromNearTheTop() {
        assertEquals(listOf(0, 0, 160, 90), cropRect(160, 90, 16, 9).toList())
        assertEquals(listOf(40, 0, 200, 90), cropRect(240, 90, 16, 9).toList())
        val r = cropRect(200, 300, 16, 9, bias = 0.3f) // 112 px tall band, 188 spare, 56 above
        assertEquals(listOf(0, 56, 200, 168), r.toList())
    }

    // ---------- tiles ----------

    @Test fun logoSizingIsByAreaWithinLimits() {
        val square = logoRect(100, 100)
        assertEquals(square[2], square[3], 0.001)
        assertEquals(0.11 * TILE_W * TILE_H, square[2] * square[3], 1.0)
        val wide = logoRect(1000, 100) // a long wordmark is capped at 64% of the width
        assertEquals(0.64 * TILE_W, wide[2], 0.001)
        assertEquals((TILE_W - wide[2]) / 2, wide[0], 0.001)
    }

    @Test fun brandTintKeepsColourfulBackgroundsOnly() {
        assertEquals(NEUTRAL_TINT.toList(), brandTint(doubleArrayOf(30.0, 30.0, 32.0)).toList())
        assertEquals(listOf(20.0, 60.0, 160.0), brandTint(doubleArrayOf(20.0, 60.0, 160.0)).toList())
        assertTrue(prefersSymbol("com.lagradost.cloudstream3.prerelease"))
        assertFalse(prefersSymbol("com.netflix.ninja"))
    }

    @Test fun aLogoIsCutFromAPlainBannerAndCentred() {
        val w = 160
        val h = 90
        // Dark blue banner with a white bar in the middle (a stand-in wordmark).
        val px = IntArray(w * h) { i ->
            val x = i % w
            val y = i / w
            if (x in 50 until 110 && y in 40 until 50) 0xFFFFFFFF.toInt() else 0xFF102040.toInt()
        }
        val tile = composeTile("com.example.app", Pixels(px, w, h), null, null, null)!!
        assertEquals(TILE_W * TILE_H, tile.size)
        val centre = tile[(TILE_H / 2) * TILE_W + TILE_W / 2]
        assertTrue((centre and 0xFF) > 230) // white logo in the middle
        val corner = tile[5 * TILE_W + 5]
        assertTrue((corner and 0xFF) < 90) // dark, blue-tinted tile around it
        assertTrue((corner and 0xFF) >= ((corner shr 16) and 0xFF))
    }
}
