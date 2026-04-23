package com.midnight.kuira.feature.settings

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.midnight.kuira.core.designsystem.component.GlassPanel
import com.midnight.kuira.core.designsystem.effect.StarField
import com.midnight.kuira.core.designsystem.theme.MidnightColors
import com.midnight.kuira.core.auth.AuthenticationCancelledException
import com.midnight.kuira.core.compact.proving.ProvingMode
import com.midnight.kuira.core.network.MidnightNetwork
import kotlinx.coroutines.launch

// Sizing tokens (mirrors DuskTokens)
private val TopBarHeight = 56.dp
private val RowMinHeight = 56.dp
private val Space8 = 8.dp
private val Space12 = 12.dp
private val Space16 = 16.dp
private val Space24 = 24.dp
private val Space32 = 32.dp
private val Icon16 = 16.dp
private val Icon24 = 24.dp
private val ButtonHeight = 48.dp
private val RadiusMd = 12.dp

// External URLs — update the BASE_URL when GitHub Pages is deployed.
// Create a public repo (e.g., nel349/kuira-wallet-site), push docs/public/
// contents, enable GitHub Pages → these URLs go live.
private const val PAGES_BASE_URL = "https://nel349.github.io/kuira-wallet-site"
// Repo: https://github.com/nel349/kuira-wallet-site
private const val URL_LICENSE = "$PAGES_BASE_URL/license.html"
private const val URL_GITHUB = "https://github.com/nel349/kuira-android-wallet"
private const val URL_SUPPORT = "https://github.com/nel349/kuira-android-wallet/issues"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit = {},
    onNavigateToRecoveryPhrase: () -> Unit = {},
    onWipeComplete: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val statusBarPadding = WindowInsets.statusBars.asPaddingValues()
    val navBarPadding = WindowInsets.navigationBars.asPaddingValues()

    // Sheet states
    var showNetworkPicker by remember { mutableStateOf(false) }
    var showWipeConfirmation by remember { mutableStateOf(false) }
    var showResyncConfirmation by remember { mutableStateOf(false) }
    var showProofServerEditor by remember { mutableStateOf(false) }
    var wipeChallenge by remember { mutableStateOf("") }

    fun openUrl(url: String) {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }

    fun triggerBiometricTest() {
        val activity = context as? FragmentActivity ?: return
        scope.launch {
            try {
                val seed = viewModel.testBiometric(activity)
                seed?.wipe()
                Toast.makeText(context, "Biometric OK", Toast.LENGTH_SHORT).show()
            } catch (e: AuthenticationCancelledException) {
                // User dismissed — not an error, no toast
            } catch (e: Exception) {
                Toast.makeText(context, "Biometric failed", Toast.LENGTH_SHORT).show()
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MidnightColors.Void),
    ) {
        StarField(modifier = Modifier.fillMaxSize(), starCount = 60)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = statusBarPadding.calculateTopPadding()),
        ) {
            // Top bar
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(TopBarHeight)
                    .padding(horizontal = Space16),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back to balance",
                    tint = MidnightColors.Light,
                    modifier = Modifier
                        .size(Icon24)
                        .clickable { onBack() },
                )
                Spacer(modifier = Modifier.width(Space16))
                Text(
                    text = "Settings",
                    color = MidnightColors.Light,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.W300,
                )
            }
            HorizontalDivider(color = MidnightColors.LightFaint, thickness = 1.dp)

            // Scrollable content
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Space16)
                    .padding(bottom = navBarPadding.calculateBottomPadding() + Space24),
            ) {
                Spacer(modifier = Modifier.height(Space16))

                // ── NETWORK ──
                SectionHeader("NETWORK")
                Spacer(modifier = Modifier.height(Space12))
                GlassPanel(
                    tint = MidnightColors.VoidElevated,
                    border = MidnightColors.LightFaint,
                    contentPadding = 0.dp,
                ) {
                    SettingsRowItem(
                        leadingIcon = Icons.Filled.Language,
                        label = "Network",
                        value = uiState.network.name,
                        onClick = { showNetworkPicker = true },
                    )
                    SettingsDividerItem()
                    SettingsRowItem(
                        leadingIcon = Icons.Filled.Sync,
                        label = "Last sync",
                        value = uiState.lastSyncAgo,
                        readOnly = true,
                    )
                }

                Spacer(modifier = Modifier.height(Space32))

                // ── DEVELOPER OPTIONS (conditional) ──
                if (uiState.devModeUnlocked) {
                    SectionHeader("DEVELOPER OPTIONS")
                    Spacer(modifier = Modifier.height(Space12))
                    GlassPanel(
                        tint = MidnightColors.VoidElevated,
                        border = MidnightColors.LightFaint,
                        contentPadding = 0.dp,
                    ) {
                        SettingsRowItem(
                            label = "Proof server",
                            value = uiState.proofServerDisplay,
                            onClick = { showProofServerEditor = true },
                        )
                        SettingsDividerItem()
                        DangerRowItem(
                            label = "Force re-sync",
                            leadingIcon = Icons.Filled.Sync,
                            onClick = { showResyncConfirmation = true },
                        )
                        SettingsDividerItem()
                        SettingsRowItem(
                            label = "Build info",
                            value = "${uiState.buildType} · ${uiState.commitHash}",
                            readOnly = true,
                        )
                    }
                    Spacer(modifier = Modifier.height(Space32))
                }

                // ── SECURITY ──
                SectionHeader("SECURITY")
                Spacer(modifier = Modifier.height(Space12))
                GlassPanel(
                    tint = MidnightColors.VoidElevated,
                    border = MidnightColors.LightFaint,
                    contentPadding = 0.dp,
                ) {
                    SettingsRowItem(
                        leadingIcon = Icons.Filled.Key,
                        label = "View recovery phrase",
                        onClick = onNavigateToRecoveryPhrase,
                    )
                    SettingsDividerItem()
                    SettingsRowItem(
                        leadingIcon = Icons.Filled.Fingerprint,
                        label = "Test biometric",
                        onClick = { triggerBiometricTest() },
                    )
                    SettingsDividerItem()
                    DangerRowItem(
                        label = "Wipe wallet",
                        leadingIcon = Icons.Filled.DeleteForever,
                        onClick = {
                            wipeChallenge = ""
                            showWipeConfirmation = true
                        },
                    )
                }

                Spacer(modifier = Modifier.height(Space32))

                // ── ABOUT ──
                SectionHeader("ABOUT")
                Spacer(modifier = Modifier.height(Space12))
                GlassPanel(
                    tint = MidnightColors.VoidElevated,
                    border = MidnightColors.LightFaint,
                    contentPadding = 0.dp,
                ) {
                    SettingsRowItem(
                        label = "Version",
                        value = uiState.versionName,
                        readOnly = true,
                        alwaysClickable = true,
                        onClick = { viewModel.onVersionTapped() },
                    )
                    SettingsDividerItem()
                    SettingsRowItem(
                        label = "Commit",
                        value = uiState.commitHash,
                        valueMono = true,
                        readOnly = true,
                    )
                    SettingsDividerItem()
                    SettingsRowItem(
                        label = "License",
                        onClick = { openUrl(URL_LICENSE) },
                    )
                    SettingsDividerItem()
                    SettingsRowItem(
                        label = "GitHub",
                        onClick = { openUrl(URL_GITHUB) },
                    )
                    SettingsDividerItem()
                    SettingsRowItem(
                        label = "Support",
                        onClick = { openUrl(URL_SUPPORT) },
                    )
                }
            }
        }
    }

    // ── Bottom sheets ──

    // Network picker
    if (showNetworkPicker) {
        ModalBottomSheet(
            onDismissRequest = { showNetworkPicker = false },
            containerColor = MidnightColors.VoidElevated,
        ) {
            Column(modifier = Modifier.padding(horizontal = Space16, vertical = Space16)) {
                Text(
                    text = "SELECT NETWORK",
                    color = MidnightColors.LightMuted,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.W400,
                    letterSpacing = 3.sp,
                )
                Spacer(modifier = Modifier.height(Space16))
                MidnightNetwork.entries.forEach { network ->
                    val isSelected = network == uiState.network
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = RowMinHeight)
                            .clickable {
                                val activity = context as? FragmentActivity
                                scope.launch {
                                    val success = viewModel.switchNetwork(network, activity)
                                    if (success) {
                                        showNetworkPicker = false
                                    } else {
                                        Toast.makeText(context, "Network switch cancelled", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                            .padding(horizontal = Space16, vertical = Space12),
                    ) {
                        Text(
                            text = network.name,
                            color = if (isSelected) MidnightColors.Light else MidnightColors.LightSoft,
                            fontSize = 14.sp,
                            fontWeight = if (isSelected) FontWeight.W400 else FontWeight.W300,
                            modifier = Modifier.weight(1f),
                        )
                        if (isSelected) {
                            Text(
                                text = "✓",
                                color = MidnightColors.Light,
                                fontSize = 16.sp,
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(Space32))
            }
        }
    }

    // Wipe confirmation
    if (showWipeConfirmation) {
        ModalBottomSheet(
            onDismissRequest = { showWipeConfirmation = false },
            containerColor = MidnightColors.VoidElevated,
        ) {
            Column(modifier = Modifier.padding(horizontal = Space16, vertical = Space16)) {
                Text(
                    text = "Wipe wallet?",
                    color = MidnightColors.Light,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.W300,
                )
                Spacer(modifier = Modifier.height(Space8))
                Text(
                    text = "This erases your seed, keys, and cached state from this device. You can only restore with your recovery phrase.",
                    color = MidnightColors.LightSoft,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.W300,
                    lineHeight = 20.sp,
                )
                Spacer(modifier = Modifier.height(Space16))
                Text(
                    text = "Type WIPE to confirm",
                    color = MidnightColors.LightMuted,
                    fontSize = 13.sp,
                )
                Spacer(modifier = Modifier.height(Space8))
                // Challenge input
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = RowMinHeight)
                        .background(MidnightColors.LightBarely, RoundedCornerShape(RadiusMd))
                        .padding(horizontal = Space16, vertical = Space12),
                ) {
                    if (wipeChallenge.isEmpty()) {
                        Text(
                            text = "WIPE",
                            color = MidnightColors.LightFaint,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.W300,
                        )
                    }
                    BasicTextField(
                        value = wipeChallenge,
                        onValueChange = { wipeChallenge = it },
                        textStyle = TextStyle(
                            color = MidnightColors.Light,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.W300,
                        ),
                        cursorBrush = SolidColor(MidnightColors.Light),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Spacer(modifier = Modifier.height(Space16))
                val canWipe = wipeChallenge.equals("WIPE", ignoreCase = false)
                SheetButtonRow(
                    onCancel = { showWipeConfirmation = false },
                    onConfirm = {
                        showWipeConfirmation = false
                        viewModel.onWipeWallet()
                        onWipeComplete()
                    },
                    confirmText = "Wipe wallet",
                    confirmEnabled = canWipe,
                )
                Spacer(modifier = Modifier.height(Space32))
            }
        }
    }

    // Force re-sync confirmation
    if (showResyncConfirmation) {
        ModalBottomSheet(
            onDismissRequest = { showResyncConfirmation = false },
            containerColor = MidnightColors.VoidElevated,
        ) {
            Column(modifier = Modifier.padding(horizontal = Space16, vertical = Space16)) {
                Text(
                    text = "Force re-sync?",
                    color = MidnightColors.Light,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.W300,
                )
                Spacer(modifier = Modifier.height(Space8))
                Text(
                    text = "This clears cached transactions and re-syncs from the indexer. Takes a few seconds on a warm stack.",
                    color = MidnightColors.LightSoft,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.W300,
                    lineHeight = 20.sp,
                )
                Spacer(modifier = Modifier.height(Space16))
                SheetButtonRow(
                    onCancel = { showResyncConfirmation = false },
                    onConfirm = {
                        showResyncConfirmation = false
                        viewModel.onForceResync()
                    },
                    confirmText = "Re-sync",
                )
                Spacer(modifier = Modifier.height(Space32))
            }
        }
    }

    // Proof server mode + URL editor
    if (showProofServerEditor) {
        var selectedMode by remember { mutableStateOf(uiState.provingMode) }
        var editUrl by remember { mutableStateOf(uiState.remoteProofServerUrl) }
        ModalBottomSheet(
            onDismissRequest = { showProofServerEditor = false },
            containerColor = MidnightColors.VoidElevated,
        ) {
            Column(modifier = Modifier.padding(horizontal = Space16, vertical = Space16)) {
                Text(
                    text = "Proof server",
                    color = MidnightColors.Light,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.W300,
                )
                Spacer(modifier = Modifier.height(Space16))

                // LOCAL option
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = RowMinHeight)
                        .clickable { selectedMode = ProvingMode.LOCAL }
                        .padding(horizontal = Space16, vertical = Space12),
                ) {
                    val isLocal = selectedMode == ProvingMode.LOCAL
                    Text(
                        text = "Local (on-device)",
                        color = if (isLocal) MidnightColors.Light else MidnightColors.LightSoft,
                        fontSize = 14.sp,
                        fontWeight = if (isLocal) FontWeight.W400 else FontWeight.W300,
                        modifier = Modifier.weight(1f),
                    )
                    if (isLocal) {
                        Text("✓", color = MidnightColors.Light, fontSize = 16.sp)
                    }
                }
                Text(
                    text = "Generate ZK proofs on your phone. No server needed.",
                    color = MidnightColors.LightMuted,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    modifier = Modifier.padding(horizontal = Space16),
                )

                Spacer(modifier = Modifier.height(Space8))
                HorizontalDivider(color = MidnightColors.LightFaint, thickness = 1.dp)
                Spacer(modifier = Modifier.height(Space8))

                // REMOTE option
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = RowMinHeight)
                        .clickable { selectedMode = ProvingMode.REMOTE }
                        .padding(horizontal = Space16, vertical = Space12),
                ) {
                    val isRemote = selectedMode == ProvingMode.REMOTE
                    Text(
                        text = "Remote server",
                        color = if (isRemote) MidnightColors.Light else MidnightColors.LightSoft,
                        fontSize = 14.sp,
                        fontWeight = if (isRemote) FontWeight.W400 else FontWeight.W300,
                        modifier = Modifier.weight(1f),
                    )
                    if (isRemote) {
                        Text("✓", color = MidnightColors.Light, fontSize = 16.sp)
                    }
                }
                Text(
                    text = "Send proofs to an external server. Faster but requires network.",
                    color = MidnightColors.LightMuted,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    modifier = Modifier.padding(horizontal = Space16),
                )

                // URL input (only when REMOTE selected)
                if (selectedMode == ProvingMode.REMOTE) {
                    Spacer(modifier = Modifier.height(Space16))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = RowMinHeight)
                            .background(MidnightColors.LightBarely, RoundedCornerShape(RadiusMd))
                            .padding(horizontal = Space16, vertical = Space12),
                    ) {
                        if (editUrl.isEmpty()) {
                            Text(
                                text = "http://localhost:6300",
                                color = MidnightColors.LightFaint,
                                fontSize = 14.sp,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                        BasicTextField(
                            value = editUrl,
                            onValueChange = { editUrl = it },
                            textStyle = TextStyle(
                                color = MidnightColors.Light,
                                fontSize = 14.sp,
                                fontFamily = FontFamily.Monospace,
                            ),
                            cursorBrush = SolidColor(MidnightColors.Light),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }

                Spacer(modifier = Modifier.height(Space16))
                SheetButtonRow(
                    onCancel = { showProofServerEditor = false },
                    onConfirm = {
                        viewModel.onProvingModeChanged(selectedMode, editUrl)
                        showProofServerEditor = false
                    },
                    confirmText = "Save",
                )
                Spacer(modifier = Modifier.height(Space32))
            }
        }
    }
}

// ── Private composables ──

@Composable
private fun SectionHeader(label: String) {
    Text(
        text = label,
        color = MidnightColors.LightMuted,
        fontSize = 11.sp,
        fontWeight = FontWeight.W400,
        letterSpacing = 3.sp,
    )
}

@Composable
private fun SettingsRowItem(
    label: String,
    value: String? = null,
    valueMono: Boolean = false,
    readOnly: Boolean = false,
    alwaysClickable: Boolean = false,
    leadingIcon: ImageVector? = null,
    trailingIcon: ImageVector? = null,
    contentDesc: String? = null,
    onClick: () -> Unit = {},
) {
    val isClickable = !readOnly || alwaysClickable || trailingIcon != null
    val showChevron = !readOnly && trailingIcon == null
    val resolvedTrailing = trailingIcon
        ?: if (showChevron) Icons.AutoMirrored.Filled.ArrowForward else null

    val labelColor = if (readOnly) MidnightColors.LightSoft else MidnightColors.Light
    val valueColor = if (readOnly) MidnightColors.Light else MidnightColors.LightSoft

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = RowMinHeight)
            .then(if (isClickable) Modifier.clickable(onClick = onClick) else Modifier)
            .then(
                if (contentDesc != null) Modifier.semantics { this.contentDescription = contentDesc }
                else Modifier,
            )
            .padding(horizontal = Space16, vertical = Space16),
    ) {
        if (leadingIcon != null) {
            Icon(
                imageVector = leadingIcon,
                contentDescription = null,
                tint = MidnightColors.Light,
                modifier = Modifier.size(Icon24),
            )
            Spacer(modifier = Modifier.width(Space12))
        }
        Text(
            text = label,
            color = labelColor,
            fontSize = 14.sp,
            fontWeight = FontWeight.W300,
            lineHeight = 20.sp,
            modifier = Modifier.weight(1f),
        )
        if (value != null) {
            Text(
                text = value,
                color = valueColor,
                fontSize = 14.sp,
                fontWeight = FontWeight.W300,
                lineHeight = 20.sp,
                fontFamily = if (valueMono) FontFamily.Monospace else FontFamily.Default,
            )
        }
        if (resolvedTrailing != null) {
            Spacer(modifier = Modifier.width(Space8))
            Icon(
                imageVector = resolvedTrailing,
                contentDescription = null,
                tint = MidnightColors.LightMuted,
                modifier = Modifier.size(Icon16),
            )
        }
    }
}

