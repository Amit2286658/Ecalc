/*
 * math_engine_jni.c — Ready-to-Use Android NDK JNI Bridge Implementation
 *
 * Location: Core/jni/math_engine_jni.c
 */

#include "math_engine_jni.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#if !(defined(__has_include) && __has_include(<jni.h>))
static char s_mock_str_ring[8][65536];
static int  s_mock_str_idx = 0;

static const char *mock_get_str(JniMockEnv **env, jstring s, jboolean *isCopy) {
    (void)env;
    if (isCopy) *isCopy = JNI_FALSE;
    return s;
}
static void mock_rel_str(JniMockEnv **env, jstring s, const char *utf) {
    (void)env; (void)s; (void)utf;
}
static jstring mock_new_str(JniMockEnv **env, const char *bytes) {
    (void)env;
    if (!bytes) return NULL;
    char *dst = s_mock_str_ring[s_mock_str_idx];
    s_mock_str_idx = (s_mock_str_idx + 1) & 7;
    snprintf(dst, sizeof(s_mock_str_ring[0]), "%s", bytes);
    return dst;
}
static jsize mock_arr_len(JniMockEnv **env, jbyteArray arr) {
    (void)env;
    return arr ? arr->len : 0;
}
static jbyte *mock_get_bytes(JniMockEnv **env, jbyteArray arr, jboolean *isCopy) {
    (void)env;
    if (isCopy) *isCopy = JNI_FALSE;
    return arr ? arr->data : NULL;
}
static void mock_rel_bytes(JniMockEnv **env, jbyteArray arr, jbyte *elems, jint mode) {
    (void)env; (void)arr; (void)elems; (void)mode;
}
static jbyteArray mock_new_byte_arr(JniMockEnv **env, jsize len) {
    (void)env;
    if (len <= 0) return NULL;
    JniMockByteArray *a = (JniMockByteArray *)malloc(sizeof(JniMockByteArray));
    if (!a) return NULL;
    a->data = (uint8_t *)malloc((size_t)len);
    if (!a->data) { free(a); return NULL; }
    a->len = len;
    return a;
}
static void mock_set_byte_region(JniMockEnv **env, jbyteArray arr, jsize start, jsize len, const jbyte *buf) {
    (void)env;
    if (arr && arr->data && buf && start >= 0 && start + len <= arr->len) {
        memcpy(arr->data + start, buf, (size_t)len);
    }
}

static JniMockEnv s_mock_vtbl = {
    mock_get_str,
    mock_rel_str,
    mock_new_str,
    mock_arr_len,
    mock_get_bytes,
    mock_rel_bytes,
    mock_new_byte_arr,
    mock_set_byte_region
};
static JniMockEnv *s_mock_env_ptr = &s_mock_vtbl;

JNIEnv *mep_jni_mock_env(void) {
    return &s_mock_env_ptr;
}

void mep_jni_mock_free_byte_array(jbyteArray arr) {
    if (arr) {
        free(arr->data);
        free(arr);
    }
}
#endif

/* Trim any incomplete trailing multi-byte UTF-8 sequence and replace bare NUL/0x80+
 * continuation errors so `NewStringUTF` never aborts under Android CheckJNI. */
static void jni_sanitize_utf8_inplace(char *s) {
    if (!s) return;
    size_t len = strlen(s);
    size_t i = 0;
    while (i < len) {
        unsigned char c = (unsigned char)s[i];
        if (c < 0x80u) {
            i++;
            continue;
        }
        size_t seq_len = 0;
        if ((c & 0xE0u) == 0xC0u) seq_len = 2;
        else if ((c & 0xF0u) == 0xE0u) seq_len = 3;
        else if ((c & 0xF8u) == 0xF0u) seq_len = 4;
        else {
            s[i++] = '?';
            continue;
        }
        if (i + seq_len > len) {
            /* Truncated multi-byte UTF-8 sequence at end of buffer: trim cleanly */
            s[i] = '\0';
            break;
        }
        bool valid_cont = true;
        for (size_t k = 1; k < seq_len; ++k) {
            if (((unsigned char)s[i + k] & 0xC0u) != 0x80u) {
                valid_cont = false;
                break;
            }
        }
        if (!valid_cont) {
            s[i++] = '?';
            continue;
        }
        i += seq_len;
    }
}

