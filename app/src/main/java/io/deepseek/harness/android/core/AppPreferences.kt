package io.deepseek.harness.android.core

import android.content.Context
import android.content.SharedPreferences
import io.deepseek.harness.android.saf.SafMountEntry
import org.json.JSONArray
import org.json.JSONObject

/**
 * Preferencias persistentes de la aplicación (puerto del motor, opciones de PRoot,
 * tamaño de fuente de la terminal y puntos de montaje SAF guardados).
 */
class AppPreferences(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var enginePort: Int
        get() = prefs.getInt(KEY_ENGINE_PORT, AppPaths.DEFAULT_ENGINE_PORT)
        set(value) = prefs.edit().putInt(KEY_ENGINE_PORT, value.coerceIn(1024, 65535)).apply()

    var terminalFontSizeSp: Int
        get() = prefs.getInt(KEY_TERMINAL_FONT_SP, 13)
        set(value) = prefs.edit().putInt(KEY_TERMINAL_FONT_SP, value.coerceIn(9, 28)).apply()

    /**
     * `PROOT_NO_SECCOMP=1`: desactiva la aceleración BPF seccomp de proot cuando el
     * filtro seccomp de Android Zygote entra en conflicto en ciertos kernels OEM (Samsung/Xiaomi).
     * Activado por defecto en true para máxima compatibilidad entre Android 10–15+.
     */
    var prootNoSeccomp: Boolean
        get() = prefs.getBoolean(KEY_PROOT_NO_SECCOMP, true)
        set(value) = prefs.edit().putBoolean(KEY_PROOT_NO_SECCOMP, value).apply()

    var keepScreenOn: Boolean
        get() = prefs.getBoolean(KEY_KEEP_SCREEN_ON, false)
        set(value) = prefs.edit().putBoolean(KEY_KEEP_SCREEN_ON, value).apply()

    fun loadSafMounts(): List<SafMountEntry> {
        val raw = prefs.getString(KEY_SAF_MOUNTS, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    SafMountEntry.fromJson(obj)?.let { add(it) }
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun saveSafMounts(entries: List<SafMountEntry>) {
        val arr = JSONArray()
        entries.forEach { entry ->
            arr.put(entry.toJson())
        }
        prefs.edit().putString(KEY_SAF_MOUNTS, arr.toString()).apply()
    }

    companion object {
        private const val PREFS_NAME = "dsh_android_prefs"
        private const val KEY_ENGINE_PORT = "engine_port"
        private const val KEY_TERMINAL_FONT_SP = "terminal_font_sp"
        private const val KEY_PROOT_NO_SECCOMP = "proot_no_seccomp"
        private const val KEY_KEEP_SCREEN_ON = "keep_screen_on"
        private const val KEY_SAF_MOUNTS = "saf_mounts_json"
    }
}
