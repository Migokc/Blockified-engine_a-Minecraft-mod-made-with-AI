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
                heads[lane] = atlas.findAnimation(COLORS[lane], "note" + CAPS[lane],
                        COLORS[lane] + " alone", DIRECTIONS[lane] + " note",
                        "note" + DIRECTIONS[lane].toUpperCase(Locale.ROOT));
                receptors[lane][0] = atlas.findAnimation(
                        "arrow" + DIRECTIONS[lane].toUpperCase(Locale.ROOT),
                        DIRECTIONS[lane] + " static", DIRECTIONS[lane] + " receptor");
                receptors[lane][1] = atlas.findAnimation(
                        DIRECTIONS[lane] + " press", DIRECTIONS[lane] + " pressed");
                receptors[lane][2] = atlas.findAnimation(
                        DIRECTIONS[lane] + " confirm", DIRECTIONS[lane] + " confirmed");
                pieces[lane] = atlas.findAnimation(COLORS[lane] + " hold piece",
                        COLORS[lane] + " hold", DIRECTIONS[lane] + " hold piece");
                ends[lane] = atlas.findAnimation(COLORS[lane] + " hold end",
                        COLORS[lane] + " end hold",
                        lane == 0 ? "pruple end hold" : COLORS[lane] + " hold end");
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
    }

    private final Path songFolder;
    private final Path modRoot;
    private final boolean enabled;
    private final Map<String, Style> styles = new HashMap<>();
    private final Set<String> missing = new HashSet<>();

    public PsychNoteTextureCache(Path songFolder, Path modRoot, boolean enabled) {
        this.songFolder = normalize(songFolder);
        this.modRoot = normalize(modRoot);
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
        for (Path root : new Path[]{songFolder, modRoot}) {
            if (root == null) continue;
            for (String prefix : new String[]{"sounds", "assets/sounds", ""}) {
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
            FnfMod.LOGGER.warn("Custom note texture {} needs matching PNG and XML files", rawTexture);
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
        for (Path root : new Path[]{songFolder, modRoot}) {
            if (root == null) continue;
            for (String prefix : new String[]{"images", "assets/images", ""}) {
                Path base = prefix.isBlank() ? root : root.resolve(prefix);
                Path candidate = base.resolve(relative).normalize();
                if (candidate.startsWith(root) && Files.isRegularFile(candidate)) return candidate;
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
