#!/usr/bin/env bash
set -uo pipefail
mkdir -p .cloud/device-diagnostics
gradle --no-daemon --stacktrace :app:connectedDebugAndroidTest
result=$?
adb logcat -d > .cloud/device-diagnostics/logcat.txt
adb shell dumpsys accessibility > .cloud/device-diagnostics/accessibility.txt
adb pull /sdcard/Android/data/ru.chesscoach.overlay/files/ .cloud/device-diagnostics/screenshots/ || true
exit "$result"
