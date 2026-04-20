package com.midnight.kuira.dev.wireframes.onboarding

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Backspace
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Fingerprint
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.midnight.kuira.core.designsystem.component.GlassPanel
import com.midnight.kuira.core.designsystem.effect.KuiraMaterializeFrame
import com.midnight.kuira.core.designsystem.effect.StarField
import com.midnight.kuira.dev.wireframes.send.DuskPrimaryButtonPaletted
import com.midnight.kuira.dev.wireframes.send.DuskSecondaryButtonPaletted
import com.midnight.kuira.dev.wireframes.send.StepIndicator
import com.midnight.kuira.feature.balance.redesign.DuskPalette

enum class OnboardingStep {
    WELCOME,
    ENTER_PASSCODE,
    CONFIRM_PASSCODE,
    BIOMETRIC_SETUP,
    CREATING,
    RECOVERY_PHRASE,
    ALL_SET,
}

@Composable
fun OnboardingWireframe(
    step: OnboardingStep = OnboardingStep.WELCOME,
    palette: DuskPalette = DuskPalette.DarkMode,
    onBack: () -> Unit = {},
) {
    when (step) {
        OnboardingStep.WELCOME -> WelcomeScreen(palette)
        OnboardingStep.ENTER_PASSCODE -> PasscodeScreen(
            title = "Enter new passcode", palette = palette, onBack = onBack,
        )
        OnboardingStep.CONFIRM_PASSCODE -> PasscodeScreen(
            title = "Confirm passcode", palette = palette, onBack = onBack,
        )
        OnboardingStep.BIOMETRIC_SETUP -> BiometricSetupScreen(palette, onBack)
        OnboardingStep.CREATING -> CreatingScreen(palette)
        OnboardingStep.RECOVERY_PHRASE -> RecoveryPhraseStub(palette)
        OnboardingStep.ALL_SET -> AllSetScreen(palette)
    }
}

// ═══════════════════════════════════════════════════════════════════
// Step 1 — Welcome (matches production DuskScaffold layout)
// ═══════════════════════════════════════════════════════════════════

@Composable
private fun WelcomeScreen(palette: DuskPalette) {
    val statusBarPadding = WindowInsets.statusBars.asPaddingValues()
    val navBarPadding = WindowInsets.navigationBars.asPaddingValues()

    Box(
        modifier = Modifier.fillMaxSize().background(palette.Void),
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
                .padding(top = statusBarPadding.calculateTopPadding())
                .padding(horizontal = 16.dp)
                .padding(bottom = navBarPadding.calculateBottomPadding() + 24.dp),
        ) {
            Spacer(modifier = Modifier.weight(0.4f))

            // KUIRA wordmark with sparkle + breathing glow
            // Uses the same MaterializeEffect from the connector approval sheet
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                KuiraMaterializeFrame(progress = 1f)
            }

            Spacer(modifier = Modifier.height(32.dp))

            // Tagline — short, powerful, no explanation needed
            Text(
                text = "YOUR KEYS.\nYOUR MIDNIGHT.",
                color = palette.Light,
                fontSize = 22.sp,
                fontWeight = FontWeight.W300,
                lineHeight = 32.sp,
                letterSpacing = 2.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(modifier = Modifier.height(48.dp))

            // Action stack
            DuskPrimaryButtonPaletted(
                text = "Create wallet",
                onClick = { },
                palette = palette,
            )
            Spacer(modifier = Modifier.height(8.dp))
            DuskSecondaryButtonPaletted(
                text = "Import existing wallet",
                onClick = { },
                palette = palette,
            )
        }
    }
}

@Composable
private fun BulletLine(text: String, palette: DuskPalette) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    ) {
        Text(
            text = "\u2022",
            color = palette.LightFaint,
            fontSize = 14.sp,
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = text,
            color = palette.LightSoft,
            fontSize = 14.sp,
            fontWeight = FontWeight.W300,
            lineHeight = 20.sp,
        )
    }
}

// ═══════════════════════════════════════════════════════════════════
// Step 2/3 — Passcode entry (6-digit PIN with custom numpad)
// ═══════════════════════════════════════════════════════════════════

