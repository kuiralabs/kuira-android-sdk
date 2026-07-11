package com.midnight.kuira.contract

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * TestKit acceptance tests for the Kuira Contract Gradle Plugin.
 *
 * Each test materialises a self-contained Gradle project, applies the
 * plugin, runs the `syncContractAssets` task, and asserts the resulting
 * asset layout. No Android SDK / device required — the plugin is
 * exercised in isolation against a synthetic Android-library-shaped
 * project with the `preBuild` lifecycle task stubbed in.
 */
class KuiraContractPluginTest {

    @get:Rule
    val tempDir = TemporaryFolder()

    private lateinit var projectDir: File

    @Before
    fun setup() {
        projectDir = tempDir.root
    }

    @Test
    fun `task syncContractAssets copies contract js with alias rename`() {
        writeCanonicalContractTree("penalty")
        writeBuildScript(source = "contract/src/managed/penalty")

        val result = run("syncContractAssets")

        assertEquals(TaskOutcome.SUCCESS, result.task(":syncContractAssets")?.outcome)
        val runtimeDir = projectDir.resolve("src/main/assets/runtime")
        assertTrue(
            "contract JS should be renamed to {alias}-contract.js",
            runtimeDir.resolve("penalty-contract.js").exists(),
        )
        assertEquals(
            "stub contract JS content should round-trip verbatim",
            CONTRACT_JS_STUB,
            runtimeDir.resolve("penalty-contract.js").readText(),
        )
    }

    @Test
    fun `task syncContractAssets copies prover + verifier + bzkir files`() {
        writeCanonicalContractTree("penalty")
        writeBuildScript(source = "contract/src/managed/penalty")

        run("syncContractAssets")

        val keysDir = projectDir.resolve("src/main/assets/keys")
        assertTrue("circuit1.prover should be copied", keysDir.resolve("circuit1.prover").exists())
        assertTrue("circuit1.verifier should be copied", keysDir.resolve("circuit1.verifier").exists())
        assertTrue("circuit1.bzkir should be copied", keysDir.resolve("circuit1.bzkir").exists())
    }

    @Test
    fun `alias defaults to the source dirname`() {
        writeCanonicalContractTree("snake")
        // No explicit alias — should be derived from dirname.
        writeBuildScript(source = "contract/src/managed/snake")

        run("syncContractAssets")

        assertTrue(
            projectDir.resolve("src/main/assets/runtime/snake-contract.js").exists(),
        )
    }

    @Test
    fun `explicit alias overrides the dirname default`() {
        writeCanonicalContractTree("penalty")
        writeBuildScript(
            source = "contract/src/managed/penalty",
            alias = "custom",
        )

        run("syncContractAssets")

        assertTrue(
            "explicit alias should win over the dirname default",
            projectDir.resolve("src/main/assets/runtime/custom-contract.js").exists(),
        )
    }

    @Test
    fun `failure when source path does not exist surfaces a helpful message`() {
        // Don't write any contract files — source path is missing.
        writeBuildScript(source = "contract/src/managed/nonexistent")

        val result = runExpectingFailure("syncContractAssets")

        val output = result.output
        assertTrue(
            "error should name the missing path: $output",
            output.contains("nonexistent"),
        )
        assertTrue(
            "error should hint at how to fix it: $output",
            output.contains("compile your contract") || output.contains("npm run compact"),
        )
    }

    @Test
    fun `failure when source is unset surfaces a helpful message`() {
        // No `source.set(...)` call in the build script.
        projectDir.resolve("build.gradle.kts").writeText(
            """
            plugins {
                id("io.github.kuiralabs.contract")
            }
            tasks.register("preBuild")
            // kuiraContract block omitted on purpose.
            """.trimIndent(),
        )
        projectDir.resolve("settings.gradle.kts").writeText(
            """rootProject.name = "kuira-contract-test"
            """.trimIndent(),
        )

        val result = runExpectingFailure("syncContractAssets")

        val output = result.output
        // With neither the top-level `source` shorthand nor a `contracts { }` block, the plugin
        // fails fast naming BOTH ways to declare a contract.
        assertTrue(
            "error should name the missing setting: $output",
            output.contains("kuiraContract needs a contract") &&
                output.contains("contracts {"),
        )
    }

