package com.fnfmod.client.render;

import com.fnfmod.FnfMod;
import com.fnfmod.song.SongLibrary;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

import java.nio.file.Path;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * Renders notes/receptors/holds. If config/fnfmod/skins/default/NOTE_assets.png
 * + NOTE_assets.xml exist (straight out of an FNF mod), those are used;
 * otherwise a built-in procedurally generated arrow is drawn.
 */
public final class NoteStyle {
    /** Neutral hold-cover placement, derived from the previously required skin.json workaround. */
    private static final float HOLD_COVER_BASE_SCALE = 1.7f;
    private static final float HOLD_COVER_BASE_Y = 35f;

    /** lane colors: left purple, down blue, up green, right red */
    public static final int[] LANE_COLORS = {0xFFC24B99, 0xFF00FFFF, 0xFF12FA05, 0xFFF9393F};
    private static final float[] LANE_ROTATION = {-90f, 180f, 0f, 90f}; // arrow texture points up

    private static ResourceLocation arrowTexture;
    private static ResourceLocation arrowOutlineTexture;
    /** atlas holding the scrolling notes (classic NOTE_assets or V-Slice notes.png) */
    private static SparrowAtlas noteAtlas;
    /** atlas holding the receptors (classic NOTE_assets or V-Slice noteStrumline.png) */
    private static SparrowAtlas strumAtlas;
    /** Separate Psych pixel-UI sustain sheet; retained so its GL texture is released. */
    private static SparrowAtlas pixelEndsAtlas;
    private static String[] noteAnims;      // per lane
    private static String[] receptorAnims;  // per lane (static)
    private static String[] pressAnims;     // per lane
    private static String[] confirmAnims;   // per lane
    private static String[] holdAnims;      // per lane
    private static String[] holdEndAnims;   // per lane
    /** hit splash atlas (noteSplashes.png/xml) and its per-lane animation variants */
    private static SparrowAtlas splashAtlas;
    /** Psych generic sustain splash: start once, hold loop, end once. */
    private static SparrowAtlas holdSplashAtlas;
    private static String holdSplashStartAnim, holdSplashLoopAnim, holdSplashEndAnim;
    @SuppressWarnings("unchecked")
    private static java.util.List<String>[] splashAnims = new java.util.List[4];

    /** a fixed sub-rectangle of some texture, used for sustain trail pieces/ends */
    private record HoldSprite(ResourceLocation tex, int texW, int texH, int x, int y, int w, int h) {}

    private static final HoldSprite[] holdPieces = new HoldSprite[4];
    private static final HoldSprite[] holdEnds = new HoldSprite[4];
    /** V-Slice hold cover effect atlases (holdCoverPurple.png/xml etc.) */
    private static final SparrowAtlas[] coverAtlases = new SparrowAtlas[4];
    private static final String[] coverAnims = new String[4];
    private static final String[] coverEndAnims = new String[4];
    /** Source-pixel size of a plain note; every authored skin part shares this scale. */
    private static float noteRefPx = 155;

    /**
     * Psych-style RGB template support: sheets authored with pure red (base),
     * green (highlight) and blue (outline) get remapped into one recolored
     * texture per lane using the user's note colors.
     */
    private static final class RGBSet {
        NativeImage src;
        boolean template;
        final com.mojang.blaze3d.platform.NativeImage[] pixels = new NativeImage[4];
        final net.minecraft.client.renderer.texture.DynamicTexture[] dyn =
                new net.minecraft.client.renderer.texture.DynamicTexture[4];
        final ResourceLocation[] id = new ResourceLocation[4];
    }

    private static RGBSet noteRGB, strumRGB, splashRGB, holdRGB, holdSplashRGB;
    /** Hurt covers use Psych's fixed palette: template red -> red, green/blue -> black. */
    private static RGBSet hurtHoldSplashRGB;
    private static final RGBSet[] hurtCoverRGB = new RGBSet[4];
    private static boolean holdsFromStrumAtlas;
    /** the note skin folder ships its own noteSplashes files */
    private static boolean skinOwnSplash;
    /** the note skin folder ships its own per-lane holdCover files */
    private static boolean skinOwnHoldCover;
    private static NoteSkinConfig skinConfig = NoteSkinConfig.DEFAULT;
    private static java.nio.file.Path skinConfigFile;
    private static SkinFiles activeSkinFiles;
    // The current song's arrowSkin (chart noteTexture), fed in so the "default"
    // note-skin setting renders it through this pipeline (RGB, proper sustains)
    // instead of the raw per-note path. songSkinActive is true once it is loaded.
    private static java.nio.file.Path songSkinPng, songSkinXml, songSkinJson;
    private static java.nio.file.Path songHoldSplashPng, songHoldSplashXml;
    private static boolean songHoldSplashExplicit;
    private static boolean songSkinActive;
    /** Legacy compatibility state retained for callers loading old Psych charts. */
    private static boolean songRgbAllowed = true;
    /** external alpha multiplier for everything drawn (middlescroll opponent fade) */
    private static float extAlpha = 1f;
    private static float drawAlpha = 1f;
    private static boolean loaded;

    /** External fade (e.g. middlescroll opponent); combines with per-skin alphas. */
    public static void setDrawAlpha(float alpha) {
        extAlpha = alpha;
    }

    /** Applies a per-element alpha for the next draw (skin alpha x external fade x missed). */
    private static void applyAlpha(float skinAlpha) {
        drawAlpha = skinAlpha * extAlpha * missedFactor;
        SparrowAtlas.globalAlpha = drawAlpha;
    }

    /** Applies lane/script alpha and missed-note tint before an external Psych note atlas draw. */
    public static void prepareCustomNoteDraw() {
        applyAlpha(1f);
    }

    /** Applies active skin splash alpha to an atlas stored outside the note-skin folder. */
    public static void prepareCustomSplashDraw(float skinAlpha) {
        applyAlpha(skinAlpha);
    }

    /** Gray + 30% translucent styling for a totally-missed long note. */
    public static void setMissed(boolean m) {
        if (m) {
            SparrowAtlas.tintR = 0.45f;
            SparrowAtlas.tintG = 0.45f;
            SparrowAtlas.tintB = 0.5f;
            missedTint = true;
            missedFactor = 0.3f;
        } else {
            SparrowAtlas.tintR = SparrowAtlas.tintG = SparrowAtlas.tintB = 1f;
            missedTint = false;
            missedFactor = 1f;
        }
    }

    private static boolean missedTint = false;
    private static float missedFactor = 1f;

    private NoteStyle() {}

    public static void reload() {
        closeLoadedResources();
        loaded = false;
        load();
    }

    private static void closeLoadedResources() {
        Set<RGBSet> rgbSets = Collections.newSetFromMap(new IdentityHashMap<>());
        Collections.addAll(rgbSets, noteRGB, strumRGB, splashRGB, holdRGB, holdSplashRGB,
                hurtHoldSplashRGB);
        Collections.addAll(rgbSets, hurtCoverRGB);
        rgbSets.remove(null);
        var textureManager = Minecraft.getInstance().getTextureManager();
        for (RGBSet set : rgbSets) {
            for (int lane = 0; lane < 4; lane++) {
                if (set.id[lane] != null) textureManager.release(set.id[lane]);
                else if (set.dyn[lane] != null) set.dyn[lane].close();
                set.id[lane] = null;
                set.dyn[lane] = null;
                set.pixels[lane] = null;
            }
        }
        noteRGB = strumRGB = splashRGB = holdRGB = holdSplashRGB = hurtHoldSplashRGB = null;
        skinOwnSplash = false;
        skinOwnHoldCover = false;
        skinConfig = NoteSkinConfig.DEFAULT;
        skinConfigFile = null;
        activeSkinFiles = null;

        if (holdSheetTextureId != null) textureManager.release(holdSheetTextureId);
        holdSheetTextureId = null;
        holdSheetImage = null;

        Set<SparrowAtlas> atlases = Collections.newSetFromMap(new IdentityHashMap<>());
        Collections.addAll(atlases, noteAtlas, strumAtlas, splashAtlas, pixelEndsAtlas, holdSplashAtlas);
        Collections.addAll(atlases, coverAtlases);
        atlases.remove(null);
        for (SparrowAtlas atlas : atlases) atlas.close();
        noteAtlas = strumAtlas = splashAtlas = pixelEndsAtlas = holdSplashAtlas = null;
        holdSplashStartAnim = holdSplashLoopAnim = holdSplashEndAnim = null;

        for (int i = 0; i < 4; i++) {
            holdPieces[i] = null;
            holdEnds[i] = null;
            coverAtlases[i] = null;
            coverAnims[i] = null;
            coverEndAnims[i] = null;
            hurtCoverRGB[i] = null;
        }
    }

    /**
     * Resolved file locations for a skin. Flat skins (a mod's images/noteSkins/&lt;name&gt;.png)
     * only carry the classic atlas + json; folder skins (config/fnfmod/skins/&lt;name&gt;/) carry
     * the full fixed-name set. Null fields are simply absent.
     */
    private record SkinFiles(Path dir, Path classicPng, Path classicXml, Path json,
                             Path notesPng, Path notesXml, Path strumPng, Path strumXml,
                             Path holdAssetsPng, Path splashPng, Path splashXml, boolean folder,
                             Path pixelPng, Path pixelEndsPng) {}

    public enum ExportLayout { PSYCH_MOD, BLOCKIFIED_CONFIG }

    public record ExportResult(Path root, Path configFile, int copiedFiles) {}

    private static SkinFiles resolveSkinFiles(String name) {
        String source = com.fnfmod.client.ClientOptions.get().noteSkinSource;
        if (allowsCurrentSource(source)) {
            Path current = SongLibrary.currentModPickerRoot().orElse(null);
            SkinFiles fromCurrent = skinInContentRoot(current, name);
            if (fromCurrent != null) return fromCurrent;
        }
        if (!allowsGlobalSource(source)) return null;
        // Flat file directly in config/fnfmod/skins/, named by the skin or NOTE_assets-<skin>.
        Path skins = SongLibrary.skinsDir();
        String flatStem = flatStemFor(skins, name);
        if (flatStem != null) return flatSkin(skins, flatStem);
        // Folder skin under config/fnfmod/skins/<name>/ — classic atlas may be NOTE_assets.png
        // or a NOTE_assets-<whatever>.png named like a flat skin.
        Path folder = skins.resolve(name);
        if (java.nio.file.Files.isDirectory(folder)) return folderSkin(folder);
        // Mods settings paths are ordered. Search every enabled source in that exact
        // order so a duplicate skin name always resolves from the upper path.
        for (Path root : SongLibrary.orderedGlobalPickerContentRoots(SongLibrary.ExternalContent.IMAGES)) {
            SkinFiles external = skinInContentRoot(root, name);
            if (external != null) return external;
        }
        return null;
    }

    private static boolean allowsCurrentSource(String source) {
        return source == null || !source.equalsIgnoreCase(
                com.fnfmod.client.ClientOptions.NOTE_ASSET_SOURCE_GLOBAL);
    }

    private static boolean allowsGlobalSource(String source) {
        return source == null || !source.equalsIgnoreCase(
                com.fnfmod.client.ClientOptions.NOTE_ASSET_SOURCE_CURRENT);
    }

    private static SkinFiles flatSkin(Path dir, String stem) {
        return new SkinFiles(dir, dir.resolve(stem + ".png"), dir.resolve(stem + ".xml"),
                dir.resolve(stem + ".json"), null, null, null, null, null, null, null, false,
                null, null);
    }

    /** The flat-skin stem in {@code dir} for a display name: {@code <name>} or {@code NOTE_assets-<name>}. */
    private static String flatStemFor(Path dir, String name) {
        if (dir == null || !java.nio.file.Files.isDirectory(dir)) return null;
        if (java.nio.file.Files.isRegularFile(dir.resolve(name + ".png"))) return name;
        String prefixed = "NOTE_assets-" + name;
        if (java.nio.file.Files.isRegularFile(dir.resolve(prefixed + ".png"))) return prefixed;
        return null;
    }

    /** Accepted image roots for a mod, engine root, shared library, or selected assets folder. */
    private static java.util.List<Path> imageRoots(Path root) {
        if (root == null) return java.util.List.of();
        java.util.LinkedHashSet<Path> result = new java.util.LinkedHashSet<>();
        for (String relative : new String[]{"images", "shared/images", "assets/images",
                "assets/shared/images", ""}) {
            Path candidate = relative.isEmpty() ? root : root.resolve(relative);
            candidate = candidate.toAbsolutePath().normalize();
            if (java.nio.file.Files.isDirectory(candidate)) result.add(candidate);
        }
        return java.util.List.copyOf(result);
    }

    /** Finds one normal or pixel-only Psych skin inside a single ordered content root. */
    private static SkinFiles skinInContentRoot(Path root, String name) {
        for (Path images : imageRoots(root)) {
            Path normalDir = images.resolve("noteSkins");
            Path folder = normalDir.resolve(name);
            if (java.nio.file.Files.isDirectory(folder) && folderHasAtlas(folder)) {
                return folderSkin(folder);
            }
            String normalStem = flatStemFor(normalDir, name);
            if (normalStem != null
                    && java.nio.file.Files.isRegularFile(normalDir.resolve(normalStem + ".xml"))) {
                return flatSkin(normalDir, normalStem);
            }
            for (Path pixelDir : new Path[]{images.resolve("pixelUI/noteSkins"),
                    images.resolve("pixelUI")}) {
                String pixelStem = flatStemFor(pixelDir, name);
                if (pixelStem == null) continue;
                Path pixelPng = pixelDir.resolve(pixelStem + ".png");
                Path pixelEnds = pixelEndsPath(pixelDir, pixelStem);
                Path json = firstExisting(normalDir.resolve(pixelStem + ".json"),
                        pixelDir.resolve(pixelStem + ".json"));
                if (json == null) json = pixelDir.resolve(pixelStem + ".json");
                // Keep the expected classic path so normal mode can gracefully fall back;
                // pixel mode uses the explicit grid files below and needs no XML.
                return new SkinFiles(normalDir, normalDir.resolve(pixelStem + ".png"),
                        normalDir.resolve(pixelStem + ".xml"), json,
                        null, null, null, null, null, null, null, false,
                        pixelPng, pixelEnds);
            }
        }
        return null;
    }

