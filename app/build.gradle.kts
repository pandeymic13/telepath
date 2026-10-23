import java.util.Properties

plugins {
    id("com.android.application")
}

/**
 * telepath's settings: telepath.properties (in git), then telepath.local.properties (yours, git-ignored) on
 * top. Every value is checked here, so a typo stops the build with a clear message instead of reaching the TV.
 * The app reads them from BuildConfig. See docs/CUSTOMIZING.md.
 */
val telepath = Properties().apply {
    for (name in listOf("telepath.properties", "telepath.local.properties")) {
        val file = rootProject.file(name)
        if (file.exists()) file.reader(Charsets.UTF_8).use { load(it) }
    }
}

fun setting(key: String): String =
    telepath.getProperty(key)?.trim() ?: throw GradleException("telepath.properties: $key is missing")

fun invalid(key: String, why: String): Nothing =
    throw GradleException("telepath.properties: $key $why (it is \"${setting(key)}\")")

fun javaString(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

val packagePattern = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")

fun colour(key: String, value: String = setting(key)): String {
    val hex = value.trim().removePrefix("#")
    if (!Regex("[0-9A-Fa-f]{6}|[0-9A-Fa-f]{8}").matches(hex)) invalid(key, "needs colours written as #RRGGBB or #AARRGGBB")
    return "0x" + (if (hex.length == 6) "FF" else "") + hex.uppercase()
}

fun colours(key: String, count: Int): String {
    val parts = setting(key).split(',')
    if (parts.size != count) invalid(key, "needs $count colours separated by commas")
    return "new int[]{" + parts.joinToString(", ") { colour(key, it) } + "}"
}

fun flag(key: String): String = when (setting(key).lowercase()) {
    "true", "false" -> setting(key).lowercase()
    else -> invalid(key, "must be true or false")
}

fun choice(key: String, vararg allowed: String): String =
    setting(key).lowercase().takeIf { it in allowed } ?: invalid(key, "must be one of: ${allowed.joinToString()}")

fun packages(key: String): String {
    val list = setting(key).split(',').map { it.trim() }.filter { it.isNotEmpty() }
    list.firstOrNull { !packagePattern.matches(it) }?.let { invalid(key, "lists \"$it\", which isn't an Android package name") }
    return list.distinct().joinToString(",")
}

fun coordinate(key: String, limit: Double): String {
    val v = setting(key).toDoubleOrNull()?.takeIf { it in -limit..limit } ?: invalid(key, "must be a number from -$limit to $limit")
    return "${v}f"
}

fun symbols(key: String): String {
    val list = setting(key).split(',').map { it.trim() }.filter { it.isNotEmpty() }
    if (list.size > 5) invalid(key, "can list at most five symbols")
    list.firstOrNull { !Regex("[A-Za-z0-9^][A-Za-z0-9.^=-]{0,23}").matches(it) }?.let { invalid(key, "has \"$it\", which isn't a Yahoo Finance symbol") }
    return list.joinToString(",")
}

fun teams(key: String): String {
    val list = setting(key).split(',').map { it.trim() }.filter { it.isNotEmpty() }
    list.firstOrNull { !Regex("\\d+\\s*:\\s*[^:]*\\S[^:]*").matches(it) }
        ?.let { invalid(key, "has \"$it\": write each team as its TheSportsDB ID, a colon and its name, like 133664:FC Bayern Munich") }
    return list.joinToString(",") { it.substringBefore(':').trim() + ":" + it.substringAfter(':').trim() }
}

val weatherCity = setting("weather.city")
val projectUrl = setting("project.url")
if (projectUrl.isNotEmpty() && !projectUrl.startsWith("https://")) invalid("project.url", "must start with https://")
val primaryPort = setting("inputs.primary.port").toIntOrNull()?.takeIf { it in 0..20 }
    ?: invalid("inputs.primary.port", "must be an HDMI port number, or 0 for none")

// Framework Views only: no runtime libraries.
android {
    namespace = "dev.plume"
    compileSdk = 36

    defaultConfig {
        applicationId = setting("app.id").takeIf { packagePattern.matches(it) } ?: invalid("app.id", "isn't an Android package name")
        minSdk = 26
        targetSdk = 36
        versionCode = 7
        versionName = "1.0"
        // What's new: only this version's section of CHANGELOG.md ships, one entry per line, without ** marks.
        val notes = rootProject.file("CHANGELOG.md").readText().replace("\r", "")
            .substringAfter("\n## $versionName\n", "").substringBefore("\n## ")
            .lines().filter { it.startsWith("- ") }.map { it.removePrefix("- ").replace("**", "") }
        require(notes.isNotEmpty()) { "CHANGELOG.md has no entries under \"## $versionName\"" }
        resValue("string", "whats_new", notes.joinToString("\\n") { it.replace("'", "\\'").replace("\"", "\\\"") })

        buildConfigField("String", "PROJECT_URL", javaString(projectUrl))
        buildConfigField("String", "FAVOURITES", javaString(packages("home.favourites")))
        buildConfigField("int", "PRIMARY_INPUT_PORT", primaryPort.toString())
        buildConfigField("String", "PRIMARY_INPUT_NAME", javaString(setting("inputs.primary.name")))
        buildConfigField("String", "PRIMARY_INPUT_ICON", javaString(choice("inputs.primary.icon",
            "auto", "xbox", "playstation", "nintendoswitch", "appletv", "settop", "playback", "hdmi")))
        buildConfigField("String", "WEATHER_CITY", javaString(weatherCity))
        buildConfigField("float", "WEATHER_LAT", if (weatherCity.isEmpty()) "Float.NaN" else coordinate("weather.latitude", 90.0))
        buildConfigField("float", "WEATHER_LON", if (weatherCity.isEmpty()) "Float.NaN" else coordinate("weather.longitude", 180.0))
        buildConfigField("String", "WATCHLIST", javaString(symbols("watchlist.symbols")))
        buildConfigField("boolean", "UPNEXT_ON", flag("upnext.enabled"))
        buildConfigField("boolean", "UPNEXT_F1", flag("upnext.f1"))
        buildConfigField("boolean", "UPNEXT_CHAMPIONS_LEAGUE", flag("upnext.championsLeague"))
        buildConfigField("boolean", "UPNEXT_WORLD_CUP", flag("upnext.worldCup"))
        buildConfigField("String", "UPNEXT_TEAMS", javaString(teams("upnext.teams")))
        buildConfigField("String", "THEME_MODE", javaString(choice("theme.mode", "auto", "light", "dark")))
        buildConfigField("String", "MOOD_MODE", javaString(choice("theme.mood", "weather", "time", "off")))
        for ((side, prefix) in listOf("DAY" to "theme.day", "NIGHT" to "theme.night")) {
            buildConfigField("int", "${side}_TEXT", colour("$prefix.text"))
            buildConfigField("int", "${side}_TEXT2", colour("$prefix.textSecondary"))
            buildConfigField("int", "${side}_TEXT3", colour("$prefix.textTertiary"))
            buildConfigField("int", "${side}_DIVIDER", colour("$prefix.divider"))
            buildConfigField("int", "${side}_GROUND", colour("$prefix.ground"))
            buildConfigField("int", "${side}_FROST", colour("$prefix.frost"))
            buildConfigField("int[]", "${side}_GLASS", colours("$prefix.glass", 2))
            buildConfigField("int", "${side}_GLASS_RIM", colour("$prefix.glassRim"))
            buildConfigField("int[]", "${side}_GLASS_FOCUSED", colours("$prefix.glassFocused", 2))
            buildConfigField("int", "${side}_GLASS_FOCUSED_RIM", colour("$prefix.glassFocusedRim"))
        }
        buildConfigField("int", "FOCUS_FILL", colour("theme.focus"))
        buildConfigField("int", "FOCUS_TEXT", colour("theme.focusText"))
        for (phase in listOf("dawn", "day", "golden", "sunset", "dusk", "night")) {
            buildConfigField("int[]", "MOOD_${phase.uppercase()}", colours("mood.$phase", 3))
        }
    }

    buildFeatures {
        buildConfig = true
        resValues = true
    }

    // Release key lives outside the repo; keystore.properties (git-ignored) points at it. Without that file
    // (a fresh clone) release builds fall back to the debug key, so anyone can still build and sideload.
    val keys = rootProject.file("keystore.properties").takeIf { it.exists() }
        ?.let { f -> Properties().apply { f.inputStream().use { load(it) } } }
    signingConfigs {
        if (keys != null) create("release") {
            storeFile = file(keys.getProperty("storeFile"))
            storePassword = keys.getProperty("storePassword")
            keyAlias = keys.getProperty("keyAlias")
            keyPassword = keys.getProperty("keyPassword")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
