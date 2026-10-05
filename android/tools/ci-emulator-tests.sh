#!/usr/bin/env bash
# Инструментальные тесты на эмуляторе CI. Плеер YouTube (для проверки EJS
# внутри APK) и дорожки живой проверки 1440p кладутся в /data/local/tmp:
# тесты читают их через shell инструментации и пропускаются, если файлов нет.
set -euo pipefail
cd "$(dirname "$0")/.."
adb shell mkdir -p /data/local/tmp/nox-ejs /data/local/tmp/nox-live
if [ -f build-ejs/player-74edf1a3.js ]; then
  adb push build-ejs/player-74edf1a3.js /data/local/tmp/nox-ejs/
fi
if [ -f build-live/meta.json ]; then
  adb push build-live/video.webm build-live/audio.webm build-live/meta.json /data/local/tmp/nox-live/
fi
# Снимки экрана из тестов забираются даже при падении.
trap 'mkdir -p build-shots && adb pull /data/local/tmp/nox-shots build-shots/ >/dev/null 2>&1 || true; adb logcat -d -s NOX-TEST:I > build-shots/nox-test.log 2>/dev/null || true' EXIT
./gradlew --no-daemon --stacktrace connectedDebugAndroidTest
mkdir -p build-shots && adb logcat -d -s NOX-TEST:I > build-shots/nox-test-connected.log 2>/dev/null || true
# Механика проверки обновления (tools/ci-upgrade-check.sh) на отладочной сборке —
# чтобы скрипт выпуска был проверен до того, как от него зависит публикация.
NOX_PKG=com.nox.offline.debug NOX_TEST_PKG=com.nox.offline.debug.test NOX_UPGRADE_DRY_RUN=1 \
  bash tools/ci-upgrade-check.sh app/build/outputs/apk/debug/app-debug.apk app/build/outputs/apk/debug/app-debug.apk \
  app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
