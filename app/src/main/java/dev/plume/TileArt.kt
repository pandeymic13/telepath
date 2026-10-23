package dev.plume

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Apple TV+-style app tiles: each app's logo is cut out of its banner (or icon), then re-centred at one consistent
 * optical size, in two finishes: for the dark theme on a deep, brand-tinted dark gradient, for the light theme on the
 * brand's own background colours; both lit softly from the top. When no logo can be cut out, the app's own icon sits
 * on the theme's plate instead ([composeIconTile]). Pure pixel maths on ARGB int arrays (straight alpha), so it runs
 * on the TV's io thread and in JVM tests alike.
 */
internal const val TILE_W = 320
internal const val TILE_H = 180

/** A streaming app shown by its symbol alone, like the apps & utilities: CloudStream by just its cloud. */
internal fun prefersSymbol(pkg: String) = pkg.startsWith("com.lagradost.cloudstream")

/**
 * How an app's tiles are made, and the end of their file names: its logo cut from its banner (streaming apps), its
 * symbol cut from its icon (apps & utilities, the tvOS way), or its whole icon on the theme's plate ([ICON]: chosen with
 * "Use app icon", and the fallback for the other two).
 */
internal enum class TileStyle(val suffix: String) { LOGO(""), SYMBOL("-sym"), ICON("-icon") }

internal fun tileStyle(pkg: String, streaming: Boolean, plainIcon: Boolean) = when {
    plainIcon -> TileStyle.ICON
    !streaming || prefersSymbol(pkg) -> TileStyle.SYMBOL
    else -> TileStyle.LOGO
}

/** An adaptive icon's foreground covering more than this is a picture, not a symbol (real ones here: at most 0.44). */
private const val MAX_SYMBOL_COVER = 0.6

/** ARGB pixels (straight alpha), row by row. */
internal class Pixels(val px: IntArray, val w: Int, val h: Int)

/** A tile's background: its colour (r, g, b, 0..255) at (u, v) in 0..1, into [out]. Not thread-safe. */
internal fun interface Ground {
    fun at(u: Double, v: Double, out: DoubleArray)
}

/** One flat colour. */
internal class Flat(private val c: DoubleArray) : Ground {
    override fun at(u: Double, v: Double, out: DoubleArray) {
        out[0] = c[0]; out[1] = c[1]; out[2] = c[2]
    }
}

/** The part u0..u1 × v0..v1 of [g]: a 16:9 tile's share of a square icon's backplate, as the icon shows it. */
internal class Window(private val g: Ground, private val u0: Double, private val u1: Double, private val v0: Double,
                      private val v1: Double) : Ground {
    override fun at(u: Double, v: Double, out: DoubleArray) = g.at(u0 + (u1 - u0) * u, v0 + (v1 - v0) * v, out)
}

/**
 * What an app's tiles are made from: its logo cut out, the logo's bounds, the tint of its dark tile and the brand's own
 * background for its light tile.
 */
internal class TileCut(val logo: Logo, val box: IntArray, val tint: DoubleArray, val ground: Ground) {
    fun dark() = composeDarkTile(tint, logo, box)

    /** On the brand's own colours; a black or charcoal brand turns to a white plate with its pale strokes in ink. */
    fun light(): IntArray {
        val mean = groundMean(ground)
        return if (darkGround(mean)) composeLightTile(Flat(LIGHT_PLATE), logo, box, Strokes.INK, backdrop = mean)
        else composeLightTile(ground, logo, box)
    }
}

/**
 * A cut-out logo with no background of its own sits on [LIGHT_PLATE], or on [DARK_PLATE] when it's white (by day it
 * then gets the light plate with its strokes in ink, see [TileCut.light]).
 */
internal val LIGHT_PLATE = doubleArrayOf(248.0, 248.0, 250.0)
internal val DARK_PLATE = doubleArrayOf(30.0, 30.0, 32.0)

/** What happens to a logo's plain strokes on its tile: kept, dark ones made white (dark tile), pale ones made ink (light). */
internal enum class Strokes { KEEP, WHITE, INK }

/** The average colour of [g] over the tile. */
internal fun groundMean(g: Ground): DoubleArray {
    val m = DoubleArray(3)
    val c = DoubleArray(3)
    for (y in 0..8) for (x in 0..8) {
        g.at(x / 8.0, y / 8.0, c)
        for (ch in 0..2) m[ch] += c[ch] / 81
    }
    return m
}

/**
 * True for black, charcoal or another dark, muted colour: what the dark tiles already look like, so the light theme
 * shows a white plate instead. Deep but vivid brand colours (Sony Pictures Core's purple) stay.
 */
internal fun darkGround(mean: DoubleArray): Boolean {
    val mx = max(mean[0], max(mean[1], mean[2]))
    val mn = min(mean[0], min(mean[1], mean[2]))
    val luma = (0.2126 * mean[0] + 0.7152 * mean[1] + 0.0722 * mean[2]) / 255
    return luma < 0.22 && (mx - mn) / max(mx, 1.0) < 0.6
}

/**
 * An app's logo from what it ships, best first: cut out of its TV banner (unless the banner is really a picture, or
 * [symbol] asks for the app's symbol), else its adaptive icon's symbol layer over its backplate (unless that layer is a
 * whole picture), else its old-style icon (a cut-out one as is, an opaque one with its background taken off). Null if
 * nothing works: the app then gets its plain icon ([composeIconTile]).
 */
