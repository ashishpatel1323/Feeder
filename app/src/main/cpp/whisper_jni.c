// JNI wrapper around whisper.cpp for com.nononsenseapps.feeder.model.transcript.WhisperNative.
// One transcription runs at a time; cancel() stops the running one.
#include <jni.h>
#include <stdatomic.h>
#include <stdbool.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <android/log.h>
#include "whisper.h"

#define TAG "FeederWhisper"
#define JNI_FN(name) Java_com_nononsenseapps_feeder_model_transcript_WhisperNative_##name

static atomic_bool g_cancel = false;

static bool abort_cb(void *user_data) {
    (void) user_data;
    return atomic_load(&g_cancel);
}

JNIEXPORT jlong JNICALL JNI_FN(init)(JNIEnv *env, jobject thiz, jstring model_path) {
    (void) thiz;
    const char *path = (*env)->GetStringUTFChars(env, model_path, NULL);
    struct whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = false;
    struct whisper_context *ctx = whisper_init_from_file_with_params(path, cparams);
    (*env)->ReleaseStringUTFChars(env, model_path, path);
    return (jlong) ctx;
}

JNIEXPORT void JNICALL JNI_FN(free)(JNIEnv *env, jobject thiz, jlong ctx) {
    (void) env;
    (void) thiz;
    if (ctx != 0) whisper_free((struct whisper_context *) ctx);
}

JNIEXPORT void JNICALL JNI_FN(cancel)(JNIEnv *env, jobject thiz) {
    (void) env;
    (void) thiz;
    atomic_store(&g_cancel, true);
}

// Returns UTF-8 bytes, one line per segment: "<start ms>\t<end ms>\t<text>", or null when it
// failed or was cancelled. Bytes, not a jstring: JNI strings are modified UTF-8.
JNIEXPORT jbyteArray JNICALL JNI_FN(transcribe)(
    JNIEnv *env, jobject thiz, jlong ctx_ptr, jfloatArray samples, jint threads, jstring language) {
    (void) thiz;
    struct whisper_context *ctx = (struct whisper_context *) ctx_ptr;
    if (ctx == NULL) return NULL;
    atomic_store(&g_cancel, false);

    const char *lang = (*env)->GetStringUTFChars(env, language, NULL);
    struct whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.print_realtime = false;
    params.print_progress = false;
    params.print_timestamps = false;
    params.print_special = false;
    params.translate = false;
    params.language = lang;
    params.n_threads = threads;
    params.no_context = true;
    params.suppress_blank = true;
    params.abort_callback = abort_cb;
    params.abort_callback_user_data = NULL;

    jsize n = (*env)->GetArrayLength(env, samples);
    jfloat *data = (*env)->GetFloatArrayElements(env, samples, NULL);
    int rc = whisper_full(ctx, params, data, n);
    (*env)->ReleaseFloatArrayElements(env, samples, data, JNI_ABORT);
    (*env)->ReleaseStringUTFChars(env, language, lang);

    if (rc != 0 || atomic_load(&g_cancel)) {
        __android_log_print(ANDROID_LOG_WARN, TAG, "whisper_full stopped: %d", rc);
        return NULL;
    }

    int segments = whisper_full_n_segments(ctx);
    size_t cap = 4096, len = 0;
    char *out = malloc(cap);
    if (out == NULL) return NULL;
    out[0] = '\0';
    for (int i = 0; i < segments; i++) {
        const char *text = whisper_full_get_segment_text(ctx, i);
        // Timestamps are in 10 ms units
        long long t0 = (long long) whisper_full_get_segment_t0(ctx, i) * 10;
        long long t1 = (long long) whisper_full_get_segment_t1(ctx, i) * 10;
        size_t need = strlen(text) + 64;
        if (len + need >= cap) {
            while (len + need >= cap) cap *= 2;
            char *grown = realloc(out, cap);
            if (grown == NULL) {
                free(out);
                return NULL;
            }
            out = grown;
        }
        len += (size_t) snprintf(out + len, cap - len, "%lld\t%lld\t", t0, t1);
        // Tabs and newlines inside the text would break the line format
        for (const char *c = text; *c; c++) out[len++] = (*c == '\t' || *c == '\n' || *c == '\r') ? ' ' : *c;
        out[len++] = '\n';
        out[len] = '\0';
    }
    jbyteArray result = (*env)->NewByteArray(env, (jsize) len);
    if (result != NULL) (*env)->SetByteArrayRegion(env, result, 0, (jsize) len, (const jbyte *) out);
    free(out);
    return result;
}
