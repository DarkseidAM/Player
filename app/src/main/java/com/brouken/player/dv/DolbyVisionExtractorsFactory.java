package com.brouken.player.dv;

import android.net.Uri;

import androidx.media3.common.C;
import androidx.media3.common.DataReader;
import androidx.media3.common.Format;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.extractor.Extractor;
import androidx.media3.extractor.ExtractorInput;
import androidx.media3.extractor.ExtractorOutput;
import androidx.media3.extractor.ExtractorsFactory;
import androidx.media3.extractor.PositionHolder;
import androidx.media3.extractor.SeekMap;
import androidx.media3.extractor.TrackOutput;

import java.io.EOFException;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * App-level Dolby Vision profile 7 → 8.1 conversion that needs no forked Media3 for the
 * containers whose RPU rides in-band as NAL units (MP4 / fMP4 = length-delimited, TS = Annex-B).
 *
 * <p>It wraps a stock {@link ExtractorsFactory} and, for video tracks of those containers,
 * intercepts the sample stream at the {@link TrackOutput} level: it rewrites the Dolby Vision
 * RPU NAL (type 62) via {@link DoviBridge}, drops the enhancement-layer NAL units, and rewrites
 * the codec string (dvhe.07 → dvhe.08). For any non-DV7 content (or when inactive) every wrapper
 * is a strict pass-through, so normal playback is unaffected.
 *
 * <p>Matroska is NOT handled here: the DV7 RPU rides in {@code BlockAdditional}, which the stock
 * MatroskaExtractor discards before any TrackOutput. MKV requires a vendored extractor (a separate,
 * device-verified follow-up); MKV streams pass through untouched for now.
 *
 * <p>Adapted from NuvioMedia/NuvioTV (GPLv3).
 */
@UnstableApi
public final class DolbyVisionExtractorsFactory implements ExtractorsFactory {

    /** How HEVC NAL units are framed in the sample stream for a given container. */
    private enum NalFormat { ANNEX_B, LENGTH_DELIMITED }

    private final ExtractorsFactory delegate;
    private final DolbyVisionConversionConfig config;

    public DolbyVisionExtractorsFactory(ExtractorsFactory delegate, DolbyVisionConversionConfig config) {
        this.delegate = delegate;
        this.config = config;
    }

    @Override
    public Extractor[] createExtractors() {
        return wrapAll(delegate.createExtractors());
    }

    @Override
    public Extractor[] createExtractors(Uri uri, Map<String, List<String>> responseHeaders) {
        return wrapAll(delegate.createExtractors(uri, responseHeaders));
    }

    private Extractor[] wrapAll(Extractor[] extractors) {
        for (int i = 0; i < extractors.length; i++) {
            extractors[i] = wrap(extractors[i]);
        }
        return extractors;
    }

    private static final String STOCK_MATROSKA_EXTRACTOR = "androidx.media3.extractor.mkv.MatroskaExtractor";

    private Extractor wrap(Extractor extractor) {
        if (!config.active) {
            return extractor;
        }
        // Matroska: the DV7 RPU rides in BlockAdditional, which the stock MatroskaExtractor discards
        // before any TrackOutput. Swap in the vendored extractor that surfaces the RPU through a
        // transformer. (Vendored from NuvioTV; reconciled to media3 1.10.1.)
        if (extractor.getClass().getName().equals(STOCK_MATROSKA_EXTRACTOR)) {
            return new com.brouken.player.dv.dvmkv.MatroskaExtractor(
                    new androidx.media3.extractor.text.DefaultSubtitleParserFactory(),
                    /* flags= */ 0,
                    new DolbyVisionMatroskaTransformer(config));
        }
        NalFormat nalFormat = nalFormatFor(extractor);
        if (nalFormat == null) {
            return extractor;
        }
        return new DolbyVisionExtractor(extractor, config, nalFormat);
    }

    private static NalFormat nalFormatFor(Extractor extractor) {
        String name = extractor.getClass().getName();
        // RPU is in-band in the sample for these containers, so reachable at the TrackOutput.
        if (name.contains("FragmentedMp4Extractor") || name.contains("Mp4Extractor")) {
            return NalFormat.LENGTH_DELIMITED;
        }
        if (name.contains("TsExtractor")) {
            return NalFormat.ANNEX_B;
        }
        return null;
    }

