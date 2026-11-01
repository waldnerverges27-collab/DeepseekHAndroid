package io.deepseek.harness.android.engine

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import io.deepseek.harness.android.MainActivity
import io.deepseek.harness.android.R
import io.deepseek.harness.android.container.RootfsExtractor
import io.deepseek.harness.android.core.AppLogger
import io.deepseek.harness.android.core.AppPaths
import io.deepseek.harness.android.core.AppPreferences
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Servicio en primer plano (`ForegroundService`) responsable de mantener vivo el motor
 * `deepseek-harness` (`dsh`) en segundo plano y supervisarlo mediante un Watchdog.
 *
 * Acciones de notificación persistente:
 * - **Abrir**: trae [MainActivity] al frente.
 * - **Reiniciar**: reinicia limpiamente el motor `dsh` y restablece el contador de fallos.
 * - **Detener**: detiene el motor `dsh`, desarma el watchdog y cierra el servicio.
 *
 * Watchdog:
 * - Sondea cada 5 segundos el proceso hijo y el endpoint `http://127.0.0.1:<port>/`.
 * - Respeta la ventana de arranque en frío de Node.js + Cordis (`75s`).
 * - Si el motor cae inesperadamente, lo relanza automáticamente hasta un máximo de
 *   [MAX_CONSECUTIVE_CRASHES] fallos consecutivos rápidos (evitando drenar batería si el
 *   rootfs fue alterado manualmente), restableciendo el contador tras 45s de estabilidad.
 */
class DshForegroundService : Service() {

