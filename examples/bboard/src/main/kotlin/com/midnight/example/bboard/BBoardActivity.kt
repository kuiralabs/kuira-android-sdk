package com.midnight.example.bboard

import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import com.midnight.example.common.PanelBar
import com.midnight.example.common.wallet.WalletStatusPanel
import com.midnight.kuira.core.ledger.ui.BalanceFormatter
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

class BBoardActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { BBoardApp() }
    }
}

@Composable
fun BBoardApp(viewModel: BBoardViewModel = viewModel()) {
    val state by viewModel.state.collectAsState()
    val sigilState by viewModel.sigilState.collectAsState()
    // FragmentActivity (which ComponentActivity extends) hosts SeedVault's
    // biometric prompts. Same instance also satisfies Activity for the
    // legacy sigil-side callbacks.
    val activity = LocalContext.current as? FragmentActivity

    // Mirror of the network the WalletStatusPanel is on — the panel owns
    // the selection (chip lives in its sheet), but BBoard's deploy/connect
    // operations need to target the same chain. The panel's
    // `onNetworkChange` callback keeps this in sync; we seed it with
    // UNDEPLOYED so the first deploy goes to localnet if the user hasn't
    // touched the panel chip yet.
    var midnightNetwork by rememberSaveable { mutableStateOf(MidnightNetwork.UNDEPLOYED) }

    Surface(modifier = Modifier.fillMaxSize(), color = Colors.Background) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Top panel bar: sigil chip (left) + wallet chip (right). Pulled
            // out of the scroll container so the chips stay pinned to the top
            // of the screen as the host content scrolls underneath. Pushes
            // the BBoard title below it — title no longer fights chip widths
            // for the same row on narrow phones.
            PanelBar(
                network = midnightNetwork,
                onNetworkChange = { midnightNetwork = it },
            )
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(
                        top = Spacing.SectionGap,
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
                    sigilState = sigilState,
                    onForgeSigil = { activity?.let { viewModel.forgeSigil(it) } },
                    onTestPrf = { activity?.let { viewModel.testPrf(it) } },
                    onBackup = { activity?.let { viewModel.backupSeed(it) } },
                    onRestore = { activity?.let { viewModel.restoreSeed(it) } },
                    onConnectSdk = { addr ->
                        activity?.let { viewModel.connectWithSdk(addr, midnightNetwork, it) }
                    },
                    onDeploySdk = {
                        activity?.let { viewModel.deployAndConnect(midnightNetwork, it) }
                    },
                )
                is BBoardState.Connecting -> ConnectingView(s.stage)
                is BBoardState.Error -> ErrorView(s.message) { viewModel.disconnect() }
                    is BBoardState.Connected -> ConnectedScreen(
                        state = s,
                        sigilState = sigilState,
                        onAuthorize = { activity?.let { viewModel.authorizeAccessKey(it) } },
                        onBackup = { activity?.let { viewModel.backupSeed(it) } },
                        onRestore = { activity?.let { viewModel.restoreSeed(it) } },
                        onPost = viewModel::post,
                        onTakeDown = viewModel::takeDown,
                        onRefresh = viewModel::refresh,
                        onDisconnect = viewModel::disconnect,
                    )
                }
            }
        }
    }
}

// ── Setup Screen ──
//
// Stripped to two cards: sigil identity + contract connection. Network
// selection lives in WalletStatusPanel's sheet (anchored top-right by
// BBoardApp). Wallet status (balance / fund / register) is the panel too —
// the in-screen WalletStatusCard was redundant with that and was removed.
// Only one connection mode remains: standalone SDK. The old "Remote Wallet"
// (mn serve via WebSocket) path is gone — see commit log for rationale.

