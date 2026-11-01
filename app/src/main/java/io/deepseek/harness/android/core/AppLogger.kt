package io.deepseek.harness.android.core

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Registrador de eventos con buffer circular en memoria (observable por UI Compose)
 * y volcado persistente a disco con rotación simple.
 */
object AppLogger {

    private const val TAG = "DeepSeekHarness"
    private const val MAX_LINES = 400
    private const val MAX_FILE_BYTES = 1024L * 1024L // 1 MB

    private val lock = Any()
    private val ringBuffer = ArrayDeque<String>(MAX_LINES)
    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines.asStateFlow()

    @Volatile
    private var logFile: File? = null

    fun init(context: Context) {
        logFile = File(AppPaths.logsDir(context), "app-events.log")
    }

    fun info(scope: String, message: String) {
        Log.i(TAG, "[$scope] $message")
        append("INFO", scope, message, null)
    }

    fun warn(scope: String, message: String, error: Throwable? = null) {
        Log.w(TAG, "[$scope] $message", error)
        append("WARN", scope, message, error)
    }

    fun error(scope: String, message: String, error: Throwable? = null) {
        Log.e(TAG, "[$scope] $message", error)
        append("ERROR", scope, message, error)
    }

    fun clear() {
        synchronized(lock) {
            ringBuffer.clear()
            _lines.value = emptyList()
            try {
                logFile?.writeText("")
            } catch (_: Exception) {
            }
        }
    }

    fun exportAllText(context: Context): String {
        val sb = StringBuilder()
        sb.appendLine("=== Eventos de la Aplicación ===")
        synchronized(lock) {
            ringBuffer.forEach { sb.appendLine(it) }
        }
        val engineLog = AppPaths.engineLogFile(context)
        if (engineLog.isFile) {
            sb.appendLine()
            sb.appendLine("=== Últimas líneas de dsh-engine.log ===")
            try {
                val tail = engineLog.readLines().takeLast(150)
                tail.forEach { sb.appendLine(it) }
            } catch (e: Exception) {
                sb.appendLine("(Error leyendo dsh-engine.log: ${e.message})")
            }
        }
        return sb.toString()
    }

    private fun append(level: String, scope: String, message: String, error: Throwable?) {
        val ts = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
        val suffix = if (error != null) " | ${error.javaClass.simpleName}: ${error.message}" else ""
        val formatted = "$ts [$level][$scope] $message$suffix"

        synchronized(lock) {
            if (ringBuffer.size >= MAX_LINES) {
                ringBuffer.removeFirst()
            }
            ringBuffer.addLast(formatted)
            _lines.value = ringBuffer.toList()

            val file = logFile ?: return
            try {
                if (file.exists() && file.length() > MAX_FILE_BYTES) {
                    val backup = File(file.parentFile, "app-events.log.1")
                    backup.delete()
                    file.renameTo(backup)
                }
                FileOutputStream(file, true).bufferedWriter(Charsets.UTF_8).use { writer ->
                    writer.appendLine(formatted)
                }
            } catch (_: Exception) {
                // No interrumpir ejecución si el almacenamiento está lleno
            }
        }
    }
}
