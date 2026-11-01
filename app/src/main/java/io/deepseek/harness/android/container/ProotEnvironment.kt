package io.deepseek.harness.android.container

import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import android.os.Environment
import io.deepseek.harness.android.core.AppLogger
import io.deepseek.harness.android.core.AppPaths
import io.deepseek.harness.android.core.AppPreferences
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Paths

/**
 * Instantánea inmutable de la configuración lista para construir comandos PRoot.
 */
data class ProotConfig(
    val prootExecutable: File,
    val prootLoader: File?,
    val prootLoader32: File?,
    val ldLibraryPath: String,
    val prootTmpDir: File,
    val prootL2sDir: File,
    val rootfsDir: File,
    val sharedTmpDir: File,
    val sharedShmDir: File,
    val resolvConfFile: File,
    val hostsFile: File,
    val fakeProcBinds: List<String>,
    val workspaceHostDir: File,
    val safMountsHostDir: File,
    val externalStorageRoot: File?,
    val guestNodePath: String,
    val guestDshEntry: String,
    val disableSeccomp: Boolean,
    val useLinker64Wrapper: Boolean = false,
)

/**
 * Prepara todos los artefactos del host e internos del contenedor necesarios para que
 * PRoot funcione sin root y completamente offline en Android 10–15+:
 *
 * 1. Localiza `libproot.so` y `libproot_loader.so` en `nativeLibraryDir` (extraídos por
 *    PackageManager desde `jniLibs/<abi>/` con contexto SELinux `apk_data_file` ejecutable),
 *    con respaldo opcional desde `assets/proot/<abi>/`.
 * 2. Construye el enlace simbólico `libtalloc.so.2 -> <nativeLibraryDir>/libtalloc.so`
 *    para satisfacer `DT_NEEDED` de proot sin modificar el binario ELF.
 * 3. Genera `/etc/resolv.conf` (combinando servidores DNS del sistema activo + DNS de respaldo)
 *    y `/etc/hosts`.
 * 4. Instala en el rootfs los scripts compartidos `/etc/dsh-android-preload.cjs`,
 *    `/etc/profile.d/dsh-android.sh`, `/usr/local/bin/dsh-doctor` y el enlace `/root/saf -> /mnt/saf`.
 */
object ProotEnvironment {

    private val prepareLock = Any()

    @Volatile
    var forceLinker64Fallback: Boolean = false

    fun prepare(context: Context): ProotConfig = synchronized(prepareLock) {
        val prefs = AppPreferences(context)
        val rootfsDir = AppPaths.rootfsDir(context)
        if (!rootfsDir.isDirectory) {
            throw IOException("El directorio rootfs no existe aún: ${rootfsDir.absolutePath}")
        }

        val nativeLibDir = File(context.applicationInfo.nativeLibraryDir)
        val prootLibsDir = AppPaths.prootLibsDir(context)

        val (prootBin, loaderBin, loader32Bin, extraLibDir) = resolveProotBinaries(
            context = context,
            nativeLibDir = nativeLibDir,
            prootLibsDir = prootLibsDir,
        )

        val ldPathParts = buildList {
            add(prootLibsDir.absolutePath)
            extraLibDir?.let { add(it.absolutePath) }
            add(nativeLibDir.absolutePath)
        }.distinct()

        val resolvConf = writeResolvConf(context)
        val hostsFile = writeHostsFile(context)
        val fakeProcBinds = prepareFakeProcBinds(context)
        installGuestIntegrationFiles(context, rootfsDir)

        val nodePath = AppPaths.resolveGuestNodePath(rootfsDir)
            ?: "/usr/local/bin/node"
        val dshEntry = AppPaths.resolveGuestDshEntry(rootfsDir)
            ?: "/opt/dsh/node_modules/@deepseek-ai/dsh/lib/bin.js"

        val extRoot = resolveAccessibleExternalStorageRoot()

        ProotConfig(
            prootExecutable = prootBin,
            prootLoader = loaderBin,
            prootLoader32 = loader32Bin,
            ldLibraryPath = ldPathParts.joinToString(":"),
            prootTmpDir = AppPaths.prootTmpDir(context),
            prootL2sDir = AppPaths.prootL2sDir(context),
            rootfsDir = rootfsDir,
            sharedTmpDir = AppPaths.sharedContainerTmpDir(context),
            sharedShmDir = AppPaths.sharedContainerShmDir(context),
            resolvConfFile = resolvConf,
            hostsFile = hostsFile,
            fakeProcBinds = fakeProcBinds,
            workspaceHostDir = AppPaths.workspaceHostDir(context),
            safMountsHostDir = AppPaths.safMountsHostDir(context),
            externalStorageRoot = extRoot,
            guestNodePath = nodePath,
            guestDshEntry = dshEntry,
            disableSeccomp = prefs.prootNoSeccomp,
            useLinker64Wrapper = forceLinker64Fallback,
        )
    }

