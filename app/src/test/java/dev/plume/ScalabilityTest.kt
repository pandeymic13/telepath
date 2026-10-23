package dev.plume

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Apps installed, removed or reinstalled later: their group, tile style, plain icon fallback and cached tiles. */
class ScalabilityTest {
    private fun r(c: Int) = (c shr 16) and 0xFF
    private fun g(c: Int) = (c shr 8) and 0xFF
    private fun b(c: Int) = c and 0xFF
    private fun lum(c: Int) = (0.2126 * r(c) + 0.7152 * g(c) + 0.0722 * b(c)) / 255

    // ---------- groups ----------

    @Test fun everyAppOnThisTvIsGroupedFromItsOwnManifest() {
        // As this TV reports them (category, asks to publish home-screen rows, came with the TV, updated since), with no
        // list of known services at all: only PlayZ TV, which declares nothing, needs the list.
        val streaming = listOf(
            AppTraits("com.netflix.ninja", -1, publishesToHome = true, system = true, updated = true),
            AppTraits("com.google.android.youtube.tv", -1, publishesToHome = true, system = true, updated = true),
            AppTraits("com.amazon.amazonvideo.livingroom", -1, publishesToHome = true, system = true, updated = true),
            AppTraits("com.sonypicturescore", -1, publishesToHome = true, system = true, updated = true),
            AppTraits("com.google.android.youtube.tvmusic", 1, system = true, updated = true),
            AppTraits("in.startv.hotstar", -1, publishesToHome = true),
            AppTraits("com.apple.atve.androidtv.appletv", -1, publishesToHome = true),
            AppTraits("com.graymatrix.did", -1, publishesToHome = true),
            AppTraits("com.sonyliv", -1, publishesToHome = true),
            AppTraits("com.fancode.tv", -1, publishesToHome = true),
            AppTraits("com.spotify.tv.android", 1, publishesToHome = true),
            AppTraits("com.lagradost.cloudstream3.prerelease", 2, publishesToHome = true),
        )
        val utilities = listOf(
            AppTraits("com.android.vending", 7, system = true, updated = true),
            AppTraits("com.esaba.downloader", 7),
            AppTraits("com.cxinventor.file.explorer", 7),
            AppTraits("com.realtek.tvmediaplay", -1, system = true),
            AppTraits("com.android.tv", -1, publishesToHome = true, system = true),
            AppTraits("com.sony.dtv.ecodashboard", -1, publishesToHome = true, system = true),
            AppTraits("com.sony.dtv.m2.seeds.iot", -1, publishesToHome = true, system = true),
            AppTraits("com.sony.dtv.timers", -1, publishesToHome = true, system = true),
            AppTraits("com.sony.dtv.smarthelp", -1, system = true),
        )
        for (t in streaming) assertTrue(t.pkg, isStreamingApp(t, known = emptySet()))
        for (t in utilities) assertFalse(t.pkg, isStreamingApp(t, known = emptySet()))
        val playz = AppTraits("com.playz.tv", -1)
        assertFalse(isStreamingApp(playz, known = emptySet()))
        assertTrue(isStreamingApp(playz))
    }

    @Test fun newAppsAreGroupedByWhatTheyDeclare() {
        assertTrue(isStreamingApp(AppTraits("com.example.newservice", publishesToHome = true))) // installed by you
        assertTrue(isStreamingApp(AppTraits("com.example.radio", category = 1)))
        assertTrue(isStreamingApp(AppTraits("com.example.player", category = 2)))
        assertFalse(isStreamingApp(AppTraits("com.example.game", category = 0, publishesToHome = true)))
        assertFalse(isStreamingApp(AppTraits("com.example.files", category = 7)))
        assertFalse(isStreamingApp(AppTraits("com.example.browser"))) // declares nothing, publishes nothing
        assertFalse(isStreamingApp(AppTraits("com.example.preinstalled", publishesToHome = true, system = true))) // the TV's own, never updated
        assertFalse(isStreamingApp(AppTraits("com.sony.dtv.newtool", publishesToHome = true, system = true, updated = true)))
    }

    @Test fun yourChoiceOfGroupWinsSurvivesReinstallsAndCanBeUndone() {
        val (s, u) = moveGroup("com.example.vlc", streaming = false, automatic = true, emptySet(), emptySet())
        assertEquals(setOf("com.example.vlc"), u)
        assertTrue(s.isEmpty())
        assertFalse(inStreaming("com.example.vlc", automatic = true, s, u)) // kept by package name, so a reinstall keeps it
        val (s2, u2) = moveGroup("com.example.vlc", streaming = true, automatic = true, s, u) // back where it belongs
        assertTrue(s2.isEmpty() && u2.isEmpty())
        assertTrue(inStreaming("com.example.vlc", automatic = true, s2, u2))
        val (s3, _) = moveGroup("com.example.tool", streaming = true, automatic = false, emptySet(), emptySet())
        assertTrue(inStreaming("com.example.tool", automatic = false, s3, emptySet()))
    }

    // ---------- tiles ----------

    @Test fun tileStylesFollowTheGroupAndYourChoice() {
        assertEquals(TileStyle.LOGO, tileStyle("com.netflix.ninja", streaming = true, plainIcon = false))
        assertEquals(TileStyle.SYMBOL, tileStyle("com.example.files", streaming = false, plainIcon = false))
        assertEquals(TileStyle.SYMBOL, tileStyle("com.lagradost.cloudstream3.prerelease", streaming = true, plainIcon = false))
        assertEquals(TileStyle.ICON, tileStyle("com.netflix.ninja", streaming = true, plainIcon = true))
    }