internal fun cutTile(pkg: String, banner: Pixels?, fg: Pixels?, bgLayer: Pixels?, icon: Pixels?,
                     symbol: Boolean = prefersSymbol(pkg)): TileCut? {
    var bannerSurface: Surface? = null
    if (banner != null && !symbol) {
        val surface = fitSurface(banner.px, banner.w, banner.h)
        bannerSurface = surface
        val logo = extractLogo(banner.px, banner.w, banner.h, surface)
        val box = trimLogo(logo)
        if (box != null && !bannerIsPicture(logo, box)) return TileCut(logo, box, brandTint(surface.mean()), surface)
    }
    if (fg != null && bgLayer != null) {
        val logo = layerLogo(fg.px, fg.w, fg.h)
        // An adaptive icon shows the middle two thirds of its layers; the tile takes a 16:9 band of that.
        val plate = Window(fitSurface(bgLayer.px, bgLayer.w, bgLayer.h), 1 / 6.0, 5 / 6.0, 0.3125, 0.6875)
        if (logo.coverage <= MAX_SYMBOL_COVER) trimLogo(logo)?.let { return TileCut(logo, it, brandTint(meanColour(bgLayer.px)), plate) }
    }
    if (icon == null) return null
    if (icon.px.count { it ushr 24 < 128 } > icon.px.size * 0.3) {
        val logo = layerLogo(icon.px, icon.w, icon.h)
        val box = trimLogo(logo) ?: return null
        val plate = Flat(if (whiteLogo(logo, box)) DARK_PLATE else LIGHT_PLATE)
        return TileCut(logo, box, brandTint(bannerSurface?.mean() ?: meanColour(icon.px)), plate)
    }
    val surface = fitSurface(icon.px, icon.w, icon.h)
    val logo = extractLogo(icon.px, icon.w, icon.h, surface)
    val box = trimLogo(logo) ?: return null
    return TileCut(logo, box, brandTint(surface.mean()), Window(surface, 0.0, 1.0, 0.21875, 0.78125))
}

/** An app's tile (see [cutTile]): the dark one, or with [light] the one in the brand's own colours. */
internal fun composeTile(pkg: String, banner: Pixels?, fg: Pixels?, bgLayer: Pixels?, icon: Pixels?, light: Boolean = false,
                         symbol: Boolean = prefersSymbol(pkg)): IntArray? =
    cutTile(pkg, banner, fg, bgLayer, icon, symbol)?.let { if (light) it.light() else it.dark() }

/**
 * True when what was cut from a banner ([l], visible within [box]) is a picture, not a logo: it covers 30% or more of
 * the banner, its bounds span more than 40% of it, or the clean-up threw more than a fifth of it away as scattered
 * specks. Measured on this TV: its 13 streaming banners at most 13% coverage, 37% bounds, 12% thrown away; 55 photos
 * each caught by at least one of the three.
 */
internal fun bannerIsPicture(l: Logo, box: IntArray): Boolean {
    if (l.coverage >= 0.30) return true
    if (box[2].toLong() * box[3] > 0.40 * l.w * l.h) return true
    var kept = 0.0
    for (y in box[1] until box[1] + box[3]) for (x in box[0] until box[0] + box[2]) kept += l.a[y * l.w + x]
    return kept < 0.8 * l.coverage * l.w * l.h
}

/** True when what shows of the logo in [box] is mostly white or pale grey, so it needs a dark plate to be seen. */
internal fun whiteLogo(l: Logo, box: IntArray): Boolean {
    var wsum = 0.0
    var pale = 0.0
    for (y in box[1] until box[1] + box[3]) for (x in box[0] until box[0] + box[2]) {
        val i = y * l.w + x
        val a = l.a[i].toDouble()
        if (a < 0.3) continue
        wsum += a
        val lum = (0.2126 * l.r[i] + 0.7152 * l.g[i] + 0.0722 * l.b[i]) / 255
        if (lum > 0.75 && chroma(l.r[i], l.g[i], l.b[i]) < 60) pale += a
    }
    return wsum > 0 && pale / wsum > 0.6
}

internal fun channel(c: Int, ch: Int) = when (ch) {
    0 -> (c shr 16) and 0xFF
    1 -> (c shr 8) and 0xFF
    else -> c and 0xFF
}

private fun terms(u: Double, v: Double, t: DoubleArray) {
    t[0] = 1.0; t[1] = u; t[2] = v; t[3] = u * u; t[4] = u * v; t[5] = v * v
    t[6] = u * u * u; t[7] = u * u * v; t[8] = u * v * v; t[9] = v * v * v
}

/** A smooth background: a cubic surface per colour channel over (u, v) in 0..1. */
internal class Surface(private val coef: Array<DoubleArray>) : Ground {
    private val t = DoubleArray(10)

    /** Colour at (u, v) into [out] (r, g, b, 0..255). Not thread-safe; one surface per thread. */
    override fun at(u: Double, v: Double, out: DoubleArray) {
        terms(u, v, t)
        for (ch in 0..2) {
            var s = 0.0
            for (i in 0..9) s += coef[ch][i] * t[i]
            out[ch] = s.coerceIn(0.0, 255.0)
        }
    }

    fun mean(): DoubleArray {
        val m = DoubleArray(3)
        val c = DoubleArray(3)
        for (y in 0..8) for (x in 0..8) {
            at(x / 8.0, y / 8.0, c)
            for (ch in 0..2) m[ch] += c[ch] / 81
        }
        return m
    }
}

/**
 * The background of a banner or icon without its logo: a least-squares cubic surface, refitted three times without
 * the samples that don't fit (the logo). Edge samples count double; see-through pixels are skipped.
 */
