package dev.plume

/*
 * What telepath.properties (with your telepath.local.properties on top) chose for this build, as the app uses it.
 * The build checks the values and puts them in BuildConfig; see app/build.gradle.kts and docs/CUSTOMIZING.md.
 */

/** "a, b,,c" → [a, b, c]: trimmed, with empty entries and repeats dropped. */
internal fun parseList(s: String): List<String> = s.split(',').map { it.trim() }.filter { it.isNotEmpty() }.distinct()

/** A team the Up next line follows: its TheSportsDB ID and the name shown for it. */
internal data class FollowedTeam(val id: Int, val name: String)

/** "133664:FC Bayern Munich,137143:India" → the teams, each once. Entries without an ID and a name are skipped. */
internal fun parseTeams(s: String): List<FollowedTeam> = parseList(s).mapNotNull { entry ->
    val id = entry.substringBefore(':').trim().toIntOrNull()
    val name = entry.substringAfter(':', "").trim()
    if (id == null || name.isEmpty()) null else FollowedTeam(id, name)
}.distinctBy { it.id }

/** The dock's first favourites, left to right (home.favourites). */
internal val DEFAULT_FAVOURITES: List<String> = parseList(BuildConfig.FAVOURITES)

/** The teams the Up next line follows (upnext.teams). */
internal val FOLLOWED_TEAMS: List<FollowedTeam> = parseTeams(BuildConfig.UPNEXT_TEAMS)

/** "a", "a and b", "a, b, and c": [items] as a sentence would list them, with [and] in the reader's language. */
internal fun joinWithAnd(items: List<String>, and: String): String = when (items.size) {
    0 -> ""
    1 -> items[0]
    2 -> "${items[0]} $and ${items[1]}"
    else -> items.dropLast(1).joinToString(", ") + ", $and " + items.last()
}

/** Where telepath points for help with the Home button: your project.url, else Plume Launcher's guide. */
internal fun helpUrl(projectUrl: String = BuildConfig.PROJECT_URL) =
    projectUrl.trimEnd('/').ifEmpty { "https://github.com/aradhyas8/Plume_Launcher" } + "#home-button-on-android-13-and-newer"
