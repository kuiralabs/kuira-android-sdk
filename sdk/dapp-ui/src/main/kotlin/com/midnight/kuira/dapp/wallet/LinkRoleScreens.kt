package com.midnight.kuira.dapp.wallet

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.midnight.kuira.core.identity.link.LinkException
import com.midnight.kuira.core.identity.link.NewDeviceLinkSession
import com.midnight.kuira.core.identity.link.SourceLinkSession
import com.midnight.kuira.dapp.R
import kotlinx.coroutines.launch

/** The QR has to be big enough for another phone's camera to read across a table. */
private val LinkQrSize = 240.dp

/**
 * The ceremony on the phone that NEEDS the wallet.
 *
 * Three beats, and each names what the other phone should be doing. A camera pointed at a phone
 * that is not ready yet looks exactly like a camera that is broken, which is how the first iOS cut
 * stranded people silently.
 */
@Composable
internal fun NewDeviceLinkFlow(
    deliver: suspend (ByteArray) -> Unit,
    palette: SendPalette,
    onFinish: () -> Unit,
) {
    // One session per visit to this role. Rebuilding it would mint a fresh ephemeral key and
    // silently invalidate the code the other phone is already looking at.
    val session = remember { NewDeviceLinkSession(deliver) }
    val state by session.state.collectAsState()
    val scope = rememberCoroutineScope()
    val failureCopy = rememberLinkFailureCopy()
    var scanError by remember { mutableStateOf<String?>(null) }

    when (val s = state) {
        is NewDeviceLinkSession.State.ShowingRequest -> LinkStep(
            step = 1,
            title = stringResource(R.string.kuira_link_show_this_code),
            detail = stringResource(R.string.kuira_link_show_this_code_detail),
            palette = palette,
        ) {
            QrCode(text = session.requestQrText, size = LinkQrSize, modifier = Modifier.testTag("linkRequestQr"))
            LinkSpacer()
            // NOT "Done" — the only button on a screen gets tapped immediately, and tapping this
            // early lands the person on a camera with nothing yet in existence to scan.
            SendPrimaryButton(
                text = stringResource(R.string.kuira_link_shown_it),
                onClick = session::readyToScan,
                palette = palette,
                modifier = Modifier.testTag("linkShownIt"),
            )
        }

        is NewDeviceLinkSession.State.ScanningResponse -> LinkStep(
            step = 2,
            title = stringResource(R.string.kuira_link_scan_their_code),
            detail = stringResource(R.string.kuira_link_scan_their_code_detail),
            palette = palette,
            error = scanError,
        ) {
            LinkScanner(palette = palette) { text ->
                scanError = runCatching { session.scan(text) }.exceptionOrNull()?.let(failureCopy::of)
            }
            LinkSpacer()
            SendSecondaryButton(
                text = stringResource(R.string.kuira_link_show_mine_again),
                onClick = session::showRequestAgain,
                palette = palette,
            )
        }

        is NewDeviceLinkSession.State.AwaitingConfirmation -> LinkStep(
            step = 3,
            title = stringResource(R.string.kuira_link_code_question),
            detail = stringResource(R.string.kuira_link_code_why),
            palette = palette,
        ) {
            LinkCode(s.code, palette)
            LinkSpacer()
            // "They differ" is the SAFE answer, so it must not be the timid one. Both are given
            // equal weight, and the destructive-sounding option is not hidden.
            SendButtonRow(
                secondaryText = stringResource(R.string.kuira_link_codes_differ),
                primaryText = stringResource(R.string.kuira_link_codes_match),
                onSecondary = session::abort,
                onPrimary = { scope.launch { session.confirm() } },
                palette = palette,
            )
        }

        is NewDeviceLinkSession.State.Linked -> LinkOutcome(
            title = stringResource(R.string.kuira_link_done),
            detail = stringResource(R.string.kuira_link_done_detail),
            palette = palette,
            onFinish = onFinish,
            tag = "linkDone",
        )

        is NewDeviceLinkSession.State.Cancelled -> LinkOutcome(
            title = stringResource(R.string.kuira_link_cancelled),
            detail = null,
            palette = palette,
            onFinish = onFinish,
        )

        is NewDeviceLinkSession.State.Failed -> LinkOutcome(
            title = stringResource(R.string.kuira_link_failed),
            detail = failureCopy.of(s.cause),
            palette = palette,
            onFinish = onFinish,
        )
    }
}

