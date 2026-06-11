/**
 * UniKeyJNI.cpp  —  verified hoàn toàn từ headers thực tế
 *
 * ukengine.h:
 *   struct UkSharedMem { bool vietKey; UnikeyOptions options; UkInputProcessor input;
 *                        bool usrKeyMapLoaded; int usrKeyMap[256]; int charsetId; CMacroTable macStore; }
 *   int UkEngine::process(unsigned int keyCode, int &backs, unsigned char *outBuf,
 *                         int &outSize, UkOutputType &outType)
 *   void UkEngine::reset()
 *   void SetupUnikeyEngine()
 *
 * keycons.h:
 *   struct UnikeyOptions { int freeMarking; int modernStyle; int macroEnabled;
 *                          int useUnicodeClipboard; int alwaysMacro; int strictSpellCheck;
 *                          int useIME; int spellCheckEnabled; int autoNonVnRestore; }
 *   enum UkInputMethod { UkTelex, UkVni, UkViqr, UkMsVi, UkUsrIM, UkSimpleTelex, UkSimpleTelex2 }
 *   typedef enum { UkCharOutput, UkKeyOutput } UkOutputType
 *     → UkCharOutput=0 : engine xuất ký tự vào outBuf, backs = số ký tự xoá trước
 *     → UkKeyOutput=1  : không có output, phím pass-through
 *
 * inputproc.h:
 *   int UkInputProcessor::setIM(UkInputMethod im)
 *
 * Mapping UkOutputType → Kotlin UkOutputType:
 *   UkCharOutput(0) + backs==0 → NOTHING(0)   (ký tự mới thêm vào)
 *   UkCharOutput(0) + backs >0 → BACKSPACE(2) (engine tự sửa)
 *   UkKeyOutput(1)             → ERROR(3)     (pass-through, không xử lý)
 */

#include <jni.h>
#include <android/log.h>
#include <cstring>

#include "ukengine.h"   // UkEngine, UkSharedMem
#include "inputproc.h"  // UkInputProcessor, UkInputMethod
#include "keycons.h"    // UnikeyOptions, UkOutputType
#include "charset.h"    // CONV_CHARSET_UNIUTF8

#define LOG_TAG "VietKeyJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)

// ─── InputMethod mapping ─────────────────────────────────────────────────────
// Kotlin enum InputMethod: TELEX=0 VNI=1 VIQR=2 SIMPLE_TELEX=3 SIMPLE_TELEX2=4
static const UkInputMethod kMethods[] = {
    UkTelex,        // 0
    UkVni,          // 1
    UkViqr,         // 2
    UkSimpleTelex,  // 3
    UkSimpleTelex2, // 4
};
static const int kMethodCount = (int)(sizeof(kMethods)/sizeof(kMethods[0]));
static UkInputMethod toMethod(jint m) {
    return (m >= 0 && m < kMethodCount) ? kMethods[m] : UkTelex;
}

// ─── Kotlin UkOutputType codes (phải khớp enum trong UniKeyEngine.kt) ────────
static const int KT_NOTHING   = 0;
static const int KT_COMMIT    = 1;
static const int KT_BACKSPACE = 2;
static const int KT_ERROR     = 3;

// ─── Per-instance context ────────────────────────────────────────────────────
struct VkContext {
    UkSharedMem   shm;
    UkEngine      engine;
    unsigned char outBuf[64];
    int           outSize;
    int           backs;
    int           ktOutputType; // Kotlin-side output type code