static jstring jni_new_safe_utf(JNIEnv *env, char *buf) {
    if (!buf) return NULL;
    jni_sanitize_utf8_inplace(buf);
    return (*env)->NewStringUTF(env, buf);
}

static jstring jni_format_eval_reply(JNIEnv *env, Engine *eng, bool ok, Value out) {
    char reply[4096];
    if (!ok) {
        snprintf(reply, sizeof(reply), "ERR|%s", eng_last_error(eng));
        return jni_new_safe_utf(env, reply);
    }
    char val_buf[3900], type_buf[32], unit_buf[32];
    eng_format_value(eng, out, val_buf, sizeof(val_buf));
    jni_sanitize_utf8_inplace(val_buf);
    eng_type_name(eng, eng_val_type(eng, out), type_buf, sizeof(type_buf));
    eng_unit_name(eng, eng_val_unit(eng, out), unit_buf, sizeof(unit_buf));
    snprintf(reply, sizeof(reply), "OK|%s|%s|%s", type_buf, unit_buf, val_buf);
    return jni_new_safe_utf(env, reply);
}

JNIEXPORT jlong JNICALL
Java_com_mathengine_MathEngineBridge_nativeCreate(JNIEnv *env, jclass clazz) {
    (void)env; (void)clazz;
    Engine *eng = eng_create();
    return (jlong)(intptr_t)eng;
}

JNIEXPORT jlong JNICALL
Java_com_mathengine_MathEngineBridge_nativeCreateBlank(JNIEnv *env, jclass clazz) {
    (void)env; (void)clazz;
    Engine *eng = eng_create_blank();
    return (jlong)(intptr_t)eng;
}

JNIEXPORT void JNICALL
Java_com_mathengine_MathEngineBridge_nativeDestroy(JNIEnv *env, jclass clazz, jlong handle) {
    (void)env; (void)clazz;
    Engine *eng = (Engine *)(intptr_t)handle;
    if (eng) eng_destroy(eng);
}

JNIEXPORT jstring JNICALL
Java_com_mathengine_MathEngineBridge_nativeEval(JNIEnv *env, jclass clazz,
                                                jlong handle, jstring expr) {
    (void)clazz;
    Engine *eng = (Engine *)(intptr_t)handle;
    if (!eng || !expr) return (*env)->NewStringUTF(env, "ERR|Invalid engine handle or null expression");
    const char *c_expr = (*env)->GetStringUTFChars(env, expr, NULL);
    if (!c_expr) return (*env)->NewStringUTF(env, "ERR|Failed to read UTF-8 expression");
    Value out = val_void();
    bool ok = eng_eval(eng, c_expr, &out);
    (*env)->ReleaseStringUTFChars(env, expr, c_expr);
    return jni_format_eval_reply(env, eng, ok, out);
}

JNIEXPORT jstring JNICALL
Java_com_mathengine_MathEngineBridge_nativeLastError(JNIEnv *env, jclass clazz, jlong handle) {
    (void)clazz;
    Engine *eng = (Engine *)(intptr_t)handle;
    if (!eng) return (*env)->NewStringUTF(env, "Invalid engine handle");
    return (*env)->NewStringUTF(env, eng_last_error(eng));
}

JNIEXPORT jint JNICALL
Java_com_mathengine_MathEngineBridge_nativeSessionOpen(JNIEnv *env, jclass clazz, jlong handle) {
    (void)env; (void)clazz;
    Engine *eng = (Engine *)(intptr_t)handle;
    if (!eng) return -1;
    return (jint)eng_session_open(eng);
}

