#!/usr/bin/env bash
set -euo pipefail

android_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
adb="${ADB:-${ANDROID_HOME:-$HOME/Android/Sdk}/platform-tools/adb}"
package="${AKASHIC_DEBUG_PACKAGE:-com.akashic.mobile.debug}"
timestamp="$(date -u +%Y%m%dT%H%M%SZ)"
output_dir="$android_root/build/reports/debug-diagnostics/$timestamp"

test -x "$adb"
mkdir -p "$output_dir"

# 1. 从 debuggable 私有目录导出应用自己记录的固定容量报告
for name in last-crash.txt exit-history.txt; do
    if "$adb" exec-out run-as "$package" cat "files/diagnostics/$name" >"$output_dir/$name" 2>/dev/null; then
        test -s "$output_dir/$name" || rm "$output_dir/$name"
    else
        rm -f "$output_dir/$name"
    fi
done

# 2. 保存系统侧最近日志，便于区分 Java 崩溃、ANR 与前台服务限制
"$adb" logcat -d -v threadtime >"$output_dir/logcat.txt"
"$adb" shell dumpsys activity exit-info "$package" >"$output_dir/system-exit-info.txt"

echo "$output_dir"
