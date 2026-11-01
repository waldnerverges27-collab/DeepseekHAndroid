package io.deepseek.harness.android.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.deepseek.harness.android.container.ContainerSmokeTester
import io.deepseek.harness.android.container.ProotEnvironment
import io.deepseek.harness.android.container.RootfsExtractor
import io.deepseek.harness.android.core.AppLogger
import io.deepseek.harness.android.core.AppPaths
import io.deepseek.harness.android.core.AppPreferences
import io.deepseek.harness.android.engine.DshAuthCookieMinter
import io.deepseek.harness.android.engine.DshForegroundService
import io.deepseek.harness.android.engine.DshHealthProbe
import io.deepseek.harness.android.engine.EngineLifecycleState
import io.deepseek.harness.android.engine.EngineStateStore
import io.deepseek.harness.android.saf.SafBridgeManager
import io.deepseek.harness.android.saf.SafMountEntry
import io.deepseek.harness.android.terminal.TerminalSessionRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

enum class WorkspaceTab {
    WEB,
    TERMINAL,
    SAF,
    DIAGNOSTICS,
}

/**
 * ViewModel coordinador principal:
 * - Ejecuta el flujo de arranque inicial (verificación/extracción del rootfs -> configuración
 *   de PRoot y SAF -> prueba de humo del contenedor -> arranque del `ForegroundService` ->
 *   sondeo del puerto 3080).
 * - Coordina las pestañas de interfaz, el modo de pantalla dividida WebView + Terminal,
 *   el puente SAF y las opciones de diagnóstico.
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val appContext = application.applicationContext
    val preferences = AppPreferences(appContext)
    val safManager = SafBridgeManager(appContext)

    val engineState: StateFlow<EngineLifecycleState> = EngineStateStore.state
    val safMounts: StateFlow<List<SafMountEntry>> = safManager.mounts
    val logLines: StateFlow<List<String>> = AppLogger.lines

    private val _selectedTab = MutableStateFlow(WorkspaceTab.WEB)
    val selectedTab: StateFlow<WorkspaceTab> = _selectedTab.asStateFlow()

    private val _splitTerminalOpen = MutableStateFlow(false)
    val splitTerminalOpen: StateFlow<Boolean> = _splitTerminalOpen.asStateFlow()

    private val _terminalFontSp = MutableStateFlow(preferences.terminalFontSizeSp)
    val terminalFontSp: StateFlow<Int> = _terminalFontSp.asStateFlow()

    private val _prootNoSeccomp = MutableStateFlow(preferences.prootNoSeccomp)
    val prootNoSeccomp: StateFlow<Boolean> = _prootNoSeccomp.asStateFlow()

    private val _toastMessage = MutableStateFlow<String?>(null)
    val toastMessage: StateFlow<String?> = _toastMessage.asStateFlow()

    private val bootInFlight = AtomicBoolean(false)

    init {
        startBootPipeline(forceReExtract = false)
    }

    fun selectTab(tab: WorkspaceTab) {
        _selectedTab.value = tab
        if (tab == WorkspaceTab.TERMINAL) {
            ensureAtLeastOneTerminalSession()
        }
    }

    fun toggleSplitTerminal() {
        val next = !_splitTerminalOpen.value
        _splitTerminalOpen.value = next
        if (next) {
            ensureAtLeastOneTerminalSession()
        }
    }

    fun adjustTerminalFontSize(scaleFactor: Float): Float {
        val delta = when {
            scaleFactor > 1.08f -> 1
            scaleFactor < 0.92f -> -1
            else -> return 1f
        }
        val updated = (_terminalFontSp.value + delta).coerceIn(9, 28)
        if (updated != _terminalFontSp.value) {
            _terminalFontSp.value = updated
            preferences.terminalFontSizeSp = updated
        }
        return 1f
    }

    fun stepTerminalFontSize(delta: Int) {
        val updated = (_terminalFontSp.value + delta).coerceIn(9, 28)
        _terminalFontSp.value = updated
        preferences.terminalFontSizeSp = updated
    }

    fun setProotNoSeccomp(enabled: Boolean) {
        _prootNoSeccomp.value = enabled
        preferences.prootNoSeccomp = enabled
        emitMessage("PROOT_NO_SECCOMP=${if (enabled) "1" else "0"} (reinicia el motor para aplicar)")
    }

    fun clearToastMessage() {
        _toastMessage.value = null
    }

    fun emitMessage(msg: String) {
        _toastMessage.value = msg
    }

    /**
     * Flujo completo de inicio (Requisitos 4.1 – 4.5):
     * 1. Verificar si el rootfs ya fue extraído (en inicios posteriores se reutiliza).
     * 2. Si no existe o cambió su versión, extraer `assets/rootfs.tar.xz` con progreso.
     * 3. Configurar el entorno PRoot (`resolv.conf`, `hosts`, preload Node.js, montajes SAF).
     * 4. Verificar el contenedor (`ContainerSmokeTester`) y lanzar `dsh` con `DshForegroundService`.
     * 5. Esperar hasta que el puerto 3080 responda para mostrar la `WebView`.
     */
    fun startBootPipeline(forceReExtract: Boolean = false) {
        if (!bootInFlight.compareAndSet(false, true)) return

        viewModelScope.launch {
            try {
                val port = preferences.enginePort
                val rootfs = AppPaths.rootfsDir(appContext)

                // Vía rápida: si el motor ya está respondiendo y no se pidió reextraer
                if (!forceReExtract && RootfsExtractor.isRootfsReady(appContext)) {
                    val quickProbe = withContext(Dispatchers.IO) {
                        safManager.restorePersistedMounts()
                        ProotEnvironment.prepare(appContext)
                        DshHealthProbe.probe(port = port, rootfsDir = rootfs)
                    }
                    if (quickProbe.reachable) {
                        DshAuthCookieMinter.syncCookiesToWebView(rootfs, port)
                        DshForegroundService.start(appContext)
                        EngineStateStore.update(
                            EngineLifecycleState.Running(
                                port = port,
                                webUrl = resolveInitialWebUrl(port),
                                latencyMs = quickProbe.latencyMs,
                                restartCount = 0,
                            ),
                        )
                        return@launch
                    }
                }

                if (forceReExtract) {
                    withContext(Dispatchers.IO) {
                        DshForegroundService.stop(appContext)
                        delay(600)
                    }
                }

                // Paso 1 & 2: Verificar / Extraer rootfs desde assets con progreso
                EngineStateStore.update(EngineLifecycleState.CheckingEnvironment)
                val needsExtract = withContext(Dispatchers.IO) {
                    forceReExtract || !RootfsExtractor.isRootfsReady(appContext)
                }

                if (needsExtract) {
                    withContext(Dispatchers.IO) {
                        RootfsExtractor.ensureExtracted(
                            context = appContext,
                            force = forceReExtract,
                            onProgress = { progress ->
                                EngineStateStore.update(EngineLifecycleState.ExtractingRootfs(progress))
                            },
                        )
                    }
                }

                // Paso 3: Configurar entorno PRoot + restaurar montajes SAF + prueba de humo
                EngineStateStore.update(EngineLifecycleState.VerifyingContainer)
                safManager.restorePersistedMounts()
                val smoke = withContext(Dispatchers.IO) {
                    ContainerSmokeTester.run(appContext)
                }
                if (!smoke.success) {
                    EngineStateStore.update(
                        EngineLifecycleState.Error(
                            title = "Fallo al verificar contenedor PRoot",
                            detail = smoke.output,
                            recoverable = true,
                        ),
                    )
                    return@launch
                }

                // Paso 4: Lanzar motor dsh en segundo plano mediante ForegroundService
                EngineStateStore.update(EngineLifecycleState.StartingEngine(port = port, attempt = 1))
                DshForegroundService.start(appContext)

                // Paso 5: Sondear puerto 3080 hasta que esté disponible (máx. 90s en arranque frío)
                val deadlineMs = System.currentTimeMillis() + 90_000L
                while (System.currentTimeMillis() < deadlineMs) {
                    val probe = withContext(Dispatchers.IO) {
                        DshHealthProbe.probe(port = port, rootfsDir = rootfs)
                    }
                    if (probe.reachable) {
                        DshAuthCookieMinter.syncCookiesToWebView(rootfs, port)
                        EngineStateStore.update(
                            EngineLifecycleState.Running(
                                port = port,
                                webUrl = resolveInitialWebUrl(port),
                                latencyMs = probe.latencyMs,
                                restartCount = 0,
                            ),
                        )
                        return@launch
                    }
                    delay(1_000L)
                }

                EngineStateStore.update(
                    EngineLifecycleState.Error(
                        title = "Tiempo de espera agotado en puerto $port",
                        detail = "El motor dsh tardó más de 90s en abrir 127.0.0.1:$port. Revisa la pestaña Diagnóstico o abre la Terminal para ejecutar dsh-doctor.",
                        recoverable = true,
                    ),
                )
            } catch (t: Throwable) {
                AppLogger.error("Boot", "Fallo en flujo de inicio", t)
                EngineStateStore.update(
                    EngineLifecycleState.Error(
                        title = "Error inicializando el entorno",
                        detail = t.message ?: t.javaClass.simpleName,
                        recoverable = true,
                    ),
                )
            } finally {
                bootInFlight.set(false)
            }
        }
    }

    fun restartEngine() {
        val port = preferences.enginePort
        EngineStateStore.update(EngineLifecycleState.StartingEngine(port = port, attempt = 1))
        DshForegroundService.restart(appContext)
        emitMessage("Reiniciando motor dsh…")
    }

    fun stopEngine() {
        DshForegroundService.stop(appContext)
        emitMessage("Deteniendo motor dsh y servicio en primer plano…")
    }

    fun ensureAtLeastOneTerminalSession() {
        if (TerminalSessionRegistry.tabs.value.isEmpty() && RootfsExtractor.isRootfsReady(appContext)) {
            createNewTerminalSession()
        }
    }

    fun createNewTerminalSession(
        workingDir: String = AppPaths.GUEST_WORKSPACE,
        initialCommand: String? = null,
    ) {
        val res = TerminalSessionRegistry.createSession(
            context = appContext,
            guestWorkingDir = workingDir,
            initialCommand = initialCommand,
            onFontSizeDelta = ::adjustTerminalFontSize,
        )
        res.onFailure { err ->
            emitMessage("No se pudo abrir sesión bash: ${err.message}")
        }
    }

    fun openGuestDirectoryInTerminal(guestPath: String) {
        TerminalSessionRegistry.openDirectoryInTerminal(
            context = appContext,
            guestPath = guestPath,
            onFontSizeDelta = ::adjustTerminalFontSize,
        )
        _selectedTab.value = WorkspaceTab.TERMINAL
    }

    fun onSafTreePicked(uri: Uri, onMounted: ((SafMountEntry) -> Unit)? = null) {
        viewModelScope.launch {
            val res = safManager.registerPickedTreeUri(uri)
            res.fold(
                onSuccess = { entry ->
                    // Si el usuario otorgó acceso de almacenamiento completo recientemente,
                    // actualizar configuración de PRoot para próximos subprocesos.
                    withContext(Dispatchers.IO) {
                        runCatching { ProotEnvironment.prepare(appContext) }
                    }
                    emitMessage("Montado en ${entry.guestPath}")
                    onMounted?.invoke(entry)
                },
                onFailure = { err ->
                    AppLogger.error("SAF", "Error montando carpeta externa", err)
                    emitMessage("Error SAF: ${err.message}")
                },
            )
        }
    }

    fun syncSafMount(alias: String) {
        viewModelScope.launch {
            safManager.syncMountBidirectional(alias).fold(
                onSuccess = { entry -> emitMessage("Sincronizado: ${entry.guestPath}") },
                onFailure = { err -> emitMessage("Error sincronizando: ${err.message}") },
            )
        }
    }

    fun unmountSaf(alias: String) {
        viewModelScope.launch {
            safManager.unmount(alias)
            emitMessage("Carpeta desmontada: /mnt/saf/$alias")
        }
    }

    private fun resolveInitialWebUrl(port: Int): String {
        val rootfs = AppPaths.rootfsDir(appContext)
        return if (DshAuthCookieMinter.findBrowserSessionSecret(rootfs) != null) {
            DshHealthProbe.baseUrl(port)
        } else {
            EngineStateStore.latestTokenAuthUrl ?: DshHealthProbe.baseUrl(port)
        }
    }
}