    @Test
    fun `contracts container registers name-discriminated sync + validate tasks per entry`() {
        writeCanonicalContractTree("Alpha")
        writeCanonicalContractTree("Beta")
        projectDir.resolve("settings.gradle.kts").writeText(
            """rootProject.name = "kuira-contract-test"
            """.trimIndent(),
        )
        projectDir.resolve("build.gradle.kts").writeText(
            """
            plugins {
                id("io.github.kuiralabs.contract")
            }
            tasks.register("preBuild")
            kuiraContract {
                contracts {
                    register("Alpha") { source.set("contract/src/managed/Alpha") }
                    register("Beta") { source.set("contract/src/managed/Beta") }
                }
            }
            """.trimIndent(),
        )

        val output = run("tasks", "--all").output

        // Each container entry fans out into its own name-discriminated tasks (no collision).
        assertTrue("Alpha sync task missing: $output", output.contains("syncAlphaContractAssets"))
        assertTrue("Beta sync task missing: $output", output.contains("syncBetaContractAssets"))
        assertTrue("Alpha validate task missing: $output", output.contains("validateAlphaContractSource"))
        assertTrue("Beta validate task missing: $output", output.contains("validateBetaContractSource"))
    }

    @Test
    fun `container entry missing source fails loudly, naming the entry`() {
        // A container entry is never optional; forgetting source must NOT silently ship a broken
        // build (the onlyIf gate would otherwise skip its validate task with no diagnostic).
        projectDir.resolve("settings.gradle.kts").writeText(
            """rootProject.name = "kuira-contract-test"
            """.trimIndent(),
        )
        projectDir.resolve("build.gradle.kts").writeText(
            """
            plugins {
                id("io.github.kuiralabs.contract")
            }
            tasks.register("preBuild")
            kuiraContract {
                contracts {
                    register("Vault") { }
                }
            }
            """.trimIndent(),
        )

        val output = runExpectingFailure("tasks").output
        assertTrue("should name the entry missing source: $output",
            output.contains("entry 'Vault' is missing its source"))
    }

    @Test
    fun `contracts whose aliases collapse to the same class fail with a clear message`() {
        writeCanonicalContractTree("Vault")
        projectDir.resolve("settings.gradle.kts").writeText(
            """rootProject.name = "kuira-contract-test"
            """.trimIndent(),
        )
        // Shorthand alias "Vault" + container entry alias "vault" both generate VaultContract into
        // one package → duplicate-class compile error. Caught at config with a clear message.
        projectDir.resolve("build.gradle.kts").writeText(
            """
            plugins {
                id("io.github.kuiralabs.contract")
            }
            tasks.register("preBuild")
            kuiraContract {
                source.set("contract/src/managed/Vault")
                contracts {
                    register("vault") { source.set("contract/src/managed/Vault") }
                }
            }
            """.trimIndent(),
        )

        val output = runExpectingFailure("tasks").output
        assertTrue("should flag the alias collision: $output",
            output.contains("collapse to the same generated class"))
    }

    @Test
    fun `syncContractAssets is wired into the preBuild lifecycle`() {
        writeCanonicalContractTree("penalty")
        writeBuildScript(source = "contract/src/managed/penalty")

        val result = run("preBuild")

        // preBuild itself should run, AND it should have triggered our task.
        assertNotNull(
            "syncContractAssets should be in the preBuild graph",
            result.task(":syncContractAssets"),
        )
    }

    // ── Runtime-version pin enforcement (Fix #9) ─────────────────────

    @Test
    fun `runtime match — contract and co-located package_json agree`() {
        writeCanonicalContractTree("counter", emittedRuntimeVersion = "0.16.0")
        writePackageJson("contract", runtimeVersion = "0.16.0")
        writeBuildScript(source = "contract/src/managed/counter")

        val result = run("validateKuiraContractSource")
        assertEquals(
            TaskOutcome.SUCCESS,
            result.task(":validateKuiraContractSource")?.outcome,
        )
    }

