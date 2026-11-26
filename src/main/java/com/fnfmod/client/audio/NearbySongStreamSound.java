package com.fnfmod.client.audio;

import com.fnfmod.FnfMod;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.client.sounds.JOrbisAudioStream;
import net.minecraft.client.sounds.SoundBufferLibrary;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.client.sounds.WeighedSoundEvents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundSource;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.util.RandomSource;
import net.minecraft.util.valueproviders.ConstantFloat;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.DoubleSupplier;
import javax.sound.sampled.AudioFormat;

/**
 * A local OGG played by Minecraft's streaming sound engine. Unlike SongPlayer,
 * this never decodes and stores an entire track in memory.
 */
final class NearbySongStreamSound extends AbstractTickableSoundInstance {
    private static final ResourceLocation LOCATION = FnfMod.id("nearby_song_stream");
    private static final SoundEvent SOUND_EVENT = SoundEvent.createVariableRangeEvent(LOCATION);
    private static final Sound STREAM_SOUND = new Sound(LOCATION,
            ConstantFloat.of(1.0F), ConstantFloat.of(1.0F), 1,
            Sound.Type.FILE, true, false, 64);
    private static final WeighedSoundEvents EVENT = createEvent();
    private static final int READ_CHUNK_BYTES = 64 * 1024;
    private static final long MIN_SEEK_BUFFER_BYTES = 16 * 1024;

    private final Path file;
    private final DoubleSupplier targetPositionMs;
    private final DoubleSupplier gain;
    private final Runnable onFailure;
    private volatile boolean streamReady;
    private volatile double streamPositionMs;
    private volatile long streamReadyNano;
    private volatile boolean cancelled;
    private double clockPositionMs;
    private long clockNano;
    private float playbackPitch = 1.0F;

    NearbySongStreamSound(Path file, Vec3 origin, DoubleSupplier targetPositionMs,
                          DoubleSupplier gain, Runnable onFailure) {
        super(SOUND_EVENT, SoundSource.RECORDS, RandomSource.create());
        this.file = file;
        this.targetPositionMs = targetPositionMs;
        this.gain = gain;
        this.onFailure = onFailure;
        this.x = origin.x;
        this.y = origin.y;
        this.z = origin.z;
        this.volume = 0.0F;
        this.pitch = 1.0F;
        this.looping = false;
        // Keep the source stereo; distance is applied as a smooth per-listener gain.
        this.attenuation = SoundInstance.Attenuation.NONE;
        this.relative = false;
    }

    private static WeighedSoundEvents createEvent() {
        WeighedSoundEvents event = new WeighedSoundEvents(LOCATION, null);
        event.addSound(STREAM_SOUND);
        return event;
    }

    @Override
    public WeighedSoundEvents resolve(SoundManager soundManager) {
        this.sound = STREAM_SOUND;
        return EVENT;
    }

    @Override
    public CompletableFuture<AudioStream> getStream(SoundBufferLibrary soundBuffers, Sound sound,
                                                     boolean looping) {
        return CompletableFuture.supplyAsync(() -> {
            InputStream input = null;
            JOrbisAudioStream decoder = null;
            try {
                if (isStopped()) throw new IOException("Nearby song playback was cancelled");
                input = Files.newInputStream(file);
                decoder = new JOrbisAudioStream(input);
                input = null; // decoder owns the stream now
                double position = seekToCurrentPosition(decoder);
                if (isStopped()) throw new IOException("Nearby song playback was cancelled");
                streamPositionMs = position;
                streamReadyNano = System.nanoTime();
                streamReady = true;
                return cancelledStream(decoder);
            } catch (IOException | RuntimeException error) {
                closeQuietly(decoder);
                closeQuietly(input);
                throw new CompletionException(error);
            }
        }, Util.nonCriticalIoPool()).whenComplete((stream, error) -> {
            if (error != null && !isStopped()) {
                Minecraft.getInstance().execute(() -> {
                    if (!isStopped()) onFailure.run();
                });
            }
        });
    }

    /**
     * Decode and discard only the already-played portion. Re-read the live
     * target while catching up so slow disk/CPU startup does not start stale.
     */
    private double seekToCurrentPosition(JOrbisAudioStream decoder) throws IOException {
        double bytesPerMs = decoder.getFormat().getFrameRate()
                * decoder.getFormat().getFrameSize() / 1000.0;
        if (!Double.isFinite(bytesPerMs) || bytesPerMs <= 0) {
            throw new IOException("Invalid OGG stream format");
        }

        long discardedBytes = 0;
        while (!isStopped()) {
            double targetMs = Math.max(0.0, targetPositionMs.getAsDouble() - 80.0);
            if (!Double.isFinite(targetMs)) throw new IOException("Invalid nearby song position");
            long targetBytes = (long) Math.min(Long.MAX_VALUE / 2.0, targetMs * bytesPerMs);
            long remaining = targetBytes - discardedBytes;
            if (remaining <= MIN_SEEK_BUFFER_BYTES) break;

            ByteBuffer discarded = decoder.read((int) Math.min(READ_CHUNK_BYTES, remaining));
            int read = discarded.remaining();
            if (read <= 0) break; // the track ended before the reported position
            discardedBytes += read;
        }
        if (isStopped()) throw new IOException("Nearby song playback was cancelled");
        return discardedBytes / bytesPerMs;
    }

    private static void closeQuietly(AutoCloseable closeable) {
        if (closeable == null) return;
        try { closeable.close(); } catch (Exception ignored) {}
    }

    private AudioStream cancelledStream(JOrbisAudioStream decoder) {
        return new AudioStream() {
            @Override
            public AudioFormat getFormat() {
                return decoder.getFormat();
            }

            @Override
            public ByteBuffer read(int size) throws IOException {
                return cancelled ? ByteBuffer.allocate(0) : decoder.read(size);
            }

            @Override
            public void close() throws IOException {
                decoder.close();
            }
        };
    }

    @Override
    public void tick() {
        if (!streamReady) {
            this.volume = 0.0F;
            return;
        }

        long now = System.nanoTime();
        if (clockNano == 0) {
            clockPositionMs = streamPositionMs;
            clockNano = streamReadyNano;
        }
        if (now > clockNano) {
            clockPositionMs += (now - clockNano) / 1_000_000.0 * playbackPitch;
            clockNano = now;
        }

        double errorMs = targetPositionMs.getAsDouble() - clockPositionMs;
        // Gently correct network jitter instead of repeatedly stopping/restarting
        // the stream, which would cause audible gaps and decoder churn.
        float desiredPitch = (float) Math.max(0.97, Math.min(1.03, 1.0 + errorMs / 8000.0));
        playbackPitch += (desiredPitch - playbackPitch) * 0.15F;
        this.pitch = playbackPitch;
        this.volume = (float) Math.max(0.0, Math.min(1.0, gain.getAsDouble()));
    }

    void setOrigin(Vec3 origin) {
        this.x = origin.x;
        this.y = origin.y;
        this.z = origin.z;
    }

    void finish() {
        cancelled = true;
        stop();
    }
}
