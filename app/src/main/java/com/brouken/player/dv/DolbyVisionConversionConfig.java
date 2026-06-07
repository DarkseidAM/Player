package com.brouken.player.dv;

/**
 * Drives the per-stream DV7 → 8.1 conversion decision. Adapted from NuvioMedia/NuvioTV (GPLv3),
 * simplified for Just Player's single Auto/On/Off preference.
 *
 * <p>When converting, we prefer libdovi mode 2 (FEL → 8.1) with per-RPU fallback to mode 1
 * (MEL → 8.1), which maximizes compatibility across real-world profile-7 files.
 */
public final class DolbyVisionConversionConfig {

    public final boolean active;

    public DolbyVisionConversionConfig(boolean active) {
        this.active = active;
    }

    public static DolbyVisionConversionConfig inactive() {
        return new DolbyVisionConversionConfig(false);
    }

    /** Mode 2 first with a fallback to mode 1 when mode 2 fails for a given RPU. */
    public boolean allowMode2Fallback() {
        return true;
    }

    /** True when a track of {@code profile} should be converted. Only profile 7 here. */
    public boolean shouldConvert(Integer profile) {
        return active && profile != null && profile == 7;
    }

    /** libdovi conversion mode to use for {@code profile}. */
    public int conversionMode(Integer profile) {
        return 2;
    }
}
