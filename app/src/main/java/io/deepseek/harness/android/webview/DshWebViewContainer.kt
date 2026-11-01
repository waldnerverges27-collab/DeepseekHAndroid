package io.deepseek.harness.android.webview

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.util.Base64
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import io.deepseek.harness.android.core.AppLogger
import io.deepseek.harness.android.core.AppPaths
import io.deepseek.harness.android.engine.DshAuthCookieMinter
import io.deepseek.harness.android.engine.EngineStateStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

data class WebViewUiState(
    val isLoading: Boolean = true,
    val currentUrl: String = "",
    val loadErrorDescription: String? = null,
    val canGoBack: Boolean = false,
)

/**
 * Contenedor retenido del `WebView` principal que aloja la interfaz web de `deepseek-harness`
 * en `http://127.0.0.1:3080`.
 *
 * Responsabilidades:
 * - Aislar estrictamente la navegación: solo `127.0.0.1:<port>` y `localhost:<port>` permanecen
 *   en el WebView con acceso a `window.androidBridge`; cualquier enlace externo se abre en el
 *   navegador del sistema.
 * - Autorrecuperación ante respuestas `401/403` regenerando la cookie firmada `dsh-auth-*`
 *   desde `/root/.dsh/.credentials.yaml` o usando la URL `?token=...` capturada del motor.
 * - Manejo de cargas de archivos (`<input type="file">`), descargas (`/api/session.export`),
 *   permisos de micrófono y polyfills de compatibilidad para WebViews antiguos.
 */
