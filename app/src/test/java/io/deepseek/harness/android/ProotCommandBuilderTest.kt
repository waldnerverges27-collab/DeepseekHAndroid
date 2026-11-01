package io.deepseek.harness.android

import io.deepseek.harness.android.container.ProotCommandBuilder
import io.deepseek.harness.android.container.ProotConfig
import io.deepseek.harness.android.container.ProotEnvironment
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths

class ProotCommandBuilderTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun sampleConfig(
        useLinker64: Boolean = false,
        prootL2sDirOverride: File? = null,
    ): ProotConfig {
        val base = tmp.root.canonicalFile
        val rootfs = File(base, "rootfs").apply { mkdirs() }
        val l2s = (prootL2sDirOverride ?: File(rootfs, ".l2s")).apply { mkdirs() }
        return ProotConfig(
            prootExecutable = File(base, "lib/libproot.so"),
            prootLoader = File(base, "lib/libproot_loader.so"),
            prootLoader32 = null,
            ldLibraryPath = "${base.absolutePath}/proot-libs:${base.absolutePath}/lib",
            prootTmpDir = File(base, "proot-tmp"),
            prootL2sDir = l2s,
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

        // PROOT_L2S_DIR debe apuntar dentro de rootfs/.l2s canónico para evitar ENOENT en fs.link()
        val l2sEnv = engineSpec.hostEnvironment["PROOT_L2S_DIR"]
        assertEquals(File(cfg.rootfsDir, ".l2s").canonicalPath, l2sEnv)

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
        assertEquals(cfg.prootExecutable.canonicalPath, engineSpec.executablePath)
        assertEquals(cfg.prootExecutable.canonicalPath, shellSpec.executablePath)
        assertEquals(shellSpec.executablePath, shellSpec.argvArray[0])

        // Ambos montan el mismo rootfs, /tmp, /dev/shm, /root/workspace y /mnt/saf
        val requiredArgs = listOf(
            cfg.rootfsDir.canonicalPath,
            "${cfg.sharedTmpDir.canonicalPath}:/tmp",
            "${cfg.sharedShmDir.canonicalPath}:/dev/shm",
            "${cfg.workspaceHostDir.canonicalPath}:/root/workspace",
            "${cfg.safMountsHostDir.canonicalPath}:/mnt/saf",
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
    fun `canonicaliza symlink estilo Android data-user-0 hacia data-data en -r y PROOT_L2S_DIR`() {
        val base = tmp.root.canonicalFile
        val dataDataPkgFiles = File(base, "data/data/io.deepseek.harness.android/files").apply { mkdirs() }
        val realRootfs = File(dataDataPkgFiles, "rootfs").apply { mkdirs() }
        File(realRootfs, ".l2s").mkdirs()

        // Simular `/data/user/0 -> /data/data` de Android
        val dataUserDir = File(base, "data/user").apply { mkdirs() }
        val userZeroLink = File(dataUserDir, "0")
        Files.createSymbolicLink(userZeroLink.toPath(), Paths.get(File(base, "data/data").path))

        val symlinkFilesDir = File(userZeroLink, "io.deepseek.harness.android/files")
        val symlinkRootfs = File(symlinkFilesDir, "rootfs")
        val symlinkL2s = File(symlinkRootfs, ".l2s")

        val cfg = sampleConfig().copy(
            rootfsDir = symlinkRootfs,
            prootL2sDir = symlinkL2s,
        )
        val spec = ProotCommandBuilder.buildEngineSpec(cfg, port = 3080)

        val rIndex = spec.command.indexOf("-r")
        assertEquals(realRootfs.canonicalPath, spec.command[rIndex + 1])
        assertEquals(File(realRootfs, ".l2s").canonicalPath, spec.hostEnvironment["PROOT_L2S_DIR"])
    }

    @Test
    fun `repara cadenas l2s de 2 saltos con prefijo data-user-0 convirtiendo session zstd en archivo regular`() {
        val base = tmp.root.canonicalFile
        val dataDataFiles = File(base, "data/data/pkg/files").apply { mkdirs() }
        val realRootfs = File(dataDataFiles, "rootfs").apply { mkdirs() }
        val realL2s = File(realRootfs, ".l2s").apply { mkdirs() }

        val dataUserDir = File(base, "data/user").apply { mkdirs() }
        val userZeroLink = File(dataUserDir, "0")
        Files.createSymbolicLink(userZeroLink.toPath(), Paths.get(File(base, "data/data").path))

        val rawRootfsViaUser0 = File(userZeroLink, "pkg/files/rootfs")
        val rawL2sViaUser0 = File(rawRootfsViaUser0, ".l2s")

        // Crear cadena de 2 saltos como la generada en v1.0.3 con `/data/user/0/...`
        val payload = "ZSTD_HEADER_AND_TURN_PAYLOAD".toByteArray()
        val backingName = ".proot.l2s.session.v4.jsonl.zstd.abcdef123456.tmp0001.0001"
        val intermediateName = ".proot.l2s.session.v4.jsonl.zstd.abcdef123456.tmp0001"
        File(realL2s, backingName).writeBytes(payload)
        Files.createSymbolicLink(
            File(realL2s, intermediateName).toPath(),
            Paths.get("${rawL2sViaUser0.absolutePath}/$backingName"),
        )

        val sessionDir = File(
            realRootfs,
            "root/.dsh/sessions/--root-Hola--/session-da413709-504f-4b8a-b03a-c7ed2f819ca6",
        ).apply { mkdirs() }
        val sessionLog = File(sessionDir, "session.v4.jsonl.zstd")
        Files.createSymbolicLink(
            sessionLog.toPath(),
            Paths.get("${rawL2sViaUser0.absolutePath}/$intermediateName"),
        )

        ProotEnvironment.repairAndCanonicalizeProotL2s(
            rawRootfsDir = rawRootfsViaUser0,
            legacyL2sDir = File(userZeroLink, "pkg/files/proot-l2s"),
        )

        assertFalse(Files.isSymbolicLink(sessionLog.toPath()))
        assertTrue(sessionLog.isFile)
        assertArrayEquals(payload, sessionLog.readBytes())
        assertEquals(
            "${realL2s.canonicalPath}/$backingName",
            Files.readSymbolicLink(File(realL2s, intermediateName).toPath()).toString(),
        )
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
        assertEquals(cfg.prootExecutable.canonicalPath, spec.command[1])
        assertEquals("1", spec.hostEnvironment["PROOT_NO_SECCOMP"])
    }
}
