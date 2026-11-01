package io.deepseek.harness.android.container

import android.content.Context
import io.deepseek.harness.android.core.AppLogger
import io.deepseek.harness.android.core.AppPaths
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Paths

/**
 * Progreso detallado de la extracción inicial del rootfs embebido en los assets del APK.
 */
data class ExtractionProgress(
    val bytesCompressedRead: Long,
    val totalCompressedBytes: Long,
    val entriesExtracted: Int,
    val currentEntry: String,
) {
    val fraction: Float
        get() = if (totalCompressedBytes > 0L) {
            (bytesCompressedRead.toDouble() / totalCompressedBytes.toDouble()).toFloat().coerceIn(0f, 1f)
        } else {
            0f
        }
}

/**
 * Gestiona la verificación e instalación atómica de `assets/rootfs.tar.xz` en `<filesDir>/rootfs`.
 *
 * Características clave:
 * - Extracción atómica con archivo `.rootfs-extracting.lock` y sello `.dsh-rootfs-stamp`:
 *   si el usuario cierra la app a mitad de la primera extracción, el siguiente inicio
 *   detecta el estado incompleto y limpia antes de reextraer sin corromper el entorno.
 * - Si se actualiza el APK con una versión nueva en `assets/rootfs.version`, respalda
 *   automáticamente `/root/.dsh` (credenciales, sesiones y skills) y lo restaura tras actualizar.
 * - Protección contra ataques TarSlip (validación canónica de rutas).
 * - Preservación de enlaces simbólicos, materialización de enlaces duros y eliminación
 *   del bit de escritura en ejecutables/librerías (`.so`, `.node`) para cumplir políticas W^X
 *   de Android 10–15+ y capas OEM (Huawei/EMUI, Xiaomi, Samsung).
 */
object RootfsExtractor {

    private val extractionLock = Any()

    fun readBundledVersion(context: Context): String =
        try {
            context.assets.open(AppPaths.ASSET_ROOTFS_VERSION).bufferedReader().use {
                it.readText().trim().ifEmpty { "1.0.0" }
            }
        } catch (_: Exception) {
            "1.0.0"
        }

    /**
     * Comprueba si el rootfs ya está extraído íntegramente, sin bloqueo pendiente,
     * con la versión actual y con `bash`, `node` y `dsh` presentes.
     */
    fun isRootfsReady(context: Context): Boolean {
        val rootfs = AppPaths.rootfsDir(context)
        val lock = AppPaths.rootfsExtractingLock(context)
        val stamp = AppPaths.rootfsStampFile(context)

        if (lock.exists() || !rootfs.isDirectory || !stamp.isFile) {
            return false
        }
        val installedVersion = try {
            stamp.readText().trim()
        } catch (_: Exception) {
            ""
        }
        if (installedVersion != readBundledVersion(context)) {
            return false
        }
        val bashExists = File(rootfs, "bin/bash").exists() || File(rootfs, "usr/bin/bash").exists()
        val nodeExists = AppPaths.resolveGuestNodePath(rootfs) != null
        val dshExists = AppPaths.resolveGuestDshEntry(rootfs) != null
        return bashExists && nodeExists && dshExists
    }

