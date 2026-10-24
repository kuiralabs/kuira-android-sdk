#!/usr/bin/env bash
#
# preprod-gate.sh — the PreProd tier of the release gate. LOCAL ONLY, never CI.
#
#   scripts/e2e/preprod-gate.sh [serial]
#   ANDROID_SERIAL=emulator-5554 scripts/e2e/preprod-gate.sh
#
# WHY THIS TIER EXISTS. Localnet answers every request, so every localnet-only gate was green
# while PreProd's balance had been 0 for two weeks: the indexer was decommissioned on 2026-10-09,
# each sync leg swallowed its exception, and the UI labelled the zero "Synced". Unit tests
# asserted URL shapes and kuiraDoctor validated the credential — neither reads the chain.
#
# Zero is a legitimate balance, so "no exception" proves nothing here. This gate asserts the app's
# balance against `mn`, the ground-truth oracle, and fails if they disagree.
#
# CREDENTIALS, both from the gitignored local.properties, never printed:
#   kuira.dev.seed              the gate wallet — funded out of band on PreProd
#   kuira.blockfrost.projectId  PreProd's indexer is Blockfrost-served and 403s without it
#
# NOTHING HERE SKIPS. A missing credential, an unfunded wallet or an unreachable oracle is a
# FAILURE. A gate that quietly covers nothing is worse than no gate, because it reports green.
set -uo pipefail
cd "$(dirname "$0")/../.."

# This gate only derives an address and reads an UNSHIELDED balance, which the PATH `mn` (0.6.x,
# ledger 9) answers correctly against a ledger-8 chain. The 0.5.8 pin the localnet scripts use is
# for shielded sync, which this gate never touches.
MN_CLI="${MN_CLI:-mn}"
NIGHT_UNIT=1000000
SEND_WHOLE=1
RECIPIENT_INDEX=1
PROOF_SERVER_PORT=6300
TEST_CLASS=com.midnight.kuira.sdk.PreProdBalanceE2ETest

ok()   { printf '  \033[32m✓\033[0m %s\n' "$1"; }
die()  { printf '  \033[31m✗ %s\033[0m\n' "$1" >&2; exit 1; }
bold() { printf '\033[1m==> %s\033[0m\n' "$1"; }

prop() { awk -F= -v k="$1" '$1==k{sub(/^[^=]*=/,""); print; exit}' local.properties; }

SERIAL="${1:-${ANDROID_SERIAL:-$(adb devices | awk '/\tdevice$/{print $1; exit}')}}"
[ -n "$SERIAL" ] || die "no device. Attach one or pass a serial."
export ANDROID_SERIAL="$SERIAL"

echo
bold "PreProd gate on $SERIAL"

SEED="$(prop kuira.dev.seed)"
PROJECT_ID="$(prop kuira.blockfrost.projectId)"
[ -n "$SEED" ] || die "kuira.dev.seed is missing from local.properties — the gate has no wallet."
[ -n "$PROJECT_ID" ] || die "kuira.blockfrost.projectId is missing from local.properties. PreProd's indexer is served by Blockfrost and answers nothing without one: create a PreProd project at https://blockfrost.io (the id starts with nightpreprod)."
ok "credentials present"

ADDRESS="$($MN_CLI address --seed "$SEED" --network preprod 2>/dev/null | head -1 | tr -d '[:space:]')"
case "$ADDRESS" in
  mn_addr_preprod1*) ok "gate wallet $ADDRESS" ;;
  *) die "could not derive the gate wallet's PreProd address via '$MN_CLI address'." ;;
esac

# The oracle, not a hardcoded figure: whatever the chain says now is what the app must report.
CHAIN_RAW="$($MN_CLI balance "$ADDRESS" --network preprod 2>/dev/null | sed 's/\x1b\[[0-9;]*m//g' | awk -F= '/^NIGHT=/{print $2; exit}')"
case "$CHAIN_RAW" in
  ''|*[!0-9]*) die "'$MN_CLI balance' did not report a NIGHT figure for the gate wallet — the oracle itself is unreachable, so this gate cannot judge the app." ;;
