package com.vietsmart.key

enum class InputMethod(val code: Int) {
    TELEX(0), VNI(1), VIQR(2), SIMPLE_TELEX(3), SIMPLE_TELEX2(4);
}

/**
 * Model hoạt động của UkEngine (từ ukengine.h):
 *
 *   process(keyCode, backs, outBuf, outSize, outType)
 *
 *   UkOutputType:
 *     UkCharOutput(0) — engine xuất ký tự vào outBuf
 *       backs == 0 → ký tự mới, chèn outBuf vào composing
 *       backs >  0 → engine sửa từ: xoá backs ký tự đã committed, commit outBuf
 *     UkKeyOutput(1)  — phím pass-through, engine không xử lý
 *
 * Lưu ý quan trọng: UkEngine KHÔNG dùng preedit/composing model.
 * Engine commit từng ký tự ngay lập tức (backs=0) hoặc replace
 * N ký tự đã commit trước đó (backs>0). Service phải dùng
 * commitText thay vì setComposingText.
 */
enum class ProcessResult {
    /** backs==0: commit outBuf bình thường */
    COMMIT_CHAR,
    /** backs>0: xoá backs ký tự đã commit, rồi commit outBuf mới */
    REPLACE,
    /** Engine không xử lý phím này */
    PASSTHROUGH
}

class UniKeyEngine(inputMethod: InputMethod = InputMethod.TELEX) {

    private var handle: Long = 0L

    var inputMethod: InputMethod = inputMethod
        set(value) {
            field = value
            if (handle != 0L) nativeSetInputMethod(handle, value.code)
        }

    /** Output string sau processKey() — toàn bộ chuỗi engine trả về */
    var lastOutput: String = ""
        private set

    /** Số ký tự đã commit cần xoá trước khi commit lastOutput */
    var backspaceCount: Int = 0
        private set

    init {
        ensureLibLoaded()
        handle = nativeCreate(inputMethod.code, 0)
        check(handle != 0L) { "UniKeyEngine: không tạo được native context" }
    }

    fun processKey(ch: Char): ProcessResult {
        if (handle == 0L) return ProcessResult.PASSTHROUGH
        val caps    = ch.isUpperCase()
        val keyCode = ch.lowercaseChar().code
        // nativeProcessKey trả về: 0=KT_NOTHING(backs=0), 2=KT_BACKSPACE(backs>0), 3=KT_ERROR
        val raw = nativeProcessKey(handle, keyCode, caps)
        lastOutput     = nativeGetOutput(handle)
        backspaceCount = nativeGetBackspaceCount(handle)
        return when (raw) {
            3    -> ProcessResult.PASSTHROUGH          // KT_ERROR = UkKeyOutput
            2    -> ProcessResult.REPLACE              // KT_BACKSPACE = backs > 0
            else -> ProcessResult.COMMIT_CHAR          // KT_NOTHING = backs == 0
        }
    }

    /** Gửi Backspace vào engine, trả về (backs, newOutput) */
    fun processBackspace(): Pair<Int, String> {
        if (handle == 0L) return 0 to ""
        nativeProcessKey(handle, ASCII_BACKSPACE, false)
        return nativeGetBackspaceCount(handle) to nativeGetOutput(handle)
    }

    fun reset() {
        if (handle != 0L) nativeReset(handle)
        lastOutput     = ""
        backspaceCount = 0
    }

    fun destroy() {
        if (handle != 0L) { nativeDestroy(handle); handle = 0L }
    }

    fun setSpellCheck(enabled: Boolean) { if (handle != 0L) nativeSetSpellCheck(handle, enabled) }
    fun setMacro(enabled: Boolean)       { if (handle != 0L) nativeSetMacro(handle, enabled) }
    fun setModernStyle(enabled: Boolean) { if (handle != 0L) nativeSetModernStyle(handle, enabled) }
    fun setFreeMarking(enabled: Boolean) { if (handle != 0L) nativeSetFreeMarking(handle, enabled) }

    @Suppress("ProtectedInFinal")
    protected fun finalize() {
        if (handle != 0L) { nativeDestroy(handle); handle = 0L }
    }

    private external fun nativeCreate(inputMethod: Int, outputCharset: Int): Long
    private external fun nativeDestroy(handle: Long)
    private external fun nativeReset(handle: Long)
    private external fun nativeProcessKey(handle: Long, keyCode: Int, caps: Boolean): Int
    private external fun nativeGetOutput(handle: Long): String
    private external fun nativeGetBackspaceCount(handle: Long): Int
    private external fun nativeSetInputMethod(handle: Long, method: Int)
    private external fun nativeSetSpellCheck(handle: Long, enabled: Boolean)
    private external fun nativeSetMacro(handle: Long, enabled: Boolean)
    private external fun nativeSetModernStyle(handle: Long, enabled: Boolean)
    private external fun nativeSetFreeMarking(handle: Long, enabled: Boolean)

    companion object {
        private const val ASCII_BACKSPACE = 8
        @Volatile private var libLoaded = false
        private fun ensureLibLoaded() {
            if (!libLoaded) synchronized(this) {
                if (!libLoaded) { System.loadLibrary("vietkey-jni"); libLoaded = true }
            }
        }
    }
}
