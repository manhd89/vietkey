/**
 * UniKeyJNI.cpp
 *
 * JNI bridge: Kotlin UniKeyEngine  ←→  UnikeyInputContext (fcitx5-unikey C++)
 *
 * Mỗi instance UniKeyEngine ở Kotlin giữ một jlong handle trỏ tới
 * UnikeyInputContext* được cấp phát trên heap. Vòng đời:
 *   nativeCreate()  → nativeProcessKey() × N  → nativeDestroy()
 *
 * OutputType (mirror của UkOutputType trong Kotlin):
 *   0 = UkNothing    — ký tự thêm vào preedit, chưa có output
 *   1 = UkCommit     — output sẵn sàng để commit (word-break nội bộ)
 *   2 = UkBackspace  — engine tự sửa: xoá backspaceCount rồi chèn output mới
 *   3 = UkError      — engine không xử lý được
 */

#include <jni.h>
#include <android/log.h>
#include <cstring>
#include <string>

#include "ukengine.h"
#include "keycons.h"

#define LOG_TAG  "VietKeyJNI"
#define LOGI(...)  __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGW(...)  __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__)
#define LOGE(...)  __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// ─── Input method mapping (phải khớp thứ tự enum InputMethod trong Kotlin) ───
// TELEX=0  VNI=1  VIQR=2  SIMPLE_TELEX=3  SIMPLE_TELEX2=4
static const UkInputMethod kMethods[] = {
    UkTelex,
    UkVni,
    UkViqr,
    UkSimpleTelex,
    UkSimpleTelex2,
};
static const int kMethodCount = (int)(sizeof(kMethods) / sizeof(kMethods[0]));

// ─── Helpers ──────────────────────────────────────────────────────────────────
static inline UnikeyInputContext* toCtx(jlong h) {
    return reinterpret_cast<UnikeyInputContext*>(static_cast<uintptr_t>(h));
}

static inline UkInputMethod toMethod(jint m) {
    return (m >= 0 && m < kMethodCount) ? kMethods[m] : UkTelex;
}

// ─── JNI implementations ─────────────────────────────────────────────────────
extern "C" {

/**
 * Tạo UnikeyInputContext mới và trả về handle (jlong).
 * @param inputMethod  0–4 (xem bảng trên)
 * @param outputCharset  không dùng trên Android — luôn UTF-8
 */
JNIEXPORT jlong JNICALL
Java_com_vietsmart_key_UniKeyEngine_nativeCreate(
        JNIEnv*, jobject, jint inputMethod, jint /*outputCharset*/)
{
    // UnikeySetup() khởi tạo bảng tra cứu tĩnh — gọi 1 lần là đủ
    UnikeySetup();

    UnikeyInputContext* ctx = new UnikeyInputContext();
    ctx->ukOptions.inputMethod      = toMethod(inputMethod);
    ctx->ukOptions.outputCharset    = CONV_CHARSET_UNIUTF8;
    ctx->ukOptions.spellCheckEnabled = true;
    ctx->ukOptions.macroEnabled     = false;
    ctx->ukOptions.modernStyle      = false;
    ctx->ukOptions.freeMarking      = true;
    ctx->Reset();

    LOGI("nativeCreate ctx=%p method=%d", ctx, inputMethod);
    return static_cast<jlong>(reinterpret_cast<uintptr_t>(ctx));
}

JNIEXPORT void JNICALL
Java_com_vietsmart_key_UniKeyEngine_nativeDestroy(
        JNIEnv*, jobject, jlong handle)
{
    UnikeyInputContext* ctx = toCtx(handle);
    LOGI("nativeDestroy ctx=%p", ctx);
    delete ctx;
}

JNIEXPORT void JNICALL
Java_com_vietsmart_key_UniKeyEngine_nativeReset(
        JNIEnv*, jobject, jlong handle)
{
    UnikeyInputContext* ctx = toCtx(handle);
    if (ctx) ctx->Reset();
}

/**
 * Xử lý một phím gõ.
 * @param keyCode  mã ASCII lowercase (vd: 'a'=97)
 * @param caps     true nếu người dùng đang giữ Shift
 * @return UkOutputType (0–3)
 */
JNIEXPORT jint JNICALL
Java_com_vietsmart_key_UniKeyEngine_nativeProcessKey(
        JNIEnv*, jobject, jlong handle, jint keyCode, jboolean caps)
{
    UnikeyInputContext* ctx = toCtx(handle);
    if (!ctx) return 0;

    // Filter() nhận keycode ASCII + bitmask modifier
    ctx->Filter(static_cast<uint32_t>(keyCode),
                (caps == JNI_TRUE) ? UkCaps : 0);

    return static_cast<jint>(ctx->outputType);
}

/**
 * Trả về output string (UTF-8) sau khi processKey.
 * outputBuf là mảng byte UTF-8, outputCount là số byte.
 */
JNIEXPORT jstring JNICALL
Java_com_vietsmart_key_UniKeyEngine_nativeGetOutput(
        JNIEnv* env, jobject, jlong handle)
{
    UnikeyInputContext* ctx = toCtx(handle);
    if (!ctx || ctx->outputCount == 0)
        return env->NewStringUTF("");

    // outputBuf không null-terminated nên tạo string tường minh
    std::string s(reinterpret_cast<const char*>(ctx->outputBuf),
                  ctx->outputCount);
    return env->NewStringUTF(s.c_str());
}

JNIEXPORT jint JNICALL
Java_com_vietsmart_key_UniKeyEngine_nativeGetBackspaceCount(
        JNIEnv*, jobject, jlong handle)
{
    UnikeyInputContext* ctx = toCtx(handle);
    return ctx ? static_cast<jint>(ctx->backspaceCount) : 0;
}

// ── Setters (gọi sau khi thay đổi settings, không cần reset) ─────────────────

JNIEXPORT void JNICALL
Java_com_vietsmart_key_UniKeyEngine_nativeSetInputMethod(
        JNIEnv*, jobject, jlong handle, jint method)
{
    UnikeyInputContext* ctx = toCtx(handle);
    if (ctx) {
        ctx->ukOptions.inputMethod = toMethod(method);
        ctx->Reset();
    }
}

JNIEXPORT void JNICALL
Java_com_vietsmart_key_UniKeyEngine_nativeSetSpellCheck(
        JNIEnv*, jobject, jlong handle, jboolean enabled)
{
    UnikeyInputContext* ctx = toCtx(handle);
    if (ctx) ctx->ukOptions.spellCheckEnabled = (enabled == JNI_TRUE);
}

JNIEXPORT void JNICALL
Java_com_vietsmart_key_UniKeyEngine_nativeSetMacro(
        JNIEnv*, jobject, jlong handle, jboolean enabled)
{
    UnikeyInputContext* ctx = toCtx(handle);
    if (ctx) ctx->ukOptions.macroEnabled = (enabled == JNI_TRUE);
}

JNIEXPORT void JNICALL
Java_com_vietsmart_key_UniKeyEngine_nativeSetModernStyle(
        JNIEnv*, jobject, jlong handle, jboolean enabled)
{
    UnikeyInputContext* ctx = toCtx(handle);
    if (ctx) ctx->ukOptions.modernStyle = (enabled == JNI_TRUE);
}

JNIEXPORT void JNICALL
Java_com_vietsmart_key_UniKeyEngine_nativeSetFreeMarking(
        JNIEnv*, jobject, jlong handle, jboolean enabled)
{
    UnikeyInputContext* ctx = toCtx(handle);
    if (ctx) ctx->ukOptions.freeMarking = (enabled == JNI_TRUE);
}

} // extern "C"