/**
 * The ceremony on the phone that HAS the wallet.
 *
 * The seed is read only after approval — reading it is what raises the biometric prompt, so an
 * earlier read would ask for a fingerprint before saying what it is for.
 */
@Composable
internal fun SourceLinkFlow(
    entropy: suspend () -> ByteArray,
    palette: SendPalette,
    onFinish: () -> Unit,
) {
    val session = remember { SourceLinkSession(entropy) }
    val state by session.state.collectAsState()
    val scope = rememberCoroutineScope()
    val failureCopy = rememberLinkFailureCopy()
    var scanError by remember { mutableStateOf<String?>(null) }

    when (val s = state) {
        is SourceLinkSession.State.Scanning -> LinkStep(
            step = 1,
            title = stringResource(R.string.kuira_link_scan_other_phone),
            detail = stringResource(R.string.kuira_link_scan_detail),
            palette = palette,
            error = scanError,
        ) {
            LinkScanner(palette = palette) { text ->
                scanError = runCatching { session.scan(text) }.exceptionOrNull()?.let(failureCopy::of)
            }
        }

        is SourceLinkSession.State.AwaitingApproval -> LinkStep(
            step = 2,
            title = stringResource(R.string.kuira_link_approve_title),
            detail = stringResource(R.string.kuira_link_approve_detail),
            palette = palette,
        ) {
            SendButtonRow(
                secondaryText = stringResource(R.string.kuira_link_decline),
                primaryText = stringResource(R.string.kuira_link_approve),
                onSecondary = session::abort,
                onPrimary = { scope.launch { session.approve() } },
                palette = palette,
                modifier = Modifier.testTag("linkApprove"),
            )
        }

        is SourceLinkSession.State.ShowingResponse -> LinkStep(
            step = 3,
            title = stringResource(R.string.kuira_link_code_question),
            detail = stringResource(R.string.kuira_link_waiting_for_scan),
            palette = palette,
        ) {
            QrCode(text = s.qrText, size = LinkQrSize, modifier = Modifier.testTag("linkResponseQr"))
            LinkSpacer()
            LinkCode(s.code, palette)
            LinkSpacer()
            // This phone is finished the moment the other one has scanned and compared. There is
            // nothing further for it to confirm, so the only action is to leave.
            SendSecondaryButton(
                text = stringResource(R.string.kuira_link_done_button),
                onClick = onFinish,
                palette = palette,
            )
        }

        is SourceLinkSession.State.Cancelled -> LinkOutcome(
            title = stringResource(R.string.kuira_link_cancelled),
            detail = null,
            palette = palette,
            onFinish = onFinish,
        )

        is SourceLinkSession.State.Failed -> LinkOutcome(
            title = stringResource(R.string.kuira_link_failed),
            detail = failureCopy.of(s.cause),
            palette = palette,
            onFinish = onFinish,
        )
    }
}

// ── Shared pieces ──

/** One beat of the ceremony: which phone and how far along, a title, a detail, then the content. */
@Composable
private fun LinkStep(
    step: Int,
    title: String,
    detail: String,
    palette: SendPalette,
    error: String? = null,
    content: @Composable () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(SendDimens.Space12),
    ) {
        // Names the phone as well as the number: both devices are mid-ceremony at once, and a bare
        // "step 1 of 3" on each reads as two independent wizards rather than one alternating dance.
        Text(
            text = stringResource(R.string.kuira_link_step_this_phone, step),
            color = palette.textMuted,
            fontSize = SendType.SectionLabel,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = title,
            color = palette.text,
            fontSize = SendType.SuccessHeadline,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.fillMaxWidth().semantics { heading() },
        )
        Text(text = detail, color = palette.textSoft, fontSize = SendType.Caption, modifier = Modifier.fillMaxWidth())
        if (error != null) {
            Text(
                text = error,
                color = palette.error,
                fontSize = SendType.Caption,
                modifier = Modifier.fillMaxWidth().testTag("linkError"),
            )
        }
        content()
    }
}

