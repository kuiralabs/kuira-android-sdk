#!/usr/bin/env bash
#
# One-shot prep for EVERY localnet-dependent instrumented test — so they RUN instead of
# assumeTrue-skipping. Idempotent; safe to call from CI or the SDK test gate before the run.
#
# The instrumented e2e suite depends on four things the test APKs can't provision themselves:
#
#   1. A fully-up localnet: node (:9944), indexer (:8088), proof-server (:6300). Deployed
#      automatically via `mn localnet up` when the ports aren't answering. Health is judged by
#      live port probes, never `mn localnet status` (which reports stale "running" state).
#   2. The canonical "abandon … art" wallet, funded + dust-registered (fund-localnet.sh).
#      Unblocks the core:ledger e2e suite (RealDustFeePaymentTest, DustPaymentE2ETest,
#      EndToEndTransactionTest) — all derive the same FUNDED_ADDRESS.
#   3. Wallet proving keys (zswap + dust + BLS params) adb-pushed to the device, so the
#      LOCAL-proving SdkRegistrationE2ETest can prove on-device (installFromLocalTmp reads
#      /data/local/tmp/wallet_keys/{zswap,dust} + /data/local/tmp/bboard_keys/bls_*).
#   4. An attached emulator/device.
#
# What each test still needs beyond this script:
#   - SdkSendNightIsolatedE2ETest (REMOTE proving): per-test funding servicer — run via
#     sdk/midnight-sdk/run-sdk-e2e.sh (fresh wallet per @Test, funded on demand).
#   - core:compact-engine bboard tests: per-test contract deploys — the Gradle `bboardSetup`
#     task (wired as a connected-test dependsOn) handles those.
#
# Usage:
#   scripts/e2e/prepare-localnet-e2e.sh [emulator-serial]
#     serial defaults to the first `adb devices` entry.
#   WALLET_KEYS_DIR=/path/to/wallet-keys  scripts/e2e/prepare-localnet-e2e.sh   # override key source
#
# Prereqs: Docker running, `mn` + `adb` on PATH, an emulator/device attached. The localnet
# itself is brought up automatically (`mn localnet up`) when its ports aren't answering.
#
# One prereq this script can only CHECK, never fix: `mn` is ledger-versioned (0.5.x talks to
# ledger 8, 0.6.x to ledger 9) and refuses a mismatched chain rather than mis-signing. Step 2
# fails fast when the CLI and the running chain disagree -- see MN_CLI below.

set -euo pipefail
cd "$(dirname "$0")/../.."   # repo root

SERIAL="${1:-$(adb devices | awk '/\tdevice$/{print $1; exit}')}"
# The wallet proving keys are vendored in the Kicks example (the offline bundle, ~33MB).
WALLET_KEYS_DIR="${WALLET_KEYS_DIR:-examples/midnight-kicks/app/src/main/assets/wallet-keys}"
# Must match WALLET in fund-localnet.sh — that script creates it, this one checks its dust.
E2E_WALLET="kuiratest"

# ─── Which CLI, and which localnet ───
#
# EXPORTED, so fund-localnet.sh pins the same stack from the same values -- one place decides.
# Every on-chain `mn` call pins all three endpoints: with more than one local stack up, mn refuses
# to guess ("Several local Midnight stacks are running … and mn won't guess which one to use") and
# with one stack it can still resolve a DIFFERENT chain than the device reads. Pinning all three
# satisfies the check; so does MN_NO_LOCAL_DETECT=1.
export MN_NODE="${MN_NODE:-ws://127.0.0.1:9944}"
export MN_INDEXER_WS="${MN_INDEXER_WS:-ws://127.0.0.1:8088/api/v4/graphql/ws}"
export MN_PROOF_SERVER="${MN_PROOF_SERVER:-http://127.0.0.1:6300}"
MN_EP=(--node "$MN_NODE" --indexer-ws "$MN_INDEXER_WS" --proof-server "$MN_PROOF_SERVER")