    @Test
    fun `runtime mismatch — contract vs co-located package_json fails with both versions named`() {
        writeCanonicalContractTree("counter", emittedRuntimeVersion = "0.16.0")
        writePackageJson("contract", runtimeVersion = "0.15.0")
        writeBuildScript(source = "contract/src/managed/counter")

        val result = runExpectingFailure("syncContractAssets")
        val output = result.output

        assertTrue(
            "error should name the emitted runtime version: $output",
            output.contains("compiled against @midnight-ntwrk/compact-runtime 0.16.0"),
        )
        assertTrue(
            "error should name the consumer-pinned runtime version: $output",
            output.contains("Consumer pinned @midnight-ntwrk/compact-runtime 0.15.0"),
        )
        assertTrue(
            "error should mention the runtime crash this prevents: $output",
            output.contains("Unsupported bytecode version"),
        )
    }

    @Test
    fun `runtime check skipped when contract-info_json missing — older compactc output`() {
        // Tree WITHOUT compiler/contract-info.json — older compactc didn't emit it.
        writeCanonicalContractTree("counter", emittedRuntimeVersion = null)
        writePackageJson("contract", runtimeVersion = "0.16.0")
        writeBuildScript(source = "contract/src/managed/counter")

        val result = run("validateKuiraContractSource")
        assertEquals(
            TaskOutcome.SUCCESS,
            result.task(":validateKuiraContractSource")?.outcome,
        )
        assertTrue(
            "should log that the check was skipped because the info file is absent: ${result.output}",
            result.output.contains("runtime-version check skipped"),
        )
    }

    @Test
    fun `runtime check skipped when no package_json and no explicit pin`() {
        writeCanonicalContractTree("counter", emittedRuntimeVersion = "0.16.0")
        // No writePackageJson — no co-located pin and no explicit one.
        writeBuildScript(source = "contract/src/managed/counter")

        val result = run("validateKuiraContractSource")
        assertEquals(
            TaskOutcome.SUCCESS,
            result.task(":validateKuiraContractSource")?.outcome,
        )
        assertTrue(
            "should log that auto-discovery found no package.json: ${result.output}",
            result.output.contains("No package.json declaring"),
        )
    }

    @Test
    fun `explicit expectedRuntimeVersion overrides auto-discovery and validates correctly`() {
        writeCanonicalContractTree("counter", emittedRuntimeVersion = "0.16.0")
        // No package.json — exercise the explicit-pin-only path.
        writeBuildScript(
            source = "contract/src/managed/counter",
            expectedRuntimeVersion = "0.16.0",
        )

        val result = run("validateKuiraContractSource")
        assertEquals(
            TaskOutcome.SUCCESS,
            result.task(":validateKuiraContractSource")?.outcome,
        )
    }

    @Test
    fun `explicit expectedRuntimeVersion fails on mismatch even without a package_json`() {
        writeCanonicalContractTree("counter", emittedRuntimeVersion = "0.16.0")
        writeBuildScript(
            source = "contract/src/managed/counter",
            expectedRuntimeVersion = "0.15.0",
        )

        val result = runExpectingFailure("syncContractAssets")
        assertTrue(
            "error should name the emitted version: ${result.output}",
            result.output.contains("0.16.0"),
        )
        assertTrue(
            "error should name the explicit-pin version: ${result.output}",
            result.output.contains("0.15.0"),
        )
    }

    @Test
    fun `requireRuntimeMatch=false downgrades mismatch to a warning, build succeeds`() {
        writeCanonicalContractTree("counter", emittedRuntimeVersion = "0.16.0")
        writePackageJson("contract", runtimeVersion = "0.15.0")
        writeBuildScript(
            source = "contract/src/managed/counter",
            requireRuntimeMatch = false,
        )

        val result = run("validateKuiraContractSource")
        assertEquals(
            TaskOutcome.SUCCESS,
            result.task(":validateKuiraContractSource")?.outcome,
        )
        assertTrue(
            "should warn even when not failing: ${result.output}",
            result.output.contains("Compact runtime version mismatch"),
        )
    }

