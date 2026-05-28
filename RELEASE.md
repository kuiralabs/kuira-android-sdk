# Kuira SDK — Release Guide

How to ship a Kuira SDK version to Maven Central. Two phases: **one-time
setup**, then the **per-release ritual**.

The pipeline itself is
[`.github/workflows/publish-maven-central.yml`](.github/workflows/publish-maven-central.yml).
This doc is the human contract around it.

---

## Phase 1 — one-time setup

Do these once. Subsequent releases skip straight to Phase 2.

### 1. Central Portal account + namespace

1. Sign up at <https://central.sonatype.com/>.
2. **Settings → Namespaces → Add** → enter `io.github.kuiralabs`.
3. The Portal returns a verification key. Create a **public** repo at
   `github.com/kuiralabs/<verification-key>` (Portal tells you the exact
   name).
4. Back on the Portal: **Verify Namespace**.

You only own one namespace; this is permanent.

### 2. Portal user token (the publish credentials)

On the Portal: **Settings → User Token → Generate**.

- Pick any descriptive name (e.g. `kuira-sdk-publish`) — it's just a label
  for your records, no functional effect.
- The Portal shows the **username + password once**. Copy both immediately
  into your password manager — the password can't be retrieved later.
- These are the values for `MAVEN_CENTRAL_USERNAME` and
  `MAVEN_CENTRAL_PASSWORD` in step 4.

### 3. GPG signing key

Maven Central requires every artifact to be signed. Generate the key on your
laptop (interactive prompts for passphrase):

```bash
gpg --full-generate-key                      # RSA, 4096, no expiry, name+email, passphrase
gpg --list-secret-keys --keyid-format=long   # note the KEY_ID and FINGERPRINT
gpg --keyserver keyserver.ubuntu.com --send-keys <FINGERPRINT>
gpg --armor --export-secret-keys <KEY_ID>    # entire -----BEGIN…END----- block
```

The armored block goes into the `SIGNING_IN_MEMORY_KEY` secret; the passphrase
goes into `SIGNING_IN_MEMORY_KEY_PASSWORD`. **Never paste either into git or
chat.**

### 4. GitHub Actions secrets

GitHub → repo → **Settings → Secrets and variables → Actions → New repository
secret**. Add four secrets, exact names:

| Name | Value |
|---|---|
| `MAVEN_CENTRAL_USERNAME` | Portal token username (Phase 1 step 2) |
| `MAVEN_CENTRAL_PASSWORD` | Portal token password (Phase 1 step 2) |
| `SIGNING_IN_MEMORY_KEY` | The whole armored `-----BEGIN PGP PRIVATE KEY BLOCK----- … -----END PGP PRIVATE KEY BLOCK-----` block |
| `SIGNING_IN_MEMORY_KEY_PASSWORD` | The GPG passphrase |

### 5. Dry-run the workflow

GitHub → **Actions** → "Publish to Maven Central" → **Run workflow** → branch
`main` → Run.

- All green ⇒ secrets work, signing works, BBoard acceptance gate passes.
- The bundle lands on Central **staging** — you can either release it as
  `0.1.0-alpha01` (turning the dry-run into the real first release) or drop
  it on the Portal.

---

## Phase 2 — per-release ritual

```bash
# 1. Bump the version in the root build.gradle.kts:
#      val kuiraVersion = "0.1.0-alphaXX"

# 2. Commit + tag + push (the workflow triggers on the tag):
git add build.gradle.kts
git commit -m "release: v0.1.0-alphaXX"
git tag v0.1.0-alphaXX
git push origin main --follow-tags
```

From here it's automated:

```
git push --tags
       │
       ▼
Actions "Publish to Maven Central" (~8–15 min)
       │   ├─ unit tests (fail-fast)
       │   ├─ publishToMavenLocal on the runner
       │   ├─ BBoard builds against those artifacts (acceptance gate)
       │   └─ publishToMavenCentral → Portal STAGING
       ▼
central.sonatype.com → Publish → Validate → Release
       ▼  (~30 min for global propagation)
io.github.kuiralabs:dapp-ui:0.1.0-alphaXX available via mavenCentral()
```

If the acceptance gate fails, the workflow halts **before** uploading — the
tag still exists but nothing is staged. Investigate, fix, re-tag.

---

## How this differs from npm

| | npm | Maven Central |
|---|---|---|
| Ship command | `npm publish` | `git tag v… && git push --tags` |
| Versions immutable? | yes | **yes — and undeletable.** Hence the staging step. |
| Live after | seconds | ~30 min after you click Release on the Portal |
| Skip the manual click? | n/a | swap the workflow's task to `publishAndReleaseToMavenCentral` to auto-release. Leave manual until the alpha stabilizes — you can drop a bad bundle from staging; you can't drop one from Central. |

Two extra clicks per release, in exchange for "can't accidentally publish a
broken artifact and have it stuck on Central forever."

---

## See also

- [`INTEGRATION.md`](INTEGRATION.md) — what a consumer does on the other side.
- [`docs/ALPHA_RELEASE_PLAN.md`](docs/ALPHA_RELEASE_PLAN.md) — the audit that
  led to this pipeline.
- [`.github/workflows/publish-maven-central.yml`](.github/workflows/publish-maven-central.yml) — the workflow itself.
