package com.midnight.kuira.core.identity.backup

import android.content.Context
import android.content.Intent
import android.content.IntentSender
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * An OAuth access token (+ the account it was granted on) for the Google Drive
 * `appDataFolder` scope. The token is short-lived and held only in memory by
 * callers — never persisted by us.
 */
data class DriveAuth(
    val accessToken: String,
    /** The Google account email the scope was granted on, when the platform exposes it. */
    val accountEmail: String?,
)

/** Result of an authorization attempt. */
sealed interface AuthorizeOutcome {
    /** Consent already exists — token is ready, no UI needed. */
    data class Authorized(val auth: DriveAuth) : AuthorizeOutcome

    /**
     * First-time (or re-)consent required. The UI must launch [intentSender] via
     * an ActivityResultLauncher and feed the returned Intent to
     * [DriveAuthManager.tokenFromConsent].
     */
    data class NeedsConsent(val intentSender: IntentSender) : AuthorizeOutcome
}

/**
 * Obtains authorization for the Drive `appDataFolder` scope via Play Services
 * `AuthorizationClient`. This is separate from the passkey/Credential-Manager
 * flow but resolves to the same Google account the user picks at consent.
 */
interface DriveAuthManager {
    /**
     * Request the `drive.appdata` scope. Returns [AuthorizeOutcome.Authorized]
     * when consent already exists (silent), or [AuthorizeOutcome.NeedsConsent]
     * carrying an IntentSender the UI must launch.
     */
    suspend fun authorize(): AuthorizeOutcome

    /** Extract the token from the consent activity result Intent. */
    fun tokenFromConsent(data: Intent?): DriveAuth

    /**
     * Revoke the Drive `appDataFolder` grant (#246 — true backup disable). After this the app no
     * longer has Drive access; the next [authorize] returns [AuthorizeOutcome.NeedsConsent]. No-op
     * if nothing is currently granted. Best-effort: a network failure to reach the revoke endpoint
     * is logged, not thrown (the local grant is gone from the user's intent either way).
     */
    suspend fun revoke()
}

/**
 * Returns a `drive.appdata` access token **without UI**, or throws if consent
 * has not been granted yet. Use from headless paths (e.g. the dust backup
 * coordinator during a routine refresh) — the first-time consent must come
 * from a UI flow ([DriveAuthManager.authorize] + launching the IntentSender).
 */
suspend fun DriveAuthManager.silentTokenOrThrow(): String =
    when (val outcome = authorize()) {
        is AuthorizeOutcome.Authorized -> outcome.auth.accessToken
        is AuthorizeOutcome.NeedsConsent -> throw DriveConsentRequiredException()
    }

/**
 * Thrown by [silentTokenOrThrow] when Drive backup hasn't been consented to yet
 * — a distinct, catchable signal (vs a generic failure) so a headless backup can
 * surface "needs consent" instead of a silent error. Extends
 * [IllegalStateException] so existing best-effort `catch (Exception)` paths still
 * behave as before.
 */
class DriveConsentRequiredException :
    IllegalStateException("Drive consent not granted — enable cloud backup first")

class PlayServicesDriveAuthManager(
    context: Context,
) : DriveAuthManager {

    private val client = Identity.getAuthorizationClient(context)

    private val request: AuthorizationRequest =
        AuthorizationRequest.builder()
            .setRequestedScopes(listOf(Scope(DRIVE_APPDATA_SCOPE)))
            .build()

    override suspend fun authorize(): AuthorizeOutcome {
        val result = client.authorize(request).await()
        return if (result.hasResolution()) {
            val pendingIntent = result.pendingIntent
                ?: throw IllegalStateException("Authorization needs resolution but no PendingIntent was provided")
            AuthorizeOutcome.NeedsConsent(pendingIntent.intentSender)
        } else {
            AuthorizeOutcome.Authorized(result.toDriveAuth())
        }
    }

    override fun tokenFromConsent(data: Intent?): DriveAuth =
        client.getAuthorizationResultFromIntent(data).toDriveAuth()

    override suspend fun revoke(): Unit = withContext(Dispatchers.IO) {
        // The Identity AuthorizationClient has no revoke API; revoke the grant server-side via
        // Google's OAuth revoke endpoint using a current token. Nothing granted → nothing to do.
        val token = when (val outcome = authorize()) {
            is AuthorizeOutcome.Authorized -> outcome.auth.accessToken
            is AuthorizeOutcome.NeedsConsent -> return@withContext
        }
        runCatching {
            // Pure query-param POST (Google's revoke endpoint accepts ?token=). No request body, so
            // NO doOutput (that would force a Content-Length:0 / body wait). URL-encode the token.
            val encoded = URLEncoder.encode(token, "UTF-8")
            val conn = (URL("$REVOKE_ENDPOINT?token=$encoded").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            }
            try {
                val code = conn.responseCode
                // 200 = revoked. The endpoint also returns 400 for an already-invalid token, which
                // for our purpose (the grant is gone) is success enough.
                Log.i(TAG, "Drive consent revoke → HTTP $code")
            } finally {
                conn.disconnect()
            }
        }.onFailure { Log.w(TAG, "Drive consent revoke failed (network): ${it.message}") }
    }

    private fun AuthorizationResult.toDriveAuth(): DriveAuth {
        val token = accessToken
            ?: throw IllegalStateException("AuthorizationResult had no access token")
        return DriveAuth(accessToken = token, accountEmail = null)
    }

    companion object {
        private const val TAG = "DriveAuthManager"
        const val DRIVE_APPDATA_SCOPE = "https://www.googleapis.com/auth/drive.appdata"

        /** Google's OAuth 2.0 token/grant revocation endpoint (#246). */
        private const val REVOKE_ENDPOINT = "https://oauth2.googleapis.com/revoke"
    }
}
