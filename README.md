<h1 align="center">telepath</h1>

<p align="center">
  A calm, ad-free home screen for Google TV and Android TV, built on
  <a href="https://github.com/aradhyas8/Plume_Launcher">Plume Launcher</a>.<br>
  Your photos fill the top of the screen. Make your own version with one settings file.
</p>

<p align="center">
  <img alt="Android 8.0+" src="https://img.shields.io/badge/Android-8.0%2B-3ddc84">
  <img alt="APK size" src="https://img.shields.io/badge/APK-0.5%20MB-blue">
  <a href="LICENSE"><img alt="GPL-3.0" src="https://img.shields.io/badge/license-GPL--3.0-blue"></a>
  <a href="https://github.com/aradhyas8/Plume_Launcher"><img alt="Built on Plume" src="https://img.shields.io/badge/built%20on-Plume%20Launcher-8a63d2"></a>
</p>

<!-- Screenshots: add yours to docs/screenshots/ (see docs/screenshots/README.md) and show them here, for example
<p align="center"><img src="docs/screenshots/home-day.jpg" width="49%"> <img src="docs/screenshots/home-night.jpg" width="49%"></p> -->

telepath replaces the busy stock home screen with what you actually use: your favourite apps, what you're
watching, and a photo you like, with no ads, sponsored rows, accounts or tracking. It's a personal launcher that you
can fork, tweak and install on your own TV.

## Highlights

- **Your photos, up top.** The top half of Home is left to the wallpaper: a slideshow of your own photos, with a new
  one each time you come back to Home. The screensaver shows them too.
- **A theme that follows the sun.** Light from sunrise to sunset and dark after, worked out for where the TV is.
  Mood colours tint the photo at dawn, golden hour, sunset, dusk and night, and with clouds, fog or rain.
- **Always readable.** The clock, date and Up next turn white or dark to suit each photo. Busy photos get a small
  glass panel instead of a veil over the whole picture.
- **Continue Watching that only shows what you're watching:** what you started and the next episode, as full
  landscape pictures with titles. No suggestions.
- **App tiles in the Apple TV style,** with day and night versions made from each app's own logo. New apps get a
  tile and a group (Streaming, or Apps & utilities) automatically.
- **Widgets one press down:** your inputs with your console first, shortcuts (Home settings, TV settings, the
  Google TV home screen, All apps), the weather and a stock watchlist.
- **Up next:** the next match or race you follow, beside the clock: football and cricket teams, the Champions League
  and World Cup knockouts, and Formula 1.
- **Light:** about 0.5 MB, no third-party libraries, no background services. The Home button works even where Google
  TV's own launcher can't be replaced, and holding Home still opens the Google TV dashboard.

## Make it yours

Everything personal is in one file, [`telepath.properties`](telepath.properties): your favourite apps, the
weather location, your console's HDMI port, watchlist symbols, the teams Up next follows, and every theme colour.

1. **Fork this repository** on GitHub.
2. **Edit `telepath.properties`** (on GitHub, select the file, then the pencil). The comments explain each setting,
   and [docs/CUSTOMIZING.md](docs/CUSTOMIZING.md) has the full reference.
3. **Get your APK.** Every push runs the [Build workflow](.github/workflows/build.yml): open your fork's **Actions**
   tab, select the latest run, and download the APK under **Artifacts**. (In a fork, turn Actions on once from the
   Actions tab.) Or [build it on your computer](#build-from-source).
4. **[Install it](#install)** on your TV.

Settings you'd rather not publish (your TV's address, a city) can go in `telepath.local.properties` next to it
instead. That file is git-ignored, and its values win.

## Requirements

- Android TV or Google TV with **Android 8.0 or newer**. Developed on a Sony BRAVIA with Google TV (Android 14).
- A way to sideload an APK: the *Downloader* app, a USB drive, or `adb` from a computer on the same network.

## Install

1. **Get the APK:** from your fork's Actions artifacts, from this project's Releases page if it publishes one, or
   from your own build.
2. **Install it on the TV**, using any of these:
   - **Downloader app:** enter the APK's web address.
   - **USB drive:** copy the APK over and open it with a file manager.
   - **adb:** `adb install -r telepath-1.0.apk`, or on Windows `.\scripts\Install-Telepath.ps1`.
3. **Open telepath** from your apps and follow its setup: it makes itself the default home screen and turns on
   its Home button service. Allow the **TV listings** permission when asked; Continue Watching needs it.

> [!NOTE]
> Android shows the same generic warning for every accessibility service. telepath's service only watches for
> the Google TV home screen appearing. It doesn't see or filter remote buttons (filtering them would make a held
> Volume button move only one step), and it can't read what's on the screen.

### Home button on Android 13 and newer