    private data class ProotBinaries(
        val proot: File,
        val loader: File?,
        val loader32: File?,
        val extraLibDir: File?,
    )

    private fun resolveProotBinaries(
        context: Context,
        nativeLibDir: File,
        prootLibsDir: File,
    ): ProotBinaries {
        // 1. Vía principal recomendada (Android 10–15+ W^X): jniLibs -> nativeLibraryDir
        val jniProot = File(nativeLibDir, "libproot.so")
        if (jniProot.isFile && jniProot.length() > 0L) {
            val jniLoader = File(nativeLibDir, "libproot_loader.so").takeIf { it.isFile }
            val jniLoader32 = File(nativeLibDir, "libproot_loader32.so").takeIf { it.isFile }
            val jniTalloc = File(nativeLibDir, "libtalloc.so")
            if (jniTalloc.isFile) {
                ensureSymlink(
                    link = File(prootLibsDir, "libtalloc.so.2"),
                    target = jniTalloc,
                )
            }
            return ProotBinaries(
                proot = jniProot,
                loader = jniLoader,
                loader32 = jniLoader32,
                extraLibDir = null,
            )
        }

        // 2. Respaldo desde assets/proot/<abi>/ (útil en entornos de desarrollo personalizados)
        val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
        val fallbackDir = AppPaths.prootFallbackBinDir(context).apply { mkdirs() }
        val fallbackProot = File(fallbackDir, "proot")
        val fallbackLoader = File(fallbackDir, "loader")
        val fallbackTalloc = File(fallbackDir, "libtalloc.so.2")
        val fallbackShmem = File(fallbackDir, "libandroid-shmem.so")

        extractAssetIfPresent(context, "proot/$abi/proot", fallbackProot, executable = true)
        extractAssetIfPresent(context, "proot/$abi/loader", fallbackLoader, executable = true)
        extractAssetIfPresent(context, "proot/$abi/libtalloc.so.2", fallbackTalloc, executable = false)
        extractAssetIfPresent(context, "proot/$abi/libandroid-shmem.so", fallbackShmem, executable = false)

        if (!fallbackProot.isFile) {
            throw IOException(
                "No se encontró libproot.so en ${nativeLibDir.absolutePath} ni en assets/proot/$abi/. " +
                    "Ejecuta ./scripts/fetch-proot-binaries.sh antes de compilar el APK.",
            )
        }

        return ProotBinaries(
            proot = fallbackProot,
            loader = fallbackLoader.takeIf { it.isFile },
            loader32 = null,
            extraLibDir = fallbackDir,
        )
    }

    private fun extractAssetIfPresent(
        context: Context,
        assetPath: String,
        target: File,
        executable: Boolean,
    ) {
        if (target.isFile && target.length() > 0L) return
        try {
            context.assets.open(assetPath).use { input ->
                target.parentFile?.mkdirs()
                if (target.exists()) target.setWritable(true, true)
                target.outputStream().use { out -> input.copyTo(out) }
            }
            target.setReadable(true, true)
            target.setExecutable(executable, true)
            target.setWritable(false, false)
        } catch (_: Exception) {
            // Ignorar si no existe en assets
        }
    }