internal fun fitSurface(px: IntArray, w: Int, h: Int): Surface {
    val step = max(2, w / 80)
    val cap = ((w + step - 1) / step) * ((h + step - 1) / step)
    val tt = Array(cap) { DoubleArray(10) }
    val cols = IntArray(cap)
    val wt = DoubleArray(cap)
    val du = max(1, w - 1).toDouble()
    val dv = max(1, h - 1).toDouble()
    var n = 0
    var y = 0
    while (y < h) {
        var x = 0
        while (x < w) {
            val c = px[y * w + x]
            if (c ushr 24 >= 200) {
                terms(x / du, y / dv, tt[n])
                cols[n] = c
                wt[n] = if (x < 3 * step || y < 3 * step || x >= w - 3 * step || y >= h - 3 * step) 2.0 else 1.0
                n++
            }
            x += step
        }
        y += step
    }
    val coef = Array(3) { DoubleArray(10) }
    if (n == 0) return Surface(coef)
    val res = DoubleArray(n)
    for (iter in 0..3) {
        for (ch in 0..2) coef[ch] = solve(tt, cols, wt, n, ch)
        if (iter == 3) break
        for (i in 0 until n) {
            var r = 0.0
            for (ch in 0..2) {
                var s = 0.0
                for (k in 0..9) s += coef[ch][k] * tt[i][k]
                r = max(r, abs(channel(cols[i], ch) - s.coerceIn(0.0, 255.0)))
            }
            res[i] = r
        }
        val sorted = res.copyOf(n).also { it.sort() }
        val limit = max(10.0, 2.5 * sorted[n / 2])
        for (i in 0 until n) if (res[i] > limit) wt[i] = 0.0
    }
    return Surface(coef)
}

private fun solve(tt: Array<DoubleArray>, cols: IntArray, wt: DoubleArray, n: Int, ch: Int): DoubleArray {
    val k = 10
    val a = Array(k) { DoubleArray(k + 1) }
    for (i in 0 until n) {
        val w = wt[i]
        if (w <= 0) continue
        val t = tt[i]
        val y = channel(cols[i], ch).toDouble()
        for (r in 0 until k) {
            val tr = w * t[r]
            for (c in 0 until k) a[r][c] += tr * t[c]
            a[r][k] += tr * y
        }
    }
    for (r in 0 until k) a[r][r] += 1e-6
    for (col in 0 until k) {
        var piv = col
        for (r in col + 1 until k) if (abs(a[r][col]) > abs(a[piv][col])) piv = r
        val tmp = a[col]; a[col] = a[piv]; a[piv] = tmp
        val d = a[col][col]
        if (abs(d) < 1e-12) continue
        for (c in col..k) a[col][c] /= d
        for (r in 0 until k) {
            if (r == col) continue
            val f = a[r][col]
            if (f == 0.0) continue
            for (c in col..k) a[r][c] -= f * a[col][c]
        }
    }
    return DoubleArray(k) { a[it][k] }
}

/** A logo cut out of its background: straight colour (0..255) and alpha (0..1) per pixel. */
internal class Logo(val w: Int, val h: Int) {
    val a = FloatArray(w * h)
    val r = FloatArray(w * h)
    val g = FloatArray(w * h)
    val b = FloatArray(w * h)
    var coverage = 0.0
}

private fun away(p: Double, b: Double) = when {
    p > b -> (p - b) / max(1.0, 255 - b)
    p < b -> (b - p) / max(1.0, b)
    else -> 0.0
}

/**
 * Separates the logo from [bg] by "colour to alpha": each pixel becomes the least opaque logo colour that, over the
 * background, gives the pixel. Pixels within ~12 levels of the background count as background (a logo's own
 * off-white box, JPEG noise), and the outermost 2.5% is ignored (banner edges often carry a rim or a corner).
 */
internal fun extractLogo(px: IntArray, w: Int, h: Int, bg: Surface): Logo {
    val l = Logo(w, h)
    val mx = max(2, (w * 0.025).toInt())
    val my = max(2, (h * 0.025).toInt())
    val du = max(1, w - 1).toDouble()
    val dv = max(1, h - 1).toDouble()
    val c3 = DoubleArray(3)
    var sum = 0.0
    for (y in my until h - my) for (x in mx until w - mx) {
        val i = y * w + x
        val c = px[i]
        if (c ushr 24 < 128) continue
        val pr = channel(c, 0).toDouble()
        val pg = channel(c, 1).toDouble()
        val pb = channel(c, 2).toDouble()
        bg.at(x / du, y / dv, c3)
        val dist = max(abs(pr - c3[0]), max(abs(pg - c3[1]), abs(pb - c3[2])))
        val ramp = ((dist - 12) / 16).coerceIn(0.0, 1.0)
        val a = max(away(pr, c3[0]), max(away(pg, c3[1]), away(pb, c3[2])))
        if (a <= 0 || ramp <= 0) continue
        l.r[i] = ((pr - c3[0]) / a + c3[0]).coerceIn(0.0, 255.0).toFloat()
        l.g[i] = ((pg - c3[1]) / a + c3[1]).coerceIn(0.0, 255.0).toFloat()
        l.b[i] = ((pb - c3[2]) / a + c3[2]).coerceIn(0.0, 255.0).toFloat()
        l.a[i] = (a * ramp).toFloat()
        sum += a * ramp
    }
    // Measured before the clean-up, as tuned: a third or more of the banner "logo" means it's really a picture.
    l.coverage = sum / (w * h)
    fillHoles(l, px)
    dropFaint(l)
    dropStrays(l, mx, my)
    return l
}

/** A layer that already has transparency (an adaptive icon's foreground, a cut-out legacy icon). */
internal fun layerLogo(px: IntArray, w: Int, h: Int): Logo {
    val l = Logo(w, h)
    for (i in px.indices) {
        val c = px[i]
        l.a[i] = (c ushr 24) / 255f
        l.r[i] = channel(c, 0).toFloat(); l.g[i] = channel(c, 1).toFloat(); l.b[i] = channel(c, 2).toFloat()
    }
    l.coverage = l.a.sum().toDouble() / max(1, w * h)
    return l
}

private fun chroma(r: Float, g: Float, b: Float) = max(r, max(g, b)) - min(r, min(g, b))

