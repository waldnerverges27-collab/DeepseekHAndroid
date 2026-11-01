package io.deepseek.harness.android.webview

import android.webkit.JavascriptInterface
import io.deepseek.harness.android.core.AppPaths
import io.deepseek.harness.android.engine.DshEngineController
import io.deepseek.harness.android.engine.DshHealthProbe
import org.json.JSONObject
import java.io.File

/**
 * Puente JavaScript expuesto como `window.androidBridge` únicamente para el origen local
 * del motor (`http://127.0.0.1:<port>`). Permite a la Web UI o plugins de `dsh` abrir el
 * selector SAF, abrir la terminal integrada o mantener encendida la pantalla.
 */
class DshWebViewBridge(
    private val rootfsProvider: () -> File,
    private val portProvider: () -> Int,
    private val onPickDirectoryRequested: (callbackId: String) -> Unit,
    private val onOpenTerminalRequested: (guestPath: String) -> Unit,
    private val onKeepScreenOnRequested: (enabled: Boolean) -> Unit,
) {

    @JavascriptInterface
    fun version(): String = "1.0"

    @JavascriptInterface
    fun checkEngine(): String {
        val port = portProvider()
        val res = DshHealthProbe.probe(port = port, rootfsDir = rootfsProvider())
        return JSONObject()
            .put("running", res.reachable)
            .put("httpCode", res.httpCode)
            .put("latencyMs", res.latencyMs)
            .apply { if (res.error != null) put("error", res.error) }
            .toString()
    }

    @JavascriptInterface
    fun pickDirectory(callbackId: String) {
        onPickDirectoryRequested(callbackId)
    }

    @JavascriptInterface
    fun openTerminal(guestPath: String?) {
        val path = guestPath?.takeIf { it.isNotBlank() } ?: AppPaths.GUEST_WORKSPACE
        onOpenTerminalRequested(path)
    }

    @JavascriptInterface
    fun keepScreenOn(enable: Boolean) {
        onKeepScreenOnRequested(enable)
    }

    @JavascriptInterface
    fun getPickToken(): String = DshEngineController.bridgeSessionToken
}
