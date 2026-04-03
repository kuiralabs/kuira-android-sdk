package com.midnight.kuira.ui.approval

import android.app.Activity
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.midnight.kuira.core.designsystem.effect.KuiraMaterializeFrame
import com.midnight.kuira.core.designsystem.effect.StarField
import com.midnight.kuira.core.designsystem.theme.MidnightColors

/**
 * Connection approval — launched BY the dApp (foreground) to request wallet access.
 *
 * The dApp starts this activity via:
 *   Intent("com.midnight.kuira.CONNECT_APPROVAL")
 *
 * Returns RESULT_OK if approved, RESULT_CANCELED if denied.
 * The dApp then binds to ConnectorService after receiving RESULT_OK.
 */
class ConnectionApprovalActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            ConnectionApprovalScreen(
                callingApp = callingActivity?.packageName
                    ?: intent?.data?.getQueryParameter("app")
                    ?: "An app",
                onApprove = {
                    setResult(Activity.RESULT_OK)
                    finish()
                },
                onDeny = {
                    setResult(Activity.RESULT_CANCELED)
                    finish()
                },
            )
        }
    }

    override fun onBackPressed() {
        setResult(Activity.RESULT_CANCELED)
        super.onBackPressed()
    }
}

@Composable
private fun ConnectionApprovalScreen(
    callingApp: String,
    onApprove: () -> Unit,
    onDeny: () -> Unit,
) {
    val bgAlpha = remember { Animatable(0f) }
    val starAlpha = remember { Animatable(0f) }
    val brandAlpha = remember { Animatable(0f) }
    val sheetOffset = remember { Animatable(400f) }
    val contentAlpha = remember { Animatable(0f) }

    LaunchedEffect(Unit) { bgAlpha.animateTo(1f, tween(300, easing = FastOutSlowInEasing)) }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(150)
        starAlpha.animateTo(0.7f, tween(600))
    }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(200)
        brandAlpha.animateTo(1f, tween(500))
    }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(100)
        sheetOffset.animateTo(0f, tween(400, easing = FastOutSlowInEasing))
    }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(350)
        contentAlpha.animateTo(1f, tween(300))
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MidnightColors.Void.copy(alpha = bgAlpha.value))
            .clickable(onClick = onDeny),
    ) {
        StarField(modifier = Modifier.fillMaxSize(), alpha = starAlpha.value)

        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 80.dp)
                .alpha(brandAlpha.value),
        ) {
            KuiraMaterializeFrame(progress = 1f)
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .offset(y = sheetOffset.value.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                .background(MidnightColors.VoidSoft)
                .clickable(enabled = false, onClick = {})
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

            Column(modifier = Modifier.alpha(contentAlpha.value)) {
                Text(
                    text = "connection request",
                    color = MidnightColors.LightMuted,
                    fontSize = 11.sp,
                    letterSpacing = 3.sp,
                )

                Spacer(modifier = Modifier.height(20.dp))

                Text(
                    text = callingApp,
                    color = MidnightColors.Light,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.W300,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "wants to connect",
                    color = MidnightColors.LightMuted,
                    fontSize = 14.sp,
                )

                Spacer(modifier = Modifier.height(24.dp))

                PermissionLine("view your addresses")
                PermissionLine("view your balances")
                PermissionLine("request transactions")

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
                            .background(MidnightColors.LightBarely)
                            .clickable(onClick = onDeny),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("deny", color = MidnightColors.LightMuted, fontSize = 13.sp)
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MidnightColors.Light)
                            .clickable(onClick = onApprove),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("connect", color = MidnightColors.Void, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }
    }
}

@Composable
private fun PermissionLine(text: String) {
    Row(modifier = Modifier.padding(vertical = 4.dp)) {
        Text("\u2022", color = MidnightColors.LightFaint, fontSize = 13.sp)
        Spacer(modifier = Modifier.width(8.dp))
        Text(text, color = MidnightColors.LightSoft, fontSize = 13.sp)
    }
}
