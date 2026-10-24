# Customizing telepath

Your version of telepath is set by [`telepath.properties`](../telepath.properties) at the top of the repository.
Change it, build, and install the APK. If you don't want a setting published (your TV's address, a city), put that line
in `telepath.local.properties` instead: the file sits next to it, is git-ignored, and its values win.

Most settings are **first-run defaults**. Once telepath is installed, you can change them on the TV, and
installing a new build doesn't overwrite what you chose there. Colours are the exception: they come from the build
every time. To start a TV over with your new defaults, clear telepath's data (**Settings → Apps → telepath
→ Clear data**).

The build checks every value and stops with a message that names the setting if one isn't valid, for example:

```
telepath.properties: theme.day.text needs colours written as #RRGGBB or #AARRGGBB (it is "#12345")
```

## The app

| Setting | What it does |
|---|---|
| `app.id` | The Android package ID (default `dev.aster.home`). Give your build its own, such as `io.github.yourname.home`, to install it next to someone else's. A new ID is a new app: favourites and settings don't carry over. |
| `project.url` | Your fork's web address. telepath shows it as a QR code where it helps with the Home button, so it should have the README's *Home button on Android 13 and newer* section. Empty: Plume Launcher's guide. |

## Home screen

| Setting | What it does |
|---|---|
| `home.favourites` | The dock's apps, left to right, as Android package names separated by commas. Apps that aren't installed are skipped. Other apps are in All apps, where holding OK adds one to the favourites. |

To find an app's package name, open its Play Store page: it's the `id=` part of the address
(`https://play.google.com/store/apps/details?id=com.netflix.ninja`). Or, with adb: `adb shell pm list packages`.

Some common ones: Netflix `com.netflix.ninja`, YouTube `com.google.android.youtube.tv`, Prime Video
`com.amazon.amazonvideo.livingroom`, Disney+ `com.disney.disneyplus`, JioHotstar `in.startv.hotstar`, Apple TV
`com.apple.atve.androidtv.appletv`, Spotify `com.spotify.tv.android`, Plex `com.plexapp.android`, YouTube Music
`com.google.android.youtube.tvmusic`, Max `com.wbd.stream`.

## Inputs

The widgets page (press Down from the dock) lists the TV's HDMI inputs. One of them can be the **primary input**:
shown first, as a lit tile, even when nothing on it is switched on.

A device that names itself over HDMI-CEC (most consoles and streaming boxes do) gives its port its name and icon:
Xbox, PlayStation (including one that calls itself "PS5"), Nintendo Switch, Apple TV, set-top boxes and other
players. telepath remembers the device on each port, so the tile keeps its name and icon while the device is off; a
device seen on another port moves there. Consoles get their own colours: Xbox green, PlayStation blue, Nintendo red.
To forget a remembered device, hold OK on its tile while the device is off.

| Setting | What it does |
|---|---|
| `inputs.primary.port` | Its HDMI port number. `0`: no primary input; all ports in number order. |
| `inputs.primary.name` | The name on its tile, such as `Xbox`. Empty: the name the device gives over HDMI-CEC (or the one remembered), else the port's name. |
| `inputs.primary.icon` | `xbox`, `playstation`, `nintendoswitch`, `appletv`, `settop`, `playback`, `hdmi`, or `auto` to pick one from the name. The icon also sets the tile's colours. |

## Weather

| Setting | What it does |
|---|---|
| `weather.city` | The city's name as the Weather card shows it. Empty: found from the TV's internet address. |
| `weather.latitude`, `weather.longitude` | Where it is, in degrees (north and east are positive). Needed when `weather.city` is set. |

The location also sets where sunrise and sunset are worked out, for the automatic theme and the mood colours. On the
TV: hold OK on the Weather card, or **Home settings → Widgets → Weather location**. **Automatic** looks the place up
again.

```properties
weather.city=Lisbon
weather.latitude=38.7223
weather.longitude=-9.1393
```

## Watchlist

