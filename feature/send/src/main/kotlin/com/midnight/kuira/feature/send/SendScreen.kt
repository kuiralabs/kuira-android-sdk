package com.midnight.kuira.feature.send

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.FilterChip
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.hilt.navigation.compose.hiltViewModel
import com.midnight.kuira.core.ledger.ui.BalanceFormatter
import java.math.BigDecimal
import java.math.BigInteger

/**
 * Currency conversion: 1 NIGHT = 1,000,000 Stars (6 decimal places)
 */
private val STARS_PER_NIGHT_DECIMAL = BigDecimal.valueOf(1_000_000)

/**
 * Convert NIGHT to Stars (multiply by 1,000,000).
 *
 * @param night Amount in NIGHT (can have decimals)
 * @return Amount in Stars (whole number)
 */
private fun nightToStars(night: BigDecimal): BigInteger {
    return (night * STARS_PER_NIGHT_DECIMAL).toBigInteger()
}

/**
 * MVP send screen — mode-driven sending (shielded vs unshielded).
 *
 * **Flow (mode-driven, not auto-detected):**
 * 1. User picks a mode via the toggle (Shielded or Unshielded).
 * 2. The FROM field is populated from [SendViewModel.walletAddresses] based on
 *    the mode — unshielded mode shows the unshielded address, shielded mode
 *    shows the shielded address.
 * 3. The recipient default is populated with Bob's matching address for the mode.
 * 4. Send button calls [SendViewModel.send] with an explicit [SendMode] — the
 *    VM validates the recipient matches the mode and rejects mismatches.
 *
 * **Navigation parameter:**
 * - [initialMode]: optional default mode (e.g., BalanceScreen can pass
 *   [SendMode.SHIELDED] if the user tapped the shielded balance card).
 *   Defaults to [SendMode.UNSHIELDED].
 */
@RequiresApi(Build.VERSION_CODES.O)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SendScreen(
    initialMode: SendMode = SendMode.UNSHIELDED,
    viewModel: SendViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val formatter = remember { BalanceFormatter() }
    val context = LocalContext.current
    // We require a FragmentActivity host so BiometricPrompt can attach.
    // MainActivity already extends FragmentActivity (changed in Step 8A.1).
    val activity = context as? FragmentActivity
        ?: error("SendScreen must be hosted in a FragmentActivity")

    // Observe the wallet's cached addresses (populated by onboarding).
    val walletAddresses by viewModel.walletAddresses.collectAsState()

    // Authoritative send mode — drives the FROM field, the recipient default,
    // and which protocol path runs on Send tap.
    var sendMode by remember { mutableStateOf(initialMode) }
    val isLocalProving by viewModel.isLocalProvingEnabled.collectAsState()

    // FROM address: derived from the wallet cache + current mode. Empty string
    // while the cache is loading so the UI shows a placeholder instead of a
    // stale / wrong address.
    val fromAddress: String = when (sendMode) {
        SendMode.UNSHIELDED -> walletAddresses?.unshieldedAddress.orEmpty()
        SendMode.SHIELDED -> walletAddresses?.shieldedAddress.orEmpty()
    }

    // Auto-load balance whenever the FROM address settles to a non-blank value.
    // Balance is queried by unshielded address (balance repo only handles
    // unshielded UTXOs today), so for shielded mode we still pass the cached
    // unshielded address so we can show the total NIGHT balance as context.
    val balanceQueryAddress = walletAddresses?.unshieldedAddress.orEmpty()
    LaunchedEffect(balanceQueryAddress) {
        if (balanceQueryAddress.isNotBlank()) {
            android.util.Log.d("SendScreen", "loadBalance('$balanceQueryAddress')")
            viewModel.loadBalance(balanceQueryAddress)
        }
    }

    // Recipient input — resets to Bob's matching address whenever the mode
    // toggle changes. User can then override with another address.
    var recipientAddress by remember(sendMode) {
        mutableStateOf(
            when (sendMode) {
                SendMode.SHIELDED -> viewModel.defaultShieldedRecipient
                SendMode.UNSHIELDED -> viewModel.defaultTestRecipient
            }
        )
    }
    var amountInput by remember { mutableStateOf("1") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Send NIGHT") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Sender Address Display (read-only) — updates with mode toggle
            SenderAddressDisplay(address = fromAddress)

            // Balance Display
            if (state is SendUiState.Idle) {
                BalanceCard(
                    availableBalance = (state as SendUiState.Idle).availableBalance,
                    formatter = formatter
                )
            }

            // Send Mode Selector (Shielded vs Unshielded) — authoritative.
            // Toggling updates the FROM field (above), the recipient default,
            // and which protocol path runs when Send is tapped.
            SendModeSelector(
                isShielded = (sendMode == SendMode.SHIELDED),
                onModeChange = { shielded ->
                    sendMode = if (shielded) SendMode.SHIELDED else SendMode.UNSHIELDED
                },
            )

            // Proving Mode Selector
            ProvingModeSelector(
                isLocal = isLocalProving,
                proofServerUrl = viewModel.proofServerUrl,
                onToggle = { viewModel.toggleProvingMode() },
            )

            // Recipient Address Input
            RecipientAddressSection(
                address = recipientAddress,
                onAddressChange = { recipientAddress = it }
            )

            // Amount Input
            AmountSection(
                amount = amountInput,
                onAmountChange = { amountInput = it }
            )

            // Send Button
            //
            // The seed is no longer entered in the UI — SendViewModel.send() will
            // load it from SeedVault via biometric prompt when the user taps Send.
            SendButtonSection(
                enabled = fromAddress.isNotBlank() &&
                        recipientAddress.isNotBlank() &&
                        amountInput.isNotBlank() &&
                        state is SendUiState.Idle,
                onClick = {
                    // Convert NIGHT input to Stars for transaction
                    val amountInStars = try {
                        val nightAmount = BigDecimal(amountInput)
                        nightToStars(nightAmount)
                    } catch (e: NumberFormatException) {
                        BigInteger.ZERO
                    }

                    viewModel.send(
                        activity = activity,
                        mode = sendMode,
                        toAddress = recipientAddress,
                        amount = amountInStars,
                    )
                }
            )

            // State Display
            when (val currentState = state) {
                is SendUiState.Building -> LoadingCard("Building transaction...")
                is SendUiState.Signing -> LoadingCard("Signing transaction...")
                is SendUiState.Proving -> LoadingCard("Generating ZK proof (this may take a few minutes)...")
                is SendUiState.Submitting -> LoadingCard("Submitting to blockchain...")
                is SendUiState.SyncingAndRetrying -> SyncingAndRetryingCard(
                    retryAttempt = currentState.retryAttempt,
                    maxRetries = currentState.maxRetries
                )
                is SendUiState.Success -> SuccessCard(
                    txHash = currentState.txHash,
                    amount = currentState.amountSent,
                    recipient = currentState.recipientAddress,
                    formatter = formatter,
                    onReset = { viewModel.reset(balanceQueryAddress) }
                )
                is SendUiState.Error -> ErrorCard(
                    message = currentState.message,
                    onRetry = { viewModel.reset(balanceQueryAddress) }
                )
                is SendUiState.Idle -> {} // No extra display needed
            }
        }
    }
}

