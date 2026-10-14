#!/usr/bin/env bash
# Keep diagnostics on the CI host before the emulator or test apps are removed.
set -uo pipefail
api_level="${1:?Android API level required}"
expected_pages="${2:?Expected memory page size required}"
diagnostics="app/build/outputs/device-diagnostics"
mkdir -p "$diagnostics"

capture_diagnostics() {
    timeout 10s adb shell getprop ro.build.version.sdk > "$diagnostics/android-api.txt" 2>&1 || true
    timeout 10s adb shell getconf PAGE_SIZE > "$diagnostics/page-size.txt" 2>&1 || true
    timeout 10s adb shell getprop bionic.linker.16kb.app_compat.enabled > "$diagnostics/linker-compat.txt" 2>&1 || true
    timeout 10s adb shell getprop pm.16kb.app_compat.disabled > "$diagnostics/package-compat-disabled.txt" 2>&1 || true
    timeout 10s adb logcat -b crash -d > "$diagnostics/crash.log" 2>&1 || true
    timeout 10s adb logcat -d > "$diagnostics/logcat.log" 2>&1 || true
}

wait_for_ready_device() {
    local deadline=$((SECONDS + 45))
    while [ "$SECONDS" -lt "$deadline" ]; do
        if [ "$(timeout 5s adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; then
            return 0
        fi
        sleep 2
    done
    return 1
}

prepare_root_adb() {
    local attempt
    # Root restarts adbd. A closed connection can mean the restart succeeded;
    # check the eventual UID instead of treating that transient exit as failure.
    for attempt in 1 2 3; do
        timeout 10s adb root >> "$diagnostics/adb-readiness.log" 2>&1 || true
        if wait_for_ready_device && [ "$(timeout 5s adb shell id -u 2>/dev/null | tr -d '\r')" = "0" ]; then
            return 0
        fi
    done
    return 1
}

prepare_device() {
    wait_for_ready_device || return 1
    if [ "$api_level" -ge 35 ]; then
        actual_pages="$(adb shell getconf PAGE_SIZE | tr -d '\r')"
        [ "$actual_pages" = "$expected_pages" ] || return 1
    fi
    if [ "$expected_pages" = "16384" ]; then
        prepare_root_adb || return 1
        adb shell setprop bionic.linker.16kb.app_compat.enabled false || return 1
        adb shell setprop pm.16kb.app_compat.disabled true || return 1
        [ "$(adb shell getprop bionic.linker.16kb.app_compat.enabled | tr -d '\r')" = "false" ] || return 1
        [ "$(adb shell getprop pm.16kb.app_compat.disabled | tr -d '\r')" = "true" ] || return 1
    fi
}

if ! prepare_device; then
    capture_diagnostics
    exit 1
fi

gradle --no-daemon :app:connectedDebugAndroidTest
test_status=$?
capture_diagnostics
if [ "$test_status" -eq 0 ]; then
    mkdir -p app/build/test-ui-screenshots
    adb pull /sdcard/Download/PaperPocket-ui/. app/build/test-ui-screenshots/ > "$diagnostics/screenshot-export.log" 2>&1 || true
fi
exit "$test_status"
