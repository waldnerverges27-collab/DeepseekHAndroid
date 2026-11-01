package io.deepseek.harness.android.ui

import android.view.ViewGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.FolderShared
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Splitscreen
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.deepseek.harness.android.engine.EngineLifecycleState
import io.deepseek.harness.android.webview.DshWebViewHolder

/**
 * Contenedor visual principal de la aplicación:
 * integra la `WebView` del agente `dsh` (`http://127.0.0.1:3080`), el panel nativo de
 * terminal Linux (`bash` sobre PTY en el mismo PRoot), el modo dividido WebView + Terminal,
 * el puente SAF y el panel de diagnóstico.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainShellScreen(
    viewModel: MainViewModel,
    webViewHolder: DshWebViewHolder,
    onPickSafDirectory: () -> Unit,
    onRequestAllFilesAccess: () -> Unit,
    onRequestBatteryExemption: () -> Unit,
) {
    val engineState by viewModel.engineState.collectAsState()
    val selectedTab by viewModel.selectedTab.collectAsState()
    val splitTerminalOpen by viewModel.splitTerminalOpen.collectAsState()
    val terminalFontSp by viewModel.terminalFontSp.collectAsState()
    val safMounts by viewModel.safMounts.collectAsState()
    val logLines by viewModel.logLines.collectAsState()
    val prootNoSeccomp by viewModel.prootNoSeccomp.collectAsState()
    val toastMsg by viewModel.toastMessage.collectAsState()
    val webUiState by webViewHolder.uiState.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(toastMsg) {
        val msg = toastMsg ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(msg)
        viewModel.clearToastMessage()
    }

    // Cargar automáticamente la URL de dsh en cuanto el puerto 3080 esté disponible
    LaunchedEffect(engineState) {
        val running = engineState as? EngineLifecycleState.Running ?: return@LaunchedEffect
        webViewHolder.loadEngineUrlIfNeeded(running.webUrl)
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "DeepSeek Harness",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        val subtitle = when (val st = engineState) {
                            is EngineLifecycleState.Running -> "127.0.0.1:${st.port} • PRoot compartido activo"
                            is EngineLifecycleState.StartingEngine -> "Iniciando motor en puerto ${st.port}…"
                            is EngineLifecycleState.ExtractingRootfs -> "Instalando rootfs Linux…"
                            is EngineLifecycleState.Stopped -> "Motor detenido"
                            is EngineLifecycleState.Error -> "Atención requerida"
                            else -> "Inicializando…"
                        }
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    if (selectedTab == WorkspaceTab.WEB && engineState is EngineLifecycleState.Running) {
                        // Alternar vista dividida WebView (arriba) + Terminal bash (abajo)
                        IconButton(onClick = { viewModel.toggleSplitTerminal() }) {
                            Icon(
                                imageVector = Icons.Default.Splitscreen,
                                contentDescription = "Dividir pantalla con Terminal",
                                tint = if (splitTerminalOpen) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                            )
                        }
                        IconButton(
                            onClick = {
                                val url = (engineState as? EngineLifecycleState.Running)?.webUrl
                                    ?: "http://127.0.0.1:${viewModel.preferences.enginePort}"
                                webViewHolder.forceReloadEngine(url)
                            },
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = "Recargar Web UI")
                        }
                    }
                },
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = selectedTab == WorkspaceTab.WEB,
                    onClick = { viewModel.selectTab(WorkspaceTab.WEB) },
                    icon = { Icon(Icons.Default.Language, contentDescription = null) },
                    label = { Text("Agente Web") },
                )
                NavigationBarItem(
                    selected = selectedTab == WorkspaceTab.TERMINAL,
                    onClick = { viewModel.selectTab(WorkspaceTab.TERMINAL) },
                    icon = { Icon(Icons.Default.Terminal, contentDescription = null) },
                    label = { Text("Terminal") },
                )
                NavigationBarItem(
                    selected = selectedTab == WorkspaceTab.SAF,
                    onClick = { viewModel.selectTab(WorkspaceTab.SAF) },
                    icon = { Icon(Icons.Default.FolderShared, contentDescription = null) },
                    label = { Text("SAF") },
                )
                NavigationBarItem(
                    selected = selectedTab == WorkspaceTab.DIAGNOSTICS,
                    onClick = { viewModel.selectTab(WorkspaceTab.DIAGNOSTICS) },
                    icon = { Icon(Icons.Default.Build, contentDescription = null) },
                    label = { Text("Estado") },
                )
            }
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            when (selectedTab) {
                WorkspaceTab.WEB -> {
                    val runningState = engineState as? EngineLifecycleState.Running
                    if (runningState == null) {
                        BootSetupScreen(
                            state = engineState,
                            onRetryBoot = { viewModel.startBootPipeline(forceReExtract = false) },
                            onStartStoppedEngine = { viewModel.startBootPipeline(forceReExtract = false) },
                            onOpenTerminal = { viewModel.selectTab(WorkspaceTab.TERMINAL) },
                            onOpenDiagnostics = { viewModel.selectTab(WorkspaceTab.DIAGNOSTICS) },
                        )
                    } else {
                        Column(modifier = Modifier.fillMaxSize()) {
                            Box(
                                modifier = Modifier
                                    .weight(if (splitTerminalOpen) 0.55f else 1f)
                                    .fillMaxWidth(),
                            ) {
                                AndroidView(
                                    modifier = Modifier.fillMaxSize(),
                                    factory = {
                                        (webViewHolder.webView.parent as? ViewGroup)?.removeView(webViewHolder.webView)
                                        webViewHolder.webView
                                    },
                                )

                                if (webUiState.isLoading) {
                                    LinearProgressIndicator(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .align(Alignment.TopCenter),
                                    )
                                }

                                if (webUiState.loadErrorDescription != null) {
                                    Surface(
                                        modifier = Modifier.fillMaxSize(),
                                        color = MaterialTheme.colorScheme.background,
                                    ) {
                                        Column(
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .padding(24.dp),
                                            verticalArrangement = Arrangement.Center,
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                        ) {
                                            Text(
                                                text = "No se pudo cargar la interfaz web de dsh",
                                                style = MaterialTheme.typography.titleMedium,
                                                fontWeight = FontWeight.Bold,
                                            )
                                            Spacer(modifier = Modifier.height(8.dp))
                                            Text(
                                                text = webUiState.loadErrorDescription ?: "",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                            Spacer(modifier = Modifier.height(16.dp))
                                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                                Button(
                                                    onClick = { webViewHolder.forceReloadEngine(runningState.webUrl) },
                                                ) {
                                                    Text("Reintentar carga")
                                                }
                                                OutlinedButton(
                                                    onClick = { viewModel.restartEngine() },
                                                ) {
                                                    Text("Reiniciar motor")
                                                }
                                            }
                                            Spacer(modifier = Modifier.height(8.dp))
                                            OutlinedButton(
                                                onClick = { viewModel.selectTab(WorkspaceTab.TERMINAL) },
                                            ) {
                                                Icon(Icons.Default.Terminal, contentDescription = null)
                                                Spacer(Modifier.width(6.dp))
                                                Text("Abrir Terminal")
                                            }
                                        }
                                    }
                                }
                            }

                            if (splitTerminalOpen) {
                                HorizontalDivider(thickness = 2.dp)
                                TerminalPanel(
                                    fontSizeSp = terminalFontSp,
                                    onCreateSession = { viewModel.createNewTerminalSession() },
                                    onFontSizeScale = viewModel::adjustTerminalFontSize,
                                    onStepFontSize = viewModel::stepTerminalFontSize,
                                    onMessage = viewModel::emitMessage,
                                    modifier = Modifier
                                        .weight(0.45f)
                                        .fillMaxWidth(),
                                )
                            }
                        }
                    }
                }

                WorkspaceTab.TERMINAL -> {
                    TerminalPanel(
                        fontSizeSp = terminalFontSp,
                        onCreateSession = { viewModel.createNewTerminalSession() },
                        onFontSizeScale = viewModel::adjustTerminalFontSize,
                        onStepFontSize = viewModel::stepTerminalFontSize,
                        onMessage = viewModel::emitMessage,
                    )
                }

                WorkspaceTab.SAF -> {
                    SafMountScreen(
                        mounts = safMounts,
                        hasAllFilesAccess = viewModel.safManager.hasAllFilesAccess(),
                        onPickSafDirectory = onPickSafDirectory,
                        onRequestAllFilesAccess = onRequestAllFilesAccess,
                        onOpenInTerminal = { guestPath -> viewModel.openGuestDirectoryInTerminal(guestPath) },
                        onSyncMount = { alias -> viewModel.syncSafMount(alias) },
                        onUnmount = { alias -> viewModel.unmountSaf(alias) },
                        onMessage = viewModel::emitMessage,
                    )
                }

                WorkspaceTab.DIAGNOSTICS -> {
                    DiagnosticsScreen(
                        engineState = engineState,
                        logLines = logLines,
                        prootNoSeccomp = prootNoSeccomp,
                        onToggleProotNoSeccomp = viewModel::setProotNoSeccomp,
                        onStartOrRetry = { viewModel.startBootPipeline(forceReExtract = false) },
                        onRestartEngine = { viewModel.restartEngine() },
                        onStopEngine = { viewModel.stopEngine() },
                        onReExtractRootfs = { viewModel.startBootPipeline(forceReExtract = true) },
                        onRequestBatteryExemption = onRequestBatteryExemption,
                        onMessage = viewModel::emitMessage,
                    )
                }
            }
        }
    }
}
