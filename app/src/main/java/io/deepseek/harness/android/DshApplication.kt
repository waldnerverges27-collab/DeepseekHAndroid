package io.deepseek.harness.android

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import io.deepseek.harness.android.core.AppLogger
import io.deepseek.harness.android.core.AppPaths

/**
 * Punto de entrada global de la aplicación autónoma DeepSeek Harness para Android.
 * Inicializa el sistema de logs local y el canal de notificación del ForegroundService.
 */
class DshApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
        AppLogger.init(this)
        createNotificationChannels()
        AppLogger.info("App", "Aplicación inicializada (ABI=${Build.SUPPORTED_ABIS.joinToString()})")
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java) ?: return
            val engineChannel = NotificationChannel(
                AppPaths.NOTIFICATION_CHANNEL_ENGINE,
                getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.notif_channel_desc)
                setShowBadge(false)
            }
            manager.createNotificationChannel(engineChannel)
        }
    }

    companion object {
        @Volatile
        lateinit var instance: DshApplication
            private set
    }
}
