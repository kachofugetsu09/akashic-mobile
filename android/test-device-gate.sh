#!/usr/bin/env bash
set -euo pipefail

readonly script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
readonly fixture_dir="$script_dir/test-fixtures/device-gate"
readonly test_dir="$script_dir/build/tmp/device-gate-test"
readonly fake_bin="$test_dir/bin"

rm -rf "$test_dir"
mkdir -p "$fake_bin"
ln -s "$fixture_dir/adb" "$fake_bin/adb"

run_gate() {
    local mode="$1"
    local run_id="rgate${mode}"
    local adb_log="$test_dir/$mode-adb.log"
    rm -rf "$script_dir/build/reports/device-gate/$run_id"
    : >"$adb_log"

    set +e
    PATH="$fake_bin:$PATH" \
    DEVICE_GATE_FAKE_MODE="$mode" \
    DEVICE_GATE_FAKE_ADB_LOG="$adb_log" \
    DEVICE_GATE_FAKE_COLLISION_ID="com.akashic.mobile.review.${run_id}" \
        "$script_dir/device-gate.sh" \
            --serial fake-device \
            --run-id "$run_id" \
            --test 'com.akashic.mobile.data.realtime.IsolatedGatewayDeviceTest#pairSendAndReceiveFixedMedia' \
            --runner-arg historySessionId=fake-session
    gate_exit=$?
    set -e
}

run_gate collision
readonly collision_report="$script_dir/build/reports/device-gate/rgatecollision/report.txt"

[[ "$gate_exit" -ne 0 ]] || {
    printf 'device Gate accepted a colliding package\n' >&2
    exit 1
}
[[ "$(wc -l <"$test_dir/collision-adb.log")" -eq 2 ]] || {
    printf 'device Gate reached adb after collision detection:\n' >&2
    cat "$test_dir/collision-adb.log" >&2
    exit 1
}
grep -Fxq 'collision_result=blocked' "$collision_report"
grep -Fxq 'collisions=com.akashic.mobile.review.rgatecollision' "$collision_report"
grep -Fxq 'backup=unavailable_no_install_performed' "$collision_report"
! grep -Eq ' install | uninstall | clear | force-stop | instrument ' "$test_dir/collision-adb.log"

run_gate zero
readonly zero_report="$script_dir/build/reports/device-gate/rgatezero/report.txt"
[[ "$gate_exit" -ne 0 ]] || {
    printf 'device Gate accepted a zero-test instrumentation result\n' >&2
    exit 1
}
! grep -Fxq 'result=passed' "$zero_report"
grep -Eq '^test_exit=[1-9][0-9]*$' "$zero_report"
[[ "$(grep -c ' uninstall ' "$test_dir/zero-adb.log")" -eq 2 ]]

run_gate failure
readonly failure_report="$script_dir/build/reports/device-gate/rgatefailure/report.txt"
[[ "$gate_exit" -ne 0 ]] || {
    printf 'device Gate accepted a failing instrumentation result\n' >&2
    exit 1
}
! grep -Fxq 'result=passed' "$failure_report"
grep -Eq '^test_exit=[1-9][0-9]*$' "$failure_report"
[[ "$(grep -c ' uninstall ' "$test_dir/failure-adb.log")" -eq 2 ]]

run_gate success
readonly success_report="$script_dir/build/reports/device-gate/rgatesuccess/report.txt"
[[ "$gate_exit" -eq 0 ]] || {
    printf 'device Gate rejected a single passing instrumentation result\n' >&2
    exit 1
}
grep -Fxq 'result=passed' "$success_report"
grep -Fxq 'test_exit=0' "$success_report"
grep -Fxq 'cleanup_exit=0' "$success_report"
[[ "$(grep -c ' uninstall ' "$test_dir/success-adb.log")" -eq 2 ]]

printf 'device Gate collision and instrumentation oracle tests passed\n'
