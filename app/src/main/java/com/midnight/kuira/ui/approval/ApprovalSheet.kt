package com.midnight.kuira.ui.approval

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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.midnight.kuira.core.connector.ApprovalCategory
import com.midnight.kuira.core.connector.ApprovalRequest
import com.midnight.kuira.core.designsystem.effect.KuiraMaterializeFrame
import com.midnight.kuira.core.designsystem.effect.StarField
import com.midnight.kuira.core.designsystem.theme.MidnightColors
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Approval sheet content — B&W, void background, white text.
 */
@Composable
fun ApprovalSheetScreen(
    request: ApprovalRequest,
    onApprove: () -> Unit,
    onReject: () -> Unit,
) {
    // Entrance animations
    val bgAlpha = remember { Animatable(0f) }
    val starAlpha = remember { Animatable(0f) }
    val brandAlpha = remember { Animatable(0f) }
    val sheetOffset = remember { Animatable(400f) }
    val contentAlpha = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        bgAlpha.animateTo(1f, tween(300, easing = FastOutSlowInEasing))
    }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(150)
        starAlpha.animateTo(0.7f, tween(600))
    }
    LaunchedEffect(Unit) {
        // KUIRA appears in the void before sheet arrives
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
            .clickable(onClick = onReject),
    ) {
        StarField(
            modifier = Modifier.fillMaxSize(),
            alpha = starAlpha.value,
        )

        // KUIRA in the void — settled state with breathing glow
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
            // Handle
            Box(
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .width(28.dp)
                    .height(1.dp)
                    .background(MidnightColors.LightFaint),
            )

            Spacer(modifier = Modifier.height(32.dp))

            // Content fades in
            Column(modifier = Modifier.alpha(contentAlpha.value)) {

            // Category label
            Text(
                text = categoryLabel(request.category),
                color = MidnightColors.LightMuted,
                fontSize = 11.sp,
                letterSpacing = 3.sp,
            )

            Spacer(modifier = Modifier.height(20.dp))

            // Category-specific content
            when (request.category) {
                ApprovalCategory.TRANSFER -> TransferContent(request)
                ApprovalCategory.SIGN -> SignContent(request)
                ApprovalCategory.TRANSACTION -> TransactionContent(request)
                ApprovalCategory.INTENT -> IntentContent(request)
                ApprovalCategory.READ -> {} // never shown
            }

            Spacer(modifier = Modifier.height(48.dp))

            // Action buttons
            ActionButtons(
                confirmLabel = confirmLabel(request.category),
                onApprove = onApprove,
                onReject = onReject,
            )

            } // end content alpha Column
        }
    }
}

@Composable
private fun TransferContent(request: ApprovalRequest) {
    val outputs = request.params["desiredOutputs"]?.jsonArray
    val firstOutput = outputs?.firstOrNull()?.jsonObject
    val amount = firstOutput?.get("value")?.jsonPrimitive?.content ?: "?"
    val recipient = firstOutput?.get("recipient")?.jsonPrimitive?.content ?: "?"
    val kind = firstOutput?.get("kind")?.jsonPrimitive?.content ?: ""

    if (kind == "shielded") {
        Text(
            text = "private",
            color = MidnightColors.LightFaint,
            fontSize = 10.sp,
            letterSpacing = 2.sp,
        )
        Spacer(modifier = Modifier.height(8.dp))
    }

    Text(
        text = amount,
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
        text = recipient,
        color = MidnightColors.LightMuted,
        fontSize = 12.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun SignContent(request: ApprovalRequest) {
    val data = request.params["data"]?.jsonPrimitive?.content ?: ""
    val encoding = request.params["options"]?.jsonObject?.get("encoding")?.jsonPrimitive?.content ?: "hex"

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MidnightColors.LightBarely)
            .padding(16.dp),
    ) {
        Text(
            text = data,
            color = MidnightColors.LightSoft,
            fontSize = 13.sp,
            fontFamily = FontFamily.Monospace,
            lineHeight = 20.sp,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
        )
    }
    Spacer(modifier = Modifier.height(16.dp))
    Text(
        text = "$encoding  \u00B7  unshielded key",
        color = MidnightColors.LightFaint,
        fontSize = 11.sp,
    )
}

@Composable
private fun TransactionContent(request: ApprovalRequest) {
    val method = request.method
    val description = when (method) {
        "submitTransaction" -> "submit to network"
        "balanceUnsealedTransaction" -> "balance unsealed transaction"
        "balanceSealedTransaction" -> "balance sealed transaction"
        else -> method
    }
    Text(
        text = description,
        color = MidnightColors.LightSoft,
        fontSize = 16.sp,
    )
    Spacer(modifier = Modifier.height(12.dp))

    val payFees = request.params["payFees"]?.jsonPrimitive?.content
        ?: request.params["options"]?.jsonObject?.get("payFees")?.jsonPrimitive?.content
        ?: "true"
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text("pay fees", color = MidnightColors.LightFaint, fontSize = 11.sp)
        Text(payFees, color = MidnightColors.LightFaint, fontSize = 11.sp)
    }
}

@Composable
private fun IntentContent(request: ApprovalRequest) {
    val inputs = request.params["desiredInputs"]?.jsonArray?.size ?: 0
    val outputs = request.params["desiredOutputs"]?.jsonArray?.size ?: 0

    Text(
        text = "create intent",
        color = MidnightColors.LightSoft,
        fontSize = 16.sp,
    )
    Spacer(modifier = Modifier.height(16.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text("inputs", color = MidnightColors.LightFaint, fontSize = 11.sp)
        Text("$inputs", color = MidnightColors.LightFaint, fontSize = 11.sp)
    }
    Spacer(modifier = Modifier.height(4.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text("outputs", color = MidnightColors.LightFaint, fontSize = 11.sp)
        Text("$outputs", color = MidnightColors.LightFaint, fontSize = 11.sp)
    }
}

@Composable
private fun ActionButtons(
    confirmLabel: String,
    onApprove: () -> Unit,
    onReject: () -> Unit,
) {
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
                .clickable(onClick = onReject),
            contentAlignment = Alignment.Center,
        ) {
            Text("no", color = MidnightColors.LightMuted, fontSize = 13.sp)
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
            Text(
                confirmLabel,
                color = MidnightColors.Void,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

private fun categoryLabel(category: ApprovalCategory): String = when (category) {
    ApprovalCategory.TRANSFER -> "transfer"
    ApprovalCategory.SIGN -> "sign data"
    ApprovalCategory.TRANSACTION -> "transaction"
    ApprovalCategory.INTENT -> "intent"
    ApprovalCategory.READ -> ""
}

private fun confirmLabel(category: ApprovalCategory): String = when (category) {
    ApprovalCategory.SIGN -> "sign"
    ApprovalCategory.TRANSACTION -> "confirm"
    ApprovalCategory.INTENT -> "confirm"
    else -> "confirm"
}