class DshWebViewHolder(
    private val context: Context,
    private val portProvider: () -> Int,
    private val onShowFileChooserCallback: (ValueCallback<Array<Uri>>) -> Boolean,
    private val onRequestAudioPermission: (PermissionRequest) -> Unit,
    private val onExternalUri: (Uri) -> Unit,
    private val downloadHandler: DshDownloadHandler,
    bridge: DshWebViewBridge,
) {

    private val _uiState = MutableStateFlow(WebViewUiState())
    val uiState: StateFlow<WebViewUiState> = _uiState.asStateFlow()

    private var polyfillsScriptCache: String? = null
    private var mobileCssB64Cache: String? = null
    private var lastAuthRetryAtMs: Long = 0L
    private var hasLoadedInitialEngineUrl: Boolean = false

    @SuppressLint("SetJavaScriptEnabled")
    val webView: WebView = WebView(context).apply {
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )

        val isDebug = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (isDebug) {
            WebView.setWebContentsDebuggingEnabled(true)
        }

        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = false
            allowContentAccess = true
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            mediaPlaybackRequiresUserGesture = false
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(true)
            builtInZoomControls = false
            displayZoomControls = false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                @Suppress("DEPRECATION")
                forceDark = WebSettings.FORCE_DARK_AUTO
            }
        }

        addJavascriptInterface(bridge, "androidBridge")

        setDownloadListener { url, _, contentDisposition, mimeType, _ ->
            if (isSameEngineOrigin(Uri.parse(url))) {
                downloadHandler.enqueueEngineDownload(
                    url = url,
                    contentDisposition = contentDisposition,
                    mimeType = mimeType,
                    port = portProvider(),
                )
            } else {
                onExternalUri(Uri.parse(url))
            }
        }

        webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                _uiState.value = _uiState.value.copy(
                    isLoading = true,
                    currentUrl = url ?: "",
                    loadErrorDescription = null,
                    canGoBack = view.canGoBack(),
                )
                injectPolyfills(view)
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val uri = request.url
                if (!isSameEngineOrigin(uri)) {
                    onExternalUri(uri)
                    return true
                }
                if (uri.path == "/api/session.export" && request.method.equals("GET", ignoreCase = true)) {
                    downloadHandler.enqueueEngineDownload(
                        url = uri.toString(),
                        contentDisposition = null,
                        mimeType = "application/json",
                        port = portProvider(),
                    )
                    return true
                }
                return false
            }

            override fun onPageFinished(view: WebView, url: String?) {
                super.onPageFinished(view, url)
                injectMobileCss(view)
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    currentUrl = url ?: "",
                    canGoBack = view.canGoBack(),
                )
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError,
            ) {
                super.onReceivedError(view, request, error)
                if (request.isForMainFrame) {
                    val desc = "${error.description} (código ${error.errorCode})"
                    AppLogger.warn("WebView", "Error cargando ${request.url}: $desc")
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        loadErrorDescription = desc,
                    )
                }
            }

            override fun onReceivedHttpError(
                view: WebView,
                request: WebResourceRequest,
                errorResponse: WebResourceResponse,
            ) {
                super.onReceivedHttpError(view, request, errorResponse)
                val status = errorResponse.statusCode
                if (request.isForMainFrame && (status == 401 || status == 403) && isSameEngineOrigin(request.url)) {
                    val now = System.currentTimeMillis()
                    if (now - lastAuthRetryAtMs > 5_000L) {
                        lastAuthRetryAtMs = now
                        AppLogger.info("WebView", "HTTP $status recibido; renovando cookie de sesión dsh y recargando")
                        val port = portProvider()
                        val minted = DshAuthCookieMinter.syncCookiesToWebView(AppPaths.rootfsDir(context), port)
                        val fallbackTokenUrl = EngineStateStore.latestTokenAuthUrl
                        val nextUrl = if (minted != null) {
                            "http://127.0.0.1:$port/"
                        } else {
                            fallbackTokenUrl ?: "http://127.0.0.1:$port/"
                        }
                        view.post { view.loadUrl(nextUrl) }
                    }
                }
            }
        }

        webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                webView: WebView,
                filePathCallback: ValueCallback<Array<Uri>>,
                fileChooserParams: FileChooserParams,
            ): Boolean = onShowFileChooserCallback(filePathCallback)

            override fun onPermissionRequest(request: PermissionRequest) {
                val resources = request.resources ?: emptyArray()
                if (resources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)) {
                    onRequestAudioPermission(request)
                } else {
                    request.deny()
                }
            }

            override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                if (consoleMessage.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                    AppLogger.warn(
                        "WebConsole",
                        "${consoleMessage.message()} (${consoleMessage.sourceId()}:${consoleMessage.lineNumber()})",
                    )
                }
                return super.onConsoleMessage(consoleMessage)
            }
        }
    }

    fun loadEngineUrlIfNeeded(url: String) {
        if (!hasLoadedInitialEngineUrl || _uiState.value.loadErrorDescription != null) {
            hasLoadedInitialEngineUrl = true
            DshAuthCookieMinter.syncCookiesToWebView(AppPaths.rootfsDir(context), portProvider())
            webView.loadUrl(url)
        }
    }

    fun forceReloadEngine(url: String) {
        hasLoadedInitialEngineUrl = true
        _uiState.value = _uiState.value.copy(loadErrorDescription = null, isLoading = true)
        DshAuthCookieMinter.syncCookiesToWebView(AppPaths.rootfsDir(context), portProvider())
        webView.loadUrl(url)
    }

    fun deliverPickedDirectoryToJs(callbackId: String, guestPath: String?) {
        val safeCb = JSONObject.quote(callbackId)
        val safePath = if (guestPath != null) JSONObject.quote(guestPath) else "null"
        val js = """
            if (window.__dshBridge && typeof window.__dshBridge.onDirectoryPicked === 'function') {
              window.__dshBridge.onDirectoryPicked($safeCb, $safePath);
            }
        """.trimIndent()
        webView.post { webView.evaluateJavascript(js, null) }
    }

    private fun isSameEngineOrigin(uri: Uri?): Boolean {
        if (uri == null) return false
        val scheme = uri.scheme?.lowercase() ?: return false
        if (scheme != "http" && scheme != "https") return false
        val host = uri.host?.lowercase() ?: return false
        val port = if (uri.port == -1) 80 else uri.port
        return (host == "127.0.0.1" || host == "localhost") && port == portProvider()
    }

    private fun injectPolyfills(view: WebView) {
        try {
            val script = polyfillsScriptCache ?: context.assets
                .open(AppPaths.ASSET_WEB_POLYFILLS)
                .bufferedReader()
                .use { it.readText() }
                .also { polyfillsScriptCache = it }
            view.evaluateJavascript(script, null)
        } catch (_: Exception) {
        }
    }

    private fun injectMobileCss(view: WebView) {
        try {
            val b64 = mobileCssB64Cache ?: context.assets
                .open(AppPaths.ASSET_WEB_MOBILE_CSS)
                .use { Base64.encodeToString(it.readBytes(), Base64.NO_WRAP) }
                .also { mobileCssB64Cache = it }
            val script = """
                (function() {
                  if (document.getElementById('dsh-android-mobile-css')) return;
                  var s = document.createElement('style');
                  s.id = 'dsh-android-mobile-css';
                  s.textContent = atob('$b64');
                  (document.head || document.documentElement).appendChild(s);
                })();
            """.trimIndent()
            view.evaluateJavascript(script, null)
        } catch (_: Exception) {
        }
    }
}

fun Context.openExternalBrowserSafe(uri: Uri) {
    try {
        startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: Exception) {
        AppLogger.warn("Browser", "No se encontró navegador externo para $uri", e)
    }
}
