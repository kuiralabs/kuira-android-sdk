#!/usr/bin/env bash
#
# release.sh — bump the Kuira SDK version across every consumer surface at once.
#
#   Usage:  scripts/release.sh 0.1.0-alpha03
#
# Rewrites the version PINS (Maven coordinates, the contract-plugin id version,
# the `kuira` version-catalog entry, and the mkdocs version vars) in every repo
# a fresh developer lands in. It does NOT touch READMEs' "latest version"
# prose — those should show the Maven Central badge instead, which is always
# live (see each README header).
#
# It does NOT commit or push. Review `git diff` in each repo, then push yourself.
#
# ┌─ IMPORTANT ────────────────────────────────────────────────────────────────┐
# │ Run this AFTER the version is published and resolvable on Maven Central.     │
# │ If you push these pins before then, a fresh clone resolves a version that    │
# │ 404s. (The SDK's own gradle.properties is the source of truth and is bumped  │
# │ separately, as part of the release commit.)                                  │
# └─────────────────────────────────────────────────────────────────────────────┘
#
# `examples/bboard` is intentionally skipped: it already derives the version
# from `-PkuiraVersion` (the publish workflow's acceptance test), so it can't
# drift and never needs a manual bump.
#
set -euo pipefail

VERSION="${1:?usage: scripts/release.sh <version>   e.g. 0.1.0-alpha03}"
if [[ ! "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.]+)?$ ]]; then
  echo "error: '$VERSION' is not a valid version (expected e.g. 0.1.0-alpha03)" >&2
  exit 1
fi

# Repo checkout locations — override via env if your layout differs.
# MONOREPO defaults to this repo (the script lives in scripts/); siblings default
# under $HOME/Development. Set KUIRA_* env vars for any other layout.
MONOREPO="${KUIRA_MONOREPO:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}"
STARTER="${KUIRA_STARTER:-$HOME/Development/kuira-starter-android}"
DOCS="${KUIRA_DOCS:-$HOME/Development/kuira-sdk-android}"
BBOARD="${KUIRA_BBOARD:-$HOME/Development/example-bboard-android}"
TALLY="${KUIRA_TALLY:-$HOME/Development/kuira-examples/midnight-tally}"

# Portable in-place sed (GNU vs BSD/macOS).
sedi() { if sed --version >/dev/null 2>&1; then sed -i -E "$@"; else sed -i '' -E "$@"; fi; }

# A semver-ish version with an optional pre-release suffix.
V='[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.]+)?'

bump() {
  local f="$1"
  [[ -f "$f" ]] || { echo "  (skip, not found) $f"; return 0; }
  # 1. Maven coordinates: io.github.kuiralabs:<artifact>:<version>
  sedi "s#(io\.github\.kuiralabs:[a-z0-9-]+:)${V}#\1${VERSION}#g" "$f"
  # 2. Contract Gradle plugin: id("io.github.kuiralabs.contract") version "<version>"
  sedi "s#(id\(\"io\.github\.kuiralabs\.contract\"\) version \")${V}#\1${VERSION}#g" "$f"
  # 3. Version catalog: kuira = "<version>"
  sedi "s#^(kuira = \")${V}#\1${VERSION}#" "$f"
  # 4. mkdocs macro vars
  sedi "s#^(  kuira_version: \")${V}#\1${VERSION}#" "$f"
  sedi "s#^(  kuira_contract_plugin_version: \")${V}#\1${VERSION}#" "$f"
  # 5. Starter "Pinned versions" table row:  | Kuira SDK | `<version>` ...
  sedi "s#(\\| Kuira SDK \\| \`)${V}#\1${VERSION}#" "$f"
  echo "  bumped $f"
}

echo "Bumping Kuira pins → ${VERSION}"
echo
bump "$STARTER/gradle/libs.versions.toml"
bump "$STARTER/app/build.gradle.kts"
bump "$STARTER/README.md"
bump "$DOCS/mkdocs.yml"
bump "$DOCS/README.md"
bump "$BBOARD/app/build.gradle.kts"
bump "$TALLY/gradle/libs.versions.toml"
bump "$TALLY/README.md"
bump "$MONOREPO/examples/midnight-kicks/app/build.gradle.kts"
# examples/bboard — skipped on purpose (-PkuiraVersion-driven).

echo
echo "Done. Review each repo, then push — ONLY after ${VERSION} is live on Maven Central:"
for r in "$STARTER" "$DOCS" "$BBOARD" "$TALLY" "$MONOREPO"; do
  [[ -d "$r/.git" ]] || continue
  changed=$(git -C "$r" --no-pager diff --name-only 2>/dev/null || true)
  [[ -n "$changed" ]] && { echo "  --- ${r##*/} ---"; echo "$changed" | sed 's/^/      /'; }
done