    private val workerExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "dsh-service-worker").apply { isDaemon = true }
    }
    private var watchdogScheduler: ScheduledExecutorService? = null
    private var cpuWakeLock: PowerManager.WakeLock? = null

    private val stoppedByUser = AtomicBoolean(false)

    @Volatile
    private var totalRestarts: Int = 0

    @Volatile
    private var consecutiveCrashes: Int = 0

    @Volatile
    private var healthySinceMs: Long = 0L

    override fun onCreate() {
        super.onCreate()
        promoteToForeground(
            title = getString(R.string.notif_title_starting),
            contentText = "Preparando contenedor PRoot y motor dsh…",
        )
        acquirePartialWakeLock()
        armWatchdog()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                AppLogger.info("Service", "Acción DETENER solicitada por el usuario.")
                stoppedByUser.set(true)
                workerExecutor.execute {
                    DshEngineController.stopEngine()
                    EngineStateStore.update(EngineLifecycleState.Stopped)
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
                return START_NOT_STICKY
            }

            ACTION_RESTART -> {
                AppLogger.info("Service", "Acción REINICIAR solicitada por el usuario.")
                stoppedByUser.set(false)
                consecutiveCrashes = 0
                healthySinceMs = 0L
                val port = AppPreferences(this).enginePort
                EngineStateStore.update(EngineLifecycleState.StartingEngine(port = port, attempt = 1))
                updateNotification(
                    title = getString(R.string.notif_title_restarting),
                    contentText = "Reiniciando servidor web en 127.0.0.1:$port…",
                )
                workerExecutor.execute {
                    DshEngineController.startEngine(this, forceRestart = true)
                }
                return START_STICKY
            }

            else -> {
                stoppedByUser.set(false)
                workerExecutor.execute {
                    ensureEngineRunningIfRootfsReady()
                }
                return START_STICKY
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        watchdogScheduler?.shutdownNow()
        watchdogScheduler = null
        workerExecutor.shutdownNow()
        releasePartialWakeLock()
        if (stoppedByUser.get()) {
            DshEngineController.stopEngine()
        }
        super.onDestroy()
    }

    private fun ensureEngineRunningIfRootfsReady() {
        if (stoppedByUser.get()) return
        if (!RootfsExtractor.isRootfsReady(this)) {
            AppLogger.info("Service", "Rootfs aún no extraído; esperando a que finalice el asistente inicial.")
            return
        }

        val port = AppPreferences(this).enginePort
        val rootfs = AppPaths.rootfsDir(this)
        val probe = DshHealthProbe.probe(port = port, rootfsDir = rootfs)

        if (probe.reachable) {
            DshAuthCookieMinter.syncCookiesToWebView(rootfs, port)
            val targetUrl = resolveBestWebUrl(rootfs, port)
            EngineStateStore.update(
                EngineLifecycleState.Running(
                    port = port,
                    webUrl = targetUrl,
                    latencyMs = probe.latencyMs,
                    restartCount = totalRestarts,
                ),
            )
            updateNotification(
                title = getString(R.string.notif_title_running),
                contentText = "Escuchando en 127.0.0.1:$port (reinicios: $totalRestarts)",
            )
            return
        }

        if (!DshEngineController.isWithinBootGraceWindow()) {
            EngineStateStore.update(EngineLifecycleState.StartingEngine(port = port, attempt = 1))
            updateNotification(
                title = getString(R.string.notif_title_starting),
                contentText = "Arrancando motor Node.js + dsh en puerto $port…",
            )
            DshEngineController.startEngine(this, forceRestart = false)
        }
    }

    private fun armWatchdog() {
        if (watchdogScheduler != null) return
        watchdogScheduler = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "dsh-watchdog").apply { isDaemon = true }
        }.also { scheduler ->
            scheduler.scheduleWithFixedDelay(
                { runWatchdogTick() },
                WATCHDOG_INTERVAL_SECONDS,
                WATCHDOG_INTERVAL_SECONDS,
                TimeUnit.SECONDS,
            )
        }
    }

    private fun runWatchdogTick() {
        try {
            if (stoppedByUser.get()) return
            if (!RootfsExtractor.isRootfsReady(this)) return

            val port = AppPreferences(this).enginePort
            val rootfs = AppPaths.rootfsDir(this)
            val probe = DshHealthProbe.probe(port = port, rootfsDir = rootfs)
            val now = System.currentTimeMillis()

            if (probe.reachable) {
                if (healthySinceMs == 0L) {
                    healthySinceMs = now
                } else if (now - healthySinceMs >= STABLE_RESET_WINDOW_MS && consecutiveCrashes > 0) {
                    consecutiveCrashes = 0
                }

                DshAuthCookieMinter.syncCookiesToWebView(rootfs, port)
                val webUrl = resolveBestWebUrl(rootfs, port)
                val current = EngineStateStore.state.value
                if (current !is EngineLifecycleState.Running || current.port != port) {
                    AppLogger.info("Watchdog", "Puerto $port disponible (${probe.latencyMs} ms)")
                    updateNotification(
                        title = getString(R.string.notif_title_running),
                        contentText = "Activo en 127.0.0.1:$port • Contenedor PRoot compartido",
                    )
                }
                EngineStateStore.update(
                    EngineLifecycleState.Running(
                        port = port,
                        webUrl = webUrl,
                        latencyMs = probe.latencyMs,
                        restartCount = totalRestarts,
                    ),
                )
                return
            }

            // Si está dentro de la ventana normal de arranque en frío y el proceso sigue vivo, esperar
            if (DshEngineController.isWithinBootGraceWindow(now)) {
                return
            }

            healthySinceMs = 0L
            consecutiveCrashes++

            if (consecutiveCrashes > MAX_CONSECUTIVE_CRASHES) {
                val msg = "El motor se detuvo $consecutiveCrashes veces seguidas. Revisa los logs o pulsa Reiniciar."
                AppLogger.error("Watchdog", msg)
                EngineStateStore.update(
                    EngineLifecycleState.Error(
                        title = "Watchdog en pausa por fallos repetidos",
                        detail = msg,
                        recoverable = true,
                    ),
                )
                updateNotification(
                    title = getString(R.string.notif_title_error),
                    contentText = "Pulsa Reiniciar tras revisar los logs",
                )
                stoppedByUser.set(true)
                return
            }

            totalRestarts++
            AppLogger.warn(
                "Watchdog",
                "Motor dsh caído o sin respuesta en 127.0.0.1:$port; reiniciando automáticamente (intento #$consecutiveCrashes, total=$totalRestarts)",
            )
            EngineStateStore.update(
                EngineLifecycleState.StartingEngine(
                    port = port,
                    attempt = consecutiveCrashes,
                ),
            )
            updateNotification(
                title = getString(R.string.notif_title_restarting),
                contentText = "Watchdog relanzando motor (intento $consecutiveCrashes/$MAX_CONSECUTIVE_CRASHES)…",
            )
            DshEngineController.startEngine(this, forceRestart = true)
        } catch (t: Throwable) {
            AppLogger.error("Watchdog", "Excepción capturada en ciclo de watchdog", t)
        }
    }

    private fun resolveBestWebUrl(rootfs: java.io.File, port: Int): String {
        val hasMintedCookie = DshAuthCookieMinter.findBrowserSessionSecret(rootfs) != null
        if (hasMintedCookie) {
            return DshHealthProbe.baseUrl(port)
        }
        return EngineStateStore.latestTokenAuthUrl ?: DshHealthProbe.baseUrl(port)
    }

    private fun promoteToForeground(title: String, contentText: String) {
        val notification = buildNotification(title, contentText)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(title: String, contentText: String) {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.notify(NOTIFICATION_ID, buildNotification(title, contentText))
    }

    private fun buildNotification(title: String, contentText: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPending = PendingIntent.getActivity(
            this,
            REQ_OPEN,
            openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val restartIntent = Intent(this, DshForegroundService::class.java).apply {
            action = ACTION_RESTART
        }
        val restartPending = PendingIntent.getService(
            this,
            REQ_RESTART,
            restartIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val stopIntent = Intent(this, DshForegroundService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPending = PendingIntent.getService(
            this,
            REQ_STOP,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        return NotificationCompat.Builder(this, AppPaths.NOTIFICATION_CHANNEL_ENGINE)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(contentText)
            .setContentIntent(openPending)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, getString(R.string.notif_action_open), openPending)
            .addAction(0, getString(R.string.notif_action_restart), restartPending)
            .addAction(0, getString(R.string.notif_action_stop), stopPending)
            .build()
    }

    private fun acquirePartialWakeLock() {
        try {
            val pm = getSystemService(PowerManager::class.java) ?: return
            cpuWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DeepSeekHarness::EngineWakeLock").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (e: Exception) {
            AppLogger.warn("Service", "No se pudo adquirir WakeLock parcial", e)
        }
    }

    private fun releasePartialWakeLock() {
        try {
            cpuWakeLock?.let { wl ->
                if (wl.isHeld) wl.release()
            }
        } catch (_: Exception) {
        } finally {
            cpuWakeLock = null
        }
    }

    companion object {
        const val NOTIFICATION_ID = 3080
        const val ACTION_START = "io.deepseek.harness.android.action.START_ENGINE"
        const val ACTION_RESTART = "io.deepseek.harness.android.action.RESTART_ENGINE"
        const val ACTION_STOP = "io.deepseek.harness.android.action.STOP_ENGINE"

        private const val REQ_OPEN = 100
        private const val REQ_RESTART = 101
        private const val REQ_STOP = 102

        private const val WATCHDOG_INTERVAL_SECONDS = 5L
        private const val STABLE_RESET_WINDOW_MS = 45_000L
        private const val MAX_CONSECUTIVE_CRASHES = 8

        fun start(context: Context) {
            val intent = Intent(context, DshForegroundService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun restart(context: Context) {
            val intent = Intent(context, DshForegroundService::class.java).apply {
                action = ACTION_RESTART
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, DshForegroundService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }
}
