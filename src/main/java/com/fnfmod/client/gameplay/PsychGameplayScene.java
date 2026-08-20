package com.fnfmod.client.gameplay;

import com.fnfmod.FnfMod;
import com.fnfmod.chart.SongChart;
import com.fnfmod.client.camera.GameplayCamera;
import com.fnfmod.client.math.Easing;
import com.fnfmod.client.render.PsychCanvas;
import com.fnfmod.client.render.AnimateAtlas;
import com.fnfmod.client.render.SparrowAtlas;
import com.fnfmod.client.render.SpriteAtlasCache;
import com.fnfmod.gameplay.PlaybackPolicy;
import com.fnfmod.song.SongEntry;
import com.fnfmod.song.SongLibrary;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Psych character/stage presentation used by the FNF playback profile. */
public final class PsychGameplayScene implements AutoCloseable {
    public static final int CHARACTER_ORDER = 500;
    private static final int SUSTAIN_LOOP_FRAMES = 2;
    private static final String[] DIRECTIONS = {"LEFT", "DOWN", "UP", "RIGHT"};

    private record Animation(List<SparrowAtlas.Frame> frames, double fps, boolean loop,
                             double offsetX, double offsetY, SparrowAtlas sheet) {}

    private record StageLayout(double[] boyfriend, double[] opponent, double[] girlfriend,
                               double[] cameraBoyfriend, double[] cameraOpponent,
                               double[] cameraGirlfriend, double defaultZoom,
                               double cameraSpeed, boolean hideGirlfriend) {
        static StageLayout defaults() {
            return new StageLayout(new double[]{770, 100}, new double[]{100, 100},
                    new double[]{400, 130}, new double[]{0, 0}, new double[]{0, 0},
                    new double[]{0, 0}, 0.9, 1, false);
        }
    }

    private static final class CharacterSprite implements AutoCloseable {
        final SparrowAtlas atlas;
        final Map<String, Animation> animations;
        final double baseX;
        final double baseY;
        final double cameraX;
        final double cameraY;
        final double singDuration;
        final int danceEveryNumBeats;
        final double logicalWidth;
        final double logicalHeight;
        final boolean danceFromSing;
        double x;
        double y;
        double scaleX;
        double scaleY;
        double alpha = 1;
        int color = 0xFFFFFF;
        double objectBorderSize;
        int objectBorderColor;
        double angle;
        double holdTimer;
        double heyTimer;
        boolean visible = true;
        boolean flipX;
        boolean antialiasing;
        boolean specialAnim;
        boolean sustainActive;
        boolean animationFinished;
        boolean danced;
        String animation = "idle";
        String idleSuffix = "";
        int frame;
        double elapsed;

        CharacterSprite(SparrowAtlas atlas, Map<String, Animation> animations,
                        double x, double y, double scale, boolean flipX,
                        double cameraX, double cameraY, double singDuration, int danceEvery,
                        boolean antialiasing, boolean danceFromSing) {
            this.atlas = atlas;
            this.animations = animations;
            this.baseX = this.x = x;
            this.baseY = this.y = y;
            this.scaleX = this.scaleY = scale;
            this.flipX = flipX;
            this.antialiasing = antialiasing;
            this.danceFromSing = danceFromSing;
            for (SparrowAtlas sheet : animationSheets()) sheet.setAntialiasing(antialiasing);
            this.cameraX = cameraX;
            this.cameraY = cameraY;
            this.singDuration = Math.max(0.1, singDuration);
            // Psych alternates danceLeft/danceRight each beat. A regular idle
            // normally restarts every two beats unless JSON/scripts override it.
            this.danceEveryNumBeats = danceEvery > 0 ? danceEvery
                    : has("danceLeft") && has("danceRight") ? 1 : 2;
            this.danced = true;
            if (!has(animation) && !animations.isEmpty()) animation = animations.keySet().iterator().next();
            // Psych assigns atlas frames, applies JSON scale/updateHitbox, then
            // registers/plays animations. Its persistent midpoint therefore uses
            // first atlas frame, not current animation and not largest attack frame.
            SparrowAtlas.Frame referenceFrame = atlas.allFrames().isEmpty()
                    ? null : atlas.allFrames().get(0);
            this.logicalWidth = referenceFrame == null ? 0 : referenceFrame.frameW;
            this.logicalHeight = referenceFrame == null ? 0 : referenceFrame.frameH;
        }

        boolean has(String requested) { return findAnimation(requested) != null; }

        boolean play(String requested, boolean force, boolean special) {
            String match = findAnimation(requested);
            if (match == null || (!force && match.equals(animation) && !finished())) return false;
            animation = match;
            frame = 0;
            elapsed = 0;
            animationFinished = false;
            holdTimer = 0;
            sustainActive = false;
            specialAnim = special;
            // Psych changes danceLeft/danceRight state from sing animations only
            // for GF-style character IDs, not every opponent with two idles.
            if (danceFromSing) {
                if (match.equalsIgnoreCase("singLEFT")) danced = true;
                else if (match.equalsIgnoreCase("singRIGHT")) danced = false;
                else if (match.equalsIgnoreCase("singUP") || match.equalsIgnoreCase("singDOWN")) danced = !danced;
            }
            return true;
        }

