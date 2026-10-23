package dev.plume

import java.net.URLDecoder

internal const val YOUTUBE_PKG = "com.google.android.youtube.tv"

private val PROXY = Regex("^https?://(?:images\\.)?(?:wsrv|weserv)\\.nl/\\?(?:[^#]*&)?url=([^&#]+)", RegexOption.IGNORE_CASE)
private val YOUTUBE_THUMB = Regex("^https?://i\\d?\\.ytimg\\.com/vi(?:_webp)?/([A-Za-z0-9_-]{6,})/([A-Za-z0-9_]+)\\.(?:jpg|webp)")
private val METAHUB_POSTER = Regex("^https?://images\\.metahub\\.space/poster/[a-z]+/(tt\\d+)/img")
private val TVDB_SERIES_POSTER = Regex("^https?://artworks\\.thetvdb\\.com/banners/posters/(\\d+)-\\d+\\.jpg")
private val TVDB_MOVIE_POSTER = Regex("^https?://artworks\\.thetvdb\\.com/banners/movies/(\\d+)/posters/")

/** The picture behind an image proxy link (CloudStream sends its posters through wsrv.nl), or null. */
internal fun proxiedImage(url: String): String? = PROXY.find(url)?.groupValues?.get(1)?.let {
    if ('%' in it) URLDecoder.decode(it, "UTF-8") else it
}

/**
 * Wide (16:9) pictures to try before a poster, best first, from the same image hosts: YouTube's letterbox-free
 * thumbnail, Metahub's background for an IMDb id, TheTVDB's fan art for a series or background for a film.
 * Empty when there's nothing wider to try.
 */
internal fun landscapeCandidates(url: String): List<String> {
    val target = proxiedImage(url) ?: url
    YOUTUBE_THUMB.find(target)?.let { m ->
        return if (m.groupValues[2] == "mqdefault") emptyList() else listOf("https://i.ytimg.com/vi/${m.groupValues[1]}/mqdefault.jpg")
    }
    METAHUB_POSTER.find(target)?.let { return listOf("https://images.metahub.space/background/medium/${it.groupValues[1]}/img") }
    TVDB_SERIES_POSTER.find(target)?.let { return listOf("https://artworks.thetvdb.com/banners/fanart/original/${it.groupValues[1]}-1.jpg") }
    TVDB_MOVIE_POSTER.find(target)?.let {
        val id = it.groupValues[1]
        return listOf("https://artworks.thetvdb.com/banners/movies/$id/backgrounds/$id.jpg")
    }
    return emptyList()
}

/** CloudStream's "S4:E3 The Show" as ("The Show", "4", "3"); other titles as they are, with no episode. */
internal fun splitEpisodeTitle(title: String): Triple<String, String?, String?> {
    val m = Regex("^S(\\d+)\\s*:\\s*E(\\d+)\\s+(.+)$").find(title.trim()) ?: return Triple(title, null, null)
    return Triple(m.groupValues[3].trim(), m.groupValues[1], m.groupValues[2])
}

/**
 * The part of a [sw]×[sh] picture that fills a [w]:[h] card: centred across, and [bias] of the spare height above
 * it (0.5 centred; less keeps more of the top, where a poster's faces usually are). Left, top, right, bottom.
 */
internal fun cropRect(sw: Int, sh: Int, w: Int, h: Int, bias: Float = 0.5f): IntArray =
    if (sw.toLong() * h > sh.toLong() * w) {
        val cw = (sh.toLong() * w / h).toInt()
        intArrayOf((sw - cw) / 2, 0, (sw - cw) / 2 + cw, sh)
    } else {
        val ch = (sw.toLong() * h / w).toInt()
        val top = ((sh - ch) * bias.coerceIn(0f, 1f)).toInt()
        intArrayOf(0, top, sw, top + ch)
    }
