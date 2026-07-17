#!/usr/bin/env bash
set -euo pipefail

readonly RELEASE_APPLICATION_ID="com.akashic.mobile"
readonly REVIEW_APPLICATION_ID_PREFIX="${RELEASE_APPLICATION_ID}.review."

usage() {
    cat <<'EOF'
Usage: ./device-gate.sh --serial SERIAL --test CLASS#METHOD [options]

Required:
  --serial SERIAL           Exact adb device serial.
  --test CLASS#METHOD       Instrumentation phase; repeat to test a restart flow.

Options:
  --runner-arg KEY=VALUE    Instrumentation argument; repeat as needed.
  --run-id ID               Package-safe run ID; defaults to a unique local value.
EOF
}

die() {
    printf 'device-gate: %s\n' "$*" >&2
    exit 1
}

require_value() {
    local option="$1"
    local value="${2:-}"
    [[ -n "$value" ]] || die "$option requires a value"
}

resolve_aapt() {
    if command -v aapt >/dev/null 2>&1; then
        command -v aapt
        return
    fi

    local sdk_root="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
    [[ -n "$sdk_root" ]] || die "ANDROID_HOME or ANDROID_SDK_ROOT is required when aapt is not on PATH"
    local aapt_path
    aapt_path="$(find "$sdk_root/build-tools" -mindepth 2 -maxdepth 2 -type f -name aapt -print | sort -V | tail -n 1)"
    [[ -n "$aapt_path" ]] || die "aapt was not found under $sdk_root/build-tools"
    printf '%s\n' "$aapt_path"
}

read_package_id() {
    local tool_path="$1"
    local apk_path="$2"
    "$tool_path" dump badging "$apk_path" |
        sed -n "s/^package: name='\([^']*\)'.*/\1/p" |
        head -n 1
}

read_target_package_id() {
    local tool_path="$1"
    local apk_path="$2"
    "$tool_path" dump xmltree "$apk_path" AndroidManifest.xml |
        sed -n 's/.*android:targetPackage[^=]*="\([^"]*\)".*/\1/p' |
        head -n 1
}

serial=""
run_id=""
tests=()
runner_args=()

while (($# > 0)); do
    case "$1" in
        --serial)
            require_value "$1" "${2:-}"
            serial="$2"
            shift 2
            ;;
        --test)
            require_value "$1" "${2:-}"
            tests+=("$2")
            shift 2
            ;;
        --runner-arg)
            require_value "$1" "${2:-}"
            runner_args+=("$2")
            shift 2
            ;;
        --run-id)
            require_value "$1" "${2:-}"
            run_id="$2"
            shift 2
            ;;
        --help|-h)
            usage
            exit 0
            ;;
        *)
            die "unknown argument: $1"
            ;;
    esac
done

