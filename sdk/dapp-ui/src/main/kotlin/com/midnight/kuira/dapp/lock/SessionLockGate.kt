package com.midnight.kuira.dapp.lock

import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.withTimeoutOrNull
import com.midnight.kuira.core.designsystem.effect.StarField
import com.midnight.kuira.dapp.wallet.LockGlyph
import com.midnight.kuira.dapp.wallet.ThemeStore
import com.midnight.kuira.dapp.wallet.WalletPanelColors
import com.midnight.kuira.dapp.wallet.WalletThemes
import com.midnight.kuira.sdk.walletruntime.SessionLock
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.launch

/**
 * Whole-app session lock — the Chase/banking model.
 *
 * Wrap a host's entire Compose root in this once:
 * ```
 * setContent { SessionLockGate { BBoardApp() } }
 * ```
 * While [SessionLock.locked] is true it covers the **whole app** with a re-auth
 * screen: nothing behind it is visible or tappable, and the window is marked
 * `FLAG_SECURE` so the recents/app-switcher thumbnail doesn't leak content
 * either. This is what makes the lock real — unlike hiding one balance, the host
 * never has to decide which screens hold sensitive data; everything is gated.
 *
 * **Unlock** runs the genuine biometric ([SessionLock.unlock] →
 * `WalletSeedSource.ensureSeedReady`). It auto-prompts when the app returns to
 * the foreground while locked (like Face ID on app open); a manual "unlock"
 * button is the fallback after a cancel. Cancelling keeps the app locked.
 *
 * The host still calls [SessionLock.attach] from `Application.onCreate` and
 * forwards `onUserInteraction()` for idle detection — the gate is only the
 * presentation + unlock affordance.
 *
 * @param secureWindow when true (default) the wrapped activity's window is
 *   marked `FLAG_SECURE` for its whole lifetime — no screenshots, clean recents
 *   thumbnail (the banking default). A host that needs screenshots (e.g. a game
 *   menu) can pass false; the lock overlay still covers content while locked.
 */
