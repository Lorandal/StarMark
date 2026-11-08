#include <jni.h>
#include <android/bitmap.h>
#include <stdio.h>
#include <stdlib.h>
#include <stdint.h>
#include <setjmp.h>
#include <string.h>
#include <errno.h>
#include "jpeglib.h"

typedef struct {
    struct jpeg_error_mgr base;
    jmp_buf jump;
    char message[JMSG_LENGTH_MAX];
} EncoderError;

typedef struct {
    struct jpeg_compress_struct jpeg;
    EncoderError error;
    FILE *file;
    int failed;
} Encoder;

static void throw_java(JNIEnv *env, const char *type, const char *message) {
    if ((*env)->ExceptionCheck(env)) return;
    jclass cls = (*env)->FindClass(env, type);
    if (cls != NULL) (*env)->ThrowNew(env, cls, message);
}

static void jpeg_error(j_common_ptr jpeg) {
    EncoderError *error = (EncoderError *)jpeg->err;
    (*jpeg->err->format_message)(jpeg, error->message);
    longjmp(error->jump, 1);
}

static void destroy_encoder(Encoder *encoder) {
    if (encoder == NULL) return;
    if (encoder->jpeg.mem != NULL) jpeg_destroy_compress(&encoder->jpeg);
    if (encoder->file != NULL) fclose(encoder->file);
    free(encoder);
}

JNIEXPORT jlong JNICALL
Java_dev_starmark_starmark_StreamingJpeg_nativeOpen(JNIEnv *env, jobject self,
    jstring path, jint width, jint height, jint quality, jbyteArray icc) {
    (void)self;
    if (path == NULL || width < 1 || width > 32768 || height < 1 || height > 32768 || quality < 0 || quality > 100) {
        throw_java(env, "java/lang/IllegalArgumentException", "Invalid JPEG arguments");
        return 0;
    }
    const jsize icc_size = icc == NULL ? 0 : (*env)->GetArrayLength(env, icc);
    if (icc_size > 65519 * 255) {
        throw_java(env, "java/lang/IllegalArgumentException", "ICC exceeds JPEG segment limit");
        return 0;
    }
    Encoder *encoder = calloc(1, sizeof(*encoder));
    if (encoder == NULL) {
        throw_java(env, "java/lang/OutOfMemoryError", "Cannot allocate JPEG encoder");
        return 0;
    }
    const char *filename = (*env)->GetStringUTFChars(env, path, NULL);
    if (filename == NULL) { destroy_encoder(encoder); return 0; }
    encoder->file = fopen(filename, "wb");
    if (encoder->file == NULL) {
        throw_java(env, "java/io/IOException", strerror(errno));
        (*env)->ReleaseStringUTFChars(env, path, filename);
        destroy_encoder(encoder);
        return 0;
    }
    unsigned char *profile = icc_size == 0 ? NULL : malloc((size_t)icc_size);
    if (icc_size > 0 && profile == NULL) {
        destroy_encoder(encoder);
        remove(filename);
        (*env)->ReleaseStringUTFChars(env, path, filename);
        throw_java(env, "java/lang/OutOfMemoryError", "Cannot allocate ICC buffer");
        return 0;
    }
    if (icc_size > 0) (*env)->GetByteArrayRegion(env, icc, 0, icc_size, (jbyte *)profile);
    if ((*env)->ExceptionCheck(env)) {
        free(profile); destroy_encoder(encoder); remove(filename);
        (*env)->ReleaseStringUTFChars(env, path, filename);
        return 0;
    }
    encoder->jpeg.err = jpeg_std_error(&encoder->error.base);
    encoder->error.base.error_exit = jpeg_error;
    if (setjmp(encoder->error.jump)) {
        throw_java(env, "java/io/IOException", encoder->error.message);
        free(profile); destroy_encoder(encoder); remove(filename);
        (*env)->ReleaseStringUTFChars(env, path, filename);
        return 0;
    }
    jpeg_create_compress(&encoder->jpeg);
    jpeg_stdio_dest(&encoder->jpeg, encoder->file);
    encoder->jpeg.image_width = (JDIMENSION)width;
    encoder->jpeg.image_height = (JDIMENSION)height;
    encoder->jpeg.input_components = 4;
    encoder->jpeg.in_color_space = JCS_EXT_RGBA;
    // Must precede defaults: mozjpeg otherwise selects progressive + multipass by default.
    jpeg_c_set_int_param(&encoder->jpeg, JINT_COMPRESS_PROFILE, JCP_FASTEST);
    jpeg_set_defaults(&encoder->jpeg);
    jpeg_set_quality(&encoder->jpeg, quality, TRUE);
    encoder->jpeg.optimize_coding = FALSE;
    encoder->jpeg.scan_info = NULL;
    encoder->jpeg.num_scans = 0;
    jpeg_c_set_bool_param(&encoder->jpeg, JBOOLEAN_OPTIMIZE_SCANS, FALSE);
    jpeg_c_set_bool_param(&encoder->jpeg, JBOOLEAN_TRELLIS_QUANT, FALSE);
    jpeg_c_set_bool_param(&encoder->jpeg, JBOOLEAN_TRELLIS_QUANT_DC, FALSE);
    jpeg_c_set_bool_param(&encoder->jpeg, JBOOLEAN_TRELLIS_EOB_OPT, FALSE);
    jpeg_c_set_bool_param(&encoder->jpeg, JBOOLEAN_TRELLIS_Q_OPT, FALSE);
    jpeg_c_set_bool_param(&encoder->jpeg, JBOOLEAN_OVERSHOOT_DERINGING, FALSE);
    // Preserve high-quality coloured edges and text. Memory is still only a few MCU rows.
    for (int i = 0; i < encoder->jpeg.num_components; ++i) {
        encoder->jpeg.comp_info[i].h_samp_factor = 1;
        encoder->jpeg.comp_info[i].v_samp_factor = 1;
    }
    encoder->jpeg.dct_method = JDCT_ISLOW;
    jpeg_start_compress(&encoder->jpeg, TRUE);
    if (icc_size > 0) jpeg_write_icc_profile(&encoder->jpeg, profile, (unsigned int)icc_size);
    free(profile);
    (*env)->ReleaseStringUTFChars(env, path, filename);
    return (jlong)(intptr_t)encoder;
}

