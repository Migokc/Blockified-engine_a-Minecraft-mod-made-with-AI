package com.fnfmod.client.gui.editor;

import com.fnfmod.FnfMod;
import org.lwjgl.stb.STBVorbis;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** CPU-only async OGG envelope used by chart editor. No OpenAL/GL work leaves client thread. */
final class EditorWaveform {
    static final double BIN_MS = 8.0;
    private static final ExecutorService DECODER = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "Blockified waveform decoder");
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY);
        return thread;
    });

    record Data(float[] peaks, double[] onsetsMs, double durationMs, double estimatedBpm) {
        float peakAt(double timeMs) {
            if (peaks.length == 0 || timeMs < 0 || timeMs > durationMs) return 0;
            int index = Math.max(0, Math.min(peaks.length - 1, (int) (timeMs / BIN_MS)));
            return peaks[index];
        }
    }

    static CompletableFuture<Data> decode(Path file) {
        return file == null ? CompletableFuture.completedFuture(null)
                : CompletableFuture.supplyAsync(() -> decodeNow(file), DECODER);
    }

    private static Data decodeNow(Path file) {
        ByteBuffer encoded = null;
        ShortBuffer pcm = null;
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            encoded = MemoryUtil.memAlloc(Math.toIntExact(channel.size()));
            while (encoded.hasRemaining() && channel.read(encoded) > 0) {}
            encoded.flip();
            try (MemoryStack stack = MemoryStack.stackPush()) {
                IntBuffer channelsOut = stack.mallocInt(1);
                IntBuffer rateOut = stack.mallocInt(1);
                pcm = STBVorbis.stb_vorbis_decode_memory(encoded, channelsOut, rateOut);
                if (pcm == null) return null;
                int channels = Math.max(1, channelsOut.get(0));
                int rate = Math.max(1, rateOut.get(0));
                int frames = pcm.limit() / channels;
                int binFrames = Math.max(1, (int) Math.round(rate * BIN_MS / 1000.0));
                float[] peaks = new float[Math.max(1, (frames + binFrames - 1) / binFrames)];
                for (int frame = 0; frame < frames; frame++) {
                    float peak = 0;
                    int base = frame * channels;
                    for (int channelIndex = 0; channelIndex < channels; channelIndex++) {
                        peak = Math.max(peak, Math.abs(pcm.get(base + channelIndex) / 32768f));
                    }
                    int bin = frame / binFrames;
                    if (peak > peaks[bin]) peaks[bin] = peak;
                }
                smooth(peaks);
                double[] onsets = findOnsets(peaks);
                return new Data(peaks, onsets, frames * 1000.0 / rate, estimateBpm(onsets));
            }
        } catch (Exception error) {
            FnfMod.LOGGER.warn("Could not decode editor waveform {}: {}", file, error.toString());
            return null;
        } finally {
            if (pcm != null) org.lwjgl.system.libc.LibCStdlib.free(pcm);
            if (encoded != null) MemoryUtil.memFree(encoded);
        }
    }

    private static void smooth(float[] peaks) {
        if (peaks.length < 3) return;
        float previous = peaks[0];
        for (int i = 1; i < peaks.length - 1; i++) {
            float current = peaks[i];
            peaks[i] = Math.max(current, (previous + current + peaks[i + 1]) / 3f);
            previous = current;
        }
    }

    private static double[] findOnsets(float[] peaks) {
        List<Double> result = new ArrayList<>();
        double last = -1000;
        double average = 0;
        for (int i = 1; i < peaks.length; i++) {
            average = average * 0.97 + peaks[i] * 0.03;
            double time = i * BIN_MS;
            float rise = peaks[i] - peaks[i - 1];
            if (time - last >= 70 && peaks[i] > Math.max(0.08, average * 1.45) && rise > 0.035) {
                result.add(time);
                last = time;
            }
        }
        return result.stream().mapToDouble(Double::doubleValue).toArray();
    }

    private static double estimateBpm(double[] onsets) {
        if (onsets.length < 4) return 0;
        int[] histogram = new int[281];
        for (int i = 1; i < onsets.length; i++) {
            double interval = onsets[i] - onsets[i - 1];
            if (interval <= 0) continue;
            double bpm = 60000.0 / interval;
            while (bpm < 60) bpm *= 2;
            while (bpm > 200) bpm /= 2;
            int bucket = (int) Math.round(bpm * 2);
            if (bucket >= 0 && bucket < histogram.length) histogram[bucket]++;
        }
        int best = 0;
        for (int i = 1; i < histogram.length; i++) if (histogram[i] > histogram[best]) best = i;
        return best / 2.0;
    }
}