        private String findAnimation(String requested) {
            if (requested == null || requested.isBlank()) return null;
            String exact = findExactAnimation(requested);
            if (exact != null) return exact;
            int suffix = requested.indexOf('-');
            if (suffix > 0) return findAnimation(requested.substring(0, suffix));
            return null;
        }

        private String findExactAnimation(String requested) {
            if (animations.containsKey(requested)) return requested;
            for (String key : animations.keySet()) if (key.equalsIgnoreCase(requested)) return key;
            return null;
        }

        void update(double seconds, double stepMs, double playbackRate) {
            Animation current = animations.get(animation);
            if (current == null) return;
            advance(current, seconds * Math.max(0.01, playbackRate));

            if (heyTimer > 0) {
                heyTimer -= seconds * Math.max(0.01, playbackRate);
                if (heyTimer <= 0 && specialAnim
                        && (animation.equalsIgnoreCase("hey") || animation.equalsIgnoreCase("cheer"))) {
                    specialAnim = false;
                    dance(true);
                }
            } else if (finished() && findExactAnimation(animation + "-loop") != null) {
                // Loop companions are exact names in Psych. Suffix fallback here
                // could turn a missing *-loop into the base sing animation forever.
                play(findExactAnimation(animation + "-loop"), true, specialAnim);
            } else if (specialAnim && finished()) {
                specialAnim = false;
                dance(true);
            } else if (animation.toLowerCase(Locale.ROOT).endsWith("miss") && finished()) {
                dance(true);
            } else if (finished() && !isIdleAnimation(animation)
                    && !animation.toLowerCase(Locale.ROOT).startsWith("sing")) {
                dance(true);
            }

            if (animation.toLowerCase(Locale.ROOT).startsWith("sing")) {
                holdTimer += seconds * Math.max(0.01, playbackRate);
                double singSeconds = Math.max(0.001, stepMs) * 0.0011 * singDuration;
                if (!specialAnim && !sustainActive && holdTimer >= singSeconds) dance(true);
            }
        }

        private void advance(Animation current, double seconds) {
            if (current.fps() <= 0 || animationFinished && !current.loop()) return;
            elapsed += seconds;
            double frameTime = 1.0 / current.fps();
            while (elapsed >= frameTime) {
                elapsed -= frameTime;
                int frameLimit = sustainActive && animation.toLowerCase(Locale.ROOT).startsWith("sing")
                        ? Math.min(SUSTAIN_LOOP_FRAMES, current.frames().size()) : current.frames().size();
                if (frame + 1 < frameLimit) frame++;
                else if (sustainActive && frameLimit > 0) {
                    frame = 0;
                    animationFinished = false;
                }
                else if (current.loop()) frame = 0;
                else {
                    animationFinished = true;
                    break;
                }
            }
        }

        boolean finished() {
            return animationFinished;
        }

        private boolean isIdleAnimation(String name) {
            return name.equalsIgnoreCase("idle" + idleSuffix)
                    || name.equalsIgnoreCase("danceLeft" + idleSuffix)
                    || name.equalsIgnoreCase("danceRight" + idleSuffix)
                    || name.equalsIgnoreCase("idle") || name.equalsIgnoreCase("danceLeft")
                    || name.equalsIgnoreCase("danceRight");
        }

        void dance(boolean force) {
            if (specialAnim) return;
            String left = findExactAnimation("danceLeft" + idleSuffix);
            String right = findExactAnimation("danceRight" + idleSuffix);
            if (left == null || right == null) {
                left = findExactAnimation("danceLeft");
                right = findExactAnimation("danceRight");
            }
            if (left != null && right != null) {
                danced = !danced;
                play(danced ? right : left, force, false);
            } else {
                String idle = findExactAnimation("idle" + idleSuffix);
                play(idle == null ? "idle" : idle, force, false);
            }
        }

        void setIdleSuffix(String suffix) {
            idleSuffix = suffix == null ? "" : suffix;
            dance(true);
        }

        void beat(int beat, int speed) {
            if (beat >= 0 && beat % Math.max(1, danceEveryNumBeats * speed) == 0
                    && !specialAnim && !animation.toLowerCase(Locale.ROOT).startsWith("sing")) {
                dance(true);
            }
        }

