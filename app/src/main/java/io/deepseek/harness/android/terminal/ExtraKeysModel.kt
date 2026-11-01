package io.deepseek.harness.android.terminal

import android.view.KeyEvent
import com.termux.terminal.KeyHandler
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Definición de teclas extra para uso móvil en la terminal integrada:
 * incluye modificadores persistentes/de un toque (Ctrl, Alt), navegación (Esc, Tab,
 * flechas, Inicio/Home, Fin/End, RePág, AvPág), atajos de señal (Ctrl+C, Ctrl+D, Ctrl+L)
 * y símbolos frecuentes en Linux.
 */
sealed interface ExtraKeySpec {
    val label: String

    data class ModifierToggle(
        override val label: String,
        val modifier: ModifierKind,
    ) : ExtraKeySpec

    data class KeyCodeKey(
        override val label: String,
        val keyCode: Int,
        val keyMod: Int = 0,
    ) : ExtraKeySpec

    data class TextKey(
        override val label: String,
        val text: String,
        val ctrlChord: Boolean = false,
    ) : ExtraKeySpec
}

enum class ModifierKind {
    CTRL,
    ALT,
    SHIFT,
}

class ExtraKeysController {

    private val _ctrlActive = MutableStateFlow(false)
    val ctrlActive: StateFlow<Boolean> = _ctrlActive.asStateFlow()

    private val _ctrlLocked = MutableStateFlow(false)
    val ctrlLocked: StateFlow<Boolean> = _ctrlLocked.asStateFlow()

    private val _altActive = MutableStateFlow(false)
    val altActive: StateFlow<Boolean> = _altActive.asStateFlow()

    private val _altLocked = MutableStateFlow(false)
    val altLocked: StateFlow<Boolean> = _altLocked.asStateFlow()

    private val _shiftActive = MutableStateFlow(false)
    val shiftActive: StateFlow<Boolean> = _shiftActive.asStateFlow()

    val primaryRow: List<ExtraKeySpec> = listOf(
        ExtraKeySpec.KeyCodeKey("ESC", KeyEvent.KEYCODE_ESCAPE),
        ExtraKeySpec.KeyCodeKey("TAB", KeyEvent.KEYCODE_TAB),
        ExtraKeySpec.ModifierToggle("CTRL", ModifierKind.CTRL),
        ExtraKeySpec.ModifierToggle("ALT", ModifierKind.ALT),
        ExtraKeySpec.KeyCodeKey("←", KeyEvent.KEYCODE_DPAD_LEFT),
        ExtraKeySpec.KeyCodeKey("↓", KeyEvent.KEYCODE_DPAD_DOWN),
        ExtraKeySpec.KeyCodeKey("↑", KeyEvent.KEYCODE_DPAD_UP),
        ExtraKeySpec.KeyCodeKey("→", KeyEvent.KEYCODE_DPAD_RIGHT),
        ExtraKeySpec.KeyCodeKey("INICIO", KeyEvent.KEYCODE_MOVE_HOME),
        ExtraKeySpec.KeyCodeKey("FIN", KeyEvent.KEYCODE_MOVE_END),
    )

    val secondaryRow: List<ExtraKeySpec> = listOf(
        ExtraKeySpec.TextKey("^C", "c", ctrlChord = true),
        ExtraKeySpec.TextKey("^D", "d", ctrlChord = true),
        ExtraKeySpec.TextKey("^Z", "z", ctrlChord = true),
        ExtraKeySpec.TextKey("^L", "l", ctrlChord = true),
        ExtraKeySpec.KeyCodeKey("REPÁG", KeyEvent.KEYCODE_PAGE_UP),
        ExtraKeySpec.KeyCodeKey("AVPÁG", KeyEvent.KEYCODE_PAGE_DOWN),
        ExtraKeySpec.TextKey("-", "-"),
        ExtraKeySpec.TextKey("/", "/"),
        ExtraKeySpec.TextKey("|", "|"),
        ExtraKeySpec.TextKey("~", "~"),
        ExtraKeySpec.TextKey("_", "_"),
        ExtraKeySpec.TextKey("$", "$"),
    )

