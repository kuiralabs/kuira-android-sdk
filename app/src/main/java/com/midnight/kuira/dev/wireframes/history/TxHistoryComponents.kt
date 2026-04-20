package com.midnight.kuira.dev.wireframes.history

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.midnight.kuira.dev.wireframes.shared.DuskTokens
import com.midnight.kuira.feature.balance.redesign.DuskPalette

/**
 * TxTypeBadge — pill showing transaction type. Same geometry as
 * NetworkBadge and mode badges (LightBarely bg, radius-full,
 * type-label-tiny LightSoft).
 */
@Composable
fun TxTypeBadge(
    label: String,
    palette: DuskPalette,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(DuskTokens.RadiusFull))
            .background(palette.LightBarely)
            .padding(horizontal = DuskTokens.Space8, vertical = DuskTokens.Space4),
    ) {
        Text(
            text = label,
            color = palette.LightSoft,
            fontSize = 11.sp,
            fontWeight = FontWeight.W400,
            letterSpacing = 3.sp,
        )
    }
}

/**
 * TxRow — two-line transaction row inside a day-group GlassPanel.
 * Line 1: type badge (left) + amount (right).
 * Line 2: detail address (left) + status (right).
 * Trailing icon-16 chevron. 56dp minimum (LIST ROWS standard).
 */
@Composable
fun TxRow(
    typeBadge: String,
    amount: String,
    detail: String,
    status: String,
    palette: DuskPalette,
    onClick: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = DuskTokens.RowMinHeight)
            .clickable(onClick = onClick)
            .padding(horizontal = DuskTokens.Space16, vertical = DuskTokens.Space12),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            // Line 1: badge + amount
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(),
            ) {
                TxTypeBadge(label = typeBadge, palette = palette)
                Text(
                    text = amount,
                    color = palette.Light,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.W300,
                    lineHeight = 20.sp,
                )
            }
            Spacer(modifier = Modifier.height(DuskTokens.Space4))
            // Line 2: detail + status
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = detail,
                    color = palette.LightMuted,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.W400,
                    lineHeight = 16.sp,
                )
                Text(
                    text = status,
                    color = palette.LightMuted,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.W400,
                    lineHeight = 16.sp,
                )
            }
        }
        Spacer(modifier = Modifier.width(DuskTokens.Space8))
        Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
            contentDescription = null,
            tint = palette.LightMuted,
            modifier = Modifier.size(DuskTokens.Icon16),
        )
    }
}
