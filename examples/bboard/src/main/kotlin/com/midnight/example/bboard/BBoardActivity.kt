package com.midnight.example.bboard

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
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
 * Demonstrates how an Android dApp connects to Kuira wallet
 * and calls ConnectedAPI methods. No browser, no WebView —
 * pure native Android talking to the Midnight blockchain
 * through Kuira.
 *
 * Equivalent of: example-bboard/bboard-ui (React)
 * but for Android-first development.
 */
class BBoardActivity : ComponentActivity() {

    private val approvalLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        // Phase 1 complete — user approved or denied in Kuira
        viewModel.onApprovalResult(result.resultCode)
    }

    private lateinit var viewModel: BBoardViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            viewModel = viewModel()
            BBoardApp(
                viewModel = viewModel,
                onRequestConnect = {
                    // Phase 1: launch Kuira's approval screen (we are foreground, so this works)
                    approvalLauncher.launch(KuiraWalletClient.createApprovalIntent())
                },
            )
        }
    }
}

@Composable
fun BBoardApp(
    viewModel: BBoardViewModel = viewModel(),
    onRequestConnect: () -> Unit = {},
) {
    val state by viewModel.state.collectAsState()

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color(0xFF0A0A0A),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
        ) {
            Text(
                text = "bboard",
                color = Color.White,
                fontSize = 24.sp,
                fontWeight = FontWeight.W300,
                letterSpacing = 4.sp,
            )
            Text(
                text = "midnight bulletin board",
                color = Color.White.copy(alpha = 0.4f),
                fontSize = 12.sp,
                letterSpacing = 2.sp,
            )

            Spacer(modifier = Modifier.height(32.dp))

            when (val s = state) {
                is BBoardState.Disconnected -> DisconnectedView(onRequestConnect)
                is BBoardState.Connecting -> ConnectingView()
                is BBoardState.Error -> ErrorView(s.message, onRequestConnect)
                is BBoardState.Connected -> ConnectedView(
                    state = s,
                    onPost = { viewModel.postMessage(it) },
                    onTakeDown = { viewModel.takeDown() },
                    onDisconnect = { viewModel.disconnect() },
                )
            }
        }
    }
}

@Composable
private fun DisconnectedView(onConnect: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF111111)),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(modifier = Modifier.padding(24.dp)) {
            Text(
                text = "Connect to Kuira wallet to start",
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 14.sp,
            )
            Spacer(modifier = Modifier.height(16.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.White)
                    .clickable(onClick = onConnect),
                contentAlignment = Alignment.Center,
            ) {
                Text("connect", color = Color.Black, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

@Composable
private fun ConnectingView() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF111111)),
        shape = RoundedCornerShape(16.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(32.dp),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp)
        }
    }
}

@Composable
private fun ErrorView(message: String, onRetry: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1A0A0A)),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(modifier = Modifier.padding(24.dp)) {
            Text(text = message, color = Color(0xFFFF6666), fontSize = 13.sp)
            Spacer(modifier = Modifier.height(16.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.White.copy(alpha = 0.1f))
                    .clickable(onClick = onRetry),
                contentAlignment = Alignment.Center,
            ) {
                Text("retry", color = Color.White.copy(alpha = 0.6f), fontSize = 14.sp)
            }
        }
    }
}

@Composable
private fun ConnectedView(
    state: BBoardState.Connected,
    onPost: (String) -> Unit,
    onTakeDown: () -> Unit,
    onDisconnect: () -> Unit,
) {
    // Wallet info card
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF111111)),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = state.networkId,
                    color = Color.White.copy(alpha = 0.4f),
                    fontSize = 11.sp,
                    letterSpacing = 2.sp,
                )
                Text(
                    text = "\u2022 connected",
                    color = Color(0xFF4CAF8B),
                    fontSize = 11.sp,
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            InfoLine("address", state.unshieldedAddress)
            InfoLine("shielded", state.shieldedAddress)
            InfoLine("indexer", state.indexerUri)
        }
    }

    Spacer(modifier = Modifier.height(16.dp))

    // Board card
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF111111)),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(modifier = Modifier.padding(24.dp)) {
            when (val board = state.boardState) {
                is BoardState.Vacant -> VacantBoard(onPost)
                is BoardState.Posting -> PostingBoard()
                is BoardState.Occupied -> OccupiedBoard(board, onTakeDown)
            }
        }
    }

    Spacer(modifier = Modifier.height(48.dp))

    // Disconnect
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(40.dp)
            .clickable(onClick = onDisconnect),
        contentAlignment = Alignment.Center,
    ) {
        Text("disconnect", color = Color.White.copy(alpha = 0.3f), fontSize = 12.sp)
    }
}

@Composable
private fun VacantBoard(onPost: (String) -> Unit) {
    var message by remember { mutableStateOf("") }

    Text("board is vacant", color = Color.White.copy(alpha = 0.4f), fontSize = 11.sp, letterSpacing = 2.sp)
    Spacer(modifier = Modifier.height(16.dp))

    OutlinedTextField(
        value = message,
        onValueChange = { message = it },
        modifier = Modifier.fillMaxWidth(),
        placeholder = { Text("your message", color = Color.White.copy(alpha = 0.2f)) },
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = Color.White,
            unfocusedTextColor = Color.White,
            focusedBorderColor = Color.White.copy(alpha = 0.3f),
            unfocusedBorderColor = Color.White.copy(alpha = 0.1f),
            cursorColor = Color.White,
        ),
        singleLine = true,
    )

    Spacer(modifier = Modifier.height(16.dp))

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (message.isNotBlank()) Color.White else Color.White.copy(alpha = 0.1f))
            .clickable { if (message.isNotBlank()) onPost(message) },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "post",
            color = if (message.isNotBlank()) Color.Black else Color.White.copy(alpha = 0.3f),
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun PostingBoard() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 24.dp),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp)
    }
}

@Composable
private fun OccupiedBoard(board: BoardState.Occupied, onTakeDown: () -> Unit) {
    Text("board is occupied", color = Color.White.copy(alpha = 0.4f), fontSize = 11.sp, letterSpacing = 2.sp)
    Spacer(modifier = Modifier.height(16.dp))

    Text(
        text = board.message,
        color = Color.White,
        fontSize = 18.sp,
        fontWeight = FontWeight.W300,
    )

    if (board.isOwner) {
        Spacer(modifier = Modifier.height(16.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color.White.copy(alpha = 0.1f))
                .clickable(onClick = onTakeDown),
            contentAlignment = Alignment.Center,
        ) {
            Text("take down", color = Color.White.copy(alpha = 0.6f), fontSize = 14.sp)
        }
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, color = Color.White.copy(alpha = 0.3f), fontSize = 11.sp)
        Text(
            value,
            color = Color.White.copy(alpha = 0.5f),
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 200.dp),
        )
    }
}

