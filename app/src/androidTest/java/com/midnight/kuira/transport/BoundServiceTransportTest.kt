package com.midnight.kuira.transport

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.midnight.kuira.core.connector.model.ConnectionStatus
import com.midnight.kuira.core.connector.transport.ConnectorBinder
import com.midnight.kuira.service.ConnectorService
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Real Bound Service transport test.
 *
 * Actually binds to ConnectorService on the device, gets the real Binder,
 * and calls methods through both the direct API and JSON-RPC interface.
 */
@RunWith(AndroidJUnit4::class)
class BoundServiceTransportTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val json = Json { ignoreUnknownKeys = true }
    private var binder: ConnectorBinder? = null
    private val latch = CountDownLatch(1)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            binder = service as? ConnectorBinder
            latch.countDown()
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            binder = null
        }
    }

    @Before
    fun setup() {
        // Start the service first (it needs to be running to bind)
        ConnectorService.start(context)
        Thread.sleep(1000)

        // Bind to it
        val intent = Intent(context, ConnectorService::class.java)
        context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        assertTrue("Should bind within 5s", latch.await(5, TimeUnit.SECONDS))
        assertNotNull("Binder should not be null", binder)
    }

    @After
    fun tearDown() {
        context.unbindService(connection)
    }

    // ── Direct Kotlin API (type-safe, no serialization) ──

    @Test
    fun boundService_directApi_getConnectionStatus() {
        val status = binder!!.handler.getConnectionStatus()
        assertTrue(status is ConnectionStatus.Connected)
        assertEquals("undeployed", (status as ConnectionStatus.Connected).networkId)
    }

    @Test
    fun boundService_directApi_getAddresses() {
        val addr = binder!!.handler.getUnshieldedAddress()
        assertTrue(addr.unshieldedAddress.startsWith("mn_addr"))
    }

    @Test
    fun boundService_directApi_getConfiguration() {
        val config = binder!!.handler.getConfiguration()
        assertEquals("undeployed", config.networkId)
        assertTrue(config.indexerUri.contains("/graphql"))
    }

    // ── JSON-RPC via Binder (same protocol as WebSocket) ──

    @Test
    fun boundService_jsonRpc_getConnectionStatus() = runBlocking {
        val response = binder!!.handleJsonRpc(
            """{"jsonrpc":"2.0","id":1,"method":"getConnectionStatus","params":{}}"""
        )
        val obj = json.parseToJsonElement(response).jsonObject
        val result = obj["result"]!!.jsonObject
        assertEquals("connected", result["status"]?.jsonPrimitive?.content)
    }

    @Test
    fun boundService_jsonRpc_getConfiguration() = runBlocking {
        val response = binder!!.handleJsonRpc(
            """{"jsonrpc":"2.0","id":2,"method":"getConfiguration","params":{}}"""
        )
        val result = json.parseToJsonElement(response).jsonObject["result"]!!.jsonObject
        assertEquals("undeployed", result["networkId"]?.jsonPrimitive?.content)
    }

    @Test
    fun boundService_jsonRpc_unknownMethod() = runBlocking {
        val response = binder!!.handleJsonRpc(
            """{"jsonrpc":"2.0","id":3,"method":"fakeMethod","params":{}}"""
        )
        val error = json.parseToJsonElement(response).jsonObject["error"]!!.jsonObject
        assertEquals(-32601, error["code"]?.jsonPrimitive?.int)
    }
}
