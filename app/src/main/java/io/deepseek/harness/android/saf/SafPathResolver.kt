package io.deepseek.harness.android.saf

import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import java.io.File
import java.util.Locale

/**
 * Utilidades puras para traducir URIs de `ACTION_OPEN_DOCUMENT_TREE` a rutas POSIX reales
 * cuando pertenecen al proveedor `com.android.externalstorage.documents` y para generar
 * nombres de punto de montaje seguros en Linux (`/mnt/saf/<alias>`).
 */
object SafPathResolver {

    private const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"

    /**
     * Resuelve un `treeDocumentId` (ej. `"primary:Proyectos/App"` o `"1A2B-3C4D:Datos"`)
     * a su ruta en el sistema de archivos de Android si es determinable.
     */
    fun resolveFromTreeDocumentId(
        authority: String?,
        treeDocId: String,
        primaryExternalRoot: String,
    ): String? {
        if (authority != EXTERNAL_STORAGE_AUTHORITY) return null
        val colonIdx = treeDocId.indexOf(':')
        val volume = if (colonIdx >= 0) treeDocId.substring(0, colonIdx) else treeDocId
        val relative = if (colonIdx >= 0) treeDocId.substring(colonIdx + 1).trim('/') else ""

        return when {
            volume.equals("primary", ignoreCase = true) -> {
                if (relative.isEmpty()) primaryExternalRoot else "$primaryExternalRoot/$relative"
            }

            volume.equals("home", ignoreCase = true) -> {
                val docs = "$primaryExternalRoot/Documents"
                if (relative.isEmpty()) docs else "$docs/$relative"
            }

            volume.isNotBlank() -> {
                val removableRoot = "/storage/$volume"
                if (relative.isEmpty()) removableRoot else "$removableRoot/$relative"
            }

            else -> null
        }
    }

    fun resolveTreeUriToHostPath(uri: Uri): String? =
        try {
            val docId = DocumentsContract.getTreeDocumentId(uri)
            val primaryRoot = Environment.getExternalStorageDirectory().absolutePath
            resolveFromTreeDocumentId(uri.authority, docId, primaryRoot)
        } catch (_: Exception) {
            null
        }

    /**
     * Convierte cualquier etiqueta de carpeta en un nombre de directorio POSIX limpio (`[a-z0-9._-]`)
     * evitando colisiones con alias existentes.
     */
    fun sanitizeUniqueAlias(rawName: String, existingAliases: Set<String>): String {
        val normalized = rawName
            .substringAfterLast('/')
            .substringAfterLast(':')
            .lowercase(Locale.ROOT)
            .replace(Regex("""[^a-z0-9._-]+"""), "-")
            .trim('-', '.')
            .ifBlank { "carpeta" }
            .take(40)

        if (normalized !in existingAliases) return normalized
        var suffix = 2
        while ("$normalized-$suffix" in existingAliases) {
            suffix++
        }
        return "$normalized-$suffix"
    }

    fun canAccessPosixDirectoryDirectly(path: String?): Boolean {
        if (path.isNullOrBlank()) return false
        val dir = File(path)
        return dir.isDirectory && dir.canRead() && dir.canWrite()
    }
}
