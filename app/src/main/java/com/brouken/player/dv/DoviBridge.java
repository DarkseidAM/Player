package com.brouken.player.dv;

import android.util.Log;

import java.util.concurrent.atomic.AtomicLong;

/**
 * JNI bridge to libdovi for runtime Dolby Vision profile 7 → 8.1 RPU conversion.
 * Adapted from NuvioMedia/NuvioTV (GPLv3).
 *
 * <p>The native library always loads; whether real conversion is available depends on
 * whether it was linked against libdovi ({@link #nativeIsConversionPathReady()}). In stub
 * builds the conversion call returns null and {@link #isAvailable()} is false.
 */
public final class DoviBridge {

    private static final String TAG = "DoviBridge";
    private static final String LIB_NAME = "dovi_bridge";

    private static final boolean NATIVE_LOADED = loadNativeLibrary();
    private static volatile Boolean conversionPathReady;

    private static final AtomicLong conversionCallCount = new AtomicLong(0L);
    private static final AtomicLong conversionSuccessCount = new AtomicLong(0L);

    private DoviBridge() {
    }

    /** True when the native library loaded AND it was linked against libdovi. */
    public static boolean isAvailable() {
        return NATIVE_LOADED && isConversionPathReady();
    }

    public static boolean isConversionPathReady() {
        if (!NATIVE_LOADED) {
            return false;
        }
        Boolean ready = conversionPathReady;
        if (ready == null) {
            synchronized (DoviBridge.class) {
                ready = conversionPathReady;
                if (ready == null) {
                    try {
                        ready = nativeIsConversionPathReady();
                    } catch (Throwable t) {
                        Log.w(TAG, "isConversionPathReady failed: " + t.getMessage());
                        ready = false;
                    }
                    conversionPathReady = ready;
                }
            }
        }
        return ready;
    }

    public static String getBridgeVersionOrNull() {
        if (!NATIVE_LOADED) {
            return null;
        }
        try {
            return nativeGetBridgeVersion();
        } catch (Throwable t) {
            Log.w(TAG, "getBridgeVersion failed: " + t.getMessage());
            return null;
        }
    }

    /**
     * Converts a single Dolby Vision RPU NAL (unspec-62) from profile 7 to 8.1 using libdovi.
     * Returns the rewritten RPU NAL, or null on failure / stub build.
     */
    public static byte[] convertDv7RpuToDv81(byte[] payload, int mode) {
        if (!isAvailable() || payload == null || payload.length == 0) {
            return null;
        }
        conversionCallCount.incrementAndGet();
        byte[] converted;
        try {
            converted = nativeConvertDv7RpuToDv81(payload, mode);
        } catch (Throwable t) {
            Log.w(TAG, "Conversion failed: " + t.getMessage());
            return null;
        }
        if (converted != null && converted.length > 0) {
            conversionSuccessCount.incrementAndGet();
            return converted;
        }
        return null;
    }

    public static void resetCounters() {
        conversionCallCount.set(0L);
        conversionSuccessCount.set(0L);
    }

    public static long getConversionCallCount() {
        return conversionCallCount.get();
    }

    public static long getConversionSuccessCount() {
        return conversionSuccessCount.get();
    }

    private static boolean loadNativeLibrary() {
        try {
            System.loadLibrary(LIB_NAME);
            Log.i(TAG, "Loaded native library: " + LIB_NAME);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "Failed to load native library " + LIB_NAME + ": " + t.getMessage());
            return false;
        }
    }

    private static native String nativeGetBridgeVersion();

    private static native boolean nativeIsConversionPathReady();

    private static native byte[] nativeConvertDv7RpuToDv81(byte[] payload, int mode);
}
