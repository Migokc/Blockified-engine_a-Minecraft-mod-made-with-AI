package com.fnfmod.client.audio;

import com.fnfmod.FnfMod;
import com.fnfmod.client.ClientOptions;
import com.fnfmod.song.SongLibrary;
import net.minecraft.client.Minecraft;
import net.minecraft.sounds.SoundSource;
import org.lwjgl.openal.AL10;
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
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.locks.LockSupport;
import java.util.stream.Stream;

/**
 * Plays the note hitsound through a small pool of OpenAL sources.
 *
 * <p>Every OpenAL call runs on one dedicated thread. Callers — the game thread on
 * a normal hit, or the high-rate input backend on a sub-frame hit — only enqueue
 * a request, so the sound can be fired from any thread without touching OpenAL
 * from more than one at a time. That is what lets a precisely-detected press
 * play its hitsound without waiting for the next frame.
 */
public final class HitsoundPlayer {

    private static final int POOL_SIZE = 6;

    /** A queued play, or a reload marker when {@code file} is null. */
    private record Request(Path file, float volume, boolean reload) {}

    private static final ConcurrentLinkedQueue<Request> QUEUE = new ConcurrentLinkedQueue<>();
    private static volatile Thread thread;

    // OpenAL state — touched only by the audio thread, so it needs no locking.
    private static int buffer;
    private static Path loadedFile;
    private static int[] sources;
    private static int nextSource;

    private HitsoundPlayer() {}

    /** Fire the configured hitsound (no-op when off or missing). Any thread. */
    public static void play() {
        String name = ClientOptions.get().hitsound;
        if (name == null || name.isEmpty()) return;
        play(SongLibrary.hitsoundsDir().resolve(name), (float) ClientOptions.get().hitsoundVolume);
    }

    /** Plays a note-specific OGG with the supplied per-note gain. Any thread. */
    public static void play(Path file, float volume) {
        if (file == null || volume <= 0) return;
        enqueue(new Request(file.toAbsolutePath().normalize(), volume, false));
    }

    /** Drops the current buffer so the selected file is decoded again on next hit. */
    public static void reload() {
        enqueue(new Request(null, 0, true));
    }

    /** .ogg files directly inside the hitsounds folder. Pure file listing, any thread. */
    public static List<String> list() {
        List<String> out = new ArrayList<>();
        try (Stream<Path> files = Files.list(SongLibrary.hitsoundsDir())) {
            files.filter(Files::isRegularFile)
                    .map(f -> f.getFileName().toString())
                    .filter(n -> n.toLowerCase(Locale.ROOT).endsWith(".ogg"))
                    .sorted()
                    .forEach(out::add);
        } catch (Exception ignored) {}
        return out;
    }

    // ------------------------------------------------------------- audio thread

    private static void enqueue(Request request) {
        QUEUE.add(request);
        LockSupport.unpark(ensureThread());
    }

    /** Lazily starts the single, session-long audio thread. */
    private static synchronized Thread ensureThread() {
        Thread current = thread;
        if (current != null) return current;
        Thread started = new Thread(HitsoundPlayer::loop, "fnfmod-hitsound");
        started.setDaemon(true);
        started.start();
        thread = started;
        return started;
    }

    private static void loop() {
        while (true) {
            Request request;
            while ((request = QUEUE.poll()) != null) {
                try {
                    if (request.reload) doReload();
                    else doPlay(request.file, request.volume);
                } catch (Throwable ignored) {
                    // A sound problem must never take the thread down.
                }
            }
            LockSupport.park();
        }
    }

    private static void doPlay(Path file, float volume) {
        if (!Files.isRegularFile(file)) return;
        if (!file.equals(loadedFile)) doReload(file);
        if (buffer == 0) return;
        if (sources == null) {
            sources = new int[POOL_SIZE];
            for (int i = 0; i < POOL_SIZE; i++) sources[i] = AL10.alGenSources();
        }
        int src = sources[nextSource++ % POOL_SIZE];
        AL10.alSourceStop(src);
        AL10.alSourcei(src, AL10.AL_BUFFER, buffer);
        AL10.alSourcei(src, AL10.AL_SOURCE_RELATIVE, AL10.AL_TRUE);
        float master = Minecraft.getInstance().options.getSoundSourceVolume(SoundSource.MASTER);
        AL10.alSourcef(src, AL10.AL_GAIN, Math.max(0f, Math.min(1f, volume * master)));
        AL10.alSourcePlay(src);
    }

    private static void doReload() {
        doReload(null);
    }

    /** Detaches sources, deletes the old buffer, and loads {@code file} if given. */
    private static void doReload(Path file) {
        if (sources != null) {
            for (int src : sources) {
                AL10.alSourceStop(src);
                AL10.alSourcei(src, AL10.AL_BUFFER, 0);
            }
        }
        if (buffer != 0) {
            AL10.alDeleteBuffers(buffer);
            buffer = 0;
        }
        loadedFile = file;
        if (file == null || !Files.isRegularFile(file)) return;

        ByteBuffer fileData = null;
        ShortBuffer pcm = null;
        try (FileChannel fc = FileChannel.open(file, StandardOpenOption.READ)) {
            fileData = MemoryUtil.memAlloc((int) fc.size());
            while (fileData.hasRemaining()) {
                if (fc.read(fileData) <= 0) break;
            }
            fileData.flip();
            try (MemoryStack stack = MemoryStack.stackPush()) {
                IntBuffer channels = stack.mallocInt(1);
                IntBuffer sampleRate = stack.mallocInt(1);
                pcm = STBVorbis.stb_vorbis_decode_memory(fileData, channels, sampleRate);
                if (pcm == null) return;
                int format = channels.get(0) == 2 ? AL10.AL_FORMAT_STEREO16 : AL10.AL_FORMAT_MONO16;
                buffer = AL10.alGenBuffers();
                AL10.alBufferData(buffer, format, pcm, sampleRate.get(0));
            }
        } catch (Exception e) {
            FnfMod.LOGGER.warn("Failed to load hitsound {}: {}", file, e.toString());
        } finally {
            if (pcm != null) org.lwjgl.system.libc.LibCStdlib.free(pcm);
            if (fileData != null) MemoryUtil.memFree(fileData);
        }
    }
}
