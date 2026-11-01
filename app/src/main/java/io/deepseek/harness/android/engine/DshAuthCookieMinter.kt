package io.deepseek.harness.android.engine

import android.webkit.CookieManager
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Gestiona la autenticación de sesión web de `deepseek-harness` (`dsh` >= 0.1.5 / 0.2.x).
 *
 * Estrategia dual para compatibilidad universal entre versiones de `dsh`:
 * 1. **Firma directa de cookie HMAC-SHA256** leyendo la clave persistente
 *    `client-connection/browser-session` en `<rootfs>/root/.dsh/.credentials.yaml`.
 *    Evita depender del parseo de stdout cuando el motor reinicia en segundo plano.
 * 2. **Captura de URL con `?token=...`** desde la salida estándar de `dsh web` como respaldo.
 * 3. En versiones anteriores de `dsh` sin autenticación local, la carga directa de
 *    `http://127.0.0.1:3080` funciona sin cookie adicional.
 */
object DshAuthCookieMinter {

    private const val RECORD_KEY = "client-connection/browser-session"
    private const val COOKIE_PREFIX = "dsh-auth-"
    private const val COOKIE_TTL_MS = 29L * 24L * 60L * 60L * 1000L // 29 días

    private val SECRET_REGEX =
        Regex("""secret\s*:\s*["']?([A-Za-z0-9_\-+/=]+)["']?""")
    private val TOKEN_URL_REGEX =
        Regex("""(https?://(?:127\.0\.0\.1|localhost):\d+[^\s"']*token=[^\s"']+)""")

    /**
     * Busca la clave secreta de 32 bytes dentro de los archivos `.credentials.yaml` candidatos.
     */
    fun findBrowserSessionSecret(rootfsDir: File): ByteArray? {
        val candidates = listOf(
            File(rootfsDir, "root/.dsh/.credentials.yaml"),
            File(rootfsDir, "root/.credentials.yaml"),
            File(rootfsDir, "home/dsh/.dsh/.credentials.yaml"),
        )
        for (file in candidates) {
            val secret = readSecretFromFile(file)
            if (secret != null && secret.size == 32) {
                return secret
            }
        }
        return null
    }

    fun readSecretFromFile(credentialsFile: File): ByteArray? {
        if (!credentialsFile.isFile) return null
        val text = try {
            credentialsFile.readText(StandardCharsets.UTF_8)
        } catch (_: Exception) {
            return null
        }
        val idx = text.indexOf(RECORD_KEY)
        if (idx < 0) return null
        val match = SECRET_REGEX.find(text.substring(idx)) ?: return null
        val encoded = match.groupValues[1]
        return try {
            Base64.getUrlDecoder().decode(encoded)
        } catch (_: IllegalArgumentException) {
            try {
                Base64.getDecoder().decode(encoded)
            } catch (_: IllegalArgumentException) {
                null
            }
        }
    }

    /**
     * Genera el par `nombre=valor` de la cookie `dsh-auth-*` para un `authority` (`127.0.0.1:3080`).
     */
    fun mintCookie(
        secret: ByteArray,
        authority: String,
        nowMillis: Long = System.currentTimeMillis(),
    ): String? {
        if (secret.size != 32) return null
        return try {
            val issuedAt = nowMillis - 1_000L
            val expiresAt = issuedAt + COOKIE_TTL_MS
            val payloadJson =
                """{"version":1,"authority":"$authority","issuedAt":$issuedAt,"expiresAt":$expiresAt}"""
            val bodyB64 = base64UrlNoPad(payloadJson.toByteArray(StandardCharsets.UTF_8))
            val sigBytes = hmacSha256(secret, bodyB64.toByteArray(StandardCharsets.UTF_8))
            val sigB64 = base64UrlNoPad(sigBytes)
            val authorityHash = sha256(authority.toByteArray(StandardCharsets.UTF_8))
            val cookieName = COOKIE_PREFIX + base64UrlNoPad(authorityHash)
            "$cookieName=v1.$bodyB64.$sigB64"
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Inyecta las cookies de sesión en el `CookieManager` del `WebView` de Android
     * tanto para `127.0.0.1` como para `localhost`.
     */
    fun syncCookiesToWebView(rootfsDir: File, port: Int): String? {
        val secret = findBrowserSessionSecret(rootfsDir) ?: return null
        val loopbackCookie = mintCookie(secret, "127.0.0.1:$port") ?: return null
        val localhostCookie = mintCookie(secret, "localhost:$port")

        try {
            val cm = CookieManager.getInstance()
            cm.setAcceptCookie(true)
            cm.setCookie("http://127.0.0.1:$port", "$loopbackCookie; Path=/; HttpOnly; SameSite=Lax")
            if (localhostCookie != null) {
                cm.setCookie("http://localhost:$port", "$localhostCookie; Path=/; HttpOnly; SameSite=Lax")
            }
            cm.flush()
        } catch (_: Throwable) {
            // En pruebas unitarias sin WebView nativo se devuelve igualmente la cookie firmada
        }
        return loopbackCookie
    }

    /**
     * Extrae la URL autenticada inicial (`http://127.0.0.1:3080/?token=...`) de una línea de stdout.
     */
    fun extractTokenUrlFromLogLine(line: String, expectedPort: Int): String? {
        val match = TOKEN_URL_REGEX.find(line) ?: return null
        val url = match.groupValues[1].trimEnd('.', ',', ')')
        return if (url.contains(":$expectedPort")) url else null
    }

    private fun sha256(input: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(input)

    private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    private fun base64UrlNoPad(bytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}
