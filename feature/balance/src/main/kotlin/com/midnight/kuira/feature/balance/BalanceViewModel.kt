package com.midnight.kuira.feature.balance

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.util.Log
import com.midnight.kuira.core.crypto.address.Bech32m
import com.midnight.kuira.core.crypto.bip32.HDWallet
import com.midnight.kuira.core.crypto.bip32.MidnightKeyRole
import com.midnight.kuira.core.crypto.bip39.BIP39
import com.midnight.kuira.core.crypto.shielded.ShieldedKeyDeriver
import com.midnight.kuira.core.indexer.di.SubscriptionManagerFactory
import com.midnight.kuira.core.indexer.model.TokenBalance
import com.midnight.kuira.core.indexer.repository.BalanceRepository
import com.midnight.kuira.core.indexer.repository.ShieldedRepository
import com.midnight.kuira.core.indexer.sync.SyncState
import com.midnight.kuira.core.indexer.ui.BalanceFormatter
import com.midnight.kuira.core.network.MidnightNetwork
import com.midnight.kuira.core.network.NetworkConfig
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.math.BigInteger
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject

/**
 * ViewModel for balance viewing screens.
 *
 * **Responsibilities:**
 * - Observe balances from BalanceRepository
 * - Transform domain models to UI models
 * - Handle loading/error states
 * - Track last updated timestamp
 * - Support pull-to-refresh
 *
 * **Architecture:**
 * Uses a single Flow collection with a refresh trigger to avoid memory leaks
 * and duplicate subscriptions. The trigger mechanism allows refresh without
 * creating new collections.
 *
 * **Example Usage (Compose):**
 * ```kotlin
 * @Composable
 * fun BalanceScreen(viewModel: BalanceViewModel = hiltViewModel()) {
 *     val state by viewModel.balanceState.collectAsState()
 *
 *     when (state) {
 *         is BalanceUiState.Loading -> LoadingIndicator()
 *         is BalanceUiState.Success -> BalanceList(state.balances)
 *         is BalanceUiState.Error -> ErrorMessage(state.message)
 *     }
 * }
 * ```
 */
