#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
android_root="$repo_root/clients/android"

# 1. 校验固定 WebUI 产物，再串行验证 Room 迁移、通知桥和本地可靠性投影
cd "$android_root"
flock -w 300 /tmp/akashic-gradle.lock \
  env ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}" \
  ./gradlew --no-daemon --max-workers=1 \
    :app:verifyMobileWebArchive \
    testDebugUnitTest \
    assembleDebugAndroidTest

echo "reliability gate passed: pinned WebUI, Room schema, notification bridge"