    // ── kuiraDoctor (Fix #8) ─────────────────────────────────────────

    @Test
    fun `doctor — minSdk check fails when below 30`() {
        writeCanonicalContractTree("counter", emittedRuntimeVersion = "0.16.0")
        writeBuildScript(
            source = "contract/src/managed/counter",
            applicationId = "com.example.app",
            minSdk = 28,
            requireDoctorPass = true,
        )

        val result = runExpectingFailure("kuiraDoctor")
        assertTrue(
            "report should call out the minSdk floor: ${result.output}",
            result.output.contains("minSdk = 28") &&
                result.output.contains("below Kuira SDK's required floor of 30"),
        )
    }

    @Test
    fun `doctor — minSdk check passes at 30`() {
        writeCanonicalContractTree("counter", emittedRuntimeVersion = "0.16.0")
        writePackageJson("contract", runtimeVersion = "0.16.0")
        writeBuildScript(
            source = "contract/src/managed/counter",
            applicationId = "com.example.app",
            minSdk = 30,
        )

        val result = run("kuiraDoctor")
        assertEquals(TaskOutcome.SUCCESS, result.task(":kuiraDoctor")?.outcome)
        assertTrue(
            "report should show minSdk passing: ${result.output}",
            result.output.contains("✓") && result.output.contains("minSdk"),
        )
    }

    @Test
    fun `doctor — cleartext check warns when debug manifest missing`() {
        writeCanonicalContractTree("counter", emittedRuntimeVersion = "0.16.0")
        writePackageJson("contract", runtimeVersion = "0.16.0")
        writeBuildScript(
            source = "contract/src/managed/counter",
            applicationId = "com.example.app",
            minSdk = 30,
        )
        // No app/src/debug/AndroidManifest.xml written.

        val result = run("kuiraDoctor")
        assertEquals(TaskOutcome.SUCCESS, result.task(":kuiraDoctor")?.outcome)
        assertTrue(
            "should warn about missing debug manifest: ${result.output}",
            result.output.contains("No app/src/debug/AndroidManifest.xml"),
        )
    }

    @Test
    fun `doctor — cleartext check passes when networkSecurityConfig is declared`() {
        writeCanonicalContractTree("counter", emittedRuntimeVersion = "0.16.0")
        writePackageJson("contract", runtimeVersion = "0.16.0")
        writeBuildScript(
            source = "contract/src/managed/counter",
            applicationId = "com.example.app",
            minSdk = 30,
        )
        writeDebugManifest(includesCleartext = true)

        val result = run("kuiraDoctor")
        assertTrue(
            "should pass cleartext check: ${result.output}",
            result.output.contains("PASS ") && result.output.contains("debug-cleartext"),
        )
    }

    @Test
    fun `doctor — assetlinks check skips when rpId is unset`() {
        writeCanonicalContractTree("counter", emittedRuntimeVersion = "0.16.0")
        writePackageJson("contract", runtimeVersion = "0.16.0")
        writeBuildScript(
            source = "contract/src/managed/counter",
            applicationId = "com.example.app",
            minSdk = 30,
            // rpId omitted on purpose.
        )

        val result = run("kuiraDoctor")
        assertTrue(
            "should explain why assetlinks check was skipped: ${result.output}",
            result.output.contains("kuiraContract.rpId is unset"),
        )
    }

    @Test
    fun `doctor — compact-runtime check appears in unified report`() {
        // Mismatched runtime triggers a FAIL severity in the doctor report
        // but the doctor itself doesn't fail (default requireDoctorPass=false).
        writeCanonicalContractTree("counter", emittedRuntimeVersion = "0.16.0")
        writePackageJson("contract", runtimeVersion = "0.15.0")
        writeBuildScript(
            source = "contract/src/managed/counter",
            applicationId = "com.example.app",
            minSdk = 30,
        )

        val result = run("kuiraDoctor")
        assertEquals(TaskOutcome.SUCCESS, result.task(":kuiraDoctor")?.outcome)
        assertTrue(
            "should call out the runtime mismatch in the report: ${result.output}",
            result.output.contains("Contract was compiled against runtime 0.16.0") &&
                result.output.contains("consumer pinned 0.15.0"),
        )
    }

