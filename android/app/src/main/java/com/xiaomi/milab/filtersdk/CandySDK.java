package com.xiaomi.milab.filtersdk;

import android.graphics.Bitmap;

/** Narrow JNI binding for the Xiaomi filter library supplied with MiMediaEditor.
 * The class and native method names are part of the library's exported ABI. */
public final class CandySDK implements AutoCloseable {
    static { System.loadLibrary("MiFilterSDK"); }

    private static native long initJni(int mode);
    private static native boolean configPipeline(long engine, String pipeline);
    private static native boolean updatePipeline(long engine, String parameters);
    private static native void processBitmap(long engine, Bitmap bitmap, float[] transform);
    private static native boolean release(long engine);

    private long engine;

    public CandySDK() {
        engine = initJni(5);
        if (engine == 0) throw new IllegalStateException("Xiaomi filter engine could not initialize");
    }

    public void configure(String pipeline, String parameters) {
        if (engine == 0 || !configPipeline(engine, pipeline) || !updatePipeline(engine, parameters)) {
            throw new IllegalStateException("Xiaomi filter pipeline unavailable");
        }
    }

    public void process(Bitmap bitmap) {
        if (engine == 0 || bitmap == null || !bitmap.isMutable()) {
            throw new IllegalArgumentException("A mutable bitmap and active filter are required");
        }
        processBitmap(engine, bitmap, null);
    }

    @Override public void close() {
        if (engine != 0) {
            long previous = engine;
            engine = 0;
            release(previous);
        }
    }
}
