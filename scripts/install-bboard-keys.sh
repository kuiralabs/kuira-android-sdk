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
MIDNIGHT_LIBS="${PROJECT_DIR}/../../midnight/midnight-libraries"

BBOARD_KEYS="${MIDNIGHT_LIBS}/example-bboard/contract/src/managed/bboard/keys"
BBOARD_ZKIR="${MIDNIGHT_LIBS}/example-bboard/contract/src/managed/bboard/zkir"

# App's internal storage on device (test app package)
APP_PKG="com.midnight.kuira.core.compact.test"
DEVICE_KEYS_DIR="/data/data/${APP_PKG}/files/proving_keys"

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
echo "Device dir:  $DEVICE_KEYS_DIR"
echo ""

# Create directory on device
adb shell "run-as $APP_PKG mkdir -p files/proving_keys" 2>/dev/null || \
    adb shell "mkdir -p $DEVICE_KEYS_DIR" 2>/dev/null || true

# Push bboard circuit keys
for circuit in post takeDown; do
    for ext in prover verifier; do
        src="${BBOARD_KEYS}/${circuit}.${ext}"
        if [ -f "$src" ]; then
            echo "  Pushing ${circuit}.${ext} ($(du -h "$src" | cut -f1))"
            adb push "$src" "/data/local/tmp/${circuit}.${ext}" > /dev/null
            adb shell "run-as $APP_PKG cp /data/local/tmp/${circuit}.${ext} files/proving_keys/${circuit}.${ext}" 2>/dev/null || \
                adb shell "cp /data/local/tmp/${circuit}.${ext} $DEVICE_KEYS_DIR/${circuit}.${ext}" 2>/dev/null
            adb shell "rm /data/local/tmp/${circuit}.${ext}"
        fi
    done
    # BZKIR files
    src="${BBOARD_ZKIR}/${circuit}.bzkir"
    if [ -f "$src" ]; then
        echo "  Pushing ${circuit}.bzkir ($(du -h "$src" | cut -f1))"
        adb push "$src" "/data/local/tmp/${circuit}.bzkir" > /dev/null
        adb shell "run-as $APP_PKG cp /data/local/tmp/${circuit}.bzkir files/proving_keys/${circuit}.bzkir" 2>/dev/null || \
            adb shell "cp /data/local/tmp/${circuit}.bzkir $DEVICE_KEYS_DIR/${circuit}.bzkir" 2>/dev/null
        adb shell "rm /data/local/tmp/${circuit}.bzkir"
    fi
done

echo ""
echo "=== Verifying installation ==="
adb shell "run-as $APP_PKG ls -la files/proving_keys/" 2>/dev/null || \
    adb shell "ls -la $DEVICE_KEYS_DIR/" 2>/dev/null || echo "(could not verify — run-as may not work for test package)"

echo ""
echo "Done! Run the e2e test:"
echo "  ./gradlew :core:compact-engine:connectedDebugAndroidTest \\"
echo "    -Pandroid.testInstrumentationRunnerArguments.class=com.midnight.kuira.core.compact.BboardEndToEndTest"