    /**
     * Extrae `assets/rootfs.tar.xz` en `<filesDir>/rootfs` si aún no está listo o si `force = true`.
     */
    fun ensureExtracted(
        context: Context,
        force: Boolean = false,
        onProgress: (ExtractionProgress) -> Unit = {},
    ) {
        synchronized(extractionLock) {
            if (!force && isRootfsReady(context)) {
                AppLogger.info("Rootfs", "Rootfs ya verificado (${readBundledVersion(context)}), reutilizando.")
                return
            }

            val rootfsDir = AppPaths.rootfsDir(context)
            val stagingDir = AppPaths.rootfsStagingDir(context)
            val lockFile = AppPaths.rootfsExtractingLock(context)
            val dshBackupDir = File(context.filesDir, "dsh-home-backup")
            val l2sBackupDir = File(context.filesDir, "dsh-l2s-backup")
            val bundledVersion = readBundledVersion(context)

            AppLogger.info("Rootfs", "Iniciando extracción de ${AppPaths.ASSET_ROOTFS_ARCHIVE} (versión=$bundledVersion)")
            lockFile.parentFile?.mkdirs()
            lockFile.writeText(System.currentTimeMillis().toString())

            // Migrar y reparar enlaces proot-l2s previos antes de respaldar /root/.dsh
            if (rootfsDir.isDirectory) {
                try {
                    ProotEnvironment.repairAndCanonicalizeProotL2s(
                        rawRootfsDir = rootfsDir,
                        legacyL2sDir = AppPaths.legacyProotL2sDir(context),
                    )
                } catch (_: Exception) {
                }
            }

            // Respaldar /root/.dsh y /.l2s si existía una instalación previa (conserva API keys y sesiones)
            val existingDshHome = File(rootfsDir, "root/.dsh")
            if (existingDshHome.isDirectory) {
                try {
                    restoreWritableRecursive(dshBackupDir)
                    dshBackupDir.deleteRecursively()
                    copyDirectoryPreservingPermissions(existingDshHome, dshBackupDir)
                    AppLogger.info("Rootfs", "Respaldado /root/.dsh previo antes de actualizar rootfs")
                } catch (e: Exception) {
                    AppLogger.warn("Rootfs", "No se pudo respaldar /root/.dsh previo", e)
                }
            }
            val existingL2sDir = File(rootfsDir, ".l2s")
            if (existingL2sDir.isDirectory) {
                try {
                    restoreWritableRecursive(l2sBackupDir)
                    l2sBackupDir.deleteRecursively()
                    copyDirectoryPreservingPermissions(existingL2sDir, l2sBackupDir)
                } catch (e: Exception) {
                    AppLogger.warn("Rootfs", "No se pudo respaldar /.l2s previo", e)
                }
            }

            restoreWritableRecursive(stagingDir)
            stagingDir.deleteRecursively()
            stagingDir.mkdirs()

            val totalCompressedBytes = try {
                context.assets.openFd(AppPaths.ASSET_ROOTFS_ARCHIVE).use { it.length }
            } catch (_: Exception) {
                0L
            }

            context.assets.open(AppPaths.ASSET_ROOTFS_ARCHIVE).use { rawAssetStream ->
                extractTarXzStream(
                    rawInput = rawAssetStream,
                    totalCompressedBytes = totalCompressedBytes,
                    destRoot = stagingDir,
                    onProgress = onProgress,
                )
            }

            // Restaurar /.l2s y /root/.dsh respaldados si procede
            if (l2sBackupDir.isDirectory) {
                try {
                    val targetL2s = File(stagingDir, ".l2s")
                    copyDirectoryPreservingPermissions(l2sBackupDir, targetL2s)
                    l2sBackupDir.deleteRecursively()
                } catch (e: Exception) {
                    AppLogger.warn("Rootfs", "Error restaurando respaldo de /.l2s", e)
                }
            }
            if (dshBackupDir.isDirectory) {
                try {
                    val targetDshHome = File(stagingDir, "root/.dsh")
                    copyDirectoryPreservingPermissions(dshBackupDir, targetDshHome)
                    dshBackupDir.deleteRecursively()
                } catch (e: Exception) {
                    AppLogger.warn("Rootfs", "Error restaurando respaldo de /root/.dsh", e)
                }
            }

            // Asegurar directorios base de montaje para proot
            listOf("dev", "proc", "sys", "tmp", "etc", ".l2s", "root/workspace", "mnt/saf").forEach { rel ->
                File(stagingDir, rel).mkdirs()
            }

            // Intercambio atómico staging -> rootfs
            restoreWritableRecursive(rootfsDir)
            rootfsDir.deleteRecursively()
            if (!stagingDir.renameTo(rootfsDir)) {
                throw IOException("No se pudo mover rootfs-staging a ${rootfsDir.absolutePath}")
            }

            AppPaths.rootfsStampFile(context).writeText(bundledVersion)
            lockFile.delete()
            AppLogger.info("Rootfs", "Extracción completada exitosamente en ${rootfsDir.absolutePath}")
        }
    }