private fun neighbours(i: Int, w: Int, n: Int, out: IntArray): Int {
    var k = 0
    val x = i % w
    if (x > 0) out[k++] = i - 1
    if (x < w - 1) out[k++] = i + 1
    if (i - w >= 0) out[k++] = i - w
    if (i + w < n) out[k++] = i + w
    return k
}

/**
 * Light see-through areas fully enclosed by strongly coloured logo (YouTube's white triangle inside the red button)
 * are part of the logo: they keep their original colour. Counters inside letters (the hole of an "o"), and dark
 * knock-outs like Spotify's lines, stay clear and show the tile.
 */
private fun fillHoles(l: Logo, px: IntArray) {
    val w = l.w
    val n = w * l.h
    val label = IntArray(n)
    val stack = IntArray(n)
    val nb = IntArray(4)
    var top = 0
    for (i in 0 until n) {
        val x = i % w
        val y = i / w
        if ((x == 0 || y == 0 || x == w - 1 || y == l.h - 1) && l.a[i] < 0.3f) { label[i] = -1; stack[top++] = i }
    }
    while (top > 0) {
        val i = stack[--top]
        for (k in 0 until neighbours(i, w, n, nb)) {
            val j = nb[k]
            if (label[j] == 0 && l.a[j] < 0.3f) { label[j] = -1; stack[top++] = j }
        }
    }
    var id = 0
    val members = IntArray(n)
    for (start in 0 until n) {
        if (label[start] != 0 || l.a[start] >= 0.3f) continue
        id++
        var count = 0
        label[start] = id
        stack[top++] = start
        var edge = 0
        var coloured = 0
        var light = 0.0
        while (top > 0) {
            val i = stack[--top]
            members[count++] = i
            val c = px[i]
            light += (0.2126 * channel(c, 0) + 0.7152 * channel(c, 1) + 0.0722 * channel(c, 2)) / 255
            for (k in 0 until neighbours(i, w, n, nb)) {
                val j = nb[k]
                if (l.a[j] >= 0.5f) {
                    edge++
                    if (chroma(l.r[j], l.g[j], l.b[j]) > 80) coloured++
                } else if (label[j] == 0 && l.a[j] < 0.3f) {
                    label[j] = id
                    stack[top++] = j
                }
            }
        }
        if (edge > 0 && coloured > edge * 0.6 && count < n / 20 && light / count > 0.6) {
            for (m in 0 until count) {
                val i = members[m]
                val c = px[i]
                l.a[i] = 1f
                l.r[i] = channel(c, 0).toFloat(); l.g[i] = channel(c, 1).toFloat(); l.b[i] = channel(c, 2).toFloat()
            }
        }
    }
}

/**
 * Blobs that aren't logo are dropped: faint ones (real strokes are mostly opaque) and soft ones. A gradient's glow
 * mistaken for logo fades in over many pixels, while a logo's edges are crisp even at banner size.
 */
private fun dropFaint(l: Logo) {
    val w = l.w
    val n = w * l.h
    val seen = BooleanArray(n)
    val stack = IntArray(n)
    val members = IntArray(n)
    val nb = IntArray(4)
    for (start in 0 until n) {
        if (seen[start] || l.a[start] <= 0.3f) continue
        var top = 0
        var count = 0
        var sum = 0.0
        var rim = 0.0
        var rimCount = 0
        seen[start] = true
        stack[top++] = start
        while (top > 0) {
            val i = stack[--top]
            members[count++] = i
            sum += l.a[i]
            for (k in 0 until neighbours(i, w, n, nb)) {
                val j = nb[k]
                if (l.a[j] <= 0.3f) {
                    rim += l.a[i] - l.a[j]
                    rimCount++
                } else if (!seen[j]) {
                    seen[j] = true
                    stack[top++] = j
                }
            }
        }
        // Average step across the outline: a crisp stroke drops to clear at once, a glow (cut off at most by the
        // ignored margin along one side) only fades.
        val soft = rimCount > 0 && rim / rimCount < 0.22
        if (sum / count < 0.5 || soft) for (m in 0 until count) l.a[members[m]] = 0f
    }
}

/**
 * Keeps the logo itself: its biggest part and every part close to it (letters, a symbol beside its name), dropping
 * small leftovers far away or cut off by the ignored margin [mx], [my] (a gradient's bright corner: logos sit clear
 * of the banner's edges). Faint pixels away from what's kept go too, so stray haze can't stretch the logo's bounds;
 * the anti-aliasing along the kept strokes stays.
 */
