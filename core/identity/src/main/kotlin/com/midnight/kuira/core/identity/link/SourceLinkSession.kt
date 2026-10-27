package com.midnight.kuira.core.identity.link

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Drives the ceremony on the device that already has the wallet.
 *
 * The seed is read only after the person approves — reading it earlier would put the wallet's
 * entropy in memory for every scan of a stray QR, and on this platform reading it is what triggers
 * the biometric prompt, so the prompt would appear before the user had been told what for.
 *
 * The mirror of the iOS `SourceLinkSession`, state for state. See [NewDeviceLinkSession] for why
 * the two platforms are kept identical.
 */
class SourceLinkSession(
    /**
     * Reads this device's canonical 32-byte entropy from custody. Invoked once, after approval.
     * Throwing here is reported as [LinkException.SeedUnavailable] — a declined biometric prompt is
     * the common case.
     */
    private val entropySource: suspend () -> ByteArray,
) {

    sealed interface State {
        /** Point the camera at the other device's QR. */
        data object Scanning : State

        /** A request was scanned. Ask "copy this wallet to your other device?" before sealing. */
        data object AwaitingApproval : State

        /** Show [qrText] for the other device to scan, and [code] for the person to compare. */
        data class ShowingResponse(val qrText: String, val code: String) : State

        /** The ceremony ended without sealing anything. */
        data class Failed(val cause: LinkException) : State

        /** The person declined the copy. Not an error. */
        data object Cancelled : State
    }

    private val _state = MutableStateFlow<State>(State.Scanning)
    val state: StateFlow<State> = _state.asStateFlow()

    private var scannedRequest: LinkRequest? = null

    /**
     * Set before the first suspension point. The state check below runs *before* the seed read
     * suspends, so two taps on a button that has not visibly responded — ordinary on a phone
     * waiting for a fingerprint — would both pass it and both unlock the wallet.
     */
    private var isApproving = false

    /** Accepts a scanned request and moves to the approval prompt. */
    @Throws(LinkException::class)
    fun scan(text: String) {
        if (_state.value !is State.Scanning) return
        scannedRequest = LinkRequest.fromQrText(text)
        _state.value = State.AwaitingApproval
    }

    /** The person approved the copy. Reads the seed, seals it, and produces the QR to display. */
    suspend fun approve() {
        if (_state.value !is State.AwaitingApproval) return
        val request = scannedRequest ?: return
        if (isApproving) return
        isApproving = true
        // A DEFENSIVE COPY, because the wipe below would otherwise reach into whatever buffer
        // custody handed back. Kotlin passes a ByteArray by reference, where the iOS session's
        // `var entropy = try await entropySource()` copies on write and so only ever wipes its own
        // — so a straight port zeroes the SOURCE WALLET'S seed in memory. A test caught it; a user
        // would have seen their own wallet stop working after copying it to another phone.
        // Custody owns its buffer and is responsible for wiping it; this session owns only this.
        var entropy: ByteArray? = null
        try {
            entropy = entropySource().copyOf()
            val response = DeviceLink.seal(entropy, request)
            _state.value = State.ShowingResponse(
                qrText = response.qrText(),
                code = DeviceLink.verificationCode(request, response),
            )
        } catch (e: LinkException) {
            _state.value = State.Failed(e)
        } catch (e: Exception) {
            // Custody refused. A declined biometric prompt is the common case and is not a fault,
            // so it must not surface as a crypto failure.
            _state.value = State.Failed(LinkException.SeedUnavailable(e.message ?: "unknown"))
        } finally {
            entropy?.fill(0)
            isApproving = false
        }
    }

    /** The person declined. Nothing was read and nothing was sealed. */
    fun abort() {
        scannedRequest = null
        _state.value = State.Cancelled
    }
}
