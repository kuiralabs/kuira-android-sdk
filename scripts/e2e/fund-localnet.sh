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

# ─── Which CLI, and which localnet ───
#
# Every on-chain `mn` call below PINS its endpoints, the same way sdk/midnight-sdk/run-sdk-e2e.sh
# does. Two reasons, both observed:
#   - With more than one local stack up, mn REFUSES to guess and exits: "Several local Midnight
#     stacks are running (node 9944, 29944; …), and mn won't guess which one to use." That is what
#     aborted the gate: this script failed in 8s and all the caller saw was "fund-localnet.sh
#     failed". Pinning all three endpoints satisfies the check (so does MN_NO_LOCAL_DETECT=1).
#   - With a single stack it can still resolve from saved config and fund a DIFFERENT chain than
#     the device reads, which "succeeds" here and fails much later in the suite.
# The flags go on every on-chain call, including the ones whose --help does not list them:
# `airdrop` honours --node/--indexer-ws/--proof-server despite documenting none of them (verified
# by pinning a dead port and watching it dial that port), and balance/dust accept the full set and
# ignore what they don't use. One set for all of them beats a per-command subset nobody maintains.
MN_NODE="${MN_NODE:-ws://127.0.0.1:9944}"
MN_INDEXER_WS="${MN_INDEXER_WS:-ws://127.0.0.1:8088/api/v4/graphql/ws}"
MN_PROOF_SERVER="${MN_PROOF_SERVER:-http://127.0.0.1:6300}"
MN_EP=(--node "$MN_NODE" --indexer-ws "$MN_INDEXER_WS" --proof-server "$MN_PROOF_SERVER")

# The CLI is a variable because mn is ledger-VERSIONED: a 0.6.x build talks only to ledger 9, a
# 0.5.x build only to ledger 8, and it refuses rather than mis-signing. Set MN_CLI to point this
# script at a build matching the stack (it is word-split, so a launcher with args works):
#   MN_CLI="npx -y midnight-wallet-cli@0.5.8" scripts/e2e/fund-localnet.sh
IFS=' ' read -r -a MN <<< "${MN_CLI:-mn}"

echo "==> Ensuring the canonical e2e wallet '$WALLET' exists"
# wallet info/generate are offline (key derivation only) — no endpoints to pin.
if ! "${MN[@]}" wallet info "$WALLET" >/dev/null 2>&1; then
  echo "    generating '$WALLET' from the canonical test mnemonic"
  "${MN[@]}" wallet generate "$WALLET" --network undeployed --mnemonic "$MNEMONIC"
fi

# The wallet MUST derive to the e2e FUNDED_ADDRESS, or the tests won't see its UTXOs.
INFO="$("${MN[@]}" wallet info "$WALLET" 2>/dev/null || true)"
if [[ "$INFO" != *"$EXPECTED_SUFFIX"* ]]; then
  echo "ERROR: '$WALLET' is not the e2e wallet (its address must end '$EXPECTED_SUFFIX')." >&2
  echo "       Regenerate it: mn wallet generate $WALLET --network undeployed --mnemonic \"abandon … art\"" >&2
  exit 1
fi

echo "==> Airdropping ${AIRDROP_NIGHT} NIGHT x${AIRDROP_COUNT} (separate UTXOs — covers single- AND multi-input tests)"
for _ in $(seq 1 "$AIRDROP_COUNT"); do
  "${MN[@]}" airdrop "$AIRDROP_NIGHT" --wallet "$WALLET" "${MN_EP[@]}"
done

echo "==> Registering dust (idempotent — only registers NIGHT not already generating)"
# NOT `|| true`. An already-registered wallet exits 0 ("dust already available"), so a
# non-zero exit here is a real failure — and swallowing it meant funding "succeeded" with
# no dust, which surfaces much later as dust-dependent e2e tests failing on a precondition
# with no hint that provisioning was the cause.
if ! "${MN[@]}" dust register --wallet "$WALLET" "${MN_EP[@]}"; then
  echo "ERROR: dust registration failed for '$WALLET'. Dust-dependent e2e tests cannot pass" >&2
  echo "       until this succeeds; re-run once the localnet is healthy." >&2
  exit 1
fi

echo "==> Resulting state"
"${MN[@]}" balance --wallet "$WALLET" "${MN_EP[@]}" || true
"${MN[@]}" dust status --wallet "$WALLET" "${MN_EP[@]}" || true

echo "==> e2e wallet funded. The suite no longer needs a manual airdrop:"
echo "    ANDROID_SERIAL=emulator-5554 ./gradlew :core:ledger:connectedDebugAndroidTest"
