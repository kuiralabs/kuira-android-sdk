package com.midnight.kuira.ui.prototype

import com.midnight.kuira.core.designsystem.effect.DuskTransitionBackground
import com.midnight.kuira.core.designsystem.effect.HorizonGlow
import com.midnight.kuira.core.designsystem.effect.StarField
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.midnight.kuira.core.designsystem.theme.MidnightColors

/**
 * Black and white approval experience.
 *
 * The void. Stars. White text.
 * Nothing else. The content speaks.
 */

@Composable
fun ApprovalAtmospherePrototype(
    modifier: Modifier = Modifier,
) {
    val sheetOffset = remember { Animatable(300f) }
    val contentAlpha = remember { Animatable(0f) }
    val duskProgress = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        duskProgress.animateTo(1f, tween(900, easing = FastOutSlowInEasing))
    }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(300)
        sheetOffset.animateTo(0f, tween(500, easing = FastOutSlowInEasing))
    }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(600)
        contentAlpha.animateTo(1f, tween(400))
    }

    Box(modifier = modifier.fillMaxSize().background(MidnightColors.Void)) {
        DuskTransitionBackground(
            modifier = Modifier.fillMaxSize(),
            progress = duskProgress.value,
        )
        if (duskProgress.value > 0.5f) {
            StarField(
                modifier = Modifier.fillMaxSize(),
                alpha = ((duskProgress.value - 0.5f) / 0.5f).coerceIn(0f, 1f),
            )
        }
        HorizonGlow(
            modifier = Modifier.fillMaxWidth().height(60.dp),
        )

        // Sheet
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .offset(y = sheetOffset.value.dp)
                .fillMaxWidth()
                .padding(horizontal = 1.dp)
                .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                .background(MidnightColors.VoidSoft)
                .padding(start = 32.dp, end = 32.dp, top = 20.dp, bottom = 40.dp),
        ) {
            // Thin light line
            Box(
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .width(28.dp)
                    .height(1.dp)
                    .background(MidnightColors.LightFaint),
            )

            Spacer(modifier = Modifier.height(32.dp))

            Column(modifier = Modifier.alpha(contentAlpha.value)) {
                // Type — whispered
                Text(
                    text = "shielded transfer",
                    color = MidnightColors.LightMuted,
                    fontSize = 11.sp,
                    letterSpacing = 3.sp,
                )

                Spacer(modifier = Modifier.height(20.dp))

                // The number — the only thing that matters
                Text(
                    text = "5",
                    color = MidnightColors.Light,
                    fontSize = 72.sp,
                    fontWeight = FontWeight.W100,
                    lineHeight = 72.sp,
                )
                Text(
                    text = "NIGHT",
                    color = MidnightColors.LightSoft,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Normal,
                    letterSpacing = 6.sp,
                )

                Spacer(modifier = Modifier.height(28.dp))

                // Destination
                Text(
                    text = "mn_shield-addr_undepl...def456",
                    color = MidnightColors.LightMuted,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                Spacer(modifier = Modifier.height(36.dp))

                // Quiet details
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("fee", color = MidnightColors.LightFaint, fontSize = 11.sp)
                    Text("12 dust", color = MidnightColors.LightFaint, fontSize = 11.sp)
                }

                Spacer(modifier = Modifier.height(48.dp))

                // Two zones separated by void
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    // No
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MidnightColors.LightBarely),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "no",
                            color = MidnightColors.LightMuted,
                            fontSize = 13.sp,
                        )
                    }

                    // Yes — white on black
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MidnightColors.Light),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "confirm",
                            color = MidnightColors.Void,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }
        }
    }
}

// ── Static preview (settled state) ──

@Preview(showBackground = true, widthDp = 360, heightDp = 720, backgroundColor = 0xFF000000)
@Composable
private fun TransferPreview() {
    Box(modifier = Modifier.fillMaxSize().background(MidnightColors.Void)) {
        StarField(modifier = Modifier.fillMaxSize(), alpha = 0.6f)
        HorizonGlow(modifier = Modifier.fillMaxWidth().height(60.dp))

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 1.dp)
                .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                .background(MidnightColors.VoidSoft)
                .padding(start = 32.dp, end = 32.dp, top = 20.dp, bottom = 40.dp),
        ) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .width(28.dp)
                    .height(1.dp)
                    .background(MidnightColors.LightFaint),
            )
            Spacer(modifier = Modifier.height(32.dp))

            Text(
                text = "shielded transfer",
                color = MidnightColors.LightMuted,
                fontSize = 11.sp,
                letterSpacing = 3.sp,
            )
            Spacer(modifier = Modifier.height(20.dp))
            Text(
                text = "5",
                color = MidnightColors.Light,
                fontSize = 72.sp,
                fontWeight = FontWeight.W100,
                lineHeight = 72.sp,
            )
            Text(
                text = "NIGHT",
                color = MidnightColors.LightSoft,
                fontSize = 13.sp,
                letterSpacing = 6.sp,
            )
            Spacer(modifier = Modifier.height(28.dp))
            Text(
                text = "mn_shield-addr_undepl...def456",
                color = MidnightColors.LightMuted,
                fontSize = 12.sp,
            )
            Spacer(modifier = Modifier.height(36.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("fee", color = MidnightColors.LightFaint, fontSize = 11.sp)
                Text("12 dust", color = MidnightColors.LightFaint, fontSize = 11.sp)
            }
            Spacer(modifier = Modifier.height(48.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MidnightColors.LightBarely),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("no", color = MidnightColors.LightMuted, fontSize = 13.sp)
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MidnightColors.Light),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("confirm", color = MidnightColors.Void, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}

// ── Sign data variant ──

@Preview(showBackground = true, widthDp = 360, heightDp = 720, backgroundColor = 0xFF000000)
@Composable
private fun SignPreview() {
    Box(modifier = Modifier.fillMaxSize().background(MidnightColors.Void)) {
        StarField(modifier = Modifier.fillMaxSize(), alpha = 0.6f)

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 1.dp)
                .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                .background(MidnightColors.VoidSoft)
                .padding(start = 32.dp, end = 32.dp, top = 20.dp, bottom = 40.dp),
        ) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .width(28.dp)
                    .height(1.dp)
                    .background(MidnightColors.LightFaint),
            )
            Spacer(modifier = Modifier.height(32.dp))

            Text(
                text = "sign data",
                color = MidnightColors.LightMuted,
                fontSize = 11.sp,
                letterSpacing = 3.sp,
            )
            Spacer(modifier = Modifier.height(24.dp))

            // The data
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(MidnightColors.LightBarely)
                    .padding(16.dp),
            ) {
                Text(
                    text = "0xdeadbeef cafebabe\n01234567 89abcdef",
                    color = MidnightColors.LightSoft,
                    fontSize = 13.sp,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    lineHeight = 20.sp,
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "hex  \u00B7  unshielded key",
                color = MidnightColors.LightFaint,
                fontSize = 11.sp,
            )

            Spacer(modifier = Modifier.height(48.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MidnightColors.LightBarely),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("no", color = MidnightColors.LightMuted, fontSize = 13.sp)
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MidnightColors.Light),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("sign", color = MidnightColors.Void, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}

// ── Animated entrance ──

@Preview(showBackground = true, widthDp = 360, heightDp = 720, backgroundColor = 0xFF000000)
@Composable
private fun AnimatedEntrancePreview() {
    ApprovalAtmospherePrototype()
}
