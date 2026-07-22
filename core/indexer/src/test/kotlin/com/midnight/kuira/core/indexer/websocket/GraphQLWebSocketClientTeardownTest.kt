package com.midnight.kuira.core.indexer.websocket

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets as ServerWebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Teardown CONTRACT test for [GraphQLWebSocketClient].
 *
 * Runs the client against a REAL in-memory CIO WebSocket server (no mocks) that
 * implements the minimum graphql-transport-ws handshake and records when a
 * `complete` frame arrives. It asserts the end-to-end teardown contract: after the
 * collecting coroutine is cancelled (the production trigger — subscriptionScope is
 * cancelled on a network switch / SDK rebuild), the client still emits `complete`
 * so the server can stop the subscription. Guards against gross regressions:
 * dropping the `Complete` send, or breaking the connect/subscribe handshake.
 *
 * SCOPE / HONESTY: this is NOT a discriminating regression test for the specific
 * swallowed-on-cancellation bug the fix addresses. That bug only manifests
 * when the teardown send actually SUSPENDS (contended writer / full socket buffer)
 * — under this lightweight test the send takes Channel.send's non-suspending fast
 * path, so it succeeds with OR without the NonCancellable wrap. The fix itself is
 * grounded in coroutine-cancellation semantics + on-device logcat verification
 * (the "No active subscription found" flood disappearing); a JVM unit test can't
 * deterministically force the suspending teardown the production leak needs.
 */
@RunWith(RobolectricTestRunner::class)
class GraphQLWebSocketClientTeardownTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private lateinit var server: ApplicationEngine
    private lateinit var httpClient: HttpClient
    private var port: Int = 0

    /** Resolved with the subscription id the moment the server receives `complete`. */
    private val completeReceived = CompletableDeferred<String>()

    @Before
    fun setUp() {
        server = embeddedServer(CIO, port = 0) {
            install(ServerWebSockets)
            routing {
                webSocket("/graphql/ws") {
                    for (frame in incoming) {
                        if (frame !is Frame.Text) continue
                        val obj = json.parseToJsonElement(frame.readText()).jsonObject
                        when (obj["type"]?.jsonPrimitive?.content) {
                            "connection_init" -> send(Frame.Text("""{"type":"connection_ack"}"""))
                            "subscribe" -> {
                                val id = obj["id"]?.jsonPrimitive?.content
                                // One event, then keep the socket open — the client
                                // tears down by cancelling, not by waiting for us.
                                send(Frame.Text("""{"type":"next","id":"$id","payload":{"data":{"v":1}}}"""))
                            }
                            "complete" -> {
                                completeReceived.complete(obj["id"]?.jsonPrimitive?.content ?: "")
                            }
                        }
                    }
                }
            }
        }.start(wait = false)
        port = runBlocking { server.resolvedConnectors().first().port }

        httpClient = HttpClient(OkHttp) { install(ClientWebSockets) }
    }

    @After
    fun tearDown() {
        if (::httpClient.isInitialized) httpClient.close()
        if (::server.isInitialized) server.stop(gracePeriodMillis = 0, timeoutMillis = 500)
    }

    @Test
    fun `cancelled collection still sends complete to the server`() = runBlocking {
        val client = GraphQLWebSocketClient(
            httpClient = httpClient,
            url = "ws://localhost:$port/graphql/ws",
            connectionTimeout = 5_000,
        )
        client.connect()

        // Collect in a dedicated job (as ShieldedBalanceTracker does on
        // subscriptionScope). The first event signals the subscription is live;
        // the collector then suspends waiting for more.
        val firstEvent = CompletableDeferred<Any?>()
        val collectorScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val job = collectorScope.launch {
            client.subscribe("subscription { v }").collect { event ->
                if (!firstEvent.isCompleted) firstEvent.complete(event)
            }
        }
        assertNotNull(withTimeout(2_000) { firstEvent.await() })

        // Cancel the collecting coroutine — the real teardown trigger (network
        // switch / SDK rebuild cancels subscriptionScope).
        job.cancelAndJoin()

        val completedId = withTimeout(2_000) { completeReceived.await() }
        assertTrue("expected a sub_* id, got '$completedId'", completedId.startsWith("sub_"))

        client.close()
    }
}
