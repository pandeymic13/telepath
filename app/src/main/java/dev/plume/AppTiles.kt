package dev.plume

import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.PackageInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.util.Log
import java.io.File
import java.io.IOException

/**
 * Apps shown under another brand: the name, the logo for the tile and a stacked one for the small icon (transparent
 * PNGs in assets, already in the colours to show), the dark tile's tint, and how much bigger than standard the logo
 * sits. The light theme's tile and icon show [lightLogo] and [lightIconLogo] on [plate].
 */
internal class Rebrand(val label: String, val logo: String, val iconLogo: String, val tint: DoubleArray, val scale: Double = 1.0,
                       val lightLogo: String = logo, val lightIconLogo: String = iconLogo, val plate: DoubleArray = LIGHT_PLATE)

internal val REBRANDS = mapOf(
    // JioHotstar as Disney+ Hotstar: its 2020 logo (white lettering, the arc lit at the "+") on Disney+ navy, a third
    // larger than standard, since its thin script and the arc above it read small next to Netflix's bold capitals.
    // In the light theme: the logo as published (navy lettering, the arc rising out of white) on white.
    // Logos: Wikimedia Commons "Disney+ Hotstar logo.svg" and "Disney+ Hotstar 2024.svg" (public domain text logos),
    // recoloured for dark backgrounds, and the stacked one in the 2020 navy for light ones. Trademarks of their owner.
    "in.startv.hotstar" to Rebrand("Disney+ Hotstar", "logos/disney_hotstar.png", "logos/disney_hotstar_stacked.png",
        doubleArrayOf(34.0, 72.0, 230.0), scale = 1.3, lightLogo = "logos/disney_hotstar_light.png",
        lightIconLogo = "logos/disney_hotstar_stacked_light.png", plate = doubleArrayOf(255.0, 255.0, 255.0)),
)

/**
 * The start of an app's tile file names (the theme and ".png" follow): the package, its version and install or update
 * time (so a new version, or the app reinstalled, gets new tiles), the tile format, the bundled logo's version for a
 * rebranded app ([rebrand] above 0), and the [style]. Package names can't contain "-", so everything before the first
 * one is the package ([tileOwner]), even for packages that start alike ("com.foo" and "com.foo_bar").
 */
internal fun tileName(pkg: String, version: Long, updated: Long, rebrand: Int, style: TileStyle) =
    "$pkg-$version-$updated-v$TILE_FORMAT" + (if (rebrand > 0) "-rb$rebrand" else "") + style.suffix

internal fun tileOwner(fileName: String) = fileName.substringBefore('-')

internal const val TILE_FORMAT = 3 // bump when TileArt changes, so every tile is made again

/**
 * The Apple TV+-style app tiles (TileArt.kt), one for each theme, worked out once per app version and kept as small
 * PNGs in the cache folder: an app's tiles are redone only after it updates or is reinstalled (its version and update
 * time are in the file names), or when its group or tile choice changes its [TileStyle]. Tiles of apps since
 * uninstalled are cleared away ([prune]). Background threads only, except [icon].
 */
internal class AppTiles(private val context: Context) {
    private val dir = File(context.cacheDir, "tiles")

    /** The start of the app's tile file names (see [tileName]) for the version in [info], made in [style]. */
    fun key(info: PackageInfo, style: TileStyle): String {
        @Suppress("DEPRECATION")
        val version = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
        val rebrand = if (info.packageName in REBRANDS && style != TileStyle.ICON) REBRAND_VERSION else 0
        return tileName(info.packageName, version, info.lastUpdateTime, rebrand, style)
    }

    private fun file(key: String, light: Boolean) = File(dir, key + (if (light) "-light" else "") + ".png")

    /**
     * The app's tile for the [light] theme or the dark one, if it's been made (else null), and whether both of its
     * tiles are made, so a theme switch never waits for one.
     */
    fun cached(key: String, light: Boolean): Pair<Bitmap?, Boolean> {
        val tile = file(key, light).takeIf { it.isFile }?.let { BitmapFactory.decodeFile(it.path) }
        return tile to (tile != null && file(key, !light).isFile)
    }

    /**
     * Makes and stores both of the app's tiles in [style] (a rebranded app's from its bundled logos), falling back to
     * its plain icon when no logo or symbol can be cut out; false only if not even its icon can be read.
     */
    fun make(info: ActivityInfo, key: String, style: TileStyle): Boolean {
        val pkg = info.packageName
        val tiles = REBRANDS[pkg]?.takeIf { style != TileStyle.ICON }?.let { r ->
            val dark = asset(r.logo)?.let { composeLogoTile(it, r.tint, scale = r.scale) }
            val light = asset(r.lightLogo)?.let { composeLogoTile(it, r.tint, scale = r.scale, light = Flat(r.plate)) }
            if (dark != null && light != null) dark to light else null
        } ?: (if (style == TileStyle.ICON) null else fromApp(info, style == TileStyle.SYMBOL))?.let { it.dark() to it.light() }
            ?: iconPixels(info)?.let { composeIconTile(it, light = false) to composeIconTile(it, light = true) }
            ?: return false
        save(pkg, key, tiles.first, tiles.second)
        return true
    }

    /**
     * The app's plain tile ([composeIconTile]) for the [light] theme or the dark one, made on the spot in a few
     * milliseconds: shown while its own tiles are made, so a new app is never a blank tile.
     */
    fun plain(info: ActivityInfo, light: Boolean): Bitmap? =
        iconPixels(info)?.let { Bitmap.createBitmap(composeIconTile(it, light), TILE_W, TILE_H, Bitmap.Config.ARGB_8888) }