@Composable
private fun SetupScreen(
    sigilState: SigilState,
    onForgeSigil: () -> Unit,
    onTestPrf: () -> Unit,
    onBackup: () -> Unit,
    onRestore: () -> Unit,
    onConnectSdk: (String) -> Unit,
    onDeploySdk: () -> Unit,
) {
    var address by remember { mutableStateOf("") }

    // ── Sigil Identity Card ──
    SigilCard(
        sigilState = sigilState,
        onForgeSigil = onForgeSigil,
        onTestPrf = onTestPrf,
        onBackup = onBackup,
        onRestore = onRestore,
    )
    Spacer(modifier = Modifier.height(Spacing.SectionGap))

    // ── Contract Connection Card ──
    DarkCard {
        Text("connect to contract", color = Colors.OnSurfaceDim, fontSize = Type.Caption, letterSpacing = 2.sp)
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

        Text(
            "Uses the standalone SDK (no mn serve needed). Network follows the wallet panel.",
            color = Colors.Accent.copy(alpha = 0.6f),
            fontSize = Type.Caption,
            modifier = Modifier.padding(top = Spacing.TinyGap),
        )

        Spacer(modifier = Modifier.height(Spacing.SectionGap))

        ActionButton("connect", enabled = address.length == 64) {
            onConnectSdk(address)
        }

        Spacer(modifier = Modifier.height(Spacing.SmallGap))
        Text(
            "— or deploy a fresh instance —",
            color = Colors.OnSurfaceSubtle,
            fontSize = Type.Caption,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(Spacing.SmallGap))
        ActionButton("deploy new contract", enabled = true) {
            onDeploySdk()
        }
    }
}


// ── Connected Screen ──

@Composable
private fun ConnectedScreen(
    state: BBoardState.Connected,
    sigilState: SigilState,
    onAuthorize: () -> Unit,
    onBackup: () -> Unit,
    onRestore: () -> Unit,
    onPost: (String) -> Unit,
    onTakeDown: () -> Unit,
    onRefresh: () -> Unit,
    onDisconnect: () -> Unit,
) {
    val isSyncing = state.dustSyncStatus is DustSyncStatus.Syncing
    val isProcessing = state.dustSyncStatus is DustSyncStatus.Processing
    val isReady = state.dustSyncStatus is DustSyncStatus.Ready

    // Show sigil card based on identity state
    when (sigilState) {
        is SigilState.Forged, is SigilState.Authorizing -> {
            SigilAuthCard(sigilState = sigilState, onAuthorize = onAuthorize)
            Spacer(modifier = Modifier.height(Spacing.SmallGap))
            ActionButton("backup to cloud", enabled = true, dimmed = true, onClick = onBackup)
            Spacer(modifier = Modifier.height(Spacing.TinyGap))
            ActionButton("restore from cloud", enabled = true, dimmed = true, onClick = onRestore)
            Spacer(modifier = Modifier.height(Spacing.SectionGap))
        }
        is SigilState.Authorized -> {
            SigilAuthorizedCard(sigilState, onBackup = onBackup, onRestore = onRestore)
            Spacer(modifier = Modifier.height(Spacing.SectionGap))
        }
        else -> {}
    }

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
        val clipboardManager = LocalClipboardManager.current
        var copied by remember { mutableStateOf(false) }
        Row(
            modifier = Modifier.fillMaxWidth().clickable {
                clipboardManager.setText(AnnotatedString(state.contractAddress))
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

// ── Sigil Identity Components ──

@Composable
private fun SigilCard(
    sigilState: SigilState,
    onForgeSigil: () -> Unit,
    onTestPrf: () -> Unit,
    onBackup: () -> Unit,
    onRestore: () -> Unit,
) {
    DarkCard {
        Text("sigil identity", color = Colors.OnSurfaceDim, fontSize = Type.Caption, letterSpacing = 2.sp)
        Spacer(modifier = Modifier.height(Spacing.ItemGap))

        when (sigilState) {
            is SigilState.None -> {
                Text(
                    "Create a passkey to establish your identity. One DID, stable across all Midnight dApps.",
                    color = Colors.OnSurfaceSubtle,
                    fontSize = Type.Caption,
                )
                Spacer(modifier = Modifier.height(Spacing.ItemGap))
                ActionButton("forge sigil", enabled = true, onClick = onForgeSigil)
                Spacer(modifier = Modifier.height(Spacing.SmallGap))
                ActionButton("restore from cloud", enabled = true, dimmed = true, onClick = onRestore)
            }
            is SigilState.Creating -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(color = Colors.Accent, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.size(Spacing.SmallGap))
                    Text(sigilState.stage, color = Colors.OnSurfaceDim, fontSize = Type.Label)
                }
            }
            is SigilState.Forged -> {
                SigilInfo(did = sigilState.did, publicKeyHex = sigilState.publicKeyHex)
                Spacer(modifier = Modifier.height(Spacing.SmallGap))
                ActionButton("backup to cloud", enabled = true, dimmed = true, onClick = onBackup)
                Spacer(modifier = Modifier.height(Spacing.TinyGap))
                ActionButton("test prf", enabled = true, dimmed = true, onClick = onTestPrf)
            }
            is SigilState.Authorizing -> SigilInfo(did = sigilState.sigil.did, publicKeyHex = sigilState.sigil.publicKeyHex)
            is SigilState.Authorized -> SigilInfo(
                did = sigilState.did,
                publicKeyHex = sigilState.publicKeyHex,
                accessKeyHex = sigilState.accessKeyHex,
            )
            is SigilState.Error -> {
                Text(sigilState.message, color = Colors.Error, fontSize = Type.Label)
                Spacer(modifier = Modifier.height(Spacing.SmallGap))
                ActionButton("retry", enabled = true, dimmed = true, onClick = onForgeSigil)
            }
        }
    }
}

@Composable
private fun SigilInfo(did: String, publicKeyHex: String, accessKeyHex: String? = null) {
    MonoField(label = "did", value = did)
    Spacer(modifier = Modifier.height(Spacing.SmallGap))
    MonoField(label = "root key (P-256)", value = publicKeyHex)
    if (accessKeyHex != null) {
        Spacer(modifier = Modifier.height(Spacing.SmallGap))
        MonoField(label = "access key (secp256k1)", value = accessKeyHex)
    }
}

@Composable
private fun SigilAuthCard(sigilState: SigilState, onAuthorize: () -> Unit) {
    DarkCard {
        Text("authorize access key", color = Colors.OnSurfaceDim, fontSize = Type.Caption, letterSpacing = 2.sp)
        Spacer(modifier = Modifier.height(Spacing.ItemGap))
        Text(
            "Sign with your passkey to authorize the SDK's secp256k1 key for Midnight transactions.",
            color = Colors.OnSurfaceSubtle,
            fontSize = Type.Caption,
        )
        Spacer(modifier = Modifier.height(Spacing.ItemGap))

        if (sigilState is SigilState.Authorizing) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(color = Colors.Accent, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.size(Spacing.SmallGap))
                Text(sigilState.stage, color = Colors.OnSurfaceDim, fontSize = Type.Label)
            }
        } else {
            ActionButton("authorize", enabled = true, onClick = onAuthorize)
        }
    }
}

