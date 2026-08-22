package com.fnfmod.client.render;

import com.fnfmod.FnfMod;
import net.minecraft.client.gui.GuiGraphics;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Per-song cache and renderer for Psych custom note Sparrow atlases. */
public final class PsychNoteTextureCache implements AutoCloseable {
    private static final String[] COLORS = {"purple", "blue", "green", "red"};
    private static final String[] DIRECTIONS = {"left", "down", "up", "right"};
    private static final String[] CAPS = {"Left", "Down", "Up", "Right"};

    private static final class Style {
        final SparrowAtlas atlas;
        final String[] heads = new String[4];
        final String[][] receptors = new String[4][3];
        final String[] pieces = new String[4];
        final String[] ends = new String[4];
        @SuppressWarnings("unchecked")
        final List<String>[] splashes = new List[4];
        float referenceSize;

        Style(SparrowAtlas atlas) {
            this.atlas = atlas;
            for (int lane = 0; lane < 4; lane++) {
                heads[lane] = find(atlas, COLORS[lane], COLORS[lane] + "Scroll",
                        "note" + CAPS[lane],
                        COLORS[lane] + " alone", DIRECTIONS[lane] + " note",
                        "note" + DIRECTIONS[lane].toUpperCase(Locale.ROOT));
                receptors[lane][0] = find(atlas,
                        "arrow" + DIRECTIONS[lane].toUpperCase(Locale.ROOT),
                        DIRECTIONS[lane] + " static", DIRECTIONS[lane] + " receptor");
                receptors[lane][1] = find(atlas,
                        DIRECTIONS[lane] + " press", DIRECTIONS[lane] + " pressed");
                receptors[lane][2] = find(atlas,
                        DIRECTIONS[lane] + " confirm", DIRECTIONS[lane] + " confirmed");
                pieces[lane] = find(atlas, COLORS[lane] + " hold piece",
                        COLORS[lane] + "hold", COLORS[lane] + " hold",
                        DIRECTIONS[lane] + " hold piece", DIRECTIONS[lane] + "hold");
                ends[lane] = find(atlas, COLORS[lane] + " hold end",
                        COLORS[lane] + "holdend",
                        COLORS[lane] + " end hold",
                        lane == 0 ? "pruple end hold" : COLORS[lane] + " hold end");
                if (heads[lane] == null) heads[lane] = findLaneAnimation(atlas, lane, Part.HEAD);
                if (pieces[lane] == null) pieces[lane] = findLaneAnimation(atlas, lane, Part.HOLD);
                if (ends[lane] == null) ends[lane] = findLaneAnimation(atlas, lane, Part.END);
                SparrowAtlas.Frame head = frame(heads[lane]);
                if (head != null && referenceSize <= 0) {
                    referenceSize = Math.max(1, Math.max(head.frameW, head.frameH));
                }
                List<String> variants = new ArrayList<>();
                List<String> genericVariants = new ArrayList<>();
                for (String animation : atlas.animationNames()) {
                    String lower = animation.toLowerCase(Locale.ROOT);
                    boolean splash = lower.contains("splash") || lower.contains("impact");
                    boolean laneMatch = lower.contains(COLORS[lane]) || lower.contains(DIRECTIONS[lane]);
                    if (splash) {
                        genericVariants.add(animation);
                        if (laneMatch) variants.add(animation);
                    }
                }
                if (variants.isEmpty()) variants.addAll(genericVariants);
                Collections.sort(variants);
                splashes[lane] = variants;
            }
            if (referenceSize <= 0) referenceSize = 155;
        }

        SparrowAtlas.Frame frame(String animation) {
            return animation == null ? null : atlas.frame(animation, 0);
        }

        SparrowAtlas.Frame loopedFrame(String animation, long index) {
            if (animation == null) return null;
            List<SparrowAtlas.Frame> frames = atlas.frames(animation);
            if (frames.isEmpty()) return null;
            return frames.get((int) Math.floorMod(index, (long) frames.size()));
        }

        private enum Part { HEAD, HOLD, END }

