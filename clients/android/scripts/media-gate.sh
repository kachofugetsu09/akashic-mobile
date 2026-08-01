#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
export ANDROID_HOME

cd "$ROOT"
rg -q 'android.permission.ACCESS_NETWORK_STATE' clients/android/app/src/main/AndroidManifest.xml
APKSIGNER="$(find "$ANDROID_HOME/build-tools" -mindepth 2 -maxdepth 2 -type f -name apksigner -print | sort -V | tail -n 1)"
test -x "$APKSIGNER"

flock /tmp/akashic-gradle.lock clients/android/gradlew \
  -p clients/android \
  --no-daemon \
  --max-workers=1 \
  :app:verifyMobileWebArchive \
  :app:testDebugUnitTest \
  :app:compileDebugAndroidTestKotlin \
  :app:assembleDebug

"$APKSIGNER" verify \
  clients/android/app/build/outputs/apk/debug/app-debug.apk
