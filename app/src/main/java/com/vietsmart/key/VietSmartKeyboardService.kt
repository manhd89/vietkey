package com.vietsmart.key

import android.inputmethodservice.InputMethodService
import android.os.Build
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection

class VietSmartKeyboardService : InputMethodService(), KeyboardView.KeyListener {

    private lateinit var keyboardView: KeyboardView
    private val engine = UniKeyEngine(InputMethod.TELEX)
    private var isVietEnabled = true

    // Số ký tự đã committed mà engine đang theo dõi (để REPLACE hoạt động đúng)
    private var committedCount = 0

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
        return keyboardView
    }

    override fun onComputeInsets(outInsets: Insets) {
        super.onComputeInsets(outInsets)
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        resetState()
        currentInputConnection?.finishComposingText()
    }

    override fun onFinishInput() {
        super.onFinishInput()
        resetState()
    }

    private fun resetState() {
        engine.reset()
        committedCount = 0
    }

    override fun onKey(key: String) {
        val ic: InputConnection = currentInputConnection ?: return

        when (key) {

            // ── Backspace ─────────────────────────────────────────────────
            "⌫" -> {
                if (committedCount > 0) {
                    // Nhờ engine xử lý backspace trong từ hiện tại
                    val (backs, output) = engine.processBackspace()
                    if (backs > 0 || output.isNotEmpty()) {
                        ic.deleteSurroundingText(backs + 1, 0)
                        if (output.isNotEmpty()) {
                            ic.commitText(output, 1)
                            committedCount = output.length
                        } else {
                            committedCount = maxOf(0, committedCount - 1)
                        }
                    } else {
                        // Engine đã hết từ
                        ic.deleteSurroundingText(1, 0)
                        committedCount = 0
                    }
                } else {
                    ic.deleteSurroundingText(1, 0)
                }
            }

            // ── Word-break ────────────────────────────────────────────────
            " ", "\n",
            ".", ",", "!", "?", ":", ";",
            "(", ")", "\"", "'", "-", "/" -> {
                resetState()
                ic.commitText(key, 1)
            }

            // ── Regular keys ──────────────────────────────────────────────
            else -> {
                if (key.length == 1) {
                    val ch = key[0]

                    if (!isVietEnabled || !isVietProcessable(ch)) {
                        resetState()
                        ic.commitText(key, 1)
                        return
                    }

                    when (engine.processKey(ch)) {
                        ProcessResult.COMMIT_CHAR -> {
                            // backs == 0: commit output bình thường
                            val out = engine.lastOutput
                            if (out.isNotEmpty()) {
                                ic.commitText(out, 1)
                                committedCount += out.length
                            } else {
                                // Engine không có output (đang buffer nội bộ?)
                                // commit raw char để không mất phím
                                ic.commitText(key, 1)
                                committedCount++
                            }
                        }
                        ProcessResult.REPLACE -> {
                            // backs > 0: engine sửa từ
                            // Xoá backs ký tự đã commit, commit output mới
                            val backs = engine.backspaceCount
                            val out   = engine.lastOutput
                            ic.deleteSurroundingText(backs, 0)
                            committedCount = maxOf(0, committedCount - backs)
                            if (out.isNotEmpty()) {
                                ic.commitText(out, 1)
                                committedCount += out.length
                            }
                        }
                        ProcessResult.PASSTHROUGH -> {
                            // Engine không xử lý: reset rồi commit raw
                            resetState()
                            ic.commitText(key, 1)
                        }
                    }
                } else {
                    resetState()
                    ic.commitText(key, 1)
                }
            }
        }
    }

    override fun onVietToggle(enabled: Boolean) {
        isVietEnabled = enabled
        resetState()
    }

    override fun onInputMethodChange(method: InputMethod) {
        engine.inputMethod = method
        resetState()
    }

    private fun isVietProcessable(ch: Char): Boolean {
        val k = ch.lowercaseChar()
        if (k in 'a'..'z') return true
        return when (engine.inputMethod) {
            InputMethod.VNI          -> k in '0'..'9'
            InputMethod.VIQR         -> k in "'`?~.^(+"
            else                     -> false
        }
    }
}
