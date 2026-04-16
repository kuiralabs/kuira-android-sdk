package com.midnight.kuira.core.designsystem.devportal

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.midnight.kuira.core.designsystem.theme.MidnightColors

/**
 * Bottom-sheet modal opened by the dev FAB on any screen.
 *
 * Always shows the "View all wireframes" button at the bottom. Takes
 * an optional [stateControls] slot that hosts per-screen controls
 * (state toggles, option checkboxes). Pass `null` for screens without
 * swappable states (e.g., the real app).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevPortalModal(
    title: String,
    onDismiss: () -> Unit,
    onOpenWireframeList: () -> Unit,
    stateControls: (@Composable () -> Unit)? = null,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MidnightColors.VoidElevated,
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(top = 8.dp, bottom = 4.dp)
                    .size(width = 32.dp, height = 4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(MidnightColors.LightFaint),
            )
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
        ) {
            Text(
                text = title.uppercase(),
                color = MidnightColors.LightMuted,
                fontSize = 11.sp,
                fontWeight = FontWeight.W400,
                letterSpacing = 3.sp,
            )
            Spacer(modifier = Modifier.height(12.dp))

            if (stateControls != null) {
                stateControls()
                Spacer(modifier = Modifier.height(24.dp))
            }

            WireframeListButton(onClick = onOpenWireframeList)
        }
    }
}

@Composable
private fun WireframeListButton(onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MidnightColors.LightBarely)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "View all wireframes",
                color = MidnightColors.Light,
                fontSize = 14.sp,
                fontWeight = FontWeight.W400,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "Browse the 8B.1 design sprint index",
                color = MidnightColors.LightMuted,
                fontSize = 12.sp,
                fontWeight = FontWeight.W400,
                lineHeight = 16.sp,
            )
        }
        Spacer(modifier = Modifier.size(12.dp))
        Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
            contentDescription = null,
            tint = MidnightColors.LightMuted,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
fun DevStateSection(
    label: String = "STATE",
    content: @Composable () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            color = MidnightColors.LightSoft,
            fontSize = 11.sp,
            fontWeight = FontWeight.W400,
            letterSpacing = 3.sp,
        )
        Spacer(modifier = Modifier.height(8.dp))
        content()
    }
}

@Composable
fun DevRadioRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 10.dp),
    ) {
        // Radio indicator
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(18.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(MidnightColors.LightBarely),
        ) {
            if (selected) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .background(MidnightColors.Light),
                )
            }
        }
        Text(
            text = label,
            color = if (selected) MidnightColors.Light else MidnightColors.LightMuted,
            fontSize = 13.sp,
            fontWeight = FontWeight.W400,
        )
    }
}

@Composable
fun DevCheckboxRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 8.dp, vertical = 10.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(18.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(if (checked) MidnightColors.Light else MidnightColors.LightBarely),
        ) {
            if (checked) {
                Text(
                    text = "\u2713",
                    color = MidnightColors.Void,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.W500,
                )
            }
        }
        Text(
            text = label,
            color = MidnightColors.Light,
            fontSize = 13.sp,
            fontWeight = FontWeight.W400,
        )
    }
}