    private static Path firstExisting(Path... candidates) {
        for (Path candidate : candidates) {
            if (candidate != null && java.nio.file.Files.isRegularFile(candidate)) return candidate;
        }
        return null;
    }

    /** Psych inserts ENDS before a variant suffix, otherwise appends it. */
    private static Path pixelEndsPath(Path dir, String stem) {
        java.util.List<String> candidates = new java.util.ArrayList<>();
        int dash = stem.indexOf('-');
        if (dash > 0) candidates.add(stem.substring(0, dash) + "ENDS" + stem.substring(dash));
        candidates.add(stem + "ENDS");
        for (String candidate : candidates) {
            Path exact = dir.resolve(candidate + ".png");
            if (java.nio.file.Files.isRegularFile(exact)) return exact;
            Path insensitive = firstNamed(dir, candidate + ".png");
            if (insensitive != null) return insensitive;
        }
        return null;
    }

    /** Builds a folder skin, resolving the classic atlas whether it is NOTE_assets.png or NOTE_assets-*.png. */
    private static SkinFiles folderSkin(Path folder) {
        Path classicPng = folder.resolve("NOTE_assets.png");
        if (!java.nio.file.Files.isRegularFile(classicPng)) {
            Path variant = firstMatching(folder, "note_assets-", ".png");
            if (variant != null) classicPng = variant;
        }
        String stem = fileStem(classicPng);
        Path classicXml = folder.resolve(stem + ".xml");
        Path notesPng = folder.resolve("notes.png");
        Path strumPng = folder.resolve("noteStrumline.png");
        Path primaryPng = java.nio.file.Files.isRegularFile(classicPng) ? classicPng
                : java.nio.file.Files.isRegularFile(notesPng) ? notesPng
                : java.nio.file.Files.isRegularFile(strumPng) ? strumPng : classicPng;
        Path matchingJson = folder.resolve(fileStem(primaryPng) + ".json");
        Path json = folder.resolve("skin.json");
        if (!java.nio.file.Files.isRegularFile(json)) {
            if (java.nio.file.Files.isRegularFile(matchingJson)) json = matchingJson;
            else {
                Path existingJson = firstMatching(folder, "", ".json");
                json = existingJson == null ? matchingJson : existingJson;
            }
        }
        return new SkinFiles(folder,
                classicPng, classicXml, json,
                notesPng, folder.resolve("notes.xml"),
                strumPng, folder.resolve("noteStrumline.xml"),
                folder.resolve("NOTE_hold_assets.png"),
                folder.resolve("noteSplashes.png"), folder.resolve("noteSplashes.xml"), true,
                null, null);
    }

    private static String fileStem(Path png) {
        String n = png.getFileName().toString();
        int dot = n.lastIndexOf('.');
        return dot > 0 ? n.substring(0, dot) : n;
    }

    /** First file in {@code dir} whose lowercase name starts with {@code prefix} and ends with {@code ext}. */
    private static Path firstMatching(Path dir, String prefix, String ext) {
        try (var files = java.nio.file.Files.list(dir)) {
            return files.filter(java.nio.file.Files::isRegularFile)
                    .filter(f -> {
                        String n = f.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
                        return n.startsWith(prefix) && n.endsWith(ext);
                    })
                    .sorted()
                    .findFirst().orElse(null);
        } catch (Exception ignored) {
            return null;
        }
    }

    /** Whether a folder holds a classic or V-Slice note atlas under any accepted name. */
    private static boolean folderHasAtlas(Path dir) {
        return java.nio.file.Files.isRegularFile(dir.resolve("NOTE_assets.png"))
                || java.nio.file.Files.isRegularFile(dir.resolve("notes.png"))
                || java.nio.file.Files.isRegularFile(dir.resolve("noteStrumline.png"))
                || firstMatching(dir, "note_assets-", ".png") != null;
    }

    private static SparrowAtlas atlasOrNull(Path png, Path xml) {
        return png != null && xml != null ? SparrowAtlas.load(png, xml, !pixelUi) : null;
    }

    /**
     * The pixel-UI note sheet for a skin, if one is shipped. Looked up in a {@code pixelUI/}
     * folder beside the skin (a folder skin's own pixelUI/, or the skins root's pixelUI/ for a
     * flat skin), matching the skin's file name — the same layout Psych mods use.
     */
    private static SparrowAtlas loadPixelSheet(SkinFiles files) {
        Path sheet = pixelSheetPath(files, false);
        return sheet == null ? null : SparrowAtlas.loadPixelGrid(sheet, false);
    }

    /** The matching {@code <skin>ENDS.png} hold sheet, if present. */
    private static SparrowAtlas loadPixelEndsSheet(SkinFiles files) {
        Path sheet = pixelSheetPath(files, true);
        return sheet == null ? null : SparrowAtlas.loadPixelGrid(sheet, true);
    }

    /**
     * Resolves a pixel sheet path. {@code ends} picks the sustain sheet, whose name inserts
     * {@code ENDS} after the base atlas name (NOTE_assets-bar.png -&gt; NOTE_assetsENDS-bar.png).
     */
    private static Path pixelSheetPath(SkinFiles files, boolean ends) {
        Path explicit = ends ? files.pixelEndsPng() : files.pixelPng();
        if (explicit != null && java.nio.file.Files.isRegularFile(explicit)) return explicit;
        Path png = files.classicPng();
        if (png == null || files.dir() == null) return null;
        String stem = fileStem(png);
        java.util.List<String> candidates = new java.util.ArrayList<>();
        if (ends) {
            // NOTE_assets-<x> -> NOTE_assetsENDS-<x>; otherwise just append ENDS.
            int dash = stem.indexOf('-');
            if (dash > 0) candidates.add(stem.substring(0, dash) + "ENDS" + stem.substring(dash));
            candidates.add(stem + "ENDS");
        } else {
            candidates.add(stem);
        }
        for (Path pixelDir : pixelDirectories(files)) {
            if (!java.nio.file.Files.isDirectory(pixelDir)) continue;
            for (String candidate : candidates) {
                Path exact = pixelDir.resolve(candidate + ".png");
                if (java.nio.file.Files.isRegularFile(exact)) return exact;
            }
            // Fall back to a case-insensitive match, since these files are hand-named.
            for (String candidate : candidates) {
                Path match = firstNamed(pixelDir, candidate + ".png");
                if (match != null) return match;
            }
        }
        return null;
    }

    /**
     * Psych stores a normal atlas at images/noteSkins/X and its pixel counterpart at
     * images/pixelUI/noteSkins/X. Local Blockified skin folders may instead keep pixelUI
     * directly beside the classic atlas, so both layouts remain accepted.
     */
    private static java.util.List<Path> pixelDirectories(SkinFiles files) {
        java.util.List<Path> result = new java.util.ArrayList<>();
        Path png = files.classicPng();
        Path parent = png == null ? null : png.getParent();
        if (parent != null) {
            Path cursor = parent;
            while (cursor != null && cursor.getFileName() != null) {
                if (cursor.getFileName().toString().equalsIgnoreCase("images")) {
                    Path relative = cursor.relativize(parent);
                    Path standard = cursor.resolve("pixelUI").resolve(relative).normalize();
                    if (!result.contains(standard)) result.add(standard);
                    // Psych also places some selectable pixel note sheets directly in
                    // images/pixelUI rather than its noteSkins child.
                    Path flatPixelUi = cursor.resolve("pixelUI").normalize();
                    if (!result.contains(flatPixelUi)) result.add(flatPixelUi);
                    break;
                }
                cursor = cursor.getParent();
            }
        }
        Path beside = files.dir() == null ? null : files.dir().resolve("pixelUI").normalize();
        if (beside != null && !result.contains(beside)) result.add(beside);
        return result;
    }

    /** Case-insensitive file lookup inside a folder. */
    private static Path firstNamed(Path dir, String fileName) {
        try (var files = java.nio.file.Files.list(dir)) {
            return files.filter(java.nio.file.Files::isRegularFile)
                    .filter(f -> f.getFileName().toString().equalsIgnoreCase(fileName))
                    .findFirst().orElse(null);
        } catch (Exception ignored) {
            return null;
        }
    }

    private record SplashPair(Path png, Path xml) {}

    /** A case-insensitive PNG/XML pair with the requested stem. */
    private static SplashPair splashPair(Path dir, String stem) {
        if (dir == null || stem == null || stem.isBlank() || !java.nio.file.Files.isDirectory(dir)) return null;
        Path png = firstNamed(dir, stem + ".png");
        Path xml = firstNamed(dir, stem + ".xml");
        return png != null && xml != null ? new SplashPair(png, xml) : null;
    }

