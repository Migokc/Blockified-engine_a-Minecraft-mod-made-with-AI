package com.fnfmod.client.gameplay;

import com.fnfmod.FnfMod;
import com.fnfmod.client.anim.CharacterAnimations;
import com.fnfmod.client.anim.ExtraCharacterRoster;
import com.fnfmod.client.render.LuaWorldObject;
import com.fnfmod.client.render.LuaWorldObjectRenderer;
import com.fnfmod.client.render.OverlayLines;
import com.fnfmod.client.render.SparrowAtlas;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Free-camera scene editor state: a list of user-placed objects (sprites, spritesheet
 * sprites, 2D/3D characters, text) that can be positioned in the stage world, selected,
 * and later exported as Psych/Blockified Lua.
 *
 * <p>Positions are stored in Lua world-camera pixels relative to the speakers/machine
 * centre (64 px = 1 block, x = stage-right, y = down, z = stage-forward) — the exact
 * space the copied Lua uses — so rendering reuses {@link LuaWorldObjectRenderer} and the
 * export is a direct read of these fields.
 *
 * <p>Part 1: model, add/select/delete and world rendering with a selection highlight and
 * centre handle. Transform tools (G/R/S), the drag gizmo and Lua export arrive in later
 * parts.
 */
public final class FreeCamObjects {

    /** Human-readable first line that opts a generated Lua object into safe parsing. */
    private static final Pattern PASTE_HEADER = Pattern.compile(
            "^--\\s*(Sprite|Spritesheet \\(XML\\)|Graph|2D character|3D character|Text)"
                    + "\\s+'([A-Za-z0-9_]+)'\\s+\\(world camera\\)\\s*$");
    private static final Pattern LUA_CALL = Pattern.compile("^([A-Za-z_][A-Za-z0-9_]*)\\((.*)\\)\\s*$");
    private static final int MAX_PASTE_LENGTH = 131_072;

    public enum Type {
        SPRITE("Sprite"),
        SPRITESHEET("Spritesheet (XML)"),
        GRAPH("Graph"),
        CHARACTER_2D("2D character"),
        CHARACTER_3D("3D character"),
        TEXT("Text");

        public final String label;
        Type(String label) { this.label = label; }
    }

    /** Where an editable object actually lives. Linked objects remain owned/rendered there. */
    public enum Source {
        EDITOR,
        LUA_RUNTIME,
        EXTRA_CHARACTER,
        MAIN_CHARACTER
    }

    /** One placed object. Public fields keep the later transform tools and export simple. */
    public static final class Obj {
        public final Type type;
        public String tag;
        public Source source = Source.EDITOR;
        /** Stable tag in the source registry. It deliberately does not change when copied. */
        public String sourceTag = "";
        private long syncedFingerprint = Long.MIN_VALUE;
        public double x, y, z;              // world-camera pixels from the speakers centre
        public double rotX, rotY, rotZ;     // degrees (pitch, yaw, roll)
        public double scaleX = 1, scaleY = 1, scaleZ = 1;
        public double alpha = 1;
        public int color = 0xFFFFFF;
        public boolean visible = true;
        // World modifiers, matching the runtime 1:1. Note: in this engine "shadows"
        // is an alias of lighting (one toggle), not a separate field.
        public boolean billboard = true;   // worldBillboard
        public boolean lighting = true;    // worldLighting (a.k.a. shadows)
        public boolean seeThrough = false; // worldSeeThrough
        public boolean antialiasing = true;

        // Type-specific data.
        public String texturePath = "";     // sprite / spritesheet image (mod-relative)
        public String text = "Text";
        public int textSize = 24;
        // Text styling (mirrors the 2D Lua text: border/outline, alignment, italic).
        public String borderStyle = "none";   // none / outline / shadow
        public double borderSize = 1;
        public int borderColor = 0x000000;
        public String textAlign = "left";      // left / center / right
        public boolean italic = false;
        public String characterDef = "";     // 2D/3D character definition name
        public String characterRole = "player";
        public double width = 64, height = 64; // sprite draw size in px
        // Live preview texture from a picked image (editor only, not exported).
        public ResourceLocation textureId;
        public int imgW = 16, imgH = 16;
        // Loaded spritesheet preview (Sparrow atlas + animations), editor only.
        public FreeCam2DCharacter character;
        // Loaded 2D character (real Psych character with sing/dance behaviour).
        public WorldCharacter worldChar;
        // 3D character (BBS performer) desired animation for the live preview.
        public String anim3d = "idle";
        /** Runtime-owned animation names when no editor preview asset is loaded. */
        public List<String> availableAnimations = List.of();
        // Animated-sprite playback (addAnimationByPrefix fps + loop).
        public int fps = 24;
        public boolean loop = true;

        Obj(Type type) { this.type = type; }

        Obj copy() {
            Obj c = new Obj(type);
            c.tag = tag;
            c.source = source;
            c.sourceTag = sourceTag;
            c.x = x; c.y = y; c.z = z;
            c.rotX = rotX; c.rotY = rotY; c.rotZ = rotZ;
            c.scaleX = scaleX; c.scaleY = scaleY; c.scaleZ = scaleZ;
            c.alpha = alpha; c.color = color;
            c.visible = visible;
            c.billboard = billboard; c.lighting = lighting;
            c.seeThrough = seeThrough; c.antialiasing = antialiasing;
            c.texturePath = texturePath; c.text = text; c.textSize = textSize;
            c.characterDef = characterDef; c.width = width; c.height = height;
            c.characterRole = characterRole;
            c.borderStyle = borderStyle; c.borderSize = borderSize; c.borderColor = borderColor;
            c.textAlign = textAlign; c.italic = italic;
            c.textureId = textureId; c.imgW = imgW; c.imgH = imgH;
            c.character = character;
            c.worldChar = worldChar;
            c.anim3d = anim3d;
            c.availableAnimations = availableAnimations;
            c.fps = fps;
            c.loop = loop;
            return c;
        }

        public boolean linked() { return source != Source.EDITOR; }

        public String sourceKey() {
            return source.name() + ":" + sourceTag.toLowerCase(Locale.ROOT);
        }
    }

    private static final ResourceLocation WHITE =
            ResourceLocation.withDefaultNamespace("textures/misc/white.png");

    private final List<Obj> objects = new ArrayList<>();
    private int selected = -1;
    private int counter;
    private int assetCounter;

    // Live BBS performers for 3D-character previews (client-only RemotePlayers).
    private ExtraCharacterRoster roster;
    private final java.util.Set<String> livePerformers = new java.util.HashSet<>();
    private final java.util.Map<String, String> performerAnim = new java.util.HashMap<>();
    private long lastRenderNano;

    // --- modal transform (Blender-style G / R / S) ---
    public enum Mode { NONE, MOVE, ROTATE, SCALE }
    private Mode mode = Mode.NONE;
    private int axis;            // 0 = free, 1 = X, 2 = Y, 3 = Z
    private boolean plane;       // shift+axis: constrain to all axes except the chosen one
    private boolean trackball;   // double-tap R: free camera-relative rotate
    private String numericInput = "";
    private double startMouseX, startMouseY;
    private double bX, bY, bZ, bRotX, bRotY, bRotZ, bScaleX, bScaleY, bScaleZ;

    // --- face-snap drag (centre cube) ---
    private boolean snapDragging;

    // --- undo / redo history ---
    private record Snap(List<Obj> objs, int sel) {}
    private final java.util.Deque<Snap> undoStack = new java.util.ArrayDeque<>();
    private final java.util.Deque<Snap> redoStack = new java.util.ArrayDeque<>();
    private Snap pending;   // full state captured when a transform / drag starts

    public List<Obj> objects() { return objects; }
    public List<Obj> linkedObjects() {
        if (objects.isEmpty()) return List.of();
        List<Obj> linked = new ArrayList<>();
        for (Obj o : objects) if (o.linked()) linked.add(o);
        return Collections.unmodifiableList(linked);
    }
    public int linkedCount() {
        int count = 0;
        for (Obj o : objects) if (o.linked()) count++;
        return count;
    }
    public boolean hasSelection() { return selected >= 0 && selected < objects.size(); }
    public Obj selected() { return hasSelection() ? objects.get(selected) : null; }

    public String selectionLabel() {
        Obj o = selected();
        return o == null ? "none" : o.tag + " (" + o.type.label
                + (o.linked() ? ", existing" : "") + ")";
    }

    /** Drops editor adapters only; their source objects stay alive and keep every applied edit. */
    public void clearLinked() {
        if (objects.isEmpty()) return;
        for (Obj o : objects) if (o.linked()) {
            releaseTexture(o);
            if (o.character != null) { o.character.close(); o.character = null; }
            if (o.worldChar != null) { o.worldChar.close(); o.worldChar = null; }
        }
        objects.removeIf(Obj::linked);
        selected = -1;
        // Old history may contain adapters from a previous runtime snapshot.
        undoStack.clear();
        redoStack.clear();
        resetTransform();
    }

    /** Creates one non-rendering adapter for an existing runtime object. */
    public Obj link(Type type, Source source, String sourceTag) {
        if (source == null || source == Source.EDITOR || sourceTag == null || sourceTag.isBlank()) return null;
        String key = source.name() + ":" + sourceTag.toLowerCase(Locale.ROOT);
        for (Obj existing : objects) if (existing.sourceKey().equals(key)) return existing;
        Obj o = new Obj(type);
        applyTypeDefaults(o);
        o.source = source;
        o.sourceTag = sourceTag;
        o.tag = sourceTag;
        objects.add(o);
        return o;
    }