private fun dropStrays(l: Logo, mx: Int, my: Int) {
    val w = l.w
    val h = l.h
    val n = w * h
    val label = IntArray(n) { -1 }
    val stack = IntArray(n)
    val nb = IntArray(4)
    val boxes = ArrayList<IntArray>() // x0, y0, x1, y1 per part
    val mass = ArrayList<Double>()
    for (start in 0 until n) {
        if (label[start] >= 0 || l.a[start] <= 0.3f) continue
        val id = boxes.size
        val box = intArrayOf(w, h, -1, -1)
        var sum = 0.0
        var top = 0
        label[start] = id
        stack[top++] = start
        while (top > 0) {
            val i = stack[--top]
            sum += l.a[i]
            box[0] = min(box[0], i % w); box[1] = min(box[1], i / w); box[2] = max(box[2], i % w); box[3] = max(box[3], i / w)
            for (k in 0 until neighbours(i, w, n, nb)) {
                val j = nb[k]
                if (label[j] < 0 && l.a[j] > 0.3f) { label[j] = id; stack[top++] = j }
            }
        }
        boxes += box
        mass += sum
    }
    if (boxes.isEmpty()) return
    val total = mass.sum()
    val edgeCut = BooleanArray(boxes.size) { c ->
        val b = boxes[c]
        mass[c] < 0.2 * total && (b[0] <= mx + 1 || b[1] <= my + 1 || b[2] >= w - 2 - mx || b[3] >= h - 2 - my)
    }
    val gap = max(3, (0.06 * max(w, h)).toInt())
    val keep = BooleanArray(boxes.size)
    val main = mass.indices.maxByOrNull { mass[it] }!!
    keep[main] = true
    val cluster = boxes[main].copyOf()
    var grew = true
    while (grew) {
        grew = false
        for (c in boxes.indices) {
            if (keep[c] || edgeCut[c]) continue
            val b = boxes[c]
            val dx = max(0, max(b[0] - cluster[2], cluster[0] - b[2]))
            val dy = max(0, max(b[1] - cluster[3], cluster[1] - b[3]))
            if (dx <= gap && dy <= gap) {
                keep[c] = true
                cluster[0] = min(cluster[0], b[0]); cluster[1] = min(cluster[1], b[1])
                cluster[2] = max(cluster[2], b[2]); cluster[3] = max(cluster[3], b[3])
                grew = true
            }
        }
    }
    for (c in boxes.indices) if (!keep[c] && mass[c] >= 0.2 * total) keep[c] = true // a real second part, just far
    // Kept pixels, grown by 2 px: the reach of a stroke's own anti-aliasing.
    val near = BooleanArray(n)
    for (i in 0 until n) {
        val id = label[i]
        if (id < 0 || !keep[id]) continue
        val x = i % w
        val y = i / w
        for (yy in max(0, y - 2)..min(h - 1, y + 2)) for (xx in max(0, x - 2)..min(w - 1, x + 2)) near[yy * w + xx] = true
    }
    for (i in 0 until n) {
        val id = label[i]
        if (if (id >= 0) !keep[id] else !near[i]) l.a[i] = 0f
    }
}

/** The logo's bounds (x, y, width, height) over clearly visible pixels, or null if nothing is visible. */
internal fun trimLogo(l: Logo, visible: Float = 0.3f): IntArray? {
    val rows = IntArray(l.h)
    val cols = IntArray(l.w)
    for (y in 0 until l.h) for (x in 0 until l.w) if (l.a[y * l.w + x] > visible) { rows[y]++; cols[x]++ }
    val minRow = max(2, l.w / 160)
    val minCol = max(2, l.h / 160)
    val y0 = rows.indexOfFirst { it >= minRow }
    val y1 = rows.indexOfLast { it >= minRow }
    val x0 = cols.indexOfFirst { it >= minCol }
    val x1 = cols.indexOfLast { it >= minCol }
    if (y0 < 0 || x0 < 0) return null
    return intArrayOf(x0, y0, x1 - x0 + 1, y1 - y0 + 1)
}

internal val NEUTRAL_TINT = doubleArrayOf(128.0, 128.0, 132.0)

/** The tile's tint: the brand's background colour when it has one, else Apple TV's neutral charcoal. */
internal fun brandTint(mean: DoubleArray): DoubleArray {
    val mx = max(mean[0], max(mean[1], mean[2]))
    val mn = min(mean[0], min(mean[1], mean[2]))
    return if ((mx - mn) / max(mx, 96.0) > 0.28) mean else NEUTRAL_TINT
}

/** Mean colour of the opaque pixels of an image. */
internal fun meanColour(px: IntArray): DoubleArray {
    val m = DoubleArray(3)
    var n = 0
    for (c in px) {
        if (c ushr 24 < 128) continue
        for (ch in 0..2) m[ch] += channel(c, ch).toDouble()
        n++
    }
    if (n > 0) for (ch in 0..2) m[ch] /= n
    return m
}

/** Where the logo goes: about 11% of the tile's area, never wider than 64% or taller than 46% of it. */
internal fun logoRect(boxW: Int, boxH: Int, w: Int = TILE_W, h: Int = TILE_H): DoubleArray {
    val ratio = boxW.toDouble() / max(1, boxH)
    var th = sqrt(0.11 * w * h / ratio)
    var tw = ratio * th
    val s = min(1.0, min(0.64 * w / tw, 0.46 * h / th))
    tw *= s; th *= s
    return doubleArrayOf((w - tw) / 2, (h - th) / 2, tw, th)
}

/** A [boxW]×[boxH] logo as large as fits in [fraction] of a [w]×[h] tile, centred: for small icons. */
internal fun fitRect(boxW: Int, boxH: Int, w: Int, h: Int, fraction: Double): DoubleArray {
    val s = min(fraction * w / max(1, boxW), fraction * h / max(1, boxH))
    val tw = boxW * s
    val th = boxH * s
    return doubleArrayOf((w - tw) / 2, (h - th) / 2, tw, th)
}

/**
 * A tile from a bundled replacement logo (transparent [logo] pixels, already in the colours it should show): on a dark
 * [tint], or with [light] on that background instead; [scale] times the standard size (for a logo whose thin lettering
 * reads small). [icon]: a small square app icon instead, the logo filling it, with rounded corners.
 */
internal fun composeLogoTile(logo: Pixels, tint: DoubleArray, w: Int = TILE_W, h: Int = TILE_H, icon: Boolean = false,
                             scale: Double = 1.0, light: Ground? = null): IntArray? {
    val l = layerLogo(logo.px, logo.w, logo.h)
    val box = trimLogo(l, visible = 0.02f) ?: return null // a drawn logo has no noise: keep its faint parts too
    val rect = if (icon) fitRect(box[2], box[3], w, h, 0.76) else scaledRect(logoRect(box[2], box[3], w, h), w, h, scale)
    val px = if (light != null) composeLightTile(light, l, box, w = w, h = h, rect = rect) else composeDarkTile(tint, l, box, w, h, rect)
    if (icon) roundCorners(px, w, h, min(w, h) * 0.22)
    return px
}