/**
 * The six digits, rendered big and monospaced because their whole job is to be compared against
 * another screen across a table — and read aloud between two people when the phones are not side by
 * side. Spaced so a transposition is visible rather than lost in a run of digits.
 */
@Composable
private fun LinkCode(code: String, palette: SendPalette) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(SendDimens.RadiusMd))
            .background(palette.panel)
            .padding(vertical = SendDimens.Space20)
            .testTag("linkCode"),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = code.toCharArray().joinToString(" "),
            color = palette.text,
            fontSize = SendType.HeroNumber,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium,
        )
    }
}

/**
 * The camera, reusing the Send wizard's scanner rather than standing up a second pipeline — there
 * is one camera surface in this module and this is it.
 *
 * Cancelling the scanner is deliberately a no-op here: the ceremony's own Back is in the header,
 * and a scanner that could vanish mid-step would leave the person on a screen with no camera and
 * the other phone still waiting.
 */
@Composable
private fun LinkScanner(palette: SendPalette, onText: (String) -> Unit) {
    Box(Modifier.fillMaxWidth().height(LinkQrSize).clip(RoundedCornerShape(SendDimens.RadiusMd))) {
        QrScannerScreen(palette = palette, onResult = onText, onCancel = {})
    }
}

/** A terminal screen — done, stopped, or failed — with the one way out. */
@Composable
private fun LinkOutcome(
    title: String,
    detail: String?,
    palette: SendPalette,
    onFinish: () -> Unit,
    tag: String? = null,
) {
    Column(
        Modifier.fillMaxWidth().then(if (tag != null) Modifier.testTag(tag) else Modifier),
        verticalArrangement = Arrangement.spacedBy(SendDimens.Space12),
    ) {
        Text(
            text = title,
            color = palette.text,
            fontSize = SendType.SuccessHeadline,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.semantics { heading() },
        )
        if (detail != null) {
            Text(text = detail, color = palette.textSoft, fontSize = SendType.Caption)
        }
        SendPrimaryButton(
            text = stringResource(R.string.kuira_link_done_button),
            onClick = onFinish,
            palette = palette,
        )
    }
}

@Composable
private fun LinkSpacer() = Box(Modifier.height(SendDimens.Space12))

/**
 * Why a link failed, in the person's terms.
 *
 * The session keeps these apart on purpose: collapsing them sends someone to re-run a ceremony that
 * was never the problem. A declined fingerprint is a choice, and a full keystore is this phone's
 * business — neither is "the code could not be read".
 */
private class LinkFailureCopy(val seedUnavailable: String, val storage: String, val generic: String) {
    fun of(cause: Throwable): String = when (cause) {
        is LinkException.SeedUnavailable -> seedUnavailable
        is LinkException.StorageFailed -> storage
        else -> generic
    }
}

/**
 * Resolves the failure copy up front, because the scan callbacks that need it are ordinary lambdas
 * — `stringResource` is `@Composable` and cannot be reached from one.
 */
@Composable
private fun rememberLinkFailureCopy(): LinkFailureCopy {
    val seedUnavailable = stringResource(R.string.kuira_link_failed_seed_unavailable)
    val storage = stringResource(R.string.kuira_link_failed_storage)
    val generic = stringResource(R.string.kuira_link_failed_generic)
    return remember(seedUnavailable, storage, generic) {
        LinkFailureCopy(seedUnavailable, storage, generic)
    }
}
