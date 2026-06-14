package com.midnight.kuira.sdk.walletruntime

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * POST_NOTIFICATIONS helper for the dust-sync Live Update (#235).
 *
 * The runtime permission (API 33+) only controls notification *visibility* — the
 * foreground service and the sync run regardless, so this is best-effort. The SDK
 * can't show the system permission dialog from a library service, so the host
 * requests [PERMISSION] with the standard `ActivityResultContracts.RequestPermission`
 * (one line in a `ComponentActivity`/Compose), using [isGranted] to gate the ask.
 */
object DustSyncNotifications {

    /** The runtime permission gating notification visibility on API 33+. */
    const val PERMISSION: String = Manifest.permission.POST_NOTIFICATIONS

    /** True if notifications can be posted (always true below API 33). */
    fun isGranted(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, PERMISSION) == PackageManager.PERMISSION_GRANTED

    /** Whether asking makes sense (API 33+ and not already granted). */
    fun shouldRequest(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !isGranted(context)
}
