package com.midnight.kuira.feature.dust

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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.hilt.navigation.compose.hiltViewModel
import com.midnight.kuira.core.indexer.ui.BalanceFormatter
import java.math.BigInteger

/**
 * Dust tank management screen.
 *
 * **Features:**
 * - Check dust registration status for an address
 * - Display dust balance, token count, and generation progress
 * - Register for dust generation (build → prove → seal → submit)
 *
 * **Security:** The seed is loaded on demand by [DustViewModel] via a
 * biometric prompt — this screen never holds or displays mnemonic
 * material. The [address] parameter is supplied by navigation from the
 * Balance screen (read from [WalletAddressCache]).
 *
 * **Design:** Follows BalanceScreen/SendScreen pattern — Card-based sections
 */
@RequiresApi(Build.VERSION_CODES.O)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DustScreen(
    address: String,
    viewModel: DustViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    val formatter = remember { BalanceFormatter() }

    val context = LocalContext.current
    // BiometricPrompt requires a FragmentActivity host. MainActivity already
    // extends FragmentActivity (changed in Step 8A.1).
    val activity = context as? FragmentActivity
        ?: error("DustScreen must be hosted in a FragmentActivity")

    // Address comes from the nav arg (BalanceScreen → onNavigateToDust).
    // We keep it editable so advanced users can paste a different address
    // for inspection, but there is no test default to fall back to.
    var addressInput by remember { mutableStateOf(address) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Dust Tank") },
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
            // Dust status/action cards at the TOP for visibility
            when (val currentState = state) {
                is DustUiState.Idle -> { /* No status card — waiting for user to check */ }
                is DustUiState.Loading -> LoadingCard()

                is DustUiState.Status -> DustStatusCard(
                    balance = currentState.balance,
                    nightBalance = currentState.nightBalance,
                    timeToCapacityMs = currentState.timeToCapacityMs,
                    isAtCapacity = currentState.isAtCapacity,
                    formatter = formatter
                )

                is DustUiState.NoDust -> NoDustCard(
                    onRegister = {
                        viewModel.registerDust(activity, addressInput)
                    },
                    enabled = addressInput.isNotBlank()
                )

                is DustUiState.Registering -> RegisteringCard(
                    step = currentState.step
                )

                is DustUiState.RegistrationSuccess -> SuccessCard(
                    txHash = currentState.txHash,
                    onReset = { viewModel.reset(activity, addressInput) }
                )

                is DustUiState.Error -> ErrorCard(
                    message = currentState.message,
                    onRetry = { viewModel.checkDustStatus(activity, addressInput) }
                )
            }

            // Address Input
            AddressInputCard(
                address = addressInput,
                onAddressChange = { addressInput = it }
            )

            // Check Status Button (only when not already loading/registering)
            if (state !is DustUiState.Registering && state !is DustUiState.Loading) {
                Button(
                    onClick = { viewModel.checkDustStatus(activity, addressInput) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = addressInput.isNotBlank()
                ) {
                    Text("Check Dust Status")
                }
            }
        }
    }
}

@Composable
private fun AddressInputCard(
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
                text = "Wallet Address",
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
private fun LoadingCard() {
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
                text = "Checking dust status...",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
private fun DustStatusCard(
    balance: BigInteger,
    nightBalance: BigInteger,
    timeToCapacityMs: Long,
    isAtCapacity: Boolean,
    formatter: BalanceFormatter
) {
    val balanceFormatted = formatter.formatCompact(balance, "DUST")
    val nightFormatted = formatter.formatCompact(nightBalance, "NIGHT")

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
                text = "Dust Tank",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Balance
            Text(
                text = "Balance",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
            )
            Text(
                text = balanceFormatted,
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.height(12.dp))

            // NIGHT balance backing dust generation
            Text(
                text = "Generating from $nightFormatted",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Generation progress
            if (isAtCapacity) {
                Text(
                    text = "Generation: At capacity",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium
                )
                LinearProgressIndicator(
                    progress = { 1f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp)
                )
            } else if (timeToCapacityMs > 0) {
                val hoursRemaining = timeToCapacityMs / (1000 * 60 * 60)
                val minutesRemaining = (timeToCapacityMs / (1000 * 60)) % 60
                val timeText = if (hoursRemaining > 0) {
                    "${hoursRemaining}h ${minutesRemaining}m to full"
                } else {
                    "${minutesRemaining}m to full"
                }
                Text(
                    text = "Generation: $timeText",
                    style = MaterialTheme.typography.bodyMedium
                )
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp)
                )
            }
        }
    }
}

@Composable
private fun NoDustCard(
    onRegister: () -> Unit,
    enabled: Boolean
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
                .padding(16.dp)
        ) {
            Text(
                text = "No Dust Registered",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onTertiaryContainer
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Register for dust generation to pay transaction fees. " +
                    "This creates a registration transaction on the blockchain.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.8f)
            )

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = onRegister,
                modifier = Modifier.fillMaxWidth(),
                enabled = enabled
            ) {
                Text("Register Dust")
            }
        }
    }
}

@Composable
private fun RegisteringCard(step: RegistrationStep) {
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
                text = "Registering Dust",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = step.displayText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f)
            )

            // Step progress indicator
            val stepIndex = RegistrationStep.entries.indexOf(step)
            val totalSteps = RegistrationStep.entries.size
            LinearProgressIndicator(
                progress = { (stepIndex + 1).toFloat() / totalSteps },
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                text = "Step ${stepIndex + 1} of $totalSteps",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.6f)
            )
        }
    }
}

@Composable
private fun SuccessCard(
    txHash: String,
    onReset: () -> Unit
) {
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
                text = "Registration Successful!",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(16.dp))
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
                Text("Check Dust Status")
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
                text = "Error",
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
