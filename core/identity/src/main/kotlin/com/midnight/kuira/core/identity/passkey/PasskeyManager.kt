package com.midnight.kuira.core.identity.passkey

import android.app.Activity
import android.util.Base64
import androidx.credentials.CreatePublicKeyCredentialRequest
import androidx.credentials.CreatePublicKeyCredentialResponse
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetPublicKeyCredentialOption
import androidx.credentials.PublicKeyCredential
import androidx.credentials.exceptions.NoCredentialException
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom

/**
 * Manages passkey creation and authentication via Android CredentialManager.
 *
 * This is Mode 1 (client mode) — the app calls the system CredentialManager,
 * and Google Password Manager (or another provider) generates and holds the P-256 private key.
 * We never see the private key. We get the public key from the attestation response.
 *
 * The passkey is the root of the sigil's identity facet.
 *
 * Note: Both [createPasskey] and [authenticate] require an Activity context because
 * the CredentialManager shows system UI (biometric prompt, account selector).
 */
class PasskeyManager(
    private val config: PasskeyConfig,
) {
    private val secureRandom = SecureRandom()

    /**
     * Creates a new passkey (registration ceremony).
     *
     * Triggers the system passkey creation flow — the user sees a biometric prompt
     * from Google Password Manager. On success, returns the P-256 public key
     * and credential ID needed for authentication and DID derivation.
     *
     * @param activity Activity context — required for the CredentialManager system UI
     * @param userId Unique user identifier (opaque bytes, not PII). Used by the
     *               authenticator to associate the credential with the user.
     * @param userName Display name for the passkey (shown in password manager UI)
     * @return [PasskeyRegistrationResult] with public key and credential ID
     * @throws PasskeyException on failure
     */
    suspend fun createPasskey(
        activity: Activity,
        userId: ByteArray,
        userName: String,
        prfSalt: ByteArray? = null,
        prfSaltSecond: ByteArray? = null,
    ): PasskeyRegistrationResult {
        val challenge = generateChallenge()
        val requestJson = buildRegistrationRequestJson(
            challenge = challenge,
            userId = userId,
            userName = userName,
            prfSaltB64 = prfSalt?.let { encodeBase64Url(it) },
            prfSaltSecondB64 = prfSaltSecond?.let { encodeBase64Url(it) },
        )

        val credentialManager = CredentialManager.create(activity)
        val request = CreatePublicKeyCredentialRequest(requestJson)

        val response = try {
            credentialManager.createCredential(activity, request)
        } catch (e: Exception) {
            throw PasskeyException("Passkey creation failed: ${e.message}", e)
        }

        if (response !is CreatePublicKeyCredentialResponse) {
            throw PasskeyException(
                "Unexpected response type: ${response::class.simpleName}, " +
                    "expected CreatePublicKeyCredentialResponse"
            )
        }

        val registrationResponseJson = response.registrationResponseJson

        val publicKey = try {
            AttestationParser.extractPublicKey(registrationResponseJson)
        } catch (e: AttestationParseException) {
            throw PasskeyException("Failed to extract public key: ${e.message}", e)
        }

        val credentialId = try {
            JSONObject(registrationResponseJson).getString("id")
        } catch (e: Exception) {
            throw PasskeyException("Missing 'id' in registration response: ${e.message}", e)
        }

        // PRF-on-create: when the authenticator evaluates the requested PRF
        // salt(s) during registration (WebAuthn L3 `prf` extension — Google
        // Password Manager since Chrome 132, hardware tokens), the outputs come
        // back in THIS response. That lets the caller derive the sigil + seed
        // from the create alone, with no follow-up GET — which is what raced
        // the just-created credential ("cannot find credential in local
        // KeyStore"). Null when the authenticator doesn't support it → caller
        // falls back to a GET ceremony.
        val (prfFirst, prfSecond) = parsePrfResults(registrationResponseJson)

        return PasskeyRegistrationResult(
            publicKey = publicKey,
            credentialId = credentialId,
            registrationResponseJson = registrationResponseJson,
            prfOutput = prfFirst,
            prfOutputSecond = prfSecond,
        )
    }

    /**
     * Authenticates with an existing passkey (assertion ceremony).
     *
     * Triggers the system passkey selection/biometric flow. On success, returns
     * the signed assertion that proves the user controls the passkey.
     *
     * @param activity Activity context — required for the CredentialManager system UI
     * @param challenge Custom challenge bytes to include in the assertion.
     *                  For keyAuthorization, this contains the delegation payload hash.
     * @return [PasskeyAssertionResult] with the signed assertion
     * @throws PasskeyException on failure
     */
    suspend fun authenticate(
        activity: Activity,
        challenge: ByteArray,
    ): PasskeyAssertionResult {
        val challengeB64 = Base64.encodeToString(
            challenge,
            Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP,
        )

        val credentialManager = CredentialManager.create(activity)
        val requestJson = buildAuthenticationRequestJson(challengeB64)
        val option = GetPublicKeyCredentialOption(requestJson)
        val request = GetCredentialRequest(listOf(option))

        val response = try {
            credentialManager.getCredential(activity, request)
        } catch (e: Exception) {
            throw PasskeyException("Passkey authentication failed: ${e.message}", e)
        }

        val credential = response.credential
        if (credential !is PublicKeyCredential) {
            throw PasskeyException(
                "Unexpected credential type: ${credential::class.simpleName}, " +
                    "expected PublicKeyCredential"
            )
        }

        val assertionResponseJson = credential.authenticationResponseJson

        val json = JSONObject(assertionResponseJson)
        val responseObj = json.getJSONObject("response")

        return PasskeyAssertionResult(
            credentialId = json.getString("id"),
            authenticatorData = decodeBase64Url(responseObj.getString("authenticatorData")),
            clientDataJson = decodeBase64Url(responseObj.getString("clientDataJSON")),
            signature = decodeBase64Url(responseObj.getString("signature")),
            assertionResponseJson = assertionResponseJson,
        )
    }

    private fun generateChallenge(): ByteArray {
        val challenge = ByteArray(CHALLENGE_SIZE)
        secureRandom.nextBytes(challenge)
        return challenge
    }

    private fun buildRegistrationRequestJson(
        challenge: ByteArray,
        userId: ByteArray,
        userName: String,
        prfSaltB64: String? = null,
        prfSaltSecondB64: String? = null,
    ): String {
        val challengeB64 = Base64.encodeToString(
            challenge,
            Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP,
        )
        val userIdB64 = Base64.encodeToString(
            userId,
            Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP,
        )

        return JSONObject().apply {
            put("challenge", challengeB64)
            put("rp", JSONObject().apply {
                put("name", config.rpName)
                put("id", config.rpId)
            })
            put("user", JSONObject().apply {
                put("id", userIdB64)
                put("name", userName)
                put("displayName", userName)
            })
            put("pubKeyCredParams", JSONArray().apply {
                // ES256 (P-256) — the only algorithm we accept
                put(JSONObject().apply {
                    put("type", "public-key")
                    put("alg", COSE_ALG_ES256)
                })
            })
            put("timeout", config.timeoutMs)
            put("attestation", "none")
            put("authenticatorSelection", JSONObject().apply {
                put("authenticatorAttachment", "platform")
                put("residentKey", "required")
                put("userVerification", "required")
            })
            // PRF-on-create extension (WebAuthn L3). Requesting eval here means
            // a supporting authenticator returns the PRF output(s) in the
            // registration response — single ceremony, no follow-up GET.
            if (prfSaltB64 != null) {
                put("extensions", JSONObject().apply {
                    put("prf", JSONObject().apply {
                        put("eval", JSONObject().apply {
                            put("first", prfSaltB64)
                            if (prfSaltSecondB64 != null) {
                                put("second", prfSaltSecondB64)
                            }
                        })
                    })
                })
            }
        }.toString()
    }

    /**
     * Authenticates with PRF extension — produces a deterministic secret from the passkey.
     *
     * Same passkey + same salt = same 32-byte secret on any device.
     * Used for deriving encryption keys for cloud backup (zero-words recovery).
     *
     * @param activity Activity context for the CredentialManager system UI
     * @param challenge Challenge bytes for the assertion
     * @param prfSalt Salt for the PRF evaluation (purpose-bound, e.g. SHA-256("kuira:backup:v1"))
     * @return [PrfAssertionResult] with the assertion data AND the 32-byte PRF output
     * @throws PasskeyException if PRF is not supported or authentication fails
     */
    suspend fun authenticateWithPrf(
        activity: Activity,
        challenge: ByteArray,
        prfSalt: ByteArray,
        prfSaltSecond: ByteArray? = null,
        preferImmediatelyAvailable: Boolean = false,
    ): PrfAssertionResult {
        val challengeB64 = encodeBase64Url(challenge)
        val saltB64 = encodeBase64Url(prfSalt)
        val saltSecondB64 = prfSaltSecond?.let { encodeBase64Url(it) }

        val credentialManager = CredentialManager.create(activity)
        val requestJson = buildAuthenticationRequestJson(
            challengeB64 = challengeB64,
            prfSaltB64 = saltB64,
            prfSaltSecondB64 = saltSecondB64,
        )
        val option = GetPublicKeyCredentialOption(requestJson)
        // preferImmediatelyAvailable = true → no cross-device / "sign in another
        // way" UI: if no local passkey exists the GET fails fast with
        // NoCredentialException, which the sign-in-if-exists flow turns into a
        // forge. Left false for an explicit restore, where hybrid sign-in (pull a
        // passkey from another device) is exactly what the user wants.
        val request = GetCredentialRequest.Builder()
            .addCredentialOption(option)
            .setPreferImmediatelyAvailableCredentials(preferImmediatelyAvailable)
            .build()

        val response = try {
            credentialManager.getCredential(activity, request)
        } catch (e: NoCredentialException) {
            // No passkey exists for this relying party — a distinct signal from a
            // failed or user-cancelled ceremony, so a caller can choose to forge a
            // new sigil instead of surfacing an error (sign-in-if-exists).
            throw NoPasskeyCredentialException("No passkey credential for this relying party", e)
        } catch (e: Exception) {
            throw PasskeyException("PRF authentication failed: ${e.message}", e)
        }

        val credential = response.credential
        if (credential !is PublicKeyCredential) {
            throw PasskeyException(
                "Unexpected credential type: ${credential::class.simpleName}"
            )
        }

        val assertionResponseJson = credential.authenticationResponseJson
        val json = JSONObject(assertionResponseJson)
        val responseObj = json.getJSONObject("response")

        val (prfFirst, prfSecond) = parsePrfResults(assertionResponseJson)

        return PrfAssertionResult(
            credentialId = json.getString("id"),
            authenticatorData = decodeBase64Url(responseObj.getString("authenticatorData")),
            clientDataJson = decodeBase64Url(responseObj.getString("clientDataJSON")),
            signature = decodeBase64Url(responseObj.getString("signature")),
            assertionResponseJson = assertionResponseJson,
            prfOutput = prfFirst,
            prfOutputSecond = prfSecond,
        )
    }

    private fun buildAuthenticationRequestJson(
        challengeB64: String,
        prfSaltB64: String? = null,
        prfSaltSecondB64: String? = null,
    ): String {
        return JSONObject().apply {
            put("challenge", challengeB64)
            put("rpId", config.rpId)
            put("timeout", config.timeoutMs)
            put("userVerification", "required")
            if (prfSaltB64 != null) {
                put("extensions", JSONObject().apply {
                    put("prf", JSONObject().apply {
                        put("eval", JSONObject().apply {
                            put("first", prfSaltB64)
                            if (prfSaltSecondB64 != null) {
                                put("second", prfSaltSecondB64)
                            }
                        })
                    })
                })
            }
        }.toString()
    }

    /**
     * Extract PRF outputs (both salts when present) from a credential
     * response's `clientExtensionResults.prf.results.{first,second}`. Shared by
     * the GET (assertion) and CREATE (registration) paths — both carry PRF
     * results in the same shape. `second` is populated only when a second salt
     * was requested AND the authenticator supports multi-salt evaluation
     * (CTAP2 hmac-secret). Returns `(null, null)` when PRF is absent.
     */
    private fun parsePrfResults(responseJson: String): Pair<ByteArray?, ByteArray?> {
        return try {
            val json = JSONObject(responseJson)
            val results = json.optJSONObject("clientExtensionResults")
                ?.optJSONObject("prf")
                ?.optJSONObject("results")
            val firstB64 = results?.optString("first", "")
            val secondB64 = results?.optString("second", "")
            val first = if (firstB64.isNullOrEmpty()) null else decodeBase64Url(firstB64)
            val second = if (secondB64.isNullOrEmpty()) null else decodeBase64Url(secondB64)
            first to second
        } catch (e: Exception) {
            null to null
        }
    }

    private fun encodeBase64Url(data: ByteArray): String {
        return Base64.encodeToString(data, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    private fun decodeBase64Url(input: String): ByteArray {
        return Base64.decode(input, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    companion object {
        private const val CHALLENGE_SIZE = 32
        private const val COSE_ALG_ES256 = -7 // ECDSA w/ SHA-256, P-256 curve
    }
}

/**
 * Configuration for passkey operations.
 *
 * @property rpId Relying Party identifier (domain). Must match the app's Digital Asset Links.
 * @property rpName Human-readable name for the relying party (shown in password manager UI).
 * @property timeoutMs Timeout for the credential ceremony in milliseconds.
 */
data class PasskeyConfig(
    val rpId: String,
    val rpName: String = "Kuira",
    val timeoutMs: Long = 60_000L,
)

/**
 * Result of a successful passkey registration (creation) ceremony.
 */
class PasskeyRegistrationResult(
    /** The extracted P-256 public key (root key of the sigil). */
    val publicKey: P256PublicKey,
    /** Credential ID — needed for future authentication ceremonies. */
    val credentialId: String,
    /** Full registration response JSON (for audit/debugging). */
    val registrationResponseJson: String,
    /**
     * PRF output for the first requested salt, when the authenticator
     * evaluated PRF during registration (PRF-on-create). Null when not
     * requested or unsupported — caller then derives via a GET ceremony.
     */
    val prfOutput: ByteArray? = null,
    /** PRF output for the second salt (multi-salt PRF-on-create), else null. */
    val prfOutputSecond: ByteArray? = null,
)

/**
 * Result of a successful passkey authentication (assertion) ceremony.
 */
class PasskeyAssertionResult(
    /** Credential ID used for this assertion. */
    val credentialId: String,
    /** Raw authenticator data bytes. */
    val authenticatorData: ByteArray,
    /** Raw client data JSON bytes. */
    val clientDataJson: ByteArray,
    /** ECDSA P-256 signature over authenticatorData + SHA-256(clientDataJSON). */
    val signature: ByteArray,
    /** Full assertion response JSON (for audit/debugging). */
    val assertionResponseJson: String,
)

/**
 * Result of a passkey authentication with PRF extension.
 * Extends the standard assertion with the 32-byte PRF output.
 */
class PrfAssertionResult(
    /** Credential ID used for this assertion. */
    val credentialId: String,
    /** Raw authenticator data bytes. */
    val authenticatorData: ByteArray,
    /** Raw client data JSON bytes. */
    val clientDataJson: ByteArray,
    /** ECDSA P-256 signature. */
    val signature: ByteArray,
    /** Full assertion response JSON. */
    val assertionResponseJson: String,
    /**
     * 32-byte PRF output — deterministic for the same passkey + salt.
     * Null if the authenticator doesn't support PRF or the extension wasn't evaluated.
     */
    val prfOutput: ByteArray?,
    /**
     * 32-byte PRF output for the **second** salt when the caller passed
     * one to `authenticateWithPrf`. Null when the caller didn't request
     * a second salt OR when the authenticator only evaluated the first.
     *
     * WebAuthn's PRF extension supports `eval.first` and `eval.second`
     * in a single assertion — modern authenticators (Google Password
     * Manager since Chrome 132, hardware tokens) collapse "derive two
     * domain-separated secrets" to one biometric prompt. Callers that
     * need two outputs should always pass both salts and check this
     * field; null means fall back to a second ceremony.
     */
    val prfOutputSecond: ByteArray? = null,
) {
    /** Whether PRF produced a result for the primary salt. */
    val hasPrf: Boolean get() = prfOutput != null && prfOutput.size == 32

    /** Whether PRF produced a result for the secondary salt (multi-salt ceremony). */
    val hasPrfSecond: Boolean get() = prfOutputSecond != null && prfOutputSecond.size == 32
}

open class PasskeyException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * Thrown by a GET ceremony when the platform reports NO credential exists for
 * the relying party — as opposed to a failed or user-cancelled ceremony. Lets
 * callers implement sign-in-if-exists: reuse an existing sigil, and forge a new
 * one only when none is present.
 */
class NoPasskeyCredentialException(
    message: String,
    cause: Throwable? = null,
) : PasskeyException(message, cause)
