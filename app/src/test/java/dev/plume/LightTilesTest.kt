package dev.plume

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO

/** The light theme's tiles: the brand's own colours by day, and a white plate for black or charcoal brands. */
class LightTilesTest {
    private val w = 160
    private val h = 90

    private fun r(c: Int) = (c shr 16) and 0xFF
    private fun g(c: Int) = (c shr 8) and 0xFF
    private fun b(c: Int) = c and 0xFF
    private fun lum(c: Int) = (0.2126 * r(c) + 0.7152 * g(c) + 0.0722 * b(c)) / 255

    /** A [ground] banner with [mark] painted where [inMark] says (a stand-in logo). */
    private fun banner(ground: Int, mark: Int, inMark: (Int, Int) -> Boolean) =
        Pixels(IntArray(w * h) { i -> if (inMark(i % w, i / w)) mark else ground }, w, h)

    private val bar = { x: Int, y: Int -> x in 50 until 110 && y in 40 until 50 }
    private fun centre(tile: IntArray) = tile[(TILE_H / 2) * TILE_W + TILE_W / 2]
    private fun corner(tile: IntArray) = tile[5 * TILE_W + 5]

    private fun asset(path: String): Pixels {
        val img = ImageIO.read(File("src/main/assets/$path"))
        return Pixels(img.getRGB(0, 0, img.width, img.height, null, 0, img.width), img.width, img.height)
    }

    @Test fun byDayATileShowsItsBrandsOwnColour() {
        val cut = cutTile("com.example.blue", banner(0xFF1478FF.toInt(), 0xFFFFFFFF.toInt(), bar), null, null, null)!!
        val day = cut.light()
        val night = cut.dark()
        assertTrue("brand blue: ${Integer.toHexString(corner(day))}", b(corner(day)) > 200 && r(corner(day)) < 80)
        assertTrue("white logo stays white", lum(centre(day)) > 0.9)
        assertTrue("night is the deep tinted tile", lum(corner(night)) < 0.25)
        assertTrue("the two differ", lum(corner(day)) > lum(corner(night)) + 0.25)
    }

    @Test fun blackWordmarksStayBlackOnAWhiteBrand() {
        // Letter-sized strokes (YouTube's wordmark): each well under a seventh of the logo's box.
        val letters = { x: Int, y: Int -> y in 40 until 50 && x in 50 until 104 && (x - 50) % 12 < 6 }
        val cut = cutTile("com.example.white", banner(0xFFFFFFFF.toInt(), 0xFF111111.toInt(), letters), null, null, null)!!
        assertTrue("white plate", lum(corner(cut.light())) > 0.9)
        assertTrue("black wordmark kept by day", lum(centre(cut.light())) < 0.2)
        assertTrue("and made white by night", lum(centre(cut.dark())) > 0.85)
    }

    @Test fun blackBrandsGetAWhitePlateWithTheirWhiteLogoInInk() {
        val cut = cutTile("com.example.black", banner(0xFF000000.toInt(), 0xFFFFFFFF.toInt(), bar), null, null, null)!!
        val day = cut.light()
        assertTrue("white plate", lum(corner(day)) > 0.9)
        assertTrue("ink logo: ${Integer.toHexString(centre(day))}", lum(centre(day)) < 0.2)
    }

    @Test fun colourfulLogosKeepTheirColourOnTheWhitePlate() {
        // CloudStream: a red cloud on black.
        val cut = cutTile("com.example.red", banner(0xFF000000.toInt(), 0xFFE52D2D.toInt(), bar), null, null, null)!!
        val day = cut.light()
        assertTrue("white plate", lum(corner(day)) > 0.9)
        assertTrue("still red: ${Integer.toHexString(centre(day))}", r(centre(day)) > 200 && g(centre(day)) < 80)
    }

