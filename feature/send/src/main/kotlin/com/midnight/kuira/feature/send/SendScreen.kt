package com.midnight.kuira.feature.send

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.midnight.kuira.core.indexer.ui.BalanceFormatter
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
 * Simple MVP send screen for testing Phase 2F transaction flow.
 *
 * **Purpose:**
 * - Test build → sign → submit flow
 * - Verify transaction submission works end-to-end
 * - Validate fee calculation and dust payment
 *
 * **MVP Limitations:**
 * - Exposes seed phrase in UI (NOT production-ready)
 * - No dust tank display (deferred to Phase 2F.1)
 * - No biometric auth
 * - Basic error handling
 *
 * **Features:**
 * - Recipient address input + validation
 * - Amount input + balance checking
 * - Sender address and seed inputs (for MVP testing)
 * - Loading states (Building, Signing, Submitting)
 * - Success/Error display
 *
 * **Design:** Follows BalanceScreen pattern - simple Card-based sections
 */
@RequiresApi(Build.VERSION_CODES.O)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SendScreen(
    address: String,
    viewModel: SendViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    val formatter = remember { BalanceFormatter() }

    // Auto-load balance when screen mounts
    LaunchedEffect(address) {
        android.util.Log.d("SendScreen", "LaunchedEffect triggered with address: '$address'")
        if (address.isNotBlank()) {
            android.util.Log.d("SendScreen", "Calling viewModel.loadBalance('$address')")
            viewModel.loadBalance(address)
        } else {
            android.util.Log.e("SendScreen", "Address is blank! Not loading balance")
        }
    }

    // User inputs with test placeholders (MVP ONLY - for faster testing)
    // MVP test defaults: auto-filled based on selected network
    var recipientAddress by remember { mutableStateOf(viewModel.defaultTestRecipient) }
    var amountInput by remember { mutableStateOf("1") }
    var seedPhrase by remember { mutableStateOf(viewModel.defaultTestSeedPhrase) }

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
            // Sender Address Display (read-only)
            SenderAddressDisplay(address = address)

            // Balance Display
            if (state is SendUiState.Idle) {
                BalanceCard(
                    availableBalance = (state as SendUiState.Idle).availableBalance,
                    formatter = formatter
                )
            }

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

            // Seed Phrase Input (MVP ONLY - not production)
            SeedPhraseSection(
                seedPhrase = seedPhrase,
                onSeedPhraseChange = { seedPhrase = it }
            )

            // Send Button
            SendButtonSection(
                enabled = address.isNotBlank() &&
                        recipientAddress.isNotBlank() &&
                        amountInput.isNotBlank() &&
                        seedPhrase.isNotBlank() &&
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
                        fromAddress = address,
                        toAddress = recipientAddress,
                        amount = amountInStars,
                        seedPhrase = seedPhrase
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
                    onReset = { viewModel.reset(address) }
                )
                is SendUiState.Error -> ErrorCard(
                    message = currentState.message,
                    onRetry = { viewModel.reset(address) }
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
private fun SeedPhraseSection(
    seedPhrase: String,
    onSeedPhraseChange: (String) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text(
                text = "⚠️ Seed Phrase (MVP ONLY)",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.error
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "WARNING: Never expose seed in production!",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = seedPhrase,
                onValueChange = onSeedPhraseChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("24-word mnemonic") },
                minLines = 3
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

