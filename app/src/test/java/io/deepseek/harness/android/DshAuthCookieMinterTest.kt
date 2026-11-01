package io.deepseek.harness.android

import io.deepseek.harness.android.engine.DshAuthCookieMinter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Base64

class DshAuthCookieMinterTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `lee clave de sesion desde credentials yaml y firma cookie v1 valida`() {
        val rootfs = tmp.newFolder("rootfs")
        val creds = File(rootfs, "root/.dsh/.credentials.yaml")
        creds.parentFile?.mkdirs()

        val rawSecret = ByteArray(32) { idx -> (idx + 1).toByte() }
        val encodedSecret = Base64.getUrlEncoder().withoutPadding().encodeToString(rawSecret)
        creds.writeText(
            """
            version: 1
            records:
              client-connection/browser-session:
                secret: "$encodedSecret"
            """.trimIndent(),
        )

        val loaded = DshAuthCookieMinter.findBrowserSessionSecret(rootfs)
        assertNotNull(loaded)
        assertEquals(32, loaded!!.size)

        val cookie = DshAuthCookieMinter.mintCookie(
            secret = loaded,
            authority = "127.0.0.1:3080",
            nowMillis = 1_760_000_000_000L,
        )
        assertNotNull(cookie)
        assertTrue(cookie!!.startsWith("dsh-auth-"))
        assertTrue(cookie.contains("=v1."))
        assertEquals(3, cookie.substringAfter('=').split('.').size)
    }

    @Test
    fun `extrae URL autenticada con token de stdout verificando puerto`() {
        val line = "dsh web: http://127.0.0.1:3080/?token=abc123XYZ (LAN: disabled)"
        assertEquals(
            "http://127.0.0.1:3080/?token=abc123XYZ",
            DshAuthCookieMinter.extractTokenUrlFromLogLine(line, expectedPort = 3080),
        )
        assertNull(DshAuthCookieMinter.extractTokenUrlFromLogLine(line, expectedPort = 4090))
    }
}
