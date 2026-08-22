package com.fnfmod.client.audio;

import com.fnfmod.gameplay.GameplayClock;
import com.fnfmod.FnfMod;
import net.minecraft.client.Minecraft;
import net.minecraft.sounds.SoundSource;
import org.lwjgl.openal.AL10;
import org.lwjgl.openal.AL11;
import org.lwjgl.stb.STBVorbis;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.Set;
import java.util.WeakHashMap;

/** Per-song OpenAL player for Psych Lua and chart-event sounds. */
public final class PsychSoundPlayer implements AutoCloseable {
    private static final int UNTAGGED_POOL = 16;
    private static final Set<PsychSoundPlayer> LIVE_PLAYERS =
            java.util.Collections.newSetFromMap(new WeakHashMap<>());

    private final Function<String, Path> resolver;
    private final Consumer<String> finishedCallback;
    private final Map<Path, Integer> buffers = new HashMap<>();
    // OGG decoded to PCM on a worker thread, awaiting a cheap OpenAL upload on the
    // render thread. Lets menu sounds preload without freezing on the decode.
    private final Map<Path, Pcm> pendingPcm = new HashMap<>();
    private final Map<String, Integer> taggedSources = new LinkedHashMap<>();
    private final List<Integer> untaggedSources = new ArrayList<>();
    /** Requested (pre-master) gain, retained so live settings changes affect playing audio. */
    private final Map<Integer, Float> logicalVolumes = new HashMap<>();
    private final Map<String, Fade> fades = new LinkedHashMap<>();
    private int nextUntagged;

    /** Decoded PCM (native memory, must be freed) with no OpenAL objects yet. */
    private record Pcm(ShortBuffer pcm, int channels, int sampleRate) {}

    /**
     * A running volume ramp on a tagged sound. {@code stopWhenDone} reproduces
     * Psych's soundFadeOut, which stops the sound once it reaches silence.
     */
    private record Fade(float from, float to, long start, long durationMs, boolean stopWhenDone) {}

    public PsychSoundPlayer(Function<String, Path> resolver, Consumer<String> finishedCallback) {
        this.resolver = resolver;
        this.finishedCallback = finishedCallback;
        synchronized (LIVE_PLAYERS) { LIVE_PLAYERS.add(this); }
    }

    /** Immediately applies a changed Minecraft master volume to all active Lua audio. */
    public static void refreshMasterVolumes() {
        List<PsychSoundPlayer> players;
        synchronized (LIVE_PLAYERS) { players = List.copyOf(LIVE_PLAYERS); }
        for (PsychSoundPlayer player : players) player.applyMasterVolume();
    }

    private void applyMasterVolume() {
        for (var volume : new ArrayList<>(logicalVolumes.entrySet())) {
            try { applyVolume(volume.getKey(), volume.getValue()); } catch (Throwable ignored) {}
        }
    }

    public boolean precache(String name) {
        Path file = resolver.apply(name);
        return file != null && buffer(file) != 0;
    }

    /**
     * Background-safe: decodes the OGG to PCM ahead of time with no OpenAL work, so
     * a later {@link #precache}/{@link #play} only does the cheap buffer upload. Safe
     * to call for an already-loaded sound.
     */
    public void prefetch(String name) {
        Path file = resolver.apply(name);
        if (file == null) return;
        Path key = file.toAbsolutePath().normalize();
        synchronized (this) {
            if (buffers.containsKey(key) || pendingPcm.containsKey(key)) return;
        }
        Pcm data = decodePcm(key);
        if (data == null) return;
        synchronized (this) {
            if (buffers.containsKey(key) || pendingPcm.containsKey(key)) {
                org.lwjgl.system.libc.LibCStdlib.free(data.pcm());
            } else {
                pendingPcm.put(key, data);
            }
        }
    }

    public boolean play(String name, float volume, String tag, boolean loop) {
        Path file = resolver.apply(name);
        int buffer = file == null ? 0 : buffer(file);
        if (buffer == 0) return false;
        try {
            int source = source(tag);
            AL10.alSourceStop(source);
            AL10.alSourcei(source, AL10.AL_BUFFER, buffer);
            AL10.alSourcei(source, AL10.AL_SOURCE_RELATIVE, AL10.AL_TRUE);
            AL10.alSourcei(source, AL10.AL_LOOPING, loop ? AL10.AL_TRUE : AL10.AL_FALSE);
            setSourceVolume(source, volume);
            AL10.alSourcePlay(source);
            return true;
        } catch (Throwable error) {
            FnfMod.LOGGER.warn("Could not play Psych sound {}: {}", name, error.toString());
            return false;
        }
    }

