#!/usr/bin/env bash
# Dijalankan di dalam emulator Android (reactivecircus/android-emulator-runner).
# Memasang APK aplikasi dan APK uji, menjalankan tes langsung dengan "am instrument",
# lalu menarik hasilnya sebelum apa pun dihapus. Mengulang bila unduhan dependency gagal sementara.
set +e
chmod +x gradlew
: > visual.log
for attempt in 1 2 3 4; do
  echo "=== percobaan $attempt ===" | tee -a visual.log
  ./gradlew installDebug installDebugAndroidTest --no-daemon --stacktrace 2>&1 | tee -a visual.log
  if grep -q "BUILD SUCCESSFUL" visual.log; then break; fi
  if grep -q -E "Received status code 429|Could not GET|Could not resolve|timed out" visual.log; then
    echo "Unduhan gagal sementara, tunggu lalu ulangi" | tee -a visual.log
    sleep $((attempt * 40))
  else
    break
  fi
done

mkdir -p visual-out
adb shell am instrument -w -r -e class com.ky7ra.fototeks.PipelineTest \
  com.ky7ra.fototeks.test/androidx.test.runner.AndroidJUnitRunner 2>&1 | tee visual-out/instrument.txt | tail -40
adb shell ls -la /sdcard/Android/data/com.ky7ra.fototeks/files/out/ 2>&1 | tee visual-out/ls.txt
adb pull /sdcard/Android/data/com.ky7ra.fototeks/files/out/. visual-out/ 2>&1 | tail -3
adb logcat -d -s TestRunner:* AndroidRuntime:* > visual-out/logcat.txt 2>&1
exit 0
