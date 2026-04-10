package com.midnight.kuira.ui.approval

import android.app.Activity
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import com.midnight.kuira.core.designsystem.component.DuskBulletLine
import com.midnight.kuira.core.designsystem.component.DuskButtonRow
import com.midnight.kuira.core.designsystem.component.DuskScaffold
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
class ConnectionApprovalActivity : FragmentActivity() {

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
    DuskScaffold(
        onDismissBackground = onDeny,
        sheet = { contentAlpha ->
            Column(modifier = Modifier.alpha(contentAlpha)) {
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

                DuskBulletLine("view your addresses")
                DuskBulletLine("view your balances")
                DuskBulletLine("request transactions")

                Spacer(modifier = Modifier.height(48.dp))

                DuskButtonRow(
                    secondaryText = "deny",
                    primaryText = "connect",
                    onSecondary = onDeny,
                    onPrimary = onApprove,
                )
            }
        },
    )
}