    VkContext() : outSize(0), backs(0), ktOutputType(KT_NOTHING) {
        memset(&shm, 0, sizeof(shm));
        memset(outBuf, 0, sizeof(outBuf));
        shm.vietKey   = true;
        shm.charsetId = CONV_CHARSET_UNIUTF8;
        // UnikeyOptions defaults
        shm.options.spellCheckEnabled = 1;
        shm.options.macroEnabled      = 0;
        shm.options.modernStyle       = 0;
        shm.options.freeMarking       = 1;
        shm.options.autoNonVnRestore  = 0;
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
    ctx->shm.input.init();
    ctx->shm.input.setIM(toMethod(inputMethod));
    SetupUnikeyEngine();
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
    ctx->outSize      = 0;
    ctx->backs        = 0;
    ctx->ktOutputType = KT_NOTHING;
    memset(ctx->outBuf, 0, sizeof(ctx->outBuf));
}

/**
 * Process one key.
 *
 * UkOutputType mapping:
 *   UkCharOutput(0) backs==0 → KT_NOTHING   ký tự thêm vào preedit
 *   UkCharOutput(0) backs >0 → KT_BACKSPACE  engine tự sửa (undo dấu)
 *   UkKeyOutput(1)           → KT_ERROR      pass-through
 */
JNIEXPORT jint JNICALL
Java_com_vietsmart_key_UniKeyEngine_nativeProcessKey(
        JNIEnv*, jobject, jlong handle, jint keyCode, jboolean caps)
{
    VkContext* ctx = toCtx(handle);
    if (!ctx) return KT_ERROR;

    ctx->outSize      = 0;
    ctx->backs        = 0;
    ctx->ktOutputType = KT_NOTHING;
    memset(ctx->outBuf, 0, sizeof(ctx->outBuf));

    unsigned int key = static_cast<unsigned int>(keyCode);
    if (caps == JNI_TRUE && key >= 'a' && key <= 'z')
        key = key - 'a' + 'A';

    UkOutputType ukType = UkCharOutput;
    ctx->engine.process(key, ctx->backs, ctx->outBuf,
                        ctx->outSize, ukType);

    if (ukType == UkKeyOutput) {
        ctx->ktOutputType = KT_ERROR;
    } else {
        // UkCharOutput
        ctx->ktOutputType = (ctx->backs > 0) ? KT_BACKSPACE : KT_NOTHING;
    }

    return static_cast<jint>(ctx->ktOutputType);
}

JNIEXPORT jstring JNICALL
Java_com_vietsmart_key_UniKeyEngine_nativeGetOutput(
        JNIEnv* env, jobject, jlong handle)
{
    VkContext* ctx = toCtx(handle);
    if (!ctx || ctx->outSize <= 0) return env->NewStringUTF("");
    int safeLen = ctx->outSize < 63 ? ctx->outSize : 63;
    ctx->outBuf[safeLen] = '\0';
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
    ctx->shm.input.setIM(toMethod(method));
    ctx->engine.reset();
}

JNIEXPORT void JNICALL
Java_com_vietsmart_key_UniKeyEngine_nativeSetSpellCheck(
        JNIEnv*, jobject, jlong handle, jboolean enabled)
{
    VkContext* ctx = toCtx(handle);
    if (ctx) ctx->shm.options.spellCheckEnabled = (enabled == JNI_TRUE) ? 1 : 0;
}

JNIEXPORT void JNICALL
Java_com_vietsmart_key_UniKeyEngine_nativeSetMacro(
        JNIEnv*, jobject, jlong handle, jboolean enabled)
{
    VkContext* ctx = toCtx(handle);
    if (ctx) ctx->shm.options.macroEnabled = (enabled == JNI_TRUE) ? 1 : 0;
}

JNIEXPORT void JNICALL
Java_com_vietsmart_key_UniKeyEngine_nativeSetModernStyle(
        JNIEnv*, jobject, jlong handle, jboolean enabled)
{
    VkContext* ctx = toCtx(handle);
    if (ctx) ctx->shm.options.modernStyle = (enabled == JNI_TRUE) ? 1 : 0;
}

JNIEXPORT void JNICALL
Java_com_vietsmart_key_UniKeyEngine_nativeSetFreeMarking(
        JNIEnv*, jobject, jlong handle, jboolean enabled)
{
    VkContext* ctx = toCtx(handle);
    if (ctx) ctx->shm.options.freeMarking = (enabled == JNI_TRUE) ? 1 : 0;
}

} // extern "C"