    /** Wraps an {@link Extractor} to inject a DV-rewriting {@link ExtractorOutput}. */
    @UnstableApi
    private static final class DolbyVisionExtractor implements Extractor {
        private final Extractor delegate;
        private final DolbyVisionConversionConfig config;
        private final NalFormat nalFormat;

        DolbyVisionExtractor(Extractor delegate, DolbyVisionConversionConfig config, NalFormat nalFormat) {
            this.delegate = delegate;
            this.config = config;
            this.nalFormat = nalFormat;
        }

        @Override
        public void init(ExtractorOutput output) {
            delegate.init(new DolbyVisionExtractorOutput(output, config, nalFormat));
        }

        @Override
        public boolean sniff(ExtractorInput input) throws IOException {
            return delegate.sniff(input);
        }

        @Override
        public int read(ExtractorInput input, PositionHolder seekPosition) throws IOException {
            return delegate.read(input, seekPosition);
        }

        @Override
        public void seek(long position, long timeUs) {
            delegate.seek(position, timeUs);
        }

        @Override
        public void release() {
            delegate.release();
        }

        @Override
        public Extractor getUnderlyingImplementation() {
            return delegate.getUnderlyingImplementation();
        }
    }

    /** Wraps an {@link ExtractorOutput} to swap video {@link TrackOutput}s for DV-rewriting ones. */
    @UnstableApi
    private static final class DolbyVisionExtractorOutput implements ExtractorOutput {
        private final ExtractorOutput delegate;
        private final DolbyVisionConversionConfig config;
        private final NalFormat nalFormat;

        DolbyVisionExtractorOutput(ExtractorOutput delegate, DolbyVisionConversionConfig config, NalFormat nalFormat) {
            this.delegate = delegate;
            this.config = config;
            this.nalFormat = nalFormat;
        }

        @Override
        public TrackOutput track(int id, int type) {
            TrackOutput track = delegate.track(id, type);
            if (type == C.TRACK_TYPE_VIDEO) {
                return new DolbyVisionTrackOutput(track, config, nalFormat);
            }
            return track;
        }

        @Override
        public void endTracks() {
            delegate.endTracks();
        }

        @Override
        public void seekMap(SeekMap seekMap) {
            delegate.seekMap(seekMap);
        }
    }

    /**
     * The actual RPU-rewriting {@link TrackOutput}. Buffers a sample's bytes and, on
     * {@link #sampleMetadata}, rewrites the DV RPU NAL (and drops EL NAL units) before forwarding
     * to the delegate. A no-op pass-through unless the track is a DV profile the config converts.
     */
    @UnstableApi
    private static final class DolbyVisionTrackOutput implements TrackOutput {
        private static final int NAL_TYPE_DV_RPU = 62;

        private final TrackOutput delegate;
        private final DolbyVisionConversionConfig config;
        private final NalFormat nalFormat;

        private final ParsableByteArray scratch = new ParsableByteArray();
        private byte[] pendingBuf = new byte[0];
        private int pendingLen = 0;
        private byte[] inputScratch = new byte[0];
        private byte[] outBuf = new byte[0];
        private int outLen = 0;

        private boolean converting = false;
        private boolean rewriteSamples = false;
        private Integer profile = null;
        private int nalLengthFieldLength = 4;

        DolbyVisionTrackOutput(TrackOutput delegate, DolbyVisionConversionConfig config, NalFormat nalFormat) {
            this.delegate = delegate;
            this.config = config;
            this.nalFormat = nalFormat;
        }

        private void ensurePendingCapacity(int extra) {
            int need = pendingLen + extra;
            if (pendingBuf.length < need) {
                int newSize = pendingBuf.length == 0 ? 16 * 1024 : pendingBuf.length;
                while (newSize < need) {
                    newSize <<= 1;
                }
                pendingBuf = java.util.Arrays.copyOf(pendingBuf, newSize);
            }
        }

        private void ensureInputScratch(int size) {
            if (inputScratch.length < size) {
                int newSize = inputScratch.length == 0 ? 16 * 1024 : inputScratch.length;
                while (newSize < size) {
                    newSize <<= 1;
                }
                inputScratch = new byte[newSize];
            }
        }