    @Test
    fun `doctor — requireDoctorPass=true converts FAIL to a build failure`() {
        writeCanonicalContractTree("counter", emittedRuntimeVersion = "0.16.0")
        writePackageJson("contract", runtimeVersion = "0.16.0")
        writeBuildScript(
            source = "contract/src/managed/counter",
            applicationId = "com.example.app",
            minSdk = 28,
            requireDoctorPass = true,
        )

        val result = runExpectingFailure("kuiraDoctor")
        assertTrue(
            "task failure should reference kuiraDoctor: ${result.output}",
            result.output.contains("kuiraDoctor failed"),
        )
    }

    @Test
    fun `doctor — sdk-bundled-runtime check SKIPs when no compact-engine AAR is on classpath`() {
        // Scratch project with no Kuira SDK dep — there's no AAR to inspect.
        writeCanonicalContractTree("counter", emittedRuntimeVersion = "0.16.0")
        writeBuildScript(
            source = "contract/src/managed/counter",
            applicationId = "com.example.app",
            minSdk = 30,
        )

        val result = run("kuiraDoctor")
        assertTrue(
            "should skip with the no-AAR-found explanation: ${result.output}",
            result.output.contains("sdk-bundled-runtime") &&
                result.output.contains("Could not locate compact-engine AAR"),
        )
    }

    @Test
    fun `doctor — sdk-bundled-runtime PASSes when fake AAR has matching version`() {
        writeCanonicalContractTree("counter", emittedRuntimeVersion = "0.16.0")
        // Stage a synthetic compact-engine AAR with versionString = "0.16.0"
        // in the project's flatDir repo. We bypass the real compact-engine
        // dependency (which would drag in 100s of MB of Android) and provide
        // just enough surface for the AAR-reader check to find a match.
        val fakeAarDir = projectDir.resolve("local-aars")
        writeFakeCompactEngineAar(fakeAarDir.resolve("compact-engine-fake.aar"), runtimeVersion = "0.16.0")
        writeBuildScriptWithFakeAar(
            source = "contract/src/managed/counter",
            applicationId = "com.example.app",
            minSdk = 30,
            aarDir = fakeAarDir,
        )

        val result = run("kuiraDoctor")
        assertTrue(
            "should PASS with versions matching: ${result.output}",
            result.output.contains("sdk-bundled-runtime") &&
                result.output.contains("PASS") &&
                result.output.contains("compact-runtime 0.16.0"),
        )
    }

    @Test
    fun `doctor — sdk-bundled-runtime FAILs when fake AAR has mismatched version`() {
        writeCanonicalContractTree("counter", emittedRuntimeVersion = "0.16.0")
        val fakeAarDir = projectDir.resolve("local-aars")
        writeFakeCompactEngineAar(fakeAarDir.resolve("compact-engine-fake.aar"), runtimeVersion = "0.15.0")
        writeBuildScriptWithFakeAar(
            source = "contract/src/managed/counter",
            applicationId = "com.example.app",
            minSdk = 30,
            aarDir = fakeAarDir,
        )

        val result = run("kuiraDoctor")
        assertTrue(
            "should FAIL naming both versions: ${result.output}",
            result.output.contains("sdk-bundled-runtime") &&
                result.output.contains("FAIL") &&
                result.output.contains("SDK bundles compact-runtime 0.15.0") &&
                result.output.contains("contract expects runtime 0.16.0"),
        )
    }

    @Test
    fun `doctor — unified report shows tallies for PASS WARN FAIL SKIP`() {
        writeCanonicalContractTree("counter", emittedRuntimeVersion = "0.16.0")
        writePackageJson("contract", runtimeVersion = "0.16.0")
        writeBuildScript(
            source = "contract/src/managed/counter",
            applicationId = "com.example.app",
            minSdk = 30,
        )

        val result = run("kuiraDoctor")
        assertTrue(
            "report should include the tally line: ${result.output}",
            result.output.contains(Regex("""\d+ passed, \d+ warning, \d+ error, \d+ skipped""")),
        )
    }

