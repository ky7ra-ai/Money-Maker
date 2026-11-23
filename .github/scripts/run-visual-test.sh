#!/usr/bin/env bash
# Dijalankan di dalam emulator Android (reactivecircus/android-emulator-runner).
set +e
chmod +x gradlew
./gradlew connectedDebugAndroidTest --no-daemon --stacktrace \
  -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true 2>&1 | tee visual.log
mkdir -p visual-out
adb pull /sdcard/Android/data/com.ky7ra.fototeks/files/out/. visual-out/ 2>&1 | tail -3
adb logcat -d > visual-out/logcat.txt 2>&1
exit 0