        /**
         * Psych's addByPrefix is exact, but mods use inconsistent capitalization
         * and descriptive prefixes. Preserve exact matches first, then accept the
         * same prefix case-insensitively so otherwise-valid atlases still render.
         */
        private static String find(SparrowAtlas atlas, String... candidates) {
            String exact = atlas.findAnimation(candidates);
            if (exact != null) return exact;
            for (String candidate : candidates) {
                for (String animation : atlas.animationNames()) {
                    if (animation.equalsIgnoreCase(candidate)) return animation;
                }
            }
            return null;
        }

        private static String findLaneAnimation(SparrowAtlas atlas, int lane, Part part) {
            List<String> matches = new ArrayList<>();
            for (String animation : atlas.animationNames()) {
                String lower = animation.toLowerCase(Locale.ROOT);
                if (!containsWord(lower, COLORS[lane]) && !containsWord(lower, DIRECTIONS[lane])) continue;
                boolean hold = lower.contains("hold") || lower.contains("sustain");
                boolean end = lower.contains("end") || lower.contains("tail");
                boolean excludedHead = lower.contains("splash") || lower.contains("impact")
                        || lower.contains("static") || lower.contains("press")
                        || lower.contains("confirm") || lower.contains("receptor")
                        || lower.startsWith("arrow");
                boolean accepted = switch (part) {
                    case HEAD -> !hold && !end && !excludedHead;
                    case HOLD -> hold && !end;
                    case END -> hold && end;
                };
                if (accepted) matches.add(animation);
            }
            Collections.sort(matches, String.CASE_INSENSITIVE_ORDER);
            return matches.isEmpty() ? null : matches.get(0);
        }

        private static boolean containsWord(String text, String word) {
            int from = 0;
            while (true) {
                int at = text.indexOf(word, from);
                if (at < 0) return false;
                boolean before = at == 0 || !Character.isLetterOrDigit(text.charAt(at - 1));
                int afterIndex = at + word.length();
                boolean after = afterIndex >= text.length()
                        || !Character.isLetterOrDigit(text.charAt(afterIndex));
                if (before && after) return true;
                from = at + 1;
            }
        }
    }

    private final List<Path> roots;
    private final boolean enabled;
    private final Map<String, Style> styles = new HashMap<>();
    private final Set<String> missing = new HashSet<>();

    public PsychNoteTextureCache(Path songFolder, Path modRoot, boolean enabled) {
        this(java.util.Arrays.asList(songFolder, modRoot), enabled);
    }

    public PsychNoteTextureCache(List<Path> roots, boolean enabled) {
        List<Path> normalized = new ArrayList<>();
        if (roots != null) {
            for (Path root : roots) {
                Path value = normalize(root);
                if (value != null && Files.isDirectory(value) && !normalized.contains(value)) {
                    normalized.add(value);
                }
            }
        }
        this.roots = List.copyOf(normalized);
        this.enabled = enabled;
    }

    public boolean drawNote(GuiGraphics gui, String texture, int lane,
                            float centerX, float centerY, float size) {
        Style style = style(texture);
        if (style == null) return false;
        SparrowAtlas.Frame frame = style.frame(style.heads[Math.floorMod(lane, 4)]);
        if (frame == null) return false;
        NoteStyle.prepareCustomNoteDraw();
        style.atlas.drawScaled(gui, frame, centerX, centerY, size / Math.max(1, style.referenceSize));
        return true;
    }

    public boolean drawReceptor(GuiGraphics gui, String texture, int lane, int state,
                                float centerX, float centerY, float size) {
        Style style = style(texture);
        if (style == null) return false;
        int safeLane = Math.floorMod(lane, 4);
        String animation = style.receptors[safeLane][Math.max(0, Math.min(2, state))];
        if (animation == null && state != 0) animation = style.receptors[safeLane][0];
        SparrowAtlas.Frame frame = style.frame(animation);
        if (frame == null) return false;
        NoteStyle.prepareCustomNoteDraw();
        style.atlas.drawScaled(gui, frame, centerX, centerY,
                size / Math.max(1, Math.max(frame.frameW, frame.frameH)));
        return true;
    }

    /** Custom-skin confirm animation used only while a sustain is actively held. */
    public boolean drawSustainReceptor(GuiGraphics gui, String texture, int lane,
                                       long animationFrame, float centerX, float centerY, float size) {
        return drawConfirmReceptor(gui, texture, lane, animationFrame, true, centerX, centerY, size);
    }