    // ── Configuration-cache compatibility ───────────────────────────

    @Test
    fun `validate + sync are configuration-cache compatible`() {
        writeCanonicalContractTree("counter", emittedRuntimeVersion = "0.16.0")
        writePackageJson("contract", runtimeVersion = "0.16.0")
        writeBuildScript(source = "contract/src/managed/counter")

        // First run stores the entry. A task action that captures the
        // Gradle `Project` (the regression this guards) fails here with
        // "cannot serialize object of type ... DefaultProject"; .build()
        // would then throw and fail the test.
        val first = runWithConfigCache("syncContractAssets")
        assertEquals(TaskOutcome.SUCCESS, first.task(":syncContractAssets")?.outcome)
        assertTrue(
            "first run should store the configuration cache without problems: ${first.output}",
            first.output.contains("Configuration cache entry stored"),
        )

        // Second run must reuse the stored entry — Gradle discards an
        // entry that recorded problems, so reuse proves there were none.
        val second = runWithConfigCache("syncContractAssets")
        assertTrue(
            "second run should reuse the configuration cache: ${second.output}",
            second.output.contains("Reusing configuration cache"),
        )
    }

    @Test
    fun `container config keeps provisionWalletKeys configuration-cache compatible`() {
        // Regression: with a contracts { } container on the extension, provisionWalletKeys's onlyIf
        // must NOT capture the whole extension (it holds the container → a non-serializable Project).
        // Running preBuild pulls provisionWalletKeys into the graph so the config-cache store
        // serializes it — the exact path that failed the real Vault build.
        writeCanonicalContractTree("Vault", emittedRuntimeVersion = "0.16.0")
        writePackageJson("contract", runtimeVersion = "0.16.0")
        projectDir.resolve("settings.gradle.kts").writeText(
            """rootProject.name = "kuira-contract-test"
            """.trimIndent(),
        )
        projectDir.resolve("build.gradle.kts").writeText(
            """
            plugins {
                id("io.github.kuiralabs.contract")
            }
            tasks.register("preBuild")
            kuiraContract {
                contracts {
                    register("Vault") { source.set("contract/src/managed/Vault") }
                }
            }
            """.trimIndent(),
        )

        val first = runWithConfigCache("preBuild")
        assertTrue(
            "first run should store the config cache without problems: ${first.output}",
            first.output.contains("Configuration cache entry stored"),
        )
        val second = runWithConfigCache("preBuild")
        assertTrue(
            "second run should reuse the config cache (proves no store problems): ${second.output}",
            second.output.contains("Reusing configuration cache"),
        )
    }

    // ── Fixtures ─────────────────────────────────────────────────────

    /**
     * Writes the canonical compactc output layout that every Kuira dApp
     * has under `contract/src/managed/<name>`:
     *
     *   contract/index.js
     *   keys/<circuit>.prover
     *   keys/<circuit>.verifier
     *   zkir/<circuit>.bzkir
     *   compiler/contract-info.json   (only when [emittedRuntimeVersion] is set)
     *
     * Passing null for [emittedRuntimeVersion] models the older-compactc
     * case where the contract-info.json file is absent.
     */
    private fun writeCanonicalContractTree(
        name: String,
        emittedRuntimeVersion: String? = null,
    ) {
        val managed = projectDir.resolve("contract/src/managed/$name")
        managed.resolve("contract").mkdirs()
        managed.resolve("keys").mkdirs()
        managed.resolve("zkir").mkdirs()

        managed.resolve("contract/index.js").writeText(CONTRACT_JS_STUB)
        managed.resolve("keys/circuit1.prover").writeText(STUB_PROVER)
        managed.resolve("keys/circuit1.verifier").writeText(STUB_VERIFIER)
        managed.resolve("zkir/circuit1.bzkir").writeText(STUB_BZKIR)

        if (emittedRuntimeVersion != null) {
            managed.resolve("compiler").mkdirs()
            managed.resolve("compiler/contract-info.json").writeText(
                contractInfoJson(emittedRuntimeVersion),
            )
        }
    }

