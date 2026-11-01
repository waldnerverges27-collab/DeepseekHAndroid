package io.deepseek.harness.android.engine

import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class HealthProbeResult(
    val reachable: Boolean,
    val httpCode: Int,
    val latencyMs: Long,
    val error: String? = null,
)

/**
 * Sonda HTTP ligera contra `http://127.0.0.1:<port>/` para verificar la disponibilidad
 * del servidor web de `deepseek-harness`.
 * Debe invocarse siempre fuera del hilo principal.
 */
object DshHealthProbe {

    fun baseUrl(port: Int): String = "http://127.0.0.1:$port"

    fun probe(
        port: Int,
        rootfsDir: File? = null,
        timeoutMs: Int = 1_500,
    ): HealthProbeResult {
        var conn: HttpURLConnection? = null
        val start = System.currentTimeMillis()
        return try {
            val cookie = rootfsDir?.let { root ->
                DshAuthCookieMinter.findBrowserSessionSecret(root)?.let { secret ->
                    DshAuthCookieMinter.mintCookie(secret, "127.0.0.1:$port")
                }
            }

            val url = URL("${baseUrl(port)}/")
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                instanceFollowRedirects = false
                if (cookie != null) {
                    setRequestProperty("Cookie", cookie)
                }
            }
            val code = conn.responseCode
            val elapsed = System.currentTimeMillis() - start
            // 200..399 indica UI lista; 401/403 indica que el servidor HTTP de dsh ya está escuchando
            val listening = code in 200..399 || code == 401 || code == 403
            HealthProbeResult(
                reachable = listening,
                httpCode = code,
                latencyMs = elapsed,
            )
        } catch (e: Exception) {
            HealthProbeResult(
                reachable = false,
                httpCode = -1,
                latencyMs = System.currentTimeMillis() - start,
                error = e.message ?: e.javaClass.simpleName,
            )
        } finally {
            conn?.disconnect()
        }
    }
}
