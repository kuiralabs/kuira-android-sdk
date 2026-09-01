# Releasing the Kuira SDK

The version lives in many places across several repos. Miss one and a new
developer lands on a stale version (this is exactly how issue #1 happened — the
public starter sat at `alpha01` after `alpha02` shipped). Follow this in order.

## Source of truth

`gradle.properties` → `version=` is the single source for the **published
artifacts**. Everything else (consumer pins, docs) is downstream and is updated
by `scripts/release.sh` — except the docs README, which shows a live **Maven
Central badge** and never needs bumping.

## Steps

1. **Bump the artifact version.** Edit `gradle.properties` (`version=X`). Commit
   it with the release changes.

2. **Pre-flight, locally (what CI will gate on):**
   ```
   ./gradlew test apiCheck            # apiCheck fails on undeclared API changes → ./gradlew apiDump
   ./gradlew publishToMavenLocal      # confirms the full bundle assembles at X
   ```
   If you changed the `kuira-crypto-ffi` submodule, make sure the commit you
   pin is **pushed to the FFI remote** (and its nested `midnight-ledger`
   submodule too) — CI checks out submodules recursively and can only build
   what's on the remote.

3. **Tag → publish to staging.** Push `main`, then:
   ```
   git tag vX && git push origin vX
   ```
   `publish-maven-central.yml` runs `test → apiCheck → publishToMavenLocal →
   BBoard acceptance (builds against X via -PkuiraVersion) → publishToMavenCentral`.
   That uploads to **Central Portal staging** — it does NOT auto-release.

4. **Release in the Portal.** Manually release the staged deployment
   (irreversible). Sign with the `nel349` key.

5. **Wait for sync.** `X` takes ~10–30 min to resolve on `repo1.maven.org`.
   Verify before the next step, e.g.:
   ```
   curl -sI https://repo1.maven.org/maven2/io/github/kuiralabs/dapp-ui/X/dapp-ui-X.pom
   ```

6. **Bump every consumer surface — ONLY after step 5.** Pushing before X
   resolves breaks fresh clones (they 404 on X).
   ```
   scripts/release.sh X
   ```
   Review `git diff` in each repo, then push per repo:
   `kuira-starter-android`, `kuira-sdk-android`, `example-bboard-android`,
   `midnight-tally`, and the monorepo (`examples/midnight-kicks`).
   (`examples/bboard` is skipped — it's `-PkuiraVersion`-driven and can't drift.)

7. **Regenerate the API docs.** The Dokka HTML under `kuira-sdk-android/docs/api`
   is generated from the build, not by the script:
   ```
   rm -rf build/dokka core/*/build/dokka sdk/*/build/dokka
   ./gradlew dokkaHtmlMultiModule
   ```
   Copy the output into `kuira-sdk-android/docs/api/` and redeploy the site.

8. **Verify.** The Maven Central badge on the docs README flips to X, and a
   fresh `git clone` of the starter builds cold against Central.

## What `scripts/release.sh` touches

Coordinates (`io.github.kuiralabs:*:X`), the contract-plugin id version, the
`kuira` version-catalog entry, the mkdocs version vars, and the starter's
pinned-versions table row — across all consumer repos. It never commits or pushes.
