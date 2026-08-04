#!/usr/bin/env bash
#
# Dev aid: render the dapp-ui wallet-panel states on a connected device/emulator via an
# instrumented Compose test and pull the PNGs for eyeballing look-and-feel. NOT for CI — this is
# for iterating on the UI. The Android analog of the iOS scripts/e2e/ui-screenshots.sh.
#
# Why an instrumented test (not JVM/Robolectric): the runner/dust/shimmer render + the
# `captureToImage()` bitmap need a real Compose renderer on a device.
#
# Why manual install + `am instrument` (not `connectedAndroidTest`): Gradle uninstalls the test
# APK after `connectedAndroidTest`, which wipes the app's external files dir — and the screenshot
# with it. Installing manually keeps the PNG pullable.
#
# Prereqs: a booted emulator/device (`adb devices`), Android SDK + Gradle.
# Usage:  scripts/ui-screenshots.sh   (PNGs land in build/ui-shots/)
set -euo pipefail
cd "$(dirname "$0")/.."   # repo root

TEST_PKG="com.midnight.kuira.dapp.test"
TEST_CLASS="com.midnight.kuira.dapp.wallet.LoadingStateScreenshotTest"
OUT="build/ui-shots"

command -v adb >/dev/null || { echo "adb not on PATH" >&2; exit 1; }
adb get-state >/dev/null 2>&1 || { echo "no booted device/emulator — start one first (adb devices)" >&2; exit 1; }

echo "==> Building the dapp-ui androidTest APK…"
./gradlew :sdk:dapp-ui:assembleDebugAndroidTest --console=plain -q
APK="$(find sdk/dapp-ui/build/outputs/apk/androidTest -name '*.apk' | head -1)"
[ -n "$APK" ] || { echo "androidTest APK not found" >&2; exit 1; }

echo "==> Installing $APK"
adb install -r -t "$APK" >/dev/null

rm -rf "$OUT"; mkdir -p "$OUT"
echo "==> Running $TEST_CLASS (holds the panel on screen)…"
# The test renders the panel full-screen and holds it visible with animations LIVE. Run the
# instrumentation in the background, then screencap the real screen mid-hold — this captures the
# Lottie runner (which a frozen-clock captureToImage would leave blank) exactly as it renders.
adb shell am instrument -w -e class "$TEST_CLASS" \
  "$TEST_PKG/androidx.test.runner.AndroidJUnitRunner" >/tmp/kuira-ui-instr.log 2>&1 &
INSTR_PID=$!
# Instrumentation cold-start (process + dexopt) can take several seconds, so don't screencap on a
# fixed delay — poll until the test's ComponentActivity is actually foreground, then grab it.
echo "  waiting for the panel activity to foreground…"
for _ in $(seq 1 40); do
  adb shell dumpsys activity activities 2>/dev/null | grep -qE "ResumedActivity.*$TEST_PKG" && break
  sleep 0.5
done
sleep 5   # let the async dotLottie composition unzip+parse+load, then the runner takes a stride
adb exec-out screencap -p > "$OUT/loading-syncing.png"
wait "$INSTR_PID" || { echo "instrumentation failed — see /tmp/kuira-ui-instr.log"; tail -20 /tmp/kuira-ui-instr.log; exit 1; }
echo "  $OUT/loading-syncing.png"
echo "==> Done."
