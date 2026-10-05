#!/usr/bin/env bash
# Проверка выпуска на эмуляторе ДО публикации, тем самым подписанным APK,
# который уйдёт в выпуск:
#   1) ставится опубликованная предыдущая версия (официальный APK с GitHub);
#   2) внутри неё создаются данные пользователя (UpgradeDataDeviceTest#seed),
#      она открывается с ними, снимок всех данных (#snapshotBefore);
#   3) новый APK ставится поверх (adb install -r — обновление, не переустановка);
#   4) внутри новой версии всё сверяется со снимком (UpgradeDataDeviceTest#verify);
#   5) длинный MP4 (70 ч, moov ≈217 МиБ) — в подписанной сборке (MarathonDeviceTest).
# Тестовый APK подписан не ключом выпуска: инструментирование чужой подписи
# разрешено отладочному образу эмулятора от root (adb root).
# Использование: ci-upgrade-check.sh <прежний.apk> <новый.apk> <тестовый.apk>
# Прогон механики на отладочной сборке (задача тестов, до выпуска):
#   NOX_PKG=com.nox.offline.debug NOX_TEST_PKG=com.nox.offline.debug.test
#   NOX_UPGRADE_DRY_RUN=1 — «прежняя» и новая версии совпадают (versionCode не растёт),
#   длинный MP4 не повторяется (его уже прогнали тесты на устройстве).
set -euo pipefail
cd "$(dirname "$0")/.."
PREV="$1"
NEW="$2"
TEST="$3"
PKG="${NOX_PKG:-com.nox.offline}"
RUNNER="${NOX_TEST_PKG:-com.nox.offline.test}/androidx.test.runner.AndroidJUnitRunner"
DRY="${NOX_UPGRADE_DRY_RUN:-0}"
OUT=build-upgrade
mkdir -p "$OUT"
trap 'adb logcat -d -s NOX-TEST:I > "$OUT/nox-test.log" 2>/dev/null || true' EXIT

adb root >/dev/null 2>&1 || true
sleep 3
adb wait-for-device
echo "adb shell: $(adb shell id)"

pkginfo() { adb shell dumpsys package "$PKG" | grep -E "versionCode=|versionName=|firstInstallTime=|lastUpdateTime=" | sed 's/^ *//' | sort -u; }
launch() {
  adb shell am start -W -n "$PKG/com.nox.offline.MainActivity" > /dev/null
  sleep 12
  adb exec-out screencap -p > "$OUT/$1.png" 2>/dev/null || true
  adb shell am force-stop "$PKG"
}
# Один тест инструментирования; успех — «OK (1 test)» и ни одного «FAILURES».
run_test() {
  local name="$1"; shift
  local out
  out=$(timeout 1500 adb shell am instrument -w -r "$@" "$RUNNER" 2>&1 || true)
  printf '%s\n' "$out" > "$OUT/$name.txt"
  printf '%s\n' "$out" | tail -n 25
  printf '%s\n' "$out" | grep -q "OK (1 test)" && ! printf '%s\n' "$out" | grep -q "FAILURES!!!"
}

adb uninstall "$PKG" > /dev/null 2>&1 || true
adb install "$PREV"
adb install -r -t "$TEST"
echo "--- установлена прежняя версия"
pkginfo | tee "$OUT/before-package.txt"
launch 01-previous-first-launch

echo "--- данные в прежней версии"
run_test seed -e nox.upgrade seed -e class com.nox.offline.UpgradeDataDeviceTest#seed
adb logcat -d -s NOX-TEST:I | grep -q "upgrade: засеяно" || { echo "::error::данные в прежней версии не созданы"; exit 1; }
launch 02-previous-with-data
run_test snapshot -e nox.upgrade snapshot -e class com.nox.offline.UpgradeDataDeviceTest#snapshotBefore
adb shell test -f "/sdcard/Android/data/$PKG/files/upgrade-check/before.txt" || { echo "::error::снимок данных не создан"; exit 1; }

echo "--- обновление поверх (adb install -r)"
adb install -r "$NEW"
pkginfo | tee "$OUT/after-package.txt"
FIRST_BEFORE=$(grep firstInstallTime "$OUT/before-package.txt")
FIRST_AFTER=$(grep firstInstallTime "$OUT/after-package.txt")
if [ "$FIRST_BEFORE" != "$FIRST_AFTER" ]; then echo "::error::firstInstallTime изменился: это переустановка"; exit 1; fi
launch 03-updated-first-launch

echo "--- сверка данных в новой версии"
run_test verify -e nox.upgrade verify -e nox.dryRun "$DRY" -e class com.nox.offline.UpgradeDataDeviceTest#verify
adb logcat -d -s NOX-TEST:I | grep -q "сверка после обновления: всё сохранилось" || { echo "::error::сверка данных после обновления не прошла"; exit 1; }
adb shell cat "/sdcard/Android/data/$PKG/files/upgrade-check/after.txt" > "$OUT/after-snapshot.txt" || true
adb shell cat "/sdcard/Android/data/$PKG/files/upgrade-check/before.txt" > "$OUT/before-snapshot.txt" || true

if [ "$DRY" != "1" ]; then
  echo "--- длинный MP4 в подписанной сборке"
  run_test marathon -e class com.nox.offline.MarathonDeviceTest
  adb logcat -d -s NOX-TEST:I | grep "marathon:" > "$OUT/marathon.log" || true
  if grep -q "НЕ ПРОШЛО" "$OUT/marathon.log"; then echo "::error::длинный MP4 в подписанной сборке"; exit 1; fi
fi
launch 04-updated-after-tests
adb uninstall "$PKG" > /dev/null 2>&1 || true
adb uninstall "${NOX_TEST_PKG:-com.nox.offline.test}" > /dev/null 2>&1 || true
echo "--- готово"
