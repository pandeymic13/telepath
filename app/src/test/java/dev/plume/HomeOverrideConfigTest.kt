package dev.plume

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class HomeOverrideConfigTest {
    private val config = File("src/main/res/xml/home_override.xml").readText()

    /**
     * Key filtering routes every remote button through accessibility; on TVs Android then handles each volume press
     * once and drops the repeats of a held button (holding Volume moved one step).
     */
    @Test fun theHomeServiceNeverFiltersRemoteButtons() {
        val attributes = Regex("<accessibility-service[\\s\\S]*?/>").find(config)!!.value
        assertFalse(attributes.contains("canRequestFilterKeyEvents=\"true\""))
        assertFalse(attributes.contains("flagRequestFilterKeyEvents"))
        assertFalse(File("src/main/java/dev/plume/HomeOverride.kt").readText().contains("onKeyEvent"))
    }

    @Test fun itOnlyWatchesGoogleTvWindowsAndCannotReadTheScreen() {
        assertTrue(config.contains("android:packageNames=\"com.google.android.apps.tv.launcherx\""))
        assertTrue(config.contains("android:accessibilityEventTypes=\"typeWindowStateChanged\""))
        assertTrue(config.contains("android:canRetrieveWindowContent=\"false\""))
    }
}
