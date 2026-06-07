package com.brouken.player.dv;

import android.media.MediaCodecInfo;
import android.media.MediaCodecList;

/**
 * Device Dolby Vision capability detection, used to decide whether DV profile 7 → 8.1
 * conversion should run.
 *
 * <p>Profile 7 (dual-layer BL+EL+RPU, UHD Blu-ray remuxes) maps to the Android codec profile
 * {@code DolbyVisionProfileDvheDtb}; single-layer profile 8.x maps to {@code DolbyVisionProfileDvheSt}.
 * A device like the Xiaomi Pad 6 advertises profile 8 support but not profile 7 — exactly the
 * case where converting 7 → 8.1 lets it play real Dolby Vision instead of falling back to HDR10.
 */
public final class DolbyVisionUtils {

    private static final String MIME_DOLBY_VISION = "video/dolby-vision";

    private DolbyVisionUtils() {
    }

    /** True if any decoder advertises Dolby Vision profile 7 ({@code DolbyVisionProfileDvheDtb}). */
    public static boolean supportsProfile7() {
        return supportsProfile(MediaCodecInfo.CodecProfileLevel.DolbyVisionProfileDvheDtb);
    }

    /** True if any decoder advertises single-layer Dolby Vision profile 8 ({@code DolbyVisionProfileDvheSt}). */
    public static boolean supportsProfile8() {
        return supportsProfile(MediaCodecInfo.CodecProfileLevel.DolbyVisionProfileDvheSt);
    }

    /**
     * The device this code targets: plays single-layer DV (profile 8) but cannot decode the
     * dual-layer profile 7 stream. When true, converting 7 → 8.1 is worthwhile.
     */
    public static boolean shouldConvertProfile7() {
        return supportsProfile8() && !supportsProfile7();
    }

    private static boolean supportsProfile(int profile) {
        try {
            MediaCodecList codecList = new MediaCodecList(MediaCodecList.ALL_CODECS);
            for (MediaCodecInfo info : codecList.getCodecInfos()) {
                if (info.isEncoder()) {
                    continue;
                }
                if (!supportsType(info, MIME_DOLBY_VISION)) {
                    continue;
                }
                MediaCodecInfo.CodecCapabilities caps = info.getCapabilitiesForType(MIME_DOLBY_VISION);
                if (caps == null || caps.profileLevels == null) {
                    continue;
                }
                for (MediaCodecInfo.CodecProfileLevel profileLevel : caps.profileLevels) {
                    if (profileLevel.profile == profile) {
                        return true;
                    }
                }
            }
        } catch (Exception e) {
            // Some devices throw while enumerating codecs; treat as "not supported".
            e.printStackTrace();
        }
        return false;
    }

    private static boolean supportsType(MediaCodecInfo info, String mimeType) {
        for (String type : info.getSupportedTypes()) {
            if (type.equalsIgnoreCase(mimeType)) {
                return true;
            }
        }
        return false;
    }
}
