package com.midnight.kuira.core.connector.transport

import android.webkit.JavascriptInterface
import android.webkit.WebView
import com.midnight.kuira.core.connector.JsonRpcRouter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * WebView JS Bridge transport for the DApp Connector.
 *
 * Injected into a WebView as `window.midnight`. Web dApps call
 * `window.midnight.request(jsonRpcString)` and get a JSON-RPC response.
 *
 * Usage:
 * ```kotlin
 * webView.addJavascriptInterface(jsBridge, "midnight")
 * ```
 *
 * From JavaScript:
 * ```javascript
 * const response = window.midnight.request(
 *   JSON.stringify({jsonrpc:"2.0", id:1, method:"getConnectionStatus", params:{}})
 * );
 * const result = JSON.parse(response);
 * ```
 */
class ConnectorJsBridge(
    private val router: JsonRpcRouter,
    private val scope: CoroutineScope,
) {

    /**
     * Synchronous JSON-RPC handler for JavaScript.
     *
     * @JavascriptInterface methods must return synchronously.
     * We bridge to the suspend router via runBlocking on a coroutine.
     */
    @JavascriptInterface
    fun request(jsonRpc: String): String {
        return runBlocking {
            router.handleMessage(jsonRpc)
        }
    }

    /**
     * Async JSON-RPC handler — calls a JavaScript callback with the response.
     *
     * Usage from JS:
     * ```javascript
     * window.midnight.requestAsync(jsonRpcString, 'myCallbackFunction');
     * // later: myCallbackFunction('{"jsonrpc":"2.0","id":1,"result":...}')
     * ```
     *
     * Requires a reference to the WebView to evaluate the callback.
     */
    fun requestAsync(webView: WebView, jsonRpc: String, callbackName: String) {
        scope.launch {
            val response = router.handleMessage(jsonRpc)
            val escaped = response.replace("'", "\\'")
            webView.post {
                webView.evaluateJavascript("$callbackName('$escaped')", null)
            }
        }
    }

    /** Inject this bridge into a WebView as window.midnight */
    fun inject(webView: WebView) {
        webView.addJavascriptInterface(this, "midnight")
    }
}
