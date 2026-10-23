package dev.plume

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The clock and Up next straight on the wallpaper: white, ink, or the glass plate, chosen from the photo behind. */
class HeaderLegibilityTest {
    private fun flat(l: Double, n: Int = 400) = DoubleArray(n) { l }

    /** [n] samples, the first [bright] share of them pure white and the rest a deep shadow. */
    private fun specks(bright: Double, n: Int = 1000) = DoubleArray(n) { if (it < bright * n) 1.0 else 0.02 }

    private fun alpha(c: Int) = (c ushr 24) and 0xFF
    private fun rgb(c: Int) = c and 0xFFFFFF

    @Test fun darkPhotoGetsWhiteText() {
        assertEquals(HeaderInk.LIGHT, headerInk(flat(0.02), flat(0.02)))
    }

    @Test fun brightPhotoGetsInkText() {
        assertEquals(HeaderInk.DARK, headerInk(flat(0.8), flat(0.8)))
    }

    @Test fun halfBrightHalfDarkGetsThePlate() {
        val mixed = DoubleArray(400) { if (it % 2 == 0) 0.0 else 1.0 }
        assertEquals(HeaderInk.PLATE, headerInk(mixed, mixed))
    }

    @Test fun theShadowUnderWhiteTextCarriesMidTones() {
        // Luminance 0.2: ink misses 4.5:1 for the small text, white misses it too without its shadow, and makes it with.
        assertTrue(contrastRatio(0.2, relativeLuminance(INK_TEXT)) < SMALL_TEXT_CONTRAST)
        assertTrue(contrastRatio(0.2, 1.0) < SMALL_TEXT_CONTRAST)
        assertEquals(HeaderInk.LIGHT, headerInk(flat(0.2), flat(0.2)))
        assertEquals(HeaderInk.PLATE, headerInk(flat(0.2), flat(0.2), shade = 0.0))
    }

    @Test fun aFewBrightSpecksDontCallForThePlate() {
        assertEquals(HeaderInk.LIGHT, headerInk(specks(0.05), specks(0.05)))
        // Calibrated on the slideshow: up to 12% falling short still reads; from 15% it needs the plate.
        assertEquals(HeaderInk.LIGHT, headerInk(specks(0.12), specks(0.12)))
        assertEquals(HeaderInk.PLATE, headerInk(specks(0.15), specks(0.15)))
        assertEquals(HeaderInk.PLATE, headerInk(specks(0.2), specks(0.2)))
    }

    @Test fun theSmallTextNeedsMoreContrastThanTheClock() {
        // Luminance 0.4 behind: white with its shadow keeps 3:1 for the clock, but not 4.5:1 for the small lines.
        val shaded = 0.4 * (1 - SHADOW_DARKENING)
        assertTrue(contrastRatio(shaded, 1.0) >= LARGE_TEXT_CONTRAST)
        assertTrue(contrastRatio(shaded, 1.0) < SMALL_TEXT_CONTRAST)
        assertEquals(HeaderInk.LIGHT, headerInk(flat(0.4), flat(0.02)))
        assertEquals(HeaderInk.PLATE, headerInk(flat(0.02), flat(0.4)))
    }

    @Test fun eachSmallLineIsJudgedOnItsOwn() {
        // The date partly over a dark tree (30% of it), Up next over clear bright sky. Pooled, the sky hid the tree.
        val date = DoubleArray(200) { if (it < 60) 0.02 else 0.8 }
        val sky = flat(0.8, 600)
        assertEquals(HeaderInk.DARK, headerInk(flat(0.8), date + sky + sky))
        assertEquals(HeaderInk.PLATE, headerInk(flat(0.8), date, sky, sky))
        assertEquals(HeaderInk.DARK, headerInk(flat(0.8), flat(0.8, 200), sky, sky))
    }

    @Test fun nothingBehindReadsAsWhite() {
        assertEquals(HeaderInk.LIGHT, headerInk(DoubleArray(0), DoubleArray(0)))
        assertEquals(HeaderInk.LIGHT, headerInk(DoubleArray(0)))
    }

    @Test fun networkIconFollowsThePhotoBehindIt() {
        assertTrue(iconIsLight(flat(0.03)))
        assertFalse(iconIsLight(flat(0.7)))
    }

