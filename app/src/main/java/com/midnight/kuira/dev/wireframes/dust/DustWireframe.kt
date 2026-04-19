package com.midnight.kuira.dev.wireframes.dust

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Token
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.midnight.kuira.core.designsystem.component.GlassPanel
import com.midnight.kuira.core.designsystem.effect.StarField
import com.midnight.kuira.dev.wireframes.send.DuskPrimaryButtonPaletted
import com.midnight.kuira.dev.wireframes.send.ErrorCard
import com.midnight.kuira.dev.wireframes.send.StepIndicator
import com.midnight.kuira.dev.wireframes.settings.SettingsDivider
import com.midnight.kuira.dev.wireframes.settings.SettingsRow
import com.midnight.kuira.dev.wireframes.settings.SettingsSectionHeader
import com.midnight.kuira.feature.balance.redesign.DuskPalette

enum class DustWireframeState {
    DEFAULT,
    LOADING_FIRST,
    EMPTY,
    PENDING_BUILD,
    PENDING_PROVE,
    PENDING_SEAL,
    PENDING_SUBMIT,
    SUCCESS,
    ERROR,
}

private data class DustStepCopy(val label: String, val detail: String)

private fun dustStepCopy(state: DustWireframeState): DustStepCopy? = when (state) {
    DustWireframeState.PENDING_BUILD ->
        DustStepCopy("Building registration", "Preparing dust tank\u2026")
    DustWireframeState.PENDING_PROVE ->
        DustStepCopy("Generating proof", "This may take a few minutes.")
    DustWireframeState.PENDING_SEAL ->
        DustStepCopy("Sealing transaction", "Applying signature\u2026")
    DustWireframeState.PENDING_SUBMIT ->
        DustStepCopy("Submitting", "Broadcasting to the network\u2026")
    else -> null
}

private fun DustWireframeState.isPending(): Boolean = dustStepCopy(this) != null

@Composable
fun DustWireframe(
    state: DustWireframeState = DustWireframeState.DEFAULT,
    palette: DuskPalette = DuskPalette.DarkMode,
    onBack: () -> Unit = {},
) {
    val statusBarPadding = WindowInsets.statusBars.asPaddingValues()
    val navBarPadding = WindowInsets.navigationBars.asPaddingValues()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.Void),
    ) {
        StarField(
            modifier = Modifier.fillMaxSize(),
            color = palette.Light,
            alpha = if (palette === DuskPalette.LightMode) 0.55f else 1f,
            starCount = 60,
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = statusBarPadding.calculateTopPadding()),
        ) {
            TopBar(
                palette = palette,
                backEnabled = !state.isPending(),
                onBack = onBack,
            )

            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
                    .padding(bottom = navBarPadding.calculateBottomPadding() + 24.dp),
            ) {
                when (state) {
                    DustWireframeState.DEFAULT -> DefaultContent(palette)
                    DustWireframeState.LOADING_FIRST -> LoadingContent(palette)
                    DustWireframeState.EMPTY -> EmptyContent(palette)
                    DustWireframeState.SUCCESS -> SuccessContent(palette)
                    DustWireframeState.ERROR -> ErrorContent(palette)
                    else -> {
                        val copy = dustStepCopy(state)
                        if (copy != null) PendingContent(copy, palette)
                    }
                }
            }
        }
    }
}

@Composable
private fun TopBar(palette: DuskPalette, backEnabled: Boolean, onBack: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .padding(horizontal = 16.dp),
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = "Back to balance",
            tint = if (backEnabled) palette.Light else palette.LightMuted,
            modifier = Modifier
                .size(24.dp)
                .clickable(enabled = backEnabled) { onBack() },
        )
        Spacer(modifier = Modifier.width(16.dp))
        Text(
            text = "Dust",
            color = palette.Light,
            fontSize = 14.sp,
            fontWeight = FontWeight.W300,
        )
    }
    HorizontalDivider(color = palette.LightFaint, thickness = 1.dp)
}

@Composable
private fun DefaultContent(palette: DuskPalette) {
    Spacer(modifier = Modifier.height(32.dp))

    GlassPanel(tint = palette.contentPanel, border = palette.LightFaint, contentPadding = 24.dp) {
        Text(
            text = "DUST BALANCE",
            color = palette.LightMuted,
            fontSize = 11.sp,
            fontWeight = FontWeight.W400,
            letterSpacing = 3.sp,
        )
        Spacer(modifier = Modifier.height(20.dp))
        Text(
            text = "98,765.432109876543",
            color = palette.Light,
            fontSize = 44.sp,
            fontWeight = FontWeight.W200,
            letterSpacing = (-1).sp,
            lineHeight = 48.sp,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "DUST",
            color = palette.LightMuted,
            fontSize = 18.sp,
            fontWeight = FontWeight.W300,
            lineHeight = 24.sp,
        )
    }

    Spacer(modifier = Modifier.height(32.dp))

    SettingsSectionHeader(label = "STATUS", palette = palette)
    Spacer(modifier = Modifier.size(12.dp))

    GlassPanel(tint = palette.contentPanel, border = palette.LightFaint, contentPadding = 0.dp) {
        SettingsRow(label = "NIGHT backing", rightValue = "1,234.567890 NIGHT", readOnly = true, palette = palette)
        SettingsDivider(palette = palette)
        SettingsRow(label = "Generation", rightValue = "42%", readOnly = true, palette = palette)
        SettingsDivider(palette = palette)
        SettingsRow(label = "Generation rate", rightValue = "0.001 DUST/block", readOnly = true, palette = palette)
    }
}