    public void selectLinked(String sourceKey) {
        if (sourceKey == null) return;
        for (int i = 0; i < objects.size(); i++) {
            if (objects.get(i).linked() && objects.get(i).sourceKey().equals(sourceKey)) {
                selected = i;
                resetTransform();
                return;
            }
        }
    }

    /** Marks a newly populated adapter as matching its source, avoiding needless writes. */
    public void markLinkedClean(Obj o) {
        if (o != null && o.linked()) o.syncedFingerprint = fingerprint(o);
    }

    /** Applies changed adapters only; idle free cam performs no runtime mutations. */
    public void forEachChangedLinked(Consumer<Obj> action) {
        if (action == null) return;
        for (Obj o : objects) {
            if (!o.linked()) continue;
            long current = fingerprint(o);
            if (current == o.syncedFingerprint) continue;
            action.accept(o);
            o.syncedFingerprint = current;
        }
    }

    private static long fingerprint(Obj o) {
        long h = 0xcbf29ce484222325L;
        h = fp(h, o.type.ordinal()); h = fp(h, o.tag); h = fp(h, o.texturePath);
        h = fp(h, o.text); h = fp(h, o.characterDef); h = fp(h, o.characterRole);
        h = fp(h, o.anim3d); h = fp(h, o.borderStyle); h = fp(h, o.textAlign);
        h = fp(h, o.x); h = fp(h, o.y); h = fp(h, o.z);
        h = fp(h, o.rotX); h = fp(h, o.rotY); h = fp(h, o.rotZ);
        h = fp(h, o.scaleX); h = fp(h, o.scaleY); h = fp(h, o.scaleZ); h = fp(h, o.alpha);
        h = fp(h, o.width); h = fp(h, o.height); h = fp(h, o.borderSize);
        h = fp(h, o.color); h = fp(h, o.borderColor); h = fp(h, o.textSize); h = fp(h, o.fps);
        h = fp(h, o.visible); h = fp(h, o.billboard); h = fp(h, o.lighting);
        h = fp(h, o.seeThrough); h = fp(h, o.antialiasing); h = fp(h, o.italic); h = fp(h, o.loop);
        return h;
    }

    private static long fp(long hash, String value) { return fp(hash, value == null ? 0 : value.hashCode()); }
    private static long fp(long hash, double value) { return fp(hash, Double.doubleToLongBits(value)); }
    private static long fp(long hash, boolean value) { return fp(hash, value ? 1 : 0); }
    private static long fp(long hash, long value) {
        hash ^= value;
        return hash * 0x100000001b3L;
    }

    /** Creates an object 4 blocks in front of the camera and selects it. */
    public Obj add(Type type, Camera camera, BlockPos speakers, Direction facing) {
        pushUndo();
        Obj o = new Obj(type);
        do { o.tag = "object" + (++counter); } while (tagExists(o.tag));
        Vec3 look = new Vec3(camera.getLookVector());
        Vec3 target = camera.getPosition().add(look.scale(4.0));
        double[] px = worldToPixels(target, speakers, facing);
        o.x = round(px[0]);
        o.y = round(px[1]);
        o.z = round(px[2]);

        applyTypeDefaults(o);
        objects.add(o);
        selected = objects.size() - 1;
        return o;
    }

    public void deleteSelected() {
        if (hasSelection()) {
            // Keep the texture registered so an undo can restore this object.
            pushUndo();
            objects.remove(selected);
            selected = -1;
        }
        resetTransform();
    }

    public void clear() {
        for (Obj o : objects) {
            releaseTexture(o);
            if (o.character != null) { o.character.close(); o.character = null; }
            if (o.worldChar != null) { o.worldChar.close(); o.worldChar = null; }
        }
        objects.clear();
        selected = -1;
        counter = 0;
        undoStack.clear();
        redoStack.clear();
        despawnPerformers();
        resetTransform();
    }

    // --- asset picking ---

    /** Loads a picked image as this object's live preview and derives its Lua path. */
    public void setSpriteImage(Path file) {
        Obj o = selected();
        if (o == null || file == null) return;
        pushUndo();
        loadSpriteImage(o, file);
    }

    /** Loads preview art without adding another undo entry (also used by paste). */
    private void loadSpriteImage(Obj o, Path file) {
        // A spritesheet loads its sibling XML as a Sparrow atlas so it previews and animates.
        if (o.type == Type.SPRITESHEET) {
            FreeCam2DCharacter preview = FreeCam2DCharacter.fromAtlas(file, siblingXml(file));
            if (preview != null) {
                preview.setPlayback(o.fps, o.loop);
                o.character = preview;
                o.width = preview.refW();
                o.height = preview.refH();
                o.texturePath = deriveLuaImagePath(file);
                applyAntialiasing(o);
                return;
            }
            FnfMod.LOGGER.warn("No sibling XML atlas for {}; showing the sheet statically", file);
        }
        try {
            NativeImage image;
            try (var in = java.nio.file.Files.newInputStream(file)) { image = NativeImage.read(in); }
            DynamicTexture tex = new DynamicTexture(image);
            releaseTexture(o);
            ResourceLocation id = FnfMod.id("freecam_asset/" + (++assetCounter));
            Minecraft.getInstance().getTextureManager().register(id, tex);
            o.textureId = id;
            o.imgW = image.getWidth();
            o.imgH = image.getHeight();
            o.width = image.getWidth();
            o.height = image.getHeight();
            o.texturePath = deriveLuaImagePath(file);
            applyAntialiasing(o);
        } catch (Exception error) {
            FnfMod.LOGGER.warn("Failed to load picked image {}: {}", file, error.toString());
        }
    }

    /** Uses a picked character JSON's base file name as the definition name, and (for a
     *  2D character) loads its Sparrow atlas + animations as a live preview. */
    public void setCharacterDefFile(Path file) {
        Obj o = selected();
        if (o == null || file == null) return;
        pushUndo();
        String base = file.getFileName().toString();
        int dot = base.lastIndexOf('.');
        base = dot > 0 ? base.substring(0, dot) : base;
        // A user-config definition (config/fnfmod/animations) resolves with a global: prefix;
        // otherwise the active mod's definition of that name is used.
        boolean userConfig = file.toString().replace('\\', '/').toLowerCase(java.util.Locale.ROOT)
                .contains("/fnfmod/animations/");
        // 2D Psych characters resolve by plain character id; 3D anim defs may be global:.
        o.characterDef = o.type == Type.CHARACTER_2D ? base
                : (userConfig ? "global:" + base : base);
        if (o.type == Type.CHARACTER_2D) {
            WorldCharacter loaded = WorldCharacter.load(file);
            if (loaded != null) {
                o.worldChar = loaded;
                o.width = loaded.refW();
                o.height = loaded.refH();
                applyAntialiasing(o);
            } else {
                FnfMod.LOGGER.warn("No 2D-character preview for {} (missing image/atlas?)", file);
            }
        }
    }

    /** Animation names for the selected character/sheet (or 3D def actions). */
    public List<String> characterAnimations() {
        Obj o = selected();
        if (o == null) return List.of();
        if (o.worldChar != null) return o.worldChar.animationNames();
        if (o.character != null) return o.character.animationNames();
        if (o.linked() && !o.availableAnimations.isEmpty()) return o.availableAnimations;
        if (o.type == Type.CHARACTER_3D && !o.characterDef.isBlank()) {
            return CharacterAnimations.actionNames(o.characterDef, o.characterRole);
        }
        return List.of();
    }

    public String characterCurrentAnim() {
        Obj o = selected();
        if (o == null) return "";
        if (o.worldChar != null) return o.worldChar.current();
        if (o.character != null) return o.character.current();
        if (o.type == Type.CHARACTER_3D || o.linked()) return o.anim3d;
        return "";
    }

    public void playCharacterAnim(String name) {
        Obj o = selected();
        if (o == null || name == null) return;
        if (o.worldChar != null) o.worldChar.play(name, true);
        else if (o.character != null) o.character.play(name);
        else if (o.type == Type.CHARACTER_3D || o.linked()) o.anim3d = name; // applied next tick
    }

    public void setText(String text) {
        Obj o = selected();
        if (o != null && text != null) { pushUndo(); o.text = text; }
    }

    public void setGraphColor(int rgb) {
        Obj o = selected();
        if (o != null) { pushUndo(); o.color = rgb & 0xFFFFFF; }
    }

    /** Pushes one undo step before a live colour-picker drag begins. */
    public void beginColorEdit() {
        if (hasSelection()) pushUndo();
    }

    /** Sets the colour without a new undo step (used during a picker drag). */
    public void setColorLive(int rgb) {
        Obj o = selected();
        if (o != null) o.color = rgb & 0xFFFFFF;
    }

    public void setBorderColorLive(int rgb) {
        Obj o = selected();
        if (o != null) o.borderColor = rgb & 0xFFFFFF;
    }

    // --- text styling ---

    public void cycleBorderStyle() {
        Obj o = selected();
        if (o == null) return;
        pushUndo();
        o.borderStyle = switch (o.borderStyle) {
            case "none" -> "outline"; case "outline" -> "shadow"; default -> "none";
        };
    }

