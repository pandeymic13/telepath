package dev.plume

/**
 * Streaming services and media apps known to say little about themselves (PlayZ TV declares nothing at all), and the
 * dock's default favourites; the rest are recognised from their own manifest, see [isStreamingApp].
 */
internal val STREAMING_APPS = DEFAULT_FAVOURITES.toSet() + setOf(
    "com.netflix.ninja",
    "com.google.android.youtube.tv",
    "com.amazon.amazonvideo.livingroom",
    "com.apple.atve.androidtv.appletv",
    "in.startv.hotstar",
    "com.graymatrix.did",
    "com.playz.tv",
    "com.lagradost.cloudstream3.prerelease",
    "com.google.android.youtube.tvmusic",
    "com.spotify.tv.android",
    "com.fancode.tv",
    "com.sonyliv",
    "com.sonypicturescore",
    "com.jio.media.ondemand",
    "com.jio.media.stb.ondemand",
    "com.mxtech.videoplayer.ad",
    "com.mxtech.videoplayer.television",
    "com.crunchyroll.crunchyroid",
    "com.lagradost.cloudstream3",
    "com.stremio.one",
    "com.plexapp.android",
    "com.disney.disneyplus",
    "com.hbo.hbonow",
    "com.wbd.stream",
    "com.hulu.livingroomplus",
    "com.mubi",
    "tv.pluto.android",
    "com.aha.ott",
    "com.sunnxt",
    "com.eros.now",
    "com.balaji.alt",
    "com.discovery.discoveryplus.androidtv",
    "com.zee5.androidtv",
    "io.gh.reisxd.tizentube.cobalt",
)

// ApplicationInfo.CATEGORY_* values; -1 (CATEGORY_UNDEFINED) when an app declares none, as most TV apps do.
private const val CATEGORY_GAME = 0
private const val CATEGORY_AUDIO = 1
private const val CATEGORY_VIDEO = 2
private const val CATEGORY_MAPS = 6
private const val CATEGORY_PRODUCTIVITY = 7
private const val CATEGORY_ACCESSIBILITY = 8

/** Declared categories that are never streaming: games, maps, productivity (files, stores, downloaders), accessibility. */
private val UTILITY_CATEGORIES = setOf(CATEGORY_GAME, CATEGORY_MAPS, CATEGORY_PRODUCTIVITY, CATEGORY_ACCESSIBILITY)

/** The TV maker's and Android's own tools: Live TV, settings, Sony's helpers, the chip maker's media player. */
private val TOOL_PREFIXES = listOf("com.sony.dtv.", "com.android.", "com.google.android.tv", "com.realtek.", "com.mediatek.", "com.amlogic.")

/**
 * What an app says about itself that decides its All apps group: its declared [category] (ApplicationInfo.category,
 * -1 if none), whether it asks to put rows on the TV's home screen ([publishesToHome]: the WRITE_EPG_DATA permission,
 * for channels and Continue Watching, which video and music services ask for), and whether it came with the TV
 * ([system]) and has been updated from the Play Store since ([updated]).
 */
internal class AppTraits(val pkg: String, val category: Int = -1, val publishesToHome: Boolean = false,
                         val system: Boolean = false, val updated: Boolean = false)

/**
 * Streaming, or apps and utilities, for any app, best evidence first: the services listed in [known]; a declared video
 * or audio category (games, maps, productivity and accessibility apps are utilities); the TV's own tools are utilities;
 * then any app that puts rows on the home screen and was installed by you or updated from the Play Store. Everything
 * else is a utility. On this TV that gets every app right from its own manifest except PlayZ TV, which is listed.
 */
internal fun isStreamingApp(t: AppTraits, known: Set<String> = STREAMING_APPS): Boolean = when {
    t.pkg in known -> true
    t.category == CATEGORY_AUDIO || t.category == CATEGORY_VIDEO -> true
    t.category in UTILITY_CATEGORIES -> false
    TOOL_PREFIXES.any { t.pkg.startsWith(it) } -> false
    else -> t.publishesToHome && (!t.system || t.updated)
}

/** The group an app shows in: the one you moved it to in All apps, if any, else its [automatic] one. */
internal fun inStreaming(pkg: String, automatic: Boolean, chosenStreaming: Set<String>, chosenUtilities: Set<String>) = when (pkg) {
    in chosenStreaming -> true
    in chosenUtilities -> false
    else -> automatic
}

/**
 * The (Streaming, Apps & utilities) choice sets after moving [pkg] to Streaming ([streaming]) or the other group.
 * Moving it back to its automatic group forgets the choice, so it follows its own manifest again.
 */
internal fun moveGroup(pkg: String, streaming: Boolean, automatic: Boolean, chosenStreaming: Set<String>,
                       chosenUtilities: Set<String>): Pair<Set<String>, Set<String>> {
    val s = chosenStreaming - pkg
    val u = chosenUtilities - pkg
    return when {
        streaming == automatic -> s to u
        streaming -> (s + pkg) to u
        else -> s to (u + pkg)
    }
}
