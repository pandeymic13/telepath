package dev.plume

import org.junit.Assert.assertEquals
import org.junit.Test

class AllAppsOrderTest {
    private val installed = listOf("netflix", "youtube", "hotstar", "prime", "spotify", "files")

    @Test fun yourOrderFirstThenNewAppsInTheirDefaultOrder() {
        assertEquals(installed, orderAllApps(installed, emptyList()))
        assertEquals(listOf("spotify", "netflix", "youtube", "hotstar", "prime", "files"), orderAllApps(installed, listOf("spotify", "netflix")))
        // Apps uninstalled since are dropped; a repeated name counts once.
        assertEquals(listOf("prime", "youtube", "netflix", "hotstar", "spotify", "files"),
            orderAllApps(installed, listOf("gone", "prime", "youtube", "prime")))
    }

    @Test fun moveStepsAlongAndAcrossRowsWithinTheGroup() {
        val group = listOf("a", "b", "c", "d", "e", "f", "g", "h")
        assertEquals(listOf("a", "c", "b", "d", "e", "f", "g", "h"), moveInGroup(group, "b", 1))
        assertEquals(listOf("b", "a", "c", "d", "e", "f", "g", "h"), moveInGroup(group, "b", -1))
        // Down a row of six from the second place: the group has only eight, so it stops at the end.
        assertEquals(listOf("a", "c", "d", "e", "f", "g", "h", "b"), moveInGroup(group, "b", ALL_APPS_ROW))
        assertEquals(listOf("a", "h", "b", "c", "d", "e", "f", "g"), moveInGroup(group, "h", -ALL_APPS_ROW))
        // At the edges, or for an app that isn't in the group, nothing moves.
        assertEquals(group, moveInGroup(group, "a", -1))
        assertEquals(group, moveInGroup(group, "h", 1))
        assertEquals(group, moveInGroup(group, "x", 1))
    }

    private companion object {
        const val ALL_APPS_ROW = 6
    }
}
