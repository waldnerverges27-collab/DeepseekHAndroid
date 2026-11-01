package io.deepseek.harness.android

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.WindowManager
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import io.deepseek.harness.android.core.AppPaths
import io.deepseek.harness.android.ui.MainShellScreen
import io.deepseek.harness.android.ui.MainViewModel
import io.deepseek.harness.android.ui.WorkspaceTab
import io.deepseek.harness.android.ui.theme.DeepSeekHarnessTheme
import io.deepseek.harness.android.webview.DshDownloadHandler
import io.deepseek.harness.android.webview.DshWebViewBridge
import io.deepseek.harness.android.webview.DshWebViewHolder
import io.deepseek.harness.android.webview.openExternalBrowserSafe

/**
 * Actividad principal de DeepSeek Harness Android.
 *
 * Gestión de ciclo de vida (Requisito 3e):
 * - Pausar o cerrar la actividad NO detiene el motor `dsh` (gestionado por [io.deepseek.harness.android.engine.DshForegroundService])
 *   ni destruye las sesiones PTY `bash` activas (mantenidas por [io.deepseek.harness.android.terminal.TerminalSessionRegistry]).
 * - `WebView.onPause()` / `WebView.onResume()` se invocan limpiamente sin recargar conversaciones activas.
 */
class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    private lateinit var webViewHolder: DshWebViewHolder
    private var pendingJsPickCallbackId: String? = null
    private var pendingFileChooserCallback: ValueCallback<Array<Uri>>? = null
    private var pendingWebAudioRequest: PermissionRequest? = null

    private val safTreeLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
            val jsCallback = pendingJsPickCallbackId
            pendingJsPickCallbackId = null

            if (uri == null) {
                if (jsCallback != null && ::webViewHolder.isInitialized) {
                    webViewHolder.deliverPickedDirectoryToJs(jsCallback, null)
                }
                return@registerForActivityResult
            }

            viewModel.onSafTreePicked(uri) { entry ->
                if (jsCallback != null && ::webViewHolder.isInitialized) {
                    webViewHolder.deliverPickedDirectoryToJs(jsCallback, entry.guestPath)
                }
            }
        }

    private val webFileChooserLauncher =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
            val cb = pendingFileChooserCallback
            pendingFileChooserCallback = null
            cb?.onReceiveValue(if (uris.isEmpty()) null else uris.toTypedArray())
        }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ -> }

    private val audioPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val req = pendingWebAudioRequest
            pendingWebAudioRequest = null
            if (req != null) {
                if (granted) req.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE)) else req.deny()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        savedInstanceState?.getString(STATE_PENDING_JS_PICK)?.let {
            pendingJsPickCallbackId = it
        }

        requestNotificationPermissionIfNeeded()

        val downloadHandler = DshDownloadHandler(
            context = this,
            scope = lifecycleScope,
            onDownloadFinished = { _, message -> viewModel.emitMessage(message) },
        )

        val bridge = DshWebViewBridge(
            rootfsProvider = { AppPaths.rootfsDir(this) },
            portProvider = { viewModel.preferences.enginePort },
            onPickDirectoryRequested = { callbackId ->
                runOnUiThread {
                    pendingJsPickCallbackId = callbackId
                    safTreeLauncher.launch(null)
                }
            },
            onOpenTerminalRequested = { guestPath ->
                runOnUiThread {
                    viewModel.openGuestDirectoryInTerminal(guestPath)
                }
            },
            onKeepScreenOnRequested = { enabled ->
                runOnUiThread {
                    if (enabled) {
                        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    } else {
                        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    }
                }
            },
        )

        webViewHolder = DshWebViewHolder(
            context = this,
            portProvider = { viewModel.preferences.enginePort },
            onShowFileChooserCallback = { callback ->
                pendingFileChooserCallback?.onReceiveValue(null)
                pendingFileChooserCallback = callback
                runOnUiThread { webFileChooserLauncher.launch(arrayOf("*/*")) }
                true
            },
            onRequestAudioPermission = { request ->
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED
                ) {
                    request.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE))
                } else {
                    pendingWebAudioRequest = request
                    audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }
            },
            onExternalUri = { uri -> openExternalBrowserSafe(uri) },
            downloadHandler = downloadHandler,
            bridge = bridge,
        )

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (viewModel.selectedTab.value == WorkspaceTab.WEB && webViewHolder.webView.canGoBack()) {
                        webViewHolder.webView.goBack()
                    } else if (viewModel.selectedTab.value != WorkspaceTab.WEB) {
                        viewModel.selectTab(WorkspaceTab.WEB)
                    } else {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                        isEnabled = true
                    }
                }
            },
        )

        setContent {
            DeepSeekHarnessTheme {
                MainShellScreen(
                    viewModel = viewModel,
                    webViewHolder = webViewHolder,
                    onPickSafDirectory = {
                        pendingJsPickCallbackId = null
                        safTreeLauncher.launch(null)
                    },
                    onRequestAllFilesAccess = { openAllFilesAccessSettings() },
                    onRequestBatteryExemption = { requestIgnoreBatteryOptimizations() },
                )
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        pendingJsPickCallbackId?.let { outState.putString(STATE_PENDING_JS_PICK, it) }
    }

    override fun onResume() {
        super.onResume()
        if (::webViewHolder.isInitialized) {
            webViewHolder.webView.onResume()
        }
    }

    override fun onPause() {
        if (::webViewHolder.isInitialized) {
            webViewHolder.webView.onPause()
        }
        super.onPause()
    }

    override fun onDestroy() {
        if (::webViewHolder.isInitialized) {
            webViewHolder.webView.destroy()
        }
        super.onDestroy()
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun openAllFilesAccessSettings() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        try {
            startActivity(
                Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.parse("package:$packageName")
                },
            )
        } catch (_: Exception) {
            runCatching {
                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        }
    }

    @SuppressLint("BatteryLife")
    private fun requestIgnoreBatteryOptimizations() {
        val pm = getSystemService(PowerManager::class.java) ?: return
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            viewModel.emitMessage("La app ya está exenta de optimización de batería")
            return
        }
        runCatching {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                },
            )
        }
    }

    companion object {
        private const val STATE_PENDING_JS_PICK = "state_pending_js_pick"
    }
}
