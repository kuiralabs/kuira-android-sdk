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
#   - no emulator connected, or attached but unauthorized        -> the run cannot start
#
# Run it, then walk away. A green finish is the sign-off an SDK change needs.
#
# Usage:
#   scripts/e2e/sdk-test-gate.sh [emulator-serial]
#   (serial: the argument, else $ANDROID_SERIAL, else the first `adb devices` entry)
#
# Prereq you must do yourself: `mn localnet up` (this script verifies it, doesn't start it).

set -euo pipefail
cd "$(dirname "$0")/../.."   # repo root

# Honour an exported ANDROID_SERIAL before falling back to "first device attached". Every adb and
# gradle call below already respects that variable, so ignoring it here meant the caller could ask
# for one device and silently have the whole ~30-min run execute against another — which is what
# happened with a phone and an emulator both attached: the phone sorts first, so an explicit
# ANDROID_SERIAL=emulator-5554 ran on the phone and the log said so in a line easy to miss.
SERIAL="${1:-${ANDROID_SERIAL:-$(adb devices | awk '/\tdevice$/{print $1; exit}')}}"
if [ -z "$SERIAL" ]; then
    # Name the actual state. An unauthorized phone IS attached and needs a tap on its screen,
    # which is a different fix from "connect a device", and the ~30-min run should not start
    # only to discover this.
    STATES="$(adb devices | tail -n +2 | awk 'NF {print $2}' | sort -u | tr '\n' ' ')"
    case "$STATES" in
        *unauthorized*) echo "GATE ABORTED: the attached device is UNAUTHORIZED. Unlock it and tap Allow on the USB debugging prompt." >&2 ;;
        *offline*)      echo "GATE ABORTED: the attached device is OFFLINE. Re-plug it, or run adb kill-server." >&2 ;;
        "")             echo "GATE ABORTED: no Android device attached." >&2 ;;
        *)              echo "GATE ABORTED: no usable device; adb reports state(s): $STATES" >&2 ;;
    esac
    exit 1
fi

# `mn` is ledger-versioned (0.5.x drives ledger 8, 0.6.x ledger 9) and refuses a mismatched
# chain. Exported here so ONE value steers every leg: this script, prepare-localnet-e2e.sh,
# fund-localnet.sh and the bboardSetup Gradle task all read it.
#   MN_CLI="npx -y midnight-wallet-cli@0.5.8" scripts/e2e/sdk-test-gate.sh
export MN_CLI="${MN_CLI:-mn}"
IFS=' ' read -r -a MN <<< "$MN_CLI"

step() { printf '\n\033[1m==> %s\033[0m\n' "$1"; }
fail() { printf '\033[31mGATE ABORTED: %s\033[0m\n' "$1" >&2; exit 1; }

# ── What counts as a pass ─────────────────────────────────────────────────────
# Not "BUILD SUCCESSFUL". That string is not evidence that anything ran: an UP-TO-DATE task
# prints it having executed nothing, and a task whose inputs Gradle cannot see goes UP-TO-DATE
# exactly when the thing it checks has changed. A gate that greps for it can sign off on a run
# that checked nothing -- the failure this script exists to prevent.
#
# Assert on the result FILES instead, with three conditions a green needs to mean anything:
#   - every module that HAS tests produced results (derived from the test source dir existing,
#     so a module that silently did not run is visible instead of invisible)
#   - those results were written during THIS run (a dead module leaves yesterday's green XML
#     behind, and summing that is how removed modules keep reporting coverage)
#   - failures = errors = skipped = 0, since a skip reads the same as a pass in a count
# Force a real run. A gate is a "prove it now" pass, so UP-TO-DATE is not acceptable here --
# and the obvious flag does not do it: `--rerun` applies only to tasks named with an explicit
# path, not to ones matched by name across subprojects, so `./gradlew testDebugUnitTest --rerun`
# left 434 tasks up-to-date and re-ran exactly one. Measured, not assumed.
#
# Removing the tasks' declared OUTPUTS does work -- Gradle marks a task out-of-date when its
# outputs are gone, and all 14 test tasks then re-ran. It also clears result XML left behind by
# modules that no longer exist in the build, which otherwise keeps being counted as coverage.
clear_results() {   # $1 = result dir path under each module's build/
    # Refuse to delete anything unless we are standing in this repo: an rm -rf driven by find
    # should not be one `cd` away from somewhere else.
    [ -f settings.gradle.kts ] && [ -f gradlew ] || fail "clear_results: not at the repo root"
    find . -path "*/build/$1" -type d -not -path './.claude/*' -exec rm -rf {} + 2>/dev/null || true
}