    @Test fun contrastFollowsWcag() {
        assertEquals(21.0, contrastRatio(0.0, 1.0), 1e-9)
        assertEquals(1.0, relativeLuminance(0xFFFFFFFF.toInt()), 1e-9)
        assertEquals(0.0, relativeLuminance(0xFF000000.toInt()), 1e-9)
        assertEquals(0.0084, relativeLuminance(INK_TEXT), 0.0005)
    }

    @Test fun lumaBandReadsStripsAndClipsSamples() {
        // 40 rows read in strips of 32: white on top, black below.
        val band = LumaBand.of(3, 40, 100) { y, rows, into ->
            for (i in 0 until 3 * rows) into[i] = if (y + i / 3 < 20) 0xFFFFFFFF.toInt() else 0xFF000000.toInt()
        }
        assertEquals(100, band.pictureH)
        assertEquals(1.0, band.luminance(0, 0), 0.01)
        assertEquals(1.0, band.luminance(2, 19), 0.01)
        assertEquals(0.0, band.luminance(0, 20), 0.01)
        assertEquals(0.0, band.luminance(2, 39), 0.01)
        assertEquals(4, band.samples(-5, -5, 2, 2).size)
        assertEquals(4, band.samples(1, 38, 10, 50).size)
        assertEquals(0, band.samples(5, 5, 9, 9).size)
        // Mid-grey keeps its value through the square-root code.
        val grey = LumaBand.of(1, 1) { _, _, into -> into[0] = 0xFF777777.toInt() }
        assertEquals(relativeLuminance(0xFF777777.toInt()), grey.luminance(0, 0), 0.005)
    }

    @Test fun headerRectsMapOntoTheCroppedPicture() {
        // 1080p picture on the 1080p screen: the clock's rect as it is.
        assertArrayEquals(intArrayOf(80, 56, 215, 141), centerCropRect(1920, 1080, 1920, 1080, 80, 56, 215, 141))
        // A 4K picture: twice the pixels.
        assertArrayEquals(intArrayOf(160, 112, 430, 282), centerCropRect(1920, 1080, 3840, 2160, 80, 56, 215, 141))
        // A 4:3 picture is cropped top and bottom: the top quarter of the screen starts 135 rows into it.
        val top = centerCropRect(1920, 1080, 1440, 1080, 0, 0, 1920, 270)
        assertEquals(0, top[0])
        assertEquals(135, top[1])
        assertEquals(338, top[3])
    }

    @Test fun eachLookHasItsColours() {
        val themeText = 0xFFF4F6F8.toInt()
        val themeText2 = 0xB3F4F6F8.toInt()
        val light = headerStyle(HeaderInk.LIGHT, true, themeText, themeText2)
        assertEquals(0xFFFFFFFF.toInt(), light.text)
        assertEquals(HEADER_SHADOW, light.shadow)
        assertEquals(0, light.plate)
        assertEquals(0xFFFFFF, rgb(light.icon))

        val dark = headerStyle(HeaderInk.DARK, false, themeText, themeText2)
        assertEquals(INK_TEXT, dark.text)
        assertEquals(rgb(INK_TEXT), rgb(dark.text2))
        assertEquals(0, alpha(dark.shadow))
        assertEquals(0, dark.plate)
        assertEquals(rgb(INK_TEXT), rgb(dark.icon))

        val plate = headerStyle(HeaderInk.PLATE, true, themeText, themeText2)
        assertEquals(themeText, plate.text)
        assertEquals(themeText2, plate.text2)
        assertEquals(0, alpha(plate.shadow))
        assertEquals(255, plate.plate)

        // Plain colour or no picture yet: the theme's own text, no plate, the icon as before.
        val plain = headerStyle(null, true, themeText, themeText2)
        assertEquals(themeText, plain.text)
        assertEquals(0, plain.plate)
        assertEquals(0, alpha(plain.shadow))
        assertEquals(themeText2, plain.icon)
    }

    @Test fun looksFadeIntoEachOther() {
        val from = headerStyle(HeaderInk.LIGHT, true, INK_TEXT, 0xA314171B.toInt())
        val to = headerStyle(HeaderInk.PLATE, false, INK_TEXT, 0xA314171B.toInt())
        assertEquals(from, from.mix(to, 0f))
        assertEquals(to, from.mix(to, 1f))
        val half = from.mix(to, 0.5f)
        assertEquals(128, half.plate)
        assertTrue(alpha(half.shadow) in 1 until alpha(HEADER_SHADOW))
    }
}
