/*
 * JNI-мост к nox_js: com.nox.offline.js.JsEngine.
 */
#include <jni.h>
#include <stdlib.h>
#include <string.h>

#include "nox_js.h"

static void throw_engine(JNIEnv *env, int status, const char *msg) {
    jclass cls = (*env)->FindClass(env, "com/nox/offline/js/JsEngineException");
    if (!cls) return; /* ClassNotFoundException уже выброшен */
    jmethodID ctor = (*env)->GetMethodID(env, cls, "<init>", "(ILjava/lang/String;)V");
    if (!ctor) return;
    /* NewStringUTF ждёт modified UTF-8: оставляем только печатный ASCII. */
    char safe[512];
    size_t n = 0;
    if (msg) {
        for (; msg[n] && n < sizeof(safe) - 1; n++) {
            unsigned char c = (unsigned char) msg[n];
            safe[n] = (c >= 0x20 && c < 0x7f) ? (char) c : '?';
        }
    }
    safe[n] = '\0';
    jstring jmsg = (*env)->NewStringUTF(env, safe);
    jobject ex = (*env)->NewObject(env, cls, ctor, (jint) status, jmsg);
    if (ex) (*env)->Throw(env, (jthrowable) ex);
}

JNIEXPORT jbyteArray JNICALL
Java_com_nox_offline_js_JsEngine_nativeRun(JNIEnv *env, jclass clazz, jbyteArray code,
                                          jlong memoryLimit, jlong stackLimit,
                                          jlong timeoutMs, jlong outputLimit) {
    (void) clazz;
    jsize len = (*env)->GetArrayLength(env, code);
    jbyte *bytes = (*env)->GetByteArrayElements(env, code, NULL);
    if (!bytes) return NULL;

    NoxJsLimits limits;
    limits.memory_limit = (size_t) memoryLimit;
    limits.stack_limit = (size_t) stackLimit;
    limits.timeout_ms = (long) timeoutMs;
    limits.output_limit = (size_t) outputLimit;

    NoxJsResult result;
    nox_js_run((const char *) bytes, (size_t) len, &limits, &result);
    (*env)->ReleaseByteArrayElements(env, code, bytes, JNI_ABORT);

    jbyteArray out = NULL;
    if (result.status == NOX_JS_OK) {
        out = (*env)->NewByteArray(env, (jsize) result.output_len);
        if (out && result.output_len) {
            (*env)->SetByteArrayRegion(env, out, 0, (jsize) result.output_len, (const jbyte *) result.output);
        }
    } else {
        throw_engine(env, result.status, result.error);
    }
    nox_js_free(&result);
    return out;
}

JNIEXPORT jstring JNICALL
Java_com_nox_offline_js_JsEngine_nativeVersion(JNIEnv *env, jclass clazz) {
    (void) clazz;
    return (*env)->NewStringUTF(env, nox_js_engine_version());
}