# The curl health probes below DERIVE their URLs from those same three values rather than keeping
# their own copy. They used to be hardcoded to localhost:9944/8088/6300, so overriding MN_NODE
# health-checked one chain while funding another -- the very "funds a DIFFERENT chain than the
# device reads" failure described above, reintroduced one layer down. Caught by a positive control
# that passed the version check against a ledger-9 stack while reporting the ledger-8 node's
# version. Host ports map 1:1 to what the emulator reaches at 10.0.2.2:<port>.
to_http() { printf '%s' "$1" | sed -e 's|^ws://|http://|' -e 's|^wss://|https://|'; }
NODE_URL="$(to_http "$MN_NODE")"
INDEXER_URL="$(to_http "${MN_INDEXER_WS%/ws}")"
PROOF_SERVER_URL="$(to_http "$MN_PROOF_SERVER")"

# `mn` is ledger-versioned, so the CLI is a variable: point this at a build matching the stack.
#   MN_CLI="npx -y midnight-wallet-cli@0.5.8" scripts/e2e/prepare-localnet-e2e.sh
export MN_CLI="${MN_CLI:-mn}"
IFS=' ' read -r -a MN <<< "$MN_CLI"

step() { printf '\n\033[1m==> %s\033[0m\n' "$1"; }
ok()   { printf '  \033[32m✓\033[0m %s\n' "$1"; }
warn() { printf '  \033[33m⚠\033[0m %s\n' "$1"; }
die()  { printf '\033[31mPREP FAILED: %s\033[0m\n' "$1" >&2; exit 1; }

# ── 1. Localnet: detect on launch, and deploy if not properly running ─────────
step "1/5 — localnet up + healthy ($NODE_URL, $INDEXER_URL, $PROOF_SERVER_URL)"

# Probe the REAL ports, never `mn localnet status` — it reports a stale "running" even when
# the containers are gone (observed: status said running with an empty `docker ps`). A live
# RPC call is the only truth. These are exactly the endpoints the emulator hits at 10.0.2.2.
probe_node()    { curl -sf -m 5 -X POST "$NODE_URL"    -H 'Content-Type: application/json' \
                    -d '{"jsonrpc":"2.0","id":1,"method":"system_health","params":[]}' >/dev/null 2>&1; }
probe_indexer() { curl -sf -m 5 -X POST "$INDEXER_URL" -H 'Content-Type: application/json' \
                    -d '{"query":"query { block { height } }"}' >/dev/null 2>&1; }
probe_proof()   {
  # This used to be `... -w '%{http_code}' | grep -qE '^[0-9]'`, which 000 satisfies -- and 000
  # is exactly what curl reports when nothing answers at all. So the probe returned success for
  # a dead proof server and the warning below could never fire. Verified against a closed port.
  local code
  code="$(curl -s -m 5 -o /dev/null -w '%{http_code}' "$PROOF_SERVER_URL" 2>/dev/null || true)"
  case "$code" in ""|000) return 1 ;; esac
  return 0
}

# node + indexer are the hard requirements for the on-chain suite.
localnet_up() { probe_node && probe_indexer; }

if localnet_up; then
  ok "localnet already up + healthy"
else
  warn "localnet not fully up — deploying ('mn localnet up' brings up node + indexer + proof-server)…"
  if ! mn localnet up >/tmp/prep-localnet-up.log 2>&1; then
    warn "'mn localnet up' failed — removing conflicting containers and retrying…"
    mn localnet clean >>/tmp/prep-localnet-up.log 2>&1 || true
    mn localnet up    >>/tmp/prep-localnet-up.log 2>&1 \
      || die "'mn localnet up' failed even after clean — see /tmp/prep-localnet-up.log (and 'mn localnet logs')."
  fi
  # `up` blocks until the first block, but re-probe to CONFIRM the ports actually answer
  # before we depend on them (a container can be 'started' seconds before it serves).
  for _ in $(seq 1 30); do localnet_up && break; sleep 2; done
  localnet_up || die "localnet deployed but node/indexer not answering after 60s — see 'mn localnet logs'."
  ok "localnet deployed + healthy"
fi