    /** Draws a confirm animation at an explicit 24-FPS frame; taps clamp, holds loop. */
    public boolean drawConfirmReceptor(GuiGraphics gui, String texture, int lane,
                                       long animationFrame, boolean loop,
                                       float centerX, float centerY, float size) {
        Style style = style(texture);
        if (style == null) return false;
        int safeLane = Math.floorMod(lane, 4);
        String animation = style.receptors[safeLane][2];
        if (animation == null) return false;
        List<SparrowAtlas.Frame> frames = style.atlas.frames(animation);
        if (frames.isEmpty()) return false;
        int index = loop
                ? (int) Math.floorMod(animationFrame, (long) frames.size())
                : (int) Math.min(Math.max(0, animationFrame), frames.size() - 1L);
        SparrowAtlas.Frame frame = frames.get(index);
        if (frame == null) return false;
        NoteStyle.prepareCustomNoteDraw();
        style.atlas.drawScaled(gui, frame, centerX, centerY,
                size / Math.max(1, Math.max(frame.frameW, frame.frameH)));
        return true;
    }

    public boolean drawHold(GuiGraphics gui, String texture, int lane, float centerX,
                            float yTop, float yBottom, float size, boolean downscroll) {
        Style style = style(texture);
        if (style == null || yBottom <= yTop) return false;
        int safeLane = Math.floorMod(lane, 4);
        SparrowAtlas.Frame piece = style.frame(style.pieces[safeLane]);
        SparrowAtlas.Frame end = style.frame(style.ends[safeLane]);
        if (piece == null && end == null) return false;

        NoteStyle.prepareCustomNoteDraw();
        float scale = size / Math.max(1, style.referenceSize);
        float tileHeight = piece == null ? 0 : Math.max(1, piece.frameH * scale);
        float endHeight = end == null ? 0 : Math.max(1, end.frameH * scale);

        PoseScissor.enable(gui, centerX - size, yTop, centerX + size, yBottom);
        gui.pose().pushPose();
        if (downscroll) {
            gui.pose().translate(0, yTop + yBottom, 0);
            gui.pose().scale(1, -1, 1);
        }
        float bodyBottom = Math.max(yTop, yBottom - endHeight);
        if (piece != null) {
            for (float y = yTop; y < bodyBottom + tileHeight; y += tileHeight) {
                style.atlas.drawScaled(gui, piece, centerX, y + tileHeight * 0.5f, scale);
            }
        }
        if (end != null) {
            style.atlas.drawScaled(gui, end, centerX, yBottom - endHeight * 0.5f, scale);
        }
        gui.pose().popPose();
        gui.disableScissor();
        return true;
    }

    /** Resolves Psych's sounds/name path for a note-specific hitsound. */
    public Path resolveSound(String rawSound) {
        if (!enabled) return null;
        if (rawSound == null || rawSound.isBlank() || rawSound.equalsIgnoreCase("hitsound")) return null;
        String sound = stripExtension(rawSound.trim().replace('\\', '/')) + ".ogg";
        for (Path root : roots) {
            if (root == null) continue;
            for (String prefix : new String[]{"sounds", "shared/sounds", "assets/sounds",
                    "assets/shared/sounds", ""}) {
                Path base = prefix.isBlank() ? root : root.resolve(prefix);
                Path candidate = base.resolve(sound).normalize();
                if (candidate.startsWith(root) && Files.isRegularFile(candidate)) return candidate;
            }
        }
        return null;
    }

    public int splashVariants(String texture, int lane) {
        Style style = style(texture);
        return style == null ? 0 : style.splashes[Math.floorMod(lane, 4)].size();
    }

    public int splashFrames(String texture, int lane, int variant) {
        Style style = style(texture);
        if (style == null) return 0;
        List<String> variants = style.splashes[Math.floorMod(lane, 4)];
        if (variants.isEmpty()) return 0;
        return style.atlas.frames(variants.get(Math.floorMod(variant, variants.size()))).size();
    }

    public boolean drawSplash(GuiGraphics gui, String texture, int lane, int variant, int frameIndex,
                              float centerX, float centerY, float size) {
        return drawSplash(gui, texture, lane, variant, frameIndex, centerX, centerY, size,
                NoteSkinConfig.DEFAULT.splash());
    }

