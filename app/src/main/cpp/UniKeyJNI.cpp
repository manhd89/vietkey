/**
 * UniKeyJNI.cpp
 *
 * JNI bridge dựa trên API thực tế của fcitx5-unikey:
 *   - UkEngine    : engine xử lý tiếng Việt (ukengine.h)
 *   - UnikeyOptions : cấu hình kiểu gõ, charset... (unikey.h / inputproc.h)
 *   - UkSharedMem : shared state giữa engine và input processor
 *
 * Không dùng UnikeyInputContext (class đó thuộc fcitx5-android plugin,
 * không có trong fcitx5-unikey gốc).
 *
 * Vòng đời:
 *   nativeCreate()  →  nativeProcessKey() × N  →  nativeDestroy()
 */

#include <jni.h>
#include <android/log.h>
#include <cstring>
#include <string>
#include <mutex>

// fcitx5-unikey headers (thư mục unikey/)
#include "ukengine.h"    // UkEngine, UkSharedMem, UnikeyOptions
#include "inputproc.h"   // UkInputProcessor, UkKeyEvent, UkOutputType
#include "vnlexi.h"      // UkInputMethod enum: UkTelex, UkVni...
#include "charset.h"     // CONV_CHARSET_UNIUTF8

#define LOG_TAG "VietKeyJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// ─── Ánh xạ enum Kotlin → UkInputMethod ────────────────────────────────────
// Thứ tự phải khớp với enum InputMethod trong UniKeyEngine.kt
static const UkInputMethod kMethods[] = {
    UkTelex,        // 0
    UkVni,          // 1
    UkViqr,         // 2
    UkSimpleTelex,  // 3
    UkSimpleTelex2, // 4
};
static const int kMethodCount = (int)(sizeof(kMethods)/sizeof(kMethods[0]));

// ─── Context per-instance ───────────────────────────────────────────────────
// Mỗi UniKeyEngine Kotlin = 1 VkContext native
struct VkContext {
    UkSharedMem mem;
    UkEngine    engine;

    // Output buffer (UTF-8)
    char    outBuf[64];
    int     outLen;
    int     bsCount;        // số ký tự cần xoá (backspace count)
    int     outputType;     // UkOutputType: UkNothing=0, UkBackspace=1, UkCommit=2

    VkContext() : outLen(0), bsCount(0), outputType(0) {
        memset(&mem, 0, sizeof(mem));
        memset(outBuf, 0, sizeof(outBuf));
        mem.initialized = 1;
        mem.vietKey     = 1;
        mem.charsetId   = CONV_CHARSET_UNIUTF8;
        engine.setCtrlInfo(&mem);
        engine.resetBuf();
    }
};

static inline VkContext* toCtx(jlong h) {
    return reinterpret_cast<VkContext*>(static_cast<uintptr_t>(h));
}

static UkInputMethod toMethod(jint m) {
    return (m >= 0 && m < kMethodCount) ? kMethods[m] : UkTelex;
}