    /**
     * Writes a `package.json` at `<projectDir>/<relativeDir>/package.json`
     * declaring the given `@midnight-ntwrk/compact-runtime` version. The
     * directory must exist; tests can place this at `contract/` for the
     * canonical layout or elsewhere for auto-discovery walk-up tests.
     */
    private fun writePackageJson(relativeDir: String, runtimeVersion: String) {
        val dir = projectDir.resolve(relativeDir)
        dir.mkdirs()
        dir.resolve("package.json").writeText(packageJson(runtimeVersion))
    }

    private fun writeBuildScript(
        source: String,
        alias: String? = null,
        expectedRuntimeVersion: String? = null,
        requireRuntimeMatch: Boolean? = null,
        rpId: String? = null,
        requireDoctorPass: Boolean? = null,
        applicationId: String? = null,
        minSdk: Int? = null,
    ) {
        val aliasLine = alias?.let { "    alias.set(\"$it\")" } ?: ""
        val expectedLine = expectedRuntimeVersion?.let {
            "    expectedRuntimeVersion.set(\"$it\")"
        } ?: ""
        val requireLine = requireRuntimeMatch?.let {
            "    requireRuntimeMatch.set($it)"
        } ?: ""
        val rpIdLine = rpId?.let { "    rpId.set(\"$it\")" } ?: ""
        val requireDoctorLine = requireDoctorPass?.let {
            "    requireDoctorPass.set($it)"
        } ?: ""

        // For kuiraDoctor's auto-discovery: applicationId + minSdk are
        // pulled from this file by simple regex. Including them in the
        // test fixture means the doctor task picks them up the same
        // way it would in a real consumer project.
        val androidShim = if (applicationId != null || minSdk != null) {
            buildString {
                append("\n\n// Auto-discoverable AGP-shaped values for kuiraDoctor.\n")
                append("// Not a real Android DSL block — just text the regex matches.\n")
                if (applicationId != null) append("// applicationId = \"$applicationId\"\n")
                if (minSdk != null) append("// minSdk = $minSdk\n")
            }
        } else {
            ""
        }

        projectDir.resolve("build.gradle.kts").writeText(
            """
            plugins {
                id("io.github.kuiralabs.contract")
            }

            // The plugin needs a `preBuild` task to wire into — in a real
            // consumer project Android plugins register it. Here we just
            // declare an empty stand-in so we can verify the dependency
            // chain without bringing the Android plugin into TestKit.
            tasks.register("preBuild")

            kuiraContract {
                source.set("$source")
                $aliasLine
                $expectedLine
                $requireLine
                $rpIdLine
                $requireDoctorLine
            }
            $androidShim
            """.trimIndent(),
        )
        projectDir.resolve("settings.gradle.kts").writeText(
            """rootProject.name = "kuira-contract-test"
            """.trimIndent(),
        )
    }

    /**
     * Writes a minimal synthetic compact-engine AAR (zip) containing
     * just `assets/runtime/compact-runtime-iife.js` with the requested
     * `versionString`. Just enough surface for the sdk-bundled-runtime
     * check to find a target without dragging the real (100+ MB)
     * compact-engine module into the TestKit project.
     */
    private fun writeFakeCompactEngineAar(aarPath: File, runtimeVersion: String) {
        aarPath.parentFile.mkdirs()
        java.util.zip.ZipOutputStream(aarPath.outputStream()).use { zip ->
            zip.putNextEntry(java.util.zip.ZipEntry("assets/runtime/compact-runtime-iife.js"))
            zip.write(
                """
                var __compactRuntime = (() => {
                  var versionString = "$runtimeVersion";
                  return { versionString };
                })();
                """.trimIndent().toByteArray(),
            )
            zip.closeEntry()
        }
    }