        private void outReset() {
            outLen = 0;
        }

        private void outEnsureCapacity(int extra) {
            int need = outLen + extra;
            if (outBuf.length < need) {
                int newSize = outBuf.length == 0 ? 16 * 1024 : outBuf.length;
                while (newSize < need) {
                    newSize <<= 1;
                }
                outBuf = java.util.Arrays.copyOf(outBuf, newSize);
            }
        }

        private void outWrite(byte[] src, int srcPos, int len) {
            if (len <= 0) {
                return;
            }
            outEnsureCapacity(len);
            System.arraycopy(src, srcPos, outBuf, outLen, len);
            outLen += len;
        }

        private void outWriteLengthPrefix(int value, int lengthFieldLength) {
            outEnsureCapacity(lengthFieldLength);
            for (int i = lengthFieldLength - 1; i >= 0; i--) {
                outBuf[outLen] = (byte) ((value >>> (i * 8)) & 0xFF);
                outLen += 1;
            }
        }

        @Override
        public void durationUs(long durationUs) {
            delegate.durationUs(durationUs);
        }

        @Override
        public void format(Format format) {
            profile = parseDvProfile(format.codecs);
            converting = config.shouldConvert(profile);
            rewriteSamples = converting;
            nalLengthFieldLength = parseNalLengthFieldLength(format);
            Format outFormat = format;
            if (converting) {
                DolbyVisionConversionStats.recordSourceProfile(profile);
                String rewritten = rewriteDvCodecString(format.codecs);
                if (rewritten != null && !rewritten.equals(format.codecs)) {
                    outFormat = format.buildUpon().setCodecs(rewritten).build();
                    DolbyVisionConversionStats.recordCodecStringRewrite();
                }
            }
            delegate.format(outFormat);
        }

        @Override
        public int sampleData(DataReader input, int length, boolean allowEndOfInput, int sampleDataPart)
                throws IOException {
            if (!rewriteSamples) {
                return delegate.sampleData(input, length, allowEndOfInput, sampleDataPart);
            }
            ensureInputScratch(length);
            int read = input.read(inputScratch, 0, length);
            if (read == C.RESULT_END_OF_INPUT) {
                if (allowEndOfInput) {
                    return C.RESULT_END_OF_INPUT;
                }
                throw new EOFException();
            }
            if (read <= 0) {
                return read;
            }
            if (sampleDataPart == TrackOutput.SAMPLE_DATA_PART_MAIN) {
                ensurePendingCapacity(read);
                System.arraycopy(inputScratch, 0, pendingBuf, pendingLen, read);
                pendingLen += read;
            } else {
                scratch.reset(inputScratch, read);
                delegate.sampleData(scratch, read, sampleDataPart);
            }
            return read;
        }

        @Override
        public void sampleData(ParsableByteArray data, int length, int sampleDataPart) {
            if (!rewriteSamples || sampleDataPart != TrackOutput.SAMPLE_DATA_PART_MAIN || length <= 0) {
                delegate.sampleData(data, length, sampleDataPart);
                return;
            }
            ensurePendingCapacity(length);
            data.readBytes(pendingBuf, pendingLen, length);
            pendingLen += length;
        }

        @Override
        public void sampleMetadata(long timeUs, int flags, int size, int offset, CryptoData cryptoData) {
            if (!rewriteSamples || pendingLen == 0) {
                delegate.sampleMetadata(timeUs, flags, size, offset, cryptoData);
                return;
            }
            // `offset` = trailing buffered bytes that belong to the NEXT sample. Keep them in
            // `pending` and rewrite only this sample's bytes. We deliver the rewritten sample
            // fresh and pass offset 0, which keeps sizing correct even when the rewrite changes
            // the length.
            int carrySize = Math.max(0, Math.min(offset, pendingLen));
            int sampleEnd = pendingLen - carrySize;
            int rewrittenLen = nalFormat == NalFormat.ANNEX_B
                    ? rewriteAnnexB(pendingBuf, sampleEnd)
                    : rewriteLengthDelimited(pendingBuf, sampleEnd, nalLengthFieldLength);
            boolean useRewritten = rewrittenLen > 0;
            byte[] outputData = useRewritten ? outBuf : pendingBuf;
            int outputLen = useRewritten ? rewrittenLen : sampleEnd;
            scratch.reset(outputData, outputLen);
            delegate.sampleData(scratch, outputLen);
            delegate.sampleMetadata(timeUs, flags, outputLen, 0, cryptoData);
            if (carrySize > 0) {
                System.arraycopy(pendingBuf, sampleEnd, pendingBuf, 0, carrySize);
            }
            pendingLen = carrySize;
        }