assert_results() {   # $1 label  $2 src dir marking scope  $3 result glob  $4 epoch  [$5 one module]
    python3 - "$1" "$2" "$3" "$4" "${5:-}" <<'PYEOF'
import glob, io, os, re, sys

label, srcdir, pattern, since = sys.argv[1], sys.argv[2], sys.argv[3], float(sys.argv[4]) - 5
only = sys.argv[5] if len(sys.argv) > 5 else ""
active = [l.split('"')[1].lstrip(":").replace(":", "/")
          for l in io.open("settings.gradle.kts", encoding="utf-8") if l.startswith("include(")]
expected = [m for m in active if os.path.isdir(os.path.join(m, srcdir))]
if only:
    # Scope the assertion to one module: the isolated-tier leg runs only that module, so
    # demanding fresh results from every other one would fail on results it never touched.
    expected = [m for m in expected if m == only]
    if not expected:
        print(f"GATE-ASSERT-FAILED: {only} is not an active module with a {srcdir} directory")
        sys.exit(1)

tot = skipped = failures = errors = 0
ran, missing, unparsed = [], [], []
for mod in expected:
    fresh = [f for f in glob.glob(os.path.join(mod, pattern), recursive=True)
             if os.path.getmtime(f) >= since]
    if not fresh:
        missing.append(mod); continue
    t = sk = fl = er = 0
    for f in fresh:
        head = io.open(f, encoding="utf-8", errors="replace").read(4000)
        # Parse each attribute on its own. A single ordered pattern does NOT work: unit results
        # are written tests/skipped/failures/errors, instrumented results tests/failures/errors/
        # skipped. The ordered regex matched only the unit leg, so the instrumented leg summed
        # zeros for every module and reported "wrote results containing zero tests" while
        # sdk/midnight-sdk had in fact run 4 tests and failed 3. It failed closed, never green,
        # but it named the wrong cause and hid the real counts.
        vals = {k: re.search(r'\b%s="(\d+)"' % k, head) for k in
                ("tests", "skipped", "failures", "errors")}
        if vals["tests"] is None:
            # Never score an unreadable file as a clean zero -- that is how a silent miss looks
            # exactly like a passing module.
            unparsed.append(f)
            continue
        t += int(vals["tests"].group(1))
        sk += int(vals["skipped"].group(1)) if vals["skipped"] else 0
        fl += int(vals["failures"].group(1)) if vals["failures"] else 0
        er += int(vals["errors"].group(1)) if vals["errors"] else 0
    ran.append((mod, t, sk, fl, er))
    tot += t; skipped += sk; failures += fl; errors += er

for mod, t, sk, fl, er in ran:
    flag = "" if (sk == 0 and fl == 0 and er == 0 and t > 0) else "   <-- PROBLEM"
    print(f"    {mod:24} tests={t:4} skipped={sk} failures={fl} errors={er}{flag}")
print(f"    {label}: {len(ran)}/{len(expected)} modules, tests={tot} "
      f"skipped={skipped} failures={failures} errors={errors}")

problems = []
if not expected:
    problems.append(f"no module has a {srcdir} directory -- the scope derivation is broken")
if missing:
    problems.append(f"no fresh results from: {', '.join(missing)}")
if unparsed:
    problems.append(f"result file(s) with no parseable testsuite counts: {', '.join(unparsed)}")
empty = [m for m, t, _, _, _ in ran if t == 0]
if empty:
    # A test source dir that yields no tests is dead weight or a discovery problem; either way
    # naming it is the difference between a diagnosable failure and a mysterious one.
    problems.append(f"wrote results containing zero tests: {', '.join(empty)}")
if failures or errors:
    problems.append(f"{failures} failure(s), {errors} error(s)")
if skipped:
    problems.append(f"{skipped} skipped -- a skip reads as a pass, so the gate counts it red")
if problems:
    print("GATE-ASSERT-FAILED: " + "; ".join(problems))
    sys.exit(1)
PYEOF
}


