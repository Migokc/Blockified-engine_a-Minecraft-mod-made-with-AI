package com.fnfmod.client.lua;

import com.fnfmod.FnfMod;
import com.mojang.blaze3d.font.GlyphProvider;
import com.mojang.blaze3d.font.TrueTypeGlyphProvider;
import com.mojang.blaze3d.platform.TextureUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.font.FontOption;
import net.minecraft.client.gui.font.FontSet;
import net.minecraft.client.gui.font.providers.FreeTypeUtil;
import net.minecraft.resources.ResourceLocation;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.freetype.FT_Face;
import org.lwjgl.util.freetype.FreeType;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.LinkedHashSet;
import java.util.concurrent.atomic.AtomicInteger;

/** Loads loose TTF/OTF files into Minecraft's native FreeType glyph renderer. */
final class LuaFontLoader implements AutoCloseable {
    private static final AtomicInteger NEXT_ID = new AtomicInteger();

    private record Loaded(Font font, FontSet set, TrueTypeGlyphProvider provider, ResourceLocation textureBase) {}

    private final List<Path> assetRoots;
    private final Path globalFonts;
    private final boolean allowSongFonts;
    /**
     * Atlas resolution is baked in when a face is rasterized, so a file loaded at two
     * qualities is two entries rather than one shared font.
     */
    private record Key(Path file, int oversample) {}

    /** Minecraft rasterizes around 11px; Lua scales that to arbitrary FNF sizes. */
    static final int DEFAULT_OVERSAMPLE = 8;
    private static final int MIN_OVERSAMPLE = 1;
    /** Past this the atlas cost grows fast for no visible gain. */
    private static final int MAX_OVERSAMPLE = 16;

    private final Map<Key, Loaded> loaded = new LinkedHashMap<>();
    private final Set<Key> failed = new LinkedHashSet<>();
    private final Set<String> missing = new LinkedHashSet<>();

    LuaFontLoader(List<Path> assetRoots, Path globalFonts, boolean allowSongFonts) {
        this.assetRoots = assetRoots == null ? List.of() : assetRoots.stream()
                .map(LuaFontLoader::normalize).filter(java.util.Objects::nonNull).distinct().toList();
        this.globalFonts = normalize(globalFonts);
        this.allowSongFonts = allowSongFonts;
    }

    Font get(String name) {
        return get(name, DEFAULT_OVERSAMPLE);
    }

    /** Clamps a requested quality into the range the atlas can serve. */
    static int clampOversample(double requested) {
        if (!Double.isFinite(requested)) return DEFAULT_OVERSAMPLE;
        return (int) Math.max(MIN_OVERSAMPLE, Math.min(MAX_OVERSAMPLE, Math.round(requested)));
    }

    Font get(String name, double oversample) {
        Path file = resolve(name);
        if (file == null) {
            if (name != null && missing.add(name.toLowerCase(Locale.ROOT))) {
                FnfMod.LOGGER.warn("Lua font not found: {}", name);
            }
            return null;
        }
        Key key = new Key(file, clampOversample(oversample));
        Loaded cached = loaded.get(key);
        if (cached != null) return cached.font;
        if (failed.contains(key)) return null;
        Loaded created = load(file, key.oversample());
        if (created == null) {
            failed.add(key);
            return null;
        }
        loaded.put(key, created);
        return created.font;
    }

    private Loaded load(Path file, int oversample) {
        FT_Face face = null;
        ByteBuffer memory = null;
        TrueTypeGlyphProvider provider = null;
        ResourceLocation textureBase = null;
        try (InputStream input = Files.newInputStream(file)) {
            memory = TextureUtil.readResource(input);
            memory.flip();
            synchronized (FreeTypeUtil.LIBRARY_LOCK) {
                try (MemoryStack stack = MemoryStack.stackPush()) {
                    PointerBuffer pointer = stack.mallocPointer(1);
                    FreeTypeUtil.assertError(FreeType.FT_New_Memory_Face(
                            FreeTypeUtil.getLibrary(), memory, 0L, pointer), "Loading Lua font");
                    face = FT_Face.create(pointer.get());
                }
                FreeTypeUtil.assertError(FreeType.FT_Select_Charmap(face, FreeType.FT_ENCODING_UNICODE),
                        "Finding Lua font Unicode charmap");
            }

            // Minecraft normally rasterizes around 11px. Lua then scales that image to
            // arbitrary FNF sizes, so the atlas is oversampled to avoid blocky edges.
            provider = new TrueTypeGlyphProvider(memory, face,
                    11f, oversample, 0f, 0f, "");
            memory = null;
            face = null;
            textureBase = FnfMod.id("lua_font/" + NEXT_ID.incrementAndGet());
            FontSet set = new FontSet(Minecraft.getInstance().getTextureManager(), textureBase);
            set.reload(List.of(new GlyphProvider.Conditional(provider, FontOption.Filter.ALWAYS_PASS)), Set.of());
            Font font = new Font(ignored -> set, false);
            FnfMod.LOGGER.info("Loaded Lua font {} at {}x quality", file, oversample);
            return new Loaded(font, set, provider, textureBase);
        } catch (Throwable error) {
            if (textureBase != null) releaseFontTextures(textureBase);
            if (provider != null) {
                provider.close();
            } else {
                if (face != null) {
                    synchronized (FreeTypeUtil.LIBRARY_LOCK) {
                        FreeType.FT_Done_Face(face);
                    }
                }
                MemoryUtil.memFree(memory);
            }
            FnfMod.LOGGER.warn("Could not load Lua font {}: {}", file, error.toString());
            return null;
        }
    }

    /** FontSet registers atlas pages as textureBase/0, textureBase/1, ... . */
    private static void releaseFontTextures(ResourceLocation textureBase) {
        var textureManager = Minecraft.getInstance().getTextureManager();
        for (int page = 0; ; page++) {
            ResourceLocation id = textureBase.withSuffix("/" + page);
            if (textureManager.getTexture(id, null) == null) break;
            textureManager.release(id);
        }
    }

    private Path resolve(String requested) {
        if (requested == null || requested.isBlank()) return null;
        String name = requested.trim().replace('\\', '/');
        List<Path> roots = new java.util.ArrayList<>(assetRoots);
        if (globalFonts != null) roots.add(globalFonts);
        for (Path root : roots) {
            if (root == null) continue;
            if (!root.equals(globalFonts) && !allowSongFonts) continue;
            Path[] candidates = root.equals(globalFonts)
                    ? new Path[]{root.resolve(name)}
                    : new Path[]{root.resolve("fonts").resolve(name), root.resolve(name)};
            for (Path candidate : candidates) {
                Path file = candidate.normalize();
                if (!file.startsWith(root) || !Files.isRegularFile(file)) continue;
                String lower = file.getFileName().toString().toLowerCase(Locale.ROOT);
                if (lower.endsWith(".ttf") || lower.endsWith(".otf")) return file;
            }
        }
        return null;
    }

    private static Path normalize(Path path) {
        return path == null ? null : path.toAbsolutePath().normalize();
    }

    @Override
    public void close() {
        for (Loaded font : loaded.values()) {
            releaseFontTextures(font.textureBase);
            font.provider.close();
        }
        loaded.clear();
        failed.clear();
        missing.clear();
    }
}