esac
CHAIN_NIGHT=$(( CHAIN_RAW / NIGHT_UNIT ))
[ "$CHAIN_NIGHT" -gt 0 ] || die "the gate wallet holds 0 NIGHT on PreProd. Fund $ADDRESS, then re-run — an unfunded wallet makes every assertion here vacuous."
ok "chain says $CHAIN_NIGHT NIGHT"

# Index 1 of the SAME seed: a fixed receiving address with the same owner, so the gate's NIGHT
# ping-pongs between two wallets the operator controls and never leaves.
RECIPIENT="$($MN_CLI address --seed "$SEED" --network preprod --index $RECIPIENT_INDEX 2>/dev/null | head -1 | tr -d '[:space:]')"
case "$RECIPIENT" in
  mn_addr_preprod1*) ok "recipient $RECIPIENT (index $RECIPIENT_INDEX of the same seed)" ;;
  *) die "could not derive the recipient address." ;;
esac
[ "$RECIPIENT" != "$ADDRESS" ] || die "the recipient resolved to the sender; index $RECIPIENT_INDEX must differ from 0."

RECIPIENT_BEFORE_RAW="$($MN_CLI balance "$RECIPIENT" --network preprod 2>/dev/null | sed 's/\x1b\[[0-9;]*m//g' | awk -F= '/^NIGHT=/{print $2; exit}')"
case "$RECIPIENT_BEFORE_RAW" in ''|*[!0-9]*) RECIPIENT_BEFORE_RAW=0 ;; esac
ok "recipient holds $(( RECIPIENT_BEFORE_RAW / NIGHT_UNIT )) NIGHT before the send"

# Proving is remote on PreProd; without a proof server the send fails on a prerequisite rather
# than on the path under test, which would read as a false regression.
python3 -c "
import socket,sys
s=socket.socket(); s.settimeout(2)
sys.exit(0 if s.connect_ex(('127.0.0.1',$PROOF_SERVER_PORT))==0 else 1)
" || die "no proof server on 127.0.0.1:$PROOF_SERVER_PORT. PreProd proving is remote; start one before gating."
ok "proof server reachable"

bold "the app must agree with the chain"
./gradlew :sdk:midnight-sdk:connectedDebugAndroidTest --console=plain \
  -Pandroid.testInstrumentationRunnerArguments.class="$TEST_CLASS" \
  -Pandroid.testInstrumentationRunnerArguments.seedHex="$SEED" \
  -Pandroid.testInstrumentationRunnerArguments.blockfrostProjectId="$PROJECT_ID" \
  -Pandroid.testInstrumentationRunnerArguments.minNight="$CHAIN_NIGHT" \
  -Pandroid.testInstrumentationRunnerArguments.recipient="$RECIPIENT" \
  || die "the SDK disagreed with the chain on PreProd. Cross-check with: $MN_CLI balance $ADDRESS --network preprod"

bold "the chain must show the send landed"
RECIPIENT_AFTER_RAW="$($MN_CLI balance "$RECIPIENT" --network preprod 2>/dev/null | sed 's/\x1b\[[0-9;]*m//g' | awk -F= '/^NIGHT=/{print $2; exit}')"
case "$RECIPIENT_AFTER_RAW" in ''|*[!0-9]*) die "could not re-read the recipient's balance to confirm the send." ;; esac
MOVED=$(( RECIPIENT_AFTER_RAW - RECIPIENT_BEFORE_RAW ))
# The app reporting Success is OUR claim; this is the chain's. They have disagreed before.
[ "$MOVED" -ge $(( SEND_WHOLE * NIGHT_UNIT )) ] \
  || die "the app reported a send but the chain moved $MOVED raw NIGHT to $RECIPIENT (expected >= $(( SEND_WHOLE * NIGHT_UNIT ))). A Pending result can land late — re-read with: $MN_CLI balance $RECIPIENT --network preprod"
ok "chain confirms $(( MOVED / NIGHT_UNIT )) NIGHT reached the recipient"

echo
printf '\033[32m════ PREPROD GATE GREEN — balance agrees with the chain and a send finalized ════\033[0m\n'