    /** Draws an external/chart splash using the active note skin's transforms. */
    public boolean drawSplash(GuiGraphics gui, String texture, int lane, int variant, int frameIndex,
                              float centerX, float centerY, float size, NoteSkinConfig.Part config) {
        Style style = style(texture);
        if (style == null) return false;
        List<String> variants = style.splashes[Math.floorMod(lane, 4)];
        if (variants.isEmpty()) return false;
        List<SparrowAtlas.Frame> frames = style.atlas.frames(
                variants.get(Math.floorMod(variant, variants.size())));
        if (frameIndex < 0 || frameIndex >= frames.size()) return false;
        SparrowAtlas.Frame frame = frames.get(frameIndex);
        NoteSkinConfig.Part transform = config == null ? NoteSkinConfig.DEFAULT.splash() : config;
        NoteStyle.prepareCustomSplashDraw(transform.alpha());
        float pixelScale = size * transform.scale()
                / Math.max(1, Math.max(frame.frameW, frame.frameH));
        style.atlas.drawScaled(gui, frame,
                centerX + transform.x() * pixelScale,
                centerY + transform.y() * pixelScale, pixelScale);
        return true;
    }

    /**
     * Resolves a chart arrowSkin name to its {@code {png, xml, json}} files (json may
     * be null), or null when the PNG/XML pair is missing. Lets NoteStyle render the
     * song's arrowSkin through the same RGB/sustain pipeline as selectable skins.
     */
    public Path[] resolveSkinFiles(String rawTexture) {
        if (rawTexture == null || rawTexture.isBlank()) return null;
        String texture = stripExtension(rawTexture.trim().replace('\\', '/'));
        Path folder = resolveDirectory(texture);
        if (folder != null && folderHasNoteAtlas(folder)) {
            return new Path[]{folder, null, folder.resolve("skin.json")};
        }
        Path png = resolve(texture + ".png");
        Path xml = resolve(texture + ".xml");
        if (png != null && xml != null) {
            Path json = resolve(texture + ".json");
            if (json == null) {
                json = png.resolveSibling(stripExtension(png.getFileName().toString()) + ".json");
            }
            return new Path[]{png, xml, json};
        }
        // A valid Psych pixel skin may ship only images/pixelUI/<arrowSkin>.png.
        // Return its corresponding classic location even when that pair is absent;
        // NoteStyle uses it as the stable name/path from which to find the XML-less grid.
        String pixelTexture = texture.regionMatches(true, 0, "pixelUI/", 0, 8)
                ? texture : "pixelUI/" + texture;
        Path pixel = resolve(pixelTexture + ".png");
        Path classic = classicPathForPixel(pixel);
        if (classic == null) return null;
        String stem = stripExtension(classic.getFileName().toString());
        Path json = resolve(texture + ".json");
        if (json == null) json = classic.resolveSibling(stem + ".json");
        return new Path[]{classic, classic.resolveSibling(stem + ".xml"), json};
    }

    /** Backward-compatible lookup of Psych's standard shared sustain cover. */
    public Path[] resolveHoldSplashFiles(boolean pixelUi) {
        return resolveHoldSplashFiles("", pixelUi);
    }

    /** Resolves an explicit hold-cover atlas/stem/folder, or Psych's default when blank. */
    public Path[] resolveHoldSplashFiles(String rawTexture, boolean pixelUi) {
        String base = rawTexture == null || rawTexture.isBlank()
                ? "noteSplashes/holdSplashes/holdSplash"
                : stripExtension(rawTexture.trim().replace('\\', '/'));
        Path folder = resolveDirectory(base);
        if (folder != null) {
            Path[] normal = folderAtlasPair(folder, "holdSplash", "holdCover");
            Path[] selected = normal;
            if (pixelUi) {
                String normalStem = normal == null ? "holdSplash" : stripExtension(normal[0].getFileName().toString());
                Path[] pixel = folderAtlasPair(folder.resolve("pixelUI"), normalStem, "holdSplash", "holdCover");
                if (pixel != null) selected = pixel;
            }
            return selected;
        }
        Path png = resolve(base + ".png");
        Path xml = resolve(base + ".xml");
        if (pixelUi) {
            Path pixelPng = resolvePixel(base + ".png");
            Path pixelXml = resolvePixel(base + ".xml");
            if (pixelPng != null && pixelXml != null) {
                png = pixelPng;
                xml = pixelXml;
            }
        }
        return png != null && xml != null ? new Path[]{png, xml} : null;
    }

