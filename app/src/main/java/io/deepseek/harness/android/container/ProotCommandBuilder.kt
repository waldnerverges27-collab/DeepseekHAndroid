package io.deepseek.harness.android.container

import io.deepseek.harness.android.core.AppPaths

/**
 * Especificación completa para lanzar un proceso dentro del contenedor PRoot compartido.
 * Sirve tanto para `ProcessBuilder` (motor `dsh` en segundo plano) como para `TerminalSession`
 * (PTY nativo de la terminal interactiva).
 */
data class ProotLaunchSpec(
    val command: List<String>,
    val hostEnvironment: Map<String, String>,
    val workingDirectoryHost: String,
) {
    /** Ruta del ejecutable para `TerminalSession` (`JNI.createSubprocess`). */
    val executablePath: String
        get() = command.first()

    /**
     * Vector `argv` completo para `execvp(cmd, argv)` en `termux.c`:
     * `argv[0]` DEBE ser el nombre/ruta del ejecutable seguido de todos los argumentos.
     */
    val argvArray: Array<String>
        get() = command.toTypedArray()

    /** Vector `envp` (`KEY=VALUE`) del lado host para `TerminalSession`. */
    val hostEnvArray: Array<String>
        get() = hostEnvironment.entries.map { (k, v) -> "$k=$v" }.toTypedArray()
}

/**
 * Constructor único y determinista de comandos PRoot.
 *
 * ARQUITECTURA DE CONTENEDOR COMPARTIDO:
 * Tanto [buildEngineSpec] (que arranca el servidor web de `deepseek-harness`) como
 * [buildInteractiveBashSpec] (que abre la shell `bash` del usuario en el panel PTY)
 * invocan exactamente la misma función base [buildContainerSpec]. Por ello:
 * - Ven la misma raíz `<filesDir>/rootfs` y los mismos binarios glibc (`node`, `npm`, `python3`, `git`, `apt`).
 * - Comparten `/tmp`, `/dev/shm`, `/root/workspace`, `/root/.dsh` y `/mnt/saf` en tiempo real.
 * - Comparten el mismo `UID/GID` emulado (`0:0`), `PATH`, `DSH_HOME` y `NODE_OPTIONS`.
 */
object ProotCommandBuilder {

    const val LINKER64_PATH = "/system/bin/linker64"

    /**
     * Construye la especificación de arranque del motor `deepseek-harness` (`dsh web`).
     */
    fun buildEngineSpec(
        config: ProotConfig,
        port: Int = AppPaths.DEFAULT_ENGINE_PORT,
        pickToken: String? = null,
    ): ProotLaunchSpec {
        val extraGuestEnv = buildMap {
            put("DSH_PORT", port.toString())
            if (!pickToken.isNullOrBlank()) {
                put("DSH_PICK_TOKEN", pickToken)
            }
        }

        val innerCommand = if (config.guestDshEntry.endsWith(".js") || config.guestDshEntry.endsWith(".mjs")) {
            listOf(
                config.guestNodePath,
                "--expose-internals",
                config.guestDshEntry,
                "web",
                "--host",
                "127.0.0.1",
                "--port",
                port.toString(),
                "--no-open",
            )
        } else {
            listOf(
                config.guestDshEntry,
                "web",
                "--host",
                "127.0.0.1",
                "--port",
                port.toString(),
                "--no-open",
            )
        }

        return buildContainerSpec(
            config = config,
            guestWorkingDir = AppPaths.GUEST_WORKSPACE,
            extraGuestEnv = extraGuestEnv,
            innerCommand = innerCommand,
        )
    }

    /**
     * Construye la especificación para abrir una terminal interactiva (`bash --login`)
     * dentro del MISMO contenedor PRoot que ejecuta el motor `dsh`.
     */
    fun buildInteractiveBashSpec(
        config: ProotConfig,
        guestWorkingDir: String = AppPaths.GUEST_WORKSPACE,
        port: Int = AppPaths.DEFAULT_ENGINE_PORT,
    ): ProotLaunchSpec {
        return buildContainerSpec(
            config = config,
            guestWorkingDir = guestWorkingDir,
            extraGuestEnv = mapOf("DSH_PORT" to port.toString()),
            innerCommand = listOf("/bin/bash", "--login"),
        )
    }

    /**
     * Construye la especificación para una prueba de humo rápida del contenedor.
     */
    fun buildSmokeTestSpec(config: ProotConfig): ProotLaunchSpec {
        return buildContainerSpec(
            config = config,
            guestWorkingDir = AppPaths.GUEST_HOME,
            extraGuestEnv = emptyMap(),
            innerCommand = listOf(
                "/bin/bash",
                "-c",
                "echo PROOT_SMOKE_OK && id -u && ${config.guestNodePath} --version",
            ),
        )
    }