        void render(GuiGraphics gui) {
            if (!visible || alpha <= 0) return;
            Animation current = animations.get(animation);
            if (current == null || current.frames().isEmpty()) return;
            SparrowAtlas.Frame atlasFrame = current.frames().get(Math.max(0,
                    Math.min(frame, current.frames().size() - 1)));
            current.sheet().prepareFrame(atlasFrame);
            float sx = (float) scaleX;
            float sy = (float) scaleY;
            float cx = (float) (x + atlasFrame.frameW * sx * 0.5 - current.offsetX() * sx);
            float cy = (float) (y + atlasFrame.frameH * sy * 0.5 - current.offsetY() * sy);
            float oldAlpha = SparrowAtlas.globalAlpha;
            float oldTintR = SparrowAtlas.tintR;
            float oldTintG = SparrowAtlas.tintG;
            float oldTintB = SparrowAtlas.tintB;
            SparrowAtlas.globalAlpha = (float) Math.max(0, Math.min(1, alpha));
            int stroke = (int) Math.round(objectBorderSize);
            if (stroke > 0) {
                SparrowAtlas.tintR = ((objectBorderColor >> 16) & 255) / 255f;
                SparrowAtlas.tintG = ((objectBorderColor >> 8) & 255) / 255f;
                SparrowAtlas.tintB = (objectBorderColor & 255) / 255f;
                ResourceLocation mask = current.sheet().silhouetteTexture();
                for (int dx = -stroke; dx <= stroke; dx++) {
                    for (int dy = -stroke; dy <= stroke; dy++) {
                        if (dx == 0 && dy == 0) continue;
                        drawFrame(gui, atlasFrame, current, sx, sy, cx + dx, cy + dy, mask);
                    }
                }
            }
            SparrowAtlas.tintR = oldTintR * ((color >> 16 & 255) / 255f);
            SparrowAtlas.tintG = oldTintG * ((color >> 8 & 255) / 255f);
            SparrowAtlas.tintB = oldTintB * ((color & 255) / 255f);
            drawFrame(gui, atlasFrame, current, sx, sy, cx, cy, null);
            SparrowAtlas.globalAlpha = oldAlpha;
            SparrowAtlas.tintR = oldTintR;
            SparrowAtlas.tintG = oldTintG;
            SparrowAtlas.tintB = oldTintB;
        }

        private void drawFrame(GuiGraphics gui, SparrowAtlas.Frame atlasFrame, Animation current,
                               float sx, float sy, float cx, float cy, ResourceLocation texture) {
            gui.pose().pushPose();
            gui.pose().translate(cx, cy, 0);
            if (angle != 0) gui.pose().mulPose(com.mojang.math.Axis.ZP.rotationDegrees((float) angle));
            gui.pose().scale(flipX ? -1f : 1f, 1f, 1f);
            if (Math.abs(sx) > 0.0001f) gui.pose().scale(1f, sy / sx, 1f);
            current.sheet().drawScaled(gui, atlasFrame, 0, 0, Math.abs(sx), texture);
            gui.pose().popPose();
        }

        void setObjectBorder(double size, int color) {
            objectBorderSize = Double.isFinite(size) ? Math.max(0, size) : 0;
            objectBorderColor = color & 0xFFFFFF;
        }

        double midpointX() {
            return x + logicalWidth * scaleX * 0.5;
        }

        double midpointY() {
            return y + logicalHeight * scaleY * 0.5;
        }

        Object property(String name) {
            return switch (name) {
                case "x" -> x; case "y" -> y; case "alpha" -> alpha; case "angle" -> angle;
                case "visible" -> visible; case "flipX" -> flipX;
                case "color" -> color;
                case "antialiasing" -> antialiasing;
                case "width" -> logicalWidth; case "height" -> logicalHeight;
                case "cameraPosition[0]" -> cameraX; case "cameraPosition[1]" -> cameraY;
                case "scale.x" -> scaleX; case "scale.y" -> scaleY;
                case "specialAnim" -> specialAnim; case "heyTimer" -> heyTimer;
                case "holdTimer" -> holdTimer; case "singDuration" -> singDuration;
                case "danceEveryNumBeats" -> danceEveryNumBeats;
                case "animation.curAnim.name" -> animation;
                case "animation.curAnim" -> animation;
                case "animation.curAnim.curFrame" -> frame;
                case "animation.curAnim.finished" -> finished();
                default -> null;
            };
        }

        boolean setProperty(String name, Object value) {
            double number = value instanceof Number n ? n.doubleValue() : 0;
            switch (name) {
                case "x" -> x = number; case "y" -> y = number; case "alpha" -> alpha = number;
                case "angle" -> angle = number; case "scale.x" -> scaleX = number;
                case "scale.y" -> scaleY = number; case "heyTimer" -> heyTimer = Math.max(0, number);
                case "holdTimer" -> holdTimer = Math.max(0, number);
                case "visible" -> visible = bool(value); case "flipX" -> flipX = bool(value);
                case "color" -> color = (int) ((long) number) & 0xFFFFFF;
                case "antialiasing" -> { antialiasing = bool(value);
                    for (SparrowAtlas sheet : animationSheets()) sheet.setAntialiasing(antialiasing); }
                case "specialAnim" -> specialAnim = bool(value);
                case "animation.curAnim.curFrame" -> {
                    Animation current = animations.get(animation);
                    if (current != null) frame = Math.max(0, Math.min(current.frames().size() - 1, (int) number));
                }
                default -> { return false; }
            }
            return true;
        }

        void reset() {
            x = baseX;
            y = baseY;
            angle = 0;
            alpha = 1;
            color = 0xFFFFFF;
            objectBorderSize = 0;
            objectBorderColor = 0;
            visible = true;
            holdTimer = heyTimer = 0;
            specialAnim = false;
            sustainActive = false;
            idleSuffix = "";
            // dance() toggles before choosing, so start true to play danceLeft first.
            danced = true;
            dance(true);
        }

        private java.util.Set<SparrowAtlas> animationSheets() {
            java.util.Set<SparrowAtlas> sheets = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
            sheets.add(atlas);
            for (Animation animation : animations.values()) sheets.add(animation.sheet());
            return sheets;
        }

        @Override public void close() { for (SparrowAtlas sheet : animationSheets()) sheet.close(); }
    }