    private fun ensureSymlink(link: File, target: File) {
        try {
            val linkPath = link.toPath()
            if (Files.isSymbolicLink(linkPath)) {
                val current = Files.readSymbolicLink(linkPath)
                if (current.toString() == target.absolutePath) return
                Files.delete(linkPath)
            } else if (link.exists()) {
                link.delete()
            }
            link.parentFile?.mkdirs()
            Files.createSymbolicLink(linkPath, Paths.get(target.absolutePath))
        } catch (e: Exception) {
            AppLogger.warn("ProotEnv", "No se pudo crear symlink ${link.name} -> ${target.absolutePath}", e)
        }
    }

    /**
     * Genera `resolv.conf` priorizando los DNS de la red activa del dispositivo Android
     * (para soportar redes corporativas/LAN locales) más Cloudflare y Google como respaldo.
     */
    fun writeResolvConf(context: Context): File {
        val file = File(AppPaths.hostEtcDir(context), "resolv.conf")
        val dnsServers = LinkedHashSet<String>()
        try {
            val cm = context.getSystemService(ConnectivityManager::class.java)
            val activeNet = cm?.activeNetwork
            val linkProps = if (activeNet != null) cm.getLinkProperties(activeNet) else null
            linkProps?.dnsServers?.forEach { addr ->
                val hostAddr = addr.hostAddress
                if (!hostAddr.isNullOrBlank() && !hostAddr.contains('%')) {
                    dnsServers.add(hostAddr)
                }
            }
        } catch (_: Exception) {
        }
        dnsServers.add("1.1.1.1")
        dnsServers.add("8.8.8.8")

        val content = buildString {
            appendLine("# Generado automáticamente por DeepSeek Harness Android")
            dnsServers.take(4).forEach { dns ->
                appendLine("nameserver $dns")
            }
        }
        file.writeText(content)
        return file
    }

    private fun writeHostsFile(context: Context): File {
        val file = File(AppPaths.hostEtcDir(context), "hosts")
        if (!file.isFile) {
            file.writeText(
                """
                127.0.0.1 localhost
                ::1 localhost ip6-localhost ip6-loopback
                """.trimIndent() + "\n",
            )
        }
        return file
    }

    /**
     * En Android 10–15+, SELinux restringe la lectura directa de `/proc/stat`, `/proc/loadavg`,
     * `/proc/uptime` y `/proc/version` para aplicaciones sin privilegios. Generamos archivos
     * sustitutos limpios (igual que `proot-distro`) únicamente para las entradas bloqueadas.
     */
    private fun prepareFakeProcBinds(context: Context): List<String> {
        val sysDir = AppPaths.hostSysdataDir(context)
        val cpuCount = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        val binds = ArrayList<String>(5)

        fun bindIfUnreadable(procPath: String, fileName: String, content: () -> String) {
            val hostProc = File(procPath)
            val readable = try {
                hostProc.isFile && hostProc.canRead() && hostProc.inputStream().use { it.read() >= 0 }
            } catch (_: Exception) {
                false
            }
            if (!readable) {
                val fakeFile = File(sysDir, fileName)
                if (!fakeFile.isFile || fakeFile.length() == 0L) {
                    fakeFile.writeText(content())
                }
                binds.add("${fakeFile.absolutePath}:$procPath")
            }
        }

        bindIfUnreadable("/proc/loadavg", "loadavg") { "0.12 0.08 0.05 1/256 1024\n" }
        bindIfUnreadable("/proc/uptime", "uptime") { "3600.00 ${(3500 * cpuCount)}.00\n" }
        bindIfUnreadable("/proc/version", "version") {
            "Linux version 6.6.30-android15 (builder@android) (clang version 18.0.1) #1 SMP PREEMPT\n"
        }
        bindIfUnreadable("/proc/stat", "stat") {
            buildString {
                val totalUser = 300 * cpuCount
                val totalSys = 180 * cpuCount
                val totalIdle = 12000 * cpuCount
                appendLine("cpu  $totalUser 0 $totalSys $totalIdle 20 5 2 0 0 0")
                for (i in 0 until cpuCount) {
                    appendLine("cpu$i 300 0 180 12000 20 5 2 0 0 0")
                }
                appendLine("ctxt 140223")
                appendLine("btime 1730000000")
                appendLine("processes 512")
                appendLine("procs_running 1")
                appendLine("procs_blocked 0")
            }
        }
        bindIfUnreadable("/proc/vmstat", "vmstat") {
            "nr_free_pages 262144\nnr_active_anon 65536\nnr_inactive_anon 32768\n"
        }

        return binds
    }