# The proof server is required, not optional. It used to be a warning because the
# REMOTE-proving tier would assumeTrue-skip without it; nothing skips any more, so an absent
# proof server means that tier FAILS, and letting prep report success guarantees a red run
# ~30 minutes later with the cause this far behind it.
probe_proof && ok "proof-server reachable" \
  || die "proof-server not answering at $PROOF_SERVER_URL. The REMOTE-proving tier will FAIL without it (it no longer skips). Start it, or point PROOF_SERVER_URL at a live one."

# ── 2. Record which CLI is driving which chain ────────────────────────────────
step "2/5 — CLI / chain pairing"
# INFORMATIONAL on purpose, not a gate -- and that distinction cost a rewrite, so it is worth
# stating. `mn` is ledger-versioned: 0.5.x drives ledger 8, 0.6.x drives ledger 9, and the wrong
# pairing cannot be fixed by provisioning, only by swapping the CLI. The obvious move is to probe
# it here and abort. There is no command that gives that verdict in BOTH builds:
#   - 0.6.x refuses with a named error ("undeployed is on ledger 8 … this build supports ledger 9
#     only"), and its `inspect-cost` reads the chain's live ledger parameters.
#   - 0.5.x has no such guard; it fails opaquely ("Wallet sync timed out"), and its `inspect-cost`
#     is computed from LedgerParameters.initialParameters() -- static. Measured: it SUCCEEDS
#     against dead ports. A gate built on it would pass unconditionally under 0.5.x, which is
#     worse than no gate, because it would read as a verified pairing.
# So the verdict stays where it is unavoidable and immediate: the first airdrop in step 4, ~8s in,
# before any state is written. What this step adds is the pairing in the log, so that failure is
# legible instead of a bare "fund-localnet.sh failed" -- which is exactly how it presented.
LEDGER_VERSION="$(curl -sf -m 5 -X POST "$NODE_URL" -H 'Content-Type: application/json' \
    -d '{"jsonrpc":"2.0","id":1,"method":"midnight_ledgerVersion","params":[]}' 2>/dev/null \
    | sed -n 's/.*"result": *"\([^"]*\)".*/\1/p')"
MN_VERSION="$("${MN[@]}" --version 2>/dev/null | tail -1 | tr -d ' \r')"
ok "CLI '$MN_CLI' (version ${MN_VERSION:-unknown}) -> chain ledger ${LEDGER_VERSION:-unknown}"
echo "      (0.5.x drives ledger 8, 0.6.x drives ledger 9 -- step 4 is where a mismatch fails)"

# ── 3. Emulator connected ─────────────────────────────────────────────────────
step "3/5 — emulator/device connected"
[ -n "$SERIAL" ] || die "no emulator/device connected (adb devices)."
ok "using $SERIAL"

# ── 4. Canonical wallet funded + dust ─────────────────────────────────────────
step "4/5 — fund the canonical e2e wallet + register dust"
if ! scripts/e2e/fund-localnet.sh >/tmp/prep-fund.log 2>&1; then
  # Show the CLI's own last words. This is where a CLI/chain ledger mismatch lands, and the
  # caller previously saw only the log's path -- a 30-minute gate aborting on a one-line cause
  # it did not print.
  sed $'s/\033\\[[0-9;]*m//g' /tmp/prep-fund.log | tr '\r' '\n' \
    | grep -vE '^[⠋⠙⠹⠸⠼⠴⠦⠧⠇⠏] ' | grep -vE '^[[:space:]]*$' | tail -6 | sed 's/^/      /'
  die "funding failed with CLI '$MN_CLI' (${MN_VERSION:-unknown}) against chain ledger ${LEDGER_VERSION:-unknown}. If those disagree (0.5.x = ledger 8, 0.6.x = ledger 9), set MN_CLI to a matching build, e.g. MN_CLI=\"npx -y midnight-wallet-cli@0.5.8\". Full log: /tmp/prep-fund.log"
fi
# Read the MACHINE-READABLE form, not the pretty box. The human-readable block ("Dust
# Available: yes") is written to STDERR, while stdout carries only key=value lines -- so matching
# that label on stdout could never succeed, whatever the real dust state. It presented as the
# opposite of a fake pass: a precondition that aborted the gate while `dust status` plainly
# reported 1864 DUST across 28 registered UTXOs. `--json` puts the verdict on stdout as a single
# object, and dustAvailable is absent when the wallet is unregistered, so testing it for `true`
# is correct in both cases. Field confirmed present in 0.5.8 and 0.6.x.
dust_available() {
  "${MN[@]}" dust status --wallet "$E2E_WALLET" "${MN_EP[@]}" --json 2>/dev/null \
    | grep -qE '"dustAvailable"[[:space:]]*:[[:space:]]*true'
}
# Dust registration takes a few blocks to become spendable, so this polls rather than checking
# once. It used to warn and carry on, on the reasoning that dust-dependent tests would skip --
# they now FAIL, so an unmet precondition here is a guaranteed red run and belongs as an abort.
if dust_available; then
  ok "canonical wallet funded, dust available"
else
  echo "  dust registered but not spendable yet — waiting (up to 180s)…"
  for _ in $(seq 1 60); do dust_available && break; sleep 3; done
  dust_available \
    || die "dust still not available for '$E2E_WALLET' after 180s. Dust-dependent e2e tests will FAIL (they no longer skip) — see /tmp/prep-fund.log and 'mn dust status --wallet $E2E_WALLET'."
  ok "canonical wallet funded, dust available"
fi

# ── 5. Wallet proving keys on device (LOCAL-proving SdkRegistrationE2ETest) ────
step "5/5 — wallet proving keys → device (LOCAL proving)"
[ -d "$WALLET_KEYS_DIR/zswap" ] && [ -d "$WALLET_KEYS_DIR/dust" ] \
  || die "wallet keys not found at $WALLET_KEYS_DIR (expected zswap/ + dust/). Set WALLET_KEYS_DIR."

# Idempotent: skip the 33MB push if the marker key is already staged on the device.
if adb -s "$SERIAL" shell ls /data/local/tmp/wallet_keys/dust/spend.prover >/dev/null 2>&1; then
  ok "wallet keys already staged on $SERIAL — skipping push"
else
  echo "  pushing $(du -sh "$WALLET_KEYS_DIR" | cut -f1) of proving keys to $SERIAL (one-time)…"
  adb -s "$SERIAL" shell mkdir -p /data/local/tmp/wallet_keys /data/local/tmp/bboard_keys
  # zswap/* + dust/* under wallet_keys/; BLS params under bboard_keys/ (installFromLocalTmp's contract).
  adb -s "$SERIAL" push "$WALLET_KEYS_DIR/zswap" /data/local/tmp/wallet_keys/ >/dev/null
  adb -s "$SERIAL" push "$WALLET_KEYS_DIR/dust"  /data/local/tmp/wallet_keys/ >/dev/null
  [ -f "$WALLET_KEYS_DIR/version.txt" ] && \
    adb -s "$SERIAL" push "$WALLET_KEYS_DIR/version.txt" /data/local/tmp/wallet_keys/ >/dev/null
  bls_pushed=0
  for bls in "$WALLET_KEYS_DIR"/bls_*; do
    if [ -f "$bls" ]; then
      adb -s "$SERIAL" push "$bls" /data/local/tmp/bboard_keys/ >/dev/null
      bls_pushed=$((bls_pushed + 1))
    fi
  done
  # An unmatched glob leaves the literal pattern, so this loop could push NOTHING and still
  # report "staged". installFromLocalTmp reads these, so local proving would fail later with
  # nothing pointing back here.
  [ "$bls_pushed" -gt 0 ] \
    || die "no bls_* params found in $WALLET_KEYS_DIR. installFromLocalTmp needs them for LOCAL proving."
  ok "wallet proving keys staged ($bls_pushed BLS param files)"
fi

printf '\n\033[32m════ localnet e2e prep complete — dependent tests will RUN, not skip ════\033[0m\n'