/** [r] made [scale] times bigger about the tile's centre, but never wider than 70% of the tile or taller than half. */
internal fun scaledRect(r: DoubleArray, w: Int, h: Int, scale: Double): DoubleArray {
    val s = min(scale, min(0.70 * w / r[2], 0.5 * h / r[3]))
    val tw = r[2] * s
    val th = r[3] * s
    return doubleArrayOf((w - tw) / 2, (h - th) / 2, tw, th)
}

/** Clears what lies outside a rounded rectangle of corner radius [r], with smooth (anti-aliased) corners. */
internal fun roundCorners(px: IntArray, w: Int, h: Int, r: Double) {
    for (y in 0 until h) for (x in 0 until w) {
        val fx = x + 0.5
        val fy = y + 0.5
        val cx = if (fx < r) r else if (fx > w - r) w - r else continue
        val cy = if (fy < r) r else if (fy > h - r) h - r else continue
        val cover = (r - sqrt((fx - cx) * (fx - cx) + (fy - cy) * (fy - cy)) + 0.5).coerceIn(0.0, 1.0)
        if (cover >= 1.0) continue
        val c = px[y * w + x]
        px[y * w + x] = (((c ushr 24) * cover).toInt() shl 24) or (c and 0xFFFFFF)
    }
}

/**
 * The dark theme's tile: a dark gradient tinted with [tint] (lit softly from the top, shaded at the bottom), and the
 * logo from [box] of [logo] in [rect] (centred at the standard size unless given). Dark neutral strokes (YouTube's black
 * wordmark) turn white so they read on dark; large dark plates (Sony LIV's badge) keep their colour.
 */
internal fun composeDarkTile(tint: DoubleArray, logo: Logo, box: IntArray, w: Int = TILE_W, h: Int = TILE_H,
                             rect: DoubleArray = logoRect(box[2], box[3], w, h)): IntArray {
    val out = IntArray(w * h)
    paintDark(out, tint, w, h)
    placeLogo(out, w, h, logo, box, rect, Strokes.WHITE)
    return out
}

/** The dark tiles' background: deep [tint], lit softly from the top and shaded at the bottom. */
private fun paintDark(out: IntArray, tint: DoubleArray, w: Int, h: Int) {
    for (y in 0 until h) {
        val v = y / max(1, h - 1).toDouble()
        val k = 0.80 + 0.13 * v
        val hi = 0.10 * max(0.0, 1 - v / 0.55)
        val sh = 0.16 * max(0.0, (v - 0.45) / 0.55)
        var row = 0xFF shl 24
        for (ch in 0..2) {
            var c = tint[ch] * (1 - k)
            c += (255 - c) * hi
            c *= 1 - sh
            row = row or (c.toInt().coerceIn(0, 255) shl (16 - 8 * ch))
        }
        for (x in 0 until w) out[y * w + x] = row
    }
}

/**
 * The light theme's tile: the brand's own background from [ground] (as its banner or icon has it), lit softly from
 * the top and shaded at the bottom like the dark tile, with the logo from [box] of [logo] in its own colours in [rect]
 * (or, with [Strokes.INK], its pale strokes in ink, for a white plate; a badge then keeps its inside on [backdrop]).
 */
internal fun composeLightTile(ground: Ground, logo: Logo, box: IntArray, strokes: Strokes = Strokes.KEEP,
                              backdrop: DoubleArray? = null, w: Int = TILE_W, h: Int = TILE_H,
                              rect: DoubleArray = logoRect(box[2], box[3], w, h)): IntArray {
    val out = IntArray(w * h)
    paintLight(out, ground, w, h)
    placeLogo(out, w, h, logo, box, rect, strokes, backdrop)
    return out
}

/** The light tiles' background: [ground], lit softly from the top and shaded at the bottom. */
private fun paintLight(out: IntArray, ground: Ground, w: Int, h: Int) {
    val c = DoubleArray(3)
    val du = max(1, w - 1).toDouble()
    for (y in 0 until h) {
        val v = y / max(1, h - 1).toDouble()
        val hi = 0.10 * max(0.0, 1 - v / 0.55)
        val sh = 0.16 * max(0.0, (v - 0.45) / 0.55)
        for (x in 0 until w) {
            ground.at(x / du, v, c)
            var p = 0xFF shl 24
            for (ch in 0..2) {
                var k = c[ch] + (255 - c[ch]) * hi
                k *= 1 - sh
                p = p or (k.toInt().coerceIn(0, 255) shl (16 - 8 * ch))
            }
            out[y * w + x] = p
        }
    }
}

/**
 * The plain tile: the app's own [icon], whole, as a rounded square in the middle of the theme's plate (white by day,
 * charcoal by night), with a hairline rim so a white or black icon still shows its edge, like an app without artwork
 * on tvOS. Always possible, so it's the fallback when no logo can be cut out, what shows while a new app's tiles are
 * made, and what "Use app icon" picks. An icon that is already round or shaped keeps its shape inside the square.
 */