    private CharacterSprite boyfriend;
    private CharacterSprite dad;
    private CharacterSprite girlfriend;
    private final StageLayout stage;
    private final PsychStageScene stageScene;
    private final PsychAssetResolver assets;
    private String cameraRole = "boyfriend";
    private double cameraX = PsychCanvas.WIDTH * 0.5;
    private double cameraY = PsychCanvas.HEIGHT * 0.5;
    private double targetCameraX = cameraX;
    private double targetCameraY = cameraY;
    private boolean cameraInitialized;
    private boolean cameraForced;
    private boolean cameraExtendedOffset;
    private boolean cameraExternalOverride;
    private double cameraOffsetEventX;
    private double cameraOffsetEventY;
    private double cameraEventFromX;
    private double cameraEventFromY;
    private long cameraEventTransitionStart;
    private String cameraEventEase = "smooth";
    private int girlfriendDanceSpeed = 1;

    private PsychGameplayScene(CharacterSprite boyfriend, CharacterSprite dad,
                               CharacterSprite girlfriend, StageLayout stage,
                               PsychStageScene stageScene, PsychAssetResolver assets) {
        this.boyfriend = boyfriend;
        this.dad = dad;
        this.girlfriend = girlfriend;
        this.stage = stage;
        this.stageScene = stageScene;
        this.assets = assets;
        updateCameraTarget();
    }

    public static PsychGameplayScene load(SongChart chart, Path songFolder, SongEntry entry,
                                          PlaybackPolicy policy) {
        PsychAssetResolver assets = new PsychAssetResolver(songFolder, entry, policy, chart.stage);
        if (policy == null || !policy.usesPsychCamera()
                || assets.roots(SongLibrary.ExternalContent.IMAGES).isEmpty()
                || assets.roots(SongLibrary.ExternalContent.CHARACTERS).isEmpty()) {
            return new PsychGameplayScene(null, null, null, StageLayout.defaults(),
                    PsychStageScene.load(null, assets), assets);
        }
        StageLayout stage = loadStage(chart.stage, assets);
        PsychStageScene stageScene = loadStageScene(chart.stage, assets);
        CharacterSprite bf = loadCharacter(chart.player1, stage.boyfriend(), assets, true);
        CharacterSprite opponent = loadCharacter(chart.player2, stage.opponent(), assets, false);
        CharacterSprite gf = stage.hideGirlfriend() || chart.player3 == null || chart.player3.isBlank()
                ? null : loadCharacter(chart.player3, stage.girlfriend(), assets, false);
        return new PsychGameplayScene(bf, opponent, gf, stage, stageScene, assets);
    }

    private static PsychStageScene loadStageScene(String id, PsychAssetResolver assets) {
        if (id == null || id.isBlank()) return PsychStageScene.load(null, assets);
        Path json = assets.stage(id);
        if (json == null) return PsychStageScene.load(null, assets);
        try {
            return PsychStageScene.load(JsonParser.parseString(Files.readString(json)).getAsJsonObject(), assets);
        } catch (Exception error) {
            FnfMod.LOGGER.warn("Could not load Psych stage visuals {}: {}", id, error.toString());
            return PsychStageScene.load(null, assets);
        }
    }

    private static StageLayout loadStage(String id, PsychAssetResolver assets) {
        if (id == null || id.isBlank()) return StageLayout.defaults();
        Path json = assets.stage(id);
        if (json == null) return StageLayout.defaults();
        try {
            JsonObject data = JsonParser.parseString(Files.readString(json)).getAsJsonObject();
            return new StageLayout(pair(data, "boyfriend", 770, 100),
                    pair(data, "opponent", 100, 100), pair(data, "girlfriend", 400, 130),
                    pair(data, "camera_boyfriend", 0, 0), pair(data, "camera_opponent", 0, 0),
                    pair(data, "camera_girlfriend", 0, 0), number(data, "defaultZoom", 0.9),
                    number(data, "camera_speed", 1), bool(data, "hide_girlfriend", false));
        } catch (Exception error) {
            FnfMod.LOGGER.warn("Could not load Psych stage {}: {}", id, error.toString());
            return StageLayout.defaults();
        }
    }

