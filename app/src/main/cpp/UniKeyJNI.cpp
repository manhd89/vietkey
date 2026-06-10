/**
 * UniKeyJNI.cpp  —  JNI bridge dùng API thực của fcitx5-unikey ukengine.h
 *
 * API thực (đọc từ compiler error log):
 *   int UkEngine::process(unsigned int keyCode,
 *                         int &backs,
 *                         unsigned char *outBuf,
 *                         int &outSize,
 *                         UkOutputType &outType);
 *   UkOutputType m_outType   (không phải m_outputType)
 *
 * UkOutputType values (từ inputproc.h):
 *   UkNothing  = 0
 *   UkBackspace = 1   (engine muốn xoá + chèn lại)
 *   UkCommit    = 2   (commit từ hiện tại)
 */

#include <jni.h>
#include <android/log.h>
#include <cstring>

#include "ukengine.h"   // UkEngine, UkSharedMem, UnikeyOptions, UkOutputType

#define LOG_TAG "VietKeyJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// ─── InputMethod mapping (phải khớp enum InputMethod trong Kotlin) ───────────
static const UkInputMethod kMethods[] = {
    UkTelex,        // 0
    UkVni,          // 1
    UkViqr,         // 2
    UkSimpleTelex,  // 3
    UkSimpleTelex2, // 4
};
static const int kMethodCount = (int)(sizeof(kMethods) / sizeof(kMethods[0]));

static UkInputMethod toMethod(jint m) {
    return (m >= 0 && m < kMethodCount) ? kMethods[m] : UkTelex;
}

// ─── Per-instance context ────────────────────────────────────────────────────
struct VkContext {
    UkSharedMem  shm;
    UkEngine     engine;

    unsigned char outBuf[64];
    int           outSize;
    int           backs;
    UkOutputType  outType;

    VkContext() : outSize(0), backs(0), outType(UkNothing) {
        memset(&shm, 0, sizeof(shm));
        memset(outBuf, 0, sizeof(outBuf));
        engine.setCtrlInfo(&shm);
    }
};

static inline VkContext* toCtx(jlong h) {
    return reinterpret_cast<VkContext*>(static_cast<uintptr_t>(h));
}

// ─── JNI ────────────────────────────────────────────────────────────────────
extern "C" {

JNIEXPORT jlong JNICALL
Java_com_vietsmart_key_UniKeyEngine_nativeCreate(
        JNIEnv*, jobject, jint inputMethod, jint /*charset*/)
{
    VkContext* ctx = new VkContext();

    // Cấu hình options trong UkSharedMem
    ctx->shm.ukOptions.inputMethod       = toMethod(inputMethod);
    ctx->shm.ukOptions.outputCharset     = CONV_CHARSET_UNIUTF8;
    ctx->shm.ukOptions.spellCheckEnabled = true;
    ctx->shm.ukOptions.macroEnabled      = false;
    ctx->shm.ukOptions.modernStyle       = false;
    ctx->shm.ukOptions.freeMarking       = true;

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
    if (!ctx) return;
    ctx->engine.reset();
    ctx->outSize = 0;
    ctx->backs   = 0;
    ctx->outType = UkNothing;
    memset(ctx->outBuf, 0, sizeof(ctx->outBuf));
}

/**
 * Signature thực của UkEngine::process (từ compiler):
 *   int process(unsigned int keyCode,
 *               int &backs,
 *               unsigned char *outBuf,
 *               int &outSize,
 *               UkOutputType &outType);
 */
JNIEXPORT jint JNICALL
Java_com_vietsmart_key_UniKeyEngine_nativeProcessKey(
        JNIEnv*, jobject, jlong handle, jint keyCode, jboolean caps)
{
    VkContext* ctx = toCtx(handle);
    if (!ctx) return 0;

    ctx->outSize = 0;
    ctx->backs   = 0;
    ctx->outType = UkNothing;
    memset(ctx->outBuf, 0, sizeof(ctx->outBuf));

    // keyCode: uppercase nếu caps==true
    unsigned int key = static_cast<unsigned int>(keyCode);
    if (caps == JNI_TRUE && key >= 'a' && key <= 'z') {
        key = key - 'a' + 'A';
    }

    ctx->engine.process(key, ctx->backs, ctx->outBuf,
                        ctx->outSize, ctx->outType);

    return static_cast<jint>(ctx->outType);
}

JNIEXPORT jstring JNICALL
Java_com_vietsmart_key_UniKeyEngine_nativeGetOutput(
        JNIEnv* env, jobject, jlong handle)
{
    VkContext* ctx = toCtx(handle);
    if (!ctx || ctx->outSize <= 0) return env->NewStringUTF("");
    // outBuf là UTF-8, đảm bảo null-terminate
    ctx->outBuf[ctx->outSize < 63 ? ctx->outSize : 63] = '\0';
    return env->NewStringUTF(reinterpret_cast<const char*>(ctx->outBuf));
}

JNIEXPORT jint JNICALL
Java_com_vietsmart_key_UniKeyEngine_nativeGetBackspaceCount(
        JNIEnv*, jobject, jlong handle)
{
    VkContext* ctx = toCtx(handle);
    return ctx ? static_cast<jint>(ctx->backs) : 0;
}

JNIEXPORT void JNICALL
Java_com_vietsmart_key_UniKeyEngine_nativeSetInputMethod(
        JNIEnv*, jobject, jlong handle, jint method)
{
    VkContext* ctx = toCtx(handle);
    if (!ctx) return;
    ctx->shm.ukOptions.inputMethod = toMethod(method);
    ctx->engine.reset();
}

JNIEXPORT void JNICALL
Java_com_vietsmart_key_UniKeyEngine_nativeSetSpellCheck(
        JNIEnv*, jobject, jlong handle, jboolean enabled)
{
    VkContext* ctx = toCtx(handle);
    if (ctx) ctx->shm.ukOptions.spellCheckEnabled = (enabled == JNI_TRUE);
}

JNIEXPORT void JNICALL
Java_com_vietsmart_key_UniKeyEngine_nativeSetMacro(
        JNIEnv*, jobject, jlong handle, jboolean enabled)
{
    VkContext* ctx = toCtx(handle);
    if (ctx) ctx->shm.ukOptions.macroEnabled = (enabled == JNI_TRUE);
}

JNIEXPORT void JNICALL
Java_com_vietsmart_key_UniKeyEngine_nativeSetModernStyle(
        JNIEnv*, jobject, jlong handle, jboolean enabled)
{
    VkContext* ctx = toCtx(handle);
    if (ctx) ctx->shm.ukOptions.modernStyle = (enabled == JNI_TRUE);
}

JNIEXPORT void JNICALL
Java_com_vietsmart_key_UniKeyEngine_nativeSetFreeMarking(
        JNIEnv*, jobject, jlong handle, jboolean enabled)
{
    VkContext* ctx = toCtx(handle);
    if (ctx) ctx->shm.ukOptions.freeMarking = (enabled == JNI_TRUE);
}

} // extern "C"