# ── Pre-flight ────────────────────────────────────────────────────────────────
# bboard-specific cleanup the deploy task can't do for itself, then the shared localnet
# + device prep (health, funding, dust, proving keys) is delegated to the reusable
# prepare-localnet-e2e.sh so there's ONE source of truth for what the e2e suite needs.
step "Pre-flight 1/3 — kill stale 'mn serve' (bboardSetup starts its own)"
STALE=$(pgrep -f "mn serve" || true)
if [ -n "$STALE" ]; then echo "$STALE" | xargs kill -9 2>/dev/null || true; echo "  killed: $STALE"; else echo "  none"; fi

step "Pre-flight 2/3 — clear the 'dev' CLI wallet cache (bboardSetup's wallet)"
# Through MN_CLI, not a bare `mn`: this must clear the cache that bboardSetup's CLI will read.
"${MN[@]}" cache clear --wallet dev 2>/dev/null | tail -1 || echo "  (no dev wallet yet — bboardSetup will create it)"

step "Pre-flight 3/3 — prepare localnet + device (health, funding, dust, proving keys)"
scripts/e2e/prepare-localnet-e2e.sh "$SERIAL" || fail "localnet prep failed — see output above"

# ── The gate ──────────────────────────────────────────────────────────────────
# --continue so one module's failure doesn't hide the state of the rest. The assertion decides
# pass or fail, so letting gradle finish red here is fine and more informative.
#
# :sdk:contract-plugin is a JVM Gradle-plugin module, so its task is `test`, not
# testDebugUnitTest -- which is why its 56 tests were never part of this gate until now.
step "Unit tests — ALL modules"
clear_results "test-results"
UNIT_START=$(date +%s)
./gradlew testDebugUnitTest :sdk:contract-plugin:test --continue --console=plain 2>&1 \
  | tee /tmp/gate-unit.log | tail -3 || true
assert_results "unit" "src/test" "build/test-results/*/TEST-*.xml" "$UNIT_START" \
  || fail "unit tests did not pass clean — see /tmp/gate-unit.log and the counts above"

# The isolated send tier cannot pass under a plain connected run, BY DESIGN. Each of its tests
# needs a fresh wallet funded per @Test by a host-side servicer, and asserts so rather than
# skipping: "Host harness must fund … run the suite via run-sdk-e2e.sh". Only that script
# provides it (it tails logcat for KUIRA_FUND_REQ and airdrops on demand). Running it as an
# ordinary connected test produced 3 guaranteed failures, so the gate runs it where it works and
# keeps it IN the sign-off rather than dropping it.
ISOLATED_TIER="com.midnight.kuira.sdk.e2e.SdkSendNightIsolatedE2ETest,com.midnight.kuira.sdk.e2e.SdkSendShieldedIsolatedE2ETest"

step "Instrumented 1/2 — ALL modules on $SERIAL, except the host-funded tier (the ~30-min leg)"
clear_results "outputs/androidTest-results"
INSTR_START=$(date +%s)
ANDROID_SERIAL="$SERIAL" ./gradlew connectedDebugAndroidTest --continue --console=plain \
  -Pandroid.testInstrumentationRunnerArguments.notClass="$ISOLATED_TIER" 2>&1 \
  | tee /tmp/gate-instr.log | tail -5 || true
assert_results "instrumented" "src/androidTest" "build/outputs/androidTest-results/**/TEST-*.xml" "$INSTR_START" \
  || fail "instrumented tests did not pass clean — see /tmp/gate-instr.log and the counts above"

step "Instrumented 2/2 — host-funded isolated send tier (per-test funding servicer)"
# Only this module's results are replaced, and the leg above has already been asserted, so
# overwriting them here loses no signal.
rm -rf sdk/midnight-sdk/build/outputs/androidTest-results
TIER_START=$(date +%s)
# It takes no positional args and cd's to the repo root itself; the device comes from
# ANDROID_SERIAL, which both its adb calls and gradle honour.
TEST_CLASS="$ISOLATED_TIER" ANDROID_SERIAL="$SERIAL" sdk/midnight-sdk/run-sdk-e2e.sh 2>&1 \
  | tee /tmp/gate-tier.log | tail -5 || true
assert_results "isolated-tier" "src/androidTest" "build/outputs/androidTest-results/**/TEST-*.xml" \
  "$TIER_START" "sdk/midnight-sdk" \
  || fail "the isolated send tier did not pass clean — see /tmp/gate-tier.log and /tmp/sdk-e2e-fund.log"

printf '\n\033[32m════ SDK TEST GATE GREEN — unit + instrumented, all modules ════\033[0m\n'