// ─── JNI exports ────────────────────────────────────────────────────────────
extern "C" {

JNIEXPORT jlong JNICALL
Java_com_vietsmart_key_UniKeyEngine_nativeCreate(
        JNIEnv*, jobject, jint inputMethod, jint /*charset*/)
{
    VkContext* ctx = new VkContext();
    ctx->mem.options.inputMethod      = toMethod(inputMethod);
    ctx->mem.options.outputCharset    = CONV_CHARSET_UNIUTF8;
    ctx->mem.options.spellCheckEnabled = 1;
    ctx->mem.options.macroEnabled     = 0;
    ctx->mem.options.modernStyle      = 0;
    ctx->mem.options.freeMarking      = 1;
    LOGI("nativeCreate ctx=%p method=%d", ctx, inputMethod);
    return static_cast<jlong>(reinterpret_cast<uintptr_t>(ctx));
}

JNIEXPORT void JNICALL
Java_com_vietsmart_key_UniKeyEngine_nativeDestroy(
        JNIEnv*, jobject, jlong handle)
{
    delete toCtx(handle);
}

JNIEXPORT void JNICALL
Java_com_vietsmart_key_UniKeyEngine_nativeReset(
        JNIEnv*, jobject, jlong handle)
{
    VkContext* ctx = toCtx(handle);
    if (ctx) {
        ctx->engine.resetBuf();
        ctx->outLen    = 0;
        ctx->bsCount   = 0;
        ctx->outputType = 0;
        memset(ctx->outBuf, 0, sizeof(ctx->outBuf));
    }
}

/**
 * Xử lý một phím.
 * @param keyCode  mã ASCII lowercase ('a'=97, '1'=49...)
 * @param caps     true nếu người dùng đang Shift
 * @return outputType: 0=nothing 1=backspace 2=commit
 */
JNIEXPORT jint JNICALL
Java_com_vietsmart_key_UniKeyEngine_nativeProcessKey(
        JNIEnv*, jobject, jlong handle, jint keyCode, jboolean caps)
{
    VkContext* ctx = toCtx(handle);
    if (!ctx) return 0;

    // Reset output buffer
    ctx->outLen    = 0;
    ctx->bsCount   = 0;
    ctx->outputType = 0;
    memset(ctx->outBuf, 0, sizeof(ctx->outBuf));

    // Tạo UkKeyEvent
    UkKeyEvent ev;
    ev.keyCode   = static_cast<unsigned char>(keyCode);
    ev.isCaps    = (caps == JNI_TRUE) ? 1 : 0;
    ev.evType    = UkKeyDown;

    // Gọi engine
    ctx->engine.process(ev);

    // Đọc kết quả
    ctx->outputType = ctx->engine.m_outputType;
    ctx->bsCount    = ctx->engine.m_bsCount;

    // Copy output bytes (UTF-8)
    int len = ctx->engine.m_outputCount;
    if (len > 0 && len < (int)sizeof(ctx->outBuf)) {
        memcpy(ctx->outBuf, ctx->engine.m_outputBuf, len);
        ctx->outBuf[len] = '\0';
        ctx->outLen = len;
    }

    return static_cast<jint>(ctx->outputType);
}

JNIEXPORT jstring JNICALL
Java_com_vietsmart_key_UniKeyEngine_nativeGetOutput(
        JNIEnv* env, jobject, jlong handle)
{
    VkContext* ctx = toCtx(handle);
    if (!ctx || ctx->outLen == 0) return env->NewStringUTF("");
    return env->NewStringUTF(ctx->outBuf);
}

JNIEXPORT jint JNICALL
Java_com_vietsmart_key_UniKeyEngine_nativeGetBackspaceCount(
        JNIEnv*, jobject, jlong handle)
{
    VkContext* ctx = toCtx(handle);
    return ctx ? static_cast<jint>(ctx->bsCount) : 0;
}

JNIEXPORT void JNICALL
Java_com_vietsmart_key_UniKeyEngine_nativeSetInputMethod(
        JNIEnv*, jobject, jlong handle, jint method)
{
    VkContext* ctx = toCtx(handle);
    if (ctx) {
        ctx->mem.options.inputMethod = toMethod(method);
        ctx->engine.resetBuf();
    }
}

JNIEXPORT void JNICALL
Java_com_vietsmart_key_UniKeyEngine_nativeSetSpellCheck(
        JNIEnv*, jobject, jlong handle, jboolean enabled)
{
    VkContext* ctx = toCtx(handle);
    if (ctx) ctx->mem.options.spellCheckEnabled = (enabled == JNI_TRUE) ? 1 : 0;
}

JNIEXPORT void JNICALL
Java_com_vietsmart_key_UniKeyEngine_nativeSetMacro(
        JNIEnv*, jobject, jlong handle, jboolean enabled)
{
    VkContext* ctx = toCtx(handle);
    if (ctx) ctx->mem.options.macroEnabled = (enabled == JNI_TRUE) ? 1 : 0;
}

JNIEXPORT void JNICALL
Java_com_vietsmart_key_UniKeyEngine_nativeSetModernStyle(
        JNIEnv*, jobject, jlong handle, jboolean enabled)
{
    VkContext* ctx = toCtx(handle);
    if (ctx) ctx->mem.options.modernStyle = (enabled == JNI_TRUE) ? 1 : 0;
}

JNIEXPORT void JNICALL
Java_com_vietsmart_key_UniKeyEngine_nativeSetFreeMarking(
        JNIEnv*, jobject, jlong handle, jboolean enabled)
{
    VkContext* ctx = toCtx(handle);
    if (ctx) ctx->mem.options.freeMarking = (enabled == JNI_TRUE) ? 1 : 0;
}

} // extern "C"
