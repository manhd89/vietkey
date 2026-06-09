package com.vietsmart.key

/**
 * UniKeyEngine
 *
 * Kotlin wrapper cho native UnikeyInputContext (fcitx5-unikey qua JNI).
 * Thay thế hoàn toàn TelexEngine — cùng interface nhưng dùng engine
 * C++ battle-tested của UniKey thay vì tự implement.
 *
 * Vòng đời:
 *   val engine = UniKeyEngine(InputMethod.TELEX)
 *   engine.processKey('a')      → UkOutputType
 *   engine.composed             → preedit string hiện tại
 *   engine.backspaceCount       → số char cần xoá khi outputType == BACKSPACE
 *   engine.commit()             → lấy text + reset
 *   engine.backspace()          → xoá 1 ký tự trong preedit
 *   engine.reset()              → xoá toàn bộ state
 *   engine.destroy()            → giải phóng native memory (gọi ở onDestroy)
 */

// ─── Enums ────────────────────────────────────────────────────────────────────

/**
 * Kiểu gõ. Thứ tự phải khớp với kMethods[] trong UniKeyJNI.cpp.
 * KeyboardView dùng enum này để hiển thị nhãn nút chuyển bộ gõ.
 */
enum class InputMethod(val code: Int) {
    TELEX(0),
    VNI(1),
    VIQR(2),
    SIMPLE_TELEX(3),
    SIMPLE_TELEX2(4);
}

/**
 * Kết quả trả về từ processKey().
 * Mirror của UkOutputType trong ukengine.h.
 */
enum class UkOutputType(val code: Int) {
    /** Engine thêm ký tự vào preedit nội bộ, composed đã được cập nhật */
    NOTHING(0),
    /** Engine commit từ hoàn chỉnh (thường do word-break nội bộ) */
    COMMIT(1),
    /**
     * Engine tự sửa buffer (undo/redo dấu, chuyển â→a...):
     * Service phải xoá [backspaceCount] ký tự trong composing region
     * rồi chèn [composed] mới vào.
     * Thực tế với Android setComposingText() một lần là đủ vì IMF
     * tự replace toàn bộ composing region.
     */
    BACKSPACE(2),
    /** Engine không nhận phím này — service commit preedit cũ + gửi raw */
    ERROR(3);

    companion object {
        fun from(code: Int) = entries.firstOrNull { it.code == code } ?: NOTHING
    }
}

// ─── Engine ───────────────────────────────────────────────────────────────────

class UniKeyEngine(inputMethod: InputMethod = InputMethod.TELEX) {

    private var handle: Long = 0L

    // Preedit được maintain ở Kotlin để tránh gọi JNI mỗi frame UI
    private val preedit = StringBuilder()

    var inputMethod: InputMethod = inputMethod
        set(value) {
            field = value
            if (handle != 0L) nativeSetInputMethod(handle, value.code)
        }

    val isEmpty: Boolean  get() = preedit.isEmpty()
    val composed: String  get() = preedit.toString()

    /**
     * Số ký tự (Unicode code points) cần xoá ở composing region
     * khi outputType == BACKSPACE. Giá trị này là số byte UTF-8 từ engine
     * — Service nên dùng [composed] trực tiếp qua setComposingText().
     */
    var backspaceCount: Int = 0
        private set

    init {
        ensureLibLoaded()
        handle = nativeCreate(inputMethod.code, 0)
        check(handle != 0L) { "UniKeyEngine: không tạo được native context" }
    }

    // ── Core API ──────────────────────────────────────────────────────────

