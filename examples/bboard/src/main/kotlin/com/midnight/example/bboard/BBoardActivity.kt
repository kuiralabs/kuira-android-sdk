package com.midnight.example.bboard

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.midnight.kuira.core.network.MidnightNetwork

// ── Design Tokens ──

private object Colors {
    val Background = Color(0xFF0A0A0A)
    val Surface = Color(0xFF111111)
    val ErrorSurface = Color(0xFF1A0A0A)
    val Accent = Color(0xFF64B5F6)
    val Success = Color(0xFF4CAF8B)
    val Error = Color(0xFFFF6666)
    val OnSurface = Color.White
    val OnSurfaceDim = Color.White.copy(alpha = 0.45f)
    val OnSurfaceSubtle = Color.White.copy(alpha = 0.25f)
    val Disabled = Color.White.copy(alpha = 0.08f)
}

private object Type {
    val Title = 24.sp
    val Subtitle = 14.sp
    val Body = 14.sp
    val Label = 13.sp
    val Caption = 12.sp
    val Mono = 11.sp // monospace addresses — smallest allowed
}

private object Spacing {
    val ScreenPadding = 24.dp
    val CardPadding = 20.dp
    val SectionGap = 20.dp
    val ItemGap = 12.dp
    val SmallGap = 8.dp
    val TinyGap = 4.dp
}

private object Shapes {
    val Card = RoundedCornerShape(16.dp)
    val Button = RoundedCornerShape(12.dp)
    val Chip = RoundedCornerShape(10.dp)
}

private const val BUTTON_HEIGHT_DP = 48
private const val CHIP_HEIGHT_DP = 40
private const val PROGRESS_BAR_HEIGHT_DP = 3
private const val SPINNER_SIZE_DP = 24

// ── Activity ──

class BBoardActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { BBoardApp() }
    }
}

@Composable
fun BBoardApp(viewModel: BBoardViewModel = viewModel()) {
    val state by viewModel.state.collectAsState()

    Surface(modifier = Modifier.fillMaxSize(), color = Colors.Background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 16.dp,
                    start = Spacing.ScreenPadding,
                    end = Spacing.ScreenPadding,
                    bottom = Spacing.ScreenPadding,
                )
        ) {
            Text("bboard", color = Colors.OnSurface, fontSize = Type.Title, fontWeight = FontWeight.W300, letterSpacing = 4.sp)
            Text("midnight bulletin board", color = Colors.OnSurfaceDim, fontSize = Type.Caption, letterSpacing = 2.sp)
            Spacer(modifier = Modifier.height(Spacing.SectionGap))

            when (val s = state) {
                is BBoardState.Setup -> SetupScreen(
                    onConnectRemote = viewModel::connect,
                    onConnectSdk = viewModel::connectWithSdk,
                    onDeploySdk = viewModel::deployAndConnect,
                )
                is BBoardState.Connecting -> ConnectingView(s.stage)
                is BBoardState.Error -> ErrorView(s.message) { viewModel.disconnect() }
                is BBoardState.Connected -> ConnectedScreen(
                    state = s,
                    onPost = viewModel::post,
                    onTakeDown = viewModel::takeDown,
                    onRefresh = viewModel::refresh,
                    onDisconnect = viewModel::disconnect,
                )
            }
        }
    }
}

// ── Setup Screen ──

private enum class ConnectionMode(val label: String) {
    REMOTE("Remote Wallet"),
    STANDALONE("Standalone SDK"),
}

