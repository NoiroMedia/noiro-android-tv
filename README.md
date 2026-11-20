# Noiro for Android TV

<img src="launcher-ui/app/res/drawable-nodpi/icon.png" alt="Noiro" width="112" />

An open-source media app for Android TV, built for the remote and the big screen.

**Early release: 1.8.40.** Browse movies and series, connect your own Stremio add-ons and watch with the built-in player. Noiro does not provide or host movies, TV shows or streams. Use sources you are authorized to access.

[Download the APK](https://github.com/NoiroMedia/noiro-android-tv/releases/latest) · [Report a problem](https://github.com/NoiroMedia/noiro-android-tv/issues) · [Noiro.tv](https://noiro.tv)

## What works

- Movie and series catalogs, search, artwork and episode details without a login or private metadata key.
- Free Stremio sign-in when choosing sources from your own installed add-ons.
- Built-in Media3 playback for compatible sources, audio-track selection and ordinary subtitles.
- Local My list and Continue Watching, including eligible aired next episodes.
- Landscape/poster layouts, Services, Where to Watch settings and supported interface languages.

Some source formats require an external compatible app. Availability and playback depend on your own sources. The public no-key metadata path does not provide TMDB regional availability; an optional TMDB connection enables the existing provider details.

## Optional Noiro cloud

The basic app is free. Existing Noiro Supporter access can connect website Home configurations and profiles, eligible Plex rows, cloud progress/player settings and supported AI subtitle translation. The saved default audio language is a Premium convenience in the official app; manual audio-track selection remains free. Membership, linked services and service availability determine cloud access.

Open **Settings → Stremio & Noiro → Noiro cloud** for the setup guide and phone QR links. Existing Supporter/Lifetime customers should use their existing account. Current plans and account setup are on [Noiro.tv](https://noiro.tv/?page=Supporters).

The app source is open. A fork can change local features, but access to hosted Noiro services is independently authorized by those services. No cloud backend, signing keys, account credentials or user data are included here.

## Install

1. Download the official APK from [Releases](https://github.com/NoiroMedia/noiro-android-tv/releases/latest).
2. Transfer it to an Android TV device and install it with your usual APK installer.
3. Open **Noiro**. Browse immediately; connect Stremio when you want to choose playback sources.

Requires Android 9 or later. Package: `tv.noiro.androidtv`. It is a normal app and does not replace your TV's Home screen. It uses separate storage from the original NoiroTV Launcher.

## Build from source

Requirements: JDK 17, Python 3 and an Android SDK with platform 36/build-tools 36. Set `JAVA_HOME` and `ANDROID_HOME` for your machine.

```sh
git clone https://github.com/NoiroMedia/noiro-android-tv.git
cd noiro-android-tv
./launcher-ui/build.sh
python3 -m unittest discover -s tests -p 'test_noiro_*.py' -v
```

The default is a normal debug build, with no private signing key or Noiro membership required to build. The output is `launcher-ui/Noiro-debug.apk`. You can also use Android Studio or `./gradlew assembleDebug` inside `launcher-ui/`.

Development APKs use a different signing certificate from official releases and cannot update an official installation. Use an emulator or a development device. Building the client does not grant a paid Noiro cloud entitlement.

The wrapper, dependencies and Android plugin are pinned. See [development and release instructions](docs/DEVELOPMENT.md) for signing your own release. APKs are distributed as release assets rather than committed to the source tree.

## Contribute

For bugs, include the Noiro version, device/Android version, steps to reproduce and whether the issue affects browsing, sources or playback. Remove account identifiers, stream links, request headers and private screenshots from reports. Discuss larger changes in an issue before starting a pull request.

## Credits and license

Noiro contributions use the [MIT License](LICENSE). The app is derived from CleanYourTV/Free My TV and NoiroTV Launcher; original source headers and attribution remain. Third-party code, artwork and fonts retain their own licenses. Read [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md), [the complete notice](launcher-ui/NOTICE) and the bundled license texts. The same notices are available offline in **Settings → Licenses & Credits**.
