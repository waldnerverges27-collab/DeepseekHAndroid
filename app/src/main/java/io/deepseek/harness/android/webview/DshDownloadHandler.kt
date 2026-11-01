package io.deepseek.harness.android.webview

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.URLUtil
import io.deepseek.harness.android.core.AppLogger
import io.deepseek.harness.android.core.AppPaths
import io.deepseek.harness.android.engine.DshAuthCookieMinter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Descarga en segundo plano archivos exportados desde la interfaz web de `dsh`
 * (ej. `/api/session.export` o artefactos generados por el agente) hacia la carpeta
 * pública `Downloads` mediante `MediaStore` sin requerir permisos adicionales en Android 10+.
 *
 * Incluye la cookie de sesión firmada `dsh-auth-*` y omite cabeceras de navegación cruzada
 * (`Origin: null`) que provocarían rechazo HTTP 403 en el servidor local de `dsh`.
 */
class DshDownloadHandler(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onDownloadFinished: (success: Boolean, message: String) -> Unit,
) {

    private val activeDownload = AtomicBoolean(false)

    fun enqueueEngineDownload(
        url: String,
        contentDisposition: String?,
        mimeType: String?,
        port: Int,
    ) {
        if (!activeDownload.compareAndSet(false, true)) return

        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
                        .ifBlank { "dsh-export-${System.currentTimeMillis()}.json" }
                    val safeMime = mimeType?.ifBlank { null } ?: "application/octet-stream"

                    val rootfs = AppPaths.rootfsDir(context)
                    val cookie = DshAuthCookieMinter.findBrowserSessionSecret(rootfs)?.let { secret ->
                        DshAuthCookieMinter.mintCookie(secret, "127.0.0.1:$port")
                    }

                    val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                        requestMethod = "GET"
                        connectTimeout = 15_000
                        readTimeout = 60_000
                        instanceFollowRedirects = false
                        if (cookie != null) {
                            setRequestProperty("Cookie", cookie)
                        }
                    }

                    try {
                        val code = conn.responseCode
                        if (code !in 200..299) {
                            error("HTTP $code al descargar recurso del motor")
                        }
                        conn.inputStream.use { input ->
                            saveToPublicDownloads(fileName, safeMime) { out ->
                                input.copyTo(out, bufferSize = 64 * 1024)
                            }
                        }
                        fileName
                    } finally {
                        conn.disconnect()
                    }
                }
            }

            activeDownload.set(false)
            result.fold(
                onSuccess = { name ->
                    AppLogger.info("Download", "Guardado en Descargas: $name")
                    onDownloadFinished(true, "Guardado en Descargas: $name")
                },
                onFailure = { err ->
                    AppLogger.error("Download", "Fallo al descargar archivo de dsh", err)
                    onDownloadFinished(false, "Error en descarga: ${err.message}")
                },
            )
        }
    }

    private fun saveToPublicDownloads(
        fileName: String,
        mimeType: String,
        writer: (java.io.OutputStream) -> Unit,
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, mimeType)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("MediaStore devolvió URI nulo")
            try {
                resolver.openOutputStream(uri)?.use(writer)
                    ?: error("No se pudo abrir flujo de escritura en MediaStore")
                val ready = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
                resolver.update(uri, ready, null, null)
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                throw e
            }
        } else {
            @Suppress("DEPRECATION")
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            dir.mkdirs()
            File(dir, fileName).outputStream().use(writer)
        }
    }
}