@Composable
private fun SetupScreen(
    onConnectRemote: (String, NetworkChoice) -> Unit,
    onConnectSdk: (String, MidnightNetwork, ByteArray) -> Unit,
    onDeploySdk: (MidnightNetwork, ByteArray) -> Unit,
) {
    var address by remember { mutableStateOf("") }
    var network by remember { mutableStateOf(NetworkChoice.LOCALNET) }
    var mode by remember { mutableStateOf(ConnectionMode.REMOTE) }

    DarkCard {
        Text("connect to contract", color = Colors.OnSurfaceDim, fontSize = Type.Caption, letterSpacing = 2.sp)
        Spacer(modifier = Modifier.height(Spacing.SectionGap))

        Text("mode", color = Colors.OnSurfaceDim, fontSize = Type.Caption)
        Spacer(modifier = Modifier.height(Spacing.SmallGap))
        ChipRow(
            options = ConnectionMode.entries.map { it.label },
            selectedIndex = mode.ordinal,
            accentSelected = mode == ConnectionMode.STANDALONE,
            onSelect = { mode = ConnectionMode.entries[it] },
        )

        Spacer(modifier = Modifier.height(Spacing.SectionGap))

        Text("network", color = Colors.OnSurfaceDim, fontSize = Type.Caption)
        Spacer(modifier = Modifier.height(Spacing.SmallGap))
        ChipRow(
            options = NetworkChoice.entries.map { it.label },
            selectedIndex = network.ordinal,
            onSelect = { network = NetworkChoice.entries[it] },
        )

        Spacer(modifier = Modifier.height(Spacing.SectionGap))

        OutlinedTextField(
            value = address,
            onValueChange = { address = it.trim() },
            label = { Text("contract address", color = Colors.OnSurfaceDim) },
            placeholder = { Text("64 hex chars", color = Colors.OnSurfaceSubtle) },
            modifier = Modifier.fillMaxWidth(),
            colors = textFieldColors(),
            singleLine = true,
        )

        val hint = if (mode == ConnectionMode.STANDALONE)
            "Uses embedded wallet (no mn serve needed). Test seed."
        else
            "Requires mn serve --approve-all running on host"
        Text(
            hint,
            color = if (mode == ConnectionMode.STANDALONE) Colors.Accent.copy(alpha = 0.6f) else Colors.OnSurfaceSubtle,
            fontSize = Type.Caption,
            modifier = Modifier.padding(top = Spacing.TinyGap),
        )

        Spacer(modifier = Modifier.height(Spacing.SectionGap))

        val buttonLabel = if (mode == ConnectionMode.STANDALONE) "connect (standalone)" else "connect"
        ActionButton(buttonLabel, enabled = address.length == 64) {
            when (mode) {
                ConnectionMode.REMOTE -> onConnectRemote(address, network)
                ConnectionMode.STANDALONE -> {
                    val midnightNetwork = when (network) {
                        NetworkChoice.LOCALNET -> MidnightNetwork.UNDEPLOYED
                        NetworkChoice.PREVIEW -> MidnightNetwork.PREVIEW
                        NetworkChoice.PREPROD -> MidnightNetwork.PREPROD
                    }
                    onConnectSdk(address, midnightNetwork, TEST_SEED)
                }
            }
        }

        if (mode == ConnectionMode.STANDALONE) {
            Spacer(modifier = Modifier.height(Spacing.SmallGap))
            Text("— or deploy a fresh instance —", color = Colors.OnSurfaceSubtle, fontSize = Type.Caption,
                modifier = Modifier.fillMaxWidth(), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            Spacer(modifier = Modifier.height(Spacing.SmallGap))
            ActionButton("deploy new contract", enabled = true) {
                val midnightNetwork = when (network) {
                    NetworkChoice.LOCALNET -> MidnightNetwork.UNDEPLOYED
                    NetworkChoice.PREVIEW -> MidnightNetwork.PREVIEW
                    NetworkChoice.PREPROD -> MidnightNetwork.PREPROD
                }
                onDeploySdk(midnightNetwork, TEST_SEED)
            }
        }
    }
}

/**
 * Test seed for standalone SDK mode.
 * In a real dApp this would come from the identity module (passkeys).
 * This is the alice wallet's 64-byte PBKDF2 seed (from `mn wallet seed`).
 */
private val TEST_SEED = hexToBytes(
    "7dc468f62278cd0c14b6674f31531a90b64599d657d3c7ab2adb63395d647f7a" +
    "505de6428fcf8b0d208873f4d5e2a1340c14688067477542f53c48dfea817da4"
)

private fun hexToBytes(hex: String): ByteArray =
    ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

// ── Connected Screen ──

@Composable
private fun ConnectedScreen(
    state: BBoardState.Connected,
    onPost: (String) -> Unit,
    onTakeDown: () -> Unit,
    onRefresh: () -> Unit,
    onDisconnect: () -> Unit,
) {
    val isSyncing = state.dustSyncStatus is DustSyncStatus.Syncing
    val isProcessing = state.dustSyncStatus is DustSyncStatus.Processing
    val isReady = state.dustSyncStatus is DustSyncStatus.Ready

    DarkCard {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(state.networkId, color = Colors.OnSurfaceDim, fontSize = Type.Caption, letterSpacing = 2.sp)
            val modeLabel = if (state.standalone) "standalone" else "remote"
            Text(
                "\u2022 $modeLabel",
                color = if (state.standalone) Colors.Accent else Colors.Success,
                fontSize = Type.Caption,
            )
        }
        Spacer(modifier = Modifier.height(Spacing.SmallGap))
        val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current
        var copied by remember { mutableStateOf(false) }
        Row(
            modifier = Modifier.fillMaxWidth().clickable {
                clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(state.contractAddress))
                copied = true
            },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                state.contractAddress,
                color = Colors.OnSurfaceSubtle,
                fontSize = Type.Mono,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                if (copied) "copied" else "tap to copy",
                color = if (copied) Colors.Success else Colors.OnSurfaceSubtle,
                fontSize = Type.Caption,
                modifier = Modifier.padding(start = Spacing.SmallGap),
            )
        }

        // Dust sync progress — inline, non-blocking
        when {
            isSyncing && state.dustSyncStatus is DustSyncStatus.Syncing -> {
                val sync = state.dustSyncStatus
                Spacer(modifier = Modifier.height(Spacing.ItemGap))
                SyncProgressBar(progress = sync.percent / 100f, label = "syncing dust: ${sync.percent}% — ${sync.detail}")
            }
            isProcessing && state.dustSyncStatus is DustSyncStatus.Processing -> {
                val proc = state.dustSyncStatus
                Spacer(modifier = Modifier.height(Spacing.ItemGap))
                SyncProgressBar(progress = null, label = proc.detail)
            }
            else -> {
                state.lastTimingMs?.let {
                    Spacer(modifier = Modifier.height(Spacing.TinyGap))
                    Text("last tx: ${it}ms", color = Colors.Success.copy(alpha = 0.7f), fontSize = Type.Caption)
                }
            }
        }
    }

    Spacer(modifier = Modifier.height(Spacing.SectionGap))

    DarkCard {
        when (val board = state.boardState) {
            is BoardState.Vacant -> VacantBoard(onPost = onPost, isEnabled = isReady)
            is BoardState.Working -> WorkingBoard(board.stage)
            is BoardState.Occupied -> OccupiedBoard(board.message, onTakeDown, onRefresh, isEnabled = isReady)
            is BoardState.CallError -> CallErrorView(board.message, onRefresh)
        }
    }

    Spacer(modifier = Modifier.height(Spacing.SectionGap * 2))

    Box(
        modifier = Modifier.fillMaxWidth().height(CHIP_HEIGHT_DP.dp).clickable(onClick = onDisconnect),
        contentAlignment = Alignment.Center,
    ) {
        Text("disconnect", color = Colors.OnSurfaceSubtle, fontSize = Type.Caption)
    }
}

