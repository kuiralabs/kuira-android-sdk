package com.midnight.kuira.dev.wireframes.recovery

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.midnight.kuira.core.designsystem.component.GlassPanel
import com.midnight.kuira.dev.wireframes.shared.DuskTokens
import com.midnight.kuira.core.designsystem.effect.StarField
import com.midnight.kuira.dev.wireframes.send.DuskPrimaryButtonPaletted
import com.midnight.kuira.feature.balance.redesign.DuskPalette

private val sampleWords = listOf(
    "abandon", "ability", "able", "about", "above", "absent",
    "absorb", "abstract", "absurd", "abuse", "access", "accident",
    "account", "accuse", "achieve", "acid", "acoustic", "acquire",
    "across", "act", "action", "actor", "actress", "actual",
)

@Composable
fun RecoveryPhraseWireframe(
    isOnboardingEntry: Boolean = false,
    palette: DuskPalette = DuskPalette.DarkMode,
    onBack: () -> Unit = {},
) {
    val statusBarPadding = WindowInsets.statusBars.asPaddingValues()
    val navBarPadding = WindowInsets.navigationBars.asPaddingValues()
    var checkboxChecked by remember { mutableStateOf(false) }

    val title = if (isOnboardingEntry) "Your recovery phrase" else "Recovery phrase"

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
            // Top bar
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(DuskTokens.TopBarHeight)
                    .padding(horizontal = DuskTokens.Space16),
            ) {
                if (!isOnboardingEntry) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back to settings",
                        tint = palette.Light,
                        modifier = Modifier.size(DuskTokens.Icon24).clickable { onBack() },
                    )
                    Spacer(modifier = Modifier.width(DuskTokens.Space16))
                }
                Text(
                    text = title,
                    color = palette.Light,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.W300,
                )
            }
            HorizontalDivider(color = palette.LightFaint, thickness = 1.dp)

            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = DuskTokens.Space16)
                    .padding(bottom = navBarPadding.calculateBottomPadding() + DuskTokens.Space24),
            ) {
                Spacer(modifier = Modifier.height(DuskTokens.Space32))

                // Warning banner
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(DuskTokens.RadiusMd))
                        .background(palette.LightBarely)
                        .padding(horizontal = DuskTokens.Space16, vertical = DuskTokens.Space12),
                ) {
                    Icon(
                        imageVector = Icons.Filled.VisibilityOff,
                        contentDescription = null,
                        tint = palette.LightMuted,
                        modifier = Modifier.size(DuskTokens.Icon20),
                    )
                    Spacer(modifier = Modifier.width(DuskTokens.Space8))
                    Text(
                        text = "Anyone with these words can access your funds. Never share them.",
                        color = palette.Light,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.W400,
                        lineHeight = 18.sp,
                    )
                }

                Spacer(modifier = Modifier.height(DuskTokens.Space32))

                // Word grid (3 columns × 8 rows)
                GlassPanel(
                    tint = palette.contentPanel,
                    border = palette.LightFaint,
                    contentPadding = DuskTokens.PanelPadding,
                ) {
                    for (row in 0 until 8) {
                        if (row > 0) Spacer(modifier = Modifier.height(DuskTokens.Space8))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            for (col in 0 until 3) {
                                val index = row * 3 + col
                                val number = index + 1
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Text(
                                        text = "$number.",
                                        color = palette.LightMuted,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.W400,
                                        modifier = Modifier.width(DuskTokens.Space24),
                                    )
                                    Spacer(modifier = Modifier.width(DuskTokens.Space4))
                                    Text(
                                        text = sampleWords[index],
                                        color = palette.Light,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.W300,
                                        lineHeight = 20.sp,
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(DuskTokens.Space32))

                // Copy action pill
                Row(
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(RoundedCornerShape(DuskTokens.RadiusFull))
                            .background(palette.LightBarely)
                            .clickable { }
                            .padding(horizontal = DuskTokens.Space12, vertical = DuskTokens.Space8),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.ContentCopy,
                            contentDescription = "Copy all 24 words to clipboard",
                            tint = palette.Light,
                            modifier = Modifier.size(DuskTokens.Icon20),
                        )
                        Spacer(modifier = Modifier.width(DuskTokens.Space8))
                        Text(
                            text = "Copy",
                            color = palette.Light,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.W400,
                        )
                    }
                }

                // Onboarding-entry: confirmation checkbox + Continue button
                if (isOnboardingEntry) {
                    Spacer(modifier = Modifier.height(DuskTokens.Space32))

                    Row(
                        verticalAlignment = Alignment.Top,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { checkboxChecked = !checkboxChecked },
                    ) {
                        Checkbox(
                            checked = checkboxChecked,
                            onCheckedChange = { checkboxChecked = it },
                            colors = CheckboxDefaults.colors(
                                checkedColor = palette.Light,
                                uncheckedColor = palette.LightFaint,
                                checkmarkColor = palette.Void,
                            ),
                        )
                        Spacer(modifier = Modifier.width(DuskTokens.Space12))
                        Text(
                            text = "I have written down my recovery phrase and understand I am responsible for keeping it safe.",
                            color = palette.Light,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.W400,
                            lineHeight = 18.sp,
                            modifier = Modifier.padding(top = DuskTokens.Space12),
                        )
                    }

                    Spacer(modifier = Modifier.height(DuskTokens.Space16))

                    DuskPrimaryButtonPaletted(
                        text = "Continue",
                        onClick = { },
                        palette = palette,
                        enabled = checkboxChecked,
                    )
                }
            }
        }
    }
}