    /**
     * Extrae un flujo `.tar.xz` arbitrario dentro de [destRoot].
     * Expuesto como método independiente para pruebas unitarias directas sin `Context`.
     */
    fun extractTarXzStream(
        rawInput: InputStream,
        totalCompressedBytes: Long,
        destRoot: File,
        onProgress: (ExtractionProgress) -> Unit = {},
    ) {
        destRoot.mkdirs()
        val destCanonical = destRoot.canonicalPath
        val countingIn = CountingInputStream(BufferedInputStream(rawInput, 64 * 1024))

        XZCompressorInputStream(countingIn).use { xzIn ->
            TarArchiveInputStream(xzIn).use { tarIn ->
                val ioBuffer = ByteArray(64 * 1024)
                var count = 0
                var lastReportBytes = 0L

                var entry: TarArchiveEntry? = tarIn.nextEntry
                while (entry != null) {
                    val cleanName = entry.name
                        .removePrefix("./")
                        .removePrefix("/")
                    if (cleanName.isNotEmpty()) {
                        extractSingleEntry(
                            tarIn = tarIn,
                            entry = entry,
                            cleanName = cleanName,
                            destRoot = destRoot,
                            destCanonical = destCanonical,
                            ioBuffer = ioBuffer,
                        )
                        count++
                        val readNow = countingIn.bytesRead
                        if (readNow - lastReportBytes >= 256 * 1024L || count % 150 == 0) {
                            lastReportBytes = readNow
                            onProgress(
                                ExtractionProgress(
                                    bytesCompressedRead = readNow,
                                    totalCompressedBytes = totalCompressedBytes,
                                    entriesExtracted = count,
                                    currentEntry = cleanName,
                                ),
                            )
                        }
                    }
                    entry = tarIn.nextEntry
                }

                onProgress(
                    ExtractionProgress(
                        bytesCompressedRead = if (totalCompressedBytes > 0) totalCompressedBytes else countingIn.bytesRead,
                        totalCompressedBytes = totalCompressedBytes,
                        entriesExtracted = count,
                        currentEntry = "Completado",
                    ),
                )
            }
        }
    }

    private fun extractSingleEntry(
        tarIn: TarArchiveInputStream,
        entry: TarArchiveEntry,
        cleanName: String,
        destRoot: File,
        destCanonical: String,
        ioBuffer: ByteArray,
    ) {
        // Ignorar nodos de dispositivos especiales (/dev/*): Android sin root prohíbe mknod()
        // y proot monta el /dev real del host.
        if (entry.isCharacterDevice || entry.isBlockDevice || entry.isFIFO) {
            return
        }

        val target = resolveSafeTarget(destRoot, destCanonical, cleanName)

        when {
            entry.isDirectory -> {
                target.mkdirs()
            }

            entry.isSymbolicLink -> {
                target.parentFile?.mkdirs()
                deleteExistingFileOrSymlink(target)
                Files.createSymbolicLink(target.toPath(), Paths.get(entry.linkName))
            }

            entry.isLink -> {
                // Enlaces duros: materializar copia independiente con los mismos permisos
                val cleanLink = entry.linkName.removePrefix("./").removePrefix("/")
                val linkSource = resolveSafeTarget(destRoot, destCanonical, cleanLink)
                if (linkSource.isFile) {
                    target.parentFile?.mkdirs()
                    deleteExistingFileOrSymlink(target)
                    linkSource.copyTo(target, overwrite = true)
                    val isExec = linkSource.canExecute()
                    applyFileSecurityPermissions(target, isExec)
                }
            }

            entry.isFile -> {
                target.parentFile?.mkdirs()
                if (target.exists()) {
                    target.setWritable(true, true)
                }
                deleteExistingFileOrSymlink(target)

                target.outputStream().use { out ->
                    var read = tarIn.read(ioBuffer)
                    while (read >= 0) {
                        out.write(ioBuffer, 0, read)
                        read = tarIn.read(ioBuffer)
                    }
                }

                val isExecutable = (entry.mode and 0b001_001_001) != 0
                applyFileSecurityPermissions(target, isExecutable)
            }
        }
    }

