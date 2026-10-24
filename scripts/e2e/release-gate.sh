#!/usr/bin/env bash
#
# release-gate.sh — everything that must be green before an SDK version is published.
#
#   scripts/e2e/release-gate.sh [serial]
#   ANDROID_SERIAL=emulator-5554 scripts/e2e/release-gate.sh
#
# Two tiers, in this order:
#
#   1. The SDK gate (scripts/e2e/sdk-test-gate.sh) — unit + instrumented across every module.
#      Proves the SDK is internally sound.
#   2. The consumer tier — each example app rebuilt against the version under release, installed,
#      and driven through its journeys.
#
# WHY TIER 2 EXISTS. Tier 1 tests the modules IN TREE. It cannot catch anything that only appears
# once the artifacts are resolved from a repository and consumed by somebody else's build: a
# missing plugin marker, a transitive dependency that was `implementation` when it needed to be
# `api`, a behaviour change that breaks a workaround an app was written around. The example apps
# are the only place that happens, and before this script nothing ran them as a release
# precondition. alpha06 reached release candidate with all four apps still pinned to alpha05,
# meaning the published artifacts had never been exercised by a consumer at all.
#
# PREREQUISITES, all verified before anything long-running starts:
#   - localnet up (the SDK gate checks this itself)
#   - a device or emulator, honoured via $ANDROID_SERIAL
#   - the version under test published to mavenLocal (`./gradlew publishToMavenLocal`)
#
# NOTHING HERE SKIPS. An app that is configured but missing from disk, pinned to the wrong
# version, or failing to install is a FAILURE, not a quiet omission. A release gate that silently
# covers three of four apps is worse than one that covers none, because it reports green.
set -uo pipefail
cd "$(dirname "$0")/../.."

SERIAL="${1:-${ANDROID_SERIAL:-$(adb devices | awk '/\tdevice$/{print $1; exit}')}}"
[ -n "$SERIAL" ] || { echo "✗ no device. Attach one or pass a serial." >&2; exit 1; }
export ANDROID_SERIAL="$SERIAL"

VERSION="$(awk -F= '/^version=/{print $2}' gradle.properties)"
[ -n "$VERSION" ] || { echo "✗ could not read version from gradle.properties" >&2; exit 1; }

bold() { printf '\033[1m==> %s\033[0m\n' "$1"; }
ok()   { printf '  \033[32m✓\033[0m %s\n' "$1"; }
die()  { printf '  \033[31m✗ %s\033[0m\n' "$1" >&2; exit 1; }

# Each consumer: directory | package | launcher activity. Keep in step with scripts/release.sh,
# which bumps the very same set of repos.
CONSUMERS=(
  "$HOME/Development/example-bboard-android|com.midnight.example.bboard|.BBoardActivity"
)
# starter, midnight-tally and midnight-kicks join this list once they carry journeys; they are
# listed nowhere yet precisely because an app in this array with no journeys would fail the run,
# which is the correct behaviour and not something to paper over with an empty directory.

echo
bold "Release gate for $VERSION on $SERIAL"
echo

bold "Tier 0 — the version under test is in mavenLocal"
M2="$HOME/.m2/repository/io/github/kuiralabs"
[ -d "$M2/dapp-ui/$VERSION" ] || die "dapp-ui $VERSION is not in mavenLocal. Run ./gradlew publishToMavenLocal first."
for marker in contract localnet; do
  [ -d "$M2/$marker/io.github.kuiralabs.$marker.gradle.plugin/$VERSION" ] \
    || die "the $marker Gradle plugin MARKER is missing at $VERSION. A dApp's plugins{} block cannot resolve without it, even though the library jar is present."
done
STALE="$(find "$M2" -maxdepth 2 -type d -name '*-SNAPSHOT' | head -5)"
[ -z "$STALE" ] || { echo "$STALE" | sed 's/^/    /'; die "SNAPSHOT artifacts in mavenLocal can shadow the release under test — remove them."; }
ok "$VERSION present, both plugin markers published, no SNAPSHOTs"
echo

bold "Tier 1 — SDK gate (unit + instrumented, all modules)"
scripts/e2e/sdk-test-gate.sh "$SERIAL" || die "SDK gate failed — fix that before looking at the apps"
echo

bold "Tier 2 — example apps against $VERSION"
for entry in "${CONSUMERS[@]}"; do
    IFS='|' read -r dir pkg activity <<< "$entry"
    name="$(basename "$dir")"
    echo
    bold "  $name"
    [ -d "$dir" ] || die "$name is not checked out at $dir"
    [ -d "$dir/journeys" ] || die "$name has no journeys/ — it would install and verify nothing"

    # The pin must already match: bumping it here would make the gate test something the repo does
    # not actually declare, and scripts/release.sh is what owns that bump.
    if ! grep -rq "$VERSION" "$dir"/app/build.gradle.kts "$dir"/gradle/libs.versions.toml 2>/dev/null; then
        die "$name is not pinned to $VERSION. Bump it (scripts/release.sh $VERSION) before gating."
    fi
    ok "pinned to $VERSION"

    ( cd "$dir" && ./gradlew :app:installDebug --refresh-dependencies --console=plain -q ) \
      || die "$name failed to build/install against $VERSION"
    ok "built and installed"

    scripts/e2e/run-journeys.sh "$dir/journeys" "$pkg" "$activity" "$SERIAL" \
      || die "$name journeys failed"
done

echo
printf '\033[32m════ RELEASE GATE GREEN — %s is safe to publish ════\033[0m\n' "$VERSION"
