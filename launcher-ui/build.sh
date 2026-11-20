#!/usr/bin/env bash
# Public local builds use Android's debug key. Release signing is always supplied externally.
# Requires JDK 17+, Android SDK platform 36 and the pinned Gradle wrapper.
set -euo pipefail
umask 077
cd "$(dirname "$0")"

variant=debug
case "${1:-}" in
  --debug|debug) shift ;;
  --release|release) variant=release; shift ;;
  --help|-h)
    echo "Usage: ./build.sh [--debug|--release] [Gradle options]"
    echo "Default: debug APK; no private credentials are needed."
    echo "Release signing: NOIRO_KEYSTORE_PATH, NOIRO_KEYSTORE_PASSWORD, NOIRO_KEY_ALIAS, NOIRO_KEY_PASSWORD."
    exit 0 ;;
esac

if [ "$variant" = release ]; then
  missing=""
  for required in NOIRO_KEYSTORE_PATH NOIRO_KEYSTORE_PASSWORD NOIRO_KEY_ALIAS NOIRO_KEY_PASSWORD; do
    if [ -z "${!required:-}" ]; then missing="$missing $required"; fi
  done
  if [ -n "$missing" ]; then
    echo "Release signing is not configured. Set these environment variables:$missing" >&2
    echo "Use ./build.sh for a normal debug build without release credentials." >&2
    exit 1
  fi
  [ -f "$NOIRO_KEYSTORE_PATH" ] || { echo "NOIRO_KEYSTORE_PATH must point to an existing private signing key." >&2; exit 1; }
fi

sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [ -z "$sdk" ]; then
  for candidate in "$HOME/Library/Android/sdk" "$HOME/Android/Sdk" /opt/homebrew/share/android-commandlinetools \
                   /usr/local/share/android-commandlinetools /usr/lib/android-sdk; do
    if [ -d "$candidate/platforms/android-36" ]; then sdk="$candidate"; break; fi
  done
fi
[ -n "$sdk" ] && [ -f "$sdk/platforms/android-36/android.jar" ] || {
  echo 'Android SDK platform 36 not found. Set ANDROID_HOME and install "platforms;android-36".' >&2
  exit 1
}
# Convert MSYS paths before passing them to the native Windows JDK/Android tools.
sdk="$(cd "$sdk" && { pwd -W 2>/dev/null || pwd; })"
javac="${JAVA_HOME:+$JAVA_HOME/bin/}javac"
command -v "$javac" >/dev/null 2>&1 || { echo "JDK not found. Install JDK 17+ and set JAVA_HOME." >&2; exit 1; }

if [ "$variant" = debug ]; then
  ANDROID_HOME="$sdk" ./gradlew --console=plain assembleDebug "$@"
  cp build/outputs/apk/debug/NoiroAndroidTV-debug.apk Noiro-debug.apk
  echo "Built Noiro-debug.apk. Debug signing is separate from the official release key."
else
  command -v python3 >/dev/null 2>&1 || { echo "Python 3 is required for release checksums and metadata." >&2; exit 1; }
  aapt="$(python3 - "$sdk" <<'PY'
from pathlib import Path
import re
import sys
tools = Path(sys.argv[1]) / 'build-tools'
def version(path):
    return tuple(int(value) for value in re.findall(r'\d+', path.parent.name))
candidates = list(tools.glob('*/aapt')) + list(tools.glob('*/aapt.exe'))
print(str(max(candidates, key=version)) if candidates else '')
PY
)"
  [ -n "$aapt" ] || { echo "Android SDK build-tools are required for release metadata." >&2; exit 1; }
  ANDROID_HOME="$sdk" ./gradlew --console=plain assembleRelease "$@"
  cp build/outputs/apk/release/NoiroAndroidTV-release.apk Noiro.apk
  python3 ../tools/release/android-tv-package.py Noiro.apk "$aapt"
  echo "Built Noiro.apk, Noiro.apk.sha256 and android-tv-release.json. Nothing was published."
fi
