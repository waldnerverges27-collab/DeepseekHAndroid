package io.deepseek.harness.android.container

import android.content.Context
import io.deepseek.harness.android.core.AppLogger
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Resultado de la prueba rápida de humo del contenedor PRoot antes de iniciar `dsh web`.
 */
data class SmokeTestResult(
    val success: Boolean,
    val output: String,
    val usedLinker64Fallback: Boolean,
)

/**
 * Verifica que PRoot puede levantar `/bin/bash` y `node` dentro del rootfs extraído.
 * Si el kernel de un fabricante bloquea `execve` directo por políticas SELinux personalizadas,
 * reintenta automáticamente mediante `/system/bin/linker64` y memoriza esa ruta para el resto
 * de la sesión (tanto para el motor `dsh` como para la terminal).
 */
object ContainerSmokeTester {

    fun run(context: Context, timeoutSeconds: Long = 20L): SmokeTestResult {
        val initialConfig = try {
            ProotEnvironment.prepare(context)
        } catch (e: Exception) {
            AppLogger.error("SmokeTest", "Error preparando entorno PRoot", e)
            return SmokeTestResult(
                success = false,
                output = e.message ?: "Error preparando entorno PRoot",
                usedLinker64Fallback = false,
            )
        }

        val directAttempt = executeOnce(initialConfig, timeoutSeconds)
        if (directAttempt.first) {
            AppLogger.info("SmokeTest", "Contenedor PRoot verificado correctamente: ${directAttempt.second.lines().joinToString(" | ")}")
            return SmokeTestResult(
                success = true,
                output = directAttempt.second,
                usedLinker64Fallback = initialConfig.useLinker64Wrapper,
            )
        }

        if (!initialConfig.useLinker64Wrapper && directAttempt.second.contains("Permission denied", ignoreCase = true)) {
            AppLogger.warn("SmokeTest", "Exec directo denegado; activando fallback /system/bin/linker64")
            ProotEnvironment.forceLinker64Fallback = true
            val fallbackConfig = initialConfig.copy(useLinker64Wrapper = true)
            val fallbackAttempt = executeOnce(fallbackConfig, timeoutSeconds)
            if (fallbackAttempt.first) {
                AppLogger.info("SmokeTest", "Contenedor PRoot verificado vía linker64")
                return SmokeTestResult(
                    success = true,
                    output = fallbackAttempt.second,
                    usedLinker64Fallback = true,
                )
            }
            return SmokeTestResult(
                success = false,
                output = fallbackAttempt.second,
                usedLinker64Fallback = true,
            )
        }

        AppLogger.error("SmokeTest", "Fallo en prueba de humo PRoot: ${directAttempt.second}")
        return SmokeTestResult(
            success = false,
            output = directAttempt.second,
            usedLinker64Fallback = initialConfig.useLinker64Wrapper,
        )
    }

    private fun executeOnce(config: ProotConfig, timeoutSeconds: Long): Pair<Boolean, String> {
        val spec = ProotCommandBuilder.buildSmokeTestSpec(config)
        return try {
            val pb = ProcessBuilder(spec.command).apply {
                directory(config.rootfsDir)
                environment().putAll(spec.hostEnvironment)
                redirectErrorStream(true)
            }
            val proc = pb.start()
            val finished = proc.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            if (!finished) {
                proc.destroyForcibly()
                return false to "Tiempo de espera agotado (${timeoutSeconds}s) verificando PRoot"
            }
            val stdout = proc.inputStream.bufferedReader().use { it.readText().trim() }
            val ok = proc.exitValue() == 0 && stdout.contains("PROOT_SMOKE_OK")
            ok to stdout
        } catch (e: IOException) {
            false to (e.message ?: "IOException ejecutando proot")
        } catch (e: Exception) {
            false to (e.message ?: "Error inesperado ejecutando proot")
        }
    }
}
