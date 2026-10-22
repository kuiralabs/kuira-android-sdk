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

# The required set is not a matter of taste: ProvingKeyManager's CIRCUIT_KEY_EXTENSIONS is
# prover/verifier/bzkir, so a circuit missing any one of the three cannot prove. This loop used
# to push whichever of them happened to exist and report success either way, which is how a
# device ended up with a partial key set and failed much later as "Bboard proving keys not
# installed" -- a symptom with nothing pointing back to here.
CIRCUITS="post takeDown"
# ext:source-dir, since bzkir lives in a different directory from the keys.
SPECS="prover:$BBOARD_KEYS verifier:$BBOARD_KEYS bzkir:$BBOARD_ZKIR"

MISSING=""
for circuit in $CIRCUITS; do
    for spec in $SPECS; do
        [ -f "${spec#*:}/${circuit}.${spec%%:*}" ] || MISSING="$MISSING ${circuit}.${spec%%:*}"
    done
done
if [ -n "$MISSING" ]; then
    echo "ERROR: the bboard key set is incomplete under $MIDNIGHT_LIBS" >&2
    echo "       missing:$MISSING" >&2
    echo "       Recompile the contract with compactc before installing." >&2
    exit 1
fi

# Verify a device is not just present but USABLE. `adb devices` distinguishes states that need
# different fixes, and collapsing them into "No Android device connected / start the emulator"
# sent you after the wrong one -- an unauthorized phone is attached and needs a tap on it.
DEV_STATES="$(adb devices | tail -n +2 | awk 'NF {print $2}' | sort -u | tr '\n' ' ')"
if ! printf '%s' "$DEV_STATES" | grep -qw device; then
    case "$DEV_STATES" in
        *unauthorized*)
            echo "ERROR: the attached device is UNAUTHORIZED, not missing." >&2
            echo "       Unlock it and tap Allow on the USB debugging prompt, then re-run." >&2
            ;;
        *offline*)
            echo "ERROR: the attached device is OFFLINE. Re-plug it, or run adb kill-server." >&2
            ;;
        "")
            echo "ERROR: no Android device attached. Connect a phone or start an emulator." >&2
            ;;
        *)
            echo "ERROR: no usable device; adb reports state(s): $DEV_STATES" >&2
            ;;
    esac
    exit 1
fi

echo "Source keys: $BBOARD_KEYS"
echo "Source zkir: $BBOARD_ZKIR"
echo "Staging dir: $DEVICE_STAGING_DIR"
echo ""

# Create the staging directory on device
adb shell "mkdir -p $DEVICE_STAGING_DIR"

local_size() { wc -c < "$1" | tr -d ' '; }
device_size() {
    # A probe, so absence is a legitimate answer and is reported by the assertion below rather
    # than by this measurement -- which is why the `|| true` here is not a swallowed failure.
    adb shell "stat -c %s '$1' 2>/dev/null || wc -c < '$1' 2>/dev/null" 2>/dev/null \
        | tr -d ' \r' | head -1 || true
}

for circuit in $CIRCUITS; do
    for spec in $SPECS; do
        ext="${spec%%:*}"; src="${spec#*:}/${circuit}.${ext}"
        echo "  Pushing ${circuit}.${ext} ($(du -h "$src" | cut -f1))"
        adb push "$src" "$DEVICE_STAGING_DIR/${circuit}.${ext}" > /dev/null
    done
done

echo ""
echo "=== Verifying installation (byte sizes on device, not just a listing) ==="
# This step used to print `ls -la` and then claim success regardless. A listing is not a check:
# a failed or truncated push looked exactly like a good one.
BAD=""
for circuit in $CIRCUITS; do
    for spec in $SPECS; do
        ext="${spec%%:*}"
        want="$(local_size "${spec#*:}/${circuit}.${ext}")"
        got="$(device_size "$DEVICE_STAGING_DIR/${circuit}.${ext}")"
        if [ "$got" = "$want" ]; then
            echo "  ok  ${circuit}.${ext}  ${want} bytes"
        else
            BAD="$BAD ${circuit}.${ext}(want ${want}, got ${got:-nothing})"
        fi
    done
done
if [ -n "$BAD" ]; then
    echo "ERROR: keys did not land on the device intact:$BAD" >&2
    exit 1
fi

echo ""
echo "Done! Run the e2e test:"
echo "  ./gradlew :core:compact-engine:connectedDebugAndroidTest \\"
echo "    -Pandroid.testInstrumentationRunnerArguments.class=com.midnight.kuira.core.compact.BboardEndToEndTest"