JNIEXPORT void JNICALL
Java_dev_starmark_starmark_StreamingJpeg_nativeWrite(JNIEnv *env, jobject self, jlong handle, jobject bitmap) {
    (void)self;
    Encoder *encoder = (Encoder *)(intptr_t)handle;
    if (encoder == NULL || encoder->failed) {
        throw_java(env, "java/lang/IllegalStateException", "JPEG encoder is closed or failed");
        return;
    }
    AndroidBitmapInfo info;
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS ||
        info.format != ANDROID_BITMAP_FORMAT_RGBA_8888 ||
        (info.flags & ANDROID_BITMAP_FLAGS_IS_HARDWARE) != 0 ||
        info.width != encoder->jpeg.image_width || info.height == 0 ||
        info.height > encoder->jpeg.image_height - encoder->jpeg.next_scanline ||
        info.stride < info.width * 4) {
        throw_java(env, "java/lang/IllegalArgumentException", "Invalid JPEG bitmap strip");
        return;
    }
    void *pixels = NULL;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS) {
        throw_java(env, "java/io/IOException", "Cannot lock JPEG bitmap strip");
        return;
    }
    if (setjmp(encoder->error.jump)) {
        encoder->failed = 1;
        AndroidBitmap_unlockPixels(env, bitmap);
        throw_java(env, "java/io/IOException", encoder->error.message);
        return;
    }
    // Check only alpha-capable inputs. The common compositor path declares its strip opaque.
    if ((info.flags & ANDROID_BITMAP_FLAGS_ALPHA_MASK) != ANDROID_BITMAP_FLAGS_ALPHA_OPAQUE) {
        for (uint32_t y = 0; y < info.height; ++y) {
            const unsigned char *row = (const unsigned char *)pixels + (size_t)y * info.stride;
            for (uint32_t x = 0; x < info.width; ++x) {
                if (row[x * 4 + 3] != 255) {
                    AndroidBitmap_unlockPixels(env, bitmap);
                    throw_java(env, "java/lang/IllegalArgumentException", "JPEG strip must be opaque");
                    return;
                }
            }
        }
    }
    for (uint32_t y = 0; y < info.height; ++y) {
        JSAMPROW row = (JSAMPROW)((unsigned char *)pixels + (size_t)y * info.stride);
        if (jpeg_write_scanlines(&encoder->jpeg, &row, 1) != 1) {
            encoder->failed = 1;
            AndroidBitmap_unlockPixels(env, bitmap);
            throw_java(env, "java/io/IOException", "JPEG destination suspended");
            return;
        }
    }
    AndroidBitmap_unlockPixels(env, bitmap);
}

JNIEXPORT void JNICALL
Java_dev_starmark_starmark_StreamingJpeg_nativeFinish(JNIEnv *env, jobject self, jlong handle) {
    (void)self;
    Encoder *encoder = (Encoder *)(intptr_t)handle;
    if (encoder == NULL) {
        throw_java(env, "java/lang/IllegalStateException", "JPEG encoder is closed");
        return;
    }
    if (encoder->failed || encoder->jpeg.next_scanline != encoder->jpeg.image_height) {
        destroy_encoder(encoder);
        throw_java(env, "java/lang/IllegalStateException", "JPEG has missing rows or failed");
        return;
    }
    if (setjmp(encoder->error.jump)) {
        throw_java(env, "java/io/IOException", encoder->error.message);
        destroy_encoder(encoder);
        return;
    }
    jpeg_finish_compress(&encoder->jpeg);
    int result = fclose(encoder->file);
    encoder->file = NULL;
    if (result != 0) throw_java(env, "java/io/IOException", strerror(errno));
    destroy_encoder(encoder);
}

JNIEXPORT void JNICALL
Java_dev_starmark_starmark_StreamingJpeg_nativeAbort(JNIEnv *env, jobject self, jlong handle) {
    (void)env; (void)self;
    destroy_encoder((Encoder *)(intptr_t)handle);
}
