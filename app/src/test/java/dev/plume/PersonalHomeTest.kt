package dev.plume

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonalHomeTest {
    @Test fun favouritesListsAreTrimmedWithoutGapsOrRepeats() {
        assertEquals(listOf("com.netflix.ninja", "com.google.android.youtube.tv"),
            parseList(" com.netflix.ninja, ,com.google.android.youtube.tv,com.netflix.ninja,"))
        assertEquals(emptyList<String>(), parseList(""))
        assertEquals(parseList(BuildConfig.FAVOURITES), DEFAULT_FAVOURITES)
        assertEquals(DEFAULT_FAVOURITES.distinct(), DEFAULT_FAVOURITES)
    }

    @Test fun otherAndNewAppsStayInAllAppsUntilExplicitlyAdded() {
        val favourites = listOf("com.netflix.ninja", "com.google.android.youtube.tv")
        val installed = favourites + listOf("other", "new", "hdmi:1")
        assertEquals(setOf("other", "new", "hdmi:1"), hiddenHomeApps(installed, emptySet(), emptySet(), favourites))
        assertEquals(setOf("new", "hdmi:1", favourites[0]),
            hiddenHomeApps(installed, setOf(favourites[0]), setOf("other"), favourites))
    }

    @Test fun dayAndNightSwitchAtSevenAndNineteen() {
        assertTrue(isNightHour(0))
        assertTrue(isNightHour(6))
        assertFalse(isNightHour(7))
        assertFalse(isNightHour(18))
        assertTrue(isNightHour(19))
        assertTrue(isNightHour(23))
    }

    @Test fun widgetsPageRestsWithTheCardsJustAboveTheBottomEdge() {
        assertEquals(302, widgetsPageY(cardsBottom = 812, gap = 30, viewport = 540, maxScroll = 460))
        assertEquals(460, widgetsPageY(cardsBottom = 1000, gap = 30, viewport = 540, maxScroll = 460))
        assertEquals(0, widgetsPageY(cardsBottom = 100, gap = 30, viewport = 540, maxScroll = 460))
    }

    @Test fun primaryInputNeverConfusesHdmiOneAndTenOrCecDeviceNames() {
        assertTrue(isHdmiOneLabel("HDMI 1"))
        assertTrue(isHdmiOneLabel(" HDMI1 (ARC) "))
        assertFalse(isHdmiOneLabel("HDMI 10"))
        assertFalse(isHdmiOneLabel("HDMI 2"))
        assertFalse(isHdmiOneLabel("Xbox"))
        assertTrue(isPrimaryInput("HDMI 2", port = 2))
        assertFalse(isPrimaryInput("HDMI 1", port = 2))
        assertFalse(isPrimaryInput("HDMI 1", port = 0))
    }

    @Test fun primaryInputTakesTheNameAndIconSetForIt() {
        assertEquals("Xbox" to "xbox", primaryInputLook("Xbox", "xbox", "Living room box", "HDMI 1"))
        // Automatic icon: from the name set, else the device's own name over HDMI-CEC, else the port's.
        assertEquals("PlayStation 5" to "playstation", primaryInputLook("", "auto", "PlayStation 5", "HDMI 1"))
        assertEquals("Console" to "playback", primaryInputLook("Console", "auto", null, "HDMI 1"))
        assertEquals("HDMI 1" to "hdmi", primaryInputLook(" ", "auto", null, "HDMI 1"))
    }

    @Test fun onlyResumeChannelsOrRealProgressQualifyForPreviewFallback() {
        assertTrue(isContinueChannel(" Continue Watching "))
        assertTrue(isContinueChannel("Watch Next"))
        assertFalse(isContinueChannel("Trending now"))
        assertFalse(hasResumeProgress(0, 10_000))
        assertFalse(hasResumeProgress(999, 10_000))
        assertTrue(hasResumeProgress(1000, 10_000))
        assertFalse(hasResumeProgress(10_000, 10_000))
        assertTrue(hasResumeProgress(60_000, 0))
    }
}
