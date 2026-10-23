package dev.plume

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class TelepathWidgetsTest {
    private val ist = TimeZone.getTimeZone("Asia/Kolkata")
    private val lat = 17.385 // Hyderabad
    private val lon = 78.4867

    private fun at(zone: TimeZone, y: Int, m: Int, d: Int, h: Int, min: Int) =
        Calendar.getInstance(zone).apply { clear(); set(y, m - 1, d, h, min) }.timeInMillis

    private fun at(y: Int, m: Int, d: Int, h: Int, min: Int) = at(ist, y, m, d, h, min)

    @Test fun sunriseAndSunsetMatchOpenMeteoForHyderabad() {
        // Open-Meteo, 22 Oct 2026: sunrise 06:11, sunset 17:50 IST.
        val (rise, set) = sunTimes(at(2026, 10, 22, 12, 0), lat, lon, ist)!!
        assertEquals(at(2026, 10, 22, 6, 11).toDouble(), rise.toDouble(), 120_000.0)
        assertEquals(at(2026, 10, 22, 17, 50).toDouble(), set.toDouble(), 120_000.0)
    }

    @Test fun solsticesInHyderabad() {
        val (juneRise, juneSet) = sunTimes(at(2026, 6, 21, 12, 0), lat, lon, ist)!!
        assertEquals(at(2026, 6, 21, 5, 42).toDouble(), juneRise.toDouble(), 120_000.0)
        assertEquals(at(2026, 6, 21, 18, 52).toDouble(), juneSet.toDouble(), 120_000.0)
        val (decRise, decSet) = sunTimes(at(2026, 12, 21, 12, 0), lat, lon, ist)!!
        assertEquals(at(2026, 12, 21, 6, 41).toDouble(), decRise.toDouble(), 120_000.0)
        assertEquals(at(2026, 12, 21, 17, 47).toDouble(), decSet.toDouble(), 120_000.0)
    }

    @Test fun theSameDayFromJustAfterMidnightToJustBefore() {
        assertEquals(sunTimes(at(2026, 10, 22, 0, 5), lat, lon, ist), sunTimes(at(2026, 10, 22, 23, 55), lat, lon, ist))
    }

    @Test fun automaticThemeFollowsTheSun() {
        assertTrue(autoDark(at(2026, 10, 22, 6, 0), lat, lon, ist))
        assertFalse(autoDark(at(2026, 10, 22, 6, 20), lat, lon, ist))
        assertFalse(autoDark(at(2026, 10, 22, 17, 45), lat, lon, ist))
        assertTrue(autoDark(at(2026, 10, 22, 17, 55), lat, lon, ist))
        assertTrue(autoDark(at(2026, 10, 22, 23, 30), lat, lon, ist))
    }

    @Test fun polarNightFallsBackToClockHours() {
        val svalbard = TimeZone.getTimeZone("Arctic/Longyearbyen")
        assertNull(sunTimes(at(svalbard, 2026, 12, 21, 12, 0), 78.22, 15.65, svalbard))
        assertFalse(autoDark(at(svalbard, 2026, 12, 21, 12, 0), 78.22, 15.65, svalbard))
        assertTrue(autoDark(at(svalbard, 2026, 12, 21, 21, 0), 78.22, 15.65, svalbard))
    }

    @Test fun forecastShowsTheNextSixWholeHours() {
        val times = (0..47).map { "2026-10-%02dT%02d:00".format(22 + it / 24, it % 24) }
        assertEquals((24..29).toList(), upcomingHours(times, "2026-10-22T23:45", 6))
        assertEquals((10..15).toList(), upcomingHours(times, "2026-10-22T09:00", 6))
        assertEquals(listOf(47), upcomingHours(times, "2026-10-23T22:30", 6))
    }

    @Test fun hourAndClockLabels() {
        assertEquals("12 AM", hourText("2026-10-23T00:00", use24 = false))
        assertEquals("1 PM", hourText("2026-10-23T13:00", use24 = false))
        assertEquals("13:00", hourText("2026-10-23T13:00", use24 = true))
        assertEquals("6:11 AM", clockText("2026-10-23T06:11", use24 = false))
        assertEquals("5:50 PM", clockText("2026-10-23T17:50", use24 = false))
        assertEquals("12:05 PM", clockText("2026-10-23T12:05", use24 = false))
        assertEquals("17:50", clockText("2026-10-23T17:50", use24 = true))
    }

    @Test fun weatherCodesAndUnits() {
        assertEquals(Sky.CLEAR, skyFor(0))
        assertEquals(Sky.MOSTLY_CLEAR, skyFor(1))
        assertEquals(Sky.PARTLY_CLOUDY, skyFor(2))
        assertEquals(Sky.CLOUDY, skyFor(3))
        assertEquals(Sky.FOG, skyFor(45))
        assertEquals(Sky.DRIZZLE, skyFor(53))
        assertEquals(Sky.RAIN, skyFor(63))
        assertEquals(Sky.RAIN, skyFor(81))
        assertEquals(Sky.SNOW, skyFor(75))
        assertEquals(Sky.THUNDER, skyFor(95))
        assertEquals(27, displayTemp(26.6, "c"))
        assertEquals(80, displayTemp(26.6, "f"))
        assertEquals(WeatherIcon.Kind.MOON_CLOUD, WeatherIcon.kindFor(Sky.MOSTLY_CLEAR, day = false))
        assertEquals(WeatherIcon.Kind.SUN, WeatherIcon.kindFor(Sky.CLEAR, day = true))
    }

    @Test fun quoteNamesChangesAndCurrencies() {
        assertEquals("NIFTY 50", quoteName("^NSEI", "NIFTY 50"))
        assertEquals("NVIDIA", quoteName("NVDA", "NVIDIA Corporation"))
        assertEquals("Apple", quoteName("AAPL", "Apple Inc."))
        assertEquals("Reliance Industries", quoteName("RELIANCE.NS", "Reliance Industries Limited"))
        assertEquals("RELIANCE", quoteName("RELIANCE.NS", null))
        assertEquals("+1.3%", changeText(101.3, 100.0))
        assertEquals("\u22120.8%", changeText(99.2, 100.0))
        assertEquals("+0.0%", changeText(99.99, 100.0))
        assertEquals("$", currencyPrefix("USD"))
        assertEquals("₹", currencyPrefix("INR"))
        assertEquals("CHF ", currencyPrefix("CHF"))
        assertEquals("", currencyPrefix(null))
    }

    /** Names as Yahoo Finance gives them (Indian and German listings have all-capital short names). */
    @Test fun quoteNamesPreferTheOneWithSmallLetters() {
        assertEquals("Reliance Industries", quoteName("RELIANCE.NS", "RELIANCE INDUSTRIES LTD", "Reliance Industries Limited"))
        assertEquals("Tata Consultancy Services", quoteName("TCS.NS", "TATA CONSULTANCY SERV LT", "Tata Consultancy Services Limited"))
        assertEquals("HDFC Bank", quoteName("HDFCBANK.NS", "HDFC BANK LTD", "HDFC Bank Limited"))
        assertEquals("Bayerische Motoren Werke",
            quoteName("BMW.DE", "BAYERISCHE MOTOREN WERKE AG   S", "Bayerische Motoren Werke Aktiengesellschaft"))
        assertEquals("Microsoft", quoteName("MSFT", "Microsoft Corporation", "Microsoft Corporation"))
        assertEquals("NVIDIA", quoteName("NVDA", "NVIDIA Corporation", "NVIDIA Corporation"))
        assertEquals("SAP", quoteName("SAP.DE", "SAP SE", "SAP SE"))
        assertEquals("SENSEX", quoteName("^BSESN", "S&P BSE SENSEX", "S&P BSE SENSEX"))
        assertEquals("HDFCBANK", quoteName("HDFCBANK.NS", "", " "))
    }

    @Test fun inputsPutThePrimaryPortFirstThenHdmiByNumber() {
        val labels = listOf("Video", "HDMI 3 (eARC/ARC)", "HDMI 2", "HDMI 1")
        assertEquals(listOf("HDMI 1", "HDMI 2", "HDMI 3 (eARC/ARC)", "Video"), labels.sortedBy { inputRank(it, port = 1) })
        assertEquals(listOf("HDMI 3 (eARC/ARC)", "HDMI 1", "HDMI 2", "Video"), labels.sortedBy { inputRank(it, port = 3) })
        assertEquals(listOf("HDMI 1", "HDMI 2", "HDMI 3 (eARC/ARC)", "Video"), labels.sortedBy { inputRank(it, port = 0) })
        assertEquals(10, hdmiNumber("HDMI 10"))
        assertEquals(3, hdmiNumber("HDMI 3 (eARC/ARC)"))
        assertNull(hdmiNumber("Video"))
    }

    @Test fun slideshowShowsEveryPhotoOncePerRound() {
        val names = listOf("a.jpg", "b.jpg", "c.jpg")
        var deck = emptyList<String>()
        var current: String? = null
        val shown = ArrayList<String>()
        repeat(3) {
            val (next, rest) = nextSlide(names, deck, current) { it }
            shown += next
            deck = rest
            current = next
        }
        assertEquals(names, shown)
        assertTrue(deck.isEmpty())
    }

    @Test fun slideshowNeverShowsTheSamePhotoTwiceInARow() {
        val names = listOf("a.jpg", "b.jpg", "c.jpg")
        // A new round shuffled to start with the photo on show moves it to the end.
        val (next, rest) = nextSlide(names, emptyList(), current = "a.jpg") { it }
        assertEquals("b.jpg", next)
        assertEquals(listOf("c.jpg", "a.jpg"), rest)
        // Photos deleted from the folder mid-round are skipped.
        assertEquals("c.jpg" to emptyList<String>(), nextSlide(listOf("a.jpg", "c.jpg"), listOf("b.jpg", "c.jpg"), "a.jpg") { it })
        // A single photo still shows.
        assertEquals("a.jpg" to emptyList<String>(), nextSlide(listOf("a.jpg"), emptyList(), "a.jpg") { it })
    }

    @Test fun newlySyncedPhotosJoinTheRoundUnderWay() {
        val names = listOf("a.jpg", "b.jpg", "c.jpg", "d.jpg", "e.jpg")
        // a and b shown (b on show), c left in this round; d and e just synced from the PC.
        val (next, rest) = nextSlide(names, listOf("c.jpg"), "b.jpg", added = listOf("d.jpg", "e.jpg")) { it.reversed() }
        assertEquals(listOf("e.jpg", "d.jpg", "c.jpg"), listOf(next) + rest)
        // Photos on show or still to come aren't added twice.
        assertEquals("c.jpg" to emptyList<String>(), nextSlide(names, listOf("c.jpg"), "b.jpg", added = listOf("b.jpg", "c.jpg")) { it })
        // First run after the update, nothing known yet: everything but the photo on show joins.
        val (n3, r3) = nextSlide(names, listOf("c.jpg"), "b.jpg", added = names) { it }
        assertEquals(setOf("a.jpg", "c.jpg", "d.jpg", "e.jpg"), (listOf(n3) + r3).toSet())
        assertEquals(4, r3.size + 1)
    }
}