    public void cycleTextAlign() {
        Obj o = selected();
        if (o == null) return;
        pushUndo();
        o.textAlign = switch (o.textAlign) {
            case "left" -> "center"; case "center" -> "right"; default -> "left";
        };
    }

    public void toggleItalic() {
        Obj o = selected();
        if (o != null) { pushUndo(); o.italic = !o.italic; }
    }

    public void setBorderSize(double v) {
        Obj o = selected();
        if (o != null) { pushUndo(); o.borderSize = Math.max(0, v); }
    }

    /** Renames the selected object (its Lua tag). Sanitised to a safe identifier. */
    public void setObjectName(String name) {
        Obj o = selected();
        if (o == null || name == null) return;
        String clean = name.trim().replaceAll("[^A-Za-z0-9_]", "_");
        if (clean.isEmpty()) return;
        pushUndo();
        o.tag = clean;
    }

    public void setFps(int fps) {
        Obj o = selected();
        if (o == null) return;
        pushUndo();
        o.fps = Math.max(1, Math.min(240, fps));
        if (o.character != null) o.character.setPlayback(o.fps, o.loop);
    }

    public void toggleLoop() {
        Obj o = selected();
        if (o == null) return;
        pushUndo();
        o.loop = !o.loop;
        if (o.character != null) o.character.setPlayback(o.fps, o.loop);
    }

    private void releaseTexture(Obj o) {
        if (o != null && o.textureId != null) {
            try { Minecraft.getInstance().getTextureManager().release(o.textureId); } catch (Throwable ignored) {}
            o.textureId = null;
        }
    }

    private static Path siblingXml(Path png) {
        String name = png.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        return png.resolveSibling(base + ".xml");
    }

    /** Absolute image path -> a mod-relative Lua reference (portion after /images/), else base name. */
    private static String deriveLuaImagePath(Path file) {
        String full = file.toString().replace('\\', '/');
        String lower = full.toLowerCase(java.util.Locale.ROOT);
        int idx = lower.lastIndexOf("/images/");
        String ref;
        if (idx >= 0) ref = full.substring(idx + 8);
        else ref = file.getFileName().toString();
        int dot = ref.lastIndexOf('.');
        return dot > 0 ? ref.substring(0, dot) : ref;
    }

    public void deselect() { selected = -1; resetTransform(); }

    /**
     * Selects the object nearest to the camera ray, or deselects if none is close.
     * Returns true if the selection changed.
     */
    public boolean pick(Vec3 eye, Vec3 dir, BlockPos speakers, Direction facing) {
        Vec3 look = dir.normalize();
        int best = -1;
        double bestAngle = 0.12; // radians-ish threshold around the cursor ray
        for (int i = 0; i < objects.size(); i++) {
            Vec3 wp = worldPos(objects.get(i), speakers, facing);
            Vec3 d = wp.subtract(eye);
            double t = d.dot(look);
            if (t <= 0.1) continue;
            double perpendicular = d.subtract(look.scale(t)).length();
            double angle = perpendicular / t;
            if (angle < bestAngle) {
                bestAngle = angle;
                best = i;
            }
        }
        if (best == selected) return false;
        selected = best;
        return true;
    }

    // --- modal transform ---

    public boolean isTransforming() { return mode != Mode.NONE && hasSelection(); }

    public Mode mode() { return mode; }

    /** Begins G/R/S on the selection; a second R while rotating toggles trackball. */
    public void beginTransform(Mode m, double mouseX, double mouseY) {
        if (!hasSelection()) return;
        if (mode == Mode.ROTATE && m == Mode.ROTATE) { trackball = !trackball; return; }
        if (pending == null) pending = snapshot();   // pre-transform state, for undo on confirm
        Obj o = selected();
        mode = m;
        axis = 0;
        plane = false;
        trackball = false;
        numericInput = "";
        startMouseX = mouseX;
        startMouseY = mouseY;
        bX = o.x; bY = o.y; bZ = o.z;
        bRotX = o.rotX; bRotY = o.rotY; bRotZ = o.rotZ;
        bScaleX = o.scaleX; bScaleY = o.scaleY; bScaleZ = o.scaleZ;
    }

    /** X/Y/Z axis lock; shift makes it a plane lock (all axes but this one), not for rotate. */
    public void setAxis(int a, boolean shift) {
        if (!isTransforming()) return;
        boolean wantPlane = shift && mode != Mode.ROTATE;
        if (axis == a && plane == wantPlane) { axis = 0; plane = false; }
        else { axis = a; plane = wantPlane; }
    }

    private void resetTransform() {
        mode = Mode.NONE; axis = 0; plane = false; trackball = false;
        numericInput = ""; pending = null;
    }

    /** Adds Blender-style signed decimal input to the active G/R/S transform. */
    public boolean inputNumeric(char character) {
        if (!isTransforming()) return false;
        if (character >= '0' && character <= '9') {
            numericInput += character;
            return true;
        }
        if ((character == '.' || character == ',') && !numericInput.contains(".")) {
            numericInput += numericInput.isEmpty() || "-".equals(numericInput) ? "0." : ".";
            return true;
        }
        if (character == '-') {
            numericInput = numericInput.startsWith("-")
                    ? numericInput.substring(1) : "-" + numericInput;
            return true;
        }
        if (character == '+') {
            if (numericInput.startsWith("-")) numericInput = numericInput.substring(1);
            return true;
        }
        return false;
    }

    public boolean backspaceNumeric() {
        if (!isTransforming() || numericInput.isEmpty()) return false;
        numericInput = numericInput.substring(0, numericInput.length() - 1);
        return true;
    }

