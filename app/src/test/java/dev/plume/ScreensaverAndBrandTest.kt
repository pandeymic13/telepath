package dev.plume

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO

class ScreensaverAndBrandTest {
    private fun asset(path: String): Pixels {
        val img = ImageIO.read(File("src/main/assets/$path"))
        return Pixels(img.getRGB(0, 0, img.width, img.height, null, 0, img.width), img.width, img.height)
    }

    private fun channel(c: Int, shift: Int) = (c shr shift) and 0xFF

    // ---------- screensaver ----------

    @Test fun photosAlwaysCoverTheScreenWhileTheyDrift() {
        for (slide in 0 until 8) for (step in 0..20) {
            val m = panZoom(step / 20f, slide, 1920, 1080, 1920, 1080)
            assertTrue(m[0] >= 1f)
            assertTrue("left edge shows: slide $slide step $step", m[1] <= 0.001f)
            assertTrue("top edge shows: slide $slide step $step", m[2] <= 0.001f)
            assertTrue("right edge shows: slide $slide step $step", m[1] + 1920 * m[0] >= 1919.99f)
            assertTrue("bottom edge shows: slide $slide step $step", m[2] + 1080 * m[0] >= 1079.99f)
        }
        val tall = panZoom(0.5f, 3, 1080, 1920, 1920, 1080) // a picture of another shape is scaled to cover too
        assertTrue(1080 * tall[0] >= 1920f && 1920 * tall[0] >= 1080f)
    }

    @Test fun zoomsAlternateInAndOutAndStopAtTheEnd() {
        assertTrue(panZoom(1f, 0, 1920, 1080, 1920, 1080)[0] > panZoom(0f, 0, 1920, 1080, 1920, 1080)[0])
        assertTrue(panZoom(1f, 1, 1920, 1080, 1920, 1080)[0] < panZoom(0f, 1, 1920, 1080, 1920, 1080)[0])
        assertEquals(1.06f, panZoom(5f, 0, 1920, 1080, 1920, 1080)[0], 0.0001f)
        assertEquals(1f, panZoom(-1f, 0, 1920, 1080, 1920, 1080)[0], 0.0001f)
    }

    @Test fun screensaverSourceKeepsEarlierChoices() {
        assertEquals("slideshow", screensaverSource("slideshow", null))
        assertEquals("folder", screensaverSource(null, "bucket:123:Camera")) // chosen before there was a slideshow
        assertEquals("paintings", screensaverSource(null, null))
        assertTrue(DEFAULT_PHOTO_SECONDS in PHOTO_SECONDS)
    }

    // ---------- Disney+ Hotstar ----------

    @Test fun hotstarIsShownAsDisneyPlusHotstar() {
        val r = REBRANDS.getValue("in.startv.hotstar")
        assertEquals("Disney+ Hotstar", r.label)
        assertTrue(File("src/main/assets/${r.logo}").isFile)
        assertTrue(File("src/main/assets/${r.iconLogo}").isFile)
    }

    @Test fun theLogoBecomesANavyTileWithWhiteLettering() {
        val r = REBRANDS.getValue("in.startv.hotstar")
        val tile = composeLogoTile(asset("logos/disney_hotstar.png"), r.tint, scale = r.scale)!!
        assertEquals(TILE_W * TILE_H, tile.size)
        for (corner in listOf(4 * TILE_W + 4, (TILE_H - 5) * TILE_W + TILE_W - 5)) {
            val c = tile[corner]
            assertTrue("navy, not grey", channel(c, 0) > channel(c, 16) + 8)
        }
        // White lettering across the middle band, larger than the standard size but within 70% of the width.
        var white = 0
        var left = TILE_W
        var right = 0
        for (y in 50 until 130) for (x in 0 until TILE_W) {
            val c = tile[y * TILE_W + x]
            if (channel(c, 16) > 225 && channel(c, 8) > 225 && channel(c, 0) > 225) {
                white++
                left = minOf(left, x)
                right = maxOf(right, x)
            }
        }
        assertTrue("lettering: $white white pixels", white > 600)
        assertTrue("logo width ${right - left}", right - left in (TILE_W * 0.5).toInt()..(TILE_W * 0.70).toInt())
        assertEquals("centred", TILE_W / 2.0, (left + right) / 2.0, 8.0)
    }

    @Test fun scaledLogosStayInsideTheTile() {
        val r = scaledRect(logoRect(346, 100), TILE_W, TILE_H, 1.3)
        assertEquals(logoRect(346, 100)[2] * 1.3, r[2], 0.001)
        assertEquals((TILE_W - r[2]) / 2, r[0], 0.001)
        val capped = scaledRect(logoRect(1000, 100), TILE_W, TILE_H, 1.3) // already 64% wide: stops at 70%
        assertEquals(0.70 * TILE_W, capped[2], 0.001)
    }

    @Test fun theStackedLogoMakesARoundedIcon() {
        val icon = composeLogoTile(asset("logos/disney_hotstar_stacked.png"), REBRANDS.getValue("in.startv.hotstar").tint, 96, 96, icon = true)!!
        assertEquals(0, icon[0] ushr 24)
        assertEquals(0, icon[96 * 96 - 1] ushr 24)
        assertEquals(255, icon[48 * 96 + 2] ushr 24)
        assertTrue(icon.count { channel(it, 16) > 225 && channel(it, 0) > 225 && (it ushr 24) == 255 } > 300)
    }

    @Test fun iconGeometry() {
        val r = fitRect(400, 100, 96, 96, 0.76)
        assertEquals(0.76 * 96, r[2], 0.001)
        assertEquals((96 - r[2]) / 2, r[0], 0.001)
        assertEquals((96 - r[3]) / 2, r[1], 0.001)
        val px = IntArray(64 * 64) { 0xFF102040.toInt() }
        roundCorners(px, 64, 64, 14.0)
        assertEquals(0, px[0] ushr 24)
        assertEquals(0, px[63] ushr 24)
        assertEquals(255, px[32 * 64] ushr 24) // the middle of an edge stays
        assertEquals(255, px[32 * 64 + 32] ushr 24)
    }
}
