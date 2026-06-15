#!/usr/bin/env bash
#
# Fund the localnet test wallet for SendNightTransferE2ETest (#240).
#
# The test forces a MULTI-INPUT NIGHT transfer, so the wallet must hold at least
# two spendable NIGHT UTXOs plus registered dust to pay the fee. Each `mn airdrop`
# creates a separate UTXO, so two small airdrops guarantee the multi-input case on
# top of whatever the wallet already holds.
#
# The wallet's index-0 address must equal the test's FUNDED_ADDRESS
# (mn_addr_undeployed1...pqauuvtx) — the `kuiratest` / `test-abandon` wallet, both
# derived from the "abandon ... art" mnemonic.
#
# Usage:
#   scripts/e2e/fund-send-test.sh [wallet-name]   # default: kuiratest
#
# Prereq: localnet running (mn localnet ...), `mn` on PATH.

set -euo pipefail

WALLET="${1:-kuiratest}"
EXPECTED_SUFFIX="pqauuvtx"   # tail of FUNDED_ADDRESS the e2e derives
AIRDROP_NIGHT=5              # small UTXOs; the big existing UTXO is left untouched

echo "==> Funding wallet '$WALLET' for the multi-input send e2e"

# Guard: the wallet must be the one the test derives, or its UTXOs won't be seen.
ADDR_LINE="$(mn wallet info "$WALLET" --json 2>/dev/null || true)"
if [[ "$ADDR_LINE" != *"$EXPECTED_SUFFIX"* ]]; then
  echo "ERROR: wallet '$WALLET' is not the test wallet (expected address ending '$EXPECTED_SUFFIX')." >&2
  echo "       Generate it from the canonical mnemonic, e.g.:" >&2
  echo "       mn wallet generate $WALLET --network undeployed --mnemonic \"abandon ... art\"" >&2
  exit 1
fi

# Two airdrops → two extra UTXOs → the builder must select >= 2 inputs for the test amount.
echo "==> Airdropping ${AIRDROP_NIGHT} NIGHT x2 (creates two extra UTXOs)"
mn airdrop "$AIRDROP_NIGHT" --wallet "$WALLET" 2>/dev/null
mn airdrop "$AIRDROP_NIGHT" --wallet "$WALLET" 2>/dev/null

# Ensure dust is registered so fees are payable (idempotent — only registers
# NIGHT that isn't already generating dust).
echo "==> Registering dust (idempotent)"
mn dust register --wallet "$WALLET" 2>/dev/null || true

echo "==> Resulting state"
mn balance --wallet "$WALLET" 2>/dev/null || true
mn dust status --wallet "$WALLET" 2>/dev/null || true

echo "==> Done. Run the e2e with:"
echo "    ANDROID_SERIAL=emulator-5554 ./gradlew :core:ledger:connectedDebugAndroidTest \\"
echo "      -Pandroid.testInstrumentationRunnerArguments.class=com.midnight.kuira.core.ledger.e2e.SendNightTransferE2ETest"