    @Test fun tileFilesSayWhoseTheyAreAndWhichVersion() {
        val a = tileName("com.foo", 12, 1_700_000_000_000, 0, TileStyle.LOGO)
        val b = tileName("com.foo_bar", 3, 1_700_000_000_001, 0, TileStyle.SYMBOL)
        assertEquals("com.foo", tileOwner(a))
        assertEquals("com.foo_bar", tileOwner(b)) // packages that start alike are told apart
        assertEquals("com.foo", tileOwner("$a-light.png"))
        assertNotEquals(a, tileName("com.foo", 12, 1_700_000_000_999, 0, TileStyle.LOGO)) // reinstalled: new tiles
        assertNotEquals(a, tileName("com.foo", 12, 1_700_000_000_000, 0, TileStyle.ICON)) // other style: new tiles
        assertTrue(tileName("in.startv.hotstar", 1, 2, 2, TileStyle.LOGO).endsWith("-rb2"))
    }

    /** A square, opaque red icon (a legacy one, as many TV apps ship). */
    private fun redIcon(side: Int = 96) = Pixels(IntArray(side * side) { 0xFFE53935.toInt() }, side, side)

    @Test fun thePlainTileShowsTheIconOnTheThemesPlate() {
        for (light in listOf(true, false)) {
            val tile = composeIconTile(redIcon(), light)
            assertEquals(TILE_W * TILE_H, tile.size)
            val corner = tile[4 * TILE_W + 4]
            if (light) assertTrue("white plate by day", lum(corner) > 0.9) else assertTrue("charcoal plate by night", lum(corner) < 0.25)
            val centre = tile[(TILE_H / 2) * TILE_W + TILE_W / 2]
            assertTrue("the icon in the middle", r(centre) > 200 && g(centre) < 90)
            // The icon's square has rounded corners: its own corner shows the plate, a little way in shows the icon.
            val side = 0.58 * TILE_H
            val x0 = ((TILE_W - side) / 2).toInt() + 1
            val y0 = ((TILE_H - side) / 2).toInt() + 1
            assertEquals(lum(corner), lum(tile[y0 * TILE_W + x0]), 0.08)
            val inside = tile[(y0 + 20) * TILE_W + x0 + 20]
            assertTrue(r(inside) > 200 && g(inside) < 90)
            assertTrue(tile.all { it ushr 24 == 255 }) // opaque, like every tile
        }
    }

    @Test fun aWhiteIconStillShowsItsEdgeOnTheWhitePlate() {
        val white = Pixels(IntArray(96 * 96) { 0xFFFFFFFF.toInt() }, 96, 96)
        val tile = composeIconTile(white, light = true)
        val side = 0.58 * TILE_H
        val left = ((TILE_W - side) / 2).toInt()
        val row = TILE_H / 2
        // Along the middle row, the rim just inside the icon's left edge is darker than the icon and the plate beside it.
        val rim = (left - 1..left + 2).minOf { lum(tile[row * TILE_W + it]) }
        assertTrue("rim $rim", rim < lum(tile[row * TILE_W + left + 10]) - 0.03)
    }

    @Test fun aPictureInsteadOfASymbolFallsBackToThePlainIcon() {
        val side = 108
        val bg = Pixels(IntArray(side * side) { 0xFF203040.toInt() }, side, side)
        // A foreground layer that is a whole picture (every pixel opaque, all different).
        val picture = Pixels(IntArray(324 * 324) { i -> (0xFF shl 24) or ((i * 2654435761L).toInt() and 0xFFFFFF) }, 324, 324)
        assertNull(cutTile("com.example.photoapp", null, picture, bg, null))
        // A real symbol in the same place is used.
        val symbol = Pixels(IntArray(324 * 324) { i -> if (i % 324 in 120 until 204 && i / 324 in 120 until 204) 0xFFFFFFFF.toInt() else 0 }, 324, 324)
        assertNotNull(cutTile("com.example.app", null, symbol, bg, null))
    }

    @Test fun appsAndUtilitiesShowTheirSymbolNotTheirBanner() {
        val w = 160
        val h = 90
        val banner = Pixels(IntArray(w * h) { i -> if (i % w in 50 until 110 && i / w in 40 until 50) 0xFFFFFFFF.toInt() else 0xFF102040.toInt() }, w, h)
        assertNotNull(composeTile("com.example.tool", banner, null, null, null, symbol = false))
        assertNull(composeTile("com.example.tool", banner, null, null, null, symbol = true)) // no icon given: the plain icon takes over
    }

    @Test fun aPhotoBannerIsNotTakenForALogo() {
        val w = 320
        val h = 180
        val rnd = java.util.Random(7)
        // A landscape: smooth sky above, textured ground below (the sky fits the background; the ground reads as "logo").
        val photo = IntArray(w * h) { i ->
            val y = i / w
            if (y < 80) (0xFF shl 24) or (110 shl 16) or ((160 + y / 2) shl 8) or 235
            else rnd.nextInt(130).let { v -> (0xFF shl 24) or ((40 + v / 2) shl 16) or ((70 + v) shl 8) or (30 + v / 3) }
        }
        val pl = extractLogo(photo, w, h, fitSurface(photo, w, h))
        assertTrue(bannerIsPicture(pl, trimLogo(pl)!!))
        assertNull(cutTile("com.example.photo", Pixels(photo, w, h), null, null, null)) // nothing else to go on: plain icon
        // A wordmark on a plain colour is a logo.
        val logo = IntArray(w * h) { i -> if (i % w in 80 until 240 && i / w in 70 until 110) 0xFFFFFFFF.toInt() else 0xFFE50914.toInt() }
        val ll = extractLogo(logo, w, h, fitSurface(logo, w, h))
        assertFalse(bannerIsPicture(ll, trimLogo(ll)!!))
    }
}
