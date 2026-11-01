package io.deepseek.harness.android.core

import android.content.Context
import java.io.File

/**
 * Registro centralizado de rutas en el host Android y dentro del contenedor PRoot.
 * Garantiza que tanto el motor Node.js (`dsh web`) como la terminal interactiva (`bash`)
 * monten exactamente los mismos directorios y compartan el mismo sistema de archivos.
 */
object AppPaths {

    const val ASSET_ROOTFS_ARCHIVE = "rootfs.tar.xz"
    const val ASSET_ROOTFS_VERSION = "rootfs.version"
    const val ASSET_CONTAINER_PRELOAD = "container/dsh-android-preload.cjs"
    const val ASSET_CONTAINER_PROFILE = "container/profile-dsh.sh"
    const val ASSET_CONTAINER_ZSHRC = "container/zshrc-dsh.zsh"
    const val ASSET_CONTAINER_DOCTOR = "container/verify-shared-env.sh"
    const val ASSET_CONTAINER_UPDATE_NODE = "container/update-node.sh"
    const val ASSET_WEB_POLYFILLS = "web/compat-polyfills.js"
    const val ASSET_WEB_MOBILE_CSS = "web/mobile-adapt.css"

    const val DEFAULT_ENGINE_PORT = 3080
    const val NOTIFICATION_CHANNEL_ENGINE = "dsh_engine_service"

    // Rutas vistas DESDE DENTRO del contenedor PRoot (guest paths)
    const val GUEST_HOME = "/root"
    const val GUEST_DSH_HOME = "/root/.dsh"
    const val GUEST_WORKSPACE = "/root/workspace"
    const val GUEST_SAF_MOUNT_ROOT = "/mnt/saf"
    const val GUEST_SAF_SYMLINK_IN_HOME = "/root/saf"
    const val GUEST_PRELOAD_CJS = "/etc/dsh-android-preload.cjs"
    const val GUEST_PROFILE_SH = "/etc/profile.d/dsh-android.sh"
    const val GUEST_DOCTOR_BIN = "/usr/local/bin/dsh-doctor"
    const val GUEST_UPDATE_NODE_BIN = "/usr/local/bin/dsh-update-node"

    /** Shells interactivas candidatas (prioriza Zsh + Oh My Zsh, con respaldo a Bash). */
    val CANDIDATE_INTERACTIVE_SHELLS = listOf(
        "bin/zsh",
        "usr/bin/zsh",
        "bin/bash",
        "usr/bin/bash",
    )

    /** Ubicaciones candidatas de Node.js (relativas a la raíz del rootfs). */
    val CANDIDATE_NODE_BINS = listOf(
        "usr/local/bin/node",
        "usr/bin/node",
        "opt/node/bin/node",
    )

    /** Ubicaciones candidatas del punto de entrada de `deepseek-harness` (`dsh`). */
    val CANDIDATE_DSH_ENTRIES = listOf(
        "opt/dsh/node_modules/@deepseek-ai/dsh/lib/bin.js",
        "usr/local/lib/node_modules/@deepseek-ai/dsh/lib/bin.js",
        "usr/lib/node_modules/@deepseek-ai/dsh/lib/bin.js",
        "root/.dsh-arm64/node_modules/@deepseek-ai/dsh/lib/bin.js",
        "usr/local/bin/dsh",
        "usr/bin/dsh",
    )

    /** Raíz del sistema de archivos Linux extraído: `<filesDir>/rootfs`. */
    fun rootfsDir(context: Context): File = File(context.filesDir, "rootfs")

    /** Directorio temporal de extracción atómica para evitar corrupción parcial. */
    fun rootfsStagingDir(context: Context): File = File(context.filesDir, "rootfs-staging")

    /** Marcador de versión del rootfs extraído exitosamente. */
    fun rootfsStampFile(context: Context): File = File(rootfsDir(context), ".dsh-rootfs-stamp")

    /** Marcador de bloqueo mientras se extrae el rootfs. */
    fun rootfsExtractingLock(context: Context): File = File(context.filesDir, ".rootfs-extracting.lock")

    /** Directorio privado para archivos temporales internos de PRoot (`PROOT_TMP_DIR`). */
    fun prootTmpDir(context: Context): File = File(context.filesDir, "proot-tmp").apply { mkdirs() }