    fun buildContainerSpec(
        config: ProotConfig,
        guestWorkingDir: String,
        extraGuestEnv: Map<String, String>,
        innerCommand: List<String>,
    ): ProotLaunchSpec {
        val hostEnv = buildHostEnvironment(config)
        val guestEnv = buildSharedGuestEnvironment(config, extraGuestEnv)

        val cmd = ArrayList<String>(48)
        if (config.useLinker64Wrapper) {
            cmd.add(LINKER64_PATH)
        }
        cmd.add(config.prootExecutable.absolutePath)
        cmd.add("--kill-on-exit")
        cmd.add("-0")
        cmd.add("--link2symlink")
        cmd.add("-L")
        cmd.add("-r")
        cmd.add(config.rootfsDir.absolutePath)

        // Puntos de montaje del sistema y del espacio compartido
        val binds = buildSharedBindMounts(config)
        for (bind in binds) {
            cmd.add("-b")
            cmd.add(bind)
        }

        cmd.add("-w")
        cmd.add(guestWorkingDir)
        cmd.add("--")

        // Limpiar variables bionic de Android (LD_LIBRARY_PATH, BOOTCLASSPATH, etc.)
        // antes de ejecutar binarios glibc de Debian/Ubuntu dentro del contenedor.
        cmd.add("/usr/bin/env")
        cmd.add("-i")
        guestEnv.forEach { (key, value) ->
            cmd.add("$key=$value")
        }
        cmd.addAll(innerCommand)

        return ProotLaunchSpec(
            command = cmd,
            hostEnvironment = hostEnv,
            workingDirectoryHost = config.rootfsDir.absolutePath,
        )
    }

    internal fun buildHostEnvironment(config: ProotConfig): Map<String, String> = buildMap {
        put("PROOT_TMP_DIR", config.prootTmpDir.absolutePath)
        put("LD_LIBRARY_PATH", config.ldLibraryPath)
        config.prootLoader?.let { put("PROOT_LOADER", it.absolutePath) }
        config.prootLoader32?.let { put("PROOT_LOADER_32", it.absolutePath) }
        if (config.disableSeccomp) {
            put("PROOT_NO_SECCOMP", "1")
        }
    }

    internal fun buildSharedBindMounts(config: ProotConfig): List<String> = buildList {
        add("/dev:/dev")
        add("/dev/urandom:/dev/random")
        add("/proc:/proc")
        add("/proc/self/fd:/dev/fd")
        add("/proc/self/fd/0:/dev/stdin")
        add("/proc/self/fd/1:/dev/stdout")
        add("/proc/self/fd/2:/dev/stderr")
        add("/sys:/sys")
        add("${config.sharedShmDir.absolutePath}:/dev/shm")
        add("${config.sharedTmpDir.absolutePath}:/tmp")
        add("${config.resolvConfFile.absolutePath}:/etc/resolv.conf")
        add("${config.hostsFile.absolutePath}:/etc/hosts")
        add("${config.workspaceHostDir.absolutePath}:${AppPaths.GUEST_WORKSPACE}")
        add("${config.safMountsHostDir.absolutePath}:${AppPaths.GUEST_SAF_MOUNT_ROOT}")

        // Si el usuario otorgó acceso al almacenamiento primario, exponer también la ruta
        // real (/storage/emulated/0) para que los enlaces simbólicos creados en /mnt/saf/<alias>
        // resuelvan de forma directa y transparente dentro de PRoot sin duplicar archivos.
        config.externalStorageRoot?.let { ext ->
            val path = ext.absolutePath
            add("$path:$path")
            add("$path:/mnt/sdcard")
        }
    }

    internal fun buildSharedGuestEnvironment(
        config: ProotConfig,
        extra: Map<String, String>,
    ): Map<String, String> = linkedMapOf<String, String>().apply {
        put("HOME", AppPaths.GUEST_HOME)
        put("USER", "root")
        put("LOGNAME", "root")
        put("SHELL", "/bin/bash")
        put("TERM", "xterm-256color")
        put("COLORTERM", "truecolor")
        put("LANG", "C.UTF-8")
        put("LC_ALL", "C.UTF-8")
        put("PATH", "/opt/dsh/node_modules/.bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin")
        put("DSH_HOME", AppPaths.GUEST_DSH_HOME)
        put("DSH_WORKSPACE", AppPaths.GUEST_WORKSPACE)
        put("DSH_SAF_ROOT", AppPaths.GUEST_SAF_MOUNT_ROOT)
        // En kernels Android sin user namespaces (bwrap/Landlock no disponibles),
        // evita SANDBOX_UNAVAILABLE delegando el aislamiento al propio contenedor PRoot.
        put("DSH_PERMISSION_MODE", "danger-full-access")
        put("DEBIAN_FRONTEND", "noninteractive")
        put("COREPACK_ENABLE_DOWNLOAD_PROMPT", "0")
        put("NODE_OPTIONS", "--require=${AppPaths.GUEST_PRELOAD_CJS}")
        putAll(extra)
    }
}
