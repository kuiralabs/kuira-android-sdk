package com.midnight.kuira.feature.balance

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.util.Log
import com.midnight.kuira.core.auth.PlaintextSeed
import com.midnight.kuira.core.auth.SeedVault
import com.midnight.kuira.core.auth.WalletAddressCache
import com.midnight.kuira.core.auth.WalletAddresses
import com.midnight.kuira.core.crypto.bip32.HDWallet
import com.midnight.kuira.core.crypto.bip32.MidnightKeyRole
import com.midnight.kuira.core.indexer.di.SubscriptionManagerFactory
import com.midnight.kuira.core.indexer.model.TokenBalance
import com.midnight.kuira.core.indexer.repository.BalanceRepository
import com.midnight.kuira.core.indexer.repository.ShieldedRepository
import com.midnight.kuira.core.indexer.sync.SyncState
import com.midnight.kuira.core.ledger.ui.BalanceFormatter
import com.midnight.kuira.core.network.NetworkClientFactory
import com.midnight.kuira.core.network.NetworkRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
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
    private val networkRepository: NetworkRepository,
    private val networkClientFactory: NetworkClientFactory,
    private val walletAddressCache: WalletAddressCache,
    private val seedVault: SeedVault,
    private val clock: Clock = Clock.systemDefaultZone()
) : ViewModel() {

    /**
     * Loading state for the wallet address cache. Distinguishes:
     * - [AddressCacheState.Loading]: initial, cache read in progress
     * - [AddressCacheState.Found]: cache contained valid addresses
     * - [AddressCacheState.Empty]: cache file missing or corrupted — wallet
     *   needs to be re-created / re-cached (state reset scenario)
     *
     * UI layer observes this to decide whether to show the balance flow,
     * an empty state, or a loading indicator.
     */
    sealed interface AddressCacheState {
        data object Loading : AddressCacheState
        data class Found(val addresses: WalletAddresses) : AddressCacheState
        data object Empty : AddressCacheState
    }

    private val _walletAddresses = MutableStateFlow<AddressCacheState>(AddressCacheState.Loading)
    val walletAddresses: StateFlow<AddressCacheState> = _walletAddresses.asStateFlow()

    init {
        // Observe selected network reactively. When user switches
        // network in Settings, this emits the new network → we reload
        // the cached address → restart the subscription. No app restart.
        viewModelScope.launch {
            networkRepository.selectedNetworkFlow.collect { network ->
                Log.d(TAG, "Network changed to ${network.name}, reloading address cache")
                val loaded = walletAddressCache.load(network)
                Log.d(TAG, "WalletAddressCache.load(${network.name}) returned: " +
                    "unshielded=${loaded?.unshieldedAddress}, " +
                    "shielded=${loaded?.shieldedAddress?.take(20)}...")
                _walletAddresses.value = if (loaded != null) {
                    loadBalances(loaded.unshieldedAddress)
                    AddressCacheState.Found(loaded)
                } else {
                    Log.w(TAG, "No cached wallet address for ${network.name}")
                    AddressCacheState.Empty
                }
            }
        }
    }

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
    private var optimisticTimeoutJob: Job? = null

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
     * Load unshielded balances for the wallet's cached unshielded address.
     *
     * This is the default entry point — no biometric is required because
     * the address is public. Shielded balance is loaded separately via
     * [loadShieldedBalance] when the user explicitly requests it.
     *
     * Does nothing if the wallet has not been onboarded yet (no cached address).
     */
    fun loadFromCache() {
        when (val current = _walletAddresses.value) {
            is AddressCacheState.Found -> loadBalances(current.addresses.unshieldedAddress)
            is AddressCacheState.Loading,
            is AddressCacheState.Empty -> {
                // Try to reload — onboarding may have just completed, or we
                // may have just finished deriving addresses for a new network.
                // Read current network from repository (reactive, not frozen config).
                viewModelScope.launch {
                    val network = networkRepository.getSelectedNetworkBlocking()
                    val loaded = walletAddressCache.load(network)
                    if (loaded != null) {
                        _walletAddresses.value = AddressCacheState.Found(loaded)
                        loadBalances(loaded.unshieldedAddress)
                    } else {
                        _walletAddresses.value = AddressCacheState.Empty
                    }
                }
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

        // Cancel previous collection + optimistic timeout to prevent stale transitions
        collectionJob?.cancel()
        optimisticTimeoutJob?.cancel()

        _balanceState.value = BalanceUiState.Loading(isRefreshing = false)

        // Optimistic timeout: if we're still in Loading state after 5 seconds
        // (e.g., fresh wallet with no UTXOs, indexer slow to respond), transition
        // to Success with zero balance. The subscription keeps running in the
        // background, so if real data arrives later it'll update the UI via the
        // normal flow collection.
        optimisticTimeoutJob = viewModelScope.launch {
            delay(OPTIMISTIC_TIMEOUT_MS)
            if (_balanceState.value is BalanceUiState.Loading) {
                Log.d(TAG, "Optimistic timeout hit ($OPTIMISTIC_TIMEOUT_MS ms) — showing zero balance")
                lastLoadTimestamp = clock.instant()
                _balanceState.value = BalanceUiState.Success(
                    balances = emptyList(),
                    lastUpdated = formatLastUpdated(lastLoadTimestamp!!),
                    totalBalance = BigInteger.ZERO,
                    shieldedBalances = cachedShieldedBalances,
                    shieldedAddress = cachedShieldedAddress,
                )
            }
        }

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
                    // Real data arrived — cancel the optimistic zero-balance timer
                    // so it doesn't overwrite a genuine Success state later.
                    optimisticTimeoutJob?.cancel()
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
     * Load shielded balance. Requires biometric auth because shielded event
     * decryption needs the zswap private key from the wallet seed.
     *
     * **UX:** This is LAZY — the user has to explicitly trigger it (e.g., via
     * a "Sync shielded balance" button or pull-to-refresh). We don't auto-load
     * it when the screen opens because showing a biometric prompt on launch
     * would be jarring right after onboarding.
     *
     * The shielded address for display is taken from the cache — only the
     * balance sync needs biometric.
     *
     * @param activity The hosting FragmentActivity (required by BiometricPrompt)
     * @param address The unshielded address (balance is looked up by this)
     */
    fun loadShieldedBalance(activity: FragmentActivity, address: String) {
        viewModelScope.launch {
            var plaintextSeed: PlaintextSeed? = null
            var hdWallet: HDWallet? = null

            try {
                // Biometric-gated seed load
                plaintextSeed = seedVault.loadSeed(activity)
                hdWallet = HDWallet.fromSeed(plaintextSeed.bip39Seed)

                // Derive zswap seed at m/44'/2400'/0'/3/0
                val zswapKey = hdWallet.selectAccount(0)
                    .selectRole(MidnightKeyRole.ZSWAP)
                    .deriveKeyAt(0)
                val zswapSeed = zswapKey.privateKeyBytes.copyOf()
                zswapKey.clear()

                // Shielded address comes from the cache — no need to re-derive
                val shieldedAddress = (_walletAddresses.value as? AddressCacheState.Found)
                    ?.addresses?.shieldedAddress

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
                // Wipe local copy AFTER storing
                java.util.Arrays.fill(zswapSeed, 0.toByte())

                startShieldedSync()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load shielded balance", e)
            } finally {
                plaintextSeed?.wipe()
                hdWallet?.clear()
            }
        }
    }

    /**
     * Persistent shielded subscription.
     *
     * Subscribes to zswapLedgerEvents via dedicated WebSocket (not shared with unshielded).
     * When new events arrive (after catching up to chain tip), does a full re-sync.
     * Re-subscribes in a loop if the subscription completes.
     */
    private fun startShieldedSync() {
        shieldedSyncJob?.cancel()
        shieldedSyncJob = viewModelScope.launch {
            try {
                val seed = shieldedSyncSeed ?: return@launch
                val addr = shieldedSyncAddress ?: return@launch

                // Track highest event ID to only re-sync on truly new events
                var lastMaxId = 0L

                while (true) {
                    Log.d(TAG, "Subscribing to zswap events (lastMaxId=$lastMaxId)")
                    shieldedRepository.subscribeToZswapEvents(fromId = lastMaxId)
                        .collect { event ->
                            lastMaxId = maxOf(lastMaxId, event.id)

                            // Only re-sync when we see the chain tip advance
                            if (event.id >= event.maxId && event.maxId > lastMaxId - 1) {
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
                        }
                    // Subscription completed — wait and re-subscribe
                    Log.d(TAG, "Shielded subscription ended, re-subscribing in 3s")
                    kotlinx.coroutines.delay(3000)
                }
            } catch (e: Exception) {
                if (e !is kotlinx.coroutines.CancellationException) {
                    Log.e(TAG, "Shielded subscription error", e)
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
        optimisticTimeoutJob?.cancel()
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

        /**
         * Time to wait for the indexer/database to emit real balance data before
         * optimistically showing zero balance. After this timeout, we transition
         * to Success(0) but keep the subscription running — real data that arrives
         * later will still update the UI through the normal flow collection.
         */
        const val OPTIMISTIC_TIMEOUT_MS = 5_000L
    }
}