    /**
     * Instala dentro del rootfs los archivos auxiliares de integración para que el motor
     * `dsh` y las sesiones interactivas `bash` tengan un comportamiento idéntico.
     */
    private fun installGuestIntegrationFiles(context: Context, rootfsDir: File) {
        copyAssetIntoRootfs(
            context = context,
            assetPath = AppPaths.ASSET_CONTAINER_PRELOAD,
            destFile = File(rootfsDir, "etc/dsh-android-preload.cjs"),
            executable = false,
        )
        copyAssetIntoRootfs(
            context = context,
            assetPath = AppPaths.ASSET_CONTAINER_PROFILE,
            destFile = File(rootfsDir, "etc/profile.d/dsh-android.sh"),
            executable = false,
        )
        copyAssetIntoRootfs(
            context = context,
            assetPath = AppPaths.ASSET_CONTAINER_DOCTOR,
            destFile = File(rootfsDir, "usr/local/bin/dsh-doctor"),
            executable = true,
        )
        copyAssetIntoRootfs(
            context = context,
            assetPath = AppPaths.ASSET_CONTAINER_UPDATE_NODE,
            destFile = File(rootfsDir, "usr/local/bin/dsh-update-node"),
            executable = true,
        )

        // Enlace simbólico cómodo /root/saf -> /mnt/saf dentro del contenedor
        val homeSafLink = File(rootfsDir, "root/saf")
        try {
            val linkPath = homeSafLink.toPath()
            if (!Files.isSymbolicLink(linkPath) && !homeSafLink.exists()) {
                homeSafLink.parentFile?.mkdirs()
                Files.createSymbolicLink(linkPath, Paths.get(AppPaths.GUEST_SAF_MOUNT_ROOT))
            }
        } catch (_: Exception) {
        }

        // Asegurar que /root/.bashrc cargue /etc/profile.d/dsh-android.sh también en shells no-login
        val bashrc = File(rootfsDir, "root/.bashrc")
        try {
            val marker = "# dsh-android-shared-env"
            val existing = if (bashrc.isFile) bashrc.readText() else ""
            if (!existing.contains(marker)) {
                bashrc.parentFile?.mkdirs()
                if (bashrc.exists()) bashrc.setWritable(true, true)
                bashrc.appendText(
                    "\n$marker\n[ -f /etc/profile.d/dsh-android.sh ] && . /etc/profile.d/dsh-android.sh\n",
                )
            }
        } catch (_: Exception) {
        }
    }

    private fun copyAssetIntoRootfs(
        context: Context,
        assetPath: String,
        destFile: File,
        executable: Boolean,
    ) {
        try {
            context.assets.open(assetPath).use { input ->
                destFile.parentFile?.mkdirs()
                if (destFile.exists()) destFile.setWritable(true, true)
                destFile.outputStream().use { out -> input.copyTo(out) }
            }
            destFile.setReadable(true, false)
            destFile.setWritable(true, true)
            if (executable) {
                destFile.setExecutable(true, false)
                destFile.setWritable(false, false)
            }
        } catch (e: Exception) {
            AppLogger.warn("ProotEnv", "No se pudo instalar asset $assetPath en rootfs", e)
        }
    }

    private fun resolveAccessibleExternalStorageRoot(): File? {
        return try {
            val hasAccess = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Environment.isExternalStorageManager()
            } else {
                true
            }
            if (!hasAccess) return null
            val dir = Environment.getExternalStorageDirectory()
            if (dir != null && dir.isDirectory && dir.canRead()) dir else null
        } catch (_: Exception) {
            null
        }
    }
}