Android 13 and newer grey out the Accessibility switch for apps installed outside the Play Store, and most Google TV
builds hide the **Allow restricted settings** menu that would unlock it. If the setup reaches its adb step, unlock it
once from a computer (or a phone adb app) on the same network:

1. On the TV, open **Settings → System → About** and press **Android TV OS build** seven times to turn on Developer
   options.
2. In **Settings → System → Developer options**, turn on **Network debugging** (called **USB debugging** or
   **Wireless debugging** on some TVs).
3. Run these, with the address telepath shows (`dev.aster.home` is the default `app.id`):

   ```sh
   adb connect <tv-address>:5555
   adb shell pm grant dev.aster.home android.permission.WRITE_SECURE_SETTINGS
   ```

4. Accept the debugging prompt on the TV, then choose **Try again**. telepath turns its Home button service on
   by itself.

The same permission lets telepath switch the screensaver to your photos.

### Or set it all up from a computer (Windows)

The [scripts](scripts) do the same over adb, with a preview first, and save the TV's original settings so you can undo
everything:

```powershell
.\scripts\Install-Telepath.ps1 -Tv 192.168.1.20             # install or update
.\scripts\Activate-Telepath.ps1 -Tv 192.168.1.20 -Apply     # default Home, Home button, screensaver
.\scripts\Restore-Telepath.ps1 -Tv 192.168.1.20 -Apply      # put the TV's own home screen back
```

Put `tv.address=192.168.1.20` in `telepath.local.properties` to leave out `-Tv`. Nothing is disabled or
uninstalled: Google TV's own launcher stays installed, and the voice button on the remote keeps working.

## Your photos

The wallpaper is a slideshow of the photos in telepath's own folder on the TV, and a different one shows each
time you return to Home. Until you add some, it shows the two built-in landscapes.

- **Windows:** put photos in the [`wallpapers`](wallpapers) folder (or point `wallpapers.folder` at your own), then
  run `.\scripts\Sync-Wallpapers.ps1 -Apply`. It turns each photo upright, fills the screen with it and removes
  location and camera data before copying. Portrait photos are zoomed to fill the screen; add `-Portrait Stretch` to
  stretch them out instead. Run it again whenever you add or remove photos: it mirrors the folder.
- **macOS and Linux:** `scripts/sync-wallpapers.sh --apply` does the same, copying the photos as they are (telepath
  fills the screen with them). It uses `exiftool` to remove their location and camera data first; without it,
  add `--keep-metadata` to copy them anyway.
- **Any computer with adb:** copy photos straight in (they keep their metadata):
  `adb push my-photo.jpg /sdcard/Android/data/dev.aster.home/files/slideshow/`
- **From your phone:** Home settings → Appearance → day or night wallpaper → **Add from phone…**, then scan the QR
  code. That photo becomes the wallpaper on its own, instead of the slideshow.

The same list also has the two built-in landscapes, a plain colour and the Bing picture of the day. The screensaver
can show a folder of photos on the TV or a USB drive instead (**Home settings → Screensaver**).

## Themes