    fun onModifierTap(kind: ModifierKind, lock: Boolean = false) {
        when (kind) {
            ModifierKind.CTRL -> {
                if (lock) {
                    val next = !_ctrlLocked.value
                    _ctrlLocked.value = next
                    _ctrlActive.value = next
                } else {
                    if (_ctrlLocked.value) {
                        _ctrlLocked.value = false
                        _ctrlActive.value = false
                    } else {
                        _ctrlActive.value = !_ctrlActive.value
                    }
                }
            }

            ModifierKind.ALT -> {
                if (lock) {
                    val next = !_altLocked.value
                    _altLocked.value = next
                    _altActive.value = next
                } else {
                    if (_altLocked.value) {
                        _altLocked.value = false
                        _altActive.value = false
                    } else {
                        _altActive.value = !_altActive.value
                    }
                }
            }

            ModifierKind.SHIFT -> {
                _shiftActive.value = !_shiftActive.value
            }
        }
    }

    /** Consumido por `TerminalViewClient.readControlKey()` al pulsar cualquier tecla del teclado. */
    fun consumeCtrlModifier(): Boolean {
        val active = _ctrlActive.value
        if (active && !_ctrlLocked.value) {
            _ctrlActive.value = false
        }
        return active
    }

    /** Consumido por `TerminalViewClient.readAltKey()` al pulsar cualquier tecla del teclado. */
    fun consumeAltModifier(): Boolean {
        val active = _altActive.value
        if (active && !_altLocked.value) {
            _altActive.value = false
        }
        return active
    }

    fun consumeShiftModifier(): Boolean {
        val active = _shiftActive.value
        if (active) {
            _shiftActive.value = false
        }
        return active
    }

    /**
     * Envía la tecla extra seleccionada a la sesión/vista PTY activa.
     */
    fun dispatchExtraKey(
        spec: ExtraKeySpec,
        terminalView: TerminalView?,
        session: TerminalSession?,
    ) {
        when (spec) {
            is ExtraKeySpec.ModifierToggle -> onModifierTap(spec.modifier, lock = false)

            is ExtraKeySpec.KeyCodeKey -> {
                var keyMod = spec.keyMod
                if (consumeCtrlModifier()) keyMod = keyMod or KeyHandler.KEYMOD_CTRL
                if (consumeAltModifier()) keyMod = keyMod or KeyHandler.KEYMOD_ALT
                if (consumeShiftModifier()) keyMod = keyMod or KeyHandler.KEYMOD_SHIFT

                if (terminalView != null && terminalView.mEmulator != null && terminalView.currentSession != null) {
                    terminalView.handleKeyCode(spec.keyCode, keyMod)
                } else if (session != null) {
                    val emulator = session.emulator
                    val code = KeyHandler.getCode(
                        spec.keyCode,
                        keyMod,
                        emulator?.isCursorKeysApplicationMode == true,
                        emulator?.isKeypadApplicationMode == true,
                    )
                    if (code != null) {
                        session.write(code)
                    }
                }
            }

            is ExtraKeySpec.TextKey -> {
                val ctrl = spec.ctrlChord || consumeCtrlModifier()
                val alt = consumeAltModifier()
                val codePoint = spec.text.codePointAt(0)
                if (terminalView != null) {
                    terminalView.inputCodePoint(codePoint, ctrl, alt)
                } else if (session != null) {
                    if (ctrl && codePoint in 'a'.code..'z'.code) {
                        val ctrlByte = byteArrayOf((codePoint - 'a'.code + 1).toByte())
                        session.write(ctrlByte, 0, 1)
                    } else {
                        if (alt) session.write("\u001b")
                        session.write(spec.text)
                    }
                }
            }
        }
    }
}
