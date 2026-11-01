package io.deepseek.harness.android.saf

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.webkit.MimeTypeMap
import androidx.documentfile.provider.DocumentFile
import io.deepseek.harness.android.core.AppLogger
import io.deepseek.harness.android.core.AppPaths
import io.deepseek.harness.android.core.AppPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths

/**
 * Puente SAF (Storage Access Framework) hacia el contenedor PRoot compartido.
 *
 * Cómo se exponen las carpetas externas dentro del contenedor:
 * - El directorio del host `<filesDir>/saf-mounts` se monta siempre con `-b` en `/mnt/saf`
 *   (y tiene un atajo en `/root/saf`) tanto en el motor `dsh` como en la terminal `bash`.
 * - Al seleccionar una carpeta mediante `ACTION_OPEN_DOCUMENT_TREE`:
 *   1. Se persiste el permiso URI (`takePersistableUriPermission`).
 *   2. **Modo Directo (`DIRECT_SYMLINK`)**: si la carpeta pertenece al almacenamiento compartido
 *      y el proceso tiene acceso POSIX directo (ej. permiso "Acceso a todos los archivos" en
 *      Android 11+ o Android 10), se crea un enlace simbólico `<filesDir>/saf-mounts/<alias> -> <ruta_real>`.
 *      Los cambios desde `bash` o `dsh` ocurren en tiempo real y sin duplicar espacio.
 *   3. **Modo Espejo (`DOCUMENT_MIRROR`)**: si el usuario no concede `MANAGE_EXTERNAL_STORAGE`
 *      o elige una tarjeta SD restringida / USB OTG / nube, `SafBridgeManager` sincroniza el árbol
 *      `DocumentFile` en `<filesDir>/saf-mounts/<alias>` y permite sincronizar cambios de ida y
 *      vuelta (`SAF <-> Contenedor`) evitando errores `EPERM` de FUSE en escrituras atómicas (`git`, `dsh`).
 */