internal fun composeIconTile(icon: Pixels, light: Boolean, w: Int = TILE_W, h: Int = TILE_H): IntArray {
    val out = IntArray(w * h)
    if (light) paintLight(out, Flat(LIGHT_PLATE), w, h) else paintDark(out, NEUTRAL_TINT, w, h)
    val l = layerLogo(icon.px, icon.w, icon.h)
    val side = 0.58 * h
    val half = side / 2
    val r = 0.225 * side
    val cx = w / 2.0
    val cy = h / 2.0
    val fx = icon.w / side
    val fy = icon.h / side
    // Signed distance from the rounded square's edge (negative inside).
    fun edge(x: Double, y: Double): Double {
        val qx = abs(x - cx) - (half - r)
        val qy = abs(y - cy) - (half - r)
        return sqrt(max(qx, 0.0) * max(qx, 0.0) + max(qy, 0.0) * max(qy, 0.0)) + min(max(qx, qy), 0.0) - r
    }
    val rim = if (light) intArrayOf(20, 23, 27) else intArrayOf(255, 255, 255)
    val rimAlpha = if (light) 0.12 else 0.16
    val acc = DoubleArray(4)
    for (oy in floor(cy - half).toInt() until min(h, ceil(cy + half).toInt())) {
        for (ox in floor(cx - half).toInt() until min(w, ceil(cx + half).toInt())) {
            acc.fill(0.0)
            for (sy in 0..2) for (sx in 0..2) {
                val x = ox + (sx + 0.5) / 3
                val y = oy + (sy + 0.5) / 3
                if (edge(x, y) <= 0) sample(l.a, l.r, l.g, l.b, icon.w, icon.h, (x - (cx - half)) * fx - 0.5, (y - (cy - half)) * fy - 0.5, acc)
            }
            val a = acc[0] / 9
            val i = oy * w + ox
            var c = out[i]
            if (a > 0.001) {
                var mixed = 0xFF shl 24
                for (ch in 0..2) mixed = mixed or ((channel(c, ch) * (1 - a) + acc[ch + 1] / 9).toInt().coerceIn(0, 255) shl (16 - 8 * ch))
                c = mixed
            }
            // The rim: a one-pixel line just inside the edge, anti-aliased.
            val ring = max(0.0, 1 - 2 * abs(edge(ox + 0.5, oy + 0.5) + 0.5)) * rimAlpha
            if (ring > 0) {
                var ringed = 0xFF shl 24
                for (ch in 0..2) ringed = ringed or ((channel(c, ch) * (1 - ring) + rim[ch] * ring).toInt().coerceIn(0, 255) shl (16 - 8 * ch))
                c = ringed
            }
            out[i] = c
        }
    }
    return out
}

/** Draws the logo from [box] of [logo] into [rect] of the opaque tile [out], its plain strokes as [strokes] says. */
private fun placeLogo(out: IntArray, w: Int, h: Int, logo: Logo, box: IntArray, rect: DoubleArray, strokes: Strokes,
                      backdrop: DoubleArray? = null) {
    val bw = box[2]
    val bh = box[3]
    val la = FloatArray(bw * bh)
    val lr = FloatArray(bw * bh)
    val lg = FloatArray(bw * bh)
    val lb = FloatArray(bw * bh)
    for (y in 0 until bh) for (x in 0 until bw) {
        val s = (box[1] + y) * logo.w + box[0] + x
        val d = y * bw + x
        la[d] = logo.a[s]; lr[d] = logo.r[s]; lg[d] = logo.g[s]; lb[d] = logo.b[s]
    }
    when (strokes) {
        Strokes.WHITE -> recolourDarkStrokes(la, lr, lg, lb, bw, bh)
        Strokes.INK -> if (backdrop == null || !backBadge(la, lr, lg, lb, bw, bh, backdrop)) inkPaleStrokes(la, lr, lg, lb, bw, bh)
        Strokes.KEEP -> {}
    }

    val fx = bw / rect[2]
    val fy = bh / rect[3]
    val acc = DoubleArray(4)
    for (oy in floor(rect[1]).toInt() until min(h, ceil(rect[1] + rect[3]).toInt())) {
        for (ox in floor(rect[0]).toInt() until min(w, ceil(rect[0] + rect[2]).toInt())) {
            acc.fill(0.0)
            // 3x3 bilinear samples per output pixel: smooth when the logo is scaled down.
            for (sy in 0..2) for (sx in 0..2) {
                val px = (ox + (sx + 0.5) / 3 - rect[0]) * fx - 0.5
                val py = (oy + (sy + 0.5) / 3 - rect[1]) * fy - 0.5
                sample(la, lr, lg, lb, bw, bh, px, py, acc)
            }
            val a = acc[0] / 9
            if (a <= 0.001) continue
            val i = oy * w + ox
            val bg = out[i]
            var c = 0xFF shl 24
            for (ch in 0..2) {
                val v = channel(bg, ch) * (1 - a) + acc[ch + 1] / 9
                c = c or (v.toInt().coerceIn(0, 255) shl (16 - 8 * ch))
            }
            out[i] = c
        }
    }
}

/** Adds the premultiplied bilinear sample at (x, y) to acc = (alpha, r*a, g*a, b*a); outside the logo is clear. */
private fun sample(la: FloatArray, lr: FloatArray, lg: FloatArray, lb: FloatArray, w: Int, h: Int, x: Double, y: Double, acc: DoubleArray) {
    val x0 = floor(x).toInt()
    val y0 = floor(y).toInt()
    val tx = x - x0
    val ty = y - y0
    for (dy in 0..1) for (dx in 0..1) {
        val xx = x0 + dx
        val yy = y0 + dy
        if (xx < 0 || yy < 0 || xx >= w || yy >= h) continue
        val wgt = (if (dx == 0) 1 - tx else tx) * (if (dy == 0) 1 - ty else ty)
        val i = yy * w + xx
        val a = la[i] * wgt
        acc[0] += a; acc[1] += lr[i] * a; acc[2] += lg[i] * a; acc[3] += lb[i] * a
    }
}

private fun recolourDarkStrokes(la: FloatArray, lr: FloatArray, lg: FloatArray, lb: FloatArray, w: Int, h: Int) {
    val n = w * h
    // Faint edge pixels belong to their stroke too, so the anti-aliasing turns white with it.
    fun dark(i: Int): Boolean {
        val lum = (0.2126 * lr[i] + 0.7152 * lg[i] + 0.0722 * lb[i]) / 255
        return la[i] > 0.02f && lum < 0.42 && chroma(lr[i], lg[i], lb[i]) < 60
    }
    val seen = BooleanArray(n)
    val stack = IntArray(n)
    val members = IntArray(n)
    val nb = IntArray(4)
    for (start in 0 until n) {
        if (seen[start] || !dark(start)) continue
        var top = 0
        var count = 0
        seen[start] = true
        stack[top++] = start
        while (top > 0) {
            val i = stack[--top]
            members[count++] = i
            for (k in 0 until neighbours(i, w, n, nb)) {
                val j = nb[k]
                if (!seen[j] && dark(j)) { seen[j] = true; stack[top++] = j }
            }
        }
        if (count < n * 0.15) for (m in 0 until count) {
            val i = members[m]
            lr[i] = 245f; lg[i] = 245f; lb[i] = 245f
        }
    }
}