        // ── Length-delimited (MP4 / fMP4) ──
        private int rewriteLengthDelimited(byte[] sample, int sampleLen, int lengthFieldLength) {
            if (sampleLen < lengthFieldLength) {
                return -1;
            }
            outReset();
            boolean changed = false;
            int pos = 0;
            while (pos + lengthFieldLength <= sampleLen) {
                int nalSize = 0;
                for (int i = 0; i < lengthFieldLength; i++) {
                    nalSize = (nalSize << 8) | (sample[pos + i] & 0xFF);
                }
                int nalStart = pos + lengthFieldLength;
                if (nalSize <= 0 || nalStart + nalSize > sampleLen) {
                    return -1;
                }
                int nalType = nalUnitTypeAt(sample, nalStart);
                int layerId = nuhLayerIdAt(sample, nalStart, nalSize);
                if (layerId > 0 && nalType != NAL_TYPE_DV_RPU) {
                    // Enhancement-layer NAL that isn't the RPU: drop it.
                    changed = true;
                } else if (nalType == NAL_TYPE_DV_RPU) {
                    byte[] nal = java.util.Arrays.copyOfRange(sample, nalStart, nalStart + nalSize);
                    byte[] converted = convertDvRpu(nal);
                    byte[] transformed = normalizeNuhLayerIdToZero(converted != null ? converted : nal);
                    if (transformed != nal) {
                        changed = true;
                    }
                    outWriteLengthPrefix(transformed.length, lengthFieldLength);
                    outWrite(transformed, 0, transformed.length);
                } else {
                    // Base-layer NAL: forward straight from the sample buffer.
                    outWriteLengthPrefix(nalSize, lengthFieldLength);
                    outWrite(sample, nalStart, nalSize);
                }
                pos = nalStart + nalSize;
            }
            if (pos != sampleLen) {
                return -1;
            }
            return changed ? outLen : 0;
        }

        // ── Annex-B (TS) ──
        private int rewriteAnnexB(byte[] sample, int sampleLen) {
            outReset();
            int scan = 0;
            boolean changed = false;
            while (scan < sampleLen) {
                int start = findStartCode(sample, scan, sampleLen);
                if (start < 0) {
                    break;
                }
                int next = findStartCode(sample, start + 3, sampleLen);
                if (next < 0) {
                    next = sampleLen;
                }
                if (start > scan) {
                    outWrite(sample, scan, start - scan);
                }
                int scLen = startCodeLength(sample, start, next);
                int payloadOffset = start + scLen;
                if (payloadOffset >= next) {
                    outWrite(sample, start, next - start);
                } else {
                    int nalSize = next - payloadOffset;
                    int nalType = nalUnitTypeAt(sample, payloadOffset);
                    int layerId = nuhLayerIdAt(sample, payloadOffset, nalSize);
                    if (layerId > 0 && nalType != NAL_TYPE_DV_RPU) {
                        changed = true;
                    } else if (nalType == NAL_TYPE_DV_RPU) {
                        byte[] nal = java.util.Arrays.copyOfRange(sample, payloadOffset, next);
                        byte[] converted = convertDvRpu(nal);
                        byte[] transformed = normalizeNuhLayerIdToZero(converted != null ? converted : nal);
                        if (transformed != nal) {
                            changed = true;
                        }
                        outWrite(sample, start, scLen);
                        outWrite(transformed, 0, transformed.length);
                    } else {
                        outWrite(sample, start, next - start);
                    }
                }
                scan = next;
            }
            if (scan < sampleLen) {
                outWrite(sample, scan, sampleLen - scan);
            }
            return changed ? outLen : 0;
        }

