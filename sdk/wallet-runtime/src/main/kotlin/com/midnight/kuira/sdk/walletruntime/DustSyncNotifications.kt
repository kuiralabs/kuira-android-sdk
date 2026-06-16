package com.midnight.kuira.sdk.walletruntime

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * POST_NOTIFICATIONS helper for ALL of the wallet's foreground notifications (#261-264,
 * generalizing the original #235 dust-sync use): the ongoing operation/sync progress, the
 * dismissible finalization push, and the received-funds / "your turn" alerts.
 *
 * The runtime permission (API 33+) only controls notification *visibility* — the foreground
 * service, operations and sync all run regardless, so this is best-effort. The SDK can't show the
 * system permission dialog from a library service, so the host requests [PERMISSION] with the
 * standard `ActivityResultContracts.RequestPermission` (one line in a `ComponentActivity`/Compose),
 * using [isGranted] to gate the ask.
 *
 * NOTE: the object name still reads "DustSync" for historical reasons; a rename to
 * `WalletNotifications` is tracked as a follow-up (it's a public API used across the example apps).
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
