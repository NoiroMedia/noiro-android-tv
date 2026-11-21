# Changelog

## 1.8.41 — Supporter recognition

- Automatically verify an existing Noiro membership after Stremio sign-in and on restored-session startup, without blocking free app use.
- Recognize server-confirmed Supporter access independently of an optional TMDB metadata key.
- Distinguish checking, unlinked accounts and temporary verification failures from confirmed free access.
- Keep account changes, lease expiry, server-side revocation and the original launcher's access rules enforced.

## 1.8.40 — first public Android TV source release

- Noiro opens public movie/series catalogs and search without login.
- Free Stremio sign-in supplies the user's own add-ons for compatible playback.
- Local My list and Continue Watching preserve account boundaries and viewing recency, and resolve eligible aired next episodes.
- Optional Noiro cloud setup explains membership, existing customer access, account linking and TMDB setup with phone QR links.
- Default audio language is a Premium convenience in the official app. Manual audio selection remains free; saved defaults are retained when access is unavailable.
- Normal app identity, approved Noiro icon/TV banner, app settings, supported languages and offline third-party notices.
- This is an early release; source formats, metadata and connected services determine availability. Automatic updates for this separate app are not yet configured.

The app is derived from NoiroTV Launcher 1.8.36. This public repository contains the current app source and portable build tooling, without the original private history, backend, keys, device information or user data.