        private byte[] convertDvRpu(byte[] nal) {
            int mode = config.conversionMode(profile);
            byte[] converted = emptyToNull(DoviBridge.convertDv7RpuToDv81(nal, mode));
            if (converted != null) {
                DolbyVisionConversionStats.recordConversionMode(mode);
            } else if (config.allowMode2Fallback() && mode == 2) {
                converted = emptyToNull(DoviBridge.convertDv7RpuToDv81(nal, 1));
                if (converted != null) {
                    DolbyVisionConversionStats.recordConversionMode(1);
                }
            }
            return converted;
        }

        private static byte[] emptyToNull(byte[] a) {
            return (a != null && a.length > 0) ? a : null;
        }

        private static int nalUnitTypeAt(byte[] data, int offset) {
            return (data[offset] >> 1) & 0x3F;
        }

        private static int nuhLayerIdAt(byte[] data, int offset, int nalSize) {
            if (nalSize < 2) {
                return 0;
            }
            int b0 = data[offset] & 0x01;
            int b1 = data[offset + 1] & 0xF8;
            return (b0 << 5) | (b1 >>> 3);
        }

        private static int nuhLayerId(byte[] nal) {
            if (nal.length < 2) {
                return 0;
            }
            int b0 = nal[0] & 0x01;
            int b1 = nal[1] & 0xF8;
            return (b0 << 5) | (b1 >>> 3);
        }

        private static byte[] normalizeNuhLayerIdToZero(byte[] nal) {
            if (nal.length < 2 || nuhLayerId(nal) == 0) {
                return nal;
            }
            byte[] copy = nal.clone();
            copy[0] = (byte) (copy[0] & 0xFE);
            copy[1] = (byte) (copy[1] & 0x07);
            return copy;
        }

        private static int findStartCode(byte[] data, int from, int limit) {
            int i = from;
            while (i + 2 < limit) {
                if (data[i] == 0 && data[i + 1] == 0) {
                    if (data[i + 2] == 1) {
                        return i;
                    }
                    if (i + 3 < limit && data[i + 2] == 0 && data[i + 3] == 1) {
                        return i;
                    }
                }
                i++;
            }
            return -1;
        }

        private static int startCodeLength(byte[] data, int startCodeOffset, int limit) {
            if (startCodeOffset + 3 < limit
                    && data[startCodeOffset] == 0
                    && data[startCodeOffset + 1] == 0
                    && data[startCodeOffset + 2] == 0
                    && data[startCodeOffset + 3] == 1) {
                return 4;
            }
            return 3;
        }
    }

    private static final Pattern DV_PROFILE_PATTERN =
            Pattern.compile("^(?:dvhe|dvav|dvh1|dva1)\\.(\\d+)\\.");
    private static final Pattern DV_CODEC_REWRITE_PATTERN =
            Pattern.compile("(?i)(dvhe|dvav|dvh1|dva1)\\.0[57]\\.");

    private static Integer parseDvProfile(String codecs) {
        if (codecs == null || codecs.trim().isEmpty()) {
            return null;
        }
        Matcher m = DV_PROFILE_PATTERN.matcher(codecs.trim().toLowerCase(java.util.Locale.US));
        if (!m.find()) {
            return null;
        }
        try {
            return Integer.parseInt(m.group(1));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** dvhe.05/.07.xx becomes dvhe.08.xx so the Format advertises single-layer 8.1. */
    private static String rewriteDvCodecString(String codecs) {
        if (codecs == null || codecs.trim().isEmpty()) {
            return null;
        }
        Matcher m = DV_CODEC_REWRITE_PATTERN.matcher(codecs);
        return m.replaceAll("$1.08.");
    }

    private static int parseNalLengthFieldLength(Format format) {
        // HEVC hvcC: lengthSizeMinusOne is the low 2 bits of the byte at index 21. Fall back to
        // the near-universal 4 if not parseable.
        List<byte[]> initData = format.initializationData;
        if (initData == null || initData.isEmpty()) {
            return 4;
        }
        byte[] csd = initData.get(0);
        if (csd.length <= 21) {
            return 4;
        }
        if (csd[0] != 1) {
            return 4;
        }
        return (csd[21] & 0x03) + 1;
    }
}