    private fun applyFileSecurityPermissions(file: File, isExecutable: Boolean) {
        // Restringir lectura a propietario (requerido por .credentials.yaml de dsh)
        file.setReadable(false, false)
        file.setReadable(true, true)
        file.setWritable(true, true)

        val isSharedObject = file.name.endsWith(".so") ||
            file.name.contains(".so.") ||
            file.name.endsWith(".node")

        if (isExecutable) {
            if (!file.setExecutable(true, true)) {
                file.setExecutable(true, false)
            }
        } else {
            file.setExecutable(false, false)
        }

        // Política W^X (Write XOR Execute): binarios ejecutables y módulos nativos (.so/.node)
        // no deben conservar simultáneamente permiso de escritura para evitar fallos dlopen/mmap
        // PROT_EXEC en Android 10–15+ y kernels EMUI/HyperOS.
        if (isExecutable || isSharedObject) {
            file.setWritable(false, false)
        }
    }

    internal fun resolveSafeTarget(destRoot: File, destCanonical: String, entryPath: String): File {
        val candidate = File(destRoot, entryPath)
        // Evitar seguir un symlink padre fuera de destRoot comprobando primero el padre y nombre
        val normalizedPath = destRoot.toPath().resolve(entryPath).normalize()
        val rootPath = destRoot.toPath().toAbsolutePath().normalize()
        if (!normalizedPath.toAbsolutePath().startsWith(rootPath)) {
            throw IOException("Entrada tar insegura fuera del rootfs (TarSlip bloqueado): $entryPath")
        }
        val parentCanonical = candidate.parentFile?.canonicalPath ?: destCanonical
        if (parentCanonical != destCanonical && !parentCanonical.startsWith(destCanonical + File.separator)) {
            throw IOException("Ruta canónica escapa del directorio destino: $entryPath")
        }
        return candidate
    }

    private fun deleteExistingFileOrSymlink(file: File) {
        val path = file.toPath()
        if (Files.isSymbolicLink(path) || file.exists()) {
            file.setWritable(true, true)
            Files.deleteIfExists(path)
        }
    }

    private fun restoreWritableRecursive(dir: File) {
        if (!dir.exists()) return
        val path = dir.toPath()
        if (Files.isSymbolicLink(path)) return
        dir.setWritable(true, true)
        if (dir.isDirectory) {
            dir.listFiles()?.forEach { child -> restoreWritableRecursive(child) }
        }
    }

    private fun copyDirectoryPreservingPermissions(src: File, dst: File) {
        val srcPath = src.toPath()
        if (Files.isSymbolicLink(srcPath)) {
            dst.parentFile?.mkdirs()
            Files.deleteIfExists(dst.toPath())
            Files.createSymbolicLink(dst.toPath(), Files.readSymbolicLink(srcPath))
            return
        }
        if (!src.exists()) return
        if (src.isDirectory) {
            dst.mkdirs()
            src.listFiles()?.forEach { child ->
                copyDirectoryPreservingPermissions(child, File(dst, child.name))
            }
        } else {
            dst.parentFile?.mkdirs()
            src.copyTo(dst, overwrite = true)
            dst.setReadable(false, false)
            dst.setReadable(true, true)
            dst.setWritable(true, true)
            if (src.canExecute()) {
                dst.setExecutable(true, true)
            }
        }
    }

    private class CountingInputStream(input: InputStream) : FilterInputStream(input) {
        @Volatile
        var bytesRead: Long = 0L
            private set

        override fun read(): Int {
            val b = super.read()
            if (b >= 0) bytesRead++
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = super.read(b, off, len)
            if (n > 0) bytesRead += n
            return n
        }
    }
}
