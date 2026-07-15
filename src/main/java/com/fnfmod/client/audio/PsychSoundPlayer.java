package com.fnfmod.client.audio;

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

/** Per-song OpenAL player for Psych Lua and chart-event sounds. */
public final class PsychSoundPlayer implements AutoCloseable {
    private static final int UNTAGGED_POOL = 16;

    private final Function<String, Path> resolver;
    private final Consumer<String> finishedCallback;
    private final Map<Path, Integer> buffers = new HashMap<>();
    private final Map<String, Integer> taggedSources = new LinkedHashMap<>();
    private final List<Integer> untaggedSources = new ArrayList<>();
    private int nextUntagged;

    public PsychSoundPlayer(Function<String, Path> resolver, Consumer<String> finishedCallback) {
        this.resolver = resolver;
        this.finishedCallback = finishedCallback;
    }

    public boolean precache(String name) {
        Path file = resolver.apply(name);
        return file != null && buffer(file) != 0;
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
            setVolume(source, volume);
            AL10.alSourcePlay(source);
            return true;
        } catch (Throwable error) {
            FnfMod.LOGGER.warn("Could not play Psych sound {}: {}", name, error.toString());
            return false;
        }
    }

    public void update() {
        List<String> finished = new ArrayList<>();
        var iterator = taggedSources.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            try {
                if (AL10.alGetSourcei(entry.getValue(), AL10.AL_SOURCE_STATE) == AL10.AL_STOPPED) {
                    int source = entry.getValue();
                    String tag = entry.getKey();
                    iterator.remove();
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

    public void setVolume(String tag, float volume) {
        Integer source = taggedSources.get(tag);
        if (source != null) setVolume(source, volume);
    }

    public float volume(String tag) {
        Integer source = taggedSources.get(tag);
        return source == null ? 0 : AL10.alGetSourcef(source, AL10.AL_GAIN);
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
            try { AL10.alDeleteSources(source); } catch (Throwable ignored) {}
        }
    }

    private static void setVolume(int source, float volume) {
        float master = Minecraft.getInstance().options.getSoundSourceVolume(SoundSource.MASTER);
        AL10.alSourcef(source, AL10.AL_GAIN, Math.max(0, Math.min(1, volume * master)));
    }

    private int buffer(Path rawFile) {
        Path file = rawFile.toAbsolutePath().normalize();
        Integer existing = buffers.get(file);
        if (existing != null) return existing;
        int loaded = decode(file);
        buffers.put(file, loaded);
        return loaded;
    }

    private static int decode(Path file) {
        ByteBuffer fileData = null;
        ShortBuffer pcm = null;
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            fileData = MemoryUtil.memAlloc((int) channel.size());
            while (fileData.hasRemaining() && channel.read(fileData) > 0) {}
            fileData.flip();
            try (MemoryStack stack = MemoryStack.stackPush()) {
                IntBuffer channels = stack.mallocInt(1);
                IntBuffer sampleRate = stack.mallocInt(1);
                pcm = STBVorbis.stb_vorbis_decode_memory(fileData, channels, sampleRate);
                if (pcm == null) return 0;
                int buffer = AL10.alGenBuffers();
                int format = channels.get(0) == 2 ? AL10.AL_FORMAT_STEREO16 : AL10.AL_FORMAT_MONO16;
                AL10.alBufferData(buffer, format, pcm, sampleRate.get(0));
                return buffer;
            }
        } catch (Throwable error) {
            FnfMod.LOGGER.warn("Could not decode Psych sound {}: {}", file, error.toString());
            return 0;
        } finally {
            if (pcm != null) org.lwjgl.system.libc.LibCStdlib.free(pcm);
            if (fileData != null) MemoryUtil.memFree(fileData);
        }
    }

    @Override
    public void close() {
        for (int source : taggedSources.values()) {
            try { AL10.alSourceStop(source); AL10.alDeleteSources(source); } catch (Throwable ignored) {}
        }
        for (int source : untaggedSources) {
            try { AL10.alSourceStop(source); AL10.alDeleteSources(source); } catch (Throwable ignored) {}
        }
        for (int buffer : buffers.values()) {
            if (buffer != 0) try { AL10.alDeleteBuffers(buffer); } catch (Throwable ignored) {}
        }
        taggedSources.clear(); untaggedSources.clear(); buffers.clear();
    }
}
