package com.brouken.player;

import android.text.TextUtils;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.ColorInfo;
import androidx.media3.common.Format;
import androidx.media3.common.Tracks;
import androidx.media3.exoplayer.DecoderCounters;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.upstream.BandwidthMeter;
import androidx.media3.exoplayer.util.DebugTextViewHelper;

import com.brouken.player.dv.DolbyVisionConversionStats;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * "Stats for nerds" overlay: a labeled panel showing what the video is and what the app is doing
 * to play it. Builds on Media3's {@link DebugTextViewHelper} for the 1s auto-refresh loop, but
 * renders its own human-readable, multi-group panel.
 *
 * <p>For DV7→8.1 playback the video line shows the source→output codec, since the player only sees
 * the rewritten format ({@link DolbyVisionConversionStats#getLastSourceCodec()} supplies the
 * original).
 */
class StatsForNerds extends DebugTextViewHelper {

    private static final int LABEL_WIDTH = 8;

    private final ExoPlayer player;

    @Nullable private String videoDecoder;
    @Nullable private String audioDecoder;
    @Nullable private String processing;
    private boolean tunneling;
    private int backBufferSeconds;
    private long mediaSizeBytes = -1;
    private boolean network;
    @Nullable private BandwidthMeter bandwidthMeter;

    StatsForNerds(ExoPlayer player, TextView textView) {
        super(player, textView);
        this.player = player;
    }

    void setVideoDecoder(@Nullable String name) {
        this.videoDecoder = name;
    }

    void setAudioDecoder(@Nullable String name) {
        this.audioDecoder = name;
    }

    void setProcessing(@Nullable String processing) {
        this.processing = processing;
    }

    void setTunneling(boolean tunneling) {
        this.tunneling = tunneling;
    }

    void setBackBufferSeconds(int backBufferSeconds) {
        this.backBufferSeconds = backBufferSeconds;
    }

    void setMediaSizeBytes(long mediaSizeBytes) {
        this.mediaSizeBytes = mediaSizeBytes;
    }

    void setNetwork(boolean network) {
        this.network = network;
    }

    void setBandwidthMeter(@Nullable BandwidthMeter bandwidthMeter) {
        this.bandwidthMeter = bandwidthMeter;
    }

    @Override
    protected String getDebugString() {
        StringBuilder sb = new StringBuilder();
        appendVideo(sb);
        appendAudio(sb);
        appendPlayback(sb);
        appendProcessing(sb);
        return sb.toString();
    }

    private void row(StringBuilder sb, String label, String value) {
        sb.append(String.format(Locale.US, "%-" + LABEL_WIDTH + "s", label)).append(value).append('\n');
    }

    private void appendVideo(StringBuilder sb) {
        Format f = player.getVideoFormat();
        if (f == null) {
            row(sb, "Video", "—");
            return;
        }
        StringBuilder v = new StringBuilder();
        String sourceCodec = DolbyVisionConversionStats.getLastSourceCodec();
        if (sourceCodec != null && f.codecs != null && !sourceCodec.equalsIgnoreCase(f.codecs)) {
            // Symmetric, tight codec pair (mime is implied by the HDR line): dvhe.07.06 → dvhe.08.06
            v.append(sourceCodec).append(" → ").append(f.codecs);
        } else {
            v.append(codecLabel(f.sampleMimeType, f.codecs));
        }
        if (f.width != Format.NO_VALUE && f.height != Format.NO_VALUE) {
            v.append("  ").append(f.width).append('x').append(f.height);
        }
        if (f.frameRate != Format.NO_VALUE && f.frameRate > 0) {
            v.append(String.format(Locale.US, " @%.3f", f.frameRate));
        }
        row(sb, "Video", v.toString());

        row(sb, "HDR", hdrLabel(f) + "   " + colorLabel(f));

        String dec = decoderLabel(videoDecoder);
        if (tunneling) {
            dec = dec.endsWith(")") ? dec.substring(0, dec.length() - 1) + ", tunneled)" : dec + " (tunneled)";
        }
        row(sb, "Decoder", dec);

        DecoderCounters c = player.getVideoDecoderCounters();
        if (c != null) {
            row(sb, "Frames", "rendered " + c.renderedOutputBufferCount
                    + "  dropped " + c.droppedBufferCount
                    + "  skipped " + c.skippedOutputBufferCount);
        }

        StringBuilder b = new StringBuilder(bitrateLabel(f));
        if (f.pixelWidthHeightRatio != Format.NO_VALUE && f.pixelWidthHeightRatio > 0) {
            b.append(String.format(Locale.US, "   PAR %.2f", f.pixelWidthHeightRatio));
        }
        b.append("  rot ").append(f.rotationDegrees == Format.NO_VALUE ? 0 : f.rotationDegrees).append('°');
        row(sb, "Bitrate", b.toString());
    }

    private void appendAudio(StringBuilder sb) {
        Format f = player.getAudioFormat();
        if (f == null) {
            row(sb, "Audio", "—");
        } else {
            StringBuilder a = new StringBuilder(codecLabel(f.sampleMimeType, f.codecs));
            if (f.channelCount != Format.NO_VALUE) {
                a.append(' ').append(f.channelCount).append("ch");
            }
            if (f.sampleRate != Format.NO_VALUE) {
                a.append(' ').append(f.sampleRate).append("Hz");
            }
            int abr = f.bitrate != Format.NO_VALUE ? f.bitrate : f.averageBitrate;
            if (abr != Format.NO_VALUE && abr > 0) {
                a.append(' ').append(Math.round(abr / 1000f)).append("kbps");
            }
            a.append(" [").append(decoderLabel(audioDecoder)).append(']');
            if (!TextUtils.isEmpty(f.language)) {
                a.append(' ').append(f.language);
            }
            row(sb, "Audio", a.toString());
        }
        appendAudioTracks(sb);
    }

    private void appendAudioTracks(StringBuilder sb) {
        Tracks tracks = player.getCurrentTracks();
        List<String> entries = new ArrayList<>();
        int total = 0;
        int selectedOrdinal = 0;
        for (Tracks.Group group : tracks.getGroups()) {
            if (group.getType() != C.TRACK_TYPE_AUDIO) {
                continue;
            }
            for (int i = 0; i < group.length; i++) {
                total++;
                Format tf = group.getTrackFormat(i);
                boolean selected = group.isTrackSelected(i);
                if (selected) {
                    selectedOrdinal = total;
                }
                StringBuilder e = new StringBuilder(selected ? "▶" : "·");
                e.append(TextUtils.isEmpty(tf.language) ? "und" : tf.language);
                e.append(' ').append(codecLabel(tf.sampleMimeType, null));
                if (tf.channelCount != Format.NO_VALUE) {
                    e.append(' ').append(tf.channelCount).append("ch");
                }
                entries.add(e.toString());
            }
        }
        if (total > 1) {
            row(sb, "Tracks", TextUtils.join("  ", entries) + "  (" + selectedOrdinal + "/" + total + ")");
        }
    }

    private void appendPlayback(StringBuilder sb) {
        long pos = player.getCurrentPosition();
        long buffered = player.getBufferedPosition();
        long aheadMs = Math.max(0, buffered - pos);
        float speed = player.getPlaybackParameters().speed;
        row(sb, "Cache", String.format(Locale.US, "+%ds ahead / %ds back    speed %.2fx",
                aheadMs / 1000, backBufferSeconds, speed));
        long dur = player.getDuration();
        row(sb, "Time", formatTime(pos) + " / " + (dur == C.TIME_UNSET ? "—" : formatTime(dur)));
        // Measured network throughput (connection speed) — only meaningful for network streams.
        if (network && bandwidthMeter != null) {
            long bps = bandwidthMeter.getBitrateEstimate();
            if (bps > 0) {
                row(sb, "Net", String.format(Locale.US, "~%.1f Mbps (measured)", bps / 1_000_000f));
            }
        }
    }

    private void appendProcessing(StringBuilder sb) {
        if (processing != null && !processing.isEmpty()) {
            sb.append(String.format(Locale.US, "%-" + LABEL_WIDTH + "s", "Doing")).append(processing);
        }
    }

    private static String codecLabel(@Nullable String mimeType, @Nullable String codecs) {
        String mime = mimeType == null ? "?" : mimeType.replaceFirst("^(video|audio)/", "");
        if (codecs != null && !codecs.isEmpty()) {
            return mime + " (" + codecs + ")";
        }
        return mime;
    }

    private static String colorLabel(Format f) {
        ColorInfo color = f.colorInfo;
        List<String> parts = new ArrayList<>();
        if (color != null) {
            switch (color.colorSpace) {
                case C.COLOR_SPACE_BT2020: parts.add("BT2020"); break;
                case C.COLOR_SPACE_BT709: parts.add("BT709"); break;
                case C.COLOR_SPACE_BT601: parts.add("BT601"); break;
                default: break;
            }
            switch (color.colorTransfer) {
                case C.COLOR_TRANSFER_ST2084: parts.add("PQ"); break;
                case C.COLOR_TRANSFER_HLG: parts.add("HLG"); break;
                case C.COLOR_TRANSFER_SDR: parts.add("SDR"); break;
                default: break;
            }
            if (color.colorRange == C.COLOR_RANGE_FULL) {
                parts.add("full");
            } else if (color.colorRange == C.COLOR_RANGE_LIMITED) {
                parts.add("limited");
            }
        }
        int depth = bitDepth(f);
        if (depth > 0) {
            parts.add(depth + "-bit");
        }
        return parts.isEmpty() ? "" : TextUtils.join("/", parts);
    }

    private static int bitDepth(Format f) {
        ColorInfo color = f.colorInfo;
        if (color != null && color.lumaBitdepth != Format.NO_VALUE && color.lumaBitdepth > 0) {
            return color.lumaBitdepth;
        }
        String codecs = f.codecs == null ? "" : f.codecs.toLowerCase(Locale.US);
        if (codecs.startsWith("dvhe") || codecs.startsWith("dvh1")
                || codecs.contains("hvc1.2") || codecs.contains("hev1.2")) {
            return 10;
        }
        if (color != null
                && (color.colorTransfer == C.COLOR_TRANSFER_ST2084 || color.colorTransfer == C.COLOR_TRANSFER_HLG)) {
            return 10;
        }
        return 0;
    }

    /** Per-stream bitrate if the Format carries one, else an overall estimate (size ÷ duration). */
    private String bitrateLabel(Format f) {
        int br = f.bitrate != Format.NO_VALUE ? f.bitrate : f.averageBitrate;
        if (br != Format.NO_VALUE && br > 0) {
            return String.format(Locale.US, "~%.1f Mbps", br / 1_000_000f);
        }
        long dur = player.getDuration();
        if (mediaSizeBytes > 0 && dur > 0 && dur != C.TIME_UNSET) {
            double mbps = (mediaSizeBytes * 8000.0) / dur / 1_000_000.0;
            return String.format(Locale.US, "~%.1f Mbps (overall)", mbps);
        }
        return "—";
    }

    private static String formatTime(long ms) {
        if (ms < 0) {
            ms = 0;
        }
        long totalSec = ms / 1000;
        long h = totalSec / 3600;
        long m = (totalSec % 3600) / 60;
        long s = totalSec % 60;
        if (h > 0) {
            return String.format(Locale.US, "%d:%02d:%02d", h, m, s);
        }
        return String.format(Locale.US, "%02d:%02d", m, s);
    }

    private static String hdrLabel(Format f) {
        String codecs = f.codecs == null ? "" : f.codecs.toLowerCase(Locale.US);
        if (codecs.startsWith("dvhe") || codecs.startsWith("dvh1")
                || codecs.startsWith("dvav") || codecs.startsWith("dva1")) {
            String[] parts = codecs.split("\\.");
            if (parts.length >= 2) {
                try {
                    return "Dolby Vision profile " + Integer.parseInt(parts[1]);
                } catch (NumberFormatException ignored) {
                    // fall through
                }
            }
            return "Dolby Vision";
        }
        ColorInfo color = f.colorInfo;
        if (color != null) {
            if (color.colorTransfer == C.COLOR_TRANSFER_ST2084) {
                return "HDR10";
            }
            if (color.colorTransfer == C.COLOR_TRANSFER_HLG) {
                return "HLG";
            }
        }
        return "SDR";
    }

    private static String decoderLabel(@Nullable String name) {
        if (name == null) {
            return "—";
        }
        String lower = name.toLowerCase(Locale.US);
        boolean software = lower.startsWith("omx.google")
                || lower.startsWith("c2.android")
                || lower.contains("ffmpeg")
                || lower.contains(".sw.")
                || lower.endsWith(".sw");
        return name + (software ? " (software)" : " (hardware)");
    }
}