@Composable
private fun VacantBoard(onPost: (String) -> Unit, isEnabled: Boolean = true) {
    var message by remember { mutableStateOf("") }
    Text("board is vacant", color = Colors.OnSurfaceDim, fontSize = Type.Caption, letterSpacing = 2.sp)
    Spacer(modifier = Modifier.height(Spacing.SectionGap))
    OutlinedTextField(
        value = message,
        onValueChange = { message = it },
        modifier = Modifier.fillMaxWidth(),
        placeholder = { Text("your message", color = Colors.OnSurfaceSubtle) },
        colors = textFieldColors(),
        singleLine = true,
    )
    Spacer(modifier = Modifier.height(Spacing.SectionGap))
    ActionButton("post", enabled = message.isNotBlank() && isEnabled) { onPost(message) }
    if (!isEnabled) {
        SyncWaitingHint()
    }
}

@Composable
private fun WorkingBoard(stage: String) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.ScreenPadding),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator(color = Colors.OnSurface, strokeWidth = 2.dp, modifier = Modifier.size(SPINNER_SIZE_DP.dp))
        Spacer(modifier = Modifier.height(Spacing.ItemGap))
        Text(stage, color = Colors.OnSurfaceDim, fontSize = Type.Label)
    }
}

@Composable
private fun OccupiedBoard(message: String, onTakeDown: () -> Unit, onRefresh: () -> Unit, isEnabled: Boolean = true) {
    Text("board is occupied", color = Colors.OnSurfaceDim, fontSize = Type.Caption, letterSpacing = 2.sp)
    Spacer(modifier = Modifier.height(Spacing.SectionGap))
    Text(message, color = Colors.OnSurface, fontSize = 18.sp, fontWeight = FontWeight.W300)
    Spacer(modifier = Modifier.height(Spacing.SectionGap))
    ActionButton("take down", enabled = isEnabled, dimmed = true, onClick = onTakeDown)
    Spacer(modifier = Modifier.height(Spacing.SmallGap))
    ActionButton("refresh", enabled = true, dimmed = true, onClick = onRefresh)
    if (!isEnabled) {
        SyncWaitingHint()
    }
}

