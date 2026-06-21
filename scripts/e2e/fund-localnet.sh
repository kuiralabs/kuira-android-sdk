#!/usr/bin/env bash
#
# Fund the canonical localnet test wallet for the ENTIRE core:ledger e2e suite — one pass.
#
# Every canonical-wallet e2e test (RealDustFeePaymentTest, DustPaymentE2ETest,
# EndToEndTransactionTest, …) derives the SAME wallet from the standard BIP-39
# test mnemonic ("abandon … art"); its index-0 address is the shared FUNDED_ADDRESS
# (mn_addr_undeployed1…pqauuvtx). So funding that ONE wallet unblocks all of them. This kills the
# manual "airdrop before every run" ritual — it's idempotent, so it's safe to call from CI / a
# Gradle setup task before the e2e run.
#
# Needs: localnet running (`mn localnet …`) + `mn` on PATH (midnight-wallet-cli).
# Usage: scripts/e2e/fund-localnet.sh
#
set -euo pipefail

WALLET="kuiratest"
# The canonical BIP-39 test mnemonic — derives to FUNDED_ADDRESS at m/44'/2400'/0'/0/0.
MNEMONIC="abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon art"
EXPECTED_SUFFIX="pqauuvtx"   # tail of the e2e FUNDED_ADDRESS — a derivation-drift guard
AIRDROP_NIGHT=2              # SMALL per airdrop — the send diagnostics pick the n SMALLEST UTXOs
AIRDROP_COUNT=8              # each airdrop = one separate UTXO (verified 1:1); fund generously so
                            # ≥3 small coins survive the suite's self-sends (which consolidate)

echo "==> Ensuring the canonical e2e wallet '$WALLET' exists"
if ! mn wallet info "$WALLET" >/dev/null 2>&1; then
  echo "    generating '$WALLET' from the canonical test mnemonic"
  mn wallet generate "$WALLET" --network undeployed --mnemonic "$MNEMONIC"
fi

# The wallet MUST derive to the e2e FUNDED_ADDRESS, or the tests won't see its UTXOs.
INFO="$(mn wallet info "$WALLET" 2>/dev/null || true)"
if [[ "$INFO" != *"$EXPECTED_SUFFIX"* ]]; then
  echo "ERROR: '$WALLET' is not the e2e wallet (its address must end '$EXPECTED_SUFFIX')." >&2
  echo "       Regenerate it: mn wallet generate $WALLET --network undeployed --mnemonic \"abandon … art\"" >&2
  exit 1
fi

echo "==> Airdropping ${AIRDROP_NIGHT} NIGHT x${AIRDROP_COUNT} (separate UTXOs — covers single- AND multi-input tests)"
for _ in $(seq 1 "$AIRDROP_COUNT"); do
  mn airdrop "$AIRDROP_NIGHT" --wallet "$WALLET"
done

echo "==> Registering dust (idempotent — only registers NIGHT not already generating)"
mn dust register --wallet "$WALLET" || true

echo "==> Resulting state"
mn balance --wallet "$WALLET" || true
mn dust status --wallet "$WALLET" || true

echo "==> e2e wallet funded. The suite no longer needs a manual airdrop:"
echo "    ANDROID_SERIAL=emulator-5554 ./gradlew :core:ledger:connectedDebugAndroidTest"
