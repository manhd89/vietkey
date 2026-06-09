package com.vietsmart.key

import android.inputmethodservice.InputMethodService
import android.os.Build
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection

class VietSmartKeyboardService : InputMethodService(), KeyboardView.KeyListener {

    private lateinit var keyboardView: KeyboardView

    // UniKeyEngine thay thế TelexEngine
    private val engine = UniKeyEngine(InputMethod.TELEX)
    private var isVietEnabled = true

    // ── Lifecycle ─────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        window?.window?.clearFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
    }

    override fun onDestroy() {
        super.onDestroy()
        engine.destroy()
    }

    override fun onCreateInputView(): View {
        keyboardView = KeyboardView(this, this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT_WATCH) {
            keyboardView.fitsSystemWindows = true
        }
        return keyboardView
    }

    override fun onComputeInsets(outInsets: Insets) {
        super.onComputeInsets(outInsets)
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        engine.reset()
        currentInputConnection?.finishComposingText()
    }

    override fun onFinishInput() {
        super.onFinishInput()
        engine.reset()
    }

    // ── KeyListener ───────────────────────────────────────────────────────

    override fun onKey(key: String) {
        val ic: InputConnection = currentInputConnection ?: return

        when (key) {

            // ── Backspace ─────────────────────────────────────────────────
            "⌫" -> {
                if (!engine.isEmpty) {
                    engine.backspace()
                    val newComposed = engine.composed
                    if (newComposed.isEmpty()) {
                        ic.setComposingText("", 0)
                        ic.finishComposingText()
                    } else {
                        ic.setComposingText(newComposed, 1)
                    }
                } else {
                    ic.deleteSurroundingText(1, 0)
                }
            }

            // ── Word-break: commit preedit trước, rồi gửi ký tự ─────────
            " ", "\n",
            ".", ",", "!", "?", ":", ";",
            "(", ")", "\"", "'", "-", "/" -> {
                if (!engine.isEmpty) ic.commitText(engine.commit(), 1)
                ic.commitText(key, 1)
            }

            // ── Regular keys ──────────────────────────────────────────────
            else -> {
                if (key.length == 1) {
                    val ch = key[0]

                    if (!isVietEnabled || !isVietProcessable(ch)) {
                        if (!engine.isEmpty) ic.commitText(engine.commit(), 1)
                        ic.commitText(key, 1)
                        return
                    }

                    when (engine.processKey(ch)) {
                        UkOutputType.NOTHING -> {
                            // Ký tự thêm vào preedit bình thường
                            ic.setComposingText(engine.composed, 1)
                        }
                        UkOutputType.COMMIT -> {
                            // Engine hoàn thành từ nội bộ
                            ic.commitText(engine.commit(), 1)
                        }
                        UkOutputType.BACKSPACE -> {
                            // Engine đã tự sửa preedit (undo/redo dấu).
                            // setComposingText() thay thế toàn bộ composing
                            // region hiện tại → không cần xoá thủ công.
                            ic.setComposingText(engine.composed, 1)
                        }
                        UkOutputType.ERROR -> {
                            if (!engine.isEmpty) ic.commitText(engine.commit(), 1)
                            ic.commitText(key, 1)
                        }
                    }
                } else {
                    // Multi-char (emoji, v.v.)
                    if (!engine.isEmpty) ic.commitText(engine.commit(), 1)
                    ic.commitText(key, 1)
                }
            }
        }
    }

    override fun onVietToggle(enabled: Boolean) {
        isVietEnabled = enabled
        if (!engine.isEmpty) currentInputConnection?.commitText(engine.commit(), 1)
        engine.reset()
    }

    override fun onInputMethodChange(method: InputMethod) {
        if (!engine.isEmpty) currentInputConnection?.commitText(engine.commit(), 1)
        engine.inputMethod = method
        engine.reset()
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private fun isVietProcessable(ch: Char): Boolean {
        val k = ch.lowercaseChar()
        if (k in 'a'..'z') return true
        return when (engine.inputMethod) {
            InputMethod.VNI                                         -> k in '0'..'9'
            InputMethod.VIQR                                        -> k in "'`?~.^(+"
            InputMethod.TELEX,
            InputMethod.SIMPLE_TELEX,
            InputMethod.SIMPLE_TELEX2                               -> false
        }
    }
}
