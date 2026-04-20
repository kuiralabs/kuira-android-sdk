package com.midnight.kuira.dev.wireframes.receive

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
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Share
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.midnight.kuira.core.designsystem.component.GlassPanel
import com.midnight.kuira.core.designsystem.effect.StarField
import com.midnight.kuira.feature.balance.redesign.DuskPalette
import com.midnight.kuira.feature.balance.redesign.ShimmerBlock

enum class ReceiveWireframeState { DEFAULT, LOADING_FIRST }

@Composable
fun ReceiveWireframe(
    state: ReceiveWireframeState = ReceiveWireframeState.DEFAULT,
    palette: DuskPalette = DuskPalette.DarkMode,
    onBack: () -> Unit = {},
) {
    val statusBarPadding = WindowInsets.statusBars.asPaddingValues()
    val navBarPadding = WindowInsets.navigationBars.asPaddingValues()

    var selectedTab by remember { mutableStateOf(0) }
    var showFullScreenQR by remember { mutableStateOf(false) }

    val tabs = listOf("UNSHIELDED", "SHIELDED")
    val addresses = listOf(
        "mn_addr_preprod1qq2x7y8f5a2k3j9nvxz4t8pq3mdlwnrhc7vwu8f",
        "mn_shield-addr_preprod1abc3f8ek9nvxz4t8pq3mdlwnrhc7vwu8f2x7y",
    )
    val currentAddress = addresses[selectedTab]

    if (showFullScreenQR) {
        FullScreenQROverlay(
            address = currentAddress,
            palette = palette,
            onDismiss = { showFullScreenQR = false },
        )
        return
    }

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
                    .height(56.dp)
                    .padding(horizontal = 16.dp),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back to balance",
                    tint = palette.Light,
                    modifier = Modifier.size(24.dp).clickable { onBack() },
                )
                Spacer(modifier = Modifier.width(16.dp))
                Text(
                    text = "Receive",
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
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
                    .padding(bottom = navBarPadding.calculateBottomPadding() + 24.dp),
            ) {
                Spacer(modifier = Modifier.height(32.dp))

                // Header
                Text(
                    text = "Receive NIGHT on Preprod",
                    color = palette.Light,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.W300,
                    lineHeight = 24.sp,
                )
                Spacer(modifier = Modifier.height(4.dp))
                // Network badge
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(9999.dp))
                        .background(palette.LightBarely)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    Text(
                        text = "PREPROD",
                        color = palette.LightSoft,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.W400,
                        letterSpacing = 3.sp,
                    )
                }

                Spacer(modifier = Modifier.height(32.dp))

                // QR panel
                GlassPanel(
                    tint = palette.contentPanel,
                    border = palette.LightFaint,
                    contentPadding = 24.dp,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (state == ReceiveWireframeState.LOADING_FIRST) {
                            // Shimmer QR placeholder
                            ShimmerBlock(
                                height = 200.dp,
                                widthFraction = 1f,
                                palette = palette,
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            ShimmerBlock(
                                height = 32.dp,
                                widthFraction = 0.8f,
                                palette = palette,
                            )
                        } else {
                            // QR placeholder — white square with "QR" text
                            Box(
                                modifier = Modifier
                                    .size(200.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(androidx.compose.ui.graphics.Color.White)
                                    .clickable { showFullScreenQR = true },
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = "QR",
                                    color = androidx.compose.ui.graphics.Color.Black,
                                    fontSize = 32.sp,
                                    fontWeight = FontWeight.W200,
                                )
                            }
                            Spacer(modifier = Modifier.height(16.dp))
                            // Address display
                            Text(
                                text = currentAddress,
                                color = palette.LightMuted,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.W400,
                                fontFamily = FontFamily.Monospace,
                                lineHeight = 16.sp,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Tab selector (reuses AddressChip geometry)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(palette.LightBarely)
                        .padding(4.dp),
                ) {
                    tabs.forEachIndexed { index, tab ->
                        val isActive = index == selectedTab
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .then(if (isActive) Modifier.background(palette.Light) else Modifier)
                                .clickable { selectedTab = index }
                                .padding(vertical = 12.dp),
                        ) {
                            Text(
                                text = tab,
                                color = if (isActive) palette.Void else palette.LightMuted,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.W400,
                                letterSpacing = 1.sp,
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(32.dp))

                // Action row
                Row(
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    ActionPill(icon = Icons.Filled.ContentCopy, label = "Copy", palette = palette)
                    ActionPill(icon = Icons.Filled.Share, label = "Share", palette = palette)
                    ActionPill(icon = Icons.Filled.Fullscreen, label = "Expand", palette = palette) {
                        showFullScreenQR = true
                    }
                }
            }
        }
    }
}

@Composable
private fun ActionPill(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    palette: DuskPalette,
    onClick: () -> Unit = {},
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(9999.dp))
                .background(palette.LightBarely),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = palette.Light,
                modifier = Modifier.size(20.dp),
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = label,
            color = palette.LightMuted,
            fontSize = 12.sp,
            fontWeight = FontWeight.W400,
        )
    }
}

@Composable
private fun FullScreenQROverlay(
    address: String,
    palette: DuskPalette,
    onDismiss: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.Void)
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        // QR always renders white bg + dark modules for scanner contrast
        Box(
            modifier = Modifier
                .padding(32.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(androidx.compose.ui.graphics.Color.White),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "QR\n$address",
                color = androidx.compose.ui.graphics.Color.Black,
                fontSize = 14.sp,
                fontWeight = FontWeight.W300,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(32.dp),
            )
        }
    }
}
