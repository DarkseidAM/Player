package com.brouken.player;

import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.ColorInfo;
import androidx.media3.common.Format;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.util.DebugTextViewHelper;

import java.util.Locale;

/**
 * "Stats for nerds" overlay: shows what the video is and what the app is doing to play it.
 * Builds on Media3's {@link DebugTextViewHelper} (which gives us the 1s auto-refresh loop) but
 * replaces the debug string with a trimmed, human-readable "essentials" panel.
 */
class StatsForNerds extends DebugTextViewHelper {

    private final ExoPlayer player;

    @Nullable private String videoDecoder;
    @Nullable private String audioDecoder;
    // "What the app is doing to it" — e.g. DV conversion state, tunneling. Set by PlayerActivity.
    @Nullable private String processing;

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

    @Override
    protected String getDebugString() {
        StringBuilder sb = new StringBuilder();
        appendVideo(sb);
        appendAudio(sb);
        appendProcessing(sb);
        return sb.toString();
    }

    private void appendVideo(StringBuilder sb) {
        Format f = player.getVideoFormat();
        if (f == null) {
            sb.append("Video: —\n");
            return;
        }
        sb.append("Video: ");
        sb.append(codecLabel(f.sampleMimeType, f.codecs));
        if (f.width != Format.NO_VALUE && f.height != Format.NO_VALUE) {
            sb.append(' ').append(f.width).append('x').append(f.height);
        }
        if (f.frameRate != Format.NO_VALUE && f.frameRate > 0) {
            sb.append(String.format(Locale.US, " @%.3ffps", f.frameRate));
        }
        sb.append('\n');
        sb.append("HDR: ").append(hdrLabel(f)).append('\n');
        sb.append("Decoder: ").append(decoderLabel(videoDecoder)).append('\n');
    }

    private void appendAudio(StringBuilder sb) {
        Format f = player.getAudioFormat();
        if (f == null) {
            sb.append("Audio: —\n");
            return;
        }
        sb.append("Audio: ");
        sb.append(codecLabel(f.sampleMimeType, f.codecs));
        if (f.channelCount != Format.NO_VALUE) {
            sb.append(' ').append(f.channelCount).append("ch");
        }
        if (f.sampleRate != Format.NO_VALUE) {
            sb.append(' ').append(f.sampleRate).append("Hz");
        }
        sb.append(" [").append(decoderLabel(audioDecoder)).append("]\n");
    }

    private void appendProcessing(StringBuilder sb) {
        if (processing != null && !processing.isEmpty()) {
            sb.append("Doing: ").append(processing);
        }
    }

    private static String codecLabel(@Nullable String mimeType, @Nullable String codecs) {
        String mime = mimeType == null ? "?" : mimeType.replaceFirst("^(video|audio)/", "");
        if (codecs != null && !codecs.isEmpty()) {
            return mime + " (" + codecs + ")";
        }
        return mime;
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