| Setting | What it does |
|---|---|
| `watchlist.symbols` | Up to five [Yahoo Finance](https://finance.yahoo.com) symbols, separated by commas: shares (`AAPL`, `RELIANCE.NS`), indices (`^GSPC`, `^NSEI`; `NIFTY` and `SENSEX` mean those indices) or currencies (`EURUSD=X`). A share without its exchange that Yahoo doesn't know, such as `RELIANCE`, is looked up on the TV and saved with it (`RELIANCE.NS`). Empty: the watchlist is off and nothing is requested. |

## Up next

The line beside the clock shows the live or next event of what you follow.

| Setting | What it does |
|---|---|
| `upnext.enabled` | `true` or `false`. Also on the TV: **Home settings → Widgets → Up next**. |
| `upnext.f1` | Formula 1 qualifying, sprint qualifying, sprint and race sessions. |
| `upnext.championsLeague` | The UEFA Champions League semi-finals and final. |
| `upnext.worldCup` | The FIFA World Cup semi-finals and final, in its tournament years. |
| `upnext.teams` | Teams, as their [TheSportsDB](https://www.thesportsdb.com) ID, a colon and the name to show, separated by commas. |

To find a team's ID, search for it on TheSportsDB and open its page: the number in the address is the ID
(`https://www.thesportsdb.com/team/133664-bayern-munich` is `133664`). Any sport TheSportsDB covers works; cricket
matches also show their format (Test, ODI, T20I) and, for Tests, the day.

```properties
upnext.teams=133664:FC Bayern Munich,137143:India,133612:Manchester United
```

TheSportsDB's free key lists only each team's next match, so a team's line can stay empty until its next fixture is
scheduled.

## Theme colours

| Setting | What it does |
|---|---|
| `theme.mode` | `auto` (light from sunrise to sunset, dark after), `light` or `dark`. Changeable on the TV. |
| `theme.mood` | Mood colours over the wallpaper: `weather` (time of day and weather), `time` (time of day only) or `off`. Changeable on the TV. |
| `theme.day.*`, `theme.night.*` | The colours of the light (day) and dark (night) themes, listed below. |
| `theme.focus`, `theme.focusText` | A focused tile's fill, and its text and icon, in both themes. |
| `mood.dawn` … `mood.night` | The tint over the wallpaper in each part of the day. |

Colours are `#RRGGBB`, or `#AARRGGBB` with the opacity first: `00` is clear and `FF` solid, so `#80FFFFFF` is
half-transparent white. Settings with two colours (`a,b`) are a gradient from top to bottom; mood colours have three:
the top, middle and bottom of the screen.

| Colour (`theme.day.` / `theme.night.`) | Used for |
|---|---|
| `text`, `textSecondary`, `textTertiary` | Titles; details and values; hints |
| `divider` | Lines between rows |
| `ground` | The colour behind everything, and plain wallpapers |
| `frost` | The tint over the wallpaper on the widgets page |
| `glass`, `glassRim` | Cards, the dock and panels: their fill and edge |
| `glassFocused`, `glassFocusedRim` | The same, when focused |

Keep mood opacities low (under `66`, about 40%): the tint is a wash over your photo, and weather makes it a little
thicker. For example, a warmer evening and a deep blue night:

```properties
mood.sunset=#50FF4E2A,#34FF7340,#48E8452C
mood.night=#460A1A48,#20102A64,#40081633
```

Over photos, the clock and Up next don't use the theme's text colour: they switch between white and near-black
(`INK_TEXT` in `HeaderLegibility.kt`) to suit each photo, and fall back to a glass panel with the theme's colours.

## Going further

Everything else is in the app's source, in `app/src/main/java/dev/plume/`:

| To change | Look in |
|---|---|
| Colours and shapes of cards and glass | `UiKit.kt` (the theme settings arrive there) |
| How mood colours react to weather | `Mood.kt` (`lookFor`) |
| App tiles, and logos shown under another brand | `TileArt.kt`, `AppTiles.kt` (`REBRANDS`; logos are in `app/src/main/assets/logos/`) |
| Which apps count as Streaming | `AppGroups.kt` (`STREAMING_APPS`, `isStreamingApp`) |
| Up next's sources | `UpNext.kt`, `Sports.kt` |
| Continue Watching | `MainActivity.kt` (`refreshWatchNext`), `CwArt.kt` |
| Wording and translations | `app/src/main/res/values*/strings.xml` |

Run the unit tests after a change: `./gradlew :app:testDebugUnitTest`.

## Sign your builds

Android installs an update only if it is signed with the same key as the version on the TV. Without a key of your own,
your computer's debug key signs local builds, and GitHub Actions uses a new throwaway key on every run, so an APK from
Actions can't update the one before it. To sign every build with your own key:

1. Make a key once (`keytool` comes with the JDK), and keep the file and passwords safe and private:

   ```sh
   keytool -genkeypair -v -keystore telepath.jks -alias telepath -keyalg RSA -keysize 4096 -validity 10000
   ```

2. **On your computer:** create `keystore.properties` at the top of the repository (it's git-ignored):

   ```properties
   storeFile=/full/path/to/telepath.jks
   storePassword=...
   keyAlias=telepath
   keyPassword=...
   ```

3. **On GitHub:** in your fork, open **Settings → Secrets and variables → Actions** and add the secrets
   `SIGNING_KEYSTORE_BASE64` (the keystore file in base64), `SIGNING_STORE_PASSWORD`, `SIGNING_KEY_ALIAS` and
   `SIGNING_KEY_PASSWORD`. To get the base64 text:

   ```sh
   base64 -w0 telepath.jks                                              # Linux
   base64 -i telepath.jks | tr -d '\n'                                  # macOS
   [Convert]::ToBase64String([IO.File]::ReadAllBytes("telepath.jks"))   # Windows PowerShell
   ```

A TV that has an APK signed with another key needs that one uninstalled first (its settings are lost), or a build
with a different `app.id`.

## Release a version

1. Raise `versionCode` and set `versionName` in `app/build.gradle.kts`.
2. Add a `## <versionName>` section to [CHANGELOG.md](../CHANGELOG.md): after the update, a card on the home screen
   shows its first entries. The build stops without it.
3. Commit, then tag and push: `git tag v1.1 && git push origin v1.1`. The Build workflow attaches the APK to a new
   GitHub release.