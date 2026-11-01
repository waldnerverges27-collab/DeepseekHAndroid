package io.deepseek.harness.android.terminal

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import io.deepseek.harness.android.core.AppLogger

/**
 * Implementación de [TerminalSessionClient] y [TerminalViewClient] para enlazar el motor PTY
 * (`libtermux.so`) con portapapeles de Android, teclado virtual IME, pellizco de zoom,
 * barra de teclas extra móvil y redimensionamiento automático `TIOCSWINSZ`.
 */
class DshTerminalClient(
    private val appContext: Context,
    private val extraKeys: ExtraKeysController,
    private val onScreenUpdateCallback: () -> Unit,
    private val onTitleChangedCallback: (TerminalSession) -> Unit,
    private val onSessionFinishedCallback: (TerminalSession) -> Unit,
    private val onFontSizeScale: (Float) -> Float,
) : TerminalSessionClient, TerminalViewClient {

    var attachedView: TerminalView? = null

    // ---------------- TerminalSessionClient ----------------

    override fun onTextChanged(changedSession: TerminalSession) {
        attachedView?.onScreenUpdated()
        onScreenUpdateCallback()
    }

    override fun onTitleChanged(changedSession: TerminalSession) {
        onTitleChangedCallback(changedSession)
    }

    override fun onSessionFinished(finishedSession: TerminalSession) {
        AppLogger.info("Terminal", "Sesión bash finalizada (exit=${finishedSession.exitStatus})")
        onSessionFinishedCallback(finishedSession)
    }

    override fun onCopyTextToClipboard(session: TerminalSession, text: String?) {
        if (text.isNullOrEmpty()) return
        val cm = appContext.getSystemService(ClipboardManager::class.java) ?: return
        cm.setPrimaryClip(ClipData.newPlainText("DeepSeek Terminal", text))
    }

    override fun onPasteTextFromClipboard(session: TerminalSession?) {
        val targetSession = session ?: attachedView?.currentSession ?: return
        val cm = appContext.getSystemService(ClipboardManager::class.java) ?: return
        val clip = cm.primaryClip ?: return
        if (clip.itemCount <= 0) return
        val text = clip.getItemAt(0).coerceToText(appContext)?.toString() ?: return
        if (text.isNotEmpty()) {
            targetSession.emulator?.paste(text)
        }
    }

    override fun onBell(session: TerminalSession) {
        // Silencioso o vibración háptica ligera en la vista
        attachedView?.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
    }

    override fun onColorsChanged(session: TerminalSession) {
        attachedView?.onScreenUpdated()
    }

    override fun onTerminalCursorStateChange(state: Boolean) {}

    override fun getTerminalCursorStyle(): Int = TerminalEmulator.TERMINAL_CURSOR_STYLE_BLOCK

    // ---------------- TerminalViewClient ----------------

    override fun onScale(scale: Float): Float = onFontSizeScale(scale)

    override fun onSingleTapUp(e: MotionEvent?) {
        val view = attachedView ?: return
        view.requestFocus()
        val imm = appContext.getSystemService(InputMethodManager::class.java)
        imm?.showSoftInput(view, 0)
    }

    override fun shouldBackButtonBeMappedToEscape(): Boolean = false

    override fun shouldEnforceCharBasedInput(): Boolean = true

    override fun shouldUseCtrlSpaceWorkaround(): Boolean = true

    override fun isTerminalViewSelected(): Boolean = true

    override fun copyModeChanged(copyMode: Boolean) {}

    override fun onKeyDown(keyCode: Int, e: KeyEvent?, session: TerminalSession?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_ENTER && session != null && !session.isRunning) {
            onSessionFinishedCallback(session)
            return true
        }
        return false
    }

    override fun onKeyUp(keyCode: Int, e: KeyEvent?): Boolean = false

    override fun onLongPress(event: MotionEvent?): Boolean = false

    override fun readControlKey(): Boolean = extraKeys.consumeCtrlModifier()

    override fun readAltKey(): Boolean = extraKeys.consumeAltModifier()

    override fun readShiftKey(): Boolean = extraKeys.consumeShiftModifier()

    override fun readFnKey(): Boolean = false

    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession?): Boolean = false

    override fun onEmulatorSet() {
        attachedView?.setTerminalCursorBlinkerState(true, true)
    }

    // ---------------- Logging compartido ----------------

    override fun logError(tag: String?, message: String?) {
        AppLogger.error(tag ?: "TermuxPTY", message ?: "")
    }

    override fun logWarn(tag: String?, message: String?) {
        AppLogger.warn(tag ?: "TermuxPTY", message ?: "")
    }

    override fun logInfo(tag: String?, message: String?) {
        AppLogger.info(tag ?: "TermuxPTY", message ?: "")
    }

    override fun logDebug(tag: String?, message: String?) {}

    override fun logVerbose(tag: String?, message: String?) {}

    override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {
        AppLogger.error(tag ?: "TermuxPTY", message ?: "Error en PTY", e)
    }

    override fun logStackTrace(tag: String?, e: Exception?) {
        AppLogger.error(tag ?: "TermuxPTY", "Excepción en PTY", e)
    }
}
