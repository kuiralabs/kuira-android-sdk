package com.midnight.kuira.core.compact

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * What [DAppConnectorClient.submitTransaction] reports, for each answer a connector wallet can give.
 *
 * A real WebSocket server on the device plays the wallet, so the client's actual transport and
 * JSON parsing are what is under test.
 */
@RunWith(AndroidJUnit4::class)
class DAppConnectorClientSubmitTest {

    private var wallet: FakeWallet? = null
    private var client: DAppConnectorClient? = null

    @After
    fun tearDown() {
        client?.disconnect()
        wallet?.stop(STOP_TIMEOUT_MS)
    }

    @Test
    fun aKuiraWalletsFinalizedReceiptCarriesHashAndHeight(): Unit = runBlocking {
        // Given
        val connected = connectTo(
            JSONObject().put("transactionHash", HASH).put("status", "finalized").put("blockHeight", 58_277L),
        )

        // When
        val submitted = connected.submitTransaction("sealed_tx_hex")

        // Then
        assertEquals(SubmittedTransaction(HASH, txIdentifier = null, TransactionStatus.FINALIZED, 58_277L), submitted)
        assertEquals("the balanced transaction is what the wallet receives", "sealed_tx_hex", wallet!!.lastTx)
    }

    @Test
    fun aKuiraWalletsPendingReceiptCarriesTheHashButNoHeight(): Unit = runBlocking {
        // Given
        val connected = connectTo(JSONObject().put("transactionHash", HASH).put("status", "pending"))

        // When
        val submitted = connected.submitTransaction("sealed_tx_hex")

        // Then
        assertEquals(SubmittedTransaction(HASH, txIdentifier = null, TransactionStatus.PENDING, blockHeight = null), submitted)
    }

    @Test
    fun aWalletSdkWalletsTxHashIsAnIdentifierNotAHash(): Unit = runBlocking {
        // Given: `mn serve` answers {txHash: <66-hex identifier>} and nothing else — measured
        val connected = connectTo(JSONObject().put("txHash", IDENTIFIER))

        // When
        val submitted = connected.submitTransaction("sealed_tx_hex")

        // Then: kept as the identifier it is, with no hash and no claim of finality
        assertEquals(
            SubmittedTransaction(txHash = null, txIdentifier = IDENTIFIER, TransactionStatus.PENDING, blockHeight = null),
            submitted,
        )
    }

    @Test
    fun aPlainSpecWalletThatAnswersNothingIsPendingWithNoHash(): Unit = runBlocking {
        // Given: Midnight's DApp Connector API answers submitTransaction with null
        val connected = connectTo(JSONObject.NULL)

        // When
        val submitted = connected.submitTransaction("sealed_tx_hex")

        // Then: submitted is all such a wallet promises
        assertEquals(TransactionStatus.PENDING, submitted.status)
        assertNull("a wallet that reports no hash must not be credited with one", submitted.txHash)
        assertNull(submitted.txIdentifier)
        assertNull(submitted.blockHeight)
    }

    private suspend fun connectTo(result: Any): DAppConnectorClient {
        val server = FakeWallet(result).also { wallet = it }
        server.start()
        check(server.started.await(START_TIMEOUT_MS, TimeUnit.MILLISECONDS)) { "fake wallet never started" }
        return DAppConnectorClient("ws://127.0.0.1:${server.port}").also {
            client = it
            it.connect()
        }
    }

    /** Answers every `submitTransaction` with [result], the way a connector wallet would. */
    private class FakeWallet(private val result: Any) : WebSocketServer(InetSocketAddress("127.0.0.1", 0)) {
        val started = CountDownLatch(1)

        @Volatile
        var lastTx: String? = null

        override fun onStart() = started.countDown()

        override fun onOpen(conn: WebSocket, handshake: ClientHandshake) = Unit

        override fun onClose(conn: WebSocket, code: Int, reason: String?, remote: Boolean) = Unit

        override fun onError(conn: WebSocket?, ex: Exception) = Unit

        override fun onMessage(conn: WebSocket, message: String) {
            val request = JSONObject(message)
            lastTx = request.getJSONObject("params").getString("tx")
            conn.send(JSONObject().put("jsonrpc", "2.0").put("id", request.getInt("id")).put("result", result).toString())
        }
    }

    private companion object {
        val HASH = "cd".repeat(32)

        /** The shape `mn serve` 0.5.8 answered with on localnet: a tag byte and 32 bytes. */
        val IDENTIFIER = "00" + "f2".repeat(32)
        const val START_TIMEOUT_MS = 5_000L
        const val STOP_TIMEOUT_MS = 1_000
    }
}
