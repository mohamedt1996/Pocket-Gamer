// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright 2026 Pocket Arcade contributors
//
// JNI entry for org.pocketarcade.madeira.steam.Zstd: decodes one zstd frame
// with the reference educational decoder (zstd_edu.c, BSD), through Madeira's
// error-safe wrapper, into a buffer of the caller's capacity.

#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include "zstd_edu.h"

JNIEXPORT jbyteArray JNICALL
Java_org_pocketarcade_madeira_steam_Zstd_nativeDecompress(JNIEnv *env, jclass cls, jbyteArray src,
                                                         jint offset, jint length, jint capacity)
{
    (void)cls;
    if (!src || offset < 0 || length <= 0 || capacity <= 0) return NULL;
    jsize total = (*env)->GetArrayLength(env, src);
    if ((jlong)offset + length > total) return NULL;
    uint8_t *in = malloc((size_t)length);
    uint8_t *out = malloc((size_t)capacity);
    if (!in || !out) { free(in); free(out); return NULL; }
    (*env)->GetByteArrayRegion(env, src, offset, length, (jbyte *)in);
    size_t produced = zstd_safe_decompress(out, (size_t)capacity, in, (size_t)length);
    free(in);
    jbyteArray result = NULL;
    if (produced != (size_t)-1 && produced <= (size_t)capacity) {
        result = (*env)->NewByteArray(env, (jsize)produced);
        if (result) (*env)->SetByteArrayRegion(env, result, 0, (jsize)produced, (const jbyte *)out);
    }
    free(out);
    return result;
}
