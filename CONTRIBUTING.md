# Contributing to telepath

Thanks for wanting to help. telepath is a personal launcher shared so that others can make their own version,
so the most useful contributions make it easier to customize, or make it work on more TVs.

## What telepath is

A calm home screen for Google TV and Android TV, built on [Plume Launcher](https://github.com/aradhyas8/Plume_Launcher).
Changes are weighed against the same principles as Plume's:

- **Small and light.** About 0.5 MB, no third-party libraries, no services running while Home isn't on screen.
- **Calm.** The top of the screen belongs to the wallpaper. Nothing competes for attention.
- **Yours.** No ads, no recommended content, no analytics, no account.
- **Quiet on the network.** telepath only goes online for what [Privacy](README.md#privacy) lists. Anything new
  that connects to the internet must say so there, and should be off unless the user sets it up.
- **Configurable, not hard-coded.** A personal choice (an app, a place, a team, a colour) belongs in
  `telepath.properties`, with a sensible default for everyone else.

Changes that suit every Plume user, such as a fix in code telepath shares with Plume, are best sent to
[Plume Launcher](https://github.com/aradhyas8/Plume_Launcher) itself, and come here with the next merge.

## Out of scope

- Recommendation, trending or "discover" rows, and anything else that fills the screen with content you didn't ask for
- Ads, analytics, crash reporting services or accounts
- New third-party libraries or SDKs (AndroidX included)
- Services, workers or alarms that run while telepath isn't on screen
- Online services that need an API key

Not sure an idea fits? Open a feature request first and ask.

## Pull requests

- **One change per pull request**, with a small diff in the style of the code around it.
- **Run the unit tests:** `./gradlew :app:testDebugUnitTest`, and add tests for logic you add.
- **Test on a TV** (or an Android TV emulator) and say which device and Android version you used.
- **Add a new setting to `telepath.properties`** with a comment, check it in `app/build.gradle.kts`, and describe it
  in [docs/CUSTOMIZING.md](docs/CUSTOMIZING.md).
- **Put user-visible changes under `## Unreleased`** in [CHANGELOG.md](CHANGELOG.md), in the style written there.
- **Keep personal data out:** no TV addresses, names, accounts, photos, or what you watch, in code, tests or
  screenshots.

By sending a pull request you agree that your contribution is licensed under the [GPL-3.0](LICENSE).

## Translations

Each language lives in `app/src/main/res/values-<code>/strings.xml`. Translate the strings in
`app/src/main/res/values/strings.xml`, keep placeholders such as `%1$s` as they are, and keep text short: TV screens
are read from across the room. Plume's own strings are best translated in Plume.

## Reporting bugs

Use the bug report form. The TV model and Android version matter most, because many problems only happen on one
manufacturer's TVs. A photo of the screen helps a lot.