    /** The app's own launcher icon (adaptive ones as the TV shapes them), or null if it can't be read. */
    private fun iconPixels(info: ActivityInfo): Pixels? = try {
        pixels(info.loadIcon(context.packageManager), ICON_SIDE, ICON_SIDE)
    } catch (e: RuntimeException) {
        Log.w("Telepath", "icon for ${info.packageName} unreadable: ${e.javaClass.simpleName}")
        null
    }

    /**
     * Deletes every tile file but both themes' of [keep]: older versions and other styles of an app, apps since
     * uninstalled, the old file format, and leftovers of an interrupted save (once they're a minute old, so a save
     * still under way elsewhere is left alone). Returns how many went.
     */
    fun prune(keep: Collection<String>): Int {
        val names = keep.flatMap { listOf(file(it, false).name, file(it, true).name) }.toSet()
        val now = System.currentTimeMillis()
        return dir.listFiles()?.count { f ->
            f.name !in names && (!f.name.endsWith(".part") || now - f.lastModified() > 60_000L) && f.delete()
        } ?: 0
    }

    /** The makings of the app's tiles from its own banner and icon; [symbol]: its symbol only, never its banner. */
    private fun fromApp(info: ActivityInfo, symbol: Boolean): TileCut? {
        val pm = context.packageManager
        val banner = if (symbol) null else info.loadBanner(pm)?.let { b ->
            // Never drawn bigger than it is: upscaling softens edges, and soft edges read as glow, not logo.
            val w = if (b.intrinsicWidth > 0) minOf(360, b.intrinsicWidth) else 360
            val h = if (b.intrinsicWidth > 0 && b.intrinsicHeight > 0) w * b.intrinsicHeight / b.intrinsicWidth else w * 9 / 16
            pixels(b, w, h.coerceIn(1, w))
        }
        val icon = info.loadIcon(pm)
        val adaptive = icon as? AdaptiveIconDrawable
        val fg = adaptive?.foreground?.let { pixels(it, 324, 324) }
        val bg = adaptive?.background?.let { pixels(it, 108, 108) }
        val side = if (icon.intrinsicWidth > 0) icon.intrinsicWidth.coerceIn(48, 216) else 216
        val whole = if (fg == null || bg == null) pixels(icon, side, side) else null
        return cutTile(info.packageName, banner, fg, bg, whole, symbol)
    }

    /**
     * A rebranded app's small square icon for the [light] theme or the dark one (Continue Watching shows it beside a
     * card's details), or null for apps that keep their own icon. Made once, then kept in memory.
     */
    fun icon(pkg: String, light: Boolean): Bitmap? {
        val r = REBRANDS[pkg] ?: return null
        val key = if (light) "$pkg/light" else pkg
        synchronized(ICONS) {
            ICONS[key]?.let { return it }
            val px = asset(if (light) r.lightIconLogo else r.iconLogo)?.let {
                composeLogoTile(it, r.tint, ICON, ICON, icon = true, light = if (light) Flat(r.plate) else null)
            } ?: return null
            return Bitmap.createBitmap(px, ICON, ICON, Bitmap.Config.ARGB_8888).also { ICONS[key] = it }
        }
    }

    private fun asset(path: String): Pixels? = try {
        context.assets.open(path).use { BitmapFactory.decodeStream(it) }?.let { bmp ->
            val px = IntArray(bmp.width * bmp.height)
            bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
            Pixels(px, bmp.width, bmp.height).also { bmp.recycle() }
        }
    } catch (e: IOException) {
        Log.w("Telepath", "logo $path missing: ${e.message}")
        null
    }

    private fun pixels(d: Drawable, w: Int, h: Int): Pixels {
        val bmp = d.toBitmap(w, h)
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        bmp.recycle()
        return Pixels(px, w, h)
    }

    /**
     * Both tiles written beside, then renamed: a half-written tile never shows. The app's other tiles (older
     * versions, another style) go.
     */
    private fun save(pkg: String, key: String, dark: IntArray, light: IntArray) {
        val keep = listOf(file(key, false), file(key, true))
        try {
            if (!dir.isDirectory && !dir.mkdirs()) throw IOException("No tile folder.")
            for ((target, px) in keep.zip(listOf(dark, light))) {
                val tile = Bitmap.createBitmap(px, TILE_W, TILE_H, Bitmap.Config.ARGB_8888)
                val part = File(dir, target.name + ".part")
                try {
                    part.outputStream().use { if (!tile.compress(Bitmap.CompressFormat.PNG, 100, it)) throw IOException("PNG failed.") }
                } finally {
                    tile.recycle()
                }
                if (!part.renameTo(target)) throw IOException("Rename failed.")
            }
            val names = keep.map { it.name }
            dir.listFiles { f -> tileOwner(f.name) == pkg && f.name !in names }?.forEach { it.delete() }
        } catch (e: IOException) {
            Log.w("Telepath", "tiles for $pkg not cached: ${e.message}")
        }
    }

    private companion object {
        const val REBRAND_VERSION = 2 // bump when a bundled logo or its size changes, so only those tiles are made again
        const val ICON = 96
        const val ICON_SIDE = 192 // app icons are read at this size for the plain tile
        val ICONS = HashMap<String, Bitmap>()
    }
}