    @Test fun aBadgeKeepsItsDarkInsideOnTheWhitePlate() {
        // Sony LIV-like: a framed badge on a near-black ground, its inside as dark as the ground, white letters inside.
        val frame = { x: Int, y: Int -> x in 50..109 && y in 15..74 && (x <= 51 || x >= 108 || y <= 16 || y >= 73) }
        val letters = { x: Int, y: Int -> x in 62 until 98 && y in 30 until 38 }
        val colour = { x: Int, y: Int -> x in 52 until 108 && y in 50 until 73 }
        val px = IntArray(w * h) { i ->
            val x = i % w
            val y = i / w
            when {
                frame(x, y) -> 0xFF8A8A98.toInt()
                letters(x, y) -> 0xFFFFFFFF.toInt()
                colour(x, y) -> 0xFFE040A0.toInt()
                else -> 0xFF1C1321.toInt()
            }
        }
        val day = cutTile("com.example.badge", Pixels(px, w, h), null, null, null)!!.light()
        assertTrue("white plate", lum(corner(day)) > 0.9)
        // Between the frame and the letters: the badge's own dark, not the plate showing through.
        var dark = 0
        var white = 0
        for (y in 0 until TILE_H) for (x in 0 until TILE_W) {
            val c = day[y * TILE_W + x]
            if (lum(c) < 0.2) dark++
            if (r(c) > 235 && g(c) > 235 && b(c) > 235 && y in 40..140 && x in 100..220) white++
        }
        assertTrue("dark inside: $dark px", dark > 1500)
        assertTrue("white letters kept: $white px", white > 150)
    }

    @Test fun aWhiteCutOutIconIsInkedByDay() {
        val side = 96
        val icon = Pixels(IntArray(side * side) { i -> if (i % side in 30 until 66 && i / side in 30 until 66) 0xFFFFFFFF.toInt() else 0 }, side, side)
        val cut = cutTile("com.example.cutout", null, null, null, icon)!!
        assertTrue("white plate", lum(corner(cut.light())) > 0.9)
        assertTrue("ink symbol", lum(centre(cut.light())) < 0.2)
        assertTrue("dark by night", lum(corner(cut.dark())) < 0.25)
        assertTrue("white symbol by night", lum(centre(cut.dark())) > 0.85)
    }

    @Test fun darkGroundsAreBlackCharcoalOrMuted() {
        assertTrue(darkGround(doubleArrayOf(0.0, 0.0, 0.0)))
        assertTrue(darkGround(doubleArrayOf(35.0, 35.0, 35.0))) // Apple TV
        assertTrue(darkGround(doubleArrayOf(28.0, 19.0, 33.0))) // Sony LIV
        assertTrue(darkGround(doubleArrayOf(30.0, 30.0, 42.0))) // Zee5
        assertFalse(darkGround(doubleArrayOf(114.0, 14.0, 174.0))) // Sony Pictures Core's purple stays
        assertFalse(darkGround(doubleArrayOf(5.0, 120.0, 255.0))) // Prime Video blue
        assertFalse(darkGround(doubleArrayOf(255.0, 80.0, 0.0))) // FanCode orange
        assertFalse(darkGround(doubleArrayOf(255.0, 255.0, 255.0)))
    }

    @Test fun disneyPlusHotstarByDayIsNavyLetteringOnWhite() {
        val brand = REBRANDS.getValue("in.startv.hotstar")
        assertTrue(File("src/main/assets/${brand.lightLogo}").isFile)
        assertTrue(File("src/main/assets/${brand.lightIconLogo}").isFile)
        val tile = composeLogoTile(asset(brand.lightLogo), brand.tint, scale = brand.scale, light = Flat(brand.plate))!!
        assertEquals(TILE_W * TILE_H, tile.size)
        assertTrue("white plate", lum(corner(tile)) > 0.95)
        val navy = tile.count { b(it) > r(it) + 60 && b(it) > g(it) + 40 && lum(it) < 0.3 }
        assertTrue("navy lettering: $navy px", navy > 600)
        val icon = composeLogoTile(asset(brand.lightIconLogo), brand.tint, 96, 96, icon = true, light = Flat(brand.plate))!!
        assertEquals(0, icon[0] ushr 24) // rounded
        assertTrue(icon.count { (it ushr 24) == 255 && b(it) > r(it) + 60 && lum(it) < 0.3 } > 150)
    }
}
