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
import java.util.stream.Stream;

/**
 * Plays the note hitsound (config/fnfmod/hitsounds/&lt;name&gt;.ogg) through a
 * small pool of OpenAL sources so quick presses can overlap.
 */
public final class HitsoundPlayer {

    private static final int POOL_SIZE = 6;

    private static int buffer;
    private static String loadedName = "";
    private static int[] sources;
    private static int nextSource;

    private HitsoundPlayer() {}

    /** .ogg files directly inside the hitsounds folder. */
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

    /** Fire the configured hitsound (no-op when off or missing). Render thread only. */
    public static void play() {
        String name = ClientOptions.get().hitsound;
        if (name == null || name.isEmpty()) return;
        try {
            if (!name.equals(loadedName)) {
                loadBuffer(name);
            }
            if (buffer == 0) return;
            if (sources == null) {
                sources = new int[POOL_SIZE];
                for (int i = 0; i < POOL_SIZE; i++) sources[i] = AL10.alGenSources();
            }
            int src = sources[nextSource++ % POOL_SIZE];
            AL10.alSourceStop(src);
            AL10.alSourcei(src, AL10.AL_BUFFER, buffer);
            AL10.alSourcei(src, AL10.AL_SOURCE_RELATIVE, AL10.AL_TRUE);
            var options = Minecraft.getInstance().options;
            float gain = (float) (ClientOptions.get().hitsoundVolume
                    * options.getSoundSourceVolume(SoundSource.MASTER));
            AL10.alSourcef(src, AL10.AL_GAIN, Math.max(0f, Math.min(1f, gain)));
            AL10.alSourcePlay(src);
        } catch (Throwable t) {
            // sound problems must never break gameplay
        }
    }

    private static void loadBuffer(String name) {
        // stop and detach everything before deleting the old buffer
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
        loadedName = name;

        Path file = SongLibrary.hitsoundsDir().resolve(name);
        if (!Files.isRegularFile(file)) return;

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
            FnfMod.LOGGER.warn("Failed to load hitsound {}: {}", name, e.toString());
        } finally {
            if (pcm != null) org.lwjgl.system.libc.LibCStdlib.free(pcm);
            if (fileData != null) MemoryUtil.memFree(fileData);
        }
    }
}
