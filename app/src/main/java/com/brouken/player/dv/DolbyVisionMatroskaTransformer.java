package com.brouken.player.dv;

import androidx.annotation.Nullable;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.container.DolbyVisionConfig;

import com.brouken.player.dv.dvmkv.MatroskaExtractor;

import java.io.ByteArrayOutputStream;
import java.util.Locale;

/**
 * Implementation of the vendored Matroska extractor's
 * {@link MatroskaExtractor.DolbyVisionSampleTransformer} seam, performing DV7 → 8.1 conversion
 * for MKV via {@link DoviBridge}. Adapted from NuvioMedia/NuvioTV (GPLv3), simplified to the
 * always-convert DV7 path (no HDR10-strip / DV5 variants).
 *
 * <p>The extractor calls:
 * <ul>
 *   <li>{@link #onDolbyVisionBlockAdditionalData} when it reads the DV7 enhancement-layer RPU from
 *       a Matroska BlockAdditional; we convert it to an 8.1 RPU NAL.
 *   <li>{@link #transformHevcSample} just before committing the HEVC sample; we rewrite the
 *       base-layer NALs (dropping EL NALs, converting any in-band RPU) and append the converted
 *       BlockAdditional RPU.
 *   <li>{@link #onDolbyVisionCodecString} when building the output Format; dvhe.07/dvh1.07 becomes
 *       dvhe.08/dvh1.08 to advertise single-layer 8.1.
 * </ul>
 */
@UnstableApi
public final class DolbyVisionMatroskaTransformer implements MatroskaExtractor.DolbyVisionSampleTransformer {

    private static final int NAL_TYPE_UNSPEC62 = 62;

    private final DolbyVisionConversionConfig config;
    private int lastTransformedLength = 0;
    private final ExposedByteArrayOutputStream scratch = new ExposedByteArrayOutputStream(64 * 1024);

    public DolbyVisionMatroskaTransformer(DolbyVisionConversionConfig config) {
        this.config = config;
    }

    private static final class ExposedByteArrayOutputStream extends ByteArrayOutputStream {
        ExposedByteArrayOutputStream(int size) {
            super(size);
        }

        byte[] backingArray() {
            return buf;
        }
    }

    @Override
    public byte[] onDolbyVisionBlockAdditionalData(
            byte[] blockAdditionalData, int blockAddIdType, @Nullable byte[] dolbyVisionConfigBytes) {
        if (blockAdditionalData == null) {
            return null;
        }
        Integer profile = resolveProfile(null, dolbyVisionConfigBytes);
        if (!config.shouldConvert(profile)) {
            return null;
        }
        return convertRpuNal(blockAdditionalData, config.conversionMode(profile));
    }

    @Override
    public int lastTransformedSampleLength() {
        return lastTransformedLength;
    }

    @Override
    public byte[] transformHevcSample(
            byte[] sampleLengthDelimitedData,
            int sampleLength,
            int nalUnitLengthFieldLength,
            @Nullable byte[] blockAdditionalData,
            @Nullable byte[] dolbyVisionConfigBytes) {
        byte[] sample = sampleLengthDelimitedData;
        if (sample == null) {
            return null;
        }
        Integer profile = resolveProfile(null, dolbyVisionConfigBytes);
        if (!config.shouldConvert(profile)) {
            return null;
        }
        int mode = config.conversionMode(profile);
        boolean baseChanged = rewriteMp4HevcSampleInto(sample, sampleLength, nalUnitLengthFieldLength, mode);

        if (blockAdditionalData == null) {
            return baseChanged ? finishScratch() : null;
        }

        // `blockAdditionalData` is the value produced by onDolbyVisionBlockAdditionalData (already an
        // 8.1 RPU). Re-running conversion is a no-op (libdovi returns null for non-DV7 input), so we
        // fall back to the already-converted bytes.
        byte[] convertedBlockAdditional = convertRpuNal(blockAdditionalData, mode);
        if (convertedBlockAdditional == null) {
            convertedBlockAdditional = blockAdditionalData;
        }
        if (!baseChanged) {
            scratch.reset();
            scratch.write(sample, 0, sampleLength);
        }
        if (appendLengthDelimitedNalToScratch(convertedBlockAdditional, nalUnitLengthFieldLength)) {
            return finishScratch();
        }
        return null;
    }