    private static Path classicPathForPixel(Path pixel) {
        if (pixel == null) return null;
        Path cursor = pixel.getParent();
        while (cursor != null && cursor.getFileName() != null) {
            if (cursor.getFileName().toString().equalsIgnoreCase("pixelUI")) {
                Path parent = cursor.getParent();
                return parent == null ? null : parent.resolve(cursor.relativize(pixel)).normalize();
            }
            cursor = cursor.getParent();
        }
        return null;
    }

    private Style style(String rawTexture) {
        if (!enabled || rawTexture == null || rawTexture.isBlank()) return null;
        String texture = stripExtension(rawTexture.trim().replace('\\', '/'));
        boolean pixel = NoteStyle.pixelUi();
        String key = (pixel ? "pixel:" : "normal:") + texture.toLowerCase(Locale.ROOT);
        if (styles.containsKey(key)) return styles.get(key);
        if (missing.contains(key)) return null;

        Path png;
        Path xml;
        Path folder = resolveDirectory(texture);
        if (folder != null) {
            String folderName = folder.getFileName() == null ? "" : folder.getFileName().toString();
            Path[] pair = folderAtlasPair(folder, folderName, "NOTE_assets", "noteSplashes", "splash");
            if (pixel) {
                String stem = pair == null ? folderName : stripExtension(pair[0].getFileName().toString());
                Path[] pixelPair = folderAtlasPair(folder.resolve("pixelUI"), stem,
                        folderName, "NOTE_assets", "noteSplashes", "splash");
                if (pixelPair != null) pair = pixelPair;
            }
            png = pair == null ? null : pair[0];
            xml = pair == null ? null : pair[1];
        } else {
            png = resolve(texture + ".png");
            xml = resolve(texture + ".xml");
            if (pixel) {
                Path pixelPng = resolvePixel(texture + ".png");
                Path pixelXml = resolvePixel(texture + ".xml");
                if (pixelPng != null && pixelXml != null) {
                    png = pixelPng;
                    xml = pixelXml;
                }
            }
        }
        if (png == null || xml == null) {
            missing.add(key);
            FnfMod.LOGGER.warn("Custom note texture {} needs matching PNG and XML files in {}",
                    rawTexture, roots);
            return null;
        }
        SparrowAtlas atlas = SparrowAtlas.load(png, xml, !pixel);
        if (atlas == null) {
            missing.add(key);
            return null;
        }
        Style style = new Style(atlas);
        styles.put(key, style);
        return style;
    }

    private Path resolve(String relative) {
        for (Path root : roots) {
            for (String prefix : new String[]{"images", "shared/images", "assets/images",
                    "assets/shared/images", ""}) {
                Path base = prefix.isBlank() ? root : root.resolve(prefix);
                Path candidate = base.resolve(relative).normalize();
                if (candidate.startsWith(root) && Files.isRegularFile(candidate)) return candidate;
                // Psych 1.0 stores defaults such as noteSplashes as
                // images/noteSplashes/noteSplashes.{png,xml}.
                int slash = relative.lastIndexOf('/');
                int dot = relative.lastIndexOf('.');
                if (slash < 0 && dot > 0) {
                    String name = relative.substring(0, dot);
                    Path nested = base.resolve(name).resolve(relative).normalize();
                    if (nested.startsWith(root) && Files.isRegularFile(nested)) return nested;
                }
            }
        }
        return null;
    }

    /** Directory equivalent of resolve(), confined to this song/mod's permitted roots. */
    private Path resolveDirectory(String relative) {
        if (relative == null || relative.isBlank()) return null;
        for (Path root : roots) {
            for (String prefix : new String[]{"images", "shared/images", "assets/images",
                    "assets/shared/images", ""}) {
                Path base = prefix.isBlank() ? root : root.resolve(prefix);
                Path candidate = base.resolve(relative).normalize();
                if (candidate.startsWith(root) && Files.isDirectory(candidate)) return candidate;
            }
        }
        return null;
    }