    /**
     * Variant of [writeBuildScript] that also wires a flatDir repo
     * pointing at [aarDir] and declares an implementation dep on the
     * fake `compact-engine` AAR. Gradle resolves this via flatDir so
     * the sdk-bundled-runtime check's `config.files` walk finds the AAR.
     */
    private fun writeBuildScriptWithFakeAar(
        source: String,
        applicationId: String,
        minSdk: Int,
        aarDir: File,
    ) {
        val androidShim = buildString {
            append("\n\n// applicationId = \"$applicationId\"\n")
            append("// minSdk = $minSdk\n")
        }
        projectDir.resolve("build.gradle.kts").writeText(
            """
            plugins {
                id("io.github.kuiralabs.contract")
            }

            repositories {
                flatDir { dirs(file("${aarDir.absolutePath.replace("\\", "/")}")) }
            }

            // Synthetic runtime classpath. AGP would normally provide
            // debugRuntimeClasspath / releaseRuntimeClasspath; in TestKit
            // we declare a plain Java configuration so the doctor's
            // walker finds the fake AAR. Name matches what the doctor's
            // RESOLVABLE_CONFIG_NAMES looks for.
            val runtimeClasspath: Configuration by configurations.creating {
                isCanBeResolved = true
                isCanBeConsumed = false
            }
            dependencies {
                runtimeClasspath(files("${aarDir.absolutePath.replace("\\", "/")}/compact-engine-fake.aar"))
            }

            // The plugin needs a `preBuild` task to wire into.
            tasks.register("preBuild")

            kuiraContract {
                source.set("$source")
            }
            $androidShim
            """.trimIndent(),
        )
        projectDir.resolve("settings.gradle.kts").writeText(
            """rootProject.name = "kuira-contract-test"
            """.trimIndent(),
        )
    }

    /**
     * Writes `app/src/debug/AndroidManifest.xml` at the test project
     * root. Used by the kuiraDoctor cleartext-manifest check tests.
     */
    private fun writeDebugManifest(includesCleartext: Boolean) {
        val dir = projectDir.resolve("src/debug")
        dir.mkdirs()
        val content = if (includesCleartext) {
            """
            <?xml version="1.0" encoding="utf-8"?>
            <manifest xmlns:android="http://schemas.android.com/apk/res/android">
                <application android:networkSecurityConfig="@xml/network_security_config" />
            </manifest>
            """.trimIndent()
        } else {
            """
            <?xml version="1.0" encoding="utf-8"?>
            <manifest xmlns:android="http://schemas.android.com/apk/res/android">
                <application />
            </manifest>
            """.trimIndent()
        }
        dir.resolve("AndroidManifest.xml").writeText(content)
    }

    private fun contractInfoJson(runtimeVersion: String) = """
        {
          "compiler-version": "0.31.0",
          "language-version": "0.23.0",
          "runtime-version": "$runtimeVersion",
          "circuits": [],
          "witnesses": [],
          "contracts": [],
          "ledger": []
        }
    """.trimIndent()

    private fun packageJson(runtimeVersion: String) = """
        {
          "name": "stub",
          "version": "0.0.0",
          "dependencies": {
            "@midnight-ntwrk/compact-runtime": "$runtimeVersion"
          }
        }
    """.trimIndent()

    private fun run(vararg args: String) =
        GradleRunner.create()
            .withProjectDir(projectDir)
            .withArguments(*args, "--stacktrace")
            .withPluginClasspath()
            .build()

    private fun runExpectingFailure(vararg args: String) =
        GradleRunner.create()
            .withProjectDir(projectDir)
            .withArguments(*args, "--stacktrace")
            .withPluginClasspath()
            .buildAndFail()

    private fun runWithConfigCache(vararg args: String) =
        GradleRunner.create()
            .withProjectDir(projectDir)
            .withArguments(*args, "--configuration-cache", "--stacktrace")
            .withPluginClasspath()
            .build()

    companion object {
        private const val CONTRACT_JS_STUB = "// stub contract\nexports.foo = 42;\n"
        private const val STUB_PROVER = "stub-prover-bytes"
        private const val STUB_VERIFIER = "stub-verifier-bytes"
        private const val STUB_BZKIR = "stub-bzkir-bytes"
    }
}