    private static CharacterSprite loadCharacter(String id, double[] stagePosition,
                                                  PsychAssetResolver assets, boolean playerSide) {
        if (id == null || id.isBlank()) return null;
        Path json = assets.character(id);
        if (json == null) return null;
        SparrowAtlas loadedAtlas = null;
        try {
            JsonObject data = JsonParser.parseString(Files.readString(json)).getAsJsonObject();
            boolean antialiasing = characterAntialiasing(data);
            String renderType = string(data, "renderType", "").toLowerCase(Locale.ROOT);
            String assetPath = string(data, "assetPath", "");
            boolean animate = renderType.contains("animateatlas") || !assetPath.isBlank();
            if (animate) {
                Path folder = AnimateAtlas.resolveFolder(json, assetPath);
                loadedAtlas = AnimateAtlas.load(folder);
            } else {
                Path png = assets.image(string(data, "image", ""));
                if (png == null) return null;
                Path xml = png.resolveSibling(stripExtension(png.getFileName().toString()) + ".xml");
                loadedAtlas = SpriteAtlasCache.acquire(png, xml, antialiasing);
            }
            if (loadedAtlas == null) return null;
            SparrowAtlas atlas = loadedAtlas;
            Map<String, Animation> animations = readAnimations(data, atlas, json, assetPath, antialiasing);
            if (animations.isEmpty()) {
                atlas.close();
                loadedAtlas = null;
                return null;
            }
            double[] position = data.has("position") ? pair(data, "position", 0, 0)
                    : pair(data, "offsets", 0, 0);
            double[] camera = data.has("camera_position") ? pair(data, "camera_position", 0, 0)
                    : pair(data, "cameraOffsets", 0, 0);
            CharacterSprite sprite = new CharacterSprite(atlas, animations,
                    stagePosition[0] + position[0], stagePosition[1] + position[1],
                    number(data, "scale", 1), (data.has("flipX")
                    ? bool(data, "flipX", false) : bool(data, "flip_x", false)) != playerSide,
                    camera[0], camera[1], data.has("singTime")
                    ? number(data, "singTime", 4) : number(data, "sing_duration", 4),
                    (int) (data.has("danceEvery") ? number(data, "danceEvery", 0)
                    : number(data, "dance_every", 0)), antialiasing,
                    id.equalsIgnoreCase("gf") || id.toLowerCase(Locale.ROOT).startsWith("gf-"));
            loadedAtlas = null;
            return sprite;
        } catch (Exception error) {
            if (loadedAtlas != null) loadedAtlas.close();
            FnfMod.LOGGER.warn("Could not load Psych character {}: {}", id, error.toString());
            return null;
        }
    }

    private static Map<String, Animation> readAnimations(JsonObject data, SparrowAtlas atlas,
                                                          Path characterJson, String baseAssetPath,
                                                          boolean antialiasing) {
        Map<String, Animation> animations = new LinkedHashMap<>();
        Map<String, SparrowAtlas> sheets = new LinkedHashMap<>();
        sheets.put(baseAssetPath == null ? "" : baseAssetPath, atlas);
        JsonArray list = data.has("animations") && data.get("animations").isJsonArray()
                ? data.getAsJsonArray("animations") : new JsonArray();
        for (JsonElement element : list) {
            if (!element.isJsonObject()) continue;
            JsonObject anim = element.getAsJsonObject();
            String name = string(anim, "anim", string(anim, "name", ""));
            String prefix = string(anim, "prefix", string(anim, "name", name));
            SparrowAtlas sheet = atlas;
            String animationAsset = string(anim, "assetPath", "");
            if (!animationAsset.isBlank() && !animationAsset.equals(baseAssetPath)) {
                sheet = sheets.get(animationAsset);
                if (sheet == null) {
                    sheet = AnimateAtlas.load(AnimateAtlas.resolveFolder(characterJson, animationAsset));
                    if (sheet != null) {
                        sheet.setAntialiasing(antialiasing);
                        sheets.put(animationAsset, sheet);
                    }
                }
                if (sheet == null) continue;
            }
            List<SparrowAtlas.Frame> frames;
            if (string(anim, "animType", "").equalsIgnoreCase("symbol")) {
                frames = new ArrayList<>(sheet.framesBySymbol(prefix));
            } else if (anim.has("indices") && anim.get("indices").isJsonArray()
                    && !anim.getAsJsonArray("indices").isEmpty()) {
                List<Integer> indices = new ArrayList<>();
                for (JsonElement index : anim.getAsJsonArray("indices")) {
                    try { indices.add(index.getAsInt()); } catch (Exception ignored) {}
                }
                frames = new ArrayList<>(sheet.framesByIndices(prefix, indices));
            } else {
                frames = new ArrayList<>(sheet.framesByPrefix(prefix));
            }
            if (name.isBlank() || frames.isEmpty()) continue;
            double[] offsets = pair(anim, "offsets", 0, 0);
            animations.put(name, new Animation(List.copyOf(frames), number(anim, "fps", 24),
                    anim.has("looped") ? bool(anim, "looped", false) : bool(anim, "loop", false),
                    offsets[0], offsets[1], sheet));
        }
        java.util.Set<SparrowAtlas> used = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        used.add(atlas);
        for (Animation animation : animations.values()) used.add(animation.sheet());
        for (SparrowAtlas sheet : sheets.values()) if (!used.contains(sheet)) sheet.close();
        return animations;
    }