    /** Starts a Psych volume ramp. A zero duration applies the target immediately. */
    public void fade(String tag, float durationSeconds, float from, float to, boolean stopWhenDone) {
        if (tag == null || !taggedSources.containsKey(tag)) return;
        long durationMs = Math.max(0, (long) (durationSeconds * 1000));
        if (durationMs == 0) {
            fades.remove(tag);
            setVolume(tag, to);
            if (stopWhenDone && to <= 0) stop(tag);
            return;
        }
        setVolume(tag, from);
        fades.put(tag, new Fade(from, to, GameplayClock.now(), durationMs, stopWhenDone));
    }

    /** Psych's soundFadeCancel: leaves the sound at whatever volume it reached. */
    public void cancelFade(String tag) {
        fades.remove(tag);
    }

    private void updateFades() {
        long now = GameplayClock.now();
        for (var entry : new ArrayList<>(fades.entrySet())) {
            String tag = entry.getKey();
            Fade fade = entry.getValue();
            if (!taggedSources.containsKey(tag)) {
                fades.remove(tag);
                continue;
            }
            double progress = Math.min(1, (now - fade.start) / (double) fade.durationMs);
            setVolume(tag, (float) (fade.from + (fade.to - fade.from) * progress));
            if (progress < 1) continue;
            fades.remove(tag);
            if (fade.stopWhenDone && fade.to <= 0) stop(tag);
        }
    }