@Composable
private fun PasscodeScreen(
    title: String,
    palette: DuskPalette,
    onBack: () -> Unit,
) {
    val statusBarPadding = WindowInsets.statusBars.asPaddingValues()
    val navBarPadding = WindowInsets.navigationBars.asPaddingValues()
    var filledDots by remember { mutableStateOf(0) }

    Box(
        modifier = Modifier.fillMaxSize().background(palette.Void),
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
                    .height(56.dp)
                    .padding(horizontal = 16.dp),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = palette.Light,
                    modifier = Modifier.size(24.dp).clickable { onBack() },
                )
                Spacer(modifier = Modifier.width(16.dp))
                Text(
                    text = title,
                    color = palette.Light,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.W300,
                )
            }
            HorizontalDivider(color = palette.LightFaint, thickness = 1.dp)

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 16.dp)
                    .padding(bottom = navBarPadding.calculateBottomPadding() + 24.dp),
            ) {
                Spacer(modifier = Modifier.weight(0.3f))

                // 6 dots
                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    repeat(6) { index ->
                        Box(
                            modifier = Modifier
                                .size(16.dp)
                                .clip(CircleShape)
                                .background(
                                    if (index < filledDots) palette.Light
                                    else palette.LightFaint
                                ),
                        )
                    }
                }

                Spacer(modifier = Modifier.weight(0.5f))

                // Custom numpad (3×4 grid)
                val numpadRows = listOf(
                    listOf("1", "2", "3"),
                    listOf("4", "5", "6"),
                    listOf("7", "8", "9"),
                    listOf("", "0", "⌫"),
                )

                numpadRows.forEach { row ->
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier.padding(vertical = 8.dp),
                    ) {
                        row.forEach { key ->
                            if (key.isEmpty()) {
                                Spacer(modifier = Modifier.size(72.dp))
                            } else {
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier
                                        .size(72.dp)
                                        .clip(CircleShape)
                                        .background(palette.LightBarely)
                                        .clickable {
                                            if (key == "⌫") {
                                                if (filledDots > 0) filledDots--
                                            } else if (filledDots < 6) {
                                                filledDots++
                                            }
                                        },
                                ) {
                                    if (key == "⌫") {
                                        Icon(
                                            imageVector = Icons.Filled.Backspace,
                                            contentDescription = "Delete",
                                            tint = palette.Light,
                                            modifier = Modifier.size(24.dp),
                                        )
                                    } else {
                                        Text(
                                            text = key,
                                            color = palette.Light,
                                            fontSize = 24.sp,
                                            fontWeight = FontWeight.W300,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.weight(0.2f))
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════
// Step 4 — Biometric setup
// ═══════════════════════════════════════════════════════════════════

@Composable
private fun BiometricSetupScreen(palette: DuskPalette, onBack: () -> Unit) {
    val statusBarPadding = WindowInsets.statusBars.asPaddingValues()
    val navBarPadding = WindowInsets.navigationBars.asPaddingValues()

    Box(
        modifier = Modifier.fillMaxSize().background(palette.Void),
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
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .padding(horizontal = 16.dp),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = palette.Light,
                    modifier = Modifier.size(24.dp).clickable { onBack() },
                )
                Spacer(modifier = Modifier.width(16.dp))
                Text(
                    text = "Secure your wallet",
                    color = palette.Light,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.W300,
                )
            }
            HorizontalDivider(color = palette.LightFaint, thickness = 1.dp)

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 16.dp)
                    .padding(bottom = navBarPadding.calculateBottomPadding() + 24.dp),
            ) {
                Spacer(modifier = Modifier.weight(1f))

                Icon(
                    imageVector = Icons.Filled.Fingerprint,
                    contentDescription = null,
                    tint = palette.Light,
                    modifier = Modifier.size(64.dp),
                )
                Spacer(modifier = Modifier.height(20.dp))
                Text(
                    text = "Enable biometrics",
                    color = palette.Light,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.W300,
                    lineHeight = 24.sp,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Unlock quicker with your face or fingerprint. No passcode typing required.",
                    color = palette.LightMuted,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.W400,
                    lineHeight = 18.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(modifier = Modifier.weight(1f))

                DuskPrimaryButtonPaletted(
                    text = "Enable biometrics",
                    onClick = { },
                    palette = palette,
                )
                Spacer(modifier = Modifier.height(8.dp))
                DuskSecondaryButtonPaletted(
                    text = "Not now",
                    onClick = { },
                    palette = palette,
                )
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════
// Step 5 — Creating
// ═══════════════════════════════════════════════════════════════════

@Composable
private fun CreatingScreen(palette: DuskPalette) {
    val statusBarPadding = WindowInsets.statusBars.asPaddingValues()
    val navBarPadding = WindowInsets.navigationBars.asPaddingValues()

    Box(
        modifier = Modifier.fillMaxSize().background(palette.Void),
    ) {
        StarField(
            modifier = Modifier.fillMaxSize(),
            color = palette.Light,
            alpha = if (palette === DuskPalette.LightMode) 0.55f else 1f,
            starCount = 60,
        )

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxSize()
                .padding(top = statusBarPadding.calculateTopPadding())
                .padding(horizontal = 16.dp)
                .padding(bottom = navBarPadding.calculateBottomPadding() + 24.dp),
        ) {
            Spacer(modifier = Modifier.weight(1f))

            GlassPanel(
                tint = palette.contentPanel,
                border = palette.LightFaint,
                contentPadding = 24.dp,
            ) {
                StepIndicator(
                    stepLabel = "Creating wallet",
                    detailHint = "Generating keys\u2026",
                    palette = palette,
                )
            }

            Spacer(modifier = Modifier.weight(1f))
        }
    }
}

// ═══════════════════════════════════════════════════════════════════
// Step 6 — Recovery phrase (reuses 08 wireframe)
// ═══════════════════════════════════════════════════════════════════

@Composable
private fun RecoveryPhraseStub(palette: DuskPalette) {
    com.midnight.kuira.dev.wireframes.recovery.RecoveryPhraseWireframe(
        isOnboardingEntry = true,
        palette = palette,
    )
}

// ═══════════════════════════════════════════════════════════════════
// Step 7 — All set
// ═══════════════════════════════════════════════════════════════════

@Composable
private fun AllSetScreen(palette: DuskPalette) {
    val statusBarPadding = WindowInsets.statusBars.asPaddingValues()
    val navBarPadding = WindowInsets.navigationBars.asPaddingValues()

    Box(
        modifier = Modifier.fillMaxSize().background(palette.Void),
    ) {
        StarField(
            modifier = Modifier.fillMaxSize(),
            color = palette.Light,
            alpha = if (palette === DuskPalette.LightMode) 0.55f else 1f,
            starCount = 60,
        )

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxSize()
                .padding(top = statusBarPadding.calculateTopPadding())
                .padding(horizontal = 16.dp)
                .padding(bottom = navBarPadding.calculateBottomPadding() + 24.dp),
        ) {
            Spacer(modifier = Modifier.weight(1f))

            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = null,
                tint = palette.SuccessText,
                modifier = Modifier.size(32.dp),
            )
            Spacer(modifier = Modifier.height(20.dp))
            Text(
                text = "You're all set",
                color = palette.Light,
                fontSize = 18.sp,
                fontWeight = FontWeight.W300,
                lineHeight = 24.sp,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Your wallet is ready and only you control the keys.",
                color = palette.LightMuted,
                fontSize = 13.sp,
                fontWeight = FontWeight.W400,
                lineHeight = 18.sp,
                textAlign = TextAlign.Center,
            )

            Spacer(modifier = Modifier.weight(1f))

            // Terms / Privacy
            Text(
                text = "By tapping the button below, you agree to our",
                color = palette.LightMuted,
                fontSize = 12.sp,
                fontWeight = FontWeight.W400,
                lineHeight = 16.sp,
                textAlign = TextAlign.Center,
            )
            Row {
                Text(
                    text = "Terms of Service",
                    color = palette.Light,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.W400,
                    textDecoration = TextDecoration.Underline,
                    modifier = Modifier.clickable { },
                )
                Text(text = " and ", color = palette.LightMuted, fontSize = 12.sp)
                Text(
                    text = "Privacy Policy",
                    color = palette.Light,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.W400,
                    textDecoration = TextDecoration.Underline,
                    modifier = Modifier.clickable { },
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            DuskPrimaryButtonPaletted(
                text = "Let's go",
                onClick = { },
                palette = palette,
            )
        }
    }
}