@Composable
private fun LoadingContent(palette: DuskPalette) {
    Spacer(modifier = Modifier.height(32.dp))

    GlassPanel(tint = palette.contentPanel, border = palette.LightFaint, contentPadding = 24.dp) {
        Text(
            text = "DUST BALANCE",
            color = palette.LightMuted,
            fontSize = 11.sp,
            fontWeight = FontWeight.W400,
            letterSpacing = 3.sp,
        )
        Spacer(modifier = Modifier.height(20.dp))
        // Shimmer placeholder
        Box(
            modifier = Modifier
                .fillMaxWidth(0.6f)
                .height(48.dp)
                .background(palette.LightBarely),
        )
    }

    Spacer(modifier = Modifier.height(32.dp))

    SettingsSectionHeader(label = "STATUS", palette = palette)
    Spacer(modifier = Modifier.size(12.dp))

    GlassPanel(tint = palette.contentPanel, border = palette.LightFaint, contentPadding = 0.dp) {
        SettingsRow(label = "NIGHT backing", rightValue = "…", readOnly = true, palette = palette)
        SettingsDivider(palette = palette)
        SettingsRow(label = "Generation", rightValue = "…", readOnly = true, palette = palette)
        SettingsDivider(palette = palette)
        SettingsRow(label = "Generation rate", rightValue = "…", readOnly = true, palette = palette)
    }
}

@Composable
private fun EmptyContent(palette: DuskPalette) {
    Spacer(modifier = Modifier.height(32.dp))

    GlassPanel(tint = palette.contentPanel, border = palette.LightFaint, contentPadding = 24.dp) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(
                imageVector = Icons.Filled.Token,
                contentDescription = null,
                tint = palette.LightMuted,
                modifier = Modifier.size(32.dp),
            )
            Spacer(modifier = Modifier.height(20.dp))
            Text(
                text = "Register your dust tank",
                color = palette.Light,
                fontSize = 18.sp,
                fontWeight = FontWeight.W300,
                lineHeight = 24.sp,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Dust generates passively from your NIGHT balance. Register once to start earning.",
                color = palette.LightMuted,
                fontSize = 13.sp,
                fontWeight = FontWeight.W400,
                lineHeight = 18.sp,
            )
        }
    }

    Spacer(modifier = Modifier.height(48.dp))

    DuskPrimaryButtonPaletted(
        text = "Register",
        onClick = { },
        palette = palette,
    )
}

@Composable
private fun PendingContent(copy: DustStepCopy, palette: DuskPalette) {
    Spacer(modifier = Modifier.height(48.dp))

    GlassPanel(tint = palette.contentPanel, border = palette.LightFaint, contentPadding = 24.dp) {
        StepIndicator(
            stepLabel = copy.label,
            detailHint = copy.detail,
            palette = palette,
        )
    }
}

@Composable
private fun SuccessContent(palette: DuskPalette) {
    Spacer(modifier = Modifier.height(48.dp))

    GlassPanel(tint = palette.contentPanel, border = palette.LightFaint, contentPadding = 24.dp) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = null,
                tint = palette.Light,
                modifier = Modifier.size(32.dp),
            )
            Spacer(modifier = Modifier.height(20.dp))
            Text(
                text = "Dust tank registered",
                color = palette.Light,
                fontSize = 18.sp,
                fontWeight = FontWeight.W300,
                lineHeight = 24.sp,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Generation will begin on the next block.",
                color = palette.LightMuted,
                fontSize = 13.sp,
                fontWeight = FontWeight.W400,
                lineHeight = 18.sp,
            )
        }
    }

    Spacer(modifier = Modifier.height(32.dp))

    SettingsSectionHeader(label = "TRANSACTION", palette = palette)
    Spacer(modifier = Modifier.size(12.dp))

    GlassPanel(tint = palette.contentPanel, border = palette.LightFaint, contentPadding = 0.dp) {
        SettingsRow(
            label = "Hash",
            rightValue = "abc12345\u2026def678",
            rightValueMono = true,
            readOnly = true,
            trailingIcon = Icons.Filled.ContentCopy,
            palette = palette,
        )
    }

    Spacer(modifier = Modifier.height(32.dp))

    DuskPrimaryButtonPaletted(
        text = "Check Dust Status",
        onClick = { },
        palette = palette,
    )
}

@Composable
private fun ErrorContent(palette: DuskPalette) {
    Spacer(modifier = Modifier.height(32.dp))

    ErrorCard(
        headline = "Something went wrong",
        body = "Could not fetch dust status. Check your connection and try again.",
        palette = palette,
    )

    Spacer(modifier = Modifier.height(48.dp))

    DuskPrimaryButtonPaletted(
        text = "Try Again",
        onClick = { },
        palette = palette,
    )
}
