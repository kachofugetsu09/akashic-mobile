#!/usr/bin/env bash
set -euo pipefail

readonly script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
readonly run_id="rgatecollision"
readonly collision_id="com.akashic.mobile.review.${run_id}"
readonly fixture_dir="$script_dir/test-fixtures/device-gate"
readonly test_dir="$script_dir/build/tmp/device-gate-test"
readonly fake_bin="$test_dir/bin"
readonly adb_log="$test_dir/adb.log"
readonly report_file="$script_dir/build/reports/device-gate/$run_id/report.txt"

rm -rf "$test_dir" "$script_dir/build/reports/device-gate/$run_id"
mkdir -p "$fake_bin"
ln -s "$fixture_dir/adb" "$fake_bin/adb"
: >"$adb_log"

set +e
PATH="$fake_bin:$PATH" \
DEVICE_GATE_FAKE_ADB_LOG="$adb_log" \
DEVICE_GATE_FAKE_COLLISION_ID="$collision_id" \
    "$script_dir/device-gate.sh" \
        --serial fake-device \
        --run-id "$run_id" \
        --test 'com.akashic.mobile.data.realtime.IsolatedGatewayDeviceTest#pairSendAndReceiveFixedMedia' \
        --runner-arg historySessionId=fake-session
gate_exit=$?
set -e

[[ "$gate_exit" -ne 0 ]] || {
    printf 'device Gate accepted a colliding package\n' >&2
    exit 1
}
[[ "$(wc -l <"$adb_log")" -eq 2 ]] || {
    printf 'device Gate reached adb after collision detection:\n' >&2
    cat "$adb_log" >&2
    exit 1
}
grep -Fxq 'collision_result=blocked' "$report_file"
grep -Fxq "collisions=$collision_id" "$report_file"
grep -Fxq 'backup=unavailable_no_install_performed' "$report_file"
! grep -Eq ' install | uninstall | clear | force-stop | instrument ' "$adb_log"

printf 'device Gate collision test passed\n'