class SafBridgeManager(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = AppPreferences(appContext)

    private val _mounts = MutableStateFlow<List<SafMountEntry>>(emptyList())
    val mounts: StateFlow<List<SafMountEntry>> = _mounts.asStateFlow()

    init {
        val saved = prefs.loadSafMounts()
        _mounts.value = saved
    }

    fun hasAllFilesAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            true
        }

    /**
     * Reconstruye al arrancar los enlaces simbólicos de todos los montajes SAF persistidos.
     */
    suspend fun restorePersistedMounts() = withContext(Dispatchers.IO) {
        val current = prefs.loadSafMounts()
        val updated = current.map { entry ->
            materializeMountOnDisk(entry, performInitialMirrorCopy = false)
        }
        prefs.saveSafMounts(updated)
        _mounts.value = updated
    }

    /**
     * Registra una nueva carpeta elegida por el usuario en el selector SAF y la expone en `/mnt/saf/<alias>`.
     */
    suspend fun registerPickedTreeUri(uri: Uri): Result<SafMountEntry> = withContext(Dispatchers.IO) {
        runCatching {
            val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            try {
                appContext.contentResolver.takePersistableUriPermission(uri, takeFlags)
            } catch (e: SecurityException) {
                AppLogger.warn("SAF", "No se pudo persistir permiso de escritura; intentando solo lectura", e)
                appContext.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            val existing = _mounts.value.toMutableList()
            val already = existing.firstOrNull { it.treeUri == uri.toString() }
            if (already != null) {
                val refreshed = materializeMountOnDisk(already, performInitialMirrorCopy = true)
                val next = existing.map { if (it.alias == already.alias) refreshed else it }
                prefs.saveSafMounts(next)
                _mounts.value = next
                return@runCatching refreshed
            }

            val docTree = DocumentFile.fromTreeUri(appContext, uri)
            val rawLabel = docTree?.name
                ?: runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull()
                ?: "carpeta"
            val alias = SafPathResolver.sanitizeUniqueAlias(
                rawName = rawLabel,
                existingAliases = existing.map { it.alias }.toSet(),
            )
            val resolvedPath = SafPathResolver.resolveTreeUriToHostPath(uri)
            val directOk = SafPathResolver.canAccessPosixDirectoryDirectly(resolvedPath)

            val initialEntry = SafMountEntry(
                alias = alias,
                treeUri = uri.toString(),
                displayName = rawLabel,
                resolvedHostPath = resolvedPath,
                mode = if (directOk) SafAccessMode.DIRECT_SYMLINK else SafAccessMode.DOCUMENT_MIRROR,
                lastSyncedEpochMs = System.currentTimeMillis(),
            )

            val materialized = materializeMountOnDisk(initialEntry, performInitialMirrorCopy = true)
            existing.add(materialized)
            prefs.saveSafMounts(existing)
            _mounts.value = existing
            AppLogger.info(
                "SAF",
                "Carpeta montada en ${materialized.guestPath} (modo=${materialized.mode}, origen=${materialized.resolvedHostPath ?: materialized.treeUri})",
            )
            materialized
        }
    }

    /**
     * Sincroniza manualmente los cambios entre el contenedor (`/mnt/saf/<alias>`) y el `DocumentFile` SAF.
     * En modo `DIRECT_SYMLINK` verifica o promueve el enlace directo si el usuario acaba de otorgar permisos.
     */
    suspend fun syncMountBidirectional(alias: String): Result<SafMountEntry> = withContext(Dispatchers.IO) {
        runCatching {
            val current = _mounts.value.toMutableList()
            val idx = current.indexOfFirst { it.alias == alias }
            require(idx >= 0) { "No existe el punto de montaje SAF '$alias'" }

            val entry = current[idx]
            // Si ahora ya tiene permiso directo POSIX, actualizar a DIRECT_SYMLINK
            if (SafPathResolver.canAccessPosixDirectoryDirectly(entry.resolvedHostPath)) {
                val upgraded = materializeMountOnDisk(
                    entry.copy(mode = SafAccessMode.DIRECT_SYMLINK),
                    performInitialMirrorCopy = false,
                )
                current[idx] = upgraded
                prefs.saveSafMounts(current)
                _mounts.value = current
                return@runCatching upgraded
            }

            val hostMirrorDir = File(AppPaths.safMountsHostDir(appContext), entry.alias)
            val treeUri = Uri.parse(entry.treeUri)
            val rootDoc = DocumentFile.fromTreeUri(appContext, treeUri)
                ?: error("No se pudo abrir el árbol SAF para ${entry.displayName}")

            // 1. Exportar primero archivos nuevos o modificados desde el contenedor hacia SAF
            if (hostMirrorDir.isDirectory) {
                exportDirToDocumentTree(hostMirrorDir, rootDoc)
            }
            // 2. Importar archivos nuevos o actualizados desde SAF hacia el contenedor
            hostMirrorDir.mkdirs()
            importDocumentTreeToDir(rootDoc, hostMirrorDir)

            val synced = entry.copy(lastSyncedEpochMs = System.currentTimeMillis())
            current[idx] = synced
            prefs.saveSafMounts(current)
            _mounts.value = current
            AppLogger.info("SAF", "Sincronización bidireccional completada para ${synced.guestPath}")
            synced
        }
    }

    /**
     * Desmonta una carpeta SAF del contenedor (sin borrar nunca los archivos originales del usuario).
     */
    suspend fun unmount(alias: String) = withContext(Dispatchers.IO) {
        val current = _mounts.value.toMutableList()
        val removed = current.firstOrNull { it.alias == alias } ?: return@withContext
        current.removeAll { it.alias == alias }

        val mountTarget = File(AppPaths.safMountsHostDir(appContext), alias)
        val path = mountTarget.toPath()
        if (Files.isSymbolicLink(path)) {
            Files.deleteIfExists(path)
        } else if (mountTarget.exists()) {
            mountTarget.deleteRecursively()
        }

        prefs.saveSafMounts(current)
        _mounts.value = current
        AppLogger.info("SAF", "Desmontado punto SAF ${removed.guestPath}")
    }

    private fun materializeMountOnDisk(
        entry: SafMountEntry,
        performInitialMirrorCopy: Boolean,
    ): SafMountEntry {
        val safRoot = AppPaths.safMountsHostDir(appContext)
        val mountPoint = File(safRoot, entry.alias)
        val mountPath = mountPoint.toPath()

        val canUseDirectSymlink = SafPathResolver.canAccessPosixDirectoryDirectly(entry.resolvedHostPath)
        if (canUseDirectSymlink && entry.resolvedHostPath != null) {
            if (Files.isSymbolicLink(mountPath)) {
                val target = Files.readSymbolicLink(mountPath).toString()
                if (target != entry.resolvedHostPath) {
                    Files.deleteIfExists(mountPath)
                    Files.createSymbolicLink(mountPath, Paths.get(entry.resolvedHostPath))
                }
            } else {
                if (mountPoint.exists()) {
                    mountPoint.deleteRecursively()
                }
                Files.createSymbolicLink(mountPath, Paths.get(entry.resolvedHostPath))
            }
            return entry.copy(
                mode = SafAccessMode.DIRECT_SYMLINK,
                lastSyncedEpochMs = System.currentTimeMillis(),
            )
        }

        // Modo espejo DocumentFile
        if (Files.isSymbolicLink(mountPath)) {
            Files.deleteIfExists(mountPath)
        }
        mountPoint.mkdirs()

        if (performInitialMirrorCopy) {
            val treeDoc = DocumentFile.fromTreeUri(appContext, Uri.parse(entry.treeUri))
            if (treeDoc != null) {
                importDocumentTreeToDir(treeDoc, mountPoint)
            }
        }
        return entry.copy(
            mode = SafAccessMode.DOCUMENT_MIRROR,
            lastSyncedEpochMs = System.currentTimeMillis(),
        )
    }

    private fun importDocumentTreeToDir(sourceDoc: DocumentFile, targetDir: File) {
        targetDir.mkdirs()
        for (child in sourceDoc.listFiles()) {
            val name = child.name ?: continue
            if (name == "." || name == "..") continue
            val dst = File(targetDir, name)
            if (child.isDirectory) {
                importDocumentTreeToDir(child, dst)
            } else if (child.isFile) {
                val remoteModified = child.lastModified()
                if (!dst.isFile || (remoteModified > 0L && remoteModified > dst.lastModified() + 1_000L)) {
                    try {
                        appContext.contentResolver.openInputStream(child.uri)?.use { input ->
                            dst.outputStream().use { out -> input.copyTo(out) }
                        }
                        if (remoteModified > 0L) {
                            dst.setLastModified(remoteModified)
                        }
                    } catch (e: Exception) {
                        AppLogger.warn("SAF", "No se pudo importar archivo SAF $name", e)
                    }
                }
            }
        }
    }

    private fun exportDirToDocumentTree(localDir: File, targetDocDir: DocumentFile) {
        val remoteChildrenByName = targetDocDir.listFiles()
            .mapNotNull { doc -> doc.name?.let { it to doc } }
            .toMap()

        localDir.listFiles()?.forEach { localChild ->
            val name = localChild.name
            if (localChild.isDirectory) {
                val remoteSubDir = remoteChildrenByName[name]?.takeIf { it.isDirectory }
                    ?: targetDocDir.createDirectory(name)
                if (remoteSubDir != null) {
                    exportDirToDocumentTree(localChild, remoteSubDir)
                }
            } else if (localChild.isFile) {
                val existingDoc = remoteChildrenByName[name]?.takeIf { it.isFile }
                val shouldWrite = existingDoc == null ||
                    localChild.lastModified() > existingDoc.lastModified() + 1_000L ||
                    localChild.length() != existingDoc.length()

                if (shouldWrite) {
                    val mime = guessMimeType(name)
                    val destDoc = existingDoc ?: targetDocDir.createFile(mime, name)
                    if (destDoc != null) {
                        try {
                            appContext.contentResolver.openOutputStream(destDoc.uri, "wt")?.use { out ->
                                localChild.inputStream().use { input -> input.copyTo(out) }
                            }
                        } catch (e: Exception) {
                            AppLogger.warn("SAF", "No se pudo exportar archivo $name hacia SAF", e)
                        }
                    }
                }
            }
        }
    }

    private fun guessMimeType(fileName: String): String {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        if (ext.isEmpty()) return "application/octet-stream"
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            ?: "application/octet-stream"
    }
}
