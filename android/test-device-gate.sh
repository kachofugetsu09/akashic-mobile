#!/usr/bin/env bash
set -euo pipefail

readonly script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
readonly fixture_dir="$script_dir/test-fixtures/device-gate"
readonly test_dir="$script_dir/build/tmp/device-gate-test"
readonly fake_bin="$test_dir/bin"
readonly fixture_run_id="rgatefixture"
readonly fixture_application_id="com.akashic.mobile.review.${fixture_run_id}"
readonly report_file="$script_dir/build/reports/device-gate/$fixture_run_id/report.txt"

rm -rf "$test_dir"
mkdir -p "$fake_bin"
ln -s "$fixture_dir/adb" "$fake_bin/adb"

run_gate() {
    local mode="$1"
    local adb_log="$test_dir/$mode-adb.log"
    local collision_id="$fixture_application_id"
    if [[ "$mode" == "collision_test" ]]; then
        collision_id="${collision_id}.test"
    fi
    rm -rf "$script_dir/build/reports/device-gate/$fixture_run_id"
    : >"$adb_log"

    set +e
    PATH="$fake_bin:$PATH" \
    DEVICE_GATE_FAKE_MODE="$mode" \
    DEVICE_GATE_FAKE_ADB_LOG="$adb_log" \
    DEVICE_GATE_FAKE_COLLISION_ID="$collision_id" \
        "$script_dir/device-gate.sh" \
            --serial fake-device \
            --run-id "$fixture_run_id" \
            --test 'com.akashic.mobile.data.realtime.IsolatedGatewayDeviceTest#pairSendAndReceiveFixedMedia' \
            --runner-arg historySessionId=fake-session
    gate_exit=$?
    set -e
}

assert_failed_gate() {
    local result="$1"
    grep -Fxq "candidate_application_id=$fixture_application_id" "$report_file"
    grep -Eq '^app_apk_sha256=[0-9a-f]{64}$' "$report_file"
    ! grep -Fxq 'gate_result=passed' "$report_file"
    grep -Fxq "gate_result=$result" "$report_file"
    grep -Eq '^test_exit=[1-9][0-9]*$|^cleanup_exit=[1-9][0-9]*$' "$report_file"
}

run_gate collision_app

[[ "$gate_exit" -ne 0 ]] || {
    printf 'device Gate accepted a colliding app package\n' >&2
    exit 1
}
[[ "$(wc -l <"$test_dir/collision_app-adb.log")" -eq 2 ]] || {
    printf 'device Gate reached adb after collision detection:\n' >&2
    cat "$test_dir/collision_app-adb.log" >&2
    exit 1
}
grep -Fxq 'collision_result=blocked' "$report_file"
grep -Fxq "collisions=$fixture_application_id" "$report_file"
grep -Fxq 'backup=unavailable_no_install_performed' "$report_file"
assert_failed_gate failed_setup
! grep -Eq ' install | uninstall | clear | force-stop | instrument ' "$test_dir/collision_app-adb.log"

run_gate collision_test
[[ "$gate_exit" -ne 0 ]] || {
    printf 'device Gate accepted a colliding test package\n' >&2
    exit 1
}
grep -Fxq "collisions=$fixture_application_id.test" "$report_file"
assert_failed_gate failed_setup
! grep -Eq ' install | uninstall | clear | force-stop | instrument ' "$test_dir/collision_test-adb.log"

run_gate app_install_failure
[[ "$gate_exit" -ne 0 ]]
assert_failed_gate failed_setup
[[ "$(grep -c ' uninstall ' "$test_dir/app_install_failure-adb.log" || true)" -eq 0 ]]

run_gate test_install_failure
[[ "$gate_exit" -ne 0 ]]
assert_failed_gate failed_setup
[[ "$(grep -c ' uninstall ' "$test_dir/test_install_failure-adb.log")" -eq 1 ]]
grep -Fq " uninstall $fixture_application_id" \
    "$test_dir/test_install_failure-adb.log"

run_gate zero
[[ "$gate_exit" -ne 0 ]] || {
    printf 'device Gate accepted a zero-test instrumentation result\n' >&2
    exit 1
}
assert_failed_gate failed_test
[[ "$(grep -c ' uninstall ' "$test_dir/zero-adb.log")" -eq 2 ]]

run_gate failure
[[ "$gate_exit" -ne 0 ]] || {
    printf 'device Gate accepted a failing instrumentation result\n' >&2
    exit 1
}
assert_failed_gate failed_test
[[ "$(grep -c ' uninstall ' "$test_dir/failure-adb.log")" -eq 2 ]]

run_gate crash
[[ "$gate_exit" -ne 0 ]] || {
    printf 'device Gate accepted a crashed instrumentation process\n' >&2
    exit 1
}
assert_failed_gate failed_test
[[ "$(grep -c ' uninstall ' "$test_dir/crash-adb.log")" -eq 2 ]]

run_gate success
[[ "$gate_exit" -eq 0 ]] || {
    printf 'device Gate rejected a single passing instrumentation result\n' >&2
    exit 1
}
grep -Fxq "candidate_application_id=$fixture_application_id" "$report_file"
grep -Eq '^app_apk_sha256=[0-9a-f]{64}$' "$report_file"
grep -Fxq 'test_result=passed' "$report_file"
grep -Fxq 'gate_result=passed' "$report_file"
grep -Fxq 'test_exit=0' "$report_file"
grep -Fxq 'cleanup_exit=0' "$report_file"
[[ "$(grep -c ' uninstall ' "$test_dir/success-adb.log")" -eq 2 ]]

run_gate cleanup_failure
[[ "$gate_exit" -ne 0 ]] || {
    printf 'device Gate accepted a cleanup failure\n' >&2
    exit 1
}
grep -Fxq 'test_result=passed' "$report_file"
grep -Fxq 'gate_result=failed_cleanup' "$report_file"
grep -Fxq "residual_packages=$fixture_application_id.test,$fixture_application_id" \
    "$report_file"

readonly unsafe_adb_log="$test_dir/unsafe-adb.log"
: >"$unsafe_adb_log"
set +e
PATH="$fake_bin:$PATH" \
DEVICE_GATE_FAKE_MODE=success \
DEVICE_GATE_FAKE_ADB_LOG="$unsafe_adb_log" \
    "$script_dir/device-gate.sh" \
        --serial fake-device \
        --run-id rgateunsafe \
        --test 'com.akashic.mobile.data.realtime.IsolatedGatewayDeviceTest#pairSendAndReceiveFixedMedia' \
        --runner-arg 'historySessionId=value with spaces'
unsafe_exit=$?
set -e
[[ "$unsafe_exit" -ne 0 ]]
[[ ! -s "$unsafe_adb_log" ]]

printf 'device Gate isolation, ownership, cleanup, and instrumentation oracle tests passed\n'
