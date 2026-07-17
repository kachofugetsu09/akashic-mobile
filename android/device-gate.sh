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

require_unchanged_source() {
    local current_status
    local current_commit
    local current_tree

    current_status="$(git -C "$repository_root" status --porcelain)"
    [[ -z "$current_status" ]] ||
        die "source worktree changed during candidate build"
    current_commit="$(git -C "$repository_root" rev-parse HEAD)"
    [[ "$current_commit" == "$source_commit" ]] ||
        die "source HEAD changed during candidate build: expected=$source_commit actual=$current_commit"
    current_tree="$(git -C "$repository_root" rev-parse 'HEAD^{tree}')"
    [[ "$current_tree" == "$source_tree" ]] ||
        die "source tree changed during candidate build: expected=$source_tree actual=$current_tree"
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

require_passing_phase() {
    local phase_file="$1"
    local test_name="$2"
    local method_name="${test_name##*#}"
    local phase_text
    phase_text="$(tr -d '\r' <"$phase_file")"

    if grep -Eq 'FAILURES!!!|INSTRUMENTATION_(FAILED|ABORTED)|shortMsg=|Process crashed' <<<"$phase_text"; then
        die "instrumentation reported a failure: $test_name"
    fi
    grep -Eq '^INSTRUMENTATION_STATUS: numtests=' <<<"$phase_text" ||
        die "instrumentation did not declare a test count: $test_name"
    if grep -E '^INSTRUMENTATION_STATUS: numtests=' <<<"$phase_text" |
        grep -Fvx 'INSTRUMENTATION_STATUS: numtests=1' |
        grep -q .; then
        die "instrumentation declared a test count other than one: $test_name"
    fi
    [[ "$(grep -Fxc "INSTRUMENTATION_STATUS: test=$method_name" <<<"$phase_text" || true)" -ge 1 ]] ||
        die "instrumentation did not report the requested method: $test_name"
    [[ "$(grep -Fxc 'INSTRUMENTATION_STATUS_CODE: 1' <<<"$phase_text" || true)" == "1" ]] ||
        die "instrumentation did not start exactly one test: $test_name"
    [[ "$(grep -Fxc 'INSTRUMENTATION_STATUS_CODE: 0' <<<"$phase_text" || true)" == "1" ]] ||
        die "instrumentation did not complete exactly one test: $test_name"
    ! grep -Eq '^INSTRUMENTATION_STATUS_CODE: -[0-9]+$' <<<"$phase_text" ||
        die "instrumentation returned a negative test status: $test_name"
    [[ "$(grep -Ec '^OK \(1 test\)$' <<<"$phase_text" || true)" == "1" ]] ||
        die "instrumentation must execute exactly one test: $test_name"
    [[ "$(grep -Ec '^INSTRUMENTATION_CODE: -1$' <<<"$phase_text" || true)" == "1" ]] ||
        die "instrumentation did not finish successfully: $test_name"
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
command -v flock >/dev/null 2>&1 || die "flock is required"
[[ "$serial" =~ ^[A-Za-z0-9._:-]{1,128}$ ]] ||
    die "serial contains unsupported characters"

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
    [[ "${runner_arg#*=}" =~ ^[A-Za-z0-9._~:/+=@-]{1,16384}$ ]] ||
        die "runner arg value contains unsupported characters: ${runner_arg%%=*}"
done

readonly script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
readonly repository_root="$(git -C "$script_dir" rev-parse --show-toplevel)"
[[ -z "$(git -C "$repository_root" status --porcelain)" ]] ||
    die "source worktree must be clean before a physical-device Gate"
readonly source_commit="$(git -C "$repository_root" rev-parse HEAD)"
readonly source_tree="$(git -C "$repository_root" rev-parse 'HEAD^{tree}')"
mkdir -p "$script_dir/build"
exec {gate_lock_fd}>"$script_dir/build/device-gate.lock"
flock -n "$gate_lock_fd" || die "another device Gate is using this Android worktree"
readonly suffix=".review.${run_id}"
readonly expected_application_id="${REVIEW_APPLICATION_ID_PREFIX}${run_id}"
readonly expected_test_application_id="${expected_application_id}.test"
readonly report_dir="$script_dir/build/reports/device-gate/$run_id"
readonly report_file="$report_dir/report.txt"
readonly inventory_file="$report_dir/package-inventory.txt"
mkdir -p "$report_dir"

exec > >(tee "$report_dir/output.log") 2>&1

printf 'run_id=%s\nserial=%s\nsource_commit=%s\nsource_tree=%s\n' \
    "$run_id" \
    "$serial" \
    "$source_commit" \
    "$source_tree" >"$report_file"
printf 'requested_tests=%s\nrunner_arg_keys=%s\n' \
    "$(IFS=,; printf '%s' "${tests[*]}")" \
    "$(printf '%s\n' "${runner_args[@]}" | sed 's/=.*//' | paste -sd, -)" >>"$report_file"

owned_application=false
owned_test_application=false
gate_stage="setup"

cleanup() {
    local test_status=$?
    trap - EXIT
    set +e

    # 1. 只清理由本次进程成功安装的 package。
    local cleanup_status=0
    local residual_packages=()
    if [[ "$owned_test_application" == true ]]; then
        if adb -s "$serial" uninstall "$expected_test_application_id" >/dev/null 2>&1; then
            owned_test_application=false
        else
            cleanup_status=1
            residual_packages+=("$expected_test_application_id")
        fi
    fi
    if [[ "$owned_application" == true ]]; then
        if adb -s "$serial" uninstall "$expected_application_id" >/dev/null 2>&1; then
            owned_application=false
        else
            cleanup_status=1
            residual_packages+=("$expected_application_id")
        fi
    fi

    # 2. 清理完成后才写唯一的 Gate 终态。
    local gate_result
    if ((cleanup_status != 0)); then
        gate_result="failed_cleanup"
    elif ((test_status != 0)); then
        if [[ "$gate_stage" == "test" ]]; then
            gate_result="failed_test"
        else
            gate_result="failed_setup"
        fi
    else
        gate_result="passed"
    fi
    printf 'test_exit=%s\ncleanup_exit=%s\ngate_result=%s\n' \
        "$test_status" "$cleanup_status" "$gate_result" >>"$report_file"
    if ((${#residual_packages[@]} > 0)); then
        printf 'residual_packages=%s\n' \
            "$(IFS=,; printf '%s' "${residual_packages[*]}")" >>"$report_file"
    fi

    if ((test_status != 0)); then
        exit "$test_status"
    fi
    exit "$cleanup_status"
}
trap cleanup EXIT

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

# 2. 构建结束后再次绑定源码；漂移时禁止接触任何设备状态。
require_unchanged_source
printf 'source_state_after_build=verified\n' >>"$report_file"

# 3. Prove the exact device and both candidate IDs are unused before installation.
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

# 4. 不允许替换已有 package；每次安装成功后才取得对应清理所有权。
adb -s "$serial" install -t "$app_apk"
owned_application=true
adb -s "$serial" install -t "$test_apk"
owned_test_application=true

# 5. Run each method as a separate phase and kill the app process between phases.
readonly runner="androidx.test.runner.AndroidJUnitRunner"
gate_stage="test"
for index in "${!tests[@]}"; do
    instrumentation_args=()
    for runner_arg in "${runner_args[@]}"; do
        instrumentation_args+=("-e" "${runner_arg%%=*}" "${runner_arg#*=}")
    done
    instrumentation_args+=("-e" "class" "${tests[$index]}")
    phase_file="$report_dir/phase-$((index + 1)).txt"
    adb -s "$serial" shell am instrument -w -r \
        "${instrumentation_args[@]}" \
        "$test_application_id/$runner" |
        tee "$phase_file"
    require_passing_phase "$phase_file" "${tests[$index]}"
    if ((index + 1 < ${#tests[@]})); then
        adb -s "$serial" shell am force-stop "$application_id"
    fi
done

printf 'test_result=passed\n' >>"$report_file"
gate_stage="complete"