private val INK = floatArrayOf(29f, 29f, 31f)

/** The share of the ring along the logo's box edge that the logo covers: near 1 for a framed badge (Sony LIV's). */
internal fun frameShare(la: FloatArray, w: Int, h: Int): Double {
    val t = max(2, (0.03 * min(w, h)).toInt())
    var ring = 0
    var covered = 0
    for (y in 0 until h) for (x in 0 until w) {
        if (x >= t && y >= t && x < w - t && y < h - t) continue
        ring++
        if (la[y * w + x] > 0.3f) covered++
    }
    return covered / max(1, ring).toDouble()
}

/**
 * A badge (a framed logo filling its box, like Sony LIV's) on a dark brand colour lost its dark inside to the
 * background when cut out. For a white plate it goes back on a [backdrop]-coloured rounded rectangle the shape of the
 * badge (corners measured from the logo), so it shows as on its own colour. False, and nothing done, for other logos:
 * a white symbol or outline (mostly pale: it's inked instead) or one with nothing see-through inside its frame.
 */
private fun backBadge(la: FloatArray, lr: FloatArray, lg: FloatArray, lb: FloatArray, w: Int, h: Int, backdrop: DoubleArray): Boolean {
    if (frameShare(la, w, h) < 0.6) return false
    var weight = 0.0
    var pale = 0.0
    var inside = 0
    var clear = 0
    val t = max(2, (0.03 * min(w, h)).toInt())
    for (y in 0 until h) for (x in 0 until w) {
        val i = y * w + x
        weight += la[i]
        val lum = (0.2126 * lr[i] + 0.7152 * lg[i] + 0.0722 * lb[i]) / 255
        if (lum > 0.6 && chroma(lr[i], lg[i], lb[i]) < 110) pale += la[i]
        if (x >= t && y >= t && x < w - t && y < h - t) {
            inside++
            if (la[i] < 0.5f) clear++
        }
    }
    if (pale > weight * 0.5 || clear < inside * 0.15) return false
    // A rounded corner of radius r starts r(1 - 1/√2) in along the diagonal; the sharpest corner gives the radius.
    fun inset(x0: Int, y0: Int, dx: Int, dy: Int): Int {
        var d = 0
        while (d < min(w, h) / 2 && la[(y0 + dy * d) * w + x0 + dx * d] <= 0.5f) d++
        return d
    }
    val r = minOf(inset(0, 0, 1, 1), inset(w - 1, 0, -1, 1), inset(0, h - 1, 1, -1), inset(w - 1, h - 1, -1, -1)) / 0.2929
    for (y in 0 until h) for (x in 0 until w) {
        val fx = x + 0.5
        val fy = y + 0.5
        val cx = if (fx < r) r else if (fx > w - r) w - r else fx
        val cy = if (fy < r) r else if (fy > h - r) h - r else fy
        val cover = if (cx == fx || cy == fy) 1.0 else (r - sqrt((fx - cx) * (fx - cx) + (fy - cy) * (fy - cy)) + 0.5).coerceIn(0.0, 1.0)
        if (cover <= 0) continue
        val i = y * w + x
        val a = la[i].toDouble()
        val under = cover * (1 - a)
        val out = a + under
        lr[i] = ((lr[i] * a + backdrop[0] * under) / out).toFloat()
        lg[i] = ((lg[i] * a + backdrop[1] * under) / out).toFloat()
        lb[i] = ((lb[i] * a + backdrop[2] * under) / out).toFloat()
        la[i] = out.toFloat()
    }
    return true
}

/**
 * For a white plate: logo parts that are mostly pale (white wordmarks and symbols, Apple TV's pastel logo) turn ink
 * whole, so they read and keep one colour; parts that are mostly colour or dark (CloudStream's cloud, Sony LIV's badge
 * with its white letters) keep their colours. Faint edge pixels belong to their part, so the anti-aliasing turns too.
 */
private fun inkPaleStrokes(la: FloatArray, lr: FloatArray, lg: FloatArray, lb: FloatArray, w: Int, h: Int) {
    val n = w * h
    fun pale(i: Int): Boolean {
        val lum = (0.2126 * lr[i] + 0.7152 * lg[i] + 0.0722 * lb[i]) / 255
        return lum > 0.6 && chroma(lr[i], lg[i], lb[i]) < 110
    }
    val seen = BooleanArray(n)
    val stack = IntArray(n)
    val members = IntArray(n)
    val nb = IntArray(4)
    for (start in 0 until n) {
        if (seen[start] || la[start] <= 0.02f) continue
        var top = 0
        var count = 0
        var paleWeight = 0.0
        var weight = 0.0
        seen[start] = true
        stack[top++] = start
        while (top > 0) {
            val i = stack[--top]
            members[count++] = i
            weight += la[i]
            if (pale(i)) paleWeight += la[i]
            for (k in 0 until neighbours(i, w, n, nb)) {
                val j = nb[k]
                if (!seen[j] && la[j] > 0.02f) { seen[j] = true; stack[top++] = j }
            }
        }
        if (paleWeight > weight * 0.5) for (m in 0 until count) {
            val i = members[m]
            lr[i] = INK[0]; lg[i] = INK[1]; lb[i] = INK[2]
        }
    }
}
