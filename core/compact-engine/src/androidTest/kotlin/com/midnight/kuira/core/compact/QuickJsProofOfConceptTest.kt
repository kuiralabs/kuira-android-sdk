package com.midnight.kuira.core.compact

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dokar.quickjs.binding.function
import com.dokar.quickjs.quickJs
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Proof of concept: QuickJS runs on Android and supports
 * everything we need for Compact contract execution.
 */
@RunWith(AndroidJUnit4::class)
class QuickJsProofOfConceptTest {

    @Test
    fun quickjs_evaluatesBasicJs() = runBlocking {
        quickJs {
            val result = evaluate<Int>("1 + 2")
            assertEquals(3, result)
        }
    }

    @Test
    fun quickjs_handlesStrings() = runBlocking {
        quickJs {
            val result = evaluate<String>("'hello' + ' ' + 'world'")
            assertEquals("hello world", result)
        }
    }

    @Test
    fun quickjs_handlesJsonObjects() = runBlocking {
        quickJs {
            val result = evaluate<String>("""
                JSON.stringify({ status: 'connected', networkId: 'undeployed' })
            """.trimIndent())
            assertTrue(result.contains("connected"))
        }
    }

    @Test
    fun quickjs_nativeFunctionCallback() = runBlocking {
        // JS calls Kotlin — this is how witnesses will work
        quickJs {
            function("getSecretKey") {
                "secret_key_bytes_here"
            }
            val result = evaluate<String>("getSecretKey()")
            assertEquals("secret_key_bytes_here", result)
        }
    }

    @Test
    fun quickjs_nativeFunctionWithArgs() = runBlocking {
        quickJs {
            function("hash") { args: Array<Any?> ->
                val input = args[0] as String
                "hashed_$input"
            }
            val result = evaluate<String>("hash('mydata')")
            assertEquals("hashed_mydata", result)
        }
    }
}