@Composable
private fun CallErrorView(message: String, onRetry: () -> Unit) {
    Text(message, color = Colors.Error, fontSize = Type.Label)
    Spacer(modifier = Modifier.height(Spacing.SectionGap))
    ActionButton("retry", enabled = true, dimmed = true, onClick = onRetry)
}

// ── Shared Components ──

@Composable
private fun ConnectingView(stage: String) {
    DarkCard {
        Column(Modifier.fillMaxWidth().padding(vertical = Spacing.ScreenPadding), horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = Colors.OnSurface, strokeWidth = 2.dp, modifier = Modifier.size(SPINNER_SIZE_DP.dp))
            Spacer(modifier = Modifier.height(Spacing.ItemGap))
            Text(stage, color = Colors.OnSurfaceDim, fontSize = Type.Label)
        }
    }
}

@Composable
private fun ErrorView(message: String, onBack: () -> Unit) {
    DarkCard(color = Colors.ErrorSurface) {
        Text(message, color = Colors.Error, fontSize = Type.Label)
        Spacer(modifier = Modifier.height(Spacing.SectionGap))
        ActionButton("back", enabled = true, dimmed = true, onClick = onBack)
    }
}

/** Inline progress bar with label — used for dust sync. */
@Composable
private fun SyncProgressBar(progress: Float?, label: String) {
    if (progress != null) {
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxWidth().height(PROGRESS_BAR_HEIGHT_DP.dp),
            color = Colors.Accent,
            trackColor = Colors.Disabled,
        )
    } else {
        LinearProgressIndicator(
            modifier = Modifier.fillMaxWidth().height(PROGRESS_BAR_HEIGHT_DP.dp),
            color = Colors.Accent,
            trackColor = Colors.Disabled,
        )
    }
    Spacer(modifier = Modifier.height(Spacing.TinyGap))
    Text(label, color = Colors.Accent.copy(alpha = 0.8f), fontSize = Type.Caption)
}

/** "waiting for dust sync..." hint shown below disabled buttons. */
@Composable
private fun SyncWaitingHint() {
    Spacer(modifier = Modifier.height(Spacing.TinyGap))
    Text("waiting for dust sync...", color = Colors.OnSurfaceDim, fontSize = Type.Caption)
}

/** Horizontal chip row for selection (mode, network). */
@Composable
private fun ChipRow(
    options: List<String>,
    selectedIndex: Int,
    accentSelected: Boolean = false,
    onSelect: (Int) -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.SmallGap)) {
        options.forEachIndexed { index, label ->
            val isSelected = index == selectedIndex
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(CHIP_HEIGHT_DP.dp)
                    .clip(Shapes.Chip)
                    .background(
                        when {
                            isSelected && accentSelected -> Colors.Accent
                            isSelected -> Colors.OnSurface
                            else -> Colors.Disabled
                        }
                    )
                    .clickable { onSelect(index) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    color = if (isSelected) Color.Black else Colors.OnSurfaceDim,
                    fontSize = Type.Label,
                    fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal,
                )
            }
        }
    }
}

@Composable
private fun DarkCard(color: Color = Colors.Surface, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = color),
        shape = Shapes.Card,
    ) { Column(Modifier.padding(Spacing.CardPadding)) { content() } }
}

@Composable
private fun ActionButton(text: String, enabled: Boolean, dimmed: Boolean = false, onClick: () -> Unit) {
    val bg = when {
        !enabled -> Colors.Disabled
        dimmed -> Color.White.copy(alpha = 0.12f)
        else -> Colors.OnSurface
    }
    val fg = when {
        !enabled -> Colors.OnSurfaceSubtle
        dimmed -> Colors.OnSurfaceDim
        else -> Color.Black
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(BUTTON_HEIGHT_DP.dp)
            .clip(Shapes.Button)
            .background(bg)
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = fg, fontSize = Type.Body, fontWeight = FontWeight.Medium) }
}

@Composable
private fun textFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = Colors.OnSurface,
    unfocusedTextColor = Colors.OnSurface,
    focusedBorderColor = Colors.OnSurfaceDim,
    unfocusedBorderColor = Colors.Disabled,
    cursorColor = Colors.OnSurface,
)
