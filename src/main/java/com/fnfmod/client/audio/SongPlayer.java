package com.fnfmod.client.audio;

import com.fnfmod.FnfMod;
import net.minecraft.client.Minecraft;
import net.minecraft.sounds.SoundSource;
import org.lwjgl.openal.AL10;
import org.lwjgl.openal.AL11;
import org.lwjgl.stb.STBVorbis;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Plays inst + vocal tracks directly through OpenAL (bypassing Minecraft's
 * sound system) so multiple stems stay sample-synced and we can query an
 * accurate song position.
 */
public class SongPlayer {

    public enum Role { INST, VOICES, VOICES_PLAYER, VOICES_OPPONENT }

    private static class Track {
        Role role;
        int buffer;
        int source;
        float volume = 1.0f;
        double durationMs;
    }

    private final List<Track> tracks = new ArrayList<>();
    private Track inst;
    private boolean started;
    private boolean paused;
    private boolean disposed;
    private float playbackRate = 1.0f;

    // position smoothing (AL updates in mixer-sized steps)
    private double lastRawMs = -1;
    private long lastRawNano;
    private double smoothedMs;
    // Reference point (position at a known nanoTime) for judging inputs that
    // arrived between frames. Volatile so a future high-rate input path can read
    // them without tearing; the values are only written from positionMs().
    private volatile double refMs;
    private volatile long refNano;

    public void load(Path instFile, Path voices, Path voicesPlayer, Path voicesOpponent) throws IOException {
        if (disposed) throw new IOException("Song player is already disposed");
        try {
            if (instFile == null) throw new IOException("Missing Inst.ogg");
            inst = loadTrack(instFile, Role.INST);
            if (voicesPlayer != null || voicesOpponent != null) {
                if (voicesPlayer != null) loadTrack(voicesPlayer, Role.VOICES_PLAYER);
                if (voicesOpponent != null) loadTrack(voicesOpponent, Role.VOICES_OPPONENT);
            } else if (voices != null) {
                loadTrack(voices, Role.VOICES);
            }
        } catch (Throwable error) {
            dispose();
            rethrowLoadFailure(error);
        }
    }

    private Track loadTrack(Path path, Role role) throws IOException {
        ByteBuffer fileData = null;
        ShortBuffer pcm = null;
        Track track = null;
        try (FileChannel fc = FileChannel.open(path, StandardOpenOption.READ)) {
            int size = (int) fc.size();
            fileData = MemoryUtil.memAlloc(size);
            while (fileData.hasRemaining()) {
                if (fc.read(fileData) <= 0) break;
            }
            fileData.flip();

            try (MemoryStack stack = MemoryStack.stackPush()) {
                IntBuffer channels = stack.mallocInt(1);
                IntBuffer sampleRate = stack.mallocInt(1);
                pcm = STBVorbis.stb_vorbis_decode_memory(fileData, channels, sampleRate);
                if (pcm == null) throw new IOException("Failed to decode OGG: " + path);

                int ch = channels.get(0);
                int rate = sampleRate.get(0);
                int format = ch == 2 ? AL10.AL_FORMAT_STEREO16 : AL10.AL_FORMAT_MONO16;

                track = new Track();
                track.role = role;
                track.buffer = AL10.alGenBuffers();
                AL10.alBufferData(track.buffer, format, pcm, rate);
                track.source = AL10.alGenSources();
                AL10.alSourcei(track.source, AL10.AL_BUFFER, track.buffer);
                AL10.alSourcei(track.source, AL10.AL_SOURCE_RELATIVE, AL10.AL_TRUE);
                AL10.alSource3f(track.source, AL10.AL_POSITION, 0, 0, 0);
                AL10.alSourcef(track.source, AL10.AL_ROLLOFF_FACTOR, 0);
                AL10.alSourcef(track.source, AL10.AL_PITCH, playbackRate);
                track.durationMs = (double) (pcm.limit() / ch) / rate * 1000.0;
                int err = AL10.alGetError();
                if (err != AL10.AL_NO_ERROR) {
                    FnfMod.LOGGER.warn("OpenAL error {} while loading {}", err, path);
                }
                tracks.add(track);
                if (role == Role.INST) inst = track;
                Track loaded = track;
                track = null;
                return loaded;
            }
        } catch (Throwable error) {
            if (track != null) {
                tracks.remove(track);
                if (track.source != 0) {
                    AL10.alSourceStop(track.source);
                    AL10.alDeleteSources(track.source);
                }
                if (track.buffer != 0) AL10.alDeleteBuffers(track.buffer);
            }
            rethrowLoadFailure(error);
            throw new AssertionError("unreachable");
        } finally {
            if (pcm != null) org.lwjgl.system.libc.LibCStdlib.free(pcm);
            if (fileData != null) MemoryUtil.memFree(fileData);
        }
    }

    private static void rethrowLoadFailure(Throwable error) throws IOException {
        if (error instanceof IOException io) throw io;
        if (error instanceof RuntimeException runtime) throw runtime;
        if (error instanceof Error fatal) throw fatal;
        throw new IOException("Could not load song audio", error);
    }

    public void start() {
        if (disposed || started) return;
        applyVolumes();
        for (Track t : tracks) AL10.alSourcePlay(t.source);
        started = true;
        paused = false;
        lastRawMs = -1;
        smoothedMs = 0;
    }

    public void pause() {
        if (!started || paused || disposed) return;
        for (Track t : tracks) AL10.alSourcePause(t.source);
        paused = true;
    }

    public void resume() {
        if (!started || !paused || disposed) return;
        for (Track t : tracks) AL10.alSourcePlay(t.source);
        paused = false;
        lastRawMs = -1;
    }