@Composable
private fun SenderAddressDisplay(
    address: String
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text(
                text = "From (Your Address)",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = if (address.length > 30) "${address.take(30)}..." else address,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            )
        }
    }
}

@Composable
private fun BalanceCard(
    availableBalance: BigInteger,
    formatter: BalanceFormatter
) {
    // Format balance using existing formatter (handles Stars → NIGHT conversion)
    val balanceFormatted = formatter.formatCompact(availableBalance, "NIGHT")

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text(
                text = "Available Balance",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = balanceFormatted,
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
private fun SendModeSelector(
    isShielded: Boolean,
    onModeChange: (Boolean) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Send Mode",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = isShielded,
                    onClick = { onModeChange(true) },
                    label = { Text("Shielded (Private)") },
                    modifier = Modifier.weight(1f),
                )
                FilterChip(
                    selected = !isShielded,
                    onClick = { onModeChange(false) },
                    label = { Text("Unshielded (Public)") },
                    modifier = Modifier.weight(1f),
                )
            }
            Text(
                text = if (isShielded) "ZK proof — recipient only sees encrypted coin"
                       else "Transparent — visible on chain",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun ProvingModeSelector(
    isLocal: Boolean,
    proofServerUrl: String,
    onToggle: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggle() },
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f)
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(
                    text = "ZK Prover",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
                Text(
                    text = if (isLocal) "On-device (no network needed)"
                           else proofServerUrl,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.7f),
                )
            }
            Text(
                text = if (isLocal) "Local" else "Remote",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
        }
    }
}

@Composable
private fun RecipientAddressSection(
    address: String,
    onAddressChange: (String) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text(
                text = "To (Recipient Address)",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = address,
                onValueChange = onAddressChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("mn_addr_...") },
                singleLine = true
            )
        }
    }
}

@Composable
private fun AmountSection(
    amount: String,
    onAmountChange: (String) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text(
                text = "Amount (NIGHT)",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = amount,
                onValueChange = { newValue ->
                    // Allow digits and one decimal point
                    val isValid = newValue.isEmpty() ||
                                  newValue.matches(Regex("^\\d*\\.?\\d*$"))
                    if (isValid) {
                        onAmountChange(newValue)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Amount in NIGHT") },
                placeholder = { Text("1.5") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
            )
        }
    }
}

@Composable
private fun SendButtonSection(
    enabled: Boolean,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        enabled = enabled
    ) {
        Text("Send Transaction")
    }
}

@Composable
private fun LoadingCard(message: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            CircularProgressIndicator(modifier = Modifier.size(48.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

/**
 * Card shown during auto-recovery (sync + retry) after error 115.
 *
 * Shows clear feedback that wallet is syncing and will retry automatically.
 */
@Composable
private fun SyncingAndRetryingCard(
    retryAttempt: Int,
    maxRetries: Int
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            CircularProgressIndicator(modifier = Modifier.size(48.dp))
            Text(
                text = "Syncing wallet...",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = "Will retry automatically",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.8f)
            )
            Text(
                text = "Attempt $retryAttempt of $maxRetries",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.6f)
            )
        }
    }
}

@Composable
private fun SuccessCard(
    txHash: String,
    amount: BigInteger,
    recipient: String,
    formatter: BalanceFormatter,
    onReset: () -> Unit
) {
    val amountFormatted = formatter.formatCompact(amount, "NIGHT")

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text(
                text = "✅ Transaction Successful!",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Amount: $amountFormatted",
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "To: ${recipient.take(30)}...",
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "TX Hash: ${txHash.take(16)}...",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
            )
            Spacer(modifier = Modifier.height(16.dp))
            TextButton(
                onClick = onReset,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Send Another Transaction")
            }
        }
    }
}

@Composable
private fun ErrorCard(
    message: String,
    onRetry: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text(
                text = "❌ Transaction Failed",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.error
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            Spacer(modifier = Modifier.height(16.dp))
            TextButton(
                onClick = onRetry,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Try Again")
            }
        }
    }
}

