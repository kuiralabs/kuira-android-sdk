package com.midnight.kuira.core.identity.link

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Drives the ceremony on the device that wants the wallet.
 *
 * The states are a sealed hierarchy because the UI has to render every one of them, and because the
 * order matters for safety: the code is shown *before* anything is opened, and the entropy is
 * delivered only after the person says the two screens agree. A session that skipped straight from
 * scanning to delivery would still decrypt correctly — see [DeviceLink] — and would still be wrong.
 *
 * Single-use. Once it reaches [State.Linked] or [State.Failed] it does not go back; start a new
 * session to retry, which also means a fresh ephemeral key.
 *
 * Byte-for-byte the same ceremony as the iOS `NewDeviceLinkSession`, and deliberately the same
 * states — the two phones in the ceremony are usually not the same platform, so a step that exists
 * on one and not the other is a step someone is left waiting for.
 *
 * Not thread-safe by design: it is driven from the UI, and [State] is exposed as a [StateFlow] for
 * Compose to collect.
 */
class NewDeviceLinkSession(
    /**
     * Hands the recovered entropy to this device's own secure store. Called at most once, and only
     * after confirmation. Throwing here is reported as [LinkException.StorageFailed], because the
     * ceremony itself succeeded.
     */
    private val deliver: suspend (ByteArray) -> Unit,
) {

    sealed interface State {
        /**
         * Show [requestQrText] and wait for the source device to scan it.
         *
         * Deliberately separate from [ScanningResponse]: the person has to hold this screen up for
         * the other phone to read, which they cannot do while also pointing this phone's camera at
         * it. Showing a QR and a live camera at once asks for two things that cannot happen at the
         * same time.
         */
        data object ShowingRequest : State

        /** The other phone has scanned; now point this camera at the code it shows back. */
        data object ScanningResponse : State

        /**
         * A response has been scanned. Show [code] next to the source device's own code and ask the
         * person whether they match. Nothing has been decrypted yet.
         */
        data class AwaitingConfirmation(val code: String) : State

        /** The entropy was opened and handed to custody. The wallet is on this device. */
        data object Linked : State

        /** The ceremony ended without a wallet. Nothing was stored. */
        data class Failed(val cause: LinkException) : State

        /**
         * The person said the codes differ, or backed out. Not an error — this is the check
         * working, and it must read differently from a failure in the UI.
         */
        data object Cancelled : State
    }

    private val request: LinkRequest
    private val secret: LinkSecret

    init {
        val (request, secret) = DeviceLink.makeRequest()
        this.request = request
        this.secret = secret
    }

    private val _state = MutableStateFlow<State>(State.ShowingRequest)
    val state: StateFlow<State> = _state.asStateFlow()

    private var scannedResponse: LinkResponse? = null

    /**
     * Set before the first suspension point — see the note on `SourceLinkSession.isApproving`. Here
     * a second pass would open the payload again and write the seed to the keystore twice.
     */
    private var isConfirming = false

    /** The text for this device's QR — the ephemeral public key the source device scans. */
    val requestQrText: String get() = request.qrText()

    /** The person has shown their code to the other phone and is ready to scan the reply. */
    fun readyToScan() {
        if (_state.value !is State.ShowingRequest) return
        _state.value = State.ScanningResponse
    }

    /**
     * Accepts a scanned response and moves to confirmation.
     *
     * Text that is not one of our QRs is thrown back rather than latching the session into a
     * failure, because a camera pointed at a phone will read all sorts of things on the way to the
     * right one.
     */
    @Throws(LinkException::class)
    fun scan(text: String) {
        if (_state.value !is State.ScanningResponse) return
        val response = LinkResponse.fromQrText(text)
        scannedResponse = response
        _state.value = State.AwaitingConfirmation(DeviceLink.verificationCode(request, response))
    }

    /** The person confirmed the codes match. Opens the payload and delivers it. */
    suspend fun confirm() {
        if (_state.value !is State.AwaitingConfirmation) return
        val response = scannedResponse ?: return
        if (isConfirming) return
        isConfirming = true
        // This buffer IS ours — `DeviceLink.open` minted it — so wiping it in `finally` is right.
        // `deliver` must therefore copy anything it intends to keep; it is handed the live array so
        // that custody can write it straight through without a second copy lingering in the heap.
        var entropy: ByteArray? = null
        try {
            entropy = DeviceLink.open(response, secret)
            deliver(entropy)
            _state.value = State.Linked
        } catch (e: LinkException) {
            _state.value = State.Failed(e)
        } catch (e: Exception) {
            // The protocol succeeded and the caller's storage did not. Reporting SealFailed here
            // would send someone to re-run the ceremony over what is really a keystore problem.
            _state.value = State.Failed(LinkException.StorageFailed(e.message ?: "unknown"))
        } finally {
            entropy?.fill(0)
            isConfirming = false
        }
    }

    /** Step back from the scanner to the code, for someone who tapped on too early. */
    fun showRequestAgain() {
        if (_state.value !is State.ScanningResponse) return
        _state.value = State.ShowingRequest
    }

    /** The person said the codes differ, or backed out. Nothing is stored. */
    fun abort() {
        scannedResponse = null
        _state.value = State.Cancelled
    }
}