@Composable
private fun DangerRowItem(
    label: String,
    leadingIcon: ImageVector,
    onClick: () -> Unit,
) {
    SettingsRowItem(
        label = label,
        leadingIcon = leadingIcon,
        contentDesc = "Destructive action, $label",
        onClick = onClick,
    )
}

@Composable
private fun SheetButtonRow(
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
    confirmText: String,
    confirmEnabled: Boolean = true,
) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .weight(1f)
                .height(ButtonHeight)
                .background(MidnightColors.LightBarely, RoundedCornerShape(RadiusMd))
                .clickable(onClick = onCancel),
            contentAlignment = Alignment.Center,
        ) {
            Text("Cancel", color = MidnightColors.LightMuted, fontSize = 13.sp)
        }
        Spacer(modifier = Modifier.width(Space8))
        Box(
            modifier = Modifier
                .weight(1f)
                .height(ButtonHeight)
                .background(
                    if (confirmEnabled) MidnightColors.Light else MidnightColors.LightBarely,
                    RoundedCornerShape(RadiusMd),
                )
                .clickable(enabled = confirmEnabled, onClick = onConfirm),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = confirmText,
                color = if (confirmEnabled) MidnightColors.Void else MidnightColors.LightMuted,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

@Composable
private fun SettingsDividerItem() {
    HorizontalDivider(
        color = MidnightColors.LightFaint,
        thickness = 1.dp,
        modifier = Modifier.padding(horizontal = Space16),
    )
}
