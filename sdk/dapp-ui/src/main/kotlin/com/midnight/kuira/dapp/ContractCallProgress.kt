package com.midnight.kuira.dapp

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.midnight.kuira.core.compact.BalanceProgress
import com.midnight.kuira.core.compact.ContractCallStage

/**
 * Drop-in progress UI for a contract call/deploy, fed by the
 * [ContractCallStage] stream that [com.midnight.kuira.core.compact.MidnightContract.call]
 * and `deploy` emit through their `onProgress` callback.
 *
 * **Why this exists.** A deploy / commit / reveal can sit 30–120 s while the tx
 * proves, balances dust, submits, and finalizes. Without a progress surface the
 * UI looks frozen — indistinguishable from a hang. This gives every dApp the
 * same staged bar + human-readable stage label the example apps hand-rolled,
 * so users always see forward motion.
 *
 * **Two entry points:**
 *  - [ContractCallProgressBar] — stateless: pass the current [ContractCallStage]
 *    (or `null` when no call is in flight) and it renders / hides itself.
 *  - [rememberContractCallProgress] — convenience holder that owns the state and
 *    hands you a `report` to pass straight to `onProgress`.
 *
 * The accent colour is caller-supplied so a host can tint each operation
 * (deploy vs commit vs reveal) distinctly while sharing one bar.
 */

/** Human-readable default label for a [ContractCallStage]; `null` if none applies. */
fun ContractCallStage.defaultLabel(): String? = when (this) {
    is ContractCallStage.FetchingState -> "Fetching contract state…"
    is ContractCallStage.Executing -> "Building transaction…"
    is ContractCallStage.Proving -> "Generating zero-knowledge proof…"
    is ContractCallStage.Balancing -> "Balancing dust fee…"
    is ContractCallStage.BalancingDetail -> when (progress) {
        is BalanceProgress.SyncingDust -> "Syncing dust wallet…"
        is BalanceProgress.SyncingDustProgress -> "Syncing dust wallet…"
        is BalanceProgress.ProvingDust -> "Proving dust payment…"
        is BalanceProgress.Submitting -> "Submitting to chain…"
        is BalanceProgress.WaitingFinalization -> "Waiting for block finalization…"
        is BalanceProgress.RetryingDustSync -> "Retrying dust sync…"
        // Self-healing error-115 recovery — frame it as "this can take a moment",
        // never as an error or hang (see BalanceProgress.RecoveringDustState).
        is BalanceProgress.RecoveringDustState -> "Recovering dust balance — this can take a moment…"
    }
    is ContractCallStage.Submitting -> "Submitting to chain…"
}

/**
 * Maps a [ContractCallStage] to a 0..1 progress value for the bar.
 *
 * Bands are weighted by typical duration, not evenly — proving is near-instant
 * while finalization dominates, so finalization sits near-full (an evenly-spaced
 * bar would stall there and read as a hang). Dust-sync sub-progress fills its
 * own band so the user sees smooth motion without raw event counts. Callers
 * should clamp the displayed value monotonic so a retry/recovery stage that
 * reports a lower value never makes the bar visibly retreat — [ContractCallProgressBar]
 * does this for you.
 */
fun ContractCallStage.progressFraction(): Float = when (this) {
    is ContractCallStage.FetchingState -> 0.08f
    is ContractCallStage.Executing -> 0.15f
    is ContractCallStage.Proving -> 0.30f
    is ContractCallStage.Balancing -> 0.35f
    is ContractCallStage.BalancingDetail -> when (val p = progress) {
        is BalanceProgress.SyncingDust -> 0.40f
        is BalanceProgress.SyncingDustProgress -> {
            val frac = if (p.totalEvents > 0) {
                (p.eventsProcessed.toFloat() / p.totalEvents).coerceIn(0f, 1f)
            } else 0f
            0.40f + frac * 0.15f // 0.40 → 0.55 within the dust-sync band
        }
        is BalanceProgress.ProvingDust -> 0.62f
        is BalanceProgress.Submitting -> 0.72f
        is BalanceProgress.WaitingFinalization -> 0.90f
        is BalanceProgress.RetryingDustSync -> 0.40f
        is BalanceProgress.RecoveringDustState -> 0.50f
    }
    is ContractCallStage.Submitting -> 0.80f
}

/** Visual defaults for [ContractCallProgressBar]; dark-theme friendly. */
object ContractCallProgressDefaults {
    val Accent = Color(0xFF64B5F6)
    val Track = Color.White.copy(alpha = 0.14f)
    val Label = Color.White.copy(alpha = 0.70f)
}

/**
 * Stateless staged progress bar. Renders the [stage]'s label above a slim,
 * monotonic (never retreats), eased fill; hides itself when [stage] is `null`
 * (no call in flight) and resets between calls.
 *
 * @param stage current contract-call stage, or `null` when idle.
 * @param accent fill colour — tint per operation (deploy/commit/reveal).
 */
@Composable
fun ContractCallProgressBar(
    stage: ContractCallStage?,
    modifier: Modifier = Modifier,
    accent: Color = ContractCallProgressDefaults.Accent,
    trackColor: Color = ContractCallProgressDefaults.Track,
    labelColor: Color = ContractCallProgressDefaults.Label,
    showLabel: Boolean = true,
) {
    // Monotonic peak, reset when the call ends (stage → null) so the next call
    // starts from empty rather than the previous call's high-water mark.
    var peak by remember { mutableFloatStateOf(0f) }
    // Keep the last non-null label so it survives the exit fade instead of
    // blanking the instant the call completes.
    var lastLabel by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(stage) {
        if (stage == null) {
            peak = 0f
        } else {
            peak = maxOf(peak, stage.progressFraction())
            stage.defaultLabel()?.let { lastLabel = it }
        }
    }
    val animated by animateFloatAsState(
        targetValue = peak,
        animationSpec = tween(durationMillis = 450, easing = FastOutSlowInEasing),
        label = "contractCallProgress",
    )

    AnimatedVisibility(visible = stage != null, enter = fadeIn(), exit = fadeOut()) {
        Column(modifier = modifier.fillMaxWidth()) {
            if (showLabel && lastLabel != null) {
                Text(
                    text = lastLabel!!,
                    color = labelColor,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(modifier = Modifier.height(8.dp))
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(trackColor),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(animated.coerceIn(0f, 1f))
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(3.dp))
                        .background(accent),
                )
            }
        }
    }
}

/**
 * Holder for the contract-call stage stream. Pass [report] straight to a
 * contract call's `onProgress`, render [stage] with [ContractCallProgressBar],
 * and call [clear] when the call finishes (typically in a `finally`).
 *
 * ```kotlin
 * val progress = rememberContractCallProgress()
 * // in a coroutine:
 * try {
 *     contract.call("post", message, onProgress = progress::report)
 * } finally {
 *     progress.clear()
 * }
 * // in the composable tree:
 * ContractCallProgressBar(progress.stage, accent = MyColors.Post)
 * ```
 */
class ContractCallProgressState {
    var stage by mutableStateOf<ContractCallStage?>(null)
        private set

    /** Pass this as a contract call's `onProgress`. */
    fun report(stage: ContractCallStage) {
        this.stage = stage
    }

    /** Clear the bar once the call completes (or fails). */
    fun clear() {
        stage = null
    }
}

/** Remembers a [ContractCallProgressState] across recompositions. */
@Composable
fun rememberContractCallProgress(): ContractCallProgressState =
    remember { ContractCallProgressState() }