    /** Rewind everything to the beginning without playing. */
    public void reset() {
        if (disposed) return;
        for (Track t : tracks) AL10.alSourceRewind(t.source);
        started = false;
        paused = false;
        lastRawMs = -1;
        smoothedMs = 0;
    }

    public void seekMs(double ms) {
        if (disposed) return;
        for (Track t : tracks) {
            double target = Math.min(ms, t.durationMs - 1);
            AL10.alSourcef(t.source, AL11.AL_SEC_OFFSET, (float) (Math.max(0, target) / 1000.0));
        }
        lastRawMs = -1;
        smoothedMs = ms;
    }

    public boolean isStarted() {
        return started;
    }

    public boolean isPaused() {
        return paused;
    }

    public boolean isFinished() {
        if (!started || disposed || inst == null) return false;
        return AL10.alGetSourcei(inst.source, AL10.AL_SOURCE_STATE) == AL10.AL_STOPPED;
    }

    public double durationMs() {
        return inst == null ? 0 : inst.durationMs;
    }

    /** Smoothed playback position of the instrumental, in ms. */
    public double positionMs() {
        if (disposed || inst == null || !started) return smoothedMs;
        if (paused) return smoothedMs;
        if (isFinished()) {
            smoothedMs = inst.durationMs;
            return smoothedMs;
        }
        double raw = AL10.alGetSourcef(inst.source, AL11.AL_SEC_OFFSET) * 1000.0;
        long now = System.nanoTime();
        if (raw != lastRawMs) {
            lastRawMs = raw;
            lastRawNano = now;
            // avoid jumping backwards from mixer jitter
            if (raw > smoothedMs || raw < smoothedMs - 60) smoothedMs = raw;
        } else {
            double extrapolated = raw + (now - lastRawNano) / 1_000_000.0 * playbackRate;
            if (extrapolated > smoothedMs) smoothedMs = extrapolated;
        }
        refMs = smoothedMs;
        refNano = now;
        return smoothedMs;
    }

    /**
     * Song position at a specific {@link System#nanoTime()} instant, using the
     * same extrapolation {@link #positionMs()} does. Judging a key press against
     * the position at the moment it was pressed — rather than at the next frame —
     * is what makes input timing independent of the frame rate. A past instant
     * (the press arrived a few ms ago) yields a slightly earlier position; a
     * future one yields a later position.
     */
    public double positionMsAt(long eventNano) {
        if (disposed || inst == null || !started || paused) return smoothedMs;
        long ref = refNano;
        if (ref == 0) return positionMs(); // no reference established yet
        double extrapolated = refMs + (eventNano - ref) / 1_000_000.0 * playbackRate;
        return Math.max(0, extrapolated);
    }

    /** Periodically pull drifting vocal tracks back in line with the inst. */
    public void resync() {
        if (!started || paused || disposed || inst == null) return;
        double instSec = AL10.alGetSourcef(inst.source, AL11.AL_SEC_OFFSET);
        for (Track t : tracks) {
            if (t == inst) continue;
            if (AL10.alGetSourcei(t.source, AL10.AL_SOURCE_STATE) != AL10.AL_PLAYING) continue;
            double sec = AL10.alGetSourcef(t.source, AL11.AL_SEC_OFFSET);
            if (Math.abs(sec - instSec) > 0.05 && instSec * 1000 < t.durationMs - 100) {
                AL10.alSourcef(t.source, AL11.AL_SEC_OFFSET, (float) instSec);
            }
        }
    }

    /** volume 0..1 for the player's vocal track (or the combined track if that's all we have). */
    public void setPlayerVoiceVolume(float volume) {
        setRoleVolume(Role.VOICES_PLAYER, volume);
        boolean hasSplit = tracks.stream().anyMatch(t -> t.role == Role.VOICES_PLAYER);
        if (!hasSplit) setRoleVolume(Role.VOICES, volume);
    }

    public void setOpponentVoiceVolume(float volume) {
        setRoleVolume(Role.VOICES_OPPONENT, volume);
    }

    /** Editor stem mixer. Combined vocals use {@link Role#VOICES}. */
    public void setVolume(Role role, float volume) {
        setRoleVolume(role, volume);
    }

    public boolean hasRole(Role role) {
        return tracks.stream().anyMatch(track -> track.role == role);
    }

    /** Changes playback speed and pitch for every synchronized stem. */
    public void setPlaybackRate(float rate) {
        playbackRate = Math.max(0.1f, Math.min(5.0f, rate));
        if (disposed) return;
        for (Track t : tracks) AL10.alSourcef(t.source, AL10.AL_PITCH, playbackRate);
        lastRawMs = -1;
    }

    public float playbackRate() {
        return playbackRate;
    }

    private void setRoleVolume(Role role, float volume) {
        for (Track t : tracks) {
            if (t.role == role) t.volume = volume;
        }
        applyVolumes();
    }

    /** Applies per-track volume x Minecraft's music/master volume. Call every frame or on change. */
    public void applyVolumes() {
        if (disposed) return;
        var options = Minecraft.getInstance().options;
        float global = options.getSoundSourceVolume(SoundSource.MASTER)
                * options.getSoundSourceVolume(SoundSource.RECORDS);
        for (Track t : tracks) {
            AL10.alSourcef(t.source, AL10.AL_GAIN, Math.max(0f, Math.min(1f, t.volume * global)));
        }
    }

    public void dispose() {
        if (disposed) return;
        disposed = true;
        for (Track t : tracks) {
            AL10.alSourceStop(t.source);
            AL10.alDeleteSources(t.source);
            AL10.alDeleteBuffers(t.buffer);
        }
        tracks.clear();
    }
}
