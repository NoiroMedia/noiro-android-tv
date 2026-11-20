# Development

Use JDK 17 and an Android SDK with platform 36 and build-tools 36. The project uses the pinned Gradle wrapper, Android plugin and dependency lockfile. No API keys are required to build or browse the basic app.

Run `./launcher-ui/build.sh` from the repository root for a normal debug APK. Direct Gradle builds also generate their required `Config.java` from safe defaults or ignored local configuration. Standard debug builds run the normal app; no private media fixture or account bypass is enabled.

After building, run:

```sh
python3 -m unittest discover -s tests -p 'test_noiro_*.py' -v
```

These checks exercise real preference/policy/metadata code with JVM fixtures. They do not prove a particular source plays on a physical TV. CI builds a debug APK and runs the public checks without production credentials.

## Signing your own release

Official Noiro signing material is private. Use your own keystore for your own distribution:

```sh
export NOIRO_KEYSTORE_PATH=/absolute/path/to/your-signing-key.jks
export NOIRO_KEYSTORE_PASSWORD=your-store-password
export NOIRO_KEY_ALIAS=your-key-alias
export NOIRO_KEY_PASSWORD=your-key-password
./launcher-ui/build.sh --release
```

Keep these values in your own environment, not in source, logs or shell history. The release entry point checks the required values and never generates a replacement official signing key. A build signed with your own key cannot update an official Noiro installation.

## Boundaries

- Installed identity: `tv.noiro.androidtv`; implementation namespace: `tv.noiro.launcher`.
- Do not change a TV's default Home during app tests.
- Account/session data is private. Test with synthetic identities and authorized media.
- Media URLs and headers remain in memory and must not enter intents, persisted state or reports.
- A local build does not supply Noiro membership or backend credentials.
- Published official APKs are release assets. Verify their SHA-256 checksums before installation.