@Composable
fun SessionLockGate(
    modifier: Modifier = Modifier,
    secureWindow: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val activity = context as? FragmentActivity
    val sessionLock = remember(context) {
        EntryPointAccessors
            .fromApplication(context.applicationContext, SessionLock.SessionLockEntryPoint::class.java)
            .sessionLock()
    }
    val locked by sessionLock.locked.collectAsStateWithLifecycle()

    // FLAG_SECURE for the wrapped activity's whole lifetime: blocks screenshots
    // and keeps the recents thumbnail clean even during the background grace
    // BEFORE the lock fires (when content would otherwise be snapshotted).
    if (secureWindow) {
        DisposableEffect(activity) {
            val window = activity?.window
            window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        content()
        if (locked) {
            SessionLockScreen(sessionLock = sessionLock, activity = activity)
        }
    }
}

/**
 * The full-screen lock surface. Drawn on top of (and fully obscuring) the app.
 * Auto-prompts biometric on every foreground return while locked; offers a
 * manual retry after a cancel.
 */
@Composable
private fun SessionLockScreen(
    sessionLock: SessionLock,
    activity: FragmentActivity?,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var unlocking by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }

    // Match the rest of the app: resolve the user's persisted appearance + light/dark. ThemeStore is
    // durable, so the lock surface is themed even on a cold start, before the wallet panel composes.
    val colors = remember {
        val id = ThemeStore.selectedThemeId(context) ?: WalletThemes.Default.id
        val themed = if (id == WalletThemes.Default.id) WalletPanelColors.Default else WalletThemes.byId(id).colors
        if (ThemeStore.lightMode(context)) WalletPanelColors.Light else themed
    }

    // A field under the cover may have held focus when the lock took over, floating its IME over the
    // lock. Dismiss it so the lock owns the screen.
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) { keyboard?.hide() }

    fun attemptUnlock() {
        val act = activity ?: return
        if (unlocking) return
        unlocking = true
        failed = false
        scope.launch {
            // Bound the attempt: if the biometric never resolves (prompt failed to
            // show, contention, etc.) the spinner must NOT trap the user — fall back
            // to the retry button. withTimeoutOrNull cancels the stuck unlock, which
            // cancels the underlying BiometricPrompt cleanly.
            val ok = withTimeoutOrNull(UNLOCK_TIMEOUT_MS) {
                sessionLock.unlock(act) // clears `locked` on success → gate dismisses
            } ?: false
            unlocking = false
            failed = !ok
        }
    }

    // Auto-prompt biometric on every foreground RETURN while locked (screen
    // unlocked, app switched back) — the Face-ID-on-open behaviour. We key on the
    // ON_RESUME *event*, NOT on the screen first appearing: a manual "lock now"
    // or idle-lock happens while already foregrounded (no resume), so it shows
    // the lock screen and waits for the user to tap unlock instead of instantly
    // re-prompting. BiometricPrompt also needs a resumed FragmentActivity, so
    // ON_RESUME is the correct, safe moment to fire.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) attemptUnlock()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.sheetBackground)
            // Absorb ALL input so nothing reaches the app underneath.
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) {},
        contentAlignment = Alignment.Center,
    ) {
        // Brand "stars against void" texture, themed + SIZED to the screen — the missing
        // matchParentSize was why it painted nothing (it laid out at zero bounds).
        StarField(
            modifier = Modifier.matchParentSize(),
            color = colors.onSheet,
            alpha = 0.5f,
            starCount = 40,
        )
        Column(
            modifier = Modifier
                .safeDrawingPadding()
                .widthIn(max = LOCK_PANEL_MAX_WIDTH)
                .padding(horizontal = LOCK_SCREEN_HPADDING),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            LockGlyph(color = colors.onSheet, size = LOCK_ICON_SIZE)
            Spacer(modifier = Modifier.height(LOCK_GAP_ICON_TITLE))
            Text(
                "LOCKED",
                color = colors.onSheet,
                fontSize = LOCK_TITLE_SIZE,
                fontWeight = FontWeight.W400,
                letterSpacing = LOCK_TITLE_TRACKING,
            )
            Spacer(modifier = Modifier.height(LOCK_GAP_TITLE_BODY))
            Text(
                "Your session is locked. Unlock with your device biometric to " +
                    "continue. Your identity stays signed in.",
                color = colors.onSheetDim,
                fontSize = LOCK_BODY_SIZE,
                lineHeight = LOCK_BODY_LINE_HEIGHT,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(LOCK_GAP_BODY_PANEL))
            // A failed attempt is a quiet retry line ABOVE the button — no card, monochrome (auth
            // retry isn't a financial-danger signal, so never red).
            if (failed && !unlocking) {
                Text(
                    "Authentication didn't complete — tap to try again.",
                    color = colors.onSheetSubtle,
                    fontSize = LOCK_HINT_SIZE,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(LOCK_GAP_HINT_BUTTON))
            }
            // One clean, themed button — no box.
            Button(
                onClick = { attemptUnlock() },
                enabled = activity != null,
                modifier = Modifier.fillMaxWidth().height(LOCK_BUTTON_HEIGHT),
                shape = RoundedCornerShape(LOCK_BUTTON_RADIUS),
                colors = ButtonDefaults.buttonColors(
                    containerColor = colors.button,
                    contentColor = colors.onButton,
                    disabledContainerColor = colors.buttonDisabled,
                    disabledContentColor = colors.onButtonDisabled,
                ),
            ) {
                if (unlocking) {
                    CircularProgressIndicator(
                        color = colors.onButton,
                        strokeWidth = 2.dp,
                        modifier = Modifier.height(LOCK_SPINNER_SIZE),
                    )
                } else {
                    Text("Unlock", fontSize = LOCK_BUTTON_LABEL_SIZE, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}

// ── Lock-screen dimensions ──
// Self-contained one-off screen, so the tokens live here rather than in the
// panel's shared PanelDimens/PanelType (which are scoped to the wallet panel).
private const val UNLOCK_TIMEOUT_MS = 60_000L // biometric can take a while; cap so the spinner never traps
private val LOCK_PANEL_MAX_WIDTH = 360.dp   // keep readable on tablets / landscape
private val LOCK_SCREEN_HPADDING = 28.dp
private val LOCK_ICON_SIZE = 48.dp   // line-art LockGlyph (was a 🔒 emoji at 44.sp)
private val LOCK_TITLE_SIZE = 13.sp
private val LOCK_TITLE_TRACKING = 4.sp
private val LOCK_BODY_SIZE = 14.sp
private val LOCK_BODY_LINE_HEIGHT = 21.sp
private val LOCK_HINT_SIZE = 13.sp
private val LOCK_BUTTON_LABEL_SIZE = 15.sp
private val LOCK_BUTTON_HEIGHT = 54.dp      // prominent CTA — above the 48dp HIG floor
private val LOCK_BUTTON_RADIUS = 16.dp      // soft-rounded, elegant — not a hard rectangle
private val LOCK_SPINNER_SIZE = 28.dp
private val LOCK_GAP_ICON_TITLE = 20.dp
private val LOCK_GAP_TITLE_BODY = 14.dp
private val LOCK_GAP_BODY_PANEL = 28.dp
private val LOCK_GAP_HINT_BUTTON = 12.dp
