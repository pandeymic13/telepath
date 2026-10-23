package dev.plume

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.TimeZone

/** telepath.properties as the app reads it: lists, followed teams, the weather place and its fallbacks. */
class TelepathConfigTest {
    @Test fun followedTeamsNeedAnIdAndAName() {
        assertEquals(listOf(FollowedTeam(133664, "FC Bayern Munich"), FollowedTeam(137143, "India")),
            parseTeams(" 133664 : FC Bayern Munich ,137143:India"))
        // Repeats, entries without an ID or a name, and empty settings are skipped.
        assertEquals(listOf(FollowedTeam(1, "A")), parseTeams("1:A,1:Again,x:B,2:,:C,3"))
        assertEquals(emptyList<FollowedTeam>(), parseTeams(""))
        assertEquals(parseTeams(BuildConfig.UPNEXT_TEAMS), FOLLOWED_TEAMS)
    }

    @Test fun followedThingsReadAsASentence() {
        assertEquals("", joinWithAnd(emptyList(), "and"))
        assertEquals("F1", joinWithAnd(listOf("F1"), "and"))
        assertEquals("India and F1", joinWithAnd(listOf("India", "F1"), "and"))
        assertEquals("Bayern, India, and F1", joinWithAnd(listOf("Bayern", "India", "F1"), "and"))
    }

    @Test fun helpGoesToYourProjectElsePlumesGuide() {
        assertEquals("https://github.com/aradhyas8/Plume_Launcher#home-button-on-android-13-and-newer", helpUrl(""))
        assertEquals("https://github.com/someone/telepath#home-button-on-android-13-and-newer",
            helpUrl("https://github.com/someone/telepath/"))
    }

    @Test fun theConfiguredPlaceWinsElseTheTimeZoneGuides() {
        val pune = Place("Pune", 18.52f, 73.86f)
        assertEquals(18.52f, fallbackLat(pune))
        assertEquals(73.86f, fallbackLon(pune, TimeZone.getTimeZone("UTC")))
        // No place set: the equator, in the middle of the TV's time zone (15° of longitude per hour).
        assertEquals(0f, fallbackLat(null))
        assertEquals(82.5f, fallbackLon(null, TimeZone.getTimeZone("Asia/Kolkata")))
        assertEquals(-75f, fallbackLon(null, TimeZone.getTimeZone("America/New_York")))
        assertEquals(0f, fallbackLon(null, TimeZone.getTimeZone("UTC")))
        assertEquals(180f, fallbackLon(null, TimeZone.getTimeZone("Pacific/Kiritimati")))
        val place = DEFAULT_PLACE
        if (BuildConfig.WEATHER_CITY.isEmpty()) assertNull(place)
        else assertEquals(Place(BuildConfig.WEATHER_CITY, BuildConfig.WEATHER_LAT, BuildConfig.WEATHER_LON), place)
    }

    @Test fun theAutomaticPlaceNeedsACityAndRealCoordinates() {
        assertEquals(Place("Hyderabad", 17.385f, 78.4867f), ipPlace(true, " Hyderabad ", 17.385, 78.4867))
        assertNull(ipPlace(false, "Hyderabad", 17.385, 78.4867))
        assertNull(ipPlace(true, "", 17.385, 78.4867))
        assertNull(ipPlace(true, null, 17.385, 78.4867))
        assertNull(ipPlace(true, "Nowhere", Double.NaN, 78.4867))
        assertNull(ipPlace(true, "Nowhere", 95.0, 78.4867))
        assertNull(ipPlace(true, "Nowhere", 17.385, 181.0))
    }
}