    /**
     * Xử lý một phím gõ. Cập nhật [composed] và [backspaceCount].
     * @param ch ký tự gốc người dùng gõ (có thể uppercase)
     * @return UkOutputType để Service biết cách update composing text
     */
    fun processKey(ch: Char): UkOutputType {
        if (handle == 0L) return UkOutputType.ERROR

        val caps    = ch.isUpperCase()
        val keyCode = ch.lowercaseChar().code
        val result  = UkOutputType.from(nativeProcessKey(handle, keyCode, caps))
        val output  = nativeGetOutput(handle)

        when (result) {
            UkOutputType.COMMIT -> {
                // Word hoàn chỉnh — output là toàn bộ từ đã commit
                preedit.clear()
                preedit.append(output)
                backspaceCount = 0
            }
            UkOutputType.BACKSPACE -> {
                // Engine tự sửa: xoá N byte UTF-8 từ cuối rồi thêm output mới.
                // Vì preedit lưu dạng String (không byte), ta tính theo char.
                backspaceCount = nativeGetBackspaceCount(handle)
                val removeChars = utf8BytesToCharCount(preedit, backspaceCount)
                val newLen = (preedit.length - removeChars).coerceAtLeast(0)
                preedit.setLength(newLen)
                preedit.append(output)
            }
            UkOutputType.NOTHING -> {
                // Ký tự thêm vào preedit bình thường
                if (output.isNotEmpty()) preedit.append(output)
                backspaceCount = 0
            }
            UkOutputType.ERROR -> {
                backspaceCount = 0
            }
        }
        return result
    }

    /**
     * Người dùng nhấn Backspace — xoá ký tự cuối trong preedit.
     * @return true nếu preedit còn ký tự để xoá
     */
    fun backspace(): Boolean {
        if (handle == 0L || preedit.isEmpty()) return false
        nativeProcessKey(handle, ASCII_BACKSPACE, false)
        // Xoá 1 Unicode code point từ cuối
        val len = preedit.length
        val removeSurrogate = len >= 2
                && Character.isLowSurrogate(preedit[len - 1])
                && Character.isHighSurrogate(preedit[len - 2])
        preedit.delete(if (removeSurrogate) len - 2 else len - 1, len)
        backspaceCount = 0
        return true
    }

    /**
     * Commit toàn bộ preedit và reset state.
     * @return string đã commit
     */
    fun commit(): String {
        val out = preedit.toString()
        reset()
        return out
    }

    /** Reset state — gọi khi focus thay đổi hoặc word-break */
    fun reset() {
        if (handle != 0L) nativeReset(handle)
        preedit.clear()
        backspaceCount = 0
    }

    /** Giải phóng native memory. Phải gọi trong Service.onDestroy(). */
    fun destroy() {
        if (handle != 0L) {
            nativeDestroy(handle)
            handle = 0L
        }
        preedit.clear()
    }

    // ── Options ───────────────────────────────────────────────────────────

    fun setSpellCheck(enabled: Boolean)  { if (handle != 0L) nativeSetSpellCheck(handle, enabled) }
    fun setMacro(enabled: Boolean)        { if (handle != 0L) nativeSetMacro(handle, enabled) }
    fun setModernStyle(enabled: Boolean)  { if (handle != 0L) nativeSetModernStyle(handle, enabled) }
    fun setFreeMarking(enabled: Boolean)  { if (handle != 0L) nativeSetFreeMarking(handle, enabled) }

    // ── Finalize (safety net — không thay thế destroy()) ─────────────────
    @Suppress("ProtectedInFinal")
    protected fun finalize() {
        if (handle != 0L) {
            android.util.Log.w(TAG, "destroy() chưa được gọi trước GC!")
            nativeDestroy(handle)
            handle = 0L
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────

    /**
     * Chuyển số byte UTF-8 → số Char trong Kotlin String.
     * Cần thiết vì backspaceCount của engine tính theo byte UTF-8
     * nhưng StringBuilder tính theo Char (UTF-16 code unit).
     */
    private fun utf8BytesToCharCount(sb: StringBuilder, byteCount: Int): Int {
        if (byteCount <= 0) return 0
        var bytes = 0
        var chars = 0
        var i = sb.length - 1
        while (i >= 0 && bytes < byteCount) {
            val c = sb[i]
            bytes += when {
                c.code < 0x80    -> 1
                c.code < 0x800   -> 2
                Character.isLowSurrogate(c) -> { i--; chars++; 4 } // surrogate pair = 4 bytes
                else             -> 3
            }
            chars++
            i--
        }
        return chars
    }

    // ── Native declarations ───────────────────────────────────────────────
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
        private const val TAG = "UniKeyEngine"
        private const val ASCII_BACKSPACE = 8

        @Volatile private var libLoaded = false

        private fun ensureLibLoaded() {
            if (!libLoaded) synchronized(this) {
                if (!libLoaded) {
                    System.loadLibrary("vietkey-jni")
                    libLoaded = true
                }
            }
        }
    }
}