    /** First valid PNG/XML atlas in a folder, used for freely named splash packs. */
    private static SplashPair firstSplashPair(Path dir) {
        if (dir == null || !java.nio.file.Files.isDirectory(dir)) return null;
        try (var files = java.nio.file.Files.list(dir)) {
            for (Path png : files.filter(java.nio.file.Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".png"))
                    .sorted().toList()) {
                String stem = fileStem(png);
                Path xml = firstNamed(dir, stem + ".xml");
                if (xml != null) return new SplashPair(png, xml);
            }
        } catch (Exception ignored) {}
        return null;
    }

    /** First hit-splash pair, excluding sustain-cover assets by name. */
    private static SplashPair firstHitSplashPair(Path dir) {
        if (dir == null || !java.nio.file.Files.isDirectory(dir)) return null;
        try (var files = java.nio.file.Files.list(dir)) {
            for (Path png : files.filter(java.nio.file.Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".png"))
                    .sorted().toList()) {
                String stem = fileStem(png);
                String lower = stem.toLowerCase(java.util.Locale.ROOT);
                if (lower.contains("holdsplash") || lower.contains("holdcover")) continue;
                Path xml = firstNamed(dir, stem + ".xml");
                if (xml != null) return new SplashPair(png, xml);
            }
        } catch (Exception ignored) {}
        return null;
    }

    /** Folder-pack naming mirrors skins but accepts common Psych splash names too. */
    private static SplashPair splashPairInFolder(Path dir, String packName, String preferredStem) {
        java.util.List<String> stems = new java.util.ArrayList<>();
        if (preferredStem != null && !preferredStem.isBlank()) stems.add(preferredStem);
        stems.add("noteSplashes");
        stems.add("noteSplashes-" + packName);
        stems.add(packName);
        stems.add("splash");
        for (String stem : stems) {
            SplashPair pair = splashPair(dir, stem);
            if (pair != null) return pair;
        }
        return firstHitSplashPair(dir);
    }

    /** Loads a folder pack's pixelUI atlas when available, otherwise its normal atlas. */
    private static SparrowAtlas splashFolderAtlas(Path folder, String packName) {
        SplashPair normal = splashPairInFolder(folder, packName, null);
        SplashPair selected = normal;
        if (pixelUi) {
            String normalStem = normal == null ? null : fileStem(normal.png());
            SplashPair pixel = splashPairInFolder(folder.resolve("pixelUI"), packName, normalStem);
            if (pixel != null) selected = pixel;
        }
        return selected == null ? null : atlasOrNull(selected.png(), selected.xml());
    }

    /** Resolves one named splash from a Psych images root, preferring its pixel counterpart. */
    private static SplashPair splashPairInImages(Path images, String name) {
        Path normalDir = images.resolve("noteSplashes");
        Path normalFolder = normalDir.resolve(name);
        SplashPair normal = splashPairInFolder(normalFolder, name, null);
        if (normal == null) normal = splashPair(normalDir, name);
        if (!pixelUi) return normal;

        String preferredStem = normal == null ? name : fileStem(normal.png());
        SplashPair pixel = splashPairInFolder(normalFolder.resolve("pixelUI"), name, preferredStem);
        if (pixel != null) return pixel;
        for (Path pixelDir : new Path[]{images.resolve("pixelUI/noteSplashes"),
                images.resolve("pixelUI")}) {
            pixel = splashPairInFolder(pixelDir.resolve(name), name, preferredStem);
            if (pixel == null) pixel = splashPair(pixelDir, preferredStem);
            if (pixel == null && !preferredStem.equals(name)) pixel = splashPair(pixelDir, name);
            if (pixel != null) return pixel;
        }
        return normal;
    }

    private static SplashPair splashPairInContentRoot(Path root, String name) {
        for (Path images : imageRoots(root)) {
            SplashPair pair = splashPairInImages(images, name);
            if (pair != null) return pair;
        }
        return null;
    }

    /** Resolves folder packs first, then legacy flat pairs, from active mod and global config. */
    private static SparrowAtlas resolveSplashAtlas(String name) {
        String source = com.fnfmod.client.ClientOptions.get().splashSkinSource;
        if (allowsCurrentSource(source)) {
            Path current = SongLibrary.currentModPickerRoot().orElse(null);
            SplashPair fromCurrent = splashPairInContentRoot(current, name);
            if (fromCurrent != null) return atlasOrNull(fromCurrent.png(), fromCurrent.xml());
        }
        if (!allowsGlobalSource(source)) return null;
        Path dir = SongLibrary.splashesDir();
        SparrowAtlas folder = splashFolderAtlas(dir.resolve(name), name);
        if (folder != null) return folder;
        SplashPair pair = splashPair(dir, name);
        if (pixelUi) {
            SplashPair pixel = splashPair(dir.resolve("pixelUI"), name);
            if (pixel != null) pair = pixel;
        }
        if (pair != null) return atlasOrNull(pair.png(), pair.xml());
        // Installed mods first, then external paths from top to bottom. The first
        // same-named pair wins, exactly like the note-skin selector.
        for (Path root : SongLibrary.orderedGlobalPickerContentRoots(SongLibrary.ExternalContent.IMAGES)) {
            SplashPair external = splashPairInContentRoot(root, name);
            if (external != null) return atlasOrNull(external.png(), external.xml());
        }
        return null;
    }

    /** A note-skin folder may bundle normal and pixel splashes using the same layout. */
    private static SparrowAtlas resolveSkinOwnSplash(SkinFiles files) {
        SplashPair normal = files.splashPng() != null && files.splashXml() != null
                && java.nio.file.Files.isRegularFile(files.splashPng())
                && java.nio.file.Files.isRegularFile(files.splashXml())
                ? new SplashPair(files.splashPng(), files.splashXml()) : null;
        SplashPair selected = normal;
        if (pixelUi && files.dir() != null) {
            String stem = normal == null ? null : fileStem(normal.png());
            SplashPair pixel = splashPairInFolder(files.dir().resolve("pixelUI"),
                    files.dir().getFileName().toString(), stem);
            if (pixel != null) selected = pixel;
        }
        return selected == null ? null : atlasOrNull(selected.png(), selected.xml());
    }

    /** Lane-specific names win; a generic splash/impact animation is valid for every lane. */
    private static java.util.List<String> splashAnimations(SparrowAtlas atlas,
                                                           String color, String direction) {
        java.util.List<String> lane = new java.util.ArrayList<>();
        java.util.List<String> generic = new java.util.ArrayList<>();
        java.util.List<String> fallbackLane = new java.util.ArrayList<>();
        java.util.List<String> fallback = new java.util.ArrayList<>();
        if (atlas == null) return lane;
        for (String animation : atlas.animationNames()) {
            String lower = animation.toLowerCase(java.util.Locale.ROOT);
            boolean matchesLane = lower.contains(color) || lower.contains(direction);
            fallback.add(animation);
            if (matchesLane) fallbackLane.add(animation);
            if (lower.contains("impact") || lower.contains("splash")) {
                generic.add(animation);
                if (matchesLane) lane.add(animation);
            }
        }
        // Psych normally names these "note splash"/"note impact", but RGB and
        // custom packs often use arbitrary prefixes. Prefer the known names,
        // then a lane/color match, and finally any animation in the chosen atlas.
        if (lane.isEmpty()) lane.addAll(!generic.isEmpty() ? generic
                : !fallbackLane.isEmpty() ? fallbackLane : fallback);
        java.util.Collections.sort(lane);
        return lane;
    }

    /** A hold-cover pack accepts holdSplash, its pack name, or any first PNG/XML pair. */
    private static SplashPair holdSplashPairInFolder(Path folder, String packName) {
        if (folder == null || !java.nio.file.Files.isDirectory(folder)) return null;
        for (String stem : new String[]{"holdSplash", packName}) {
            SplashPair pair = splashPair(folder, stem);
            if (pair != null) return pair;
        }
        return firstSplashPair(folder);
    }

    private static SplashPair holdSplashPairInImages(Path images, String name) {
        boolean defaultPack = name == null || name.isBlank()
                || name.equalsIgnoreCase(com.fnfmod.client.ClientOptions.NOTE_SKIN_DEFAULT);
        String packName = defaultPack ? "holdSplash" : name;
        Path normalRoot = images.resolve("noteSplashes/holdSplashes");
        Path normalFolder = defaultPack ? normalRoot : normalRoot.resolve(name);
        SplashPair normal = defaultPack ? splashPair(normalRoot, "holdSplash")
                : holdSplashPairInFolder(normalFolder, packName);
        if (!defaultPack && normal == null) normal = splashPair(normalRoot, name);
        if (!pixelUi) return normal;

        String stem = normal == null ? packName : fileStem(normal.png());
        SplashPair pixel = defaultPack ? splashPair(normalRoot.resolve("pixelUI"), "holdSplash")
                : holdSplashPairInFolder(normalFolder.resolve("pixelUI"), stem);
        if (pixel != null) return pixel;
        for (Path pixelRoot : new Path[]{images.resolve("pixelUI/noteSplashes/holdSplashes"),
                images.resolve("pixelUI/holdSplashes")}) {
            pixel = defaultPack ? splashPair(pixelRoot, "holdSplash")
                    : holdSplashPairInFolder(pixelRoot.resolve(name), stem);
            if (!defaultPack && pixel == null) pixel = splashPair(pixelRoot, name);
            if (pixel != null) return pixel;
        }
        return normal;
    }

    private static SplashPair holdSplashPairInContentRoot(Path root, String name) {
        for (Path images : imageRoots(root)) {
            SplashPair pair = holdSplashPairInImages(images, name);
            if (pair != null) return pair;
        }
        return null;
    }

    /** Pixel-aware pack lookup in local config followed by every enabled Mods path. */
    private static SplashPair resolveGlobalHoldSplash(String name) {
        String source = com.fnfmod.client.ClientOptions.get().holdSplashSkinSource;
        if (allowsCurrentSource(source)) {
            Path current = SongLibrary.currentModPickerRoot().orElse(null);
            SplashPair fromCurrent = holdSplashPairInContentRoot(current, name);
            if (fromCurrent != null) return fromCurrent;
        }
        if (!allowsGlobalSource(source)) return null;
        Path root = SongLibrary.splashesDir().resolve("holdSplashes");
        boolean defaultPack = name == null || name.isBlank()
                || name.equalsIgnoreCase(com.fnfmod.client.ClientOptions.NOTE_SKIN_DEFAULT);
        String packName = defaultPack ? "holdSplash" : name;
        Path folder = defaultPack ? root : root.resolve(name);
        SplashPair normal = defaultPack ? splashPair(root, "holdSplash")
                : holdSplashPairInFolder(folder, packName);
        if (!defaultPack && normal == null) normal = splashPair(root, name);
        SplashPair selected = normal;
        if (pixelUi) {
            String stem = normal == null ? packName : fileStem(normal.png());
            SplashPair pixel = defaultPack ? splashPair(root.resolve("pixelUI"), "holdSplash")
                    : holdSplashPairInFolder(folder.resolve("pixelUI"), stem);
            if (!defaultPack && pixel == null) pixel = splashPair(root.resolve("pixelUI"), name);
            if (pixel != null) selected = pixel;
        }
        if (selected != null) return selected;
        for (Path contentRoot : SongLibrary.orderedGlobalPickerContentRoots(SongLibrary.ExternalContent.IMAGES)) {
            SplashPair external = holdSplashPairInContentRoot(contentRoot, name);
            if (external != null) return external;
        }
        return null;
    }

    /**
     * Loads only a chart/Psych companion or the dedicated global holdSplashes folder.
     * Note-skin folders and hit-splash pack subfolders deliberately cannot supply it.
     */
    private static void loadHoldSplashAtlas() {
        String selected = com.fnfmod.client.ClientOptions.get().holdSplashSkin;
        if (selected != null && selected.equalsIgnoreCase(com.fnfmod.client.ClientOptions.NOTE_SKIN_NONE)) {
            return;
        }
        SplashPair pair = songHoldSplashPng != null && songHoldSplashXml != null
                && java.nio.file.Files.isRegularFile(songHoldSplashPng)
                && java.nio.file.Files.isRegularFile(songHoldSplashXml)
                ? new SplashPair(songHoldSplashPng, songHoldSplashXml) : null;
        boolean custom = selected != null && !selected.isBlank()
                && !selected.equalsIgnoreCase(com.fnfmod.client.ClientOptions.NOTE_SKIN_DEFAULT);
        if (custom || pair == null) pair = resolveGlobalHoldSplash(selected);
        if (pair == null) return;
        holdSplashAtlas = atlasOrNull(pair.png(), pair.xml());
        if (holdSplashAtlas == null) return;
        holdSplashStartAnim = holdSplashAtlas.findAnimation("start", "hold start");
        holdSplashLoopAnim = holdSplashAtlas.findAnimation("hold", "loop", "hold loop");
        holdSplashEndAnim = holdSplashAtlas.findAnimation("end", "hold end", "explode");
        // V-Slice and RGB templates may use holdCoverStart*, holdCover*, and
        // holdCoverEnd* (or entirely custom prefixes) instead of the short
        // Psych names. Classify their actual animation names before rejecting
        // the atlas, so a direction-neutral cover is valid for every lane.
        for (String animation : holdSplashAtlas.animationNames()) {
            String lower = animation.toLowerCase(java.util.Locale.ROOT);
            boolean ending = lower.contains("end") || lower.contains("explode");
            boolean starting = lower.contains("start");
            if (holdSplashStartAnim == null && starting) holdSplashStartAnim = animation;
            if (holdSplashEndAnim == null && ending) holdSplashEndAnim = animation;
            if (holdSplashLoopAnim == null && !starting && !ending
                    && (lower.contains("hold") || lower.contains("cover") || lower.contains("loop"))) {
                holdSplashLoopAnim = animation;
            }
        }
        if (holdSplashLoopAnim == null) {
            for (String animation : holdSplashAtlas.animationNames()) {
                if (animation.equals(holdSplashStartAnim) || animation.equals(holdSplashEndAnim)) continue;
                String lower = animation.toLowerCase(java.util.Locale.ROOT);
                if (!lower.contains("end") && !lower.contains("explode")) {
                    holdSplashLoopAnim = animation;
                    break;
                }
            }
        }
        // A one-animation template is also valid: play it once as the start,
        // then keep looping it for both the picker and gameplay hold duration.
        if (holdSplashLoopAnim == null && holdSplashStartAnim != null) {
            holdSplashLoopAnim = holdSplashStartAnim;
        }
        if (holdSplashLoopAnim == null && holdSplashEndAnim == null) {
            holdSplashAtlas.close();
            holdSplashAtlas = null;
            holdSplashStartAnim = holdSplashLoopAnim = holdSplashEndAnim = null;
        }
    }

    /** Display name for a flat skin stem: strips a leading {@code NOTE_assets-} so files drop in cleanly. */
    private static String displaySkinName(String stem) {
        String prefix = "NOTE_assets-";
        if (stem.regionMatches(true, 0, prefix, 0, prefix.length())) return stem.substring(prefix.length());
        return stem;
    }

    private static void addSkinName(java.util.List<String> out, String name) {
        if (name == null || name.isBlank()) return;
        if (name.equalsIgnoreCase(com.fnfmod.client.ClientOptions.NOTE_SKIN_DEFAULT)
                || name.equalsIgnoreCase(com.fnfmod.client.ClientOptions.NOTE_SKIN_NONE)) return;
        for (String existing : out) if (existing.equalsIgnoreCase(name)) return;
        out.add(name);
    }

    private static void addNormalSkinNames(java.util.List<String> out, Path normalDir) {
        if (normalDir == null || !java.nio.file.Files.isDirectory(normalDir)) return;
        try (var entries = java.nio.file.Files.list(normalDir)) {
            entries.filter(java.nio.file.Files::isDirectory)
                    .filter(NoteStyle::folderHasAtlas)
                    .map(path -> path.getFileName().toString())
                    .sorted()
                    .forEach(name -> addSkinName(out, name));
        } catch (Exception ignored) {}
        try (var entries = java.nio.file.Files.list(normalDir)) {
            entries.filter(java.nio.file.Files::isRegularFile)
                    .map(path -> path.getFileName().toString())
                    .filter(name -> name.toLowerCase(java.util.Locale.ROOT).endsWith(".png"))
                    .map(name -> name.substring(0, name.length() - 4))
                    .filter(stem -> firstNamed(normalDir, stem + ".xml") != null)
                    .sorted()
                    .forEach(stem -> addSkinName(out, displaySkinName(stem)));
        } catch (Exception ignored) {}
    }

    private static void addPixelSkinNames(java.util.List<String> out, Path pixelDir,
                                          boolean requireEndsCompanion) {
        if (pixelDir == null || !java.nio.file.Files.isDirectory(pixelDir)) return;
        try (var entries = java.nio.file.Files.list(pixelDir)) {
            entries.filter(java.nio.file.Files::isRegularFile)
                    .map(path -> path.getFileName().toString())
                    .filter(name -> name.toLowerCase(java.util.Locale.ROOT).endsWith(".png"))
                    .map(name -> name.substring(0, name.length() - 4))
                    .filter(stem -> !stem.toLowerCase(java.util.Locale.ROOT).contains("ends"))
                    // A dedicated noteSkins folder is unambiguous. In the general pixelUI
                    // folder, require the matching sustain sheet to avoid listing countdown,
                    // rating and number graphics as note skins.
                    .filter(stem -> !requireEndsCompanion || pixelEndsPath(pixelDir, stem) != null)
                    .sorted()
                    .forEach(stem -> addSkinName(out, displaySkinName(stem)));
        } catch (Exception ignored) {}
    }

    private static void addContentSkinNames(java.util.List<String> out, Path root) {
        for (Path images : imageRoots(root)) {
            addNormalSkinNames(out, images.resolve("noteSkins"));
            addPixelSkinNames(out, images.resolve("pixelUI/noteSkins"), false);
            addPixelSkinNames(out, images.resolve("pixelUI"), true);
        }
    }

    private static void addGlobalConfigSkinNames(java.util.List<String> out) {
        try (var dirs = java.nio.file.Files.list(SongLibrary.skinsDir())) {
            dirs.filter(java.nio.file.Files::isDirectory)
                    .filter(NoteStyle::folderHasAtlas)
                    .map(d -> d.getFileName().toString())
                    .sorted()
                    .forEach(name -> addSkinName(out, name));
        } catch (Exception ignored) {}
        // Flat files directly in config/fnfmod/skins/, named by the skin. A NOTE_assets-<x>
        // atlas is offered as "<x>" so a whole Psych note-skin file drops in and reads cleanly.
        try (var files = java.nio.file.Files.list(SongLibrary.skinsDir())) {
            files.filter(java.nio.file.Files::isRegularFile)
                    .map(f -> f.getFileName().toString())
                    .filter(n -> n.toLowerCase(java.util.Locale.ROOT).endsWith(".png"))
                    .map(n -> n.substring(0, n.length() - 4))
                    .filter(n -> java.nio.file.Files.isRegularFile(SongLibrary.skinsDir().resolve(n + ".xml")))
                    .sorted()
                    .forEach(stem -> addSkinName(out, displaySkinName(stem)));
        } catch (Exception ignored) {}
    }

    public static java.util.List<String> listCurrentModSkins() {
        java.util.List<String> out = new java.util.ArrayList<>();
        SongLibrary.currentModPickerRoot().ifPresent(root -> addContentSkinNames(out, root));
        return out;
    }

    public static java.util.List<String> listGlobalSkins() {
        java.util.List<String> out = new java.util.ArrayList<>();
        out.add(com.fnfmod.client.ClientOptions.NOTE_SKIN_DEFAULT);
        out.add(com.fnfmod.client.ClientOptions.NOTE_SKIN_NONE);
        addGlobalConfigSkinNames(out);
        for (Path root : SongLibrary.orderedGlobalPickerContentRoots(SongLibrary.ExternalContent.IMAGES)) {
            addContentSkinNames(out, root);
        }
        return out;
    }

    /** Backward-compatible combined view; dedicated settings use source tabs. */
    public static java.util.List<String> listSkins() {
        java.util.List<String> out = new java.util.ArrayList<>(listCurrentModSkins());
        for (String name : listGlobalSkins()) addSkinName(out, name);
        if (!out.contains(com.fnfmod.client.ClientOptions.NOTE_SKIN_DEFAULT)) {
            out.add(0, com.fnfmod.client.ClientOptions.NOTE_SKIN_DEFAULT);
        }
        if (!out.contains(com.fnfmod.client.ClientOptions.NOTE_SKIN_NONE)) {
            out.add(1, com.fnfmod.client.ClientOptions.NOTE_SKIN_NONE);
        }
        return out;
    }

    /**
     * Feeds the current song's arrowSkin (chart {@code noteTexture}) into this pipeline
     * so the "default" note-skin setting renders it with RGB colours and proper sustains,
     * instead of the raw per-note path. Pass nulls to clear (menus / song end).
     */
    public static synchronized void useSongSkin(java.nio.file.Path png, java.nio.file.Path xml,
                                                java.nio.file.Path json) {
        useSongSkin(png, xml, json, pixelUi);
    }

    /** Atomically changes the song skin and UI mode, performing at most one texture rebuild. */
    public static synchronized void useSongSkin(java.nio.file.Path png, java.nio.file.Path xml,
                                                java.nio.file.Path json, boolean pixel) {
        useSongSkin(png, xml, json, pixel, null, null);
    }

    /** Configures the note skin and Psych hold-splash companion in one texture rebuild. */
    public static synchronized void useSongSkin(java.nio.file.Path png, java.nio.file.Path xml,
                                                java.nio.file.Path json, boolean pixel,
                                                java.nio.file.Path holdSplashPng,
                                                java.nio.file.Path holdSplashXml) {
        useSongSkin(png, xml, json, pixel, holdSplashPng, holdSplashXml, false);
    }

    /** Configures whether the supplied hold cover was explicitly chosen by this chart. */
    public static synchronized void useSongSkin(java.nio.file.Path png, java.nio.file.Path xml,
                                                java.nio.file.Path json, boolean pixel,
                                                java.nio.file.Path holdSplashPng,
                                                java.nio.file.Path holdSplashXml,
                                                boolean holdSplashExplicit) {
        boolean changed = !java.util.Objects.equals(songSkinPng, png)
                || !java.util.Objects.equals(songSkinXml, xml)
                || !java.util.Objects.equals(songSkinJson, json)
                || !java.util.Objects.equals(songHoldSplashPng, holdSplashPng)
                || !java.util.Objects.equals(songHoldSplashXml, holdSplashXml)
                || songHoldSplashExplicit != holdSplashExplicit
                || pixelUi != pixel;
        songSkinPng = png;
        songSkinXml = xml;
        songSkinJson = json;
        songHoldSplashPng = holdSplashPng;
        songHoldSplashXml = holdSplashXml;
        songHoldSplashExplicit = holdSplashExplicit;
        pixelUi = pixel;
        if (changed) reload();
    }

    /** True when the song's arrowSkin is being rendered through this pipeline. */
    public static boolean songSkinActive() {
        return songSkinActive;
    }

    /** Legacy Psych chart value; skin.json's rgb property is authoritative for rendering. */
    public static void setSongRgbAllowed(boolean allowed) {
        songRgbAllowed = allowed;
    }

    public static boolean songRgbAllowed() {
        return songRgbAllowed;
    }

    /** Active note skin's resolved transform config and its save target. */
    public static NoteSkinConfig currentSkinConfig() {
        load();
        return skinConfig;
    }

    public static java.nio.file.Path currentSkinConfigFile() {
        load();
        return skinConfigFile;
    }

    /**
     * Copies the active skin and selected companion effects into a reusable pack.
     * Psych exports always live below an images folder; Blockified exports use
     * config/fnfmod's native skins and splashes folders.
     */
    public static synchronized ExportResult exportCurrentAssets(Path selectedRoot,
                                                                 ExportLayout layout,
                                                                 NoteSkinConfig config)
            throws java.io.IOException {
        load();
        if (activeSkinFiles == null) throw new java.io.IOException("Selected note skin has no source assets");
        if (selectedRoot == null) throw new java.io.IOException("No export folder selected");

        Path root = selectedRoot.toAbsolutePath().normalize();
        Path skinRoot;
        Path splashRoot;
        Path holdRoot;
        Path pixelSkinRoot;
        Path pixelSplashRoot;
        Path pixelHoldRoot;
        if (layout == ExportLayout.PSYCH_MOD) {
            Path images = root.getFileName() != null
                    && root.getFileName().toString().equalsIgnoreCase("images")
                    ? root : root.resolve("images");
            root = images;
            skinRoot = images.resolve("noteSkins");
            splashRoot = images.resolve("noteSplashes");
            holdRoot = splashRoot.resolve("holdSplashes");
            pixelSkinRoot = images.resolve("pixelUI/noteSkins");
            pixelSplashRoot = images.resolve("pixelUI/noteSplashes");
            pixelHoldRoot = pixelSplashRoot.resolve("holdSplashes");
        } else {
            skinRoot = SongLibrary.skinsDir();
            splashRoot = SongLibrary.splashesDir();
            holdRoot = splashRoot.resolve("holdSplashes");
            pixelSkinRoot = skinRoot.resolve("pixelUI");
            pixelSplashRoot = splashRoot.resolve("pixelUI");
            pixelHoldRoot = holdRoot.resolve("pixelUI");
            root = SongLibrary.skinsDir().getParent();
        }

        String selected = com.fnfmod.client.ClientOptions.get().noteSkin;
        String packName = safeExportName(selected, activeSkinFiles);
        int copied = 0;
        Path targetConfig;
        if (activeSkinFiles.folder() && java.nio.file.Files.isDirectory(activeSkinFiles.dir())) {
            Path destination = skinRoot.resolve(packName).normalize();
            copied += copyTree(activeSkinFiles.dir(), destination);
            String configName = activeSkinFiles.json() != null && activeSkinFiles.json().getFileName() != null
                    ? activeSkinFiles.json().getFileName().toString() : "skin.json";
            targetConfig = destination.resolve(configName);
        } else {
            copied += copyFiles(skinRoot, activeSkinFiles.classicPng(), activeSkinFiles.classicXml(),
                    activeSkinFiles.notesPng(), activeSkinFiles.notesXml(), activeSkinFiles.strumPng(),
                    activeSkinFiles.strumXml(), activeSkinFiles.holdAssetsPng());
            Path pixel = pixelSheetPath(activeSkinFiles, false);
            Path pixelEnds = pixelSheetPath(activeSkinFiles, true);
            copied += copyFiles(pixelSkinRoot, pixel, pixelEnds);
            String configName = activeSkinFiles.json() != null && activeSkinFiles.json().getFileName() != null
                    ? activeSkinFiles.json().getFileName().toString()
                    : fileStem(activeSkinFiles.classicPng()) + ".json";
            targetConfig = skinRoot.resolve(configName);
        }
        NoteSkinConfig.saveFile(targetConfig, config == null ? skinConfig : config);

        if (!skinOwnSplash) {
            String splash = com.fnfmod.client.ClientOptions.get().splashSkin;
            if (splash != null && !splash.isBlank()
                    && !splash.equalsIgnoreCase(com.fnfmod.client.ClientOptions.NOTE_SKIN_NONE)) {
                copied += copyEffectPack(resolveSplashPairForExport(splash), splash,
                        splashRoot, pixelSplashRoot);
            }
        }
        if (!skinOwnHoldCover) {
            String hold = com.fnfmod.client.ClientOptions.get().holdSplashSkin;
            SplashPair pair = songHoldSplashPng != null && songHoldSplashXml != null
                    && java.nio.file.Files.isRegularFile(songHoldSplashPng)
                    && java.nio.file.Files.isRegularFile(songHoldSplashXml)
                    ? new SplashPair(songHoldSplashPng, songHoldSplashXml)
                    : resolveGlobalHoldSplash(hold);
            if (hold == null || hold.isBlank()
                    || hold.equalsIgnoreCase(com.fnfmod.client.ClientOptions.NOTE_SKIN_DEFAULT)) hold = "holdSplash";
            if (!hold.equalsIgnoreCase(com.fnfmod.client.ClientOptions.NOTE_SKIN_NONE)) {
                copied += copyEffectPack(pair, hold, holdRoot, pixelHoldRoot);
            }
        }
        return new ExportResult(root, targetConfig, copied);
    }

    private static SplashPair resolveSplashPairForExport(String name) {
        String source = com.fnfmod.client.ClientOptions.get().splashSkinSource;
        if (allowsCurrentSource(source)) {
            Path current = SongLibrary.currentModPickerRoot().orElse(null);
            for (Path images : imageRoots(current)) {
                SplashPair pair = splashPairInImages(images, name);
                if (pair != null) return pair;
            }
        }
        if (!allowsGlobalSource(source)) return null;
        Path dir = SongLibrary.splashesDir();
        SplashPair pair = splashPairInFolder(dir.resolve(name), name, null);
        if (pair == null) pair = splashPair(dir, name);
        if (pixelUi) {
            SplashPair pixel = splashPairInFolder(dir.resolve(name).resolve("pixelUI"), name,
                    pair == null ? null : fileStem(pair.png()));
            if (pixel == null) pixel = splashPair(dir.resolve("pixelUI"), name);
            if (pixel != null) pair = pixel;
        }
        if (pair != null) return pair;
        for (Path contentRoot : SongLibrary.orderedGlobalPickerContentRoots(SongLibrary.ExternalContent.IMAGES)) {
            for (Path images : imageRoots(contentRoot)) {
                pair = splashPairInImages(images, name);
                if (pair != null) return pair;
            }
        }
        return null;
    }

    private static String safeExportName(String selected, SkinFiles files) {
        String value = selected == null ? "" : selected.trim();
        if (value.isBlank() || value.equalsIgnoreCase(com.fnfmod.client.ClientOptions.NOTE_SKIN_DEFAULT)) {
            if (files.folder() && files.dir() != null && files.dir().getFileName() != null) {
                value = files.dir().getFileName().toString();
            } else if (files.classicPng() != null) value = displaySkinName(fileStem(files.classicPng()));
        }
        return safePathSegment(value, "note-skin");
    }

    private static int copyTree(Path source, Path destination) throws java.io.IOException {
        Path src = source.toAbsolutePath().normalize();
        Path dst = destination.toAbsolutePath().normalize();
        if (src.equals(dst)) return 0;
        if (dst.startsWith(src)) throw new java.io.IOException("Export folder cannot be inside the source skin");
        java.nio.file.Files.createDirectories(dst);
        int copied = 0;
        try (var entries = java.nio.file.Files.walk(src)) {
            for (Path entry : entries.toList()) {
                Path relative = src.relativize(entry);
                Path target = dst.resolve(relative);
                if (java.nio.file.Files.isDirectory(entry)) java.nio.file.Files.createDirectories(target);
                else if (java.nio.file.Files.isRegularFile(entry)) {
                    java.nio.file.Files.createDirectories(target.getParent());
                    if (!entry.toAbsolutePath().normalize().equals(target.toAbsolutePath().normalize())) {
                        java.nio.file.Files.copy(entry, target,
                                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                                java.nio.file.StandardCopyOption.COPY_ATTRIBUTES);
                        copied++;
                    }
                }
            }
        }
        return copied;
    }

    private static int copyFiles(Path destination, Path... files) throws java.io.IOException {
        int copied = 0;
        java.nio.file.Files.createDirectories(destination);
        java.util.LinkedHashSet<Path> unique = new java.util.LinkedHashSet<>();
        if (files != null) java.util.Collections.addAll(unique, files);
        unique.remove(null);
        for (Path source : unique) {
            if (!java.nio.file.Files.isRegularFile(source)) continue;
            Path target = destination.resolve(source.getFileName()).toAbsolutePath().normalize();
            if (source.toAbsolutePath().normalize().equals(target)) continue;
            java.nio.file.Files.copy(source, target,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.COPY_ATTRIBUTES);
            copied++;
        }
        return copied;
    }

    private static int copyEffectPack(SplashPair pair, String name, Path normalRoot, Path pixelRoot)
            throws java.io.IOException {
        if (pair == null || pair.png() == null || pair.xml() == null) return 0;
        Path parent = pair.png().getParent();
        boolean pixel = containsPathPart(pair.png(), "pixelUI");
        Path pack = parent;
        if (pack != null && pack.getFileName() != null
                && pack.getFileName().toString().equalsIgnoreCase("pixelUI")) pack = null;
        if (pack != null && pack.getFileName() != null
                && pack.getFileName().toString().equalsIgnoreCase(name)) {
            return copyTree(pack, (pixel ? pixelRoot : normalRoot).resolve(safeEffectName(name)));
        }
        int copied = copyFiles(pixel ? pixelRoot : normalRoot, pair.png(), pair.xml());
        SplashPair companion = companionEffectPair(pair, pixel);
        if (companion != null) copied += copyFiles(pixel ? normalRoot : pixelRoot,
                companion.png(), companion.xml());
        return copied;
    }

    private static SplashPair companionEffectPair(SplashPair pair, boolean sourceIsPixel) {
        Path png = pair.png(), xml = pair.xml();
        if (png == null || xml == null) return null;
        java.util.List<Path> candidates = new java.util.ArrayList<>();
        Path parent = png.getParent();
        if (sourceIsPixel) {
            Path cursor = parent;
            while (cursor != null && cursor.getFileName() != null) {
                if (cursor.getFileName().toString().equalsIgnoreCase("pixelUI")) {
                    Path base = cursor.getParent();
                    if (base != null) candidates.add(base.resolve(cursor.relativize(png)));
                    break;
                }
                cursor = cursor.getParent();
            }
        } else if (parent != null) {
            candidates.add(parent.resolve("pixelUI").resolve(png.getFileName()));
            Path images = parent.getParent();
            if (images != null) candidates.add(images.resolve("pixelUI")
                    .resolve(parent.getFileName()).resolve(png.getFileName()));
        }
        for (Path candidate : candidates) {
            Path candidateXml = candidate.resolveSibling(fileStem(candidate) + ".xml");
            if (java.nio.file.Files.isRegularFile(candidate)
                    && java.nio.file.Files.isRegularFile(candidateXml)) {
                return new SplashPair(candidate, candidateXml);
            }
        }
        return null;
    }

    private static boolean containsPathPart(Path path, String wanted) {
        if (path == null) return false;
        for (Path part : path) if (part.toString().equalsIgnoreCase(wanted)) return true;
        return false;
    }

    private static String safeEffectName(String value) {
        return safePathSegment(value, "effect");
    }

    private static String safePathSegment(String value, String fallback) {
        String raw = value == null ? "" : value.trim();
        StringBuilder clean = new StringBuilder(raw.length());
        String invalid = "<>:\"/\\|?*";
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            clean.append(invalid.indexOf(c) >= 0 || c < 32 ? '_' : c);
        }
        while (!clean.isEmpty() && (clean.charAt(clean.length() - 1) == '.'
                || clean.charAt(clean.length() - 1) == ' ')) clean.setLength(clean.length() - 1);
        return clean.isEmpty() ? fallback : clean.toString();
    }

    /** Live settings preview; call reload() to discard unsaved values. */
    public static void previewSkinConfig(NoteSkinConfig config) {
        load();
        skinConfig = config == null ? NoteSkinConfig.DEFAULT : config;
    }

    private static boolean pixelUi = false;

    /**
     * Sets the pixel-UI mode for the current song. When on, the note/receptor/sustain/splash
     * atlases upload with nearest filtering instead of bilinear, so pixel-art skins stay crisp
     * (matching Psych's isPixelStage). Rebuilds the skin when the mode changes.
     */
    public static synchronized void setPixelUi(boolean pixel) {
        if (pixelUi == pixel) return;
        pixelUi = pixel;
        reload();
    }

    public static boolean pixelUi() {
        return pixelUi;
    }

    private static void load() {
        if (loaded) return;
        loaded = true;
        songSkinActive = false;
        if (arrowTexture == null) {
            arrowTexture = registerGenerated("gen/arrow", makeArrow(false));
            arrowOutlineTexture = registerGenerated("gen/arrow_outline", makeArrow(true));
        }
        String selected = com.fnfmod.client.ClientOptions.get().noteSkin;
        if (selected == null || selected.isBlank()) {
            selected = com.fnfmod.client.ClientOptions.NOTE_SKIN_DEFAULT;
        }
        if (selected.equalsIgnoreCase(com.fnfmod.client.ClientOptions.NOTE_SKIN_NONE)) {
            loadProceduralSplashFallback();
            return; // procedural arrows, no chart skin
        }
        SkinFiles files;
        if (selected.equalsIgnoreCase(com.fnfmod.client.ClientOptions.NOTE_SKIN_DEFAULT)) {
            // "default" = the song's own arrowSkin. Render it here when one is supplied;
            // otherwise fall back to procedural note art.
            boolean songFolder = songSkinPng != null && java.nio.file.Files.isDirectory(songSkinPng);
            SkinFiles songFiles = songSkinPng == null ? null : songFolder
                    ? folderSkin(songSkinPng)
                    : new SkinFiles(songSkinPng.getParent(), songSkinPng, songSkinXml, songSkinJson,
                    null, null, null, null, null, null, null, false, null, null);
            boolean classicAvailable = songFolder && songFiles != null && folderHasAtlas(songSkinPng)
                    || songSkinPng != null && java.nio.file.Files.isRegularFile(songSkinPng)
                    && songSkinXml != null && java.nio.file.Files.isRegularFile(songSkinXml);
            boolean pixelAvailable = pixelUi && songFiles != null
                    && pixelSheetPath(songFiles, false) != null;
            if (classicAvailable || pixelAvailable) {
                files = songFiles;
                songSkinActive = true;
            } else {
                loadProceduralSplashFallback();
                return;
            }
        } else {
            files = resolveSkinFiles(selected);
            if (files == null) {
                loadProceduralSplashFallback();
                return;
            }
        }
        Path skinDir = files.dir();
        activeSkinFiles = files;

        skinConfigFile = files.json();
        skinConfig = NoteSkinConfig.loadFile(skinConfigFile);

        // Pixel UI: a pixelUI/ sheet beside the skin replaces the classic atlas. It ships no
        // XML — the sprite grid defines frame size and position — and is never smoothed.
        SparrowAtlas pixelSheet = pixelUi ? loadPixelSheet(files) : null;
        pixelEndsAtlas = pixelSheet == null ? null : loadPixelEndsSheet(files);

        // classic single atlas (base game / Psych; also flat <skin>.png/.xml imports)
        SparrowAtlas classic = pixelSheet != null ? pixelSheet
                : atlasOrNull(files.classicPng(), files.classicXml());
        // V-Slice split atlases (folder skins only)
        SparrowAtlas vsNotes = classic == null ? atlasOrNull(files.notesPng(), files.notesXml()) : null;
        SparrowAtlas vsStrums = classic == null ? atlasOrNull(files.strumPng(), files.strumXml()) : null;
        noteAtlas = classic != null ? classic : vsNotes;
        strumAtlas = classic != null ? classic : vsStrums;
        if (noteAtlas == null && strumAtlas == null) return;

        String[] dirs = {"left", "down", "up", "right"};
        String[] caps = {"Left", "Down", "Up", "Right"};
        String[] colors = {"purple", "blue", "green", "red"};
        String[] arrows = {"arrowLEFT", "arrowDOWN", "arrowUP", "arrowRIGHT"};
        noteAnims = new String[4];
        receptorAnims = new String[4];
        pressAnims = new String[4];
        confirmAnims = new String[4];
        holdAnims = new String[4];
        holdEndAnims = new String[4];
        for (int i = 0; i < 4; i++) {
            if (noteAtlas != null) {
                noteAnims[i] = noteAtlas.findAnimation(colors[i], "note" + caps[i],
                        colors[i] + " alone", dirs[i] + " note", "note" + dirs[i].toUpperCase());
            }
            if (strumAtlas != null) {
                receptorAnims[i] = strumAtlas.findAnimation(arrows[i], "static" + caps[i],
                        "static" + dirs[i].toUpperCase(), dirs[i] + " static");
                pressAnims[i] = strumAtlas.findAnimation(dirs[i] + " press", "press" + caps[i]);
                confirmAnims[i] = strumAtlas.findAnimation(dirs[i] + " confirm", "confirm" + caps[i]);
                holdAnims[i] = strumAtlas.findAnimation(colors[i] + " hold piece", colors[i] + " hold",
                        dirs[i] + " hold piece");
                // "pruple end hold" is a real typo in Psych Engine's NOTE_assets.xml
                holdEndAnims[i] = strumAtlas.findAnimation(colors[i] + " hold end", colors[i] + " end hold",
                        i == 0 ? "pruple end hold" : colors[i] + " hold end");
            }
        }
        // reference art sizes: notes and receptors are sized by their art pixels,
        // because press/confirm frames carry big glow padding in their logical boxes
        // One source-pixel scale for the whole skin. This preserves the dimensions authored
        // into each frame: a 114px sustain beside a 152px note renders at 114/152 of its width.
        // Pixel grids use the same rule (for example 13px sustains beside 17px note heads).
        noteRefPx = refSize(noteAtlas, noteAnims,
                refSize(strumAtlas, receptorAnims, pixelUi ? 17 : 155));

        // sustain trail sprites: a pixel ENDS sheet first (its own 4x2 grid of pieces/ends)...
        holdsFromStrumAtlas = false;
        if (pixelEndsAtlas != null) {
            for (int i = 0; i < 4; i++) {
                holdPieces[i] = toSprite(pixelEndsAtlas,
                        pixelEndsAtlas.findAnimation(colors[i] + " hold piece"));
                holdEnds[i] = toSprite(pixelEndsAtlas,
                        pixelEndsAtlas.findAnimation(colors[i] + " hold end"));
            }
            holdsFromStrumAtlas = holdPieces[0] != null || holdPieces[1] != null
                    || holdPieces[2] != null || holdPieces[3] != null;
        }
        // ...then classic atlas frames...
        if (!holdsFromStrumAtlas && strumAtlas != null) {
            for (int i = 0; i < 4; i++) {
                holdPieces[i] = toSprite(strumAtlas, holdAnims[i]);
                holdEnds[i] = toSprite(strumAtlas, holdEndAnims[i]);
            }
            holdsFromStrumAtlas = holdPieces[0] != null || holdPieces[1] != null
                    || holdPieces[2] != null || holdPieces[3] != null;
        }
        // ...else the V-Slice NOTE_hold_assets.png strip (8 columns: 4 pieces + 4 end caps)
        if (!holdsFromStrumAtlas && files.holdAssetsPng() != null
                && java.nio.file.Files.isRegularFile(files.holdAssetsPng())) {
            loadVSliceHoldAssets(files.holdAssetsPng());
        }

        // Like a skin-bundled note splash, a V-Slice skin's own per-lane hold covers
        // belong to that skin and take priority over the separate global/chart picker.
        String[] coverColors = {"Purple", "Blue", "Green", "Red"};
        for (int i = 0; i < 4; i++) {
            SparrowAtlas cover = SparrowAtlas.load(
                    skinDir.resolve("holdCover" + coverColors[i] + ".png"),
                    skinDir.resolve("holdCover" + coverColors[i] + ".xml"), !pixelUi);
            if (cover != null) {
                coverAtlases[i] = cover;
                coverAnims[i] = cover.findAnimation("holdCover" + coverColors[i], "holdCover");
                coverEndAnims[i] = cover.findAnimation("holdCoverEnd" + coverColors[i], "holdCoverEnd");
            }
        }
        skinOwnHoldCover = java.util.Arrays.stream(coverAtlases).anyMatch(java.util.Objects::nonNull);

        // hit splashes: the skin's own file wins; otherwise the user-selected pair from
        // the mod's images/noteSplashes/ or config/fnfmod/splashes/ (colored like everything else)
        splashAtlas = resolveSkinOwnSplash(files);
        skinOwnSplash = splashAtlas != null;
        if (splashAtlas == null) {
            String sel = com.fnfmod.client.ClientOptions.get().splashSkin;
            if (sel != null && !sel.isEmpty()) {
                splashAtlas = resolveSplashAtlas(sel);
            }
        }
        splashAnims = new java.util.List[4];
        if (splashAtlas != null) {
            for (int i = 0; i < 4; i++) {
                splashAnims[i] = splashAnimations(splashAtlas, colors[i], dirs[i]);
            }
        }

        loadHoldSplashAtlas();

        // Psych applies the RGB shader when enabled by the song/player; it does not ask the
        // engine to guess whether the artwork is a template. The two explicit controls now
        // own that choice, so ON prepares and uses RGB for every loaded note skin.
        noteRGB = makeRGBSet("note", noteAtlas == null ? null : noteAtlas.image(), Boolean.TRUE);
        strumRGB = strumAtlas == noteAtlas ? noteRGB
                : makeRGBSet("strum", strumAtlas == null ? null : strumAtlas.image(), Boolean.TRUE);
        // Splash templates have no blue channel and pre-colored splashes can contain
        // pure red/green, so pixel stats can't tell them apart. A skin's own splash
        // follows the skin's template status; external packs get a relaxed check.
        Boolean splashTemplate = skinOwnSplash
                ? Boolean.valueOf(noteRGB != null && noteRGB.template)
                : (splashAtlas == null ? Boolean.FALSE : Boolean.valueOf(detectSplashTemplate(splashAtlas.image())));
        splashRGB = makeRGBSet("splash", splashAtlas == null ? null : splashAtlas.image(), splashTemplate);
        // Psych pixel sustains live in a second PNG (<skin>ENDS.png). Its UVs
        // and dimensions are unrelated to the main note sheet, so it needs its
        // own recolored texture. Reusing strumRGB sampled the first PNG with the
        // ENDS sheet's coordinates, making pixel sustain bodies disappear or
        // display unrelated note pixels whenever RGB was enabled.
        holdRGB = pixelEndsAtlas != null
                ? makeRGBSet("hold", pixelEndsAtlas.image(), Boolean.TRUE)
                : holdsFromStrumAtlas ? strumRGB
                : makeRGBSet("hold", holdSheetImage, Boolean.TRUE);
        holdSplashRGB = makeRGBSet("hold_splash",
                holdSplashAtlas == null ? null : holdSplashAtlas.image(), Boolean.TRUE);
        hurtHoldSplashRGB = makeFixedRGBSet("hurt_hold_splash",
                holdSplashAtlas == null ? null : holdSplashAtlas.image(),
                0xFF0000, 0x000000, 0x000000);
        for (int lane = 0; lane < 4; lane++) {
            hurtCoverRGB[lane] = makeFixedRGBSet("hurt_hold_cover_" + lane,
                    coverAtlases[lane] == null ? null : coverAtlases[lane].image(),
                    0xFF0000, 0x000000, 0x000000);
        }

        FnfMod.LOGGER.info("Loaded FNF note skin from {} ({}{}{})", skinDir,
                classic != null ? "classic NOTE_assets" : "V-Slice notes/noteStrumline",
                splashAtlas != null ? ", with splashes" : "",
                noteRGB != null && noteRGB.template ? ", RGB colorable" : "");
    }

    /** Procedural note modes may still use the separately selected global splash atlas. */
    @SuppressWarnings("unchecked")
    private static void loadProceduralSplashFallback() {
        loadHoldSplashAtlas();
        holdSplashRGB = makeRGBSet("hold_splash",
                holdSplashAtlas == null ? null : holdSplashAtlas.image(), Boolean.TRUE);
        hurtHoldSplashRGB = makeFixedRGBSet("hurt_hold_splash",
                holdSplashAtlas == null ? null : holdSplashAtlas.image(),
                0xFF0000, 0x000000, 0x000000);
        String selected = com.fnfmod.client.ClientOptions.get().splashSkin;
        if (selected == null || selected.isBlank()) return;
        splashAtlas = resolveSplashAtlas(selected);
        if (splashAtlas == null) return;
        skinOwnSplash = false;
        splashAnims = new java.util.List[4];
        String[] colors = {"purple", "blue", "green", "red"};
        String[] directions = {"left", "down", "up", "right"};
        for (int lane = 0; lane < 4; lane++) {
            splashAnims[lane] = splashAnimations(splashAtlas, colors[lane], directions[lane]);
        }
        splashRGB = makeRGBSet("splash", splashAtlas.image(), detectSplashTemplate(splashAtlas.image()));
    }

    // ------------------------------------------------------------------ RGB note colors

    private static RGBSet makeRGBSet(String name, NativeImage src, Boolean templateOverride) {
        if (src == null) return null;
        RGBSet set = new RGBSet();
        set.src = src;
        set.template = templateOverride != null ? templateOverride : detectTemplate(src);
        if (set.template) {
            for (int lane = 0; lane < 4; lane++) {
                set.id[lane] = FnfMod.id("gen/rgb/" + name + "/" + lane);
                remapLane(set, lane);
            }
        }
        return set;
    }

    /** One fixed recolored copy shared by every hurt-note lane. */
    private static RGBSet makeFixedRGBSet(String name, NativeImage src,
                                          int base, int highlight, int outline) {
        if (src == null) return null;
        RGBSet set = new RGBSet();
        set.src = src;
        set.template = true;
        set.id[0] = FnfMod.id("gen/rgb/" + name);
        remapColors(set, 0, base, highlight, outline);
        return set;
    }

    /**
     * Relaxed detection for splash sheets: soft feathered art (low alpha), lots of
     * pure red (base) plus some pure green (highlight); no blue/outline channel.
     */
    private static boolean detectSplashTemplate(NativeImage img) {
        long counted = 0, red = 0, green = 0;
        int step = Math.max(1, Math.min(img.getWidth(), img.getHeight()) / 256);
        for (int y = 0; y < img.getHeight(); y += step) {
            for (int x = 0; x < img.getWidth(); x += step) {
                int abgr = img.getPixelRGBA(x, y);
                int a = (abgr >>> 24) & 0xFF;
                if (a < 60) continue;
                counted++;
                int b = (abgr >> 16) & 0xFF;
                int g = (abgr >> 8) & 0xFF;
                int r = abgr & 0xFF;
                if (r > 220 && g < 60 && b < 60) red++;
                else if (g > 220 && r < 60 && b < 60) green++;
            }
        }
        return counted > 100 && red > counted * 0.05 && green > counted * 0.01;
    }

    /** A sheet is a Psych RGB template when it contains pure red, green and blue regions. */
    private static boolean detectTemplate(NativeImage img) {
        long opaque = 0, red = 0, green = 0, blue = 0;
        int step = Math.max(1, Math.min(img.getWidth(), img.getHeight()) / 256);
        for (int y = 0; y < img.getHeight(); y += step) {
            for (int x = 0; x < img.getWidth(); x += step) {
                int abgr = img.getPixelRGBA(x, y);
                int a = (abgr >>> 24) & 0xFF;
                if (a < 200) continue;
                opaque++;
                int b = (abgr >> 16) & 0xFF;
                int g = (abgr >> 8) & 0xFF;
                int r = abgr & 0xFF;
                if (r > 240 && g < 30 && b < 30) red++;
                else if (g > 240 && r < 30 && b < 30) green++;
                else if (b > 240 && r < 30 && g < 30) blue++;
            }
        }
        return opaque > 200
                && red > opaque * 0.04
                && blue > opaque * 0.005
                && green > opaque * 0.002;
    }

    /** Rewrites one lane's recolored copy: out = r*base + g*highlight + b*outline. */
    private static void remapLane(RGBSet set, int lane) {
        var opts = com.fnfmod.client.ClientOptions.get();
        remapColors(set, lane, opts.noteColorBase[lane], opts.noteColorHighlight[lane],
                opts.noteColorOutline[lane]);
    }

    private static void remapColors(RGBSet set, int lane, int base, int highlight, int outline) {
        int baseR = (base >> 16) & 0xFF, baseG = (base >> 8) & 0xFF, baseB = base & 0xFF;
        int hiR = (highlight >> 16) & 0xFF, hiG = (highlight >> 8) & 0xFF, hiB = highlight & 0xFF;
        int outR = (outline >> 16) & 0xFF, outG = (outline >> 8) & 0xFF, outB = outline & 0xFF;

        NativeImage dst = set.pixels[lane];
        if (dst == null || dst.getWidth() != set.src.getWidth() || dst.getHeight() != set.src.getHeight()) {
            dst = new NativeImage(set.src.getWidth(), set.src.getHeight(), true);
            set.pixels[lane] = dst;
            set.dyn[lane] = new net.minecraft.client.renderer.texture.DynamicTexture(dst);
            Minecraft.getInstance().getTextureManager().register(set.id[lane], set.dyn[lane]);
            if (!pixelUi) Textures.smooth(set.dyn[lane]); // antialias recolored (RGB template) skin notes
        }
        for (int y = 0; y < set.src.getHeight(); y++) {
            for (int x = 0; x < set.src.getWidth(); x++) {
                int abgr = set.src.getPixelRGBA(x, y);
                int a = (abgr >>> 24) & 0xFF;
                int b = (abgr >> 16) & 0xFF;
                int g = (abgr >> 8) & 0xFF;
                int r = abgr & 0xFF;
                // Psych RGB template: red = primary, green = secondary/highlight,
                // blue = outline. Old configs normalize the green channel to white.
                int nr = Math.min(255, (r * baseR + g * hiR + b * outR) / 255);
                int ng = Math.min(255, (r * baseG + g * hiG + b * outG) / 255);
                int nb = Math.min(255, (r * baseB + g * hiB + b * outB) / 255);
                dst.setPixelRGBA(x, y, (a << 24) | (nb << 16) | (ng << 8) | nr);
            }
        }
        set.dyn[lane].upload();
    }

    /** Re-applies the user colors for one lane across all colorable sheets. */
    public static void rebuildLaneColors(int lane) {
        load();
        // A combined Psych atlas is shared by notes, receptors and sometimes
        // sustains. Remap each actual texture once instead of uploading the same
        // full image two or three times for one picker movement.
        Set<RGBSet> unique = Collections.newSetFromMap(new IdentityHashMap<>());
        Collections.addAll(unique, noteRGB, strumRGB, splashRGB, holdRGB, holdSplashRGB);
        unique.remove(null);
        for (RGBSet set : unique) {
            if (set != null && set.template) remapLane(set, lane);
        }
    }

    /** True when the current skin supports RGB recoloring at all. */
    public static boolean skinIsColorable() {
        load();
        return noteRGB != null && noteRGB.template;
    }

    /** True when the note skin ships its own splashes (splash selection is then disabled). */
    public static boolean skinHasOwnSplash() {
        load();
        return skinOwnSplash;
    }

    /** True when the note skin ships its own hold covers (external selection is then disabled). */
    public static boolean skinHasOwnHoldCover() {
        load();
        return skinOwnHoldCover;
    }

    private static boolean splashFolderHasAtlas(Path folder) {
        if (folder == null || !java.nio.file.Files.isDirectory(folder)) return false;
        String name = folder.getFileName() == null ? "" : folder.getFileName().toString();
        return splashPairInFolder(folder, name, null) != null
                || splashPairInFolder(folder.resolve("pixelUI"), name, null) != null;
    }

    private static void addSplashName(java.util.List<String> out, String name) {
        if (name == null || name.isBlank() || name.equalsIgnoreCase("pixelUI")
                || name.equalsIgnoreCase("holdSplashes")
                || name.toLowerCase(java.util.Locale.ROOT).contains("holdsplash")
                || name.toLowerCase(java.util.Locale.ROOT).contains("holdcover")) return;
        for (String existing : out) if (existing.equalsIgnoreCase(name)) return;
        out.add(name);
    }

    private static void addSplashNamesFromDirectory(java.util.List<String> out, Path directory) {
        if (directory == null || !java.nio.file.Files.isDirectory(directory)) return;
        try (var dirs = java.nio.file.Files.list(directory)) {
            dirs.filter(java.nio.file.Files::isDirectory)
                    .filter(NoteStyle::splashFolderHasAtlas)
                    .map(path -> path.getFileName().toString())
                    .sorted()
                    .forEach(name -> addSplashName(out, name));
        } catch (Exception ignored) {}
        try (var files = java.nio.file.Files.list(directory)) {
            files.filter(java.nio.file.Files::isRegularFile)
                    .map(path -> path.getFileName().toString())
                    .filter(name -> name.toLowerCase(java.util.Locale.ROOT).endsWith(".png"))
                    .map(name -> name.substring(0, name.length() - 4))
                    .filter(name -> firstNamed(directory, name + ".xml") != null)
                    .sorted()
                    .forEach(name -> addSplashName(out, name));
        } catch (Exception ignored) {}
    }

    private static void addContentSplashNames(java.util.List<String> out, Path root) {
        for (Path images : imageRoots(root)) {
            addSplashNamesFromDirectory(out, images.resolve("noteSplashes"));
            addSplashNamesFromDirectory(out, images.resolve("pixelUI/noteSplashes"));
            // A general pixelUI directory contains many unrelated UI atlases. Only
            // advertise explicit splash-named pairs from that ambiguous location.
            Path flatPixel = images.resolve("pixelUI");
            if (!java.nio.file.Files.isDirectory(flatPixel)) continue;
            try (var files = java.nio.file.Files.list(flatPixel)) {
                files.filter(java.nio.file.Files::isRegularFile)
                        .map(path -> path.getFileName().toString())
                        .filter(name -> name.toLowerCase(java.util.Locale.ROOT).endsWith(".png"))
                        .map(name -> name.substring(0, name.length() - 4))
                        .filter(name -> name.toLowerCase(java.util.Locale.ROOT).contains("splash"))
                        .filter(name -> firstNamed(flatPixel, name + ".xml") != null)
                        .sorted()
                        .forEach(name -> addSplashName(out, name));
            } catch (Exception ignored) {}
        }
    }

    private static void addGlobalConfigSplashNames(java.util.List<String> out) {
        try (var dirs = java.nio.file.Files.list(SongLibrary.splashesDir())) {
            dirs.filter(java.nio.file.Files::isDirectory)
                    .filter(NoteStyle::splashFolderHasAtlas)
                    .map(path -> path.getFileName().toString())
                    .sorted()
                    .forEach(name -> addSplashName(out, name));
        } catch (Exception ignored) {}
        try (var files = java.nio.file.Files.list(SongLibrary.splashesDir())) {
            files.filter(java.nio.file.Files::isRegularFile)
                    .filter(f -> f.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".png"))
                    .map(f -> {
                        String n = f.getFileName().toString();
                        return n.substring(0, n.length() - 4);
                    })
                    .filter(n -> java.nio.file.Files.isRegularFile(SongLibrary.splashesDir().resolve(n + ".xml")))
                    .sorted()
                    .forEach(name -> addSplashName(out, name));
        } catch (Exception ignored) {}
        // Backward-compatible global pixelUI/<name>.png+xml pairs.
        Path globalPixelSplashes = SongLibrary.splashesDir().resolve("pixelUI");
        if (java.nio.file.Files.isDirectory(globalPixelSplashes)) {
            try (var files = java.nio.file.Files.list(globalPixelSplashes)) {
                files.filter(java.nio.file.Files::isRegularFile)
                        .map(path -> path.getFileName().toString())
                        .filter(name -> name.toLowerCase(java.util.Locale.ROOT).endsWith(".png"))
                        .map(name -> name.substring(0, name.length() - 4))
                        .filter(name -> firstNamed(globalPixelSplashes, name + ".xml") != null)
                        .sorted()
                        .forEach(name -> addSplashName(out, name));
            } catch (Exception ignored) {}
        }
    }

    public static java.util.List<String> listCurrentModSplashes() {
        java.util.List<String> out = new java.util.ArrayList<>();
        SongLibrary.currentModPickerRoot().ifPresent(root -> addContentSplashNames(out, root));
        return out;
    }

    public static java.util.List<String> listGlobalSplashes() {
        java.util.List<String> out = new java.util.ArrayList<>();
        addGlobalConfigSplashNames(out);
        for (Path root : SongLibrary.orderedGlobalPickerContentRoots(SongLibrary.ExternalContent.IMAGES)) {
            addContentSplashNames(out, root);
        }
        return out;
    }

    /** Folder splash packs plus legacy flat PNG/XML pairs. */
    public static java.util.List<String> listSplashes() {
        java.util.List<String> out = new java.util.ArrayList<>(listCurrentModSplashes());
        for (String name : listGlobalSplashes()) addSplashName(out, name);
        return out;
    }

    private static boolean holdSplashFolderHasAtlas(Path folder) {
        if (folder == null || !java.nio.file.Files.isDirectory(folder)) return false;
        String name = folder.getFileName() == null ? "holdSplash" : folder.getFileName().toString();
        return holdSplashPairInFolder(folder, name) != null
                || holdSplashPairInFolder(folder.resolve("pixelUI"), name) != null;
    }

    private static void addHoldSplashName(java.util.List<String> out, String name) {
        if (name == null || name.isBlank() || name.equalsIgnoreCase("pixelUI")
                || name.equalsIgnoreCase("holdSplash")
                || name.equalsIgnoreCase(com.fnfmod.client.ClientOptions.NOTE_SKIN_DEFAULT)
                || name.equalsIgnoreCase(com.fnfmod.client.ClientOptions.NOTE_SKIN_NONE)) return;
        for (String existing : out) if (existing.equalsIgnoreCase(name)) return;
        out.add(name);
    }

    private static void addHoldSplashNamesFromRoot(java.util.List<String> out, Path root) {
        if (root == null || !java.nio.file.Files.isDirectory(root)) return;
        try (var dirs = java.nio.file.Files.list(root)) {
            dirs.filter(java.nio.file.Files::isDirectory)
                    .filter(path -> !path.getFileName().toString().equalsIgnoreCase("pixelUI"))
                    .filter(NoteStyle::holdSplashFolderHasAtlas)
                    .map(path -> path.getFileName().toString())
                    .sorted()
                    .forEach(name -> addHoldSplashName(out, name));
        } catch (Exception ignored) {}
        try (var files = java.nio.file.Files.list(root)) {
            files.filter(java.nio.file.Files::isRegularFile)
                    .map(path -> path.getFileName().toString())
                    .filter(name -> name.toLowerCase(java.util.Locale.ROOT).endsWith(".png"))
                    .map(name -> name.substring(0, name.length() - 4))
                    .filter(name -> firstNamed(root, name + ".xml") != null)
                    .sorted()
                    .forEach(name -> addHoldSplashName(out, name));
        } catch (Exception ignored) {}
        Path pixel = root.resolve("pixelUI");
        try (var files = java.nio.file.Files.list(pixel)) {
            files.filter(java.nio.file.Files::isRegularFile)
                    .map(path -> path.getFileName().toString())
                    .filter(name -> name.toLowerCase(java.util.Locale.ROOT).endsWith(".png"))
                    .map(name -> name.substring(0, name.length() - 4))
                    .filter(name -> firstNamed(pixel, name + ".xml") != null)
                    .sorted()
                    .forEach(name -> addHoldSplashName(out, name));
        } catch (Exception ignored) {}
    }

    private static void addContentHoldSplashNames(java.util.List<String> out, Path contentRoot) {
        for (Path images : imageRoots(contentRoot)) {
            addHoldSplashNamesFromRoot(out, images.resolve("noteSplashes/holdSplashes"));
            addHoldSplashNamesFromRoot(out, images.resolve("pixelUI/noteSplashes/holdSplashes"));
            addHoldSplashNamesFromRoot(out, images.resolve("pixelUI/holdSplashes"));
        }
    }

    public static java.util.List<String> listCurrentModHoldSplashes() {
        java.util.List<String> out = new java.util.ArrayList<>();
        SongLibrary.currentModPickerRoot().ifPresent(root -> addContentHoldSplashNames(out, root));
        return out;
    }

    public static java.util.List<String> listGlobalHoldSplashes() {
        java.util.List<String> out = new java.util.ArrayList<>();
        out.add(com.fnfmod.client.ClientOptions.NOTE_SKIN_DEFAULT);
        out.add(com.fnfmod.client.ClientOptions.NOTE_SKIN_NONE);
        Path root = SongLibrary.splashesDir().resolve("holdSplashes");
        try {
            java.nio.file.Files.createDirectories(root);
        } catch (Exception ignored) {}
        addHoldSplashNamesFromRoot(out, root);
        for (Path contentRoot : SongLibrary.orderedGlobalPickerContentRoots(SongLibrary.ExternalContent.IMAGES)) {
            addContentHoldSplashNames(out, contentRoot);
        }
        return out;
    }

    /** Dedicated sustain-cover packs under config/fnfmod/splashes/holdSplashes only. */
    public static java.util.List<String> listHoldSplashes() {
        java.util.List<String> out = new java.util.ArrayList<>(listCurrentModHoldSplashes());
        for (String name : listGlobalHoldSplashes()) {
            if (name.equalsIgnoreCase(com.fnfmod.client.ClientOptions.NOTE_SKIN_DEFAULT)
                    || name.equalsIgnoreCase(com.fnfmod.client.ClientOptions.NOTE_SKIN_NONE)) continue;
            addHoldSplashName(out, name);
        }
        out.add(0, com.fnfmod.client.ClientOptions.NOTE_SKIN_DEFAULT);
        out.add(1, com.fnfmod.client.ClientOptions.NOTE_SKIN_NONE);
        return out;
    }

    private static ResourceLocation laneTex(RGBSet set, int lane) {
        if (set == null || !set.template) return null;
        if (!skinConfig.rgb()) return null;
        return set.id[lane];
    }

    private static ResourceLocation fixedTex(RGBSet set) {
        if (set == null || !set.template || !skinConfig.rgb()) return null;
        return set.id[0];
    }

    private static float refSize(SparrowAtlas atlas, String[] anims, float fallback) {
        if (atlas == null) return fallback;
        for (String anim : anims) {
            SparrowAtlas.Frame f = anim == null ? null : atlas.frame(anim, 0);
            if (f != null) return Math.max(1, Math.max(f.frameW, f.frameH));
        }
        return fallback;
    }

    private static HoldSprite toSprite(SparrowAtlas atlas, String anim) {
        if (atlas == null || anim == null) return null;
        SparrowAtlas.Frame f = atlas.frame(anim, 0);
        if (f == null) return null;
        return new HoldSprite(atlas.texture(), atlas.width(), atlas.height(), f.x, f.y, f.w, f.h);
    }

    /** source pixels of the V-Slice hold sheet, kept for RGB recoloring */
    private static NativeImage holdSheetImage;
    private static ResourceLocation holdSheetTextureId;

    private static void loadVSliceHoldAssets(Path png) {
        holdSheetImage = null;
        try (java.io.InputStream in = java.nio.file.Files.newInputStream(png)) {
            NativeImage img = NativeImage.read(in);
            ResourceLocation id = registerGenerated("gen/hold/" + System.nanoTime(), img, !pixelUi);
            holdSheetImage = img;
            holdSheetTextureId = id;
            int colW = img.getWidth() / 8;
            if (colW <= 0) return;
            // layout is interleaved per color: [piece, end, piece, end, ...]
            for (int i = 0; i < 4; i++) {
                holdPieces[i] = new HoldSprite(id, img.getWidth(), img.getHeight(), (i * 2) * colW, 0, colW, img.getHeight());
                holdEnds[i] = new HoldSprite(id, img.getWidth(), img.getHeight(), (i * 2 + 1) * colW, 0, colW, img.getHeight());
            }
        } catch (Exception ignored) {
            // no V-Slice hold sheet — procedural bars will be used
        }
    }

    private static void blitSprite(GuiGraphics gui, HoldSprite s, float x, float y, float w, float h,
                                   boolean flipY, ResourceLocation texOverride) {
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        boolean tinted = drawAlpha < 1f || missedTint;
        if (tinted) gui.setColor(SparrowAtlas.tintR, SparrowAtlas.tintG, SparrowAtlas.tintB, drawAlpha);
        gui.pose().pushPose();
        gui.pose().translate(x, y, 0);
        gui.pose().scale(w / s.w, h / s.h, 1);
        // vertical flip via source V mirroring (negative geometry scale gets the quad culled)
        float vOff = flipY ? s.y + s.h : s.y;
        int vH = flipY ? -s.h : s.h;
        gui.blit(texOverride != null ? texOverride : s.tex, 0, 0, s.w, s.h, s.x, vOff, s.w, vH, s.texW, s.texH);
        gui.pose().popPose();
        if (tinted) gui.setColor(1f, 1f, 1f, 1f);
    }

    // ------------------------------------------------------------------ hold covers

    public static boolean hasHoldCover(int lane) {
        load();
        return coverAtlases[lane] != null && coverAnims[lane] != null
                || holdSplashAtlas != null
                && (holdSplashStartAnim != null || holdSplashLoopAnim != null);
    }

    public static int holdCoverFrames(int lane) {
        load();
        if (coverAtlases[lane] != null && coverAnims[lane] != null) {
            return coverAtlases[lane].frames(coverAnims[lane]).size();
        }
        int start = holdSplashAtlas == null || holdSplashStartAnim == null ? 0
                : holdSplashAtlas.frames(holdSplashStartAnim).size();
        int loop = holdSplashAtlas == null || holdSplashLoopAnim == null ? 0
                : holdSplashAtlas.frames(holdSplashLoopAnim).size();
        return start + loop;
    }

    /** receptorSize = the receptor's on-screen size; cover placement derives from it. */
    public static void drawHoldCover(GuiGraphics gui, int lane, long frameIndex, float x, float y, float receptorSize) {
        drawHoldCover(gui, lane, frameIndex, x, y, receptorSize, false);
    }

    public static void drawHoldCover(GuiGraphics gui, int lane, long frameIndex,
                                     float x, float y, float receptorSize, boolean hurtNote) {
        if (!hasHoldCover(lane)) return;
        if (coverAtlases[lane] != null && coverAnims[lane] != null) {
            var frames = coverAtlases[lane].frames(coverAnims[lane]);
            if (frames.isEmpty()) return;
            int frame = (int) Math.floorMod(frameIndex, (long) frames.size());
            drawCoverFrame(gui, lane, frames.get(frame), x, y, receptorSize, hurtNote);
            return;
        }
        var start = holdSplashStartAnim == null ? java.util.List.<SparrowAtlas.Frame>of()
                : holdSplashAtlas.frames(holdSplashStartAnim);
        if (frameIndex < start.size()) {
            drawGenericHoldSplashFrame(gui, lane, start.get((int) frameIndex), x, y,
                    receptorSize, hurtNote);
            return;
        }
        var loop = holdSplashLoopAnim == null ? java.util.List.<SparrowAtlas.Frame>of()
                : holdSplashAtlas.frames(holdSplashLoopAnim);
        if (!loop.isEmpty()) {
            int frame = (int) Math.floorMod(frameIndex - start.size(), (long) loop.size());
            drawGenericHoldSplashFrame(gui, lane, loop.get(frame), x, y, receptorSize, hurtNote);
        }
    }

    public static int holdCoverEndFrames(int lane) {
        load();
        if (coverAtlases[lane] != null && coverEndAnims[lane] != null) {
            return coverAtlases[lane].frames(coverEndAnims[lane]).size();
        }
        return holdSplashAtlas == null || holdSplashEndAnim == null ? 0
                : holdSplashAtlas.frames(holdSplashEndAnim).size();
    }

    public static void drawHoldCoverEnd(GuiGraphics gui, int lane, int frameIndex, float x, float y, float receptorSize) {
        drawHoldCoverEnd(gui, lane, frameIndex, x, y, receptorSize, false);
    }

    public static void drawHoldCoverEnd(GuiGraphics gui, int lane, int frameIndex,
                                        float x, float y, float receptorSize, boolean hurtNote) {
        load();
        if (coverAtlases[lane] != null && coverEndAnims[lane] != null) {
            var frames = coverAtlases[lane].frames(coverEndAnims[lane]);
            if (frameIndex < 0 || frameIndex >= frames.size()) return;
            drawCoverFrame(gui, lane, frames.get(frameIndex), x, y, receptorSize, hurtNote);
            return;
        }
        if (holdSplashAtlas == null || holdSplashEndAnim == null) return;
        var frames = holdSplashAtlas.frames(holdSplashEndAnim);
        if (frameIndex < 0 || frameIndex >= frames.size()) return;
        drawGenericHoldSplashFrame(gui, lane, frames.get(frameIndex), x, y, receptorSize, hurtNote);
    }

    private static void drawGenericHoldSplashFrame(GuiGraphics gui, int lane, SparrowAtlas.Frame frame,
                                                    float x, float y, float receptorSize,
                                                    boolean hurtNote) {
        NoteSkinConfig.Part config = skinConfig.holdCover();
        applyAlpha(config.alpha());
        float guiScale = receptorSize / 104f;
        float pixelScale = 0.7f * guiScale * HOLD_COVER_BASE_SCALE * config.scale();
        holdSplashAtlas.drawScaled(gui, frame,
                x - 12f * guiScale + config.x() * pixelScale,
                y + 15.4f * guiScale + (HOLD_COVER_BASE_Y + config.y()) * pixelScale,
                pixelScale, hurtNote ? fixedTex(hurtHoldSplashRGB)
                        : laneTex(holdSplashRGB, Math.floorMod(lane, 4)));
    }

    /**
     * Funkin (Strumline.hx) places covers with fixed pixel offsets from the
     * receptor: strums are 104px, covers drawn at 0.7 world scale, box shifted
     * (-12, -96) plus INITIAL_OFFSET. Converted to a center-based draw that's
     * (-12, +15.4) receptor-pixels off the receptor center.
     */
    private static void drawCoverFrame(GuiGraphics gui, int lane, SparrowAtlas.Frame f,
                                       float receptorX, float receptorY, float receptorSize,
                                       boolean hurtNote) {
        NoteSkinConfig.Part config = skinConfig.holdCover();
        applyAlpha(config.alpha());
        float g = receptorSize / 104f;
        float pixelScale = 0.7f * g * HOLD_COVER_BASE_SCALE * config.scale();
        coverAtlases[lane].drawScaled(gui, f,
                receptorX - 12f * g + config.x() * pixelScale,
                receptorY + 15.4f * g + (HOLD_COVER_BASE_Y + config.y()) * pixelScale,
                pixelScale, hurtNote ? fixedTex(hurtCoverRGB[lane]) : null);
    }

    /** Number of splash animation variants for a lane (0 = no splashes available). */
    public static int splashVariants(int lane) {
        load();
        return splashAnims[lane] == null ? 0 : splashAnims[lane].size();
    }

    public static int splashFrameCount(int lane, int variant) {
        if (splashAtlas == null || splashAnims[lane] == null || splashAnims[lane].isEmpty()) return 0;
        return splashAtlas.frames(splashAnims[lane].get(variant % splashAnims[lane].size())).size();
    }

    public static void drawSplash(GuiGraphics gui, int lane, int variant, int frameIndex,
                                  float centerX, float centerY, float size) {
        if (splashAtlas == null || splashAnims[lane] == null || splashAnims[lane].isEmpty()) return;
        String anim = splashAnims[lane].get(variant % splashAnims[lane].size());
        var frames = splashAtlas.frames(anim);
        if (frameIndex < 0 || frameIndex >= frames.size()) return;
        NoteSkinConfig.Part config = skinConfig.splash();
        applyAlpha(config.alpha());
        SparrowAtlas.Frame f = frames.get(frameIndex);
        float pixelScale = size * config.scale() / Math.max(1, Math.max(f.frameW, f.frameH));
        splashAtlas.drawScaled(gui, f,
                centerX + config.x() * pixelScale, centerY + config.y() * pixelScale,
                pixelScale, laneTex(splashRGB, lane));
    }

    private static ResourceLocation registerGenerated(String path, NativeImage image) {
        return registerGenerated(path, image, false);
    }

    private static ResourceLocation registerGenerated(String path, NativeImage image, boolean smooth) {
        ResourceLocation id = FnfMod.id(path);
        DynamicTexture tex = null;
        boolean registered = false;
        try {
            tex = new DynamicTexture(image);
            Minecraft.getInstance().getTextureManager().register(id, tex);
            registered = true;
            if (smooth) Textures.smooth(tex); // custom skin art (hold sheet) — arrows stay crisp
            return id;
        } catch (RuntimeException error) {
            if (registered) Minecraft.getInstance().getTextureManager().release(id);
            else if (tex != null) tex.close();
            else image.close();
            throw error;
        }
    }

    /** 32x32 white arrow pointing up. outline=true draws only the border (receptor look). */
    private static NativeImage makeArrow(boolean outline) {
        int size = 32;
        NativeImage img = new NativeImage(size, size, true);
        boolean[][] mask = new boolean[size][size];
        // triangle head rows 2..17, stem rows 17..30
        for (int y = 2; y <= 17; y++) {
            int half = (y - 2);
            for (int x = 15 - half; x <= 16 + half; x++) {
                if (x >= 0 && x < size) mask[y][x] = true;
            }
        }
        for (int y = 17; y <= 29; y++) {
            for (int x = 11; x <= 20; x++) {
                mask[y][x] = true;
            }
        }
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                if (!mask[y][x]) continue;
                boolean edge = y == 0 || y == size - 1 || x == 0 || x == size - 1
                        || !mask[y - 1][x] || !mask[y + 1][x] || !mask[y][x - 1] || !mask[y][x + 1];
                int abgr;
                if (edge) {
                    abgr = 0xFF000000 | (0x30 << 16) | (0x30 << 8) | 0x30; // dark border
                } else {
                    abgr = outline ? 0x50FFFFFF : 0xFFFFFFFF;
                }
                img.setPixelRGBA(x, y, abgr);
            }
        }
        return img;
    }

    // ------------------------------------------------------------------

    public static void drawNote(GuiGraphics gui, int lane, float centerX, float centerY, float size) {
        load();
        NoteSkinConfig.Part config = skinConfig.note();
        applyAlpha(config.alpha());
        if (noteAtlas != null && noteAnims[lane] != null) {
            float pixelScale = size * config.scale() / noteRefPx;
            noteAtlas.drawScaled(gui, noteAtlas.frame(noteAnims[lane], 0),
                    centerX + config.x() * pixelScale, centerY + config.y() * pixelScale,
                    pixelScale, laneTex(noteRGB, lane));
            return;
        }
        float pixelScale = size * config.scale() / 32f;
        drawArrow(gui, arrowTexture, lane,
                centerX + config.x() * pixelScale, centerY + config.y() * pixelScale,
                size * config.scale(),
                missedTint ? 0xFF808080 : LANE_COLORS[lane]);
    }

    /** state: 0 = static, 1 = pressed (no note), 2 = confirm (hit) */
    public static void drawReceptor(GuiGraphics gui, int lane, float centerX, float centerY, float size, int state) {
        load();
        NoteSkinConfig.Part config = skinConfig.receptor();
        applyAlpha(config.alpha());
        if (strumAtlas != null) {
            String anim = switch (state) {
                case 1 -> pressAnims[lane] != null ? pressAnims[lane] : receptorAnims[lane];
                case 2 -> confirmAnims[lane] != null ? confirmAnims[lane] : receptorAnims[lane];
                default -> receptorAnims[lane];
            };
            if (anim != null) {
                // Psych applies the RGB palette to press/confirm but leaves the static frame raw
                ResourceLocation rgb = state == 0 ? null : laneTex(strumRGB, lane);
                float pixelScale = size * config.scale() / noteRefPx;
                strumAtlas.drawScaled(gui, strumAtlas.frame(anim, state == 0 ? 0 : 1),
                        centerX + config.x() * pixelScale, centerY + config.y() * pixelScale,
                        pixelScale, rgb);
                return;
            }
        }
        int color = switch (state) {
            case 1 -> 0xFF808080;
            case 2 -> LANE_COLORS[lane];
            default -> 0xFFB0B0B0;
        };
        float arrowScale = size * (state == 2 ? 1.1f : 1f) / 32f;
        drawArrow(gui, state == 0 ? arrowOutlineTexture : arrowTexture, lane,
                centerX + config.x() * arrowScale, centerY + config.y() * arrowScale,
                size * (state == 2 ? 1.1f : 1f), color);
    }

    /** Gameplay sustain receptor: loops the confirm atlas, holding each frame for two renders. */
    public static void drawSustainReceptor(GuiGraphics gui, int lane, long animationFrame,
                                           float centerX, float centerY, float size) {
        drawConfirmReceptor(gui, lane, animationFrame, true, centerX, centerY, size);
    }

    /** Draws a confirm animation at an explicit 24-FPS frame; taps clamp, holds loop. */
    public static void drawConfirmReceptor(GuiGraphics gui, int lane, long animationFrame,
                                           boolean loop, float centerX, float centerY, float size) {
        load();
        int safeLane = Math.floorMod(lane, 4);
        NoteSkinConfig.Part config = skinConfig.receptor();
        applyAlpha(config.alpha());
        if (strumAtlas != null && confirmAnims[safeLane] != null) {
            var frames = strumAtlas.frames(confirmAnims[safeLane]);
            if (!frames.isEmpty()) {
                int index = loop
                        ? (int) Math.floorMod(animationFrame, (long) frames.size())
                        : (int) Math.min(Math.max(0, animationFrame), frames.size() - 1L);
                var frame = frames.get(index);
                float pixelScale = size * config.scale() / noteRefPx;
                strumAtlas.drawScaled(gui, frame,
                        centerX + config.x() * pixelScale,
                        centerY + config.y() * pixelScale,
                        pixelScale, laneTex(strumRGB, safeLane));
                return;
            }
        }
        drawReceptor(gui, safeLane, centerX, centerY, size, 2);
    }

    /** tailAtTop = downscroll (the sustain's far end points up). */
    public static void drawHoldPiece(GuiGraphics gui, int lane, float centerX, float yTop, float yBottom,
                                     float size, boolean tailAtTop) {
        load();
        if (yBottom <= yTop) return;
        NoteSkinConfig.Part config = skinConfig.sustain();
        applyAlpha(config.alpha());

        HoldSprite piece = holdPieces[lane];
        if (piece == null) {
            float w = size * 0.36f * config.scale();
            float pixelScale = size / Math.max(1f, noteRefPx);
            centerX += config.x() * pixelScale;
            yTop += config.y() * pixelScale;
            yBottom += config.y() * pixelScale;
            int alpha = (int) (255 * drawAlpha) << 24;
            int baseCol = missedTint ? 0x808080 : LANE_COLORS[lane];
            int color = alpha | (baseCol & 0xFFFFFF);
            int dim = alpha | (dimColor(baseCol) & 0xFFFFFF);
            gui.fill((int) (centerX - w / 2), (int) yTop, (int) (centerX + w / 2), (int) yBottom, dim);
            gui.fill((int) (centerX - w / 2) + 1, (int) yTop, (int) (centerX + w / 2) - 1, (int) yBottom, color);
            return;
        }

        HoldSprite end = holdEnds[lane];
        // Sustain body and end use the exact same gui-units-per-source-pixel scale as the
        // note head. Width, tile length and cap height therefore come from their own frames.
        float pixelScale = size * config.scale() / Math.max(1f, noteRefPx);
        float pieceW = Math.max(1f, piece.w() * pixelScale);
        float endW = end == null ? 0f : Math.max(1f, end.w() * pixelScale);
        centerX += config.x() * pixelScale;
        yTop += config.y() * pixelScale;
        yBottom += config.y() * pixelScale;
        float pieceX = centerX - pieceW / 2;
        float endX = centerX - endW / 2;
        float endH = end != null ? end.h() * pixelScale : 0;
        float tileH = Math.max(1, piece.h() * pixelScale);
        ResourceLocation rgbTex = laneTex(holdRGB, lane);

        RenderSystem.enableBlend();
        float maxW = Math.max(pieceW, endW);
        PoseScissor.enable(gui, centerX - maxW / 2 - 1, yTop,
                centerX + maxW / 2 + 1, yBottom + 1);
        float bodyTop = tailAtTop ? yTop + endH : yTop;
        float bodyBottom = tailAtTop ? yBottom : yBottom - endH;
        // anchor the tile pattern to the tail end so the texture scrolls with the
        // chart instead of looking frozen while the trail shrinks into the receptor
        if (tailAtTop) {
            for (float y = bodyTop; y < bodyBottom; y += tileH) {
                blitSprite(gui, piece, pieceX, y, pieceW, tileH + 0.75f, true, rgbTex);
            }
        } else {
            for (float y = bodyBottom; y > bodyTop - tileH; y -= tileH) {
                blitSprite(gui, piece, pieceX, y - tileH, pieceW, tileH + 0.75f, false, rgbTex);
            }
        }
        if (end != null) {
            if (tailAtTop) {
                blitSprite(gui, end, endX, yTop, endW, endH, true, rgbTex);
            } else {
                blitSprite(gui, end, endX, bodyBottom, endW, endH, false, rgbTex);
            }
        }
        gui.disableScissor();
    }

    private static int dimColor(int argb) {
        int r = (argb >> 16) & 0xFF, g = (argb >> 8) & 0xFF, b = argb & 0xFF;
        return 0xFF000000 | ((r / 2) << 16) | ((g / 2) << 8) | (b / 2);
    }

    private static void drawArrow(GuiGraphics gui, ResourceLocation tex, int lane,
                                  float centerX, float centerY, float size, int argb) {
        float r = ((argb >> 16) & 0xFF) / 255f;
        float g = ((argb >> 8) & 0xFF) / 255f;
        float b = (argb & 0xFF) / 255f;
        float a = ((argb >>> 24) & 0xFF) / 255f * drawAlpha;

        gui.pose().pushPose();
        gui.pose().translate(centerX, centerY, 0);
        gui.pose().mulPose(Axis.ZP.rotationDegrees(LANE_ROTATION[lane]));
        float scale = size / 32f;
        gui.pose().scale(scale, scale, 1);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        gui.setColor(r, g, b, a);
        gui.blit(tex, -16, -16, 0, 0, 32, 32, 32, 32);
        gui.setColor(1f, 1f, 1f, 1f);
        gui.pose().popPose();
    }
}
