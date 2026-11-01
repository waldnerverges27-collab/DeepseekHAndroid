package io.deepseek.harness.android.terminal

import android.content.Context
import com.termux.terminal.TerminalSession
import io.deepseek.harness.android.container.ProotCommandBuilder
import io.deepseek.harness.android.container.ProotEnvironment
import io.deepseek.harness.android.core.AppLogger
import io.deepseek.harness.android.core.AppPaths
import io.deepseek.harness.android.core.AppPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ManagedTerminalTab(
    val id: String,
    val label: String,
    val session: TerminalSession,
    val initialDirectory: String,
)

/**
 * Registro persistente en memoria de sesiones PTY `bash` ejecutadas dentro del contenedor PRoot.
 *
 * Al vivir a nivel de proceso de la aplicación:
 * - Las sesiones `bash` sobreviven rotaciones de pantalla, cambios entre la pestaña WebView
 *   y la pestaña Terminal, o aperturas/cierres del panel dividido inferior.
 * - Cada sesión usa [ProotCommandBuilder.buildInteractiveBashSpec], compartiendo exactamente
 *   el mismo rootfs, montajes `/mnt/saf` y entorno glibc que el motor `deepseek-harness`.
 */
object TerminalSessionRegistry {

    private const val TRANSCRIPT_ROWS = 4_000

    val extraKeysController = ExtraKeysController()

    private val _tabs = MutableStateFlow<List<ManagedTerminalTab>>(emptyList())
    val tabs: StateFlow<List<ManagedTerminalTab>> = _tabs.asStateFlow()

    private val _activeTabId = MutableStateFlow<String?>(null)
    val activeTabId: StateFlow<String?> = _activeTabId.asStateFlow()

    private val _screenGeneration = MutableStateFlow(0L)
    val screenGeneration: StateFlow<Long> = _screenGeneration.asStateFlow()

    private var sessionCounter = 0
    private var sharedClient: DshTerminalClient? = null

    fun getOrCreateClient(
        context: Context,
        onFontSizeDelta: (Float) -> Float,
    ): DshTerminalClient {
        val existing = sharedClient
        if (existing != null) return existing
        return DshTerminalClient(
            appContext = context.applicationContext,
            extraKeys = extraKeysController,
            onScreenUpdateCallback = { _screenGeneration.value = _screenGeneration.value + 1L },
            onTitleChangedCallback = { _screenGeneration.value = _screenGeneration.value + 1L },
            onSessionFinishedCallback = { finished ->
                removeFinishedOrClose(finished.mHandle)
            },
            onFontSizeScale = onFontSizeDelta,
        ).also { sharedClient = it }
    }

    /**
     * Crea y activa una nueva sesión `bash --login` dentro del contenedor PRoot compartido.
     */
    fun createSession(
        context: Context,
        guestWorkingDir: String = AppPaths.GUEST_WORKSPACE,
        initialCommand: String? = null,
        onFontSizeDelta: (Float) -> Float = { 1f },
    ): Result<ManagedTerminalTab> = runCatching {
        val appCtx = context.applicationContext
        val config = ProotEnvironment.prepare(appCtx)
        val port = AppPreferences(appCtx).enginePort
        val spec = ProotCommandBuilder.buildInteractiveBashSpec(
            config = config,
            guestWorkingDir = guestWorkingDir,
            port = port,
        )

        val client = getOrCreateClient(appCtx, onFontSizeDelta)
        sessionCounter++
        val label = "bash #$sessionCounter"

        val session = TerminalSession(
            spec.executablePath,
            spec.workingDirectoryHost,
            spec.argvArray,
            spec.hostEnvArray,
            TRANSCRIPT_ROWS,
            client,
        ).apply {
            mSessionName = label
        }

        val tab = ManagedTerminalTab(
            id = session.mHandle,
            label = label,
            session = session,
            initialDirectory = guestWorkingDir,
        )

        _tabs.value = _tabs.value + tab
        _activeTabId.value = tab.id

        if (!initialCommand.isNullOrBlank()) {
            // Enviar comando inicial en cuanto el PTY se inicialice
            Thread({
                try {
                    var waited = 0
                    while (session.emulator == null && waited < 30) {
                        Thread.sleep(100)
                        waited++
                    }
                    if (session.emulator != null) {
                        session.write("$initialCommand\n")
                    }
                } catch (_: InterruptedException) {
                }
            }, "dsh-term-init-cmd").apply {
                isDaemon = true
                start()
            }
        }

        AppLogger.info("Terminal", "Abierta sesión PTY '$label' en contenedor PRoot (dir=$guestWorkingDir)")
        tab
    }

    fun selectTab(tabId: String) {
        if (_tabs.value.any { it.id == tabId }) {
            _activeTabId.value = tabId
        }
    }

    /**
     * Envía un comando `cd '<guestPath>'` a la sesión activa (o crea una nueva sesión en esa ruta).
     */
    fun openDirectoryInTerminal(
        context: Context,
        guestPath: String,
        onFontSizeDelta: (Float) -> Float = { 1f },
    ) {
        val current = activeSession()
        if (current != null && current.isRunning) {
            val escaped = guestPath.replace("'", "'\\''")
            current.write("cd '$escaped' && pwd && ls -la\n")
        } else {
            createSession(
                context = context,
                guestWorkingDir = guestPath,
                initialCommand = "pwd && ls -la",
                onFontSizeDelta = onFontSizeDelta,
            )
        }
    }

    fun activeSession(): TerminalSession? {
        val activeId = _activeTabId.value
        val list = _tabs.value
        return list.firstOrNull { it.id == activeId }?.session ?: list.firstOrNull()?.session
    }

    fun closeSession(tabId: String) {
        val target = _tabs.value.firstOrNull { it.id == tabId } ?: return
        try {
            if (target.session.isRunning) {
                target.session.finishIfRunning()
            }
        } catch (_: Exception) {
        }
        removeFinishedOrClose(tabId)
    }

    private fun removeFinishedOrClose(tabId: String) {
        val remaining = _tabs.value.filterNot { it.id == tabId }
        _tabs.value = remaining
        if (_activeTabId.value == tabId) {
            _activeTabId.value = remaining.lastOrNull()?.id
        }
    }
}