    private static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? name : name.substring(0, dot);
    }

    private static String string(JsonObject object, String key, String fallback) {
        try { return object.has(key) ? object.get(key).getAsString() : fallback; }
        catch (Exception ignored) { return fallback; }
    }

    private static double number(JsonObject object, String key, double fallback) {
        try { return object.has(key) ? object.get(key).getAsDouble() : fallback; }
        catch (Exception ignored) { return fallback; }
    }

    private static boolean bool(JsonObject object, String key, boolean fallback) {
        try { return object.has(key) ? object.get(key).getAsBoolean() : fallback; }
        catch (Exception ignored) { return fallback; }
    }

    /** Psych stores this as an inverted no_antialiasing flag. */
    private static boolean characterAntialiasing(JsonObject object) {
        if (object.has("isPixel")) return !bool(object, "isPixel", false);
        if (object.has("no_antialiasing")) return !bool(object, "no_antialiasing", false);
        if (object.has("noAntialiasing")) return !bool(object, "noAntialiasing", false);
        // Compatibility with older Blockified files that used a direct flag.
        return bool(object, "antialiasing", true);
    }

    private static boolean bool(Object value) {
        if (value instanceof Boolean b) return b;
        if (value instanceof Number n) return n.doubleValue() != 0;
        return Boolean.parseBoolean(String.valueOf(value));
    }

    private static double[] pair(JsonObject object, String key, double x, double y) {
        try {
            JsonArray array = object.getAsJsonArray(key);
            return new double[]{array.get(0).getAsDouble(), array.get(1).getAsDouble()};
        } catch (Exception ignored) {
            return new double[]{x, y};
        }
    }

    public boolean hasCharacters() { return boyfriend != null || dad != null || girlfriend != null; }
    public float defaultZoom() { return (float) Math.max(0.1, stage.defaultZoom()); }
    public double cameraX() { return cameraX; }
    public double cameraY() { return cameraY; }
    public double targetCameraX() { return targetCameraX; }
    public double targetCameraY() { return targetCameraY; }
    public void setTargetCameraX(double value) { if (!cameraExternalOverride) targetCameraX = value; }
    public void setTargetCameraY(double value) { if (!cameraExternalOverride) targetCameraY = value; }
    public void setCameraX(double value) { if (!cameraExternalOverride) { cameraX = value; cameraInitialized = true; } }
    public void setCameraY(double value) { if (!cameraExternalOverride) { cameraY = value; cameraInitialized = true; } }

    public void forceCamera(Double x, Double y) {
        cameraEventTransitionStart = 0;
        if (x == null && y == null) {
            cameraForced = false;
            cameraExtendedOffset = false;
            cameraExternalOverride = false;
            cameraOffsetEventX = cameraOffsetEventY = 0;
            updateCameraTarget();
            return;
        }
        cameraExtendedOffset = false;
        cameraExternalOverride = false;
        cameraForced = true;
        targetCameraX = x == null ? 0 : x;
        targetCameraY = y == null ? 0 : y;
    }

    /** Blockified 3D extension mirrored onto camGame as center-relative offsets. */
    public void forceCameraExtended(Double x, Double y, boolean overrideMovement, String easing) {
        cameraEventFromX = cameraX;
        cameraEventFromY = cameraY;
        cameraEventTransitionStart = System.currentTimeMillis();
        cameraEventEase = GameplayCamera.normalizeCameraEase(easing);
        cameraOffsetEventX = x == null ? 0 : x * 128.0;
        cameraOffsetEventY = y == null ? 0 : -y * 128.0;
        cameraExtendedOffset = true;
        cameraExternalOverride = overrideMovement;
        cameraForced = overrideMovement;
        if (overrideMovement) {
            targetCameraX = PsychCanvas.WIDTH * 0.5 + cameraOffsetEventX;
            targetCameraY = PsychCanvas.HEIGHT * 0.5 + cameraOffsetEventY;
        } else {
            updateCameraTarget();
        }
    }

    public void update(double seconds, double stepMs, double playbackRate) {
        if (boyfriend != null) boyfriend.update(seconds, stepMs, playbackRate);
        if (dad != null) dad.update(seconds, stepMs, playbackRate);
        if (girlfriend != null) girlfriend.update(seconds, stepMs, playbackRate);
        updateCameraTarget();
        double follow = 1 - Math.exp(-seconds * 2.45 * Math.max(0.01, stage.cameraSpeed()));
        if (!cameraInitialized) {
            cameraX = targetCameraX;
            cameraY = targetCameraY;
            cameraInitialized = true;
        } else if (cameraEventTransitionStart != 0) {
            double progress = (System.currentTimeMillis() - cameraEventTransitionStart) / 500.0;
            double eased = Easing.apply(cameraEventEase, progress);
            cameraX = cameraEventFromX + (targetCameraX - cameraEventFromX) * eased;
            cameraY = cameraEventFromY + (targetCameraY - cameraEventFromY) * eased;
            if (progress >= 1) cameraEventTransitionStart = 0;
        } else {
            cameraX += (targetCameraX - cameraX) * follow;
            cameraY += (targetCameraY - cameraY) * follow;
        }
    }

    public void beat(int beat) {
        if (boyfriend != null) boyfriend.beat(beat, 1);
        if (dad != null) dad.beat(beat, 1);
        if (girlfriend != null) girlfriend.beat(beat, girlfriendDanceSpeed);
    }

    public void focus(String role) {
        CharacterSprite target = sprite(role);
        if (target == null) return;
        cameraRole = canonicalRole(role);
        updateCameraTarget();
    }

    private void updateCameraTarget() {
        if (cameraForced) return;
        CharacterSprite target = sprite(cameraRole);
        if (target == null) return;
        switch (cameraRole) {
            case "dad" -> {
                targetCameraX = target.midpointX() + 150 + target.cameraX + stage.cameraOpponent()[0];
                targetCameraY = target.midpointY() - 100 + target.cameraY + stage.cameraOpponent()[1];
            }
            case "gf" -> {
                targetCameraX = target.midpointX() + target.cameraX + stage.cameraGirlfriend()[0];
                targetCameraY = target.midpointY() + target.cameraY + stage.cameraGirlfriend()[1];
            }
            default -> {
                targetCameraX = target.midpointX() - 100 - target.cameraX + stage.cameraBoyfriend()[0];
                targetCameraY = target.midpointY() - 100 + target.cameraY + stage.cameraBoyfriend()[1];
            }
        }
        if (cameraExtendedOffset) {
            targetCameraX += cameraOffsetEventX;
            targetCameraY += cameraOffsetEventY;
        }
    }

    public void sing(boolean playerSide, int lane, boolean miss, String suffix) {
        sing(playerSide ? "boyfriend" : "dad", lane, miss, suffix);
    }

    public void sing(String role, int lane, boolean miss, String suffix) {
        if (lane < 0 || lane >= DIRECTIONS.length) return;
        CharacterSprite target = sprite(role);
        if (target == null) return;
        String requested = "sing" + DIRECTIONS[lane] + (miss ? "miss" : "")
                + (suffix == null ? "" : suffix);
        target.play(requested, true, false);
    }

    public void hold(String role, int lane, String suffix) {
        if (lane < 0 || lane >= DIRECTIONS.length) return;
        CharacterSprite target = sprite(role);
        if (target == null) return;
        String base = "sing" + DIRECTIONS[lane] + (suffix == null ? "" : suffix);
        String hold = target.findExactAnimation(base + "-hold");
        if (hold == null) hold = target.findExactAnimation(base + "-loop");
        if (hold == null) {
            String singing = target.findExactAnimation(base);
            if (singing == null && suffix != null && !suffix.isBlank()) {
                singing = target.findExactAnimation("sing" + DIRECTIONS[lane]);
            }
            if (singing == null || !singing.equalsIgnoreCase(target.animation)) return;
        } else if (!hold.equalsIgnoreCase(target.animation)) {
            target.play(hold, true, false);
        }
        target.holdTimer = 0;
        target.sustainActive = true;
    }

    public void endHold(String role) {
        CharacterSprite target = sprite(role);
        if (target == null || !target.sustainActive) return;
        target.sustainActive = false;
        // Last sustain refresh resets holdTimer. Let Psych's independent
        // BPM-derived sing timer finish instead of snapping to idle on release.
        // If it already expired while over-held, update() returns immediately.
    }

    public void hey(String role, double durationSeconds) {
        CharacterSprite target = sprite(role);
        if (target == null) return;
        boolean girlfriendRole = "gf".equals(canonicalRole(role));
        String requested = girlfriendRole && target.has("cheer") ? "cheer"
                : target.has("hey") ? "hey" : target.has("cheer") ? "cheer" : "idle";
        if (target.play(requested, true, !requested.equals("idle"))) {
            target.heyTimer = Math.max(0.01, durationSeconds);
        }
    }

    public boolean playSpecialAnimation(String role, String animation) {
        CharacterSprite target = sprite(role);
        return target != null && target.play(animation, true, true);
    }

    public void setGirlfriendDanceSpeed(int speed) {
        girlfriendDanceSpeed = Math.max(1, speed);
    }

    public void setIdleSuffix(String role, String suffix) {
        CharacterSprite target = sprite(role);
        if (target != null) target.setIdleSuffix(suffix);
    }

    public boolean changeCharacter(String role, String characterId) {
        String canonical = canonicalRole(role);
        double[] position = switch (canonical) {
            case "dad" -> stage.opponent();
            case "gf" -> stage.girlfriend();
            default -> stage.boyfriend();
        };
        CharacterSprite replacement = loadCharacter(characterId, position, assets,
                canonical.equals("boyfriend"));
        if (replacement == null) return false;
        CharacterSprite previous;
        switch (canonical) {
            case "dad" -> { previous = dad; dad = replacement; }
            case "gf" -> { previous = girlfriend; girlfriend = replacement; }
            default -> { previous = boyfriend; boyfriend = replacement; }
        }
        if (previous != null) previous.close();
        updateCameraTarget();
        return true;
    }

    public boolean playAnimation(String role, String animation, boolean force) {
        CharacterSprite target = sprite(role);
        return target != null && target.play(animation, force, false);
    }

    public boolean dance(String role) {
        CharacterSprite target = sprite(role);
        if (target == null) return false;
        target.dance(true);
        return true;
    }

    /** Stage JSON objects created before character markers. */
    public void renderBackground(GuiGraphics gui) {
        PsychCanvas.push(gui, GameplayCamera.gameZoom(),
                cameraX - GameplayCamera.gameShakeX(), cameraY - GameplayCamera.gameShakeY());
        for (PsychStageScene.Element element : stageScene.elements()) {
            if (element.isRole()) break;
            element.render(gui, cameraX, cameraY);
        }
        PsychCanvas.pop(gui);
    }

    /** Characters and stage JSON foreground objects created after character markers. */
    public void renderCharactersAndForeground(GuiGraphics gui) {
        PsychCanvas.push(gui, GameplayCamera.gameZoom(),
                cameraX - GameplayCamera.gameShakeX(), cameraY - GameplayCamera.gameShakeY());
        boolean gfDrawn = false, dadDrawn = false, boyfriendDrawn = false;
        boolean reachedCharacters = false;
        for (PsychStageScene.Element element : stageScene.elements()) {
            switch (element.type) {
                case "gf" -> { reachedCharacters = true; if (girlfriend != null) girlfriend.render(gui); gfDrawn = true; }
                case "dad" -> { reachedCharacters = true; if (dad != null) dad.render(gui); dadDrawn = true; }
                case "boyfriend" -> { reachedCharacters = true; if (boyfriend != null) boyfriend.render(gui); boyfriendDrawn = true; }
                default -> { if (reachedCharacters) element.render(gui, cameraX, cameraY); }
            }
        }
        if (!gfDrawn && girlfriend != null) girlfriend.render(gui);
        if (!dadDrawn && dad != null) dad.render(gui);
        if (!boyfriendDrawn && boyfriend != null) boyfriend.render(gui);
        PsychCanvas.pop(gui);
    }

    public void render(GuiGraphics gui) {
        renderBackground(gui);
        renderCharactersAndForeground(gui);
    }

    public double midpointX(String role) {
        CharacterSprite target = sprite(role);
        return target == null ? 0 : target.midpointX();
    }

    public double midpointY(String role) {
        CharacterSprite target = sprite(role);
        return target == null ? 0 : target.midpointY();
    }

    /** Stage-defined starting position, before events or Lua moved the character. */
    public double defaultX(String role) {
        CharacterSprite target = sprite(role);
        return target == null ? 0 : target.baseX;
    }

    public double defaultY(String role) {
        CharacterSprite target = sprite(role);
        return target == null ? 0 : target.baseY;
    }

    public double characterCamera(String role, int axis) {
        CharacterSprite target = sprite(role);
        if (target == null) return 0;
        return axis == 0 ? target.cameraX : target.cameraY;
    }

    public double stageCameraOffset(String role, int axis) {
        double[] value = switch (canonicalRole(role)) {
            case "dad" -> stage.cameraOpponent();
            case "gf" -> stage.cameraGirlfriend();
            default -> stage.cameraBoyfriend();
        };
        return value[Math.max(0, Math.min(1, axis))];
    }

    public Object property(String path) {
        CharacterSprite target = sprite(path);
        int dot = path == null ? -1 : path.indexOf('.');
        return target == null || dot < 0 ? null : target.property(characterProperty(path.substring(dot + 1)));
    }

    public boolean setProperty(String path, Object value) {
        CharacterSprite target = sprite(path);
        int dot = path == null ? -1 : path.indexOf('.');
        return target != null && dot >= 0
                && target.setProperty(characterProperty(path.substring(dot + 1)), value);
    }

    public double characterX(String role) {
        CharacterSprite target = sprite(role);
        return target == null ? 0 : target.x;
    }

    public double characterY(String role) {
        CharacterSprite target = sprite(role);
        return target == null ? 0 : target.y;
    }

    public boolean setCharacterX(String role, double value) {
        CharacterSprite target = sprite(role);
        if (target == null) return false;
        target.x = value;
        return true;
    }

    public boolean setCharacterY(String role, double value) {
        CharacterSprite target = sprite(role);
        if (target == null) return false;
        target.y = value;
        return true;
    }

    /** setObjectBorder for Psych-rendered bf/dad/gf character sprites. */
    public boolean setObjectBorder(String role, double size, int color) {
        CharacterSprite target = sprite(role);
        if (target == null) return false;
        target.setObjectBorder(size, color);
        return true;
    }

    private CharacterSprite sprite(String raw) {
        return switch (canonicalRole(raw)) {
            case "boyfriend" -> boyfriend;
            case "dad" -> dad;
            case "gf" -> girlfriend;
            default -> null;
        };
    }

    private static String canonicalRole(String raw) {
        if (raw == null) return "";
        String tag = raw.contains(".") ? raw.substring(0, raw.indexOf('.')) : raw;
        return switch (tag.trim().toLowerCase(Locale.ROOT)) {
            case "boyfriend", "boyfriendgroup", "bf", "player", "0" -> "boyfriend";
            case "dad", "dadgroup", "opponent", "opponentgroup", "1" -> "dad";
            case "gf", "gfgroup", "girlfriend", "girlfriendgroup", "speakers", "2" -> "gf";
            default -> tag.trim().toLowerCase(Locale.ROOT);
        };
    }

    private static String characterProperty(String raw) {
        String property = raw == null ? "" : raw;
        // FlxTypedSpriteGroup access used by many Psych scripts.
        if (property.startsWith("members[0].")) property = property.substring("members[0].".length());
        return property;
    }

    public void reset() {
        if (boyfriend != null) boyfriend.reset();
        if (dad != null) dad.reset();
        if (girlfriend != null) girlfriend.reset();
        cameraRole = "boyfriend";
        cameraInitialized = false;
        cameraForced = false;
        cameraExtendedOffset = false;
        cameraExternalOverride = false;
        cameraOffsetEventX = cameraOffsetEventY = 0;
        cameraEventTransitionStart = 0;
        girlfriendDanceSpeed = 1;
        updateCameraTarget();
    }

    @Override public void close() {
        if (boyfriend != null) boyfriend.close();
        if (dad != null) dad.close();
        if (girlfriend != null) girlfriend.close();
        stageScene.close();
    }
}
