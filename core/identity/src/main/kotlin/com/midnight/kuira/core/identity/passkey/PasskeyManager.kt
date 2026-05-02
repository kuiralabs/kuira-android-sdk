package com.midnight.kuira.core.identity.passkey

import android.app.Activity
import android.util.Base64
import androidx.credentials.CreatePublicKeyCredentialRequest
import androidx.credentials.CreatePublicKeyCredentialResponse
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetPublicKeyCredentialOption
import androidx.credentials.PublicKeyCredential
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
    ): PasskeyRegistrationResult {
        val challenge = generateChallenge()
        val requestJson = buildRegistrationRequestJson(
            challenge = challenge,
            userId = userId,
            userName = userName,
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

        return PasskeyRegistrationResult(
            publicKey = publicKey,
            credentialId = credentialId,
            registrationResponseJson = registrationResponseJson,
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
        }.toString()
    }

    private fun buildAuthenticationRequestJson(challengeB64: String): String {
        return JSONObject().apply {
            put("challenge", challengeB64)
            put("rpId", config.rpId)
            put("timeout", config.timeoutMs)
            put("userVerification", "required")
        }.toString()
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

class PasskeyException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