[[ -n "$serial" ]] || die "--serial is required"
((${#tests[@]} > 0)) || die "at least one --test is required"
command -v adb >/dev/null 2>&1 || die "adb is required"

if [[ -z "$run_id" ]]; then
    run_id="r$(date -u +%Y%m%d%H%M%S)_$$"
fi
[[ "$run_id" =~ ^[a-z][a-z0-9_]{0,47}$ ]] ||
    die "run ID must match ^[a-z][a-z0-9_]{0,47}$"
for test_name in "${tests[@]}"; do
    [[ "$test_name" =~ ^[A-Za-z_][A-Za-z0-9_.]*#[A-Za-z_][A-Za-z0-9_]*$ ]] ||
        die "test must be a fully qualified CLASS#METHOD: $test_name"
done
for runner_arg in "${runner_args[@]}"; do
    [[ "$runner_arg" == *=* && "$runner_arg" != =* ]] ||
        die "runner arg must be KEY=VALUE"
    [[ "${runner_arg%%=*}" =~ ^[A-Za-z_][A-Za-z0-9_]*$ ]] ||
        die "runner arg key is invalid: ${runner_arg%%=*}"
done

readonly script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
readonly suffix=".review.${run_id}"
readonly expected_application_id="${REVIEW_APPLICATION_ID_PREFIX}${run_id}"
readonly expected_test_application_id="${expected_application_id}.test"
readonly report_dir="$script_dir/build/reports/device-gate/$run_id"
readonly report_file="$report_dir/report.txt"
readonly inventory_file="$report_dir/package-inventory.txt"
mkdir -p "$report_dir"

exec > >(tee "$report_dir/output.log") 2>&1

printf 'run_id=%s\nserial=%s\nsource_commit=%s\n' \
    "$run_id" "$serial" "$(git -C "$script_dir/.." rev-parse HEAD)" >"$report_file"
printf 'requested_tests=%s\nrunner_arg_keys=%s\n' \
    "$(IFS=,; printf '%s' "${tests[*]}")" \
    "$(printf '%s\n' "${runner_args[@]}" | sed 's/=.*//' | paste -sd, -)" >>"$report_file"

# 1. Build the run-specific candidate without touching a device.
cd "$script_dir"
./gradlew \
    "-PakashicDebugApplicationIdSuffix=$suffix" \
    assembleDebug assembleDebugAndroidTest

readonly app_apk="$script_dir/app/build/outputs/apk/debug/app-debug.apk"
readonly test_apk="$script_dir/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
[[ -f "$app_apk" ]] || die "app APK was not produced: $app_apk"
[[ -f "$test_apk" ]] || die "test APK was not produced: $test_apk"
readonly aapt_path="$(resolve_aapt)"
readonly application_id="$(read_package_id "$aapt_path" "$app_apk")"
readonly test_application_id="$(read_package_id "$aapt_path" "$test_apk")"
readonly target_application_id="$(read_target_package_id "$aapt_path" "$test_apk")"

[[ "$application_id" == "$expected_application_id" ]] ||
    die "candidate application ID mismatch: expected=$expected_application_id actual=$application_id"
[[ "$test_application_id" == "$expected_test_application_id" ]] ||
    die "test application ID mismatch: expected=$expected_test_application_id actual=$test_application_id"
[[ "$target_application_id" == "$expected_application_id" ]] ||
    die "test target package mismatch: expected=$expected_application_id actual=$target_application_id"
[[ "$application_id" == "$REVIEW_APPLICATION_ID_PREFIX"* ]] ||
    die "candidate application ID escaped the review namespace"

printf 'candidate_application_id=%s\ncandidate_test_application_id=%s\n' \
    "$application_id" "$test_application_id" >>"$report_file"
printf 'app_apk_sha256=%s\ntest_apk_sha256=%s\n' \
    "$(sha256sum "$app_apk" | cut -d' ' -f1)" \
    "$(sha256sum "$test_apk" | cut -d' ' -f1)" >>"$report_file"

# 2. Prove the exact device and both candidate IDs are unused before installation.
[[ "$(adb -s "$serial" get-state)" == "device" ]] || die "adb serial is not ready: $serial"
adb -s "$serial" shell pm list packages -u | tr -d '\r' | sort >"$inventory_file"
collisions=()
for package_id in "$application_id" "$test_application_id"; do
    if grep -Fxq "package:$package_id" "$inventory_file"; then
        collisions+=("$package_id")
    fi
done
if ((${#collisions[@]} > 0)); then
    printf 'collision_result=blocked\ncollisions=%s\nbackup=unavailable_no_install_performed\n' \
        "$(IFS=,; printf '%s' "${collisions[*]}")" >>"$report_file"
    die "candidate package already exists; refusing install, clear, or uninstall: ${collisions[*]}"
fi
printf 'collision_result=clear\nbackup=not_needed_unique_run_packages\n' >>"$report_file"

# 3. From this point cleanup owns only the two proven-new run packages.
cleanup_enabled=true
cleanup() {
    local test_status=$?
    trap - EXIT
    set +e
    local cleanup_status=0
    if [[ "$cleanup_enabled" == true ]]; then
        adb -s "$serial" uninstall "$test_application_id" >/dev/null 2>&1 || cleanup_status=$?
        adb -s "$serial" uninstall "$application_id" >/dev/null 2>&1 || cleanup_status=$?
    fi
    printf 'test_exit=%s\ncleanup_exit=%s\n' "$test_status" "$cleanup_status" >>"$report_file"
    if ((test_status != 0)); then
        exit "$test_status"
    fi
    exit "$cleanup_status"
}
trap cleanup EXIT

adb -s "$serial" install -r -t "$app_apk"
adb -s "$serial" install -r -t "$test_apk"

# 4. Run each method as a separate phase and kill the app process between phases.
readonly runner="androidx.test.runner.AndroidJUnitRunner"
for index in "${!tests[@]}"; do
    instrumentation_args=()
    for runner_arg in "${runner_args[@]}"; do
        instrumentation_args+=("-e" "${runner_arg%%=*}" "${runner_arg#*=}")
    done
    instrumentation_args+=("-e" "class" "${tests[$index]}")
    adb -s "$serial" shell am instrument -w -r \
        "${instrumentation_args[@]}" \
        "$test_application_id/$runner" |
        tee "$report_dir/phase-$((index + 1)).txt"
    if ((index + 1 < ${#tests[@]})); then
        adb -s "$serial" shell am force-stop "$application_id"
    fi
done

printf 'result=passed\n' >>"$report_file"
