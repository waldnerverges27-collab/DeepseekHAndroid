package io.deepseek.harness.android.engine

import android.content.Context
import io.deepseek.harness.android.container.ProotCommandBuilder
import io.deepseek.harness.android.container.ProotEnvironment
import io.deepseek.harness.android.core.AppLogger
import io.deepseek.harness.android.core.AppPaths
import io.deepseek.harness.android.core.AppPreferences
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Controlador a nivel de proceso del motor `deepseek-harness` (`dsh web`) ejecutado dentro de PRoot.
 *
 * Garantías de concurrencia y ciclo de vida:
 * - Protección CAS (`startingCas`) + ventana de arranque en frío (`COLD_BOOT_GRACE_MS = 75s`)
 *   para evitar arranques duplicados (`EADDRINUSE` en puerto 3080).
 * - Lector asíncrono de salida estándar/error que vuelca a `dsh-engine.log` y captura en tiempo
 *   real cualquier URL con `?token=...` impresa por `dsh web`.
 * - Fallback automático a `/system/bin/linker64` si `execve` directo recibe `Permission denied`.
 * - Cierre ordenado (`SIGTERM` -> `SIGKILL` con `--kill-on-exit` en proot) para no corromper
 *   sesiones ni archivos YAML en `/root/.dsh`.
 */
object DshEngineController {

    /** Margen para el arranque en frío de Node.js + árbol de plugins Cordis en ARM64 móvil. */
    const val COLD_BOOT_GRACE_MS = 75_000L

    /** Token compartido entre el motor y el puente JS del WebView durante la vida del proceso. */
    val bridgeSessionToken: String = UUID.randomUUID().toString()

    private val startingCas = AtomicBoolean(false)

    @Volatile
    private var engineProcess: Process? = null

    @Volatile
    var lastStartEpochMs: Long = 0L
        private set

    fun isProcessAlive(): Boolean = engineProcess?.isAlive == true

    fun isWithinBootGraceWindow(nowMs: Long = System.currentTimeMillis()): Boolean =
        isProcessAlive() && (nowMs - lastStartEpochMs) in 0 until COLD_BOOT_GRACE_MS

    /**
     * Inicia el proceso `proot ... dsh web --port <port>` si no está ya iniciándose o vivo.
     */
    fun startEngine(context: Context, forceRestart: Boolean = false): Boolean {
        if (!startingCas.compareAndSet(false, true)) {
            AppLogger.info("Engine", "Arranque ya en curso; omitiendo llamada concurrente.")
            return true
        }

        try {
            val appCtx = context.applicationContext
            val prefs = AppPreferences(appCtx)
            val port = prefs.enginePort
            val now = System.currentTimeMillis()

            if (!forceRestart && isWithinBootGraceWindow(now)) {
                return true
            }

            // Si había un proceso colgado fuera de la ventana de gracia o se pidió reinicio forzado, terminarlo
            terminateCurrentProcessLocked()

            val config = ProotEnvironment.prepare(appCtx)
            val spec = ProotCommandBuilder.buildEngineSpec(
                config = config,
                port = port,
                pickToken = bridgeSessionToken,
            )

            EngineStateStore.latestTokenAuthUrl = null
            val proc = spawnWithLinkerFallback(spec.command, spec.hostEnvironment, config.rootfsDir)
            engineProcess = proc
            lastStartEpochMs = System.currentTimeMillis()

            startLogPumpThread(appCtx, proc, port)
            AppLogger.info(
                "Engine",
                "Motor dsh lanzado en contenedor PRoot (puerto=$port, bin=${config.guestDshEntry})",
            )
            return true
        } catch (t: Throwable) {
            AppLogger.error("Engine", "Fallo al iniciar el motor dsh", t)
            return false
        } finally {
            startingCas.set(false)
        }
    }

    /**
     * Detiene ordenadamente el proceso del motor `dsh` y libera el puerto 3080.
     */
    fun stopEngine() {
        var spins = 0
        while (!startingCas.compareAndSet(false, true) && spins < 30) {
            Thread.sleep(100)
            spins++
        }
        try {
            terminateCurrentProcessLocked()
            lastStartEpochMs = 0L
            AppLogger.info("Engine", "Motor dsh detenido correctamente.")
        } finally {
            startingCas.set(false)
        }
    }

    private fun terminateCurrentProcessLocked() {
        val proc = engineProcess ?: return
        try {
            if (proc.isAlive) {
                proc.destroy()
                if (!proc.waitFor(4, TimeUnit.SECONDS)) {
                    AppLogger.warn("Engine", "El motor no respondió a SIGTERM en 4s; enviando SIGKILL")
                    proc.destroyForcibly()
                    proc.waitFor(2, TimeUnit.SECONDS)
                }
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            engineProcess = null
        }
    }

    private fun spawnWithLinkerFallback(
        command: List<String>,
        hostEnv: Map<String, String>,
        workDir: java.io.File,
    ): Process {
        fun buildPb(argv: List<String>) = ProcessBuilder(argv).apply {
            directory(workDir)
            environment().putAll(hostEnv)
            redirectErrorStream(true)
        }

        return try {
            buildPb(command).start()
        } catch (e: IOException) {
            if (e.message?.contains("Permission denied", ignoreCase = true) == true &&
                command.firstOrNull() != ProotCommandBuilder.LINKER64_PATH
            ) {
                AppLogger.warn("Engine", "execve directo denegado; relanzando con /system/bin/linker64")
                ProotEnvironment.forceLinker64Fallback = true
                buildPb(listOf(ProotCommandBuilder.LINKER64_PATH) + command).start()
            } else {
                throw e
            }
        }
    }

    private fun startLogPumpThread(context: Context, process: Process, port: Int) {
        val logFile = AppPaths.engineLogFile(context)
        Thread({
            try {
                FileOutputStream(logFile, true).bufferedWriter(Charsets.UTF_8).use { writer ->
                    writer.appendLine("--- Inicio de sesión dsh (${java.util.Date()}) ---")
                    writer.flush()
                    process.inputStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                        lines.forEach { line ->
                            writer.appendLine(line)
                            writer.flush()
                            val authUrl = DshAuthCookieMinter.extractTokenUrlFromLogLine(line, port)
                            if (authUrl != null) {
                                EngineStateStore.latestTokenAuthUrl = authUrl
                                AppLogger.info("EngineAuth", "Detectada URL autenticada de dsh web en puerto $port")
                            }
                            if (line.contains("error", ignoreCase = true) ||
                                line.contains("listening", ignoreCase = true) ||
                                line.contains("3080")
                            ) {
                                AppLogger.info("dsh-stdout", line.take(240))
                            }
                        }
                    }
                }
            } catch (_: Exception) {
                // El flujo se cierra normalmente al detener el proceso
            }
        }, "dsh-engine-log-pump").apply {
            isDaemon = true
            start()
        }
    }
}
