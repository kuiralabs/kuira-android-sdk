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
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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

class BBoardActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { BBoardApp() }
    }
}

@Composable
fun BBoardApp(viewModel: BBoardViewModel = viewModel()) {
    val state by viewModel.state.collectAsState()

    Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF0A0A0A)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    top = androidx.compose.foundation.layout.WindowInsets.statusBars
                        .asPaddingValues().calculateTopPadding() + 16.dp,
                    start = 24.dp,
                    end = 24.dp,
                    bottom = 24.dp,
                )
        ) {
            Text("bboard", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.W300, letterSpacing = 4.sp)
            Text("midnight bulletin board", color = Dim, fontSize = 12.sp, letterSpacing = 2.sp)
            Spacer(modifier = Modifier.height(32.dp))

            when (val s = state) {
                is BBoardState.Setup -> SetupScreen(
                    onConnectRemote = viewModel::connect,
                    onConnectSdk = viewModel::connectWithSdk,
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
) {
    var address by remember { mutableStateOf("") }
    var network by remember { mutableStateOf(NetworkChoice.LOCALNET) }
    var mode by remember { mutableStateOf(ConnectionMode.REMOTE) }

    DarkCard {
        Text("connect to contract", color = Dim, fontSize = 11.sp, letterSpacing = 2.sp)
        Spacer(modifier = Modifier.height(16.dp))

        // Connection mode toggle
        Text("mode", color = Dim, fontSize = 12.sp)
        Spacer(modifier = Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ConnectionMode.entries.forEach { m ->
                val selected = m == mode
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(40.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            if (selected) {
                                if (m == ConnectionMode.STANDALONE) Accent else Color.White
                            } else {
                                Color.White.copy(alpha = 0.06f)
                            }
                        )
                        .clickable { mode = m },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        m.label,
                        color = if (selected) Color.Black else Color.White.copy(alpha = 0.5f),
                        fontSize = 13.sp,
                        fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Network picker
        Text("network", color = Dim, fontSize = 12.sp)
        Spacer(modifier = Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NetworkChoice.entries.forEach { choice ->
                val selected = choice == network
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(40.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (selected) Color.White else Color.White.copy(alpha = 0.06f))
                        .clickable { network = choice },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        choice.label,
                        color = if (selected) Color.Black else Color.White.copy(alpha = 0.5f),
                        fontSize = 13.sp,
                        fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        OutlinedTextField(
            value = address,
            onValueChange = { address = it.trim() },
            label = { Text("contract address", color = Dim) },
            placeholder = { Text("64 hex chars", color = Color.White.copy(alpha = 0.15f)) },
            modifier = Modifier.fillMaxWidth(),
            colors = textFieldColors(),
            singleLine = true,
        )

        if (mode == ConnectionMode.STANDALONE) {
            Text(
                "Uses embedded wallet (no mn serve needed). Test seed.",
                color = Accent.copy(alpha = 0.5f),
                fontSize = 10.sp,
                modifier = Modifier.padding(top = 4.dp),
            )
        } else {
            Text(
                "Requires mn serve --approve-all running on host",
                color = Color.White.copy(alpha = 0.2f),
                fontSize = 10.sp,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        Spacer(modifier = Modifier.height(20.dp))

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
            Text(state.networkId, color = Dim, fontSize = 11.sp, letterSpacing = 2.sp)
            val modeLabel = if (state.standalone) "standalone" else "remote"
            Text("\u2022 $modeLabel", color = if (state.standalone) Accent else Green, fontSize = 11.sp)
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            state.contractAddress,
            color = Color.White.copy(alpha = 0.4f),
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        // Dust sync progress — inline, non-blocking
        when {
            isSyncing -> {
                val sync = state.dustSyncStatus as DustSyncStatus.Syncing
                Spacer(modifier = Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { sync.percent / 100f },
                    modifier = Modifier.fillMaxWidth().height(3.dp),
                    color = Accent,
                    trackColor = Color.White.copy(alpha = 0.1f),
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "syncing dust: ${sync.percent}% — ${sync.detail}",
                    color = Accent.copy(alpha = 0.7f),
                    fontSize = 10.sp,
                )
            }
            isProcessing -> {
                val proc = state.dustSyncStatus as DustSyncStatus.Processing
                Spacer(modifier = Modifier.height(8.dp))
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().height(3.dp),
                    color = Accent,
                    trackColor = Color.White.copy(alpha = 0.1f),
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    proc.detail,
                    color = Accent.copy(alpha = 0.7f),
                    fontSize = 10.sp,
                )
            }
            else -> {
                state.lastTimingMs?.let {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("last tx: ${it}ms", color = Green.copy(alpha = 0.6f), fontSize = 10.sp)
                }
            }
        }
    }

    Spacer(modifier = Modifier.height(16.dp))

    DarkCard {
        when (val board = state.boardState) {
            is BoardState.Vacant -> VacantBoard(onPost = onPost, isEnabled = isReady)
            is BoardState.Working -> WorkingBoard(board.stage)
            is BoardState.Occupied -> OccupiedBoard(board.message, onTakeDown, onRefresh, isEnabled = isReady)
            is BoardState.CallError -> CallErrorView(board.message, onRefresh)
        }
    }

    Spacer(modifier = Modifier.height(32.dp))

    Box(
        modifier = Modifier.fillMaxWidth().height(40.dp).clickable(onClick = onDisconnect),
        contentAlignment = Alignment.Center,
    ) {
        Text("disconnect", color = Color.White.copy(alpha = 0.3f), fontSize = 12.sp)
    }
}

@Composable
private fun VacantBoard(onPost: (String) -> Unit, isEnabled: Boolean = true) {
    var message by remember { mutableStateOf("") }
    Text("board is vacant", color = Dim, fontSize = 11.sp, letterSpacing = 2.sp)
    Spacer(modifier = Modifier.height(16.dp))
    OutlinedTextField(
        value = message,
        onValueChange = { message = it },
        modifier = Modifier.fillMaxWidth(),
        placeholder = { Text("your message", color = Color.White.copy(alpha = 0.2f)) },
        colors = textFieldColors(),
        singleLine = true,
    )
    Spacer(modifier = Modifier.height(16.dp))
    ActionButton("post", enabled = message.isNotBlank() && isEnabled) { onPost(message) }
    if (!isEnabled) {
        Spacer(modifier = Modifier.height(4.dp))
        Text("waiting for dust sync...", color = Dim, fontSize = 10.sp)
    }
}

@Composable
private fun WorkingBoard(stage: String) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(24.dp))
        Spacer(modifier = Modifier.height(12.dp))
        Text(stage, color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp)
    }
}

@Composable
private fun OccupiedBoard(message: String, onTakeDown: () -> Unit, onRefresh: () -> Unit, isEnabled: Boolean = true) {
    Text("board is occupied", color = Dim, fontSize = 11.sp, letterSpacing = 2.sp)
    Spacer(modifier = Modifier.height(16.dp))
    Text(message, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.W300)
    Spacer(modifier = Modifier.height(16.dp))
    ActionButton("take down", enabled = isEnabled, dimmed = true, onClick = onTakeDown)
    Spacer(modifier = Modifier.height(8.dp))
    ActionButton("refresh", enabled = true, dimmed = true, onClick = onRefresh)
    if (!isEnabled) {
        Spacer(modifier = Modifier.height(4.dp))
        Text("waiting for dust sync...", color = Dim, fontSize = 10.sp)
    }
}

@Composable
private fun CallErrorView(message: String, onRetry: () -> Unit) {
    Text(message, color = Color(0xFFFF6666), fontSize = 13.sp)
    Spacer(modifier = Modifier.height(16.dp))
    ActionButton("retry", enabled = true, dimmed = true, onClick = onRetry)
}

// ── Shared Components ──

@Composable
private fun ConnectingView(stage: String) {
    DarkCard {
        Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(24.dp))
            Spacer(modifier = Modifier.height(12.dp))
            Text(stage, color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp)
        }
    }
}

@Composable
private fun ErrorView(message: String, onBack: () -> Unit) {
    DarkCard(color = Color(0xFF1A0A0A)) {
        Text(message, color = Color(0xFFFF6666), fontSize = 13.sp)
        Spacer(modifier = Modifier.height(16.dp))
        ActionButton("back", enabled = true, dimmed = true, onClick = onBack)
    }
}

@Composable
private fun DarkCard(color: Color = Color(0xFF111111), content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = color),
        shape = RoundedCornerShape(16.dp),
    ) { Column(Modifier.padding(24.dp)) { content() } }
}

@Composable
private fun ActionButton(text: String, enabled: Boolean, dimmed: Boolean = false, onClick: () -> Unit) {
    val bg = when { !enabled -> Color.White.copy(alpha = 0.05f); dimmed -> Color.White.copy(alpha = 0.1f); else -> Color.White }
    val fg = when { !enabled -> Color.White.copy(alpha = 0.2f); dimmed -> Color.White.copy(alpha = 0.6f); else -> Color.Black }
    Box(
        modifier = Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(12.dp)).background(bg)
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = fg, fontSize = 14.sp, fontWeight = FontWeight.Medium) }
}

@Composable
private fun textFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = Color.White, unfocusedTextColor = Color.White,
    focusedBorderColor = Color.White.copy(alpha = 0.3f), unfocusedBorderColor = Color.White.copy(alpha = 0.1f),
    cursorColor = Color.White,
)

private val Dim = Color.White.copy(alpha = 0.4f)
private val Green = Color(0xFF4CAF8B)
private val Accent = Color(0xFF64B5F6)
