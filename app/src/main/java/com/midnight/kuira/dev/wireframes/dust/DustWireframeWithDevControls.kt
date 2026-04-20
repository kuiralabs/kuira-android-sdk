package com.midnight.kuira.dev.wireframes.dust

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.midnight.kuira.core.designsystem.devportal.DevCheckboxRow
import com.midnight.kuira.core.designsystem.devportal.DevPortalModal
import com.midnight.kuira.core.designsystem.devportal.DevRadioRow
import com.midnight.kuira.core.designsystem.devportal.DevStateSection
import com.midnight.kuira.core.designsystem.theme.MidnightColors
import com.midnight.kuira.feature.balance.redesign.DuskPalette

@Composable
fun DustWireframeWithDevControls(
    onBack: () -> Unit = {},
    onOpenWireframeList: () -> Unit = {},
) {
    var state by remember { mutableStateOf(DustWireframeState.DEFAULT) }
    var manualProgress by remember { mutableFloatStateOf(0.42f) }
    var autoPlay by remember { mutableStateOf(false) }
    var lightMode by remember { mutableStateOf(false) }
    var modalOpen by remember { mutableStateOf(false) }

    // Auto-play: 0% → 100% over 8 seconds, loops
    val autoTransition = rememberInfiniteTransition(label = "autoPlay")
    val autoProgress by autoTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 8000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "autoProgress",
    )

    val generationProgress = if (autoPlay) autoProgress else manualProgress
    val palette = if (lightMode) DuskPalette.LightMode else DuskPalette.DarkMode

    Box(modifier = Modifier.fillMaxSize()) {
        DustWireframe(
            state = state,
            generationProgress = generationProgress,
            palette = palette,
            onBack = onBack,
        )

        FloatingActionButton(
            onClick = { modalOpen = true },
            containerColor = MidnightColors.VoidElevated,
            contentColor = MidnightColors.Light,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 72.dp, end = 16.dp),
        ) {
            Icon(Icons.Filled.Build, contentDescription = "Open dev controls")
        }
    }

    if (modalOpen) {
        DevPortalModal(
            title = "Dust \u00B7 Dev Controls",
            onDismiss = { modalOpen = false },
            onOpenWireframeList = {
                modalOpen = false
                onOpenWireframeList()
            },
            stateControls = {
                DevStateSection(label = "STATE") {
                    DustWireframeState.entries.forEach { s ->
                        DevRadioRow(
                            label = s.name.lowercase().replace('_', '-'),
                            selected = s == state,
                            onClick = { state = s },
                        )
                    }
                }

                Spacer(modifier = Modifier.padding(top = 16.dp))

                DevStateSection(label = "GENERATION PROGRESS") {
                    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                        DevCheckboxRow(
                            label = "Auto-play 0%→100% (8s loop)",
                            checked = autoPlay,
                            onCheckedChange = { autoPlay = it },
                        )
                        if (!autoPlay) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = "${(manualProgress * 100).toInt()}%",
                                    color = MidnightColors.Light,
                                    fontSize = 14.sp,
                                )
                                Spacer(modifier = Modifier.weight(1f))
                                Text(
                                    text = "Drag to scrub",
                                    color = MidnightColors.LightMuted,
                                    fontSize = 11.sp,
                                )
                            }
                            Slider(
                                value = manualProgress,
                                onValueChange = { manualProgress = it },
                                modifier = Modifier.fillMaxWidth(),
                                colors = SliderDefaults.colors(
                                    thumbColor = MidnightColors.Light,
                                    activeTrackColor = MidnightColors.LightSoft,
                                    inactiveTrackColor = MidnightColors.LightFaint,
                                ),
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.padding(top = 16.dp))

                DevStateSection(label = "OPTIONS") {
                    DevCheckboxRow(
                        label = "Light mode",
                        checked = lightMode,
                        onCheckedChange = { lightMode = it },
                    )
                }
            },
        )
    }
}
