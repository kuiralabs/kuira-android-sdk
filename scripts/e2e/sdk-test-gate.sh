#!/usr/bin/env bash
#
# SDK test gate — the mandatory full run after ANY change under sdk/ or core/.
#
# SDK code is consumed by every dApp, so a change isn't "done" until ALL modules pass
# unit AND instrumented tests. This script front-loads the environmental prep that has
# repeatedly failed the ~30-min run on preventable setup (not real regressions):
#
#   - stale `mn serve` holding a previous chain's wallet state  -> bboardSetup NETWORK_ERROR
#   - the `dev` CLI wallet cache pointing at old chain state     -> bboardSetup deploy fails
#   - the canonical e2e wallet ("abandon … art") with no dust    -> RealDustFeePaymentTest fails
#     ("No dust available (balance: 0)")
#   - no emulator connected                                       -> connected tests skip/err
#
# Run it, then walk away. A green finish is the sign-off an SDK change needs.
#
# Usage:
#   scripts/e2e/sdk-test-gate.sh [emulator-serial]
#   (serial defaults to the first `adb devices` entry)
#
# Prereq you must do yourself: `mn localnet up` (this script verifies it, doesn't start it).

set -euo pipefail
cd "$(dirname "$0")/../.."   # repo root

INDEXER_URL="http://localhost:8088/api/v3/graphql"
SERIAL="${1:-$(adb devices | awk '/\tdevice$/{print $1; exit}')}"

step() { printf '\n\033[1m==> %s\033[0m\n' "$1"; }
fail() { printf '\033[31mGATE ABORTED: %s\033[0m\n' "$1" >&2; exit 1; }

# ── Pre-flight ────────────────────────────────────────────────────────────────
step "Pre-flight 1/5 — localnet reachable"
curl -sf -m 5 -X POST "$INDEXER_URL" -H 'Content-Type: application/json' \
  -d '{"query":"query { block { height } }"}' >/dev/null \
  || fail "localnet indexer not reachable at $INDEXER_URL — run 'mn localnet up' first"
echo "  ok"

step "Pre-flight 2/5 — kill stale 'mn serve' (bboardSetup starts its own)"
STALE=$(pgrep -f "mn serve" || true)
if [ -n "$STALE" ]; then echo "$STALE" | xargs kill -9 2>/dev/null || true; echo "  killed: $STALE"; else echo "  none"; fi

step "Pre-flight 3/5 — clear the 'dev' CLI wallet cache (bboardSetup's wallet)"
mn cache clear --wallet dev 2>/dev/null | tail -1 || echo "  (no dev wallet yet — bboardSetup will create it)"

step "Pre-flight 4/5 — fund the canonical e2e wallet + dust (RealDustFeePaymentTest et al.)"
scripts/e2e/fund-localnet.sh >/tmp/gate-fund.log 2>&1 \
  || fail "fund-localnet.sh failed — see /tmp/gate-fund.log"
# Strip ANSI escapes before matching — mn colorizes its output, and a reset code sits
# between "Dust Available: " and "yes" (e.g. `Dust Available: \e[0myes`).
sed $'s/\033\\[[0-9;]*m//g' /tmp/gate-fund.log | grep -iE "Dust Available: *yes" >/dev/null \
  || fail "canonical wallet has no available dust after funding — see /tmp/gate-fund.log"
echo "  funded, dust available"

step "Pre-flight 5/5 — emulator connected"
[ -n "$SERIAL" ] || fail "no emulator/device connected (adb devices)"
echo "  using $SERIAL"

# ── The gate ──────────────────────────────────────────────────────────────────
step "Unit tests — ALL modules"
./gradlew testDebugUnitTest --console=plain 2>&1 | tee /tmp/gate-unit.log | tail -3
grep -q "BUILD SUCCESSFUL" /tmp/gate-unit.log || fail "unit tests failed — see /tmp/gate-unit.log"

step "Instrumented tests — ALL modules on $SERIAL (this is the ~30-min leg)"
ANDROID_SERIAL="$SERIAL" ./gradlew connectedDebugAndroidTest --console=plain 2>&1 \
  | tee /tmp/gate-instr.log | tail -5
grep -q "BUILD SUCCESSFUL" /tmp/gate-instr.log || fail "instrumented tests failed — see /tmp/gate-instr.log"

printf '\n\033[32m════ SDK TEST GATE GREEN — unit + instrumented, all modules ════\033[0m\n'
