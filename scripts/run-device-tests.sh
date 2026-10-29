#!/usr/bin/env bash
set -uo pipefail
mkdir -p .cloud/device-diagnostics
gradle --no-daemon --stacktrace :app:connectedDebugAndroidTest
result=$?
adb logcat -d > .cloud/device-diagnostics/logcat.txt
adb shell dumpsys accessibility > .cloud/device-diagnostics/accessibility.txt
adb pull /sdcard/Android/data/ru.chesscoach.overlay/files/ .cloud/device-diagnostics/screenshots/ || true
# Gradle uninstalls test APKs at the end. Reinstall the already-built emulator
# APK to capture the actual home screen for visual inspection.
adb install -r app/build/outputs/apk/debug/app-x86_64-debug.apk
adb shell am start -W -n ru.chesscoach.overlay/.MainActivity
adb shell screencap -p /sdcard/coach-home.png
adb pull /sdcard/coach-home.png .cloud/device-diagnostics/home.png
exit "$result"
