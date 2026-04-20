package com.midnight.kuira.dev.wireframes.send

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.midnight.kuira.core.designsystem.component.GlassPanel
import com.midnight.kuira.dev.wireframes.shared.DuskTokens
import com.midnight.kuira.feature.balance.redesign.DuskPalette

/**
 * Text-only progress display for the submit state machine. Centered
 * column: current step label + one-line detail hint. NO progress bar,
 * NO percent, NO color-coded step list. Label crossfades on advance
 * (consumers wrap in `AnimatedContent` — this composable renders a
 * single frame). See `03-send-confirmation.md` §12.
 */
@Composable
fun StepIndicator(
    stepLabel: String,
    detailHint: String,
    palette: DuskPalette,
    modifier: Modifier = Modifier,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier.fillMaxWidth(),
    ) {
        Text(
            text = stepLabel,
            color = palette.Light,
            fontSize = 14.sp,
            fontWeight = FontWeight.W300,
            lineHeight = 20.sp,
        )
        Spacer(modifier = Modifier.height(DuskTokens.Space8))
        Text(
            text = detailHint,
            color = palette.LightMuted,
            fontSize = 13.sp,
            fontWeight = FontWeight.W400,
            lineHeight = 18.sp,
        )
    }
}

/**
 * Self-contained inline error block for submit failures. Wraps its own
 * GlassPanel — callers place `ErrorCard(...)` directly without adding
 * an outer panel. See `03-send-confirmation.md` §12.
 */
@Composable
fun ErrorCard(
    headline: String,
    body: String,
    palette: DuskPalette,
    modifier: Modifier = Modifier,
) {
    GlassPanel(
        tint = palette.contentPanel,
        border = palette.LightFaint,
        modifier = modifier,
    ) {
        Row(
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(DuskTokens.Space8),
        ) {
            Icon(
                imageVector = Icons.Filled.ErrorOutline,
                contentDescription = null,
                tint = palette.ErrorText,
                modifier = Modifier
                    .size(DuskTokens.Icon20)
                    .padding(top = 2.dp),
            )
            Column {
                Text(
                    text = headline,
                    color = palette.Light,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.W300,
                    lineHeight = 20.sp,
                )
                Spacer(modifier = Modifier.height(DuskTokens.Space4))
                Text(
                    text = body,
                    color = palette.LightMuted,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.W400,
                    lineHeight = 18.sp,
                    maxLines = 2,
                )
            }
        }
    }
}
