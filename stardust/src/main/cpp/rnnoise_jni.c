/*
 * JNI shim for xiph/rnnoise — exposes a minimal API to the Kotlin
 * `com.commcrete.rnnoise.RnNoise` class.
 *
 * RNNoise's native API:
 *   DenoiseState *rnnoise_create(RNNModel *model);   // model = NULL -> default
 *   void          rnnoise_destroy(DenoiseState *st);
 *   float         rnnoise_process_frame(DenoiseState *st, float *out, const float *in);
 *   int           rnnoise_get_frame_size(void);  // 480
 *
 * RNNoise expects float samples in the int16 range (i.e. ~[-32768, 32767]),
 * NOT normalised [-1, 1]. So conversion is just a cast each direction.
 */

#include <jni.h>
#include <stdlib.h>
#include <string.h>
#include <android/log.h>
#include "rnnoise.h"

#define LOG_TAG "rnnoise_jni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

#define FRAME_SIZE 480

JNIEXPORT jlong JNICALL
Java_com_commcrete_rnnoise_RnNoise_nativeCreate(JNIEnv *env, jobject thiz) {
    (void)env; (void)thiz;
    DenoiseState *st = rnnoise_create(NULL);
    if (!st) {
        LOGE("rnnoise_create failed");
        return 0;
    }
    int fs = rnnoise_get_frame_size();
    if (fs != FRAME_SIZE) {
        LOGW("rnnoise_get_frame_size()=%d, expected %d — adapter assumes %d",
             fs, FRAME_SIZE, FRAME_SIZE);
    }
    return (jlong)(intptr_t)st;
}

JNIEXPORT void JNICALL
Java_com_commcrete_rnnoise_RnNoise_nativeDestroy(JNIEnv *env, jobject thiz, jlong handle) {
    (void)env; (void)thiz;
    DenoiseState *st = (DenoiseState *)(intptr_t)handle;
    if (st) rnnoise_destroy(st);
}

JNIEXPORT jshortArray JNICALL
Java_com_commcrete_rnnoise_RnNoise_nativeProcessFrame(JNIEnv *env, jobject thiz,
                                                     jlong handle, jshortArray frame) {
    (void)thiz;
    DenoiseState *st = (DenoiseState *)(intptr_t)handle;
    if (!st || !frame) return NULL;

    jsize len = (*env)->GetArrayLength(env, frame);
    if (len < FRAME_SIZE) return NULL;

    /* Pull input samples (int16) into a stack buffer of floats. */
    jshort in_s16[FRAME_SIZE];
    (*env)->GetShortArrayRegion(env, frame, 0, FRAME_SIZE, in_s16);
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
        return NULL;
    }

    float in_f32[FRAME_SIZE];
    float out_f32[FRAME_SIZE];
    for (int i = 0; i < FRAME_SIZE; i++) in_f32[i] = (float)in_s16[i];

    /* Run RNNoise. Return value is voice-activity probability — ignored here. */
    rnnoise_process_frame(st, out_f32, in_f32);

    /* Convert back to int16 with saturation. */
    jshort out_s16[FRAME_SIZE];
    for (int i = 0; i < FRAME_SIZE; i++) {
        float v = out_f32[i];
        if (v >  32767.0f) v =  32767.0f;
        if (v < -32768.0f) v = -32768.0f;
        out_s16[i] = (jshort)v;
    }

    jshortArray result = (*env)->NewShortArray(env, FRAME_SIZE);
    if (!result) return NULL;
    (*env)->SetShortArrayRegion(env, result, 0, FRAME_SIZE, out_s16);
    return result;
}

