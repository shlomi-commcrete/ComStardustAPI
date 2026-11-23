/* pign_jni.c - JNI bridge between Kotlin (com.commcrete.pign.PigN) and PigN's C API. */
#include <jni.h>
#include <stdint.h>
#include <stdlib.h>

#include "pign.h"

static void set_status(JNIEnv *env, jintArray status, jint rc, jint method)
{
    jint v[2] = { rc, method };
    (*env)->SetIntArrayRegion(env, status, 0, 2, v);
}

static uint8_t *copy_in(JNIEnv *env, jbyteArray arr, size_t *len)
{
    jsize n = (*env)->GetArrayLength(env, arr);
    uint8_t *buf = (uint8_t *)malloc(n > 0 ? (size_t)n : 1);
    if (!buf) return NULL;
    if (n > 0) (*env)->GetByteArrayRegion(env, arr, 0, n, (jbyte *)buf);
    *len = (size_t)n;
    return buf;
}

static jbyteArray copy_out(JNIEnv *env, const uint8_t *data, size_t len)
{
    jbyteArray arr = (*env)->NewByteArray(env, (jsize)len);
    if (arr && len > 0) (*env)->SetByteArrayRegion(env, arr, 0, (jsize)len, (const jbyte *)data);
    return arr;
}

JNIEXPORT jbyteArray JNICALL
Java_com_commcrete_pign_PigN_nativeCompress(JNIEnv *env, jclass cls, jbyteArray data, jstring file_ext,
                                            jint level, jint force_method, jintArray status)
{
    (void)cls;
    if (force_method > 255) {
        set_status(env, status, PIGN_ERR_ARG, 0);
        return NULL;
    }

    size_t in_len = 0;
    uint8_t *in = copy_in(env, data, &in_len);
    if (!in) {
        set_status(env, status, PIGN_ERR_NOMEM, 0);
        return NULL;
    }

    const char *ext = NULL;
    if (file_ext) {
        ext = (*env)->GetStringUTFChars(env, file_ext, NULL);
        if (!ext) {
            free(in);
            return NULL;
        }
    }

    pign_options opt = {0};
    opt.level = level;
    if (force_method >= 0) {
        opt.force_method = 1;
        opt.method = (uint8_t)force_method;
    }

    uint8_t *out = NULL;
    size_t out_len = 0;
    uint8_t method = 0;
    int rc = pign_compress(in, in_len, ext, &opt, &out, &out_len, &method);

    if (ext) (*env)->ReleaseStringUTFChars(env, file_ext, ext);
    free(in);

    set_status(env, status, rc, method);
    jbyteArray result = NULL;
    if (rc == PIGN_OK) result = copy_out(env, out, out_len);
    pign_free(out);
    return result;
}

JNIEXPORT jbyteArray JNICALL
Java_com_commcrete_pign_PigN_nativeDecompress(JNIEnv *env, jclass cls, jbyteArray data, jint method,
                                              jintArray status)
{
    (void)cls;
    if (method < 0 || method > 255) {
        set_status(env, status, PIGN_ERR_METHOD, method);
        return NULL;
    }

    size_t in_len = 0;
    uint8_t *in = copy_in(env, data, &in_len);
    if (!in) {
        set_status(env, status, PIGN_ERR_NOMEM, method);
        return NULL;
    }

    uint8_t *out = NULL;
    size_t out_len = 0;
    int rc = pign_decompress(in, in_len, (uint8_t)method, &out, &out_len);
    free(in);

    if (rc == PIGN_OK && out_len > (size_t)INT32_MAX) rc = PIGN_ERR_LIMIT;

    set_status(env, status, rc, method);
    jbyteArray result = NULL;
    if (rc == PIGN_OK) result = copy_out(env, out, out_len);
    pign_free(out);
    return result;
}