    /**
     * Directorio para archivos de respaldo de enlaces duros emulados (`PROOT_L2S_DIR`).
     * DEBE situarse DENTRO de `<filesDir>/rootfs/.l2s` (igual que en `proot-distro`) para que
     * PRoot resuelva los enlaces simbólicos creados por `fs.link()` dentro del árbol `-r <rootfs>`.
     */
    fun prootL2sDir(context: Context): File = File(rootfsDir(context), ".l2s").apply { mkdirs() }

    /** Ubicación heredada de `PROOT_L2S_DIR` en versiones previas para migración automática. */
    fun legacyProotL2sDir(context: Context): File = File(context.filesDir, "proot-l2s")

    /** Directorio con sustitutos estáticos de `/proc/` cuando Android SELinux restringe su lectura. */
    fun hostSysdataDir(context: Context): File = File(context.filesDir, "container-sysdata").apply { mkdirs() }

    /**
     * Directorio auxiliar con enlaces simbólicos de librerías nativas de PRoot
     * (por ejemplo `libtalloc.so.2 -> <nativeLibraryDir>/libtalloc.so`).
     */
    fun prootLibsDir(context: Context): File = File(context.filesDir, "proot-libs").apply { mkdirs() }

    /** Respaldo opcional si los binarios de proot se empaquetan en `assets/proot/<abi>/`. */
    fun prootFallbackBinDir(context: Context): File = File(context.filesDir, "proot-bin")

    /** `/tmp` compartido entre el motor `dsh` y todas las shells `bash` del usuario. */
    fun sharedContainerTmpDir(context: Context): File =
        File(context.filesDir, "container-tmp").apply { mkdirs() }

    /** `/dev/shm` emulado en host (Android carece de `/dev/shm` nativo). */
    fun sharedContainerShmDir(context: Context): File =
        File(context.filesDir, "container-shm").apply { mkdirs() }

    /** Archivos `/etc/resolv.conf` y `/etc/hosts` mantenidos en host y montados con `-b`. */
    fun hostEtcDir(context: Context): File = File(context.filesDir, "container-etc").apply { mkdirs() }

    /** Espacio de trabajo persistente montado en `/root/workspace` dentro de proot. */
    fun workspaceHostDir(context: Context): File =
        File(context.filesDir, "workspace").apply { mkdirs() }

    /**
     * Raíz en host de los directorios expuestos por el puente SAF, montada en `/mnt/saf`.
     * Al montar este directorio padre al iniciar proot, cualquier subcarpeta o symlink añadido
     * posteriormente desde el selector SAF aparece de inmediato tanto en `dsh` como en `bash`.
     */
    fun safMountsHostDir(context: Context): File =
        File(context.filesDir, "saf-mounts").apply { mkdirs() }

    /** Directorio de logs de diagnóstico. */
    fun logsDir(context: Context): File = File(context.filesDir, "logs").apply { mkdirs() }

    /** Archivo de salida combinada stdout/stderr del motor `dsh web`. */
    fun engineLogFile(context: Context): File = File(logsDir(context), "dsh-engine.log")

    /** Resuelve el binario de Node.js como ruta guest (`/usr/local/bin/node`, etc.). */
    fun resolveGuestNodePath(rootfs: File): String? {
        val match = CANDIDATE_NODE_BINS.firstOrNull { rel ->
            val f = File(rootfs, rel)
            f.isFile || java.nio.file.Files.isSymbolicLink(f.toPath())
        }
        return match?.let { "/$it" }
    }

    /** Resuelve el entrypoint de `dsh` como ruta guest (`/opt/dsh/.../bin.js`, etc.). */
    fun resolveGuestDshEntry(rootfs: File): String? {
        val match = CANDIDATE_DSH_ENTRIES.firstOrNull { rel ->
            val f = File(rootfs, rel)
            f.isFile || java.nio.file.Files.isSymbolicLink(f.toPath())
        }
        return match?.let { "/$it" }
    }

    /** Resuelve la shell interactiva del usuario dentro del contenedor (`/bin/zsh` o `/bin/bash`). */
    fun resolveGuestInteractiveShell(rootfs: File): String {
        val match = CANDIDATE_INTERACTIVE_SHELLS.firstOrNull { rel ->
            val f = File(rootfs, rel)
            f.isFile || java.nio.file.Files.isSymbolicLink(f.toPath())
        }
        return match?.let { "/$it" } ?: "/bin/bash"
    }
}
