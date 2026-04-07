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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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

/**
 * BBoard — Midnight Bulletin Board example for Android.
 *
 * Demonstrates how to build a dApp using the Midnight Contract SDK.
 * No browser, no WebView — pure native Android executing ZK circuits
 * and submitting transactions to the Midnight blockchain.
 *
 * This is the Android equivalent of example-bboard (React).
 */
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
        Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
            Text("bboard", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.W300, letterSpacing = 4.sp)
            Text("midnight bulletin board", color = Dim, fontSize = 12.sp, letterSpacing = 2.sp)
            Spacer(modifier = Modifier.height(32.dp))

            when (val s = state) {
                is BBoardState.Setup -> SetupScreen(onConnect = viewModel::connect)
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

@Composable
private fun SetupScreen(onConnect: (String, NetworkChoice) -> Unit) {
    var address by remember { mutableStateOf("") }
    var network by remember { mutableStateOf(NetworkChoice.LOCALNET) }
    var expanded by remember { mutableStateOf(false) }

    DarkCard {
        Text("connect to contract", color = Dim, fontSize = 11.sp, letterSpacing = 2.sp)
        Spacer(modifier = Modifier.height(16.dp))

        Box {
            OutlinedTextField(
                value = network.label,
                onValueChange = {},
                readOnly = true,
                label = { Text("network", color = Dim) },
                modifier = Modifier.fillMaxWidth().clickable { expanded = true },
                colors = textFieldColors(),
            )
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                NetworkChoice.entries.forEach { choice ->
                    DropdownMenuItem(
                        text = { Text(choice.label) },
                        onClick = { network = choice; expanded = false },
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        OutlinedTextField(
            value = address,
            onValueChange = { address = it.trim() },
            label = { Text("contract address", color = Dim) },
            placeholder = { Text("64 hex chars", color = Color.White.copy(alpha = 0.15f)) },
            modifier = Modifier.fillMaxWidth(),
            colors = textFieldColors(),
            singleLine = true,
        )
        Text(
            "Deploy with: mn contract deploy --network ${network.networkId}",
            color = Color.White.copy(alpha = 0.2f),
            fontSize = 10.sp,
            modifier = Modifier.padding(top = 4.dp),
        )

        Spacer(modifier = Modifier.height(20.dp))
        ActionButton("connect", enabled = address.length == 64) { onConnect(address, network) }
    }
}

// ── Connected Screen ──

@Composable
private fun ConnectedScreen(
    state: BBoardState.Connected,
    onPost: (String) -> Unit,
    onTakeDown: () -> Unit,
    onRefresh: () -> Unit,
    onDisconnect: () -> Unit,
) {
    DarkCard {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(state.networkId, color = Dim, fontSize = 11.sp, letterSpacing = 2.sp)
            Text("\u2022 connected", color = Green, fontSize = 11.sp)
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
        state.lastTimingMs?.let {
            Spacer(modifier = Modifier.height(4.dp))
            Text("last tx: ${it}ms", color = Green.copy(alpha = 0.6f), fontSize = 10.sp)
        }
    }

    Spacer(modifier = Modifier.height(16.dp))

    DarkCard {
        when (val board = state.boardState) {
            is BoardState.Vacant -> VacantBoard(onPost)
            is BoardState.Working -> WorkingBoard(board.stage)
            is BoardState.Occupied -> OccupiedBoard(board.message, onTakeDown, onRefresh)
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
private fun VacantBoard(onPost: (String) -> Unit) {
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
    ActionButton("post", enabled = message.isNotBlank()) { onPost(message) }
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
private fun OccupiedBoard(message: String, onTakeDown: () -> Unit, onRefresh: () -> Unit) {
    Text("board is occupied", color = Dim, fontSize = 11.sp, letterSpacing = 2.sp)
    Spacer(modifier = Modifier.height(16.dp))
    Text(message, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.W300)
    Spacer(modifier = Modifier.height(16.dp))
    ActionButton("take down", enabled = true, dimmed = true, onClick = onTakeDown)
    Spacer(modifier = Modifier.height(8.dp))
    ActionButton("refresh", enabled = true, dimmed = true, onClick = onRefresh)
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
