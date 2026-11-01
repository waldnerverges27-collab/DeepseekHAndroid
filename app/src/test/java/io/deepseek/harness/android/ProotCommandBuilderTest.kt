package io.deepseek.harness.android

import io.deepseek.harness.android.container.ProotCommandBuilder
import io.deepseek.harness.android.container.ProotConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ProotCommandBuilderTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun sampleConfig(
        useLinker64: Boolean = false,
        prootL2sDirOverride: File? = null,
    ): ProotConfig {
        val base = tmp.root
        val rootfs = File(base, "rootfs")
        return ProotConfig(
            prootExecutable = File(base, "lib/libproot.so"),
            prootLoader = File(base, "lib/libproot_loader.so"),
            prootLoader32 = null,
            ldLibraryPath = "${base.absolutePath}/proot-libs:${base.absolutePath}/lib",
            prootTmpDir = File(base, "proot-tmp"),
            prootL2sDir = prootL2sDirOverride ?: File(rootfs, ".l2s"),
            rootfsDir = rootfs,
            sharedTmpDir = File(base, "container-tmp"),
            sharedShmDir = File(base, "container-shm"),
            resolvConfFile = File(base, "etc/resolv.conf"),
            hostsFile = File(base, "etc/hosts"),
            fakeProcBinds = listOf("${base.absolutePath}/sysdata/loadavg:/proc/loadavg"),
            workspaceHostDir = File(base, "workspace"),
            safMountsHostDir = File(base, "saf-mounts"),
            externalStorageRoot = File("/storage/emulated/0"),
            guestNodePath = "/usr/local/bin/node",
            guestDshEntry = "/opt/dsh/node_modules/@deepseek-ai/dsh/lib/bin.js",
            guestInteractiveShell = "/bin/zsh",
            disableSeccomp = true,
            useLinker64Wrapper = useLinker64,
        )
    }

    @Test
    fun `motor dsh y terminal zsh comparten exactamente el mismo rootfs, montajes y entorno`() {
        val cfg = sampleConfig()
        val engineSpec = ProotCommandBuilder.buildEngineSpec(cfg, port = 3080, pickToken = "tok-123")
        val shellSpec = ProotCommandBuilder.buildInteractiveBashSpec(cfg, port = 3080)
        val smokeSpec = ProotCommandBuilder.buildSmokeTestSpec(cfg)

        assertEquals(engineSpec.workingDirectoryHost, shellSpec.workingDirectoryHost)
        assertEquals(engineSpec.hostEnvironment, shellSpec.hostEnvironment)

        // PROOT_L2S_DIR debe apuntar dentro de rootfs/.l2s para evitar ENOENT en fs.link()
        val l2sEnv = engineSpec.hostEnvironment["PROOT_L2S_DIR"]
        assertEquals(File(cfg.rootfsDir, ".l2s").absolutePath, l2sEnv)

        // PRoot NO admite "--" como separador de opciones (causaría: proot error: unknown option '--')
        assertFalse(engineSpec.command.contains("--"))
        assertFalse(shellSpec.command.contains("--"))
        assertFalse(smokeSpec.command.contains("--"))

        // Verificar que inmediatamente después de "-w <dir>" comienza "/usr/bin/env -i"
        val wIndex = engineSpec.command.indexOf("-w")
        assertTrue(wIndex > 0)
        assertEquals("/usr/bin/env", engineSpec.command[wIndex + 2])
        assertEquals("-i", engineSpec.command[wIndex + 3])

        // Ambos usan el mismo binario proot como argv[0]
        assertEquals(cfg.prootExecutable.absolutePath, engineSpec.executablePath)
        assertEquals(cfg.prootExecutable.absolutePath, shellSpec.executablePath)
        assertEquals(shellSpec.executablePath, shellSpec.argvArray[0])

        // Ambos montan el mismo rootfs, /tmp, /dev/shm, /root/workspace y /mnt/saf
        val requiredArgs = listOf(
            cfg.rootfsDir.absolutePath,
            "${cfg.sharedTmpDir.absolutePath}:/tmp",
            "${cfg.sharedShmDir.absolutePath}:/dev/shm",
            "${cfg.workspaceHostDir.absolutePath}:/root/workspace",
            "${cfg.safMountsHostDir.absolutePath}:/mnt/saf",
            "DSH_HOME=/root/.dsh",
            "COREPACK_ENABLE_DOWNLOAD_PROMPT=0",
            "NODE_OPTIONS=--require=/etc/dsh-android-preload.cjs",
        )
        for (expected in requiredArgs) {
            assertTrue("Falta '$expected' en engineSpec", engineSpec.command.contains(expected))
            assertTrue("Falta '$expected' en shellSpec", shellSpec.command.contains(expected))
        }

        // El motor arranca `dsh web --host 127.0.0.1 --port 3080 --no-open`
        assertTrue(engineSpec.command.contains("web"))
        assertTrue(engineSpec.command.contains("3080"))
        assertTrue(engineSpec.command.contains("--no-open"))

        // La terminal interactiva abre `/bin/zsh --login`
        assertEquals(listOf("/bin/zsh", "--login"), shellSpec.command.takeLast(2))
        assertTrue(shellSpec.command.contains("SHELL=/bin/zsh"))
    }

    @Test
    fun `PROOT_L2S_DIR fuera de rootfs se descarta automaticamente para evitar ENOENT en hardlinks`() {
        val outsideCfg = sampleConfig(prootL2sDirOverride = File(tmp.root, "proot-l2s-outside"))
        val spec = ProotCommandBuilder.buildEngineSpec(outsideCfg, port = 3080)
        assertFalse(spec.hostEnvironment.containsKey("PROOT_L2S_DIR"))
    }

    @Test
    fun `modo fallback linker64 antepone el cargador del sistema`() {
        val cfg = sampleConfig(useLinker64 = true)
        val spec = ProotCommandBuilder.buildEngineSpec(cfg, port = 3080)
        assertEquals("/system/bin/linker64", spec.command[0])
        assertEquals(cfg.prootExecutable.absolutePath, spec.command[1])
        assertEquals("1", spec.hostEnvironment["PROOT_NO_SECCOMP"])
    }
}