@Composable
private fun SigilAuthorizedCard(state: SigilState.Authorized, onBackup: () -> Unit, onRestore: () -> Unit) {
    DarkCard {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("sigil", color = Colors.OnSurfaceDim, fontSize = Type.Caption, letterSpacing = 2.sp)
            Text("authorized", color = Colors.Success, fontSize = Type.Caption)
        }
        Spacer(modifier = Modifier.height(Spacing.SmallGap))
        MonoField(label = "did", value = state.did)
        Spacer(modifier = Modifier.height(Spacing.SmallGap))
        MonoField(label = "access key", value = state.accessKeyHex)
        Spacer(modifier = Modifier.height(Spacing.SmallGap))
        Text("path: ${state.accessKeyPath}", color = Colors.OnSurfaceSubtle, fontSize = Type.Mono, fontFamily = FontFamily.Monospace)
        Spacer(modifier = Modifier.height(Spacing.SmallGap))
        ActionButton("backup to cloud", enabled = true, dimmed = true, onClick = onBackup)
        Spacer(modifier = Modifier.height(Spacing.TinyGap))
        ActionButton("restore from cloud", enabled = true, dimmed = true, onClick = onRestore)
    }
}

@Composable
private fun MonoField(label: String, value: String) {
    Text(label, color = Colors.OnSurfaceDim, fontSize = Type.Caption)
    Text(
        value,
        color = Colors.Accent,
        fontSize = Type.Mono,
        fontFamily = FontFamily.Monospace,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
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
private fun ActionButton(
    text: String,
    enabled: Boolean,
    dimmed: Boolean = false,
    modifier: Modifier = Modifier.fillMaxWidth(),
    onClick: () -> Unit,
) {
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
        modifier = modifier
            .height(BUTTON_HEIGHT_DP.dp)
            .clip(Shapes.Button)
            .background(bg)
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color = fg,
            fontSize = Type.Body,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
    }
}

@Composable
private fun textFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = Colors.OnSurface,
    unfocusedTextColor = Colors.OnSurface,
    focusedBorderColor = Colors.OnSurfaceDim,
    unfocusedBorderColor = Colors.Disabled,
    cursorColor = Colors.OnSurface,
)
