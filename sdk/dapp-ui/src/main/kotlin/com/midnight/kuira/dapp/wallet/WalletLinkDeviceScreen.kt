package com.midnight.kuira.dapp.wallet

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import com.midnight.kuira.core.designsystem.effect.StarField
import com.midnight.kuira.dapp.R

/**
 * The whole link-a-device ceremony, both roles. The Android half of the flow whose byte layer lives
 * in `com.midnight.kuira.core.identity.link`, and the mirror of the iOS `DeviceLinkView`.
 *
 * A full-screen surface with its own header, the same shape as [WalletRecoveryScreen]: void ground,
 * star field, back chevron, scrolling body.
 *
 * The role question comes first and in the person's own terms — "this phone has the wallet" rather
 * than "source" — because picking the wrong role wastes the whole ceremony, and *source* and *new
 * device* mean nothing to someone holding two phones.
 *
 * @param entropy reads this device's canonical 32-byte entropy from custody. Called only after the
 *   person approves, because reading it is what raises the biometric prompt.
 * @param deliver stores entropy received from the other device.
 */
@Composable
internal fun WalletLinkDeviceScreen(
    entropy: suspend () -> ByteArray,
    deliver: suspend (ByteArray) -> Unit,
    colors: WalletPanelColors = WalletPanelColors.Default,
    onFinish: () -> Unit,
) {
    val palette = remember(colors) { SendPalette.from(colors) }
    // Survives rotation: losing the chosen role mid-ceremony would drop the person back at the role
    // question with the other phone still waiting on them.
    var role by rememberSaveable { mutableStateOf(LinkRole.Undecided) }

    Box(
        Modifier
            .fillMaxSize()
            .background(palette.bg)
            .testTag("linkDeviceScreen"),
    ) {
        StarField(
            modifier = Modifier.fillMaxSize(),
            color = palette.text,
            alpha = if (palette.isLight) STAR_ALPHA_LIGHT else STAR_ALPHA_DARK,
            starCount = STAR_COUNT,
        )
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            LinkHeader(
                palette = palette,
                // Back steps out of a chosen role first, so a mis-tap costs one tap rather than the
                // whole flow — and the other phone's ceremony with it.
                onBack = { if (role == LinkRole.Undecided) onFinish() else role = LinkRole.Undecided },
            )
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(SendDimens.Space20),
            ) {
                when (role) {
                    LinkRole.Undecided -> LinkRolePicker(
                        palette = palette,
                        onSource = { role = LinkRole.Source },
                        onNew = { role = LinkRole.New },
                    )
                    LinkRole.Source -> SourceLinkFlow(entropy = entropy, palette = palette, onFinish = onFinish)
                    LinkRole.New -> NewDeviceLinkFlow(deliver = deliver, palette = palette, onFinish = onFinish)
                }
            }
        }
    }
}

/** Which phone this is. Saveable, so it has to be a plain enum. */
internal enum class LinkRole { Undecided, Source, New }

@Composable
private fun LinkHeader(palette: SendPalette, onBack: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(SendDimens.TopBarHeight).padding(horizontal = SendDimens.Space8),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val back = stringResource(R.string.kuira_link_back)
        Box(
            Modifier
                .size(SendDimens.Space48)
                .clip(RoundedCornerShape(SendDimens.RadiusFull))
                .clickable(onClick = onBack)
                .semantics { contentDescription = back }
                .testTag("linkBack"),
            contentAlignment = Alignment.Center,
        ) {
            Text("‹", color = palette.text, fontSize = SendType.HeroDenom)
        }
        Spacer(Modifier.size(SendDimens.Space8))
        Text(
            text = stringResource(R.string.kuira_link_title),
            color = palette.text,
            fontSize = SendType.TopBarAction,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.semantics { heading() },
        )
    }
}

@Composable
private fun LinkRolePicker(palette: SendPalette, onSource: () -> Unit, onNew: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(SendDimens.Space16)) {
        Text(
            text = stringResource(R.string.kuira_link_intro),
            color = palette.textSoft,
            fontSize = SendType.Body,
        )
        Text(
            text = stringResource(R.string.kuira_link_not_recovery),
            color = palette.textMuted,
            fontSize = SendType.Caption,
        )
        Spacer(Modifier.height(SendDimens.Space4))
        Text(
            text = stringResource(R.string.kuira_link_role_question),
            color = palette.text,
            fontSize = SendType.Body,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.semantics { heading() },
        )
        // Two equal options: same component, same height, same weight. Neither is the
        // recommended one, and a person holding two phones is answering a question about
        // which phone they are touching, not being steered.
        LinkPeerButton(
            text = stringResource(R.string.kuira_link_role_source),
            onClick = onSource,
            palette = palette,
            tag = "linkRoleSource",
        )
        LinkPeerButton(
            text = stringResource(R.string.kuira_link_role_new),
            onClick = onNew,
            palette = palette,
            tag = "linkRoleNew",
        )
    }
}

/**
 * A choice that carries no recommendation — used where two options are genuinely equal, so neither
 * may take the filled primary treatment. Same metrics as [SendPrimaryButton] so a row of them lines
 * up exactly; a mismatched height between two peer choices reads as one being preferred.
 */
@Composable
internal fun LinkPeerButton(
    text: String,
    onClick: () -> Unit,
    palette: SendPalette,
    modifier: Modifier = Modifier,
    tag: String? = null,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(SendDimens.ButtonHeight)
            .clip(RoundedCornerShape(SendDimens.RadiusMd))
            .background(palette.barely)
            .clickable(onClick = onClick)
            .then(if (tag != null) Modifier.testTag(tag) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = palette.text,
            fontSize = SendType.Button,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = SendDimens.Space16),
        )
    }
}
