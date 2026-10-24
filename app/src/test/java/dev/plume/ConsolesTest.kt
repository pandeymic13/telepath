package dev.plume

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ConsolesTest {
    @Test fun playStationsThatCallThemselvesPsAreRecognised() {
        assertEquals("playstation", hdmiIcon("PS5", true))
        assertEquals("playstation", hdmiIcon("PS4", true))
        assertEquals("playstation", hdmiIcon("ps 5", true))
        assertEquals("playstation", hdmiIcon("PlayStation 5", true))
        // "ps" inside another word isn't a PlayStation.
        assertEquals("playback", hdmiIcon("Apps 3", true))
        assertEquals("hdmi", hdmiIcon("GPS 4", false))
    }

    @Test fun seenDevicesSurviveBeingWrittenDown() {
        val xbox = SeenDevice("xbox", "Xbox Series X")
        assertEquals(xbox, decodeSeen(encodeSeen(xbox)))
        assertEquals(SeenDevice("playback", "Fire TV\nStick"), decodeSeen("playback\nFire TV\nStick"))
        assertNull(decodeSeen(null))
        assertNull(decodeSeen("xbox"))
        assertNull(decodeSeen("\nXbox"))
    }

    @Test fun eachPortRemembersItsLatestDeviceAndAMovedOneLeavesItsOldPort() {
        val first = updateSeen(emptyMap(), "HW1", "Xbox Series X")
        assertEquals(mapOf("HW1" to SeenDevice("xbox", "Xbox Series X")), first)
        val both = updateSeen(first, "HW3", "PS5")
        assertEquals(mapOf("HW1" to SeenDevice("xbox", "Xbox Series X"), "HW3" to SeenDevice("playstation", "PS5")), both)
        // The PS5 moved to HDMI 2: HDMI 3 forgets it, HDMI 1 keeps its Xbox.
        val moved = updateSeen(both, "HW2", "PS5")
        assertEquals(mapOf("HW1" to SeenDevice("xbox", "Xbox Series X"), "HW2" to SeenDevice("playstation", "PS5")), moved)
        // A different device on a port replaces what it remembered there.
        assertEquals(SeenDevice("nintendoswitch", "Nintendo Switch"), updateSeen(moved, "HW1", " Nintendo Switch ")["HW1"])
        // Seeing the same device again changes nothing.
        assertEquals(moved, updateSeen(moved, "HW2", "PS5"))
    }

    @Test fun consolesHaveTheirOwnColours() {
        assertEquals(0xFF107C10.toInt(), consoleAccent("xbox"))
        assertEquals(0xFF0070D1.toInt(), consoleAccent("playstation"))
        assertEquals(0xFFE60012.toInt(), consoleAccent("nintendoswitch"))
        assertNotNull(consoleAccent("appletv"))
        assertNull(consoleAccent("hdmi"))
        assertNull(consoleAccent("playback"))
        assertNull(consoleAccent("settop"))
    }
}
