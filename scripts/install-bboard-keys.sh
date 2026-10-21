#!/bin/bash
# Install bboard proving keys on the Android emulator for 6J e2e testing.
#
# Prerequisites:
# - Running emulator (adb devices should show a device)
# - Wallet proving keys already downloaded (run the app and download from settings)
#   OR manually push BLS params
#
# This script copies bboard circuit keys from the midnight-libraries repo
# to the emulator's proving_keys directory where the Rust prover can find them.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
# Override with the MIDNIGHT_LIBS env var if your checkout lives elsewhere.
# The default used to be ${PROJECT_DIR}/../../midnight/... — one level too shallow, since
# PROJECT_DIR is <dev>/android/projects/kuira-android-wallet, so ../.. lands on
# <dev>/android. Try the plausible roots instead of pinning one relative depth.
for _cand in \
  "${MIDNIGHT_LIBS:-}" \
  "${PROJECT_DIR}/../../../midnight/midnight-libraries" \
  "${HOME}/Development/midnight/midnight-libraries"; do
  if [ -n "$_cand" ] && [ -d "$_cand/example-bboard" ]; then MIDNIGHT_LIBS="$_cand"; break; fi
done
MIDNIGHT_LIBS="${MIDNIGHT_LIBS:-${PROJECT_DIR}/../../../midnight/midnight-libraries}"

BBOARD_KEYS="${MIDNIGHT_LIBS}/example-bboard/contract/src/managed/bboard/keys"
BBOARD_ZKIR="${MIDNIGHT_LIBS}/example-bboard/contract/src/managed/bboard/zkir"

# App's internal storage on device (test app package)
APP_PKG="com.midnight.kuira.core.compact.test"
# Durable staging dir, NOT the app's private files/proving_keys.
#
# The keys used to be copied straight into /data/data/<pkg>/files/proving_keys, and the
# /data/local/tmp copy was deleted afterwards. AGP uninstalls the test APK when
# connectedAndroidTest finishes, which deletes that private directory — so the keys survived
# exactly one run and every later Gradle run failed "Bboard proving keys not installed".
#
# Both test classes already install from here in @Before, via the SDK's own content-aware
# provisioner (installCircuitKeysForProving). Staging here and leaving the files in place is
# what those tests were written to expect, and it survives any number of reinstalls.
DEVICE_STAGING_DIR="/data/local/tmp/bboard_keys"

echo "=== BBoard Proving Key Installer ==="
echo ""

# Verify sources exist
if [ ! -d "$BBOARD_KEYS" ]; then
    echo "ERROR: BBoard keys not found at: $BBOARD_KEYS"
    echo "Make sure midnight-libraries is checked out at: $MIDNIGHT_LIBS"
    exit 1
fi

# Verify emulator
if ! adb devices | grep -q "device$"; then
    echo "ERROR: No Android device connected"
    echo "Start the emulator first: emulator -avd <name>"
    exit 1
fi

echo "Source keys: $BBOARD_KEYS"
echo "Source zkir: $BBOARD_ZKIR"
echo "Staging dir: $DEVICE_STAGING_DIR"
echo ""

# Create the staging directory on device
adb shell "mkdir -p $DEVICE_STAGING_DIR"

# Push bboard circuit keys
for circuit in post takeDown; do
    for ext in prover verifier; do
        src="${BBOARD_KEYS}/${circuit}.${ext}"
        if [ -f "$src" ]; then
            echo "  Pushing ${circuit}.${ext} ($(du -h "$src" | cut -f1))"
            adb push "$src" "$DEVICE_STAGING_DIR/${circuit}.${ext}" > /dev/null
        fi
    done
    # BZKIR files
    src="${BBOARD_ZKIR}/${circuit}.bzkir"
    if [ -f "$src" ]; then
        echo "  Pushing ${circuit}.bzkir ($(du -h "$src" | cut -f1))"
        adb push "$src" "$DEVICE_STAGING_DIR/${circuit}.bzkir" > /dev/null
    fi
done

echo ""
echo "=== Verifying installation ==="
adb shell "ls -la $DEVICE_STAGING_DIR/"

echo ""
echo "Done! Run the e2e test:"
echo "  ./gradlew :core:compact-engine:connectedDebugAndroidTest \\"
echo "    -Pandroid.testInstrumentationRunnerArguments.class=com.midnight.kuira.core.compact.BboardEndToEndTest"
