#!/bin/bash
# SDK e2e driver + continuous funding servicer.
#
# Runs an instrumented SDK e2e class on an attached emulator against a running localnet, and
# funds EACH per-test isolated wallet on demand. The isolated tier ([IsolatedWalletE2E]) generates
# a fresh wallet per @Test and logs a funding request:
#
#   KUIRA_FUND_REQ addr=<bech32m> night=<int> small=<int> dust=<true|false>
#
# This script tails logcat for those lines and, for EACH (deduped by addr), runs
# `mn airdrop <night/small> --wallet <addr>` × small. Dust registers ON-DEVICE (keyed; host can't sign).
# The device's waitForFunding observes the credit over the indexer subscription — no ACK channel.
#
# The isolated tier proves REMOTELY (localnet proof server at 10.0.2.2:6300), so it needs NO
# on-device proving-key bundle; the key check is a soft warning here (only the LOCAL-proving
# SdkRegistrationE2ETest needs the pushed keys).
#
# Usage:
#   ANDROID_SERIAL=emulator-5554 ./run-sdk-e2e.sh                 # default: the isolated tier
#   TEST_CLASS=ALL ./run-sdk-e2e.sh                               # FULL instrumented suite (ALL modules) + funding — nothing skips
#   TEST_CLASS=com.midnight.kuira.sdk.SdkRegistrationE2ETest ./run-sdk-e2e.sh

set -e

TEST_CLASS="${TEST_CLASS:-com.midnight.kuira.sdk.e2e.SdkSendNightIsolatedE2ETest}"

KUIRA_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$KUIRA_ROOT"

# ─── Step 1: prereq checks ───
echo "── Checking prerequisites ──"
command -v adb >/dev/null 2>&1 || { echo "✗ adb not in PATH" >&2; exit 1; }
adb devices | grep -qE "device$|emulator-" || { echo "✗ No adb device attached. Start an emulator first." >&2; exit 1; }
command -v mn  >/dev/null 2>&1 || { echo "✗ mn CLI not in PATH (needed for funding)" >&2; exit 1; }
docker ps 2>/dev/null | grep -q "midnight" || { echo "✗ Localnet not running. Start it with: mn localnet start" >&2; exit 1; }
echo "  ✓ adb device, mn CLI, localnet all reachable"

# ─── Step 2: proving keys — SOFT for the REMOTE isolated tier ───
HAVE_DUST=$(adb shell ls /data/local/tmp/wallet_keys/dust/spend.prover 2>/dev/null || true)
if [ -z "$HAVE_DUST" ]; then
    echo "  • on-device proving keys absent — fine for the REMOTE-proving isolated tier"
    echo "    (only the LOCAL-proving SdkRegistrationE2ETest needs them: examples/midnight-kicks/build-kicks.sh)"
fi

# ─── Step 3: launch the test, start the funding servicer ───
echo ""
adb logcat -c
if [ "$TEST_CLASS" = "ALL" ]; then
    # Full instrumented suite across EVERY module, with the funding servicer live so the
    # isolated e2e tier RUNS (funded) instead of assumeTrue-skipping. --continue → every
    # module runs even if one fails, so we see the whole picture in one pass.
    echo "── Launching the FULL instrumented suite (ALL modules) + funding servicer ──"
    ./gradlew connectedDebugAndroidTest --continue \
        >/tmp/sdk-e2e-test.log 2>&1 &
else
    echo "── Launching $TEST_CLASS ──"
    ./gradlew :sdk:midnight-sdk:connectedDebugAndroidTest \
        -Pandroid.testInstrumentationRunnerArguments.class="$TEST_CLASS" \
        >/tmp/sdk-e2e-test.log 2>&1 &
fi
TEST_PID=$!
echo "  test PID: $TEST_PID (gradle log: /tmp/sdk-e2e-test.log)"

# Continuous funding servicer: tail the harness tag, fund each fresh wallet once.
SERVICED=" "
LOGCAT_PID=
cleanup() { [ -n "$LOGCAT_PID" ] && kill "$LOGCAT_PID" 2>/dev/null || true; }
trap cleanup EXIT

echo ""
echo "── Funding servicer: watching for KUIRA_FUND_REQ ──"
adb logcat -s KuiraE2EFund:* >/tmp/sdk-e2e-fund.log 2>&1 &
LOGCAT_PID=$!

# Poll the fund log; service every new, not-yet-funded request.
while kill -0 "$TEST_PID" 2>/dev/null; do
    while IFS= read -r line; do
        case "$line" in *KUIRA_FUND_REQ*) : ;; *) continue ;; esac
        addr=$(echo "$line"  | grep -oE "addr=mn_addr_[a-z0-9]+"  | head -1 | cut -d= -f2)
        night=$(echo "$line" | grep -oE "night=[0-9]+"           | head -1 | cut -d= -f2)
        small=$(echo "$line" | grep -oE "small=[0-9]+"           | head -1 | cut -d= -f2)
        dust=$(echo "$line"  | grep -oE "dust=(true|false)"      | head -1 | cut -d= -f2)
        [ -n "$addr" ] && [ -n "$night" ] && [ -n "$small" ] || continue
        case "$SERVICED" in *" $addr "*) continue ;; esac   # already funded
        SERVICED="$SERVICED$addr "
        per=$(( night / small )); [ "$per" -lt 1 ] && per=1
        echo "  → airdrop $addr : ${per} NIGHT ×${small}  (dust=$dust registered ON-DEVICE, not here — it needs the wallet's keys)"
        for _ in $(seq 1 "$small"); do mn airdrop "$per" --wallet "$addr" >/dev/null 2>&1 || true; done
    done < /tmp/sdk-e2e-fund.log
    sleep 2
done

# ─── Step 4: result + isolation gate ───
wait "$TEST_PID"; TEST_RC=$?
echo ""
if grep -qiE "customErrorCode=(195|196)|StaleUtxo|DustDoubleSpend" /tmp/sdk-e2e-test.log; then
    echo "✗ ISOLATION BROKEN — a send hit 195/196 (stale UTXO/dust); fresh wallets should make this impossible."
    TEST_RC=1
fi
if [ $TEST_RC -eq 0 ]; then
    echo "✓ Tests passed (no 195/196 across the run)"
else
    echo "✗ Tests failed (rc=$TEST_RC) — see /tmp/sdk-e2e-test.log"
    tail -40 /tmp/sdk-e2e-test.log
fi
exit $TEST_RC