@HiltViewModel
class BalanceViewModel @RequiresApi(Build.VERSION_CODES.O)
@Inject constructor(
    private val repository: BalanceRepository,
    private val shieldedRepository: ShieldedRepository,
    private val subscriptionManagerFactory: SubscriptionManagerFactory,
    private val formatter: BalanceFormatter,
    private val networkConfig: NetworkConfig,
    private val clock: Clock = Clock.systemDefaultZone()
) : ViewModel() {

    val defaultTestAddress: String = DEFAULT_TEST_ADDRESSES[networkConfig.network.addressPrefix]
        ?: ""

    val defaultTestSeedPhrase: String = DEFAULT_TEST_SEED_PHRASES[networkConfig.network] ?: ""

    private val _balanceState = MutableStateFlow<BalanceUiState>(BalanceUiState.Loading())
    val balanceState: StateFlow<BalanceUiState> = _balanceState.asStateFlow()

    // Sync state (separate from balance state)
    private val _syncState = MutableStateFlow<SyncState?>(null)
    val syncState: StateFlow<SyncState?> = _syncState.asStateFlow()

    // Trigger for refreshing without creating new subscriptions
    private val refreshTrigger = MutableSharedFlow<String>(replay = 1)

    // Job tracking for proper cancellation
    private var collectionJob: Job? = null
    private var syncJob: Job? = null

    // Cached shielded data (preserved across unshielded Flow emissions)
    private var cachedShieldedBalances: Map<String, BigInteger>? = null
    private var cachedShieldedAddress: String? = null
    private var shieldedSyncJob: Job? = null
    private var shieldedSyncSeed: ByteArray? = null
    private var shieldedSyncAddress: String? = null

    // Track when user last triggered a load/refresh (NOT when database emitted)
    // This timestamp is set ONLY on explicit user actions (loadBalances/refresh)
    //
    // Important: This stays constant across automatic database emissions.
    // formatLastUpdated() compares this stored timestamp to Instant.now() on every
    // map emission, so "2 minutes ago" becomes "3 minutes ago" as time passes.
    private var lastLoadTimestamp: Instant? = null

    /**
     * Load balances for a specific address.
     *
     * Cancels any previous collection and starts observing balance changes.
     * Uses a single collection pattern with refresh trigger to prevent memory leaks.
     *
     * @param address The unshielded address to track (must be valid Midnight address)
     * @throws IllegalArgumentException if address is invalid
     */
    /**
     * Load unshielded balances + shielded balance together.
     * Shielded runs after unshielded starts (non-blocking).
     */
    fun loadBalancesWithShielded(address: String, seedPhrase: String) {
        loadBalances(address)
        if (seedPhrase.isNotBlank()) {
            viewModelScope.launch {
                // Wait for unshielded to reach Success before starting shielded sync
                // (they share the same WebSocket client — can't run concurrently)
                var waitMs = 0L
                while (_balanceState.value !is BalanceUiState.Success) {
                    kotlinx.coroutines.delay(500)
                    waitMs += 500
                    if (waitMs > 30_000) {
                        Log.w(TAG, "Timed out waiting for unshielded Success after ${waitMs}ms")
                        return@launch
                    }
                }
                Log.d(TAG, "Unshielded reached Success after ${waitMs}ms, starting shielded sync")
                loadShieldedBalance(address, seedPhrase)
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    @OptIn(ExperimentalCoroutinesApi::class)
    fun loadBalances(address: String) {
        // Validate address format - show error instead of crashing
        if (address.isBlank()) {
            _balanceState.value = BalanceUiState.Error("Address cannot be blank")
            return
        }
        if (!address.startsWith("mn_")) {
            _balanceState.value = BalanceUiState.Error("Invalid Midnight address format. Must start with 'mn_'")
            return
        }

        // Cancel previous collection to prevent memory leaks
        collectionJob?.cancel()

        _balanceState.value = BalanceUiState.Loading(isRefreshing = false)

        // Start syncing from blockchain (updates database, which repository observes)
        startSync(address)

        // Start new collection with refresh trigger
        collectionJob = viewModelScope.launch {
            // Emit initial address to trigger
            refreshTrigger.emit(address)

            // Single collection that responds to refresh triggers
            refreshTrigger
                .flatMapLatest { addr ->
                    // Capture timestamp when user triggers load/refresh
                    // This timestamp stays the same for all subsequent database emissions
                    // until next explicit user action
                    lastLoadTimestamp = clock.instant()

                    repository.observeBalances(addr)
                        .map<List<TokenBalance>, BalanceUiState> { balances ->
                            // Transform to UI models
                            val displayBalances = balances.map { it.toDisplay(formatter) }

                            // Calculate total balance safely (no overflow with BigInteger)
                            val totalBalance = balances.fold(BigInteger.ZERO) { acc, balance ->
                                acc.add(balance.balance)
                            }

                            // Format last updated timestamp
                            // Uses stored timestamp from when user triggered load/refresh
                            // Compares to Instant.now() on each emission, so string updates
                            // as time passes (e.g., "2 min ago" → "3 min ago")
                            val lastUpdatedString = formatLastUpdated(lastLoadTimestamp!!)

                            BalanceUiState.Success(
                                balances = displayBalances,
                                lastUpdated = lastUpdatedString,
                                totalBalance = totalBalance,
                                shieldedBalances = cachedShieldedBalances,
                                shieldedAddress = cachedShieldedAddress
                            )
                        }
                        .catch { throwable ->
                            emit(
                                BalanceUiState.Error(
                                    message = getUserFriendlyError(throwable),
                                    throwable = throwable
                                )
                            )
                        }
                }
                .collect { uiState ->
                    _balanceState.value = uiState
                }
        }
    }

    /**
     * Start blockchain sync for an address.
     *
     * Creates a SubscriptionManager and starts subscription to sync UTXOs from indexer.
     * The subscription updates the database, which BalanceRepository observes.
     *
     * Sync states are emitted to syncState flow for UI to show progress.
     */
    private fun startSync(address: String) {
        // Cancel previous sync and wait for it to complete
        // CRITICAL: Must wait for full cancellation to prevent race conditions
        viewModelScope.launch {
            // Wait for previous job to finish cancelling
            syncJob?.cancelAndJoin()

            // Reset WebSocket connection to clean up old subscriptions
            // This prevents subscription buildup when switching addresses
            try {
                repository.resetConnection()
            } catch (e: Exception) {
                // Ignore errors - connection might already be closed
            }

            // Now start new sync job
            syncJob = launch {
                // Create subscription manager for this address
                val subscriptionManager = subscriptionManagerFactory.create()

                // Start subscription and collect sync states
                subscriptionManager.startSubscription(address)
                    .catch { error ->
                        // Emit error state
                        _syncState.value = SyncState.Error(
                            error.message ?: "Failed to sync"
                        )
                    }
                    .collect { state ->
                        _syncState.value = state
                    }
            }
        }
        // Note: Subscription cleanup is automatic when Job is cancelled
    }

    /**
     * Refresh balances (pull-to-refresh).
     *
     * Triggers a refresh without creating a new Flow collection.
     * Sets isRefreshing = true while keeping current data visible.
     * Also restarts blockchain sync.
     *
     * @param address The address to refresh (should match the currently loaded address)
     */
    fun refresh(address: String) {
        viewModelScope.launch {
            // Show refreshing indicator while keeping current data
            val currentState = _balanceState.value
            if (currentState is BalanceUiState.Success) {
                _balanceState.value = BalanceUiState.Loading(isRefreshing = true)
            }

            // Restart sync
            startSync(address)

            // Trigger refresh via shared flow (reuses existing collection)
            refreshTrigger.emit(address)
        }
    }

    /**
     * Load shielded balance by syncing zswap events.
     *
     * Derives the zswap key from the seed phrase, syncs events, and updates
     * the current Success state with shielded balances.
     */
    fun loadShieldedBalance(address: String, seedPhrase: String) {
        if (seedPhrase.isBlank()) return

        viewModelScope.launch {
            var seed: ByteArray? = null
            var hdWallet: HDWallet? = null

            try {
                seed = BIP39.mnemonicToSeed(seedPhrase)
                hdWallet = HDWallet.fromSeed(seed)

                // Derive zswap seed at m/44'/2400'/0'/3/0
                val zswapKey = hdWallet.selectAccount(0)
                    .selectRole(MidnightKeyRole.ZSWAP)
                    .deriveKeyAt(0)
                val zswapSeed = zswapKey.privateKeyBytes.copyOf()
                zswapKey.clear()

                // Derive shielded address from keys
                val shieldedKeys = ShieldedKeyDeriver.deriveKeys(zswapSeed)
                val shieldedAddress = shieldedKeys?.let {
                    val coinPkBytes = hexToBytes(it.coinPublicKey)
                    val encPkBytes = hexToBytes(it.encryptionPublicKey)
                    val prefix = "mn_shield-addr" + when (networkConfig.network) {
                        MidnightNetwork.PREPROD -> "_preprod"
                        MidnightNetwork.PREVIEW -> "_preview"
                        MidnightNetwork.UNDEPLOYED -> "_undeployed"
                        else -> ""
                    }
                    Bech32m.encode(prefix, coinPkBytes + encPkBytes)
                }

                // Sync zswap events
                val hasCoins = shieldedRepository.syncFromBlockchain(address, zswapSeed)

                // Get balances
                val balances = if (hasCoins) {
                    shieldedRepository.getBalances(address)
                } else {
                    emptyMap()
                }

                // Cache shielded data for future unshielded Flow emissions
                cachedShieldedBalances = balances
                cachedShieldedAddress = shieldedAddress

                Log.d(TAG, "Shielded balance cached: ${balances.size} token types, ${balances.values.sumOf { it }} total")

                // Update UI state immediately
                val current = _balanceState.value
                Log.d(TAG, "Current state type: ${current::class.simpleName}, updating with shielded")
                if (current is BalanceUiState.Success) {
                    _balanceState.value = current.copy(
                        shieldedBalances = balances,
                        shieldedAddress = shieldedAddress
                    )
                    Log.d(TAG, "State updated with shielded balances")
                } else {
                    Log.w(TAG, "State is NOT Success (${current::class.simpleName}), cannot update shielded")
                }

                // Store seed for periodic polling (will be wiped on ViewModel clear)
                shieldedSyncSeed?.let { java.util.Arrays.fill(it, 0.toByte()) }
                shieldedSyncSeed = zswapSeed.copyOf()
                shieldedSyncAddress = address
                Log.d(TAG, "Stored sync seed[0]=${shieldedSyncSeed!![0]}, seed[31]=${shieldedSyncSeed!![31]}")

                // Wipe local copy AFTER storing
                java.util.Arrays.fill(zswapSeed, 0.toByte())
                Log.d(TAG, "After wipe: field seed[0]=${shieldedSyncSeed!![0]}, seed[31]=${shieldedSyncSeed!![31]}")

                startShieldedSync()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load shielded balance", e)
            } finally {
                seed?.let { java.util.Arrays.fill(it, 0.toByte()) }
                hdWallet?.clear()
            }
        }
    }

    /**
     * Start persistent shielded subscription with incremental event replay.
     * Periodic shielded balance poll.
     * Uses seed stored in shieldedSyncSeed field (set by loadShieldedBalance).
     */
    private fun startShieldedSync() {
        shieldedSyncJob?.cancel()
        shieldedSyncJob = viewModelScope.launch {
            try {
                while (true) {
                    kotlinx.coroutines.delay(10_000)

                    val seed = shieldedSyncSeed ?: return@launch
                    val addr = shieldedSyncAddress ?: return@launch

                    val hasCoins = shieldedRepository.syncFromBlockchain(addr, seed)
                    val balances = if (hasCoins) shieldedRepository.getBalances(addr) else emptyMap()

                    if (balances != cachedShieldedBalances) {
                        cachedShieldedBalances = balances
                        val current = _balanceState.value
                        if (current is BalanceUiState.Success) {
                            _balanceState.value = current.copy(shieldedBalances = balances)
                            Log.d(TAG, "Shielded balance updated: ${balances.values.sumOf { it }}")
                        }
                    }
                }
            } catch (e: Exception) {
                if (e !is kotlinx.coroutines.CancellationException) {
                    Log.e(TAG, "Shielded poll error", e)
                }
            }
        }
    }

    private fun hexToBytes(hex: String): ByteArray {
        return hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }

    /**
     * Format last updated timestamp for display.
     *
     * Compares the stored timestamp (when user triggered load/refresh)
     * to current time to calculate duration.
     *
     * Called on every Flow emission, so the string updates as time passes
     * even if the stored timestamp stays constant.
     *
     * Examples:
     * - "Just now" (< 1 minute)
     * - "2 minutes ago"
     * - "1 hour ago"
     * - "Yesterday at 3:45 PM"
     * - "Jan 15 at 3:45 PM"
     *
     * @param timestamp When user last triggered load/refresh (NOT when database emitted)
     * @return Human-readable "time ago" string
     */
    @RequiresApi(Build.VERSION_CODES.O)
    private fun formatLastUpdated(timestamp: Instant): String {
        val now = clock.instant()  // Use injected clock (testable)
        val duration = Duration.between(timestamp, now)

        return when {
            duration.toMinutes() < ONE_MINUTE_THRESHOLD -> "Just now"
            duration.toMinutes() < MINUTES_IN_HOUR -> "${duration.toMinutes()} minutes ago"
            duration.toHours() < HOURS_IN_DAY -> {
                if (duration.toHours() == 1L) "1 hour ago" else "${duration.toHours()} hours ago"
            }
            duration.toDays() == 1L -> {
                val formatter = DateTimeFormatter.ofPattern(TIME_PATTERN)
                    .withZone(ZoneId.systemDefault())
                "Yesterday at ${formatter.format(timestamp)}"
            }
            else -> {
                val formatter = DateTimeFormatter.ofPattern(DATE_TIME_PATTERN)
                    .withZone(ZoneId.systemDefault())
                formatter.format(timestamp)
            }
        }
    }

    /**
     * Convert exceptions to user-friendly error messages.
     */
    private fun getUserFriendlyError(throwable: Throwable): String {
        return when {
            throwable.message?.contains("network", ignoreCase = true) == true ->
                "Network error. Please check your connection."

            throwable.message?.contains("timeout", ignoreCase = true) == true ->
                "Request timed out. Please try again."

            throwable.message?.contains("database", ignoreCase = true) == true ->
                "Database error. Please restart the app."

            throwable is IllegalArgumentException ->
                "Invalid input: ${throwable.message}"

            else ->
                "Failed to load balances: ${throwable.message}"
        }
    }

    override fun onCleared() {
        super.onCleared()
        collectionJob?.cancel()
        syncJob?.cancel()
        shieldedSyncJob?.cancel()
        shieldedSyncSeed?.let { java.util.Arrays.fill(it, 0.toByte()) }
        shieldedSyncSeed = null
    }

    private companion object {
        const val TAG = "BalanceViewModel"

        const val ONE_MINUTE_THRESHOLD = 1L
        const val MINUTES_IN_HOUR = 60L
        const val HOURS_IN_DAY = 24L

        const val TIME_PATTERN = "h:mm a"
        const val DATE_TIME_PATTERN = "MMM d 'at' h:mm a"

        // MVP test seed phrases — Alice (from CLI: mn wallet info alice)
        private val DEFAULT_TEST_SEED_PHRASES = mapOf(
            MidnightNetwork.PREPROD to "shoot swallow grunt cement glory exclude forward boring stool skirt portion swallow slow light town ripple obvious carry unfair beauty world small add own",
            MidnightNetwork.UNDEPLOYED to "shoot swallow grunt cement glory exclude forward boring stool skirt portion swallow slow light town ripple obvious carry unfair beauty world small add own"
        )

        // MVP test addresses per network — Alice (from CLI: mn wallet info alice)
        val DEFAULT_TEST_ADDRESSES = mapOf(
            "mn_addr_preprod" to "mn_addr_preprod18mj9eclnzussedhnvj99hdqug7n0kwsutj8dz5ez7edtwx4a60ds232yy8",
            "mn_addr_undeployed" to "mn_addr_undeployed18mj9eclnzussedhnvj99hdqug7n0kwsutj8dz5ez7edtwx4a60dss2s64k",
            "mn_addr_preview" to "mn_addr_preview18mj9eclnzussedhnvj99hdqug7n0kwsutj8dz5ez7edtwx4a60ds2s55h6"
        )
    }
}
