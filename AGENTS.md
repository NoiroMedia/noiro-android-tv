# Noiro Android TV

- This repository contains the Android TV app, package `tv.noiro.androidtv`, derived from NoiroTV Launcher. Keep it an ordinary app: no system HOME role, default-launcher changes or remote interception.
- Keep Where to Watch and Services settings. Do not restore launcher shelf, widgets or screensaver integration.
- Basic browsing is available without an account; playback uses a genuine Stremio session. Keep protected Noiro cloud authorization separate and retain account ownership checks.
- Build with JDK 17, Android SDK platform 36 and the pinned wrapper: `./launcher-ui/build.sh`. Development builds use debug signing; official release keys are never included.
- After building run `python3 -m unittest discover -s tests -p 'test_noiro_*.py' -v`.
- Preserve supported languages, original source headers, third-party licenses and offline Licenses & Credits.
- Keep media URLs and headers in memory only. HTTPS is required. Never put them in intents, logs, saved state or committed fixtures.
- Never commit credentials, signing material, local settings, generated builds or personal device information. APKs belong in GitHub Releases.
- Publishing a release and installing on a real device each require explicit user authorization. Never overwrite a published release asset.
- Report builds, automated checks and real-device verification separately.