    private double numericValue() {
        if (numericInput.isEmpty() || "-".equals(numericInput)) return 0;
        try {
            return Double.parseDouble(numericInput);
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    public void confirmTransform() {
        if (pending != null) { undoStack.push(pending); redoStack.clear(); capUndo(); }
        resetTransform();
    }

    /** Shifts the transform's mouse origin so a wrapped cursor keeps the delta continuous. */
    public void shiftTransformStart(double dx, double dy) {
        startMouseX += dx;
        startMouseY += dy;
    }

    /** World position of the selected object's visible centre/origin handle. */
    public Vec3 selectedWorldOrigin(BlockPos speakers, Direction facing) {
        Obj o = selected();
        return o == null ? null : worldPos(o, speakers, facing);
    }

    /** Largest on-screen extent of the selected object, in blocks, for framing the camera. */
    public double selectedSizeBlocks() {
        Obj o = selected();
        if (o == null) return 1;
        double sx = Math.abs(o.scaleX) * (o.worldChar != null ? o.worldChar.scaleX() : 1);
        double sy = Math.abs(o.scaleY) * (o.worldChar != null ? o.worldChar.scaleY() : 1);
        double blocks = Math.max(o.width * sx, o.height * sy) / 64.0;
        return Math.max(0.4, blocks);
    }

    public void cancelTransform() {
        Obj o = selected();
        if (o != null) {
            o.x = bX; o.y = bY; o.z = bZ;
            o.rotX = bRotX; o.rotY = bRotY; o.rotZ = bRotZ;
            o.scaleX = bScaleX; o.scaleY = bScaleY; o.scaleZ = bScaleZ;
        }
        resetTransform();   // discard the pending snapshot; nothing committed
    }

    // --- undo / redo ---

    private Snap snapshot() {
        List<Obj> copy = new ArrayList<>(objects.size());
        for (Obj o : objects) copy.add(o.copy());
        return new Snap(copy, selected);
    }

    private void restore(Snap s) {
        objects.clear();
        for (Obj o : s.objs()) objects.add(o.copy());
        selected = s.sel() < objects.size() ? s.sel() : objects.size() - 1;
    }

    /** Pushes the current state so the next mutation can be undone. */
    private void pushUndo() {
        undoStack.push(snapshot());
        redoStack.clear();
        capUndo();
    }

    private void capUndo() {
        while (undoStack.size() > 60) undoStack.removeLast();
    }

    public void undo() {
        if (undoStack.isEmpty()) return;
        redoStack.push(snapshot());
        restore(undoStack.pop());
        resetTransform();
        snapDragging = false;
    }

    public void redo() {
        if (redoStack.isEmpty()) return;
        undoStack.push(snapshot());
        restore(redoStack.pop());
        resetTransform();
        snapDragging = false;
    }

    /** Blender Alt+G: clears selected object's local position. */
    public boolean resetPosition() {
        Obj o = prepareTransformReset();
        if (o == null || (o.x == 0 && o.y == 0 && o.z == 0)) return false;
        pushUndo();
        o.x = 0; o.y = 0; o.z = 0;
        return true;
    }

    /** Blender Alt+R: clears selected object's rotation. */
    public boolean resetRotation() {
        Obj o = prepareTransformReset();
        if (o == null || (o.rotX == 0 && o.rotY == 0 && o.rotZ == 0)) return false;
        pushUndo();
        o.rotX = 0; o.rotY = 0; o.rotZ = 0;
        return true;
    }

    /** Blender-style Alt+S requested by the editor: restores unit scale. */
    public boolean resetScale() {
        Obj o = prepareTransformReset();
        if (o == null || (o.scaleX == 1 && o.scaleY == 1 && o.scaleZ == 1)) return false;
        pushUndo();
        o.scaleX = 1; o.scaleY = 1; o.scaleZ = 1;
        return true;
    }

    private Obj prepareTransformReset() {
        if (!hasSelection()) return null;
        if (isTransforming()) confirmTransform();
        if (snapDragging) endSnapDrag();
        return selected();
    }

    public void toggleBillboard() { Obj o = selected(); if (o != null) { pushUndo(); o.billboard = !o.billboard; } }
    public void toggleLighting() { Obj o = selected(); if (o != null) { pushUndo(); o.lighting = !o.lighting; } }
    public void toggleSeeThrough() { Obj o = selected(); if (o != null) { pushUndo(); o.seeThrough = !o.seeThrough; } }
    public void toggleAntialiasing() {
        Obj o = selected();
        if (o != null) { pushUndo(); o.antialiasing = !o.antialiasing; applyAntialiasing(o); }
    }

    /** Applies the object's antialiasing flag to its live texture (smooth vs pixel filtering). */
    private void applyAntialiasing(Obj o) {
        if (o.worldChar != null) { o.worldChar.setAntialiasing(o.antialiasing); return; }
        if (o.character != null) { o.character.setAntialiasing(o.antialiasing); return; }
        if (o.textureId != null) {
            var tex = Minecraft.getInstance().getTextureManager().getTexture(o.textureId, null);
            if (tex != null) {
                try { tex.setFilter(o.antialiasing, false); } catch (Throwable ignored) {}
            }
        }
    }

    /** Short status line for the HUD while a transform is running. */
    public String transformStatus() {
        if (!isTransforming()) return "";
        String m = switch (mode) {
            case MOVE -> "Move";
            case ROTATE -> trackball ? "Rotate (trackball)" : "Rotate";
            case SCALE -> "Scale";
            default -> "";
        };
        String ax = switch (axis) { case 1 -> "X"; case 2 -> "Y"; case 3 -> "Z"; default -> ""; };
        if (!ax.isEmpty()) m += plane ? "  (not " + ax + ")" : "  " + ax;
        if (!numericInput.isEmpty()) {
            String unit = switch (mode) {
                case MOVE -> " blocks";
                case ROTATE -> "°";
                case SCALE -> "×";
                default -> "";
            };
            m += "  = " + numericInput + unit;
        }
        return m + "   ·   LMB / Enter confirm, RMB / Esc cancel";
    }

    /**
     * Applies the running transform from the current mouse position.
     * {@code precise} = fine (Shift), {@code snap} = 1-block / 15° / 0.1 steps (Ctrl).
     */
    public void updateTransform(Camera camera, BlockPos speakers, Direction facing,
                                double mouseX, double mouseY, double centerX, double centerY,
                                boolean precise, boolean snap) {
        if (!isTransforming()) return;
        Obj o = selected();
        double p = precise ? 0.2 : 1.0;
        double rawDx = mouseX - startMouseX;
        double rawDy = mouseY - startMouseY;
        boolean numeric = !numericInput.isEmpty();
        double entered = numericValue();

        switch (mode) {
            case MOVE -> {
                Vec3 camRight = new Vec3(camera.getLeftVector()).scale(-1);
                Vec3 camUp = new Vec3(camera.getUpVector());
                Vec3 start = worldPosFrom(bX, bY, bZ, speakers, facing);
                double dist = start.subtract(camera.getPosition()).length();
                // Keep object translation independent from faster viewport orbit.
                // Half the previous effective world-units-per-pixel value: objects
                // were travelling roughly twice as far as the cursor gesture.
                double wu = Math.max(0.01, dist * 0.0011) * p;
                Vec3 delta = camRight.scale(rawDx * wu).add(camUp.scale(-rawDy * wu));
                delta = constrain(delta, facing);
                if (numeric) {
                    Vec3 direction;
                    if (axis != 0 && !plane) {
                        direction = axisVec(axis, facing);
                    } else {
                        direction = delta;
                        if (direction.lengthSqr() < 1.0e-10) {
                            direction = constrain(camRight, facing);
                            if (direction.lengthSqr() < 1.0e-10) direction = camUp;
                        }
                    }
                    delta = direction.normalize().scale(entered);
                }
                double[] px = worldToPixels(start.add(delta), speakers, facing);
                if (snap && !numeric) {
                    px[0] = snap(px[0], 64); px[1] = snap(px[1], 64); px[2] = snap(px[2], 64);
                }
                o.x = round(px[0]); o.y = round(px[1]); o.z = round(px[2]);
            }
            case SCALE -> {
                double factor = numeric ? entered : Math.max(0.05, 1 + rawDx * 0.005 * p);
                if (snap && !numeric) factor = Math.max(0.05, Math.round(factor * 10) / 10.0);
                boolean threeD = o.type == Type.CHARACTER_3D;
                boolean setX, setY, setZ;
                if (!threeD) {
                    if (axis == 0 || axis == 3) { setX = true; setY = true; }
                    else if (axis == 1) { setX = !plane; setY = plane; }
                    else { setX = plane; setY = !plane; }
                    setZ = false;
                } else if (axis == 0) {
                    setX = setY = setZ = true;
                } else {
                    setX = plane ? axis != 1 : axis == 1;
                    setY = plane ? axis != 2 : axis == 2;
                    setZ = plane ? axis != 3 : axis == 3;
                }
                if (setX) o.scaleX = round(bScaleX * factor);
                if (setY) o.scaleY = round(bScaleY * factor);
                if (setZ) o.scaleZ = round(bScaleZ * factor);
            }
            case ROTATE -> {
                double sens = precise ? 0.15 : 0.5;
                if (o.type == Type.CHARACTER_3D && axis == 0 && !trackball) {
                    // Plain R keeps the established BBS yaw behavior. Explicit X/Z
                    // constraints and trackball mode can now tilt the rendered form.
                    double v = bRotY + (numeric ? entered : rawDx * sens);
                    o.rotY = round(snap && !numeric ? snap(v, 15) : v);
                } else if (trackball) {
                    if (numeric) {
                        double length = Math.sqrt(rawDx * rawDx + rawDy * rawDy);
                        double dx = length < 1.0e-8 ? 1 : rawDx / length;
                        double dy = length < 1.0e-8 ? 0 : rawDy / length;
                        o.rotX = round(bRotX - dy * entered);
                        o.rotY = round(bRotY + dx * entered);
                    } else {
                        o.rotX = round(bRotX + (-rawDy) * sens);
                        o.rotY = round(bRotY + rawDx * sens);
                        if (snap) { o.rotX = snap(o.rotX, 15); o.rotY = snap(o.rotY, 15); }
                    }
                } else if (axis == 0) {
                    double a0 = Math.atan2(startMouseY - centerY, startMouseX - centerX);
                    double a1 = Math.atan2(mouseY - centerY, mouseX - centerX);
                    double deg = numeric ? entered : Math.toDegrees(a1 - a0) * p;
                    o.rotZ = round(bRotZ + deg);
                    if (snap && !numeric) o.rotZ = snap(o.rotZ, 15);
                } else {
                    double base = switch (axis) { case 1 -> bRotX; case 2 -> bRotY; default -> bRotZ; };
                    double v = base + (numeric ? entered : rawDx * sens);
                    if (snap && !numeric) v = snap(v, 15);
                    if (axis == 1) o.rotX = round(v);
                    else if (axis == 2) o.rotY = round(v);
                    else o.rotZ = round(v);
                }
            }
            default -> { }
        }
    }

    private Vec3 constrain(Vec3 delta, Direction facing) {
        if (axis == 0) return delta;
        Vec3 a = axisVec(axis, facing);
        Vec3 along = a.scale(delta.dot(a));
        return plane ? delta.subtract(along) : along;
    }

    private static Vec3 axisVec(int a, Direction facing) {
        Direction right = facing.getCounterClockWise();
        return switch (a) {
            case 1 -> new Vec3(right.getStepX(), 0, right.getStepZ());
            case 2 -> new Vec3(0, 1, 0);
            case 3 -> new Vec3(facing.getStepX(), 0, facing.getStepZ());
            default -> Vec3.ZERO;
        };
    }

    private static double snap(double v, double step) {
        return Math.round(v / step) * step;
    }

    // --- Lua export ---

    /**
     * Psych/Blockified Lua for the selection, ready to paste into a script. The three
     * flags include the object's position, rotation and scale respectively (unchecked =
     * excluded, so the script keeps whatever those properties already are).
     */
    public String toLua(boolean pos, boolean rot, boolean scale) {
        Obj o = selected();
        if (o == null) return "";
        StringBuilder sb = new StringBuilder();
        String t = o.tag;
        String q = "'" + t + "'";
        sb.append("-- ").append(o.type.label).append(" '").append(t).append("' (world camera)\n");

        // Main performers already exist; copying emits edits instead of a duplicate.
        if (o.source == Source.MAIN_CHARACTER) {
            if (rot) {
                if (o.rotX != 0) sb.append("setProperty('").append(t).append(".rotation.x', ")
                        .append(n(o.rotX)).append(")\n");
                if (o.rotY != 0) sb.append("setProperty('").append(t).append(".rotation.y', ")
                        .append(n(o.rotY)).append(")\n");
                if (o.rotZ != 0) sb.append("setProperty('").append(t).append(".rotation.z', ")
                        .append(n(o.rotZ)).append(")\n");
            }
            if (scale) {
                if (o.scaleX != 1) sb.append("setProperty('").append(t).append(".scale.x', ")
                        .append(n(o.scaleX)).append(")\n");
                if (o.scaleY != 1) sb.append("setProperty('").append(t).append(".scale.y', ")
                        .append(n(o.scaleY)).append(")\n");
                if (o.scaleZ != 1) sb.append("setProperty('").append(t).append(".scale.z', ")
                        .append(n(o.scaleZ)).append(")\n");
            }
            return sb.toString();
        }

        switch (o.type) {
            case SPRITE, SPRITESHEET -> {
                String img = o.texturePath.isBlank() ? "images/myImage" : o.texturePath;
                String make = o.type == Type.SPRITESHEET ? "makeAnimatedLuaSprite" : "makeLuaSprite";
                sb.append(make).append('(').append(q).append(", '").append(img).append("', ")
                        .append(pos ? n(o.x) : "0").append(", ").append(pos ? n(o.y) : "0").append(")\n");
                String anim = o.character != null && !o.character.current().isBlank()
                        ? o.character.current() : "idle";
                if (o.type == Type.SPRITESHEET) {
                    sb.append("addAnimationByPrefix(").append(q).append(", '").append(anim)
                            .append("', '").append(anim).append("', ").append(o.fps).append(", ")
                            .append(o.loop).append(")\n");
                }
                sb.append("setObjectCamera(").append(q).append(", 'world')\n");
                if (pos) sb.append("setProperty('").append(t).append(".z', ").append(n(o.z)).append(")\n");
                appendCommon(sb, o, t, q, rot, scale);
                if (o.type == Type.SPRITESHEET) {
                    sb.append("playAnim(").append(q).append(", '").append(anim).append("', true)\n");
                }
                sb.append("addLuaSprite(").append(q).append(", false)\n");
            }
            case GRAPH -> {
                sb.append("makeLuaSprite(").append(q).append(")\n");
                sb.append("makeGraphic(").append(q).append(", ").append((long) o.width).append(", ")
                        .append((long) o.height).append(", '")
                        .append(String.format("#%06X", o.color & 0xFFFFFF)).append("')\n");
                sb.append("setObjectCamera(").append(q).append(", 'world')\n");
                if (pos) {
                    sb.append("setProperty('").append(t).append(".x', ").append(n(o.x)).append(")\n");
                    sb.append("setProperty('").append(t).append(".y', ").append(n(o.y)).append(")\n");
                    sb.append("setProperty('").append(t).append(".z', ").append(n(o.z)).append(")\n");
                }
                appendCommon(sb, o, t, q, rot, scale);
                sb.append("addLuaSprite(").append(q).append(", false)\n");
            }
            case TEXT -> {
                sb.append("makeLuaText(").append(q).append(", '").append(esc(o.text)).append("', 0, ")
                        .append(pos ? n(o.x) : "0").append(", ").append(pos ? n(o.y) : "0");
                if (pos) sb.append(", ").append(n(o.z));
                sb.append(")\n");
                sb.append("setObjectCamera(").append(q).append(", 'world')\n");
                sb.append("setTextSize(").append(q).append(", ").append(o.textSize).append(")\n");
                if (!"none".equals(o.borderStyle) && o.borderSize > 0) {
                    sb.append("setTextBorder(").append(q).append(", ").append(n(o.borderSize))
                            .append(", '").append(String.format("%06X", o.borderColor & 0xFFFFFF))
                            .append("', '").append("shadow".equals(o.borderStyle) ? "shadow" : "outline")
                            .append("')\n");
                }
                if (!"left".equals(o.textAlign)) {
                    sb.append("setTextAlignment(").append(q).append(", '").append(o.textAlign).append("')\n");
                }
                if (o.italic) sb.append("setTextItalic(").append(q).append(", true)\n");
                appendCommon(sb, o, t, q, rot, scale);
                sb.append("addLuaText(").append(q).append(")\n");
            }
            case CHARACTER_2D -> {
                // A real 2D Psych character placed in 3D space. addBlockifiedCharacter with a
                // Psych character id spawns it as a world character, so the Play Animation
                // event, characterPlayAnim and characterDance target it natively.
                String def = o.characterDef.isBlank() ? "bf" : o.characterDef;
                double bx = pos ? o.x / 64.0 : 0;    // stage-local blocks: X right, Y up, Z forward
                double by = pos ? -o.y / 64.0 : 0;   // object Y is screen-down, character Y is up
                double bz = pos ? o.z / 64.0 : 0;
                double yaw = rot ? o.rotY : 0;
                String initial = o.worldChar != null && !o.worldChar.current().isBlank()
                        ? o.worldChar.current() : "idle";
                sb.append("addBlockifiedCharacter(").append(q).append(", '").append(def).append("', ")
                        .append(n(bx)).append(", ").append(n(by)).append(", ").append(n(bz)).append(", ")
                        .append(n(yaw)).append(", '").append(esc(initial)).append("', 'opponent')\n");
                if (rot && o.rotZ != 0) {
                    sb.append("setProperty('").append(t).append(".angle', ").append(n(o.rotZ)).append(")\n");
                }
                if (scale && (o.scaleX != 1 || o.scaleY != 1)) {
                    double baseX = o.worldChar != null ? o.worldChar.scaleX() : 1;
                    double baseY = o.worldChar != null ? o.worldChar.scaleY() : 1;
                    sb.append("setProperty('").append(t).append(".scale.x', ").append(n(o.scaleX * baseX)).append(")\n");
                    sb.append("setProperty('").append(t).append(".scale.y', ").append(n(o.scaleY * baseY)).append(")\n");
                }
                if (!o.billboard) sb.append("setProperty('").append(t).append(".billboard', false)\n");
                if (!o.lighting) sb.append("setProperty('").append(t).append(".lighting', false)\n");
                if (o.seeThrough) sb.append("setProperty('").append(t).append(".seeThrough', true)\n");
                if (!o.antialiasing) sb.append("setProperty('").append(t).append(".antialiasing', false)\n");
                if (o.alpha < 1) sb.append("setProperty('").append(t).append(".alpha', ").append(n(o.alpha)).append(")\n");
                if (o.color != 0xFFFFFF) {
                    sb.append("setProperty('").append(t).append(".color', ")
                            .append(String.format("0x%06X", o.color & 0xFFFFFF)).append(")\n");
                }
            }
            case CHARACTER_3D -> {
                String def = o.characterDef.isBlank() ? "myCharacter" : o.characterDef;
                // Stage-local blocks matching the live preview (X right, Y up, Z forward).
                double bx = pos ? charX(o) : 0;
                double by = pos ? charY(o) : 0;
                double bz = pos ? charZ(o) : 0;
                double yaw = rot ? o.rotY : 0;
                String initial = o.anim3d == null || o.anim3d.isBlank() ? "idle" : o.anim3d;
                sb.append("addBlockifiedCharacter(").append(q).append(", '").append(def).append("', ")
                        .append(n(bx)).append(", ").append(n(by)).append(", ").append(n(bz)).append(", ")
                        .append(n(yaw)).append(", '").append(initial).append("', 'opponent')\n");
                if (rot && o.rotX != 0) {
                    sb.append("setProperty('").append(t).append(".rotation.x', ")
                            .append(n(o.rotX)).append(")\n");
                }
                if (rot && o.rotZ != 0) {
                    sb.append("setProperty('").append(t).append(".rotation.z', ")
                            .append(n(o.rotZ)).append(")\n");
                }
                if (scale) {
                    if (o.scaleX != 1) sb.append("setProperty('").append(t).append(".scale.x', ")
                            .append(n(o.scaleX)).append(")\n");
                    if (o.scaleY != 1) sb.append("setProperty('").append(t).append(".scale.y', ")
                            .append(n(o.scaleY)).append(")\n");
                    if (o.scaleZ != 1) sb.append("setProperty('").append(t).append(".scale.z', ")
                            .append(n(o.scaleZ)).append(")\n");
                }
            }
        }
        return sb.toString();
    }

    /** True only when the first line declares a supported Blockified world-camera object. */
    public static boolean hasPasteHeader(String lua) {
        return PASTE_HEADER.matcher(firstLine(lua)).matches();
    }

    /**
     * Recreates an editable object by reading supported generated Lua statements.
     * Unknown statements are ignored; nothing is evaluated or executed.
     */
    public boolean pasteLua(String lua, Function<String, Path> imageResolver,
                            Function<String, Path> characterResolver) {
        if (lua == null || lua.length() > MAX_PASTE_LENGTH) return false;
        String[] lines = lua.replace("\r", "").split("\n", -1);
        if (lines.length == 0) return false;
        Matcher header = PASTE_HEADER.matcher(lines[0].trim());
        if (!header.matches()) return false;

        try {
            Type type = typeForLabel(header.group(1));
            String sourceTag = findCreatedTag(type, lines);
            if (sourceTag == null) return false;
            Obj o = new Obj(type);
            applyTypeDefaults(o);
            o.tag = uniqueTag(safeTag(sourceTag));
            boolean created = false;
            boolean characterScaleX = false, characterScaleY = false;

            for (int lineNumber = 1; lineNumber < lines.length; lineNumber++) {
                String line = lines[lineNumber].trim();
                if (line.isEmpty() || line.startsWith("--")) continue;
                Matcher call = LUA_CALL.matcher(line);
                if (!call.matches()) continue;
                String function = call.group(1);
                List<String> args = parseLuaArgs(call.group(2));

                switch (function) {
                    case "makeLuaSprite" -> {
                        if (!target(args, sourceTag)) continue;
                        if (type == Type.SPRITE && args.size() >= 4) {
                            o.texturePath = limited(arg(args, 1), 2048);
                            o.x = number(args, 2, 0); o.y = number(args, 3, 0);
                            created = true;
                        }
                    }
                    case "makeAnimatedLuaSprite" -> {
                        if (type != Type.SPRITESHEET || !target(args, sourceTag) || args.size() < 4) continue;
                        o.texturePath = limited(arg(args, 1), 2048);
                        o.x = number(args, 2, 0); o.y = number(args, 3, 0);
                        created = true;
                    }
                    case "makeGraphic" -> {
                        if (type != Type.GRAPH || !target(args, sourceTag) || args.size() < 4) continue;
                        o.width = bounded(number(args, 1, 128), 1, 16384);
                        o.height = bounded(number(args, 2, 128), 1, 16384);
                        o.color = color(arg(args, 3), 0x33AAFF);
                        created = true;
                    }
                    case "makeLuaText" -> {
                        if (type != Type.TEXT || !target(args, sourceTag) || args.size() < 5) continue;
                        o.text = limited(arg(args, 1), 8192);
                        o.x = number(args, 3, 0); o.y = number(args, 4, 0);
                        if (args.size() > 5) o.z = number(args, 5, 0);
                        created = true;
                    }
                    case "addBlockifiedCharacter" -> {
                        if ((type != Type.CHARACTER_2D && type != Type.CHARACTER_3D)
                                || !target(args, sourceTag) || args.size() < 7) continue;
                        o.characterDef = limited(arg(args, 1), 2048);
                        double bx = number(args, 2, 0), by = number(args, 3, 0), bz = number(args, 4, 0);
                        if (type == Type.CHARACTER_2D) {
                            o.x = bx * 64; o.y = -by * 64; o.z = bz * 64;
                        } else {
                            o.x = bx * 64; o.y = (0.5 - by) * 64; o.z = (bz + 2) * 64;
                        }
                        o.rotY = number(args, 5, 0);
                        o.anim3d = limited(arg(args, 6), 256);
                        created = true;
                    }
                    case "addAnimationByPrefix" -> {
                        if (!target(args, sourceTag)) continue;
                        o.anim3d = limited(arg(args, 1), 256);
                        o.fps = (int) bounded(number(args, 3, 24), 1, 240);
                        o.loop = bool(args, 4, true);
                    }
                    case "playAnim", "characterPlayAnim" -> {
                        if (target(args, sourceTag)) o.anim3d = limited(arg(args, 1), 256);
                    }
                    case "setObjectRotation" -> {
                        if (!target(args, sourceTag)) continue;
                        o.rotX = number(args, 1, 0); o.rotY = number(args, 2, 0); o.rotZ = number(args, 3, 0);
                    }
                    case "scaleObject" -> {
                        if (!target(args, sourceTag)) continue;
                        o.scaleX = number(args, 1, 1); o.scaleY = number(args, 2, o.scaleX);
                    }
                    case "setWorldSpriteBillboard" -> {
                        if (target(args, sourceTag)) o.billboard = bool(args, 1, true);
                    }
                    case "setWorldSpriteLighting" -> {
                        if (target(args, sourceTag)) o.lighting = bool(args, 1, true);
                    }
                    case "setObjectSeeThrough" -> {
                        if (target(args, sourceTag)) o.seeThrough = bool(args, 1, true);
                    }
                    case "setObjectAntialiasing" -> {
                        if (target(args, sourceTag)) o.antialiasing = bool(args, 1, true);
                    }
                    case "setTextSize" -> {
                        if (target(args, sourceTag)) o.textSize = (int) bounded(number(args, 1, 24), 1, 512);
                    }
                    case "setTextBorder" -> {
                        if (!target(args, sourceTag)) continue;
                        o.borderSize = bounded(number(args, 1, 1), 0, 128);
                        o.borderColor = color(arg(args, 2), 0);
                        o.borderStyle = oneOf(arg(args, 3), "outline", "outline", "shadow");
                    }
                    case "setTextAlignment" -> {
                        if (target(args, sourceTag)) {
                            o.textAlign = oneOf(arg(args, 1), "left", "left", "center", "right");
                        }
                    }
                    case "setTextItalic" -> {
                        if (target(args, sourceTag)) o.italic = bool(args, 1, true);
                    }
                    case "setProperty" -> {
                        if (args.size() < 2) continue;
                        String path = arg(args, 0);
                        String prefix = sourceTag + ".";
                        if (!path.startsWith(prefix)) continue;
                        String property = path.substring(prefix.length());
                        switch (property) {
                            case "x" -> o.x = number(args, 1, o.x);
                            case "y" -> o.y = number(args, 1, o.y);
                            case "z" -> o.z = number(args, 1, o.z);
                            case "angle" -> o.rotZ = number(args, 1, o.rotZ);
                            case "rotation.x", "rotationX", "angleX" -> o.rotX = number(args, 1, o.rotX);
                            case "rotation.y", "rotationY", "angleY", "rotation" -> o.rotY = number(args, 1, o.rotY);
                            case "rotation.z", "rotationZ", "angleZ" -> o.rotZ = number(args, 1, o.rotZ);
                            case "scale.x" -> { o.scaleX = number(args, 1, o.scaleX); characterScaleX = true; }
                            case "scale.y" -> { o.scaleY = number(args, 1, o.scaleY); characterScaleY = true; }
                            case "scale.z" -> o.scaleZ = number(args, 1, o.scaleZ);
                            case "alpha" -> o.alpha = bounded(number(args, 1, o.alpha), 0, 1);
                            case "color" -> o.color = color(arg(args, 1), o.color);
                            case "billboard" -> o.billboard = bool(args, 1, o.billboard);
                            case "lighting" -> o.lighting = bool(args, 1, o.lighting);
                            case "seeThrough" -> o.seeThrough = bool(args, 1, o.seeThrough);
                            case "antialiasing" -> o.antialiasing = bool(args, 1, o.antialiasing);
                            default -> { }
                        }
                    }
                    default -> { }
                }
            }
            if (!created) return false;

            if ((type == Type.SPRITE || type == Type.SPRITESHEET)
                    && imageResolver != null && !o.texturePath.isBlank()) {
                Path image = imageResolver.apply(o.texturePath);
                if (image != null) loadSpriteImage(o, image);
                if (o.character != null) o.character.play(o.anim3d);
            } else if (type == Type.CHARACTER_2D && characterResolver != null
                    && !o.characterDef.isBlank()) {
                Path definition = characterResolver.apply(o.characterDef);
                if (definition != null) {
                    o.worldChar = WorldCharacter.load(definition);
                    if (o.worldChar != null) {
                        if (characterScaleX && o.worldChar.scaleX() != 0) o.scaleX /= o.worldChar.scaleX();
                        if (characterScaleY && o.worldChar.scaleY() != 0) o.scaleY /= o.worldChar.scaleY();
                        o.worldChar.play(o.anim3d, true);
                        o.width = o.worldChar.refW(); o.height = o.worldChar.refH();
                    }
                }
            }
            applyAntialiasing(o);

            pushUndo();
            objects.add(o);
            selected = objects.size() - 1;
            resetTransform();
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private String uniqueTag(String base) {
        if (!tagExists(base)) return base;
        String copy = base + "_copy";
        if (!tagExists(copy)) return copy;
        int suffix = 2;
        while (tagExists(copy + suffix)) suffix++;
        return copy + suffix;
    }

    private boolean tagExists(String tag) {
        for (Obj o : objects) if (o.tag.equals(tag)) return true;
        return false;
    }

    private static String firstLine(String value) {
        if (value == null) return "";
        int lf = value.indexOf('\n');
        String line = lf < 0 ? value : value.substring(0, lf);
        return line.replace("\r", "").trim();
    }

    private static String limited(String value, int max) {
        if (value == null) return "";
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static String safeTag(String value) {
        String clean = value == null ? "" : value.trim().replaceAll("[^A-Za-z0-9_]", "_");
        return clean.isEmpty() ? "object" : limited(clean, 128);
    }

    private static String oneOf(String value, String fallback, String... accepted) {
        for (String candidate : accepted) if (candidate.equals(value)) return value;
        return fallback;
    }

    private static Type typeForLabel(String label) {
        for (Type type : Type.values()) if (type.label.equals(label)) return type;
        throw new IllegalArgumentException("Unsupported object type");
    }

    /** The constructor call, not the comment, owns the pasted object's Lua tag. */
    private static String findCreatedTag(Type type, String[] lines) {
        String expected = switch (type) {
            case SPRITE, GRAPH -> "makeLuaSprite";
            case SPRITESHEET -> "makeAnimatedLuaSprite";
            case TEXT -> "makeLuaText";
            case CHARACTER_2D, CHARACTER_3D -> "addBlockifiedCharacter";
        };
        for (int i = 1; i < lines.length; i++) {
            Matcher call = LUA_CALL.matcher(lines[i].trim());
            if (!call.matches() || !expected.equals(call.group(1))) continue;
            List<String> args = parseLuaArgs(call.group(2));
            String tag = arg(args, 0);
            if (!tag.isBlank()) return limited(tag, 512);
        }
        return null;
    }

    private static void applyTypeDefaults(Obj o) {
        switch (o.type) {
            case TEXT -> { o.text = "Text"; o.billboard = true; }
            case SPRITE, SPRITESHEET -> o.width = o.height = 64;
            case GRAPH -> { o.width = o.height = 128; o.color = 0x33AAFF; o.billboard = true; }
            case CHARACTER_2D -> { o.width = 48; o.height = 96; o.billboard = true; }
            case CHARACTER_3D -> { o.width = 48; o.height = 96; o.billboard = false; }
        }
    }

    /** Splits one generated call's arguments while respecting quoted commas and escapes. */
    private static List<String> parseLuaArgs(String source) {
        List<String> args = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        char quote = 0;
        boolean escaped = false;
        for (int i = 0; i < source.length(); i++) {
            char c = source.charAt(i);
            if (escaped) { current.append(c); escaped = false; continue; }
            if (quote != 0 && c == '\\') { current.append(c); escaped = true; continue; }
            if (c == '\'' || c == '"') {
                if (quote == 0) quote = c;
                else if (quote == c) quote = 0;
                current.append(c);
                continue;
            }
            if (c == ',' && quote == 0) {
                args.add(luaValue(current.toString()));
                current.setLength(0);
            } else current.append(c);
        }
        if (quote != 0 || escaped) throw new IllegalArgumentException("Unclosed Lua string");
        if (!source.isBlank() || current.length() > 0) args.add(luaValue(current.toString()));
        return args;
    }

    private static String luaValue(String raw) {
        String value = raw.trim();
        if (value.length() < 2) return value;
        char quote = value.charAt(0);
        if ((quote != '\'' && quote != '"') || value.charAt(value.length() - 1) != quote) return value;
        String body = value.substring(1, value.length() - 1);
        StringBuilder out = new StringBuilder(body.length());
        boolean escaped = false;
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (!escaped && c == '\\') { escaped = true; continue; }
            if (escaped) {
                out.append(switch (c) { case 'n' -> '\n'; case 'r' -> '\r'; case 't' -> '\t'; default -> c; });
                escaped = false;
            } else out.append(c);
        }
        if (escaped) out.append('\\');
        return out.toString();
    }

    private static boolean target(List<String> args, String tag) {
        return !args.isEmpty() && tag.equals(args.get(0));
    }

    private static String arg(List<String> args, int index) {
        return index >= 0 && index < args.size() ? args.get(index) : "";
    }

    private static double number(List<String> args, int index, double fallback) {
        try {
            double value = Double.parseDouble(arg(args, index));
            return Double.isFinite(value) ? value : fallback;
        } catch (Exception ignored) { return fallback; }
    }

    private static boolean bool(List<String> args, int index, boolean fallback) {
        String value = arg(args, index);
        if (value.equalsIgnoreCase("true")) return true;
        if (value.equalsIgnoreCase("false")) return false;
        return fallback;
    }

    private static double bounded(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static int color(String value, int fallback) {
        try {
            String clean = value.trim();
            if (clean.startsWith("#")) clean = clean.substring(1);
            else if (clean.startsWith("0x") || clean.startsWith("0X")) clean = clean.substring(2);
            return (int) Long.parseLong(clean, 16) & 0xFFFFFF;
        } catch (Exception ignored) { return fallback; }
    }

    private void appendCommon(StringBuilder sb, Obj o, String t, String q, boolean rot, boolean scale) {
        if (rot && (o.rotX != 0 || o.rotY != 0 || o.rotZ != 0)) {
            sb.append("setObjectRotation(").append(q).append(", ")
                    .append(n(o.rotX)).append(", ").append(n(o.rotY)).append(", ").append(n(o.rotZ)).append(")\n");
        }
        if (scale && (o.scaleX != 1 || o.scaleY != 1)) {
            sb.append("scaleObject(").append(q).append(", ").append(n(o.scaleX)).append(", ").append(n(o.scaleY)).append(")\n");
        }
        if (!o.billboard) sb.append("setWorldSpriteBillboard(").append(q).append(", false)\n");
        if (!o.lighting) sb.append("setWorldSpriteLighting(").append(q).append(", false)\n");
        if (o.seeThrough) sb.append("setObjectSeeThrough(").append(q).append(", true)\n");
        if (!o.antialiasing) sb.append("setObjectAntialiasing(").append(q).append(", false)\n");
        if (o.alpha < 1) sb.append("setProperty('").append(t).append(".alpha', ").append(n(o.alpha)).append(")\n");
        if (o.color != 0xFFFFFF && o.type != Type.GRAPH) {
            sb.append("setProperty('").append(t).append(".color', ")
                    .append(String.format("0x%06X", o.color & 0xFFFFFF)).append(")\n");
        }
    }

    private static String n(double v) {
        if (v == Math.rint(v) && !Double.isInfinite(v)) return String.valueOf((long) v);
        return String.valueOf(Math.round(v * 100.0) / 100.0);
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n");
    }

    // --- 3D character previews (BBS performers) ---

    /**
     * Spawns / moves / removes the client-side BBS performer for each 3D-character object.
     * Call from the client tick (not the render pass) so entities are never added while the
     * level's entity list is being iterated for rendering. Needs the BBS stack installed.
     */
    public void syncPerformers(BlockPos speakers) {
        java.util.Set<String> wanted = new java.util.HashSet<>();
        for (Obj o : objects) {
            if (o.linked()) continue; // its owning runtime already renders/moves it
            if (o.type != Type.CHARACTER_3D || o.characterDef.isBlank()) continue;
            wanted.add(o.tag);
            try {
                if (roster == null) roster = new ExtraCharacterRoster(speakers);
                if (!livePerformers.contains(o.tag)) {
                    boolean ok = roster.create(o.tag, o.characterDef,
                            charX(o), charY(o), charZ(o), (float) o.rotY, o.anim3d, "player");
                    if (ok) {
                        roster.setRotationX(o.tag, o.rotX);
                        roster.setRotationZ(o.tag, o.rotZ);
                        roster.setScaleX(o.tag, o.scaleX);
                        roster.setScaleY(o.tag, o.scaleY);
                        roster.setScaleZ(o.tag, o.scaleZ);
                        livePerformers.add(o.tag);
                        performerAnim.put(o.tag, o.anim3d);
                    }
                } else {
                    roster.setPosition(o.tag, charX(o), charY(o), charZ(o));
                    roster.setRotation(o.tag, o.rotY);
                    roster.setRotationX(o.tag, o.rotX);
                    roster.setRotationZ(o.tag, o.rotZ);
                    roster.setScaleX(o.tag, o.scaleX);
                    roster.setScaleY(o.tag, o.scaleY);
                    roster.setScaleZ(o.tag, o.scaleZ);
                    if (!java.util.Objects.equals(performerAnim.get(o.tag), o.anim3d)) {
                        roster.play(o.tag, o.anim3d);
                        performerAnim.put(o.tag, o.anim3d);
                    }
                }
            } catch (Throwable error) {
                FnfMod.LOGGER.warn("3D character preview failed for {}: {}", o.tag, error.toString());
            }
        }
        if (roster != null) {
            for (String tag : new ArrayList<>(livePerformers)) {
                if (!wanted.contains(tag)) {
                    try { roster.remove(tag); } catch (Throwable ignored) {}
                    livePerformers.remove(tag);
                    performerAnim.remove(tag);
                }
            }
            try { roster.update(); } catch (Throwable ignored) {}
        }
    }

    /** Removes every preview performer (leaving the objects), e.g. on leaving free cam. */
    public void despawnPerformers() {
        if (roster != null) { try { roster.clear(); } catch (Throwable ignored) {} }
        livePerformers.clear();
        performerAnim.clear();
    }

    private static double charX(Obj o) { return o.x / 64.0; }
    private static double charY(Obj o) { return 0.5 - o.y / 64.0; }
    private static double charZ(Obj o) { return o.z / 64.0 - 2.0; }

    /** Draws every object plus the selection highlight and centre handle. */
    public void render(PoseStack poseStack, Camera camera, BlockPos speakers, Direction facing) {
        if (objects.isEmpty()) return;
        // Advance real 2D-character animations for the preview (nominal step so sing holds).
        long now = System.nanoTime();
        double dt = lastRenderNano == 0 ? 0 : Math.min(0.1, (now - lastRenderNano) / 1.0e9);
        lastRenderNano = now;
        for (Obj o : objects) if (o.worldChar != null) o.worldChar.update(dt, 150, 1);

        List<LuaWorldObject> snapshots = new ArrayList<>(objects.size() + 3);
        for (int i = 0; i < objects.size(); i++) {
            Obj o = objects.get(i);
            // Existing runtime objects are already rendered by their owner. Only the
            // selection marker/gizmo below is drawn here, preventing a double image.
            if (o.linked()) continue;
            // A live 3D performer is drawn by the entity renderer, not as a sprite.
            if (o.type == Type.CHARACTER_3D && livePerformers.contains(o.tag)) continue;
            // Text draws only its glyphs — no backing quad (it caused a dark box that
            // hid the text through Minecraft's translucency sorting when rotated).
            if (o.type == Type.TEXT) snapshots.add(text(o));
            else snapshots.add(sprite(o));
        }
        addSelectionMarkers(snapshots);
        LuaWorldObjectRenderer.render(poseStack, camera, speakers, facing, snapshots);
        renderGizmo(poseStack, camera, speakers, facing);
    }

    /** Outline box and centre drag-cube, drawn on top of the world (behind the GUI). */
    private void renderGizmo(PoseStack poseStack, Camera camera, BlockPos speakers, Direction facing) {
        Obj o = selected();
        if (o == null) return;
        Vec3 wp = worldPos(o, speakers, facing);
        Vec3 cam = camera.getPosition();
        MultiBufferSource.BufferSource buffers = Minecraft.getInstance().renderBuffers().bufferSource();
        RenderType lines = OverlayLines.renderType();
        VertexConsumer vc = buffers.getBuffer(lines);

        poseStack.pushPose();
        poseStack.translate(wp.x - cam.x, wp.y - cam.y, wp.z - cam.z);
        poseStack.mulPose(Axis.YP.rotationDegrees(-facing.toYRot()));

        double hw = o.width * Math.abs(o.scaleX) * 0.5 * LuaWorldObjectRenderer.PIXEL_SCALE + 0.1;
        double hh = o.height * Math.abs(o.scaleY) * 0.5 * LuaWorldObjectRenderer.PIXEL_SCALE + 0.1;
        double hd = 0.05;
        float r = snapDragging ? 0.4f : 1f;
        float g = snapDragging ? 1f : 0.9f;
        float b = snapDragging ? 0.55f : 0.28f;
        LevelRenderer.renderLineBox(poseStack, vc, -hw, -hh, -hd, hw, hh, hd, r, g, b, 1f);
        double c = 0.09;
        LevelRenderer.renderLineBox(poseStack, vc, -c, -c, -c, c, c, c, 1f, 1f, 0.45f, 1f);

        poseStack.popPose();
        buffers.endBatch(lines);
    }

    // --- face-snap drag ---

    public boolean isSnapDragging() { return snapDragging && hasSelection(); }

    /** True when the camera ray roughly points at the selected object's centre. */
    public boolean cursorOverSelection(Vec3 eye, Vec3 dir, BlockPos speakers, Direction facing) {
        if (!hasSelection()) return false;
        Vec3 d = worldPos(selected(), speakers, facing).subtract(eye);
        double t = d.dot(dir);
        if (t <= 0.1) return false;
        return d.subtract(dir.scale(t)).length() / t < 0.08;
    }

    public void beginSnapDrag() {
        if (!hasSelection()) return;
        confirmTransform();
        pending = snapshot();
        snapDragging = true;
    }

    public void endSnapDrag() {
        if (pending != null) { undoStack.push(pending); redoStack.clear(); capUndo(); pending = null; }
        snapDragging = false;
    }

    /** Cancels the drag and restores the pre-drag state (right-click). */
    public void cancelSnapDrag() {
        if (pending != null) { restore(pending); pending = null; }
        snapDragging = false;
    }

    /** Places the selection flush on the hit block face, oriented outward. */
    public void snapToFace(BlockHitResult hit, BlockPos speakers, Direction facing) {
        if (!hasSelection() || hit == null || hit.getType() != HitResult.Type.BLOCK) return;
        Obj o = selected();
        Direction face = hit.getDirection();
        Vec3 n = new Vec3(face.getStepX(), face.getStepY(), face.getStepZ());
        Vec3 pos = hit.getLocation().add(n.scale(0.02));
        double[] px = worldToPixels(pos, speakers, facing);
        o.x = round(px[0]); o.y = round(px[1]); o.z = round(px[2]);
        o.billboard = false;
        if (face.getAxis().isVertical()) {
            o.rotX = face == Direction.UP ? -90 : 90;
            o.rotY = 0;
        } else {
            o.rotX = 0;
            o.rotY = round(face.toYRot() - facing.toYRot());
        }
        o.rotZ = 0;
    }

    private LuaWorldObject sprite(Obj o) {
        // A real 2D character renders its current frame with per-anim offset, scale and flipX.
        if (o.worldChar != null) {
            SparrowAtlas.Frame f = o.worldChar.currentFrame();
            if (f != null) {
                LuaWorldObject.Frame rf = new LuaWorldObject.Frame(
                        f.x, f.y, f.w, f.h, f.frameX, f.frameY, f.rotated);
                double[] off = o.worldChar.currentOffset();
                double sx = o.scaleX * o.worldChar.scaleX() * (o.worldChar.flipX() ? -1 : 1);
                double sy = o.scaleY * o.worldChar.scaleY();
                return new LuaWorldObject.Sprite(
                        o.worldChar.texture(), o.worldChar.texWidth(), o.worldChar.texHeight(),
                        rf, off[0], off[1],
                        o.x, o.y, o.z, o.width, o.height, o.worldChar.refW(), o.worldChar.refH(),
                        sx, sy, o.alpha * o.worldChar.alpha(), o.rotZ, o.rotX, o.rotY,
                        o.worldChar.color(), o.billboard, o.lighting, o.seeThrough);
            }
        }
        // A loaded spritesheet renders its current animation frame.
        if (o.character != null) {
            SparrowAtlas.Frame f = o.character.currentFrame();
            if (f != null) {
                LuaWorldObject.Frame rf = new LuaWorldObject.Frame(
                        f.x, f.y, f.w, f.h, f.frameX, f.frameY, f.rotated);
                double[] off = o.character.currentOffset();
                return new LuaWorldObject.Sprite(
                        o.character.texture(), o.character.texWidth(), o.character.texHeight(),
                        rf, off[0], off[1],
                        o.x, o.y, o.z, o.width, o.height, o.character.refW(), o.character.refH(),
                        o.scaleX, o.scaleY, o.alpha, o.rotZ, o.rotX, o.rotY,
                        0xFFFFFF, o.billboard, o.lighting, o.seeThrough);
            }
        }
        boolean hasImage = o.textureId != null && o.type != Type.TEXT;
        // A picked image renders untinted; otherwise a placeholder tint per type.
        int color = hasImage ? 0xFFFFFF : switch (o.type) {
            case TEXT -> 0x303040;
            case SPRITE -> 0x88CCFF;
            case SPRITESHEET -> 0xC8A2FF;
            case GRAPH -> o.color;
            case CHARACTER_2D -> 0x8CE08C;
            case CHARACTER_3D -> 0xE0A2E0;
        };
        if (!hasImage && o.color != 0xFFFFFF) color = o.color;
        ResourceLocation tex = hasImage ? o.textureId : WHITE;
        int tw = hasImage ? o.imgW : 16;
        int th = hasImage ? o.imgH : 16;
        double alpha = o.type == Type.TEXT ? o.alpha * 0.35 : o.alpha;
        return new LuaWorldObject.Sprite(
                tex, tw, th, null, 0, 0,
                o.x, o.y, o.z, o.width, o.height, o.width, o.height,
                o.scaleX, o.scaleY, alpha, o.rotZ, o.rotX, o.rotY,
                color, o.billboard, o.lighting, o.seeThrough);
    }

    private LuaWorldObject text(Obj o) {
        double bSize = "none".equals(o.borderStyle) ? 0 : o.borderSize;
        String bStyle = "shadow".equals(o.borderStyle) ? "shadow" : "outline";
        return new LuaWorldObject.Text(
                Minecraft.getInstance().font, o.text == null ? "" : o.text,
                o.x, o.y, o.z, 0, o.textSize,
                o.scaleX, o.scaleY, o.alpha, o.rotZ, o.rotX, o.rotY,
                o.color, o.billboard, o.lighting, o.seeThrough,
                bSize, o.borderColor, bStyle, o.textAlign, o.italic);
    }

    /** A faint translucent highlight over the object; the outline/cube are line gizmos. */
    private void addSelectionMarkers(List<LuaWorldObject> out) {
        Obj o = selected();
        if (o == null) return;
        double hw = Math.max(24, o.width * Math.abs(o.scaleX)) + 8;
        double hh = Math.max(24, o.height * Math.abs(o.scaleY)) + 8;
        out.add(new LuaWorldObject.Sprite(
                WHITE, 16, 16, null, 0, 0,
                o.x, o.y, o.z, hw, hh, hw, hh,
                1, 1, 0.15, o.rotZ, o.rotX, o.rotY,
                0xFFE24A, o.billboard, false, true));
    }

    // --- coordinate helpers (mirror LuaWorldObjectRenderer's mapping) ---

    private static Vec3 worldPos(Obj o, BlockPos speakers, Direction facing) {
        return worldPosFrom(o.x, o.y, o.z, speakers, facing);
    }

    private static Vec3 worldPosFrom(double x, double y, double z, BlockPos speakers, Direction facing) {
        Direction right = facing.getCounterClockWise();
        Vec3 origin = Vec3.atCenterOf(speakers);
        double bx = x * LuaWorldObjectRenderer.PIXEL_SCALE;
        double by = y * LuaWorldObjectRenderer.PIXEL_SCALE;
        double bz = z * LuaWorldObjectRenderer.PIXEL_SCALE;
        return new Vec3(
                origin.x + right.getStepX() * bx + facing.getStepX() * bz,
                origin.y - by,
                origin.z + right.getStepZ() * bx + facing.getStepZ() * bz);
    }

    private static double[] worldToPixels(Vec3 world, BlockPos speakers, Direction facing) {
        Direction right = facing.getCounterClockWise();
        Vec3 origin = Vec3.atCenterOf(speakers);
        double vx = world.x - origin.x, vy = world.y - origin.y, vz = world.z - origin.z;
        double xpx = (vx * right.getStepX() + vz * right.getStepZ()) / LuaWorldObjectRenderer.PIXEL_SCALE;
        double zpx = (vx * facing.getStepX() + vz * facing.getStepZ()) / LuaWorldObjectRenderer.PIXEL_SCALE;
        double ypx = -vy / LuaWorldObjectRenderer.PIXEL_SCALE;
        return new double[]{xpx, ypx, zpx};
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
