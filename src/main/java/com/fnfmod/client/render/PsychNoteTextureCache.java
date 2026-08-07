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
        float referenceSize = 155;

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
                if (head != null) referenceSize = Math.max(referenceSize,
                        Math.max(head.frameW, head.frameH));
                List<String> variants = new ArrayList<>();
                for (String animation : atlas.animationNames()) {
                    String lower = animation.toLowerCase(Locale.ROOT);
                    boolean splash = lower.contains("splash") || lower.contains("impact");
                    boolean laneMatch = lower.contains(COLORS[lane]) || lower.contains(DIRECTIONS[lane]);
                    if (splash && laneMatch) variants.add(animation);
                }
                Collections.sort(variants);
                splashes[lane] = variants;
            }
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
        Style style = style(texture);
        if (style == null) return false;
        int safeLane = Math.floorMod(lane, 4);
        String animation = style.receptors[safeLane][2];
        if (animation == null) return false;
        SparrowAtlas.Frame frame = style.loopedFrame(animation, animationFrame);
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
        Style style = style(texture);
        if (style == null) return false;
        List<String> variants = style.splashes[Math.floorMod(lane, 4)];
        if (variants.isEmpty()) return false;
        List<SparrowAtlas.Frame> frames = style.atlas.frames(
                variants.get(Math.floorMod(variant, variants.size())));
        if (frameIndex < 0 || frameIndex >= frames.size()) return false;
        SparrowAtlas.Frame frame = frames.get(frameIndex);
        NoteStyle.prepareCustomNoteDraw();
        style.atlas.drawScaled(gui, frame, centerX, centerY,
                size / Math.max(1, Math.max(frame.frameW, frame.frameH)));
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
        Path png = resolve(texture + ".png");
        Path xml = resolve(texture + ".xml");
        if (png == null || xml == null) return null;
        return new Path[]{png, xml, resolve(texture + ".json")};
    }

    private Style style(String rawTexture) {
        if (!enabled || rawTexture == null || rawTexture.isBlank()) return null;
        String texture = stripExtension(rawTexture.trim().replace('\\', '/'));
        String key = texture.toLowerCase(Locale.ROOT);
        if (styles.containsKey(key)) return styles.get(key);
        if (missing.contains(key)) return null;

        Path png = resolve(texture + ".png");
        Path xml = resolve(texture + ".xml");
        if (png == null || xml == null) {
            missing.add(key);
            FnfMod.LOGGER.warn("Custom note texture {} needs matching PNG and XML files in {}",
                    rawTexture, roots);
            return null;
        }
        SparrowAtlas atlas = SparrowAtlas.load(png, xml);
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