    public void update() {
        updateFades();
        // The Minecraft master slider may change while a shared machine-menu track
        // keeps playing across Lua screens. Re-apply it without losing script gain.
        applyMasterVolume();
        List<String> finished = new ArrayList<>();
        var iterator = taggedSources.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            try {
                if (AL10.alGetSourcei(entry.getValue(), AL10.AL_SOURCE_STATE) == AL10.AL_STOPPED) {
                    int source = entry.getValue();
                    String tag = entry.getKey();
                    iterator.remove();
                    logicalVolumes.remove(source);
                    AL10.alDeleteSources(source);
                    finished.add(tag);
                }
            } catch (Throwable ignored) {}
        }
        for (String tag : finished) finishedCallback.accept(tag);
    }

    public boolean exists(String tag) { return tag != null && taggedSources.containsKey(tag); }
    public void stop(String tag) { sourceAction(tag, AL10::alSourceStop, true); }
    public void pause(String tag) { sourceAction(tag, AL10::alSourcePause, false); }
    public void resume(String tag) { sourceAction(tag, AL10::alSourcePlay, false); }

    public void pauseAll() {
        for (int source : new ArrayList<>(taggedSources.values())) {
            try { AL10.alSourcePause(source); } catch (Throwable ignored) {}
        }
        // Fire-and-forget effects have no meaningful state at a seek target.
        for (int source : new ArrayList<>(untaggedSources)) {
            try { AL10.alSourceStop(source); } catch (Throwable ignored) {}
        }
    }

    public void resumeAll() {
        for (int source : new ArrayList<>(taggedSources.values())) {
            try {
                if (AL10.alGetSourcei(source, AL10.AL_SOURCE_STATE) == AL10.AL_PAUSED) {
                    AL10.alSourcePlay(source);
                }
            } catch (Throwable ignored) {}
        }
    }

    public void setVolume(String tag, float volume) {
        Integer source = taggedSources.get(tag);
        if (source != null) setSourceVolume(source, volume);
    }

    public float volume(String tag) {
        Integer source = taggedSources.get(tag);
        return source == null ? 0 : logicalVolumes.getOrDefault(source, 1f);
    }

    public void setPitch(String tag, float pitch) {
        Integer source = taggedSources.get(tag);
        if (source != null) AL10.alSourcef(source, AL10.AL_PITCH, Math.max(0.01f, pitch));
    }

    public float pitch(String tag) {
        Integer source = taggedSources.get(tag);
        return source == null ? 1 : AL10.alGetSourcef(source, AL10.AL_PITCH);
    }

    public void setTimeMs(String tag, float milliseconds) {
        Integer source = taggedSources.get(tag);
        if (source != null) AL10.alSourcef(source, AL11.AL_SEC_OFFSET, Math.max(0, milliseconds) / 1000f);
    }

    public float timeMs(String tag) {
        Integer source = taggedSources.get(tag);
        return source == null ? 0 : AL10.alGetSourcef(source, AL11.AL_SEC_OFFSET) * 1000f;
    }

    private int source(String tag) {
        if (tag != null && !tag.isBlank()) {
            Integer old = taggedSources.remove(tag);
            if (old != null) {
                AL10.alSourceStop(old);
                logicalVolumes.remove(old);
                AL10.alDeleteSources(old);
            }
            int source = AL10.alGenSources();
            taggedSources.put(tag, source);
            return source;
        }
        if (untaggedSources.size() < UNTAGGED_POOL) {
            int source = AL10.alGenSources();
            untaggedSources.add(source);
            return source;
        }
        return untaggedSources.get(nextUntagged++ % untaggedSources.size());
    }

    private void sourceAction(String tag, Consumer<Integer> action, boolean remove) {
        Integer source = taggedSources.get(tag);
        if (source == null) return;
        try { action.accept(source); } catch (Throwable ignored) {}
        if (remove) {
            taggedSources.remove(tag);
            logicalVolumes.remove(source);
            try { AL10.alDeleteSources(source); } catch (Throwable ignored) {}
        }
    }

    private void setSourceVolume(int source, float volume) {
        float safe = Math.max(0, Math.min(1, volume));
        logicalVolumes.put(source, safe);
        applyVolume(source, safe);
    }

    private static void applyVolume(int source, float volume) {
        float master = Minecraft.getInstance().options.getSoundSourceVolume(SoundSource.MASTER);
        AL10.alSourcef(source, AL10.AL_GAIN, Math.max(0, Math.min(1, volume * master)));
    }

    private synchronized int buffer(Path rawFile) {
        Path file = rawFile.toAbsolutePath().normalize();
        Integer existing = buffers.get(file);
        if (existing != null) return existing;
        Pcm pending = pendingPcm.remove(file);
        Pcm data = pending != null ? pending : decodePcm(file);
        int loaded = data == null ? 0 : upload(data);
        buffers.put(file, loaded);
        return loaded;
    }

    /** Background-safe: reads and decodes the OGG to PCM with no OpenAL work. */
    private static Pcm decodePcm(Path file) {
        ByteBuffer fileData = null;
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            fileData = MemoryUtil.memAlloc((int) channel.size());
            while (fileData.hasRemaining() && channel.read(fileData) > 0) {}
            fileData.flip();
            try (MemoryStack stack = MemoryStack.stackPush()) {
                IntBuffer channels = stack.mallocInt(1);
                IntBuffer sampleRate = stack.mallocInt(1);
                ShortBuffer pcm = STBVorbis.stb_vorbis_decode_memory(fileData, channels, sampleRate);
                if (pcm == null) return null;
                return new Pcm(pcm, channels.get(0), sampleRate.get(0));
            }
        } catch (Throwable error) {
            FnfMod.LOGGER.warn("Could not decode Psych sound {}: {}", file, error.toString());
            return null;
        } finally {
            if (fileData != null) MemoryUtil.memFree(fileData);
        }
    }

    /** Uploads decoded PCM to an OpenAL buffer and frees the PCM. Render-thread only. */
    private static int upload(Pcm data) {
        try {
            int buffer = AL10.alGenBuffers();
            int format = data.channels() == 2 ? AL10.AL_FORMAT_STEREO16 : AL10.AL_FORMAT_MONO16;
            AL10.alBufferData(buffer, format, data.pcm(), data.sampleRate());
            return buffer;
        } finally {
            org.lwjgl.system.libc.LibCStdlib.free(data.pcm());
        }
    }

    @Override
    public synchronized void close() {
        synchronized (LIVE_PLAYERS) { LIVE_PLAYERS.remove(this); }
        for (int source : taggedSources.values()) {
            try { AL10.alSourceStop(source); AL10.alDeleteSources(source); } catch (Throwable ignored) {}
        }
        for (int source : untaggedSources) {
            try { AL10.alSourceStop(source); AL10.alDeleteSources(source); } catch (Throwable ignored) {}
        }
        for (int buffer : buffers.values()) {
            if (buffer != 0) try { AL10.alDeleteBuffers(buffer); } catch (Throwable ignored) {}
        }
        for (Pcm data : pendingPcm.values()) {
            try { org.lwjgl.system.libc.LibCStdlib.free(data.pcm()); } catch (Throwable ignored) {}
        }
        taggedSources.clear(); untaggedSources.clear(); logicalVolumes.clear();
        buffers.clear(); pendingPcm.clear(); fades.clear();
    }
}
