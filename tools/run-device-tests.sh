#!/usr/bin/env bash
# Keep diagnostics on the CI host before the emulator or test apps are removed.
set -uo pipefail
api_level="${1:?Android API level required}"
expected_pages="${2:?Expected memory page size required}"
diagnostics="app/build/outputs/device-diagnostics"
mkdir -p "$diagnostics"

capture_diagnostics() {
    adb shell getprop ro.build.version.sdk > "$diagnostics/android-api.txt" 2>&1 || true
    adb shell getconf PAGE_SIZE > "$diagnostics/page-size.txt" 2>&1 || true
    adb shell getprop bionic.linker.16kb.app_compat.enabled > "$diagnostics/linker-compat.txt" 2>&1 || true
    adb shell getprop pm.16kb.app_compat.disabled > "$diagnostics/package-compat-disabled.txt" 2>&1 || true
    adb logcat -b crash -d > "$diagnostics/crash.log" 2>&1 || true
    adb logcat -d > "$diagnostics/logcat.log" 2>&1 || true
}

prepare_device() {
    if [ "$api_level" -ge 35 ]; then
        actual_pages="$(adb shell getconf PAGE_SIZE | tr -d '\r')"
        [ "$actual_pages" = "$expected_pages" ] || return 1
    fi
    if [ "$expected_pages" = "16384" ]; then
        adb root || return 1
        adb wait-for-device || return 1
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
