package com.midnight.example.bboard

import android.content.Context
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
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import com.midnight.kuira.core.compact.ContractCallStage
import com.midnight.kuira.core.network.MidnightNetwork
import androidx.compose.ui.text.AnnotatedString
import com.midnight.kuira.dapp.ContractCallProgressBar
import com.midnight.kuira.dapp.PanelBar
import com.midnight.kuira.dapp.sigil.SigilStatus
import dagger.hilt.android.AndroidEntryPoint

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

@AndroidEntryPoint
class BBoardActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { BBoardApp() }
    }
}

@Composable
fun BBoardApp(viewModel: BBoardViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsState()

    // The wallet panel (in PanelBar) hosts the biometric prompt now — BBoard's
    // connect/deploy are followers that wait on the shared SDK, so this screen
    // no longer needs a FragmentActivity handle.

    // The wallet panel is the in-session network authority: its chip drives
    // MidnightSdkProvider's config, and BBoard's contract ops read the active
    // network straight off the shared SDK. We still avoid a host-side mutable
    // mirror (that was a second source of truth that drifted) — the only
    // host-side touch is seeding the panel's initial from prefs + persisting
    // the choice on change (see NetworkPref below), so it survives a kill.

    // Mirror of the sigil panel's status so BBoard can gate the onboarding
    // banner. Panel emits None on first composition until it loads any
    // persisted sigil + then transitions to Forged. The banner only renders
    // while status is None (or has flipped to Error during a forge attempt)
    // — once Forged the user has all the affordances they need in the chip.
    //
    // Plain `remember` (not `rememberSaveable`) because [SigilStatus] is a
    // sealed class with non-parcelable variants; the panel re-emits on
    // recomposition / process restore anyway, so this is fine.
    var sigilStatus: SigilStatus by remember { mutableStateOf<SigilStatus>(SigilStatus.None) }

    // Persist the wallet panel's network choice across launches. The panel
    // stays the in-session source of truth (no host-side mirror state); we only
    // seed its initial from prefs and write back on change. The SDK is network-
    // agnostic — remembering the choice is the app's job.
    val context = LocalContext.current

    Surface(modifier = Modifier.fillMaxSize(), color = Colors.Background) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Top panel bar: sigil chip (left) + wallet chip (right). Pulled
            // out of the scroll container so the chips stay pinned to the top
            // of the screen as the host content scrolls underneath. Pushes
            // the BBoard title below it — title no longer fights chip widths
            // for the same row on narrow phones.
            PanelBar(
                network = NetworkPref.load(context),
                onNetworkChange = { NetworkPref.save(context, it) },
                onSigilStatusChange = { sigilStatus = it },
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
                    sigilStatus = sigilStatus,
                    onConnectSdk = { addr -> viewModel.connectWithSdk(addr) },
                    onDeploySdk = { viewModel.deployAndConnect() },
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
    sigilStatus: SigilStatus,
    onConnectSdk: (String) -> Unit,
    onDeploySdk: () -> Unit,
) {
    var address by remember { mutableStateOf("") }

    // ── Sigil onboarding nudge (only when no identity yet) ──
    //
    // The full identity flow (forge, backup, restore, test prf, DID display)
    // lives in `SigilStatusPanel`'s top sheet — tapped via the sigil chip in
    // the panel bar above. This banner is a one-time-ish onboarding cue: it
    // points the user at the chip when they don't have a sigil yet so they
    // don't try to deploy/connect a contract before they have an identity.
    //
    // Auto-hides once the user has forged (or restored) a sigil — the chip
    // is then the canonical entry point.
    if (sigilStatus is SigilStatus.None || sigilStatus is SigilStatus.Error) {
        DarkCard {
            Text("identity required", color = Colors.Accent, fontSize = Type.Caption, letterSpacing = 2.sp)
            Spacer(modifier = Modifier.height(Spacing.SmallGap))
            Text(
                "You need a sigil before posting to the board. Tap the sigil chip in the top bar to forge one.",
                color = Colors.OnSurfaceDim,
                fontSize = Type.Caption,
            )
        }
        Spacer(modifier = Modifier.height(Spacing.SectionGap))
    }

    // ── Contract Connection Card ──
    DarkCard {
        Text("join a board", color = Colors.OnSurfaceDim, fontSize = Type.Caption, letterSpacing = 2.sp)
        Spacer(modifier = Modifier.height(Spacing.TinyGap))
        Text(
            "Paste the contract address someone shared to connect to their bulletin board.",
            color = Colors.OnSurfaceSubtle,
            fontSize = Type.Caption,
        )
        Spacer(modifier = Modifier.height(Spacing.SectionGap))

        OutlinedTextField(
            value = address,
            onValueChange = { address = it.trim() },
            label = { Text("contract address", color = Colors.OnSurfaceDim) },
            placeholder = { Text("paste a board's contract address", color = Colors.OnSurfaceSubtle) },
            modifier = Modifier.fillMaxWidth(),
            colors = textFieldColors(),
            singleLine = true,
        )
        Spacer(modifier = Modifier.height(Spacing.TinyGap))
        Text(
            "A contract address is 64 characters. The network comes from the wallet pill above.",
            color = Colors.OnSurfaceSubtle,
            fontSize = Type.Caption,
        )

        Spacer(modifier = Modifier.height(Spacing.SectionGap))

        // Gated on a forged sigil: without one, the wallet panel never builds
        // the shared SDK, so connect would wait forever (awaitSdk). The
        // "identity required" banner above explains why it's disabled.
        ActionButton("connect", enabled = address.length == 64 && sigilStatus is SigilStatus.Forged) {
            onConnectSdk(address)
        }

        Spacer(modifier = Modifier.height(Spacing.SmallGap))
        Text(
            "— or start a new board —",
            color = Colors.OnSurfaceSubtle,
            fontSize = Type.Caption,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(Spacing.SmallGap))
        ActionButton("create a new board", enabled = sigilStatus is SigilStatus.Forged) {
            onDeploySdk()
        }
        Spacer(modifier = Modifier.height(Spacing.TinyGap))
        Text(
            "Deploys a fresh board on-chain and connects you to it — you'll get an address to share.",
            color = Colors.OnSurfaceSubtle,
            fontSize = Type.Caption,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
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
    val isReady = state.dustSyncStatus is DustSyncStatus.Ready

    // Authorize panel removed in the post-A2 cleanup. Under PRF-derived
    // sigil + wallet, both keys are siblings of the same passkey — the
    // act of signing in via the sigil chip IS authorization. See the
    // commit log + Kicks wishlist #27 for the future delegated-access-
    // keys consumer that will revive a similar UI in a scoped context.

    DarkCard {
        // Contract address \u2014 labeled + tap-to-copy.
        //
        // Beginners land here right after connecting. Previously this was a
        // bare monospace hash next to "tap to copy" with no label \u2014 no way to
        // tell what it was. Now: a clear label, the hash as the card's primary
        // (brighter) content, an obvious copy affordance, and one line on what
        // it's for.
        Text("contract address", color = Colors.OnSurfaceDim, fontSize = Type.Caption, letterSpacing = 2.sp)
        Spacer(modifier = Modifier.height(Spacing.TinyGap))

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
                color = Colors.OnSurface,
                fontSize = Type.Mono,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                if (copied) "\u2713 copied" else "copy",
                color = if (copied) Colors.Success else Colors.Accent,
                fontSize = Type.Caption,
                modifier = Modifier.padding(start = Spacing.SmallGap),
            )
        }
        Spacer(modifier = Modifier.height(Spacing.TinyGap))
        Text(
            "The on-chain board you're connected to. Share this address so others can join the same board.",
            color = Colors.OnSurfaceSubtle,
            fontSize = Type.Caption,
        )

        // Dust sync progress — inline, non-blocking. Exhaustive when over the
        // sealed status (smart-casts each branch; no redundant flag checks).
        when (val dust = state.dustSyncStatus) {
            is DustSyncStatus.Syncing -> {
                Spacer(modifier = Modifier.height(Spacing.ItemGap))
                SyncProgressBar(progress = dust.percent / 100f, label = "syncing dust: ${dust.percent}% — ${dust.detail}")
            }
            is DustSyncStatus.Processing -> {
                Spacer(modifier = Modifier.height(Spacing.ItemGap))
                SyncProgressBar(progress = null, label = dust.detail)
            }
            is DustSyncStatus.Ready -> {
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
    Spacer(modifier = Modifier.height(Spacing.TinyGap))
    Text(
        "No message posted yet. Write one below — it's stored on-chain for anyone on this board to see.",
        color = Colors.OnSurfaceSubtle,
        fontSize = Type.Caption,
    )
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
private fun WorkingBoard(stage: ContractCallStage?) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.ScreenPadding),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator(color = Colors.OnSurface, strokeWidth = 2.dp, modifier = Modifier.size(SPINNER_SIZE_DP.dp))
        Spacer(modifier = Modifier.height(Spacing.ItemGap))
        if (stage == null) {
            // Brief pre-stage beat before the first ContractCallStage arrives.
            Text("Preparing…", color = Colors.OnSurfaceDim, fontSize = Type.Label)
        } else {
            // SDK drop-in: staged label + monotonic, eased progress bar, fed by
            // the live ContractCallStage from the contract call's onProgress.
            ContractCallProgressBar(
                stage = stage,
                accent = Colors.Accent,
                trackColor = Colors.Disabled,
                labelColor = Colors.OnSurfaceDim,
            )
        }
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

/**
 * App-layer persistence of the wallet panel's selected network across launches.
 * Mirrors Midnight Kicks' NetworkPref — the SDK is network-agnostic, so
 * remembering the choice is the app's responsibility. Plain prefs; the network
 * id isn't a secret.
 */
private object NetworkPref {
    private const val PREFS = "bboard_network_pref"
    private const val KEY = "selected_network"

    fun load(ctx: Context): MidnightNetwork =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, null)
            ?.let { name -> runCatching { MidnightNetwork.valueOf(name) }.getOrNull() }
            ?: MidnightNetwork.UNDEPLOYED

    fun save(ctx: Context, network: MidnightNetwork) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY, network.name)
            .apply()
    }
}
