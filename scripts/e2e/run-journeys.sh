#!/usr/bin/env bash
#
# run-journeys.sh — run an app's UI journeys against a device, and fail if any assertion breaks.
#
#   scripts/e2e/run-journeys.sh <journeys-dir> <package> <activity> [serial]
#
# e.g. scripts/e2e/run-journeys.sh \
#        ~/Development/example-bboard-android/journeys \
#        com.midnight.example.bboard .BBoardActivity emulator-5554
#
# WHY THIS EXISTS, and why it is not instrumented tests. The example apps are the only place the
# PUBLISHED artifacts get exercised the way a consumer uses them, and two of the four (the
# quickstart starter, and midnight-tally) have no test infrastructure at all — no androidTest
# source set, no runner, no dependencies. A journey needs none of that: it drives the installed
# APK from outside through the accessibility tree, so an app with zero test wiring can still be
# gated. That is the difference between a release gate that covers every consumer and one that
# covers only the apps somebody already set up.
#
# WHY THE ACCESSIBILITY TREE AND NOT SCREENSHOTS. The SDK sets FLAG_SECURE, so `android screen
# capture` returns a pure black frame for every Kuira screen — vision-based checking silently
# sees nothing. `android layout` reads through it. Any journey tooling for these apps has to go
# through the tree.
#
# WHAT A JOURNEY LOOKS LIKE. XML, per the Android CLI's journeys format:
#
#   <journey name="…">
#     <description>…why this exists, and what regression it pins…</description>
#     <actions>
#       <action>Verify the screen shows "BBOARD"</action>
#       <action>Verify the screen does NOT show "LOCKED"</action>
#     </actions>
#   </journey>
#
# This runner evaluates the MECHANICAL subset — `Verify the screen shows "X"` and `Verify the
# screen does NOT show "X"` — which is what a regression assertion needs and what can run
# unattended in CI. Richer natural-language actions are left to an agent evaluating the same file;
# the format is shared deliberately so one journey serves both. An action this runner does not
# recognise is reported as UNSUPPORTED and fails the run rather than being silently passed over:
# a skipped assertion reads exactly like a passing one, which is the failure mode the SDK gate
# already had to be fixed for twice.
set -uo pipefail

JOURNEY_DIR="${1:?usage: run-journeys.sh <journeys-dir> <package> <activity> [serial]}"
PACKAGE="${2:?missing package}"
ACTIVITY="${3:?missing activity}"
SERIAL="${4:-${ANDROID_SERIAL:-}}"

command -v android >/dev/null 2>&1 || { echo "✗ the 'android' CLI is not on PATH" >&2; exit 1; }
[ -d "$JOURNEY_DIR" ] || { echo "✗ no journeys directory at $JOURNEY_DIR" >&2; exit 1; }

DEV_ARGS=(); ADB_ARGS=()
if [ -n "$SERIAL" ]; then DEV_ARGS=(--device="$SERIAL"); ADB_ARGS=(-s "$SERIAL"); fi

adb "${ADB_ARGS[@]}" shell pm list packages 2>/dev/null | grep -q "^package:$PACKAGE$" \
  || { echo "✗ $PACKAGE is not installed${SERIAL:+ on $SERIAL}. Install it before gating it." >&2; exit 1; }

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

total=0; failed=0
for journey in "$JOURNEY_DIR"/*.xml; do
    [ -f "$journey" ] || continue
    name="$(basename "$journey")"

    # Every journey starts from a cold app. A journey that inherits the previous one's screen is
    # testing an accident of ordering, not the behaviour it names.
    adb "${ADB_ARGS[@]}" shell am force-stop "$PACKAGE" >/dev/null 2>&1
    adb "${ADB_ARGS[@]}" shell am start -n "$PACKAGE/$ACTIVITY" >/dev/null 2>&1
    sleep 8

    android layout "${DEV_ARGS[@]}" --pretty -o "$WORK/layout.json" >/dev/null 2>&1 \
      || { echo "✗ $name: could not read the layout tree" >&2; failed=$((failed+1)); total=$((total+1)); continue; }

    out="$(JOURNEY="$journey" LAYOUT="$WORK/layout.json" python3 -I "$(dirname "$0")/journey_eval.py")"
    rc=$?
    echo "$out"
    total=$((total+1))
    [ $rc -eq 0 ] || failed=$((failed+1))
done

echo
if [ "$total" -eq 0 ]; then
    echo "✗ no journeys found in $JOURNEY_DIR — nothing was verified" >&2
    exit 1
fi
if [ "$failed" -gt 0 ]; then
    echo "════ JOURNEYS FAILED — $failed of $total ════" >&2
    exit 1
fi
echo "════ JOURNEYS GREEN — $total/$total ════"
