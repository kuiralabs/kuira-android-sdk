#!/bin/bash
# SDK registration E2E test driver.
#
# Runs SdkRegistrationE2ETest on an attached emulator/device against a running
# localnet, with optional auto-funding via `mn transfer`.
#
# What it does:
#   1. Sanity-checks: adb device attached, localnet container up, `mn` CLI
#      reachable. Bails with a clear message if any are missing.
#   2. Pushes proving keys to /data/local/tmp on the emulator (no-op if already
#      pushed since the previous run).
#   3. Clears logcat, launches the test in the background, and tails logcat
#      for the `FUND_TARGET_ADDR=<...>` marker.
#   4. When the marker appears, runs `mn transfer <addr> 100` on the host
#      to fund the test wallet (unless AUTO_FUND=0 was passed — then prints
#      the command and waits for you to run it manually).
#   5. Waits for the test to complete, prints the result.
#
# Idempotent: subsequent runs reuse the funded wallet, so the funding step is
# fast (the test sees NIGHT >= 1 and skips waitForFunding immediately).
#
# Usage:
#   AUTO_FUND=1 ./run-sdk-e2e.sh    # default — auto-fund + run
#   AUTO_FUND=0 ./run-sdk-e2e.sh    # log the address, wait for manual transfer

set -e

AUTO_FUND="${AUTO_FUND:-1}"
FUND_AMOUNT="${FUND_AMOUNT:-100}"

KUIRA_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$KUIRA_ROOT"

# ─── Step 1: prereq checks ───
echo "── Checking prerequisites ──"

if ! command -v adb >/dev/null 2>&1; then
    echo "✗ adb not in PATH" >&2; exit 1
fi
if ! adb devices | grep -qE "device$|emulator-"; then
    echo "✗ No adb device attached. Start an emulator first." >&2; exit 1
fi
if ! command -v mn >/dev/null 2>&1; then
    echo "✗ mn CLI not in PATH (needed for funding)" >&2; exit 1
fi
if ! docker ps 2>/dev/null | grep -q "midnight"; then
    echo "✗ Localnet not running. Start it with: mn localnet start" >&2; exit 1
fi
echo "  ✓ adb device, mn CLI, localnet all reachable"

# ─── Step 2: verify proving keys already on emulator ───
#
# The Kicks build script and `scripts/install-bboard-keys.sh` push these to
# `/data/local/tmp/{bboard,wallet}_keys/`; we don't duplicate that here. If
# either is missing, point the user at the existing tooling instead of inventing
# a parallel path.
echo ""
echo "── Verifying proving keys on emulator ──"
HAVE_BLS=$(adb shell ls /data/local/tmp/bboard_keys/bls_midnight_2p13 2>/dev/null || true)
HAVE_DUST=$(adb shell ls /data/local/tmp/wallet_keys/dust/spend.prover 2>/dev/null || true)
if [ -z "$HAVE_BLS" ] || [ -z "$HAVE_DUST" ]; then
    echo "✗ Proving keys missing on emulator." >&2
    echo "  Push them once via either:" >&2
    echo "    examples/midnight-kicks/build-kicks.sh   (full Kicks build, also pushes keys)" >&2
    echo "    scripts/install-bboard-keys.sh           (bboard/BLS subset)" >&2
    exit 1
fi
echo "  ✓ bls_midnight_2p13 + dust/spend.prover present"

# ─── Step 3: run the test, tail logcat for the funding marker ───
echo ""
echo "── Launching test ──"

adb logcat -c
./gradlew :sdk:midnight-sdk:connectedDebugAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.class=com.midnight.kuira.sdk.SdkRegistrationE2ETest \
    >/tmp/sdk-e2e-test.log 2>&1 &
TEST_PID=$!
echo "  test PID: $TEST_PID (gradle log: /tmp/sdk-e2e-test.log)"

# Tail logcat in background, watch for the marker
LOGCAT_PID=
cleanup() {
    [ -n "$LOGCAT_PID" ] && kill "$LOGCAT_PID" 2>/dev/null || true
}
trap cleanup EXIT

# Filter to the test tag so we only see what's relevant.
adb logcat -s SdkRegistrationE2E:* &
LOGCAT_PID=$!

# Wait for the FUND_TARGET_ADDR marker — appears once per test run, very early.
echo ""
echo "── Waiting for FUND_TARGET_ADDR= marker ──"
ADDR=""
for _ in $(seq 1 60); do
    sleep 1
    ADDR=$(adb logcat -d -s SdkRegistrationE2E:* 2>/dev/null \
        | grep -o "FUND_TARGET_ADDR=mn_addr_[a-z0-9_]*" \
        | head -1 \
        | cut -d= -f2)
    [ -n "$ADDR" ] && break
done

if [ -z "$ADDR" ]; then
    echo "✗ Never saw FUND_TARGET_ADDR= in logcat — did the test start? See /tmp/sdk-e2e-test.log" >&2
    wait $TEST_PID
    exit 1
fi
echo "  ✓ Test wallet address: $ADDR"

# ─── Step 4: fund the wallet ───
if [ "$AUTO_FUND" = "1" ]; then
    echo ""
    echo "── Auto-funding: mn transfer $ADDR $FUND_AMOUNT ──"
    mn transfer "$ADDR" "$FUND_AMOUNT" || {
        echo "  mn transfer failed — test will time out on waitForFunding" >&2
    }
else
    echo ""
    echo "── AUTO_FUND=0; run this in another terminal: ──"
    echo "    mn transfer $ADDR $FUND_AMOUNT"
fi

# ─── Step 5: wait for test result ───
echo ""
echo "── Waiting for test to complete... ──"
wait $TEST_PID
TEST_RC=$?

echo ""
if [ $TEST_RC -eq 0 ]; then
    echo "✓ Test passed"
else
    echo "✗ Test failed (rc=$TEST_RC) — see /tmp/sdk-e2e-test.log"
    tail -40 /tmp/sdk-e2e-test.log
fi
exit $TEST_RC
