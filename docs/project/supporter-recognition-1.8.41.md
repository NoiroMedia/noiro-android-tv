# Noiro 1.8.41 Supporter recognition validation

Build validation completed before publication. The user approved publication of the source and APK. Physical-device installation and testing remain separate.

## Problem and resulting behavior

In 1.8.40 the standalone app saved a genuine Stremio login but skipped membership verification, then called every not-ready account Free. Its initial background checks also required a TMDB key. That prevented an existing paid member from being recognized automatically, and coupled unrelated Premium access to metadata setup.

The candidate discovers membership through the real session and launcher-access endpoints after login and on restored-session startup. Only a current, server-issued lease bound to the exact Stremio account grants paid access. TMDB credentials are retrieved separately and optionally. Unchecked, checking, linked free, unlinked and unavailable results have distinct wording. Automatic checks are deduplicated and bounded by retry delays; explicit checks also refresh newly configured metadata.

Account changes and concurrent revocation invalidate delayed results. Existing free browsing and genuine Stremio playback remain available. The original launcher's stricter entry behavior is preserved. Keyless Main profiles retain public catalogs when no custom Studio Home is present; custom and secondary profiles remain isolated. Foreground screens observe membership changes without requiring an app restart.

## Validation

- Normal debug build: passed.
- Public regression suite: 40 tests passed, including actual account-code JVM fixtures for lease expiry, malformed grants, account switches, stale requests, optional TMDB, free access and retry scheduling.
- Separate backend fixture audit: 31 focused tests passed; current backend source supports membership independently of TMDB. No backend change was needed.
- Signed release build and release lint: passed.
- APK identity: Noiro, tv.noiro.androidtv, 1.8.41 / build 64; non-debuggable.
- Signing certificate matches the previously published 1.8.40 APK.
- APK size: 12,836,547 bytes.
- APK SHA-256: 3adcb29fdd236181ad8d972cdf3b2492d3a24fd9688ef51d67efaa81bc5a8a45

No authenticated test of the reporting customer's actual Noiro-to-Stremio account link was performed. Validation did not interact with Sony TV or change accounts, billing, the website or social posts. Source and APK publication are authorized separately from this validation report.

## Additional catalog fixes before publication

The same release includes the subsequently reported first-card hero issue and trailer click lifecycle handling. Row activation explicitly previews the selected card even when Android does not emit a new focus event. Catalog refresh renews pending hero metadata rather than discarding the stationary first card response. Trailer request ownership is independent of background catalog generations and remains bound to the page, foreground lifecycle and exact account. Cancellation, loading feedback and safe error text are covered by regression tests.

A separate server patch is being prepared for Plex HLS tickets that incorrectly check Kodi selections instead of the enabled Launcher Home catalog. This validation report does not claim that server patch has been deployed.