    private byte[] finishScratch() {
        lastTransformedLength = scratch.size();
        return scratch.backingArray();
    }

    @Override
    public String onDolbyVisionCodecString(@Nullable String codecs, @Nullable byte[] dolbyVisionConfigBytes) {
        Integer profile = resolveProfile(codecs, dolbyVisionConfigBytes);
        if (!config.shouldConvert(profile)) {
            return null;
        }
        DolbyVisionConversionStats.recordSourceProfile(profile);
        String normalized = normalizeDolbyVisionCodecString(codecs);
        if (normalized != null && !normalized.equals(codecs)) {
            DolbyVisionConversionStats.recordCodecStringRewrite();
            return normalized;
        }
        return null;
    }

    // ── Conversion + NAL helpers ──

    private byte[] convertRpuNal(byte[] nal, int primaryMode) {
        byte[] primary = emptyToNull(DoviBridge.convertDv7RpuToDv81(nal, primaryMode));
        if (primary != null) {
            DolbyVisionConversionStats.recordConversionMode(primaryMode);
            return primary;
        }
        if (config.allowMode2Fallback() && primaryMode == 2) {
            byte[] fallback = emptyToNull(DoviBridge.convertDv7RpuToDv81(nal, 1));
            if (fallback != null) {
                DolbyVisionConversionStats.recordConversionMode(1);
            }
            return fallback;
        }
        return null;
    }

    private boolean rewriteMp4HevcSampleInto(byte[] sample, int sampleLength, int nalUnitLengthFieldLength, int mode) {
        if (nalUnitLengthFieldLength < 1 || nalUnitLengthFieldLength > 4) {
            return false;
        }
        int offset = 0;
        boolean changed = false;
        scratch.reset();
        while (offset + nalUnitLengthFieldLength <= sampleLength) {
            int nalSize = readLengthField(sample, offset, nalUnitLengthFieldLength);
            if (nalSize < 0) {
                return false;
            }
            offset += nalUnitLengthFieldLength;
            if (offset + nalSize > sampleLength) {
                return false;
            }
            int nalType = nalSize >= 1 ? nalUnitTypeAt(sample, offset) : -1;
            int layerId = nuhLayerIdAt(sample, offset, nalSize);
            if (layerId > 0 && nalType != NAL_TYPE_UNSPEC62) {
                // Enhancement-layer NAL that isn't the RPU: drop it.
                changed = true;
            } else if (nalType == NAL_TYPE_UNSPEC62) {
                byte[] rpu = java.util.Arrays.copyOfRange(sample, offset, offset + nalSize);
                byte[] converted = convertRpuNal(rpu, mode);
                byte[] convertedNal = normalizeNuhLayerIdToZero(converted != null ? converted : rpu);
                if (convertedNal != rpu) {
                    changed = true;
                }
                if (!writeLengthField(scratch, convertedNal.length, nalUnitLengthFieldLength)) {
                    return false;
                }
                scratch.write(convertedNal, 0, convertedNal.length);
            } else {
                if (!writeLengthField(scratch, nalSize, nalUnitLengthFieldLength)) {
                    return false;
                }
                scratch.write(sample, offset, nalSize);
            }
            offset += nalSize;
        }
        if (offset != sampleLength) {
            return false;
        }
        if (!changed) {
            return false;
        }
        return scratch.size() > 0;
    }

    private boolean appendLengthDelimitedNalToScratch(byte[] nalPayload, int nalUnitLengthFieldLength) {
        if (nalUnitLengthFieldLength < 1 || nalUnitLengthFieldLength > 4 || nalPayload.length == 0) {
            return false;
        }
        if (!writeLengthField(scratch, nalPayload.length, nalUnitLengthFieldLength)) {
            return false;
        }
        scratch.write(nalPayload, 0, nalPayload.length);
        return true;
    }

