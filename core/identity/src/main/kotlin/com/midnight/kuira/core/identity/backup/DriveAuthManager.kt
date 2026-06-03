package com.midnight.kuira.core.identity.backup

import android.content.Context
import android.content.Intent
import android.content.IntentSender
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.tasks.await

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
        is AuthorizeOutcome.NeedsConsent ->
            throw IllegalStateException("Drive consent not granted — enable cloud backup first")
    }

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

    private fun AuthorizationResult.toDriveAuth(): DriveAuth {
        val token = accessToken
            ?: throw IllegalStateException("AuthorizationResult had no access token")
        return DriveAuth(accessToken = token, accountEmail = null)
    }

    companion object {
        const val DRIVE_APPDATA_SCOPE = "https://www.googleapis.com/auth/drive.appdata"
    }
}
