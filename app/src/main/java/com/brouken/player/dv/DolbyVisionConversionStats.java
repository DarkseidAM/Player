package com.brouken.player.dv;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Per-playback DV7 → 8.1 conversion diagnostics, fed by the extractor pipeline and read by the
 * "stats for nerds" overlay. Reset per playback alongside {@link DoviBridge#resetCounters()}.
 * Adapted from NuvioMedia/NuvioTV (GPLv3).
 */
public final class DolbyVisionConversionStats {

    private static final AtomicLong codecStringRewriteCount = new AtomicLong(0L);
    private static volatile Integer lastSourceProfile;
    private static volatile Integer lastConversionMode;
    private static volatile String lastSourceCodec;

    private DolbyVisionConversionStats() {
    }

    public static void reset() {
        codecStringRewriteCount.set(0L);
        lastSourceProfile = null;
        lastConversionMode = null;
        lastSourceCodec = null;
    }

    /** Records the SOURCE DV profile (pre-conversion), e.g. 7. */
    public static void recordSourceProfile(Integer profile) {
        if (profile != null) {
            lastSourceProfile = profile;
        }
    }

    /** Records the SOURCE codec string (pre-conversion), e.g. "dvhe.07.06". */
    public static void recordSourceCodec(String codecs) {
        if (codecs != null && !codecs.isEmpty()) {
            lastSourceCodec = codecs;
        }
    }

    public static String getLastSourceCodec() {
        return lastSourceCodec;
    }

    public static void recordConversionMode(int mode) {
        lastConversionMode = mode;
    }

    public static void recordCodecStringRewrite() {
        codecStringRewriteCount.incrementAndGet();
    }

    public static long getCodecStringRewriteCount() {
        return codecStringRewriteCount.get();
    }

    public static Integer getLastSourceProfile() {
        return lastSourceProfile;
    }

    public static Integer getLastSelectedConversionMode() {
        return lastConversionMode;
    }
}