JNIEXPORT jboolean JNICALL
Java_com_mathengine_MathEngineBridge_nativeSessionClose(JNIEnv *env, jclass clazz,
                                                        jlong handle, jint sessionId) {
    (void)env; (void)clazz;
    Engine *eng = (Engine *)(intptr_t)handle;
    if (!eng) return JNI_FALSE;
    return eng_session_close(eng, (int)sessionId) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_mathengine_MathEngineBridge_nativeSessionReset(JNIEnv *env, jclass clazz,
                                                        jlong handle, jint sessionId) {
    (void)env; (void)clazz;
    Engine *eng = (Engine *)(intptr_t)handle;
    if (!eng) return JNI_FALSE;
    return eng_session_reset(eng, (int)sessionId) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL
Java_com_mathengine_MathEngineBridge_nativeSessionEval(JNIEnv *env, jclass clazz,
                                                       jlong handle, jint sessionId,
                                                       jstring expr) {
    (void)clazz;
    Engine *eng = (Engine *)(intptr_t)handle;
    if (!eng || !expr) return (*env)->NewStringUTF(env, "ERR|Invalid engine handle or null expression");
    const char *c_expr = (*env)->GetStringUTFChars(env, expr, NULL);
    if (!c_expr) return (*env)->NewStringUTF(env, "ERR|Failed to read UTF-8 expression");
    Value out = val_void();
    bool ok = eng_session_eval(eng, (int)sessionId, c_expr, &out);
    (*env)->ReleaseStringUTFChars(env, expr, c_expr);
    return jni_format_eval_reply(env, eng, ok, out);
}

JNIEXPORT jint JNICALL
Java_com_mathengine_MathEngineBridge_nativeSessionSnapshotCount(JNIEnv *env, jclass clazz,
                                                                jlong handle, jint sessionId) {
    (void)env; (void)clazz;
    Engine *eng = (Engine *)(intptr_t)handle;
    if (!eng) return 0;
    return (jint)eng_session_snapshot_count(eng, (int)sessionId);
}

JNIEXPORT jstring JNICALL
Java_com_mathengine_MathEngineBridge_nativeSessionSnapshotAt(JNIEnv *env, jclass clazz,
                                                             jlong handle, jint sessionId,
                                                             jint index) {
    (void)clazz;
    Engine *eng = (Engine *)(intptr_t)handle;
    if (!eng || index < 0) return NULL;
    const char *snap = eng_session_snapshot_at(eng, (int)sessionId, (uint16_t)index);
    return snap ? (*env)->NewStringUTF(env, snap) : NULL;
}

JNIEXPORT jstring JNICALL
Java_com_mathengine_MathEngineBridge_nativeSessionExportSnapshots(JNIEnv *env, jclass clazz,
                                                                  jlong handle, jint sessionId) {
    (void)clazz;
    Engine *eng = (Engine *)(intptr_t)handle;
    if (!eng) return NULL;
    if (!eng_session_snapshot_rewind(eng, (int)sessionId)) return NULL;

    size_t total_len = 0;
    const char *raw = NULL;
    while ((raw = eng_session_snapshot_next(eng, (int)sessionId)) != NULL) {
        total_len += strlen(raw) + 1u;
    }
    char *joined = (char *)malloc(total_len + 1u);
    if (!joined) return NULL;
    joined[0] = '\0';

    size_t used = 0;
    (void)eng_session_snapshot_rewind(eng, (int)sessionId);
    while ((raw = eng_session_snapshot_next(eng, (int)sessionId)) != NULL) {
        size_t rlen = strlen(raw);
        if (used > 0) joined[used++] = '\n';
        memcpy(joined + used, raw, rlen);
        used += rlen;
        joined[used] = '\0';
    }
    jstring jres = jni_new_safe_utf(env, joined);
    free(joined);
    return jres;
}

JNIEXPORT jint JNICALL
Java_com_mathengine_MathEngineBridge_nativeSessionRestore(JNIEnv *env, jclass clazz,
                                                          jlong handle, jstring newlineJoinedSnapshots) {
    (void)clazz;
    Engine *eng = (Engine *)(intptr_t)handle;
    if (!eng || !newlineJoinedSnapshots) return -1;
    const char *raw = (*env)->GetStringUTFChars(env, newlineJoinedSnapshots, NULL);
    if (!raw) return -1;
    size_t raw_len = strlen(raw);
    char *buf = (char *)malloc(raw_len + 1u);
    if (!buf) {
        (*env)->ReleaseStringUTFChars(env, newlineJoinedSnapshots, raw);
        return -1;
    }
    memcpy(buf, raw, raw_len + 1u);
    (*env)->ReleaseStringUTFChars(env, newlineJoinedSnapshots, raw);

    const char *snaps[ENG_SESSION_MAX_EVALS];
    size_t count = 0;
    char *cur = buf;
    while (*cur != '\0') {
        while (*cur == '\n' || *cur == '\r') cur++;
        if (*cur == '\0') break;
        if (count >= ENG_SESSION_MAX_EVALS) {
            free(buf);
            return -1;
        }
        snaps[count++] = cur;
        while (*cur != '\0' && *cur != '\n' && *cur != '\r') cur++;
        if (*cur != '\0') {
            *cur = '\0';
            cur++;
        }
    }
    if (count == 0) {
        free(buf);
        return -1;
    }
    int sid = eng_session_restore(eng, snaps, count);
    free(buf);
    return (jint)sid;
}

JNIEXPORT jbyteArray JNICALL
Java_com_mathengine_MathEngineBridge_nativeCompileExt(JNIEnv *env, jclass clazz,
                                                      jlong handle, jstring mathSource) {
    (void)clazz;
    Engine *eng = (Engine *)(intptr_t)handle;
    if (!eng || !mathSource) return NULL;
    const char *src = (*env)->GetStringUTFChars(env, mathSource, NULL);
    if (!src) return NULL;
    const uint8_t *mathc_data = NULL;
    size_t mathc_len = 0;
    bool ok = user_compile_ext(eng, src, &mathc_data, &mathc_len);
    (*env)->ReleaseStringUTFChars(env, mathSource, src);
    if (!ok || !mathc_data || mathc_len == 0) return NULL;

    jbyteArray arr = (*env)->NewByteArray(env, (jsize)mathc_len);
    if (!arr) return NULL;
    (*env)->SetByteArrayRegion(env, arr, 0, (jsize)mathc_len, (const jbyte *)mathc_data);
    return arr;
}

JNIEXPORT jboolean JNICALL
Java_com_mathengine_MathEngineBridge_nativeLoadMathc(JNIEnv *env, jclass clazz,
                                                     jlong handle, jbyteArray mathcBytes) {
    (void)clazz;
    Engine *eng = (Engine *)(intptr_t)handle;
    if (!eng || !mathcBytes) return JNI_FALSE;
    jsize len = (*env)->GetArrayLength(env, mathcBytes);
    if (len <= 0) return JNI_FALSE;
    jbyte *bytes = (*env)->GetByteArrayElements(env, mathcBytes, NULL);
    if (!bytes) return JNI_FALSE;
    bool ok = user_load_mathc(eng, (const uint8_t *)bytes, (size_t)len);
    (*env)->ReleaseByteArrayElements(env, mathcBytes, bytes, JNI_ABORT);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_mathengine_MathEngineBridge_nativeSetModule(JNIEnv *env, jclass clazz,
                                                     jlong handle, jstring modName,
                                                     jboolean enabled) {
    (void)clazz;
    Engine *eng = (Engine *)(intptr_t)handle;
    if (!eng || !modName) return JNI_FALSE;
    const char *name = (*env)->GetStringUTFChars(env, modName, NULL);
    if (!name) return JNI_FALSE;
    bool ok = eng_set_module(eng, name, enabled != JNI_FALSE);
    (*env)->ReleaseStringUTFChars(env, modName, name);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_mathengine_MathEngineBridge_nativeIsModuleActive(JNIEnv *env, jclass clazz,
                                                          jlong handle, jstring modName) {
    (void)clazz;
    Engine *eng = (Engine *)(intptr_t)handle;
    if (!eng || !modName) return JNI_FALSE;
    const char *name = (*env)->GetStringUTFChars(env, modName, NULL);
    if (!name) return JNI_FALSE;
    bool active = eng_is_module_active(eng, name);
    (*env)->ReleaseStringUTFChars(env, modName, name);
    return active ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL
Java_com_mathengine_MathEngineBridge_nativeSymSimplify(JNIEnv *env, jclass clazz,
                                                       jlong handle, jstring expr) {
    (void)clazz;
    Engine *eng = (Engine *)(intptr_t)handle;
    if (!eng || !expr) return NULL;
    const char *c_expr = (*env)->GetStringUTFChars(env, expr, NULL);
    if (!c_expr) return NULL;
    char out[4096];
    bool ok = eng_sym_simplify(eng, c_expr, out, sizeof(out));
    (*env)->ReleaseStringUTFChars(env, expr, c_expr);
    return ok ? jni_new_safe_utf(env, out) : NULL;
}

JNIEXPORT jstring JNICALL
Java_com_mathengine_MathEngineBridge_nativeSymDiff(JNIEnv *env, jclass clazz,
                                                   jlong handle, jstring varName,
                                                   jstring expr) {
    (void)clazz;
    Engine *eng = (Engine *)(intptr_t)handle;
    if (!eng || !varName || !expr) return NULL;
    const char *c_var  = (*env)->GetStringUTFChars(env, varName, NULL);
    const char *c_expr = (*env)->GetStringUTFChars(env, expr, NULL);
    if (!c_var || !c_expr) {
        if (c_var)  (*env)->ReleaseStringUTFChars(env, varName, c_var);
        if (c_expr) (*env)->ReleaseStringUTFChars(env, expr, c_expr);
        return NULL;
    }
    char out[4096];
    bool ok = eng_sym_diff(eng, c_var, c_expr, out, sizeof(out));
    (*env)->ReleaseStringUTFChars(env, varName, c_var);
    (*env)->ReleaseStringUTFChars(env, expr, c_expr);
    return ok ? jni_new_safe_utf(env, out) : NULL;
}

JNIEXPORT jstring JNICALL
Java_com_mathengine_MathEngineBridge_nativeSymInteg(JNIEnv *env, jclass clazz,
                                                    jlong handle, jstring varName,
                                                    jstring expr) {
    (void)clazz;
    Engine *eng = (Engine *)(intptr_t)handle;
    if (!eng || !varName || !expr) return NULL;
    const char *c_var  = (*env)->GetStringUTFChars(env, varName, NULL);
    const char *c_expr = (*env)->GetStringUTFChars(env, expr, NULL);
    if (!c_var || !c_expr) {
        if (c_var)  (*env)->ReleaseStringUTFChars(env, varName, c_var);
        if (c_expr) (*env)->ReleaseStringUTFChars(env, expr, c_expr);
        return NULL;
    }
    char out[4096];
    bool ok = eng_sym_integ(eng, c_var, c_expr, out, sizeof(out));
    (*env)->ReleaseStringUTFChars(env, varName, c_var);
    (*env)->ReleaseStringUTFChars(env, expr, c_expr);
    return ok ? jni_new_safe_utf(env, out) : NULL;
}

JNIEXPORT jstring JNICALL
Java_com_mathengine_MathEngineBridge_nativeSymSolve(JNIEnv *env, jclass clazz,
                                                    jlong handle, jstring varName,
                                                    jstring eq) {
    (void)clazz;
    Engine *eng = (Engine *)(intptr_t)handle;
    if (!eng || !varName || !eq) return NULL;
    const char *c_var = (*env)->GetStringUTFChars(env, varName, NULL);
    const char *c_eq  = (*env)->GetStringUTFChars(env, eq, NULL);
    if (!c_var || !c_eq) {
        if (c_var) (*env)->ReleaseStringUTFChars(env, varName, c_var);
        if (c_eq)  (*env)->ReleaseStringUTFChars(env, eq, c_eq);
        return NULL;
    }
    char out[4096];
    bool ok = eng_sym_solve(eng, c_var, c_eq, out, sizeof(out));
    (*env)->ReleaseStringUTFChars(env, varName, c_var);
    (*env)->ReleaseStringUTFChars(env, eq, c_eq);
    return ok ? jni_new_safe_utf(env, out) : NULL;
}

JNIEXPORT jint JNICALL
Java_com_mathengine_MathEngineBridge_nativeCompactScratch(JNIEnv *env, jclass clazz, jlong handle) {
    (void)env; (void)clazz;
    Engine *eng = (Engine *)(intptr_t)handle;
    if (!eng) return 0;
    return (jint)eng_compact_scratch(eng);
}

JNIEXPORT jint JNICALL
Java_com_mathengine_MathEngineBridge_nativeScratchPoolUsed(JNIEnv *env, jclass clazz, jlong handle) {
    (void)env; (void)clazz;
    Engine *eng = (Engine *)(intptr_t)handle;
    if (!eng) return 0;
    return (jint)eng_scratch_pool_used(eng);
}
