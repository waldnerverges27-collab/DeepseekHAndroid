package io.deepseek.harness.android.saf

import io.deepseek.harness.android.core.AppPaths
import org.json.JSONObject

/**
 * Modo en que una carpeta seleccionada con SAF se expone dentro del contenedor PRoot:
 * - [DIRECT_SYMLINK]: enlace simbólico POSIX directo a la ruta real del almacenamiento
 *   primario/extraíble (cero copias, cambios instantáneos en tiempo real).
 * - [DOCUMENT_MIRROR]: espejo bidireccional gestionado con `DocumentFile` cuando Android
 *   restringe el acceso POSIX directo (Scoped Storage estricto sin `MANAGE_EXTERNAL_STORAGE`,
 *   unidades USB OTG o proveedores de documentos en la nube).
 */
enum class SafAccessMode {
    DIRECT_SYMLINK,
    DOCUMENT_MIRROR,
}

data class SafMountEntry(
    val alias: String,
    val treeUri: String,
    val displayName: String,
    val resolvedHostPath: String?,
    val mode: SafAccessMode,
    val lastSyncedEpochMs: Long,
) {
    /** Ruta canónica dentro del contenedor PRoot compartido (`dsh` + `bash`). */
    val guestPath: String
        get() = "${AppPaths.GUEST_SAF_MOUNT_ROOT}/$alias"

    /** Atajo adicional en `$HOME` dentro del contenedor (`/root/saf/<alias>`). */
    val guestHomeShortcut: String
        get() = "${AppPaths.GUEST_SAF_SYMLINK_IN_HOME}/$alias"

    fun toJson(): JSONObject = JSONObject().apply {
        put("alias", alias)
        put("treeUri", treeUri)
        put("displayName", displayName)
        if (resolvedHostPath != null) put("resolvedHostPath", resolvedHostPath)
        put("mode", mode.name)
        put("lastSyncedEpochMs", lastSyncedEpochMs)
    }

    companion object {
        fun fromJson(obj: JSONObject): SafMountEntry? {
            val alias = obj.optString("alias", "").trim()
            val treeUri = obj.optString("treeUri", "").trim()
            if (alias.isEmpty() || treeUri.isEmpty()) return null
            val displayName = obj.optString("displayName", alias)
            val resolved = obj.optString("resolvedHostPath", "").ifBlank { null }
            val modeName = obj.optString("mode", SafAccessMode.DOCUMENT_MIRROR.name)
            val mode = try {
                SafAccessMode.valueOf(modeName)
            } catch (_: IllegalArgumentException) {
                SafAccessMode.DOCUMENT_MIRROR
            }
            return SafMountEntry(
                alias = alias,
                treeUri = treeUri,
                displayName = displayName,
                resolvedHostPath = resolved,
                mode = mode,
                lastSyncedEpochMs = obj.optLong("lastSyncedEpochMs", 0L),
            )
        }
    }
}