    private static String normalizeDolbyVisionCodecString(@Nullable String codecs) {
        String raw = codecs == null ? "" : codecs.trim();
        if (raw.isEmpty()) {
            return null;
        }
        String[] parts = raw.split("\\.");
        if (parts.length < 2) {
            return null;
        }
        String prefix = parts[0].toLowerCase(Locale.US);
        if (!prefix.equals("dvhe") && !prefix.equals("dvh1")) {
            return null;
        }
        Integer profileValue = parseIntOrNull(parts[1]);
        if (profileValue == null || (profileValue != 5 && profileValue != 7)) {
            return null;
        }
        int width = Math.max(parts[1].length(), 2);
        StringBuilder eight = new StringBuilder();
        while (eight.length() < width - 1) {
            eight.append('0');
        }
        eight.append('8');
        parts[1] = eight.toString();
        return String.join(".", parts);
    }

    private static Integer resolveProfile(@Nullable String codecs, @Nullable byte[] configBytes) {
        Integer fromCodecs = resolveProfileFromCodecString(codecs);
        if (fromCodecs != null) {
            return fromCodecs;
        }
        if (configBytes == null || configBytes.length == 0) {
            return null;
        }
        try {
            DolbyVisionConfig parsed = DolbyVisionConfig.parse(new ParsableByteArray(configBytes));
            return parsed != null ? parsed.profile : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static Integer resolveProfileFromCodecString(@Nullable String codecs) {
        String raw = codecs == null ? "" : codecs.trim();
        if (raw.isEmpty()) {
            return null;
        }
        String[] parts = raw.split("\\.");
        if (parts.length < 2) {
            return null;
        }
        String prefix = parts[0].toLowerCase(Locale.US);
        if (!prefix.equals("dvhe") && !prefix.equals("dvh1")) {
            return null;
        }
        return parseIntOrNull(parts[1]);
    }

    private static Integer parseIntOrNull(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static byte[] emptyToNull(byte[] a) {
        return (a != null && a.length > 0) ? a : null;
    }

    private static int nalUnitTypeAt(byte[] sample, int offset) {
        return (sample[offset] >> 1) & 0x3F;
    }

    private static int nuhLayerIdAt(byte[] sample, int offset, int nalSize) {
        if (nalSize < 2) {
            return 0;
        }
        int b0 = sample[offset] & 0x01;
        int b1 = sample[offset + 1] & 0xF8;
        return (b0 << 5) | (b1 >>> 3);
    }

    private static int getNuhLayerId(byte[] nalPayload) {
        if (nalPayload.length < 2) {
            return 0;
        }
        int b0 = nalPayload[0] & 0x01;
        int b1 = nalPayload[1] & 0xF8;
        return (b0 << 5) | (b1 >>> 3);
    }

    private static byte[] normalizeNuhLayerIdToZero(byte[] nalPayload) {
        if (nalPayload.length < 2 || getNuhLayerId(nalPayload) == 0) {
            return nalPayload;
        }
        byte[] out = nalPayload.clone();
        out[0] = (byte) (out[0] & 0xFE);
        out[1] = (byte) (out[1] & 0x07);
        return out;
    }

    private static int readLengthField(byte[] data, int offset, int lengthBytes) {
        int value = 0;
        for (int i = 0; i < lengthBytes; i++) {
            value = (value << 8) | (data[offset + i] & 0xFF);
        }
        return value;
    }

    private static boolean writeLengthField(ByteArrayOutputStream out, int value, int lengthBytes) {
        if (value < 0) {
            return false;
        }
        long maxNalSize;
        switch (lengthBytes) {
            case 1: maxNalSize = 0xFF; break;
            case 2: maxNalSize = 0xFFFF; break;
            case 3: maxNalSize = 0xFFFFFF; break;
            case 4: maxNalSize = Integer.MAX_VALUE; break;
            default: return false;
        }
        if (value > maxNalSize) {
            return false;
        }
        for (int shift = lengthBytes - 1; shift >= 0; shift--) {
            out.write((value >>> (shift * 8)) & 0xFF);
        }
        return true;
    }
}