On the TV, **Home settings → Appearance** has **Theme** (Auto, Light or Dark) and **Mood colours** (time of day and
weather, time of day only, or off). Every colour (text, glass, focus, and the mood tint for each part of the day) is
in the theme section of `telepath.properties`. See [Theme colours](docs/CUSTOMIZING.md#theme-colours).

## Using it

| To | Do this |
|---|---|
| Come back to telepath | Press **Home**, from anywhere |
| See the widgets | Press **Down** from the favourites |
| Home settings | Down, then **Shortcuts → Home settings** |
| TV settings | Down, then **Shortcuts → TV settings** |
| Google TV's own home screen | Down, then **Shortcuts → Google TV** (Home brings you back) |
| All apps | Down, then **Shortcuts → All apps** |
| Switch to your console | Down, then **Inputs** |
| Google TV dashboard | Hold **Home** |
| Move, remove or add a favourite | Hold **OK** on the app (in the dock or in All apps) |
| Move an app to the other group, or use its plain icon | Hold **OK** on it in All apps |
| Hide a Continue Watching card | Hold **OK** on it |
| Change the weather location or watchlist | Hold **OK** on that card |

## Privacy

No ads, analytics or accounts, and no background service. Widgets refresh only while Home is on screen. telepath
goes online only to:

- **Weather:** unless you set a city, it looks up the TV's approximate place from its internet address
  ([ipwho.is](https://ipwho.is)) at most every 6 hours. The place's coordinates go to
  [Open-Meteo](https://open-meteo.com) at most every 30 minutes.
- **Watchlist,** only with symbols set: the symbols go to Yahoo Finance at most every 5 minutes.
- **Up next:** F1 times from [Jolpica](https://github.com/jolpica/jolpica-f1); football and cricket from
  [TheSportsDB](https://www.thesportsdb.com)'s free public key, at most every 6 hours. Only team and league numbers
  are sent.
- **Continue Watching pictures,** as each app publishes them. For apps that only publish portrait posters, the wide
  version comes from the same public image hosts (Metahub, TheTVDB).
- **Bing picture of the day** and the **Gallery paintings** screensaver, only if you choose them.

Your photos stay on your computer and in telepath's own folder on the TV, which other apps can't read on Android
11 and newer; the sync scripts remove their location and camera data first. The **Add from phone** page works only on
your home network, and only while that screen is open. telepath doesn't update itself; you install each new
version.

## Troubleshooting

<details>
<summary><b>The Home button opens Google TV's home screen</b></summary>

Check **Home settings → Home button**. If the app was force-stopped, Android turns its accessibility service off;
open telepath once to turn it back on. Right after an update, the first Home press can still land on Google
TV's home for a few seconds.
</details>

<details>
<summary><b>Continue Watching misses YouTube or Netflix</b></summary>

Neither shares what you're watching with other launchers: YouTube gives its resume points only to Google TV's own
home screen, through your Google account, and Netflix shares none. telepath shows only real resume points, so
it leaves them out rather than showing suggestions. **Home settings → Continue Watching → Resume source status**
shows what each app shares.
</details>

<details>
<summary><b>The screensaver is still Google TV's</b></summary>

Google TV hides other screensavers from its settings. Grant the permission shown under
[Home button on Android 13 and newer](#home-button-on-android-13-and-newer), or run `Activate-Telepath.ps1`, then
choose it in **Home settings → Screensaver**.
</details>

<details>
<summary><b>An update won't install over the version on the TV</b></summary>

Each build is signed with a key, and Android only installs an update signed with the same key. Sign your builds with
your own key ([how](docs/CUSTOMIZING.md#sign-your-builds)), or uninstall the old version first (its settings are
lost). Giving your build its own `app.id` lets two versions sit side by side.

Android also won't install a lower `versionCode` over a higher one. adb's `-d` option gets round that only for
debuggable apps, which release builds aren't. To go back to an older version, build it with a `versionCode` at least
as high as the one on the TV (an equal one installs over it), or uninstall first.
</details>

## Build from source

You need JDK 17 or newer (21 recommended) and the Android SDK (Android Studio installs it).

```sh
git clone <your fork's address>
cd telepath
./gradlew :app:testDebugUnitTest :app:assembleRelease     # Windows: .\scripts\Build-Telepath.ps1
```

The APK is in `app/build/outputs/apk/release/`. Without a `keystore.properties` file it is signed with your
computer's debug key, which is fine for installing your own build. To keep up with Plume:
`git remote add upstream https://github.com/aradhyas8/Plume_Launcher.git`, then `git fetch upstream` and merge
`upstream/main`.

## Contributing

Ideas, fixes and translations are welcome. See [CONTRIBUTING.md](CONTRIBUTING.md). Changes that suit every Plume user
are best sent to [Plume Launcher](https://github.com/aradhyas8/Plume_Launcher) itself.

## Credits

- **[Plume Launcher](https://github.com/aradhyas8/Plume_Launcher)** by aradhyas8: the launcher telepath is built
  on (GPL-3.0). Its history is kept in this repository.
- **[Free My TV](https://github.com/simbasjungles/free-my-tv)** by simbasjungles inspired the widgets page. No code was
  copied.
- **Screensaver paintings** (public domain, scans via Wikimedia Commons, downloaded from Plume's releases): Frederic
  Edwin Church, Ivan Shishkin, Jasper Francis Cropsey and Martin Johnson Heade. Titles are in
  [`screensaver/`](screensaver).
- **Built-in wallpapers:** "Canada" by Thomas Curryer, via [Unsplash](https://unsplash.com/@tawc20) (CC0), from Plume.
- **App logos:** [Simple Icons](https://simpleicons.org) (CC0). **Disney+ Hotstar logos:** Wikimedia Commons
  ("Disney+ Hotstar logo.svg", "Disney+ Hotstar 2024.svg"; public-domain text logos), recoloured.
- **Input icons:** [Material Symbols](https://fonts.google.com/icons) by Google (Apache 2.0).
- **Data:** [Open-Meteo](https://open-meteo.com), [ipwho.is](https://ipwho.is), Yahoo Finance,
  [Jolpica](https://github.com/jolpica/jolpica-f1), [TheSportsDB](https://www.thesportsdb.com), Metahub, TheTVDB and
  Bing.

All trademarks belong to their owners, and logos are shown only to label the apps on your TV. telepath is not
affiliated with or endorsed by Google, Sony, or any streaming service.

## License

telepath is free software under the [GNU General Public License v3.0](LICENSE), like Plume Launcher.
Copyright (C) 2026 aradhyas8 (Plume Launcher) and the telepath contributors. You may use, change and share it,
but any version you distribute, changed or not, must also be under the GPL-3.0, with its full source code.