    private static Path firstRegular(Path path) {
        return path != null && Files.isRegularFile(path) ? path : null;
    }

    private static Path namedIgnoreCase(Path folder, String fileName) {
        if (folder == null || !Files.isDirectory(folder)) return null;
        try (var files = Files.list(folder)) {
            return files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().equalsIgnoreCase(fileName))
                    .findFirst().orElse(null);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static Path[] folderAtlasPair(Path folder, String... preferredStems) {
        if (folder == null || !Files.isDirectory(folder)) return null;
        for (String stem : preferredStems) {
            if (stem == null || stem.isBlank()) continue;
            Path png = namedIgnoreCase(folder, stem + ".png");
            Path xml = namedIgnoreCase(folder, stem + ".xml");
            if (png != null && xml != null) return new Path[]{png, xml};
        }
        try (var files = Files.list(folder)) {
            for (Path png : files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".png"))
                    .sorted().toList()) {
                String stem = stripExtension(png.getFileName().toString());
                Path xml = namedIgnoreCase(folder, stem + ".xml");
                if (xml != null) return new Path[]{png, xml};
            }
        } catch (Exception ignored) {}
        return null;
    }

    private static boolean folderHasNoteAtlas(Path folder) {
        if (folder == null || !Files.isDirectory(folder)) return false;
        String folderName = folder.getFileName() == null ? "" : folder.getFileName().toString();
        if (namedIgnoreCase(folder, "NOTE_assets.png") != null
                && namedIgnoreCase(folder, "NOTE_assets.xml") != null
                || !folderName.isBlank() && namedIgnoreCase(folder, folderName + ".png") != null
                && namedIgnoreCase(folder, folderName + ".xml") != null) return true;
        return hasNoteAssetsVariant(folder, true)
                || firstRegular(folder.resolve("notes.png")) != null
                && firstRegular(folder.resolve("notes.xml")) != null
                || firstRegular(folder.resolve("noteStrumline.png")) != null
                && firstRegular(folder.resolve("noteStrumline.xml")) != null
                || folderAtlasPair(folder.resolve("pixelUI"), "NOTE_assets", folderName) != null;
    }

    private static boolean hasNoteAssetsVariant(Path folder, boolean requireXml) {
        if (folder == null || !Files.isDirectory(folder)) return false;
        try (var files = Files.list(folder)) {
            return files.filter(Files::isRegularFile).anyMatch(path -> {
                String name = path.getFileName().toString();
                String lower = name.toLowerCase(Locale.ROOT);
                if (!lower.startsWith("note_assets-") || !lower.endsWith(".png")) return false;
                if (!requireXml) return true;
                String stem = stripExtension(name);
                return namedIgnoreCase(folder, stem + ".xml") != null;
            });
        } catch (Exception ignored) {
            return false;
        }
    }

    /** Psych pixel counterpart: images/pixelUI/<same relative path>. */
    private Path resolvePixel(String relative) {
        String safe = relative.replace('\\', '/');
        for (Path root : roots) {
            for (String prefix : new String[]{"images/pixelUI", "shared/images/pixelUI",
                    "assets/images/pixelUI", "assets/shared/images/pixelUI", "pixelUI"}) {
                Path base = root.resolve(prefix);
                Path candidate = base.resolve(safe).normalize();
                if (candidate.startsWith(root) && Files.isRegularFile(candidate)) return candidate;
                // Common flat request "foo" may be stored under pixelUI/noteSplashes/foo.
                if (!safe.contains("/")) {
                    Path nested = base.resolve("noteSplashes").resolve(safe).normalize();
                    if (nested.startsWith(root) && Files.isRegularFile(nested)) return nested;
                }
            }
        }
        return null;
    }

    private static Path normalize(Path path) {
        return path == null ? null : path.toAbsolutePath().normalize();
    }

    private static String stripExtension(String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".png") || lower.endsWith(".xml") || lower.endsWith(".ogg")) {
            return value.substring(0, value.length() - 4);
        }
        return value;
    }

    @Override
    public void close() {
        for (Style style : styles.values()) style.atlas.close();
        styles.clear();
        missing.clear();
    }
}
