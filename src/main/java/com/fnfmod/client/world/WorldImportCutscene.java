package com.fnfmod.client.world;

import com.fnfmod.FnfMod;
import com.fnfmod.client.gui.WorldTransitionScreen;
import com.fnfmod.client.render.BbsChromaSkyControl;
import com.fnfmod.client.render.DirectionalShadingControl;
import com.fnfmod.client.render.IrisCompat;
import com.fnfmod.world.ModContentScope;
import com.mojang.datafixers.DataFixer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.LevelLoadingScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.worldselection.WorldOpenFlows;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.validation.DirectoryValidator;
import net.minecraft.world.level.validation.PathAllowList;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Drives the "portal" transition that moves the current ordinary singleplayer world into a
 * pack's {@code worlds/} folder and re-opens it in place as a bundled mod world. A brief
 * black-sky/flat-lighting effect plays, then a black loading screen with an enderman-style
 * teleport sound carries the player across while the world folder is moved and reloaded, so
 * the mod-world-only controls take effect. Visuals (including any Iris shaders) are restored
 * afterward; BBS chroma-sky changes are in-memory only, leaving the player's config intact.
 *
 * <p>All world I/O runs on the client thread once it observes the integrated server fully
 * stopped, so nothing blocks the server shutdown. The move retries across ticks (never
 * sleeping) to tolerate brief post-shutdown file-handle lag.</p>
 */
public final class WorldImportCutscene {

    private static final String LOG = "[world-import] ";

    private enum Phase { IDLE, EFFECT, WAIT_STOP, WAIT_LOAD }

    private static final int EFFECT_TICKS = 10;   // 0.5s at 20 TPS before the black screen
    private static final int STOP_TIMEOUT = 600;  // up to 30s for the server to stop
    private static final int MOVE_ATTEMPTS = 60;  // ~3s of per-tick move retries (handle lag)
    private static final int LOAD_TIMEOUT = 1200; // up to 60s to reload the moved world

    private static Phase phase = Phase.IDLE;
    private static int timer;
    private static int moveAttempts;
    private static Path source;
    private static Path target;
    private static Path targetWorldsRoot;
    private static String worldName;
    private static boolean irisWasEnabled;
    private static boolean openTriggered;

    /** True while the transition wants every entity blob shadow hidden. */
    private static volatile boolean hideShadows;

    private static volatile float progress;
    private static volatile String status = "";

    private WorldImportCutscene() {}

    public static boolean active() {
        return phase != Phase.IDLE;
    }

    public static boolean hideShadows() {
        return hideShadows;
    }

    /**
     * True once the black loading screen should cover everything — from the disconnect until the
     * moved world finishes loading. The earlier EFFECT phase is excluded so the black-sky effect
     * is visible in-world first.
     */
    public static boolean showLoadingScreen() {
        return phase == Phase.WAIT_STOP || phase == Phase.WAIT_LOAD;
    }

    /**
     * Draws the opaque black loading screen with progress on top of whatever Minecraft is
     * currently showing (its own world-load screens, a menu, or the bare HUD), so the loading
     * screen never flickers away mid-transition. Called from client render events.
     */
    public static void renderOverlay(GuiGraphics gui) {
        if (!showLoadingScreen()) return;
        Minecraft minecraft = Minecraft.getInstance();
        int w = minecraft.getWindow().getGuiScaledWidth();
        int h = minecraft.getWindow().getGuiScaledHeight();
        var font = minecraft.font;
        gui.fill(0, 0, w, h, 0xFF000000);
        int cy = h / 2;
        gui.drawCenteredString(font, "Loading...", w / 2, cy - 22, 0xFFFFFFFF);
        String step = status;
        if (step != null && !step.isEmpty()) {
            gui.drawCenteredString(font, step, w / 2, cy - 8, 0xFFB0B0C0);
        }
        int barW = Math.min(300, w - 80);
        int barX = (w - barW) / 2;
        int barY = cy + 8;
        int filled = (int) (barW * Math.max(0F, Math.min(1F, progress)));
        gui.fill(barX - 1, barY - 1, barX + barW + 1, barY + 7, 0xFF3A3A46);
        gui.fill(barX, barY, barX + barW, barY + 6, 0xFF15151C);
        if (filled > 0) gui.fill(barX, barY, barX + filled, barY + 6, 0xFF66D0FF);
    }

    public static float progress() {
        return progress;
    }

    public static String status() {
        return status;
    }

    /**
     * Installed pack folders a world can be exported into (mods/ only, not directories). The
     * pack the current world already belongs to is excluded, since exporting to it is a no-op.
     */
    public static List<String> exportTargets() {
        Path mods = ModContentScope.modsRoot();
        if (!Files.isDirectory(mods)) return List.of();
        String current = ModContentScope.isModWorld()
                ? ModContentScope.activeMod().map(ModContentScope.ActiveMod::id).orElse("") : "";
        try (Stream<Path> children = Files.list(mods)) {
            return children.filter(Files::isDirectory)
                    .map(path -> path.getFileName().toString())
                    .filter(name -> !com.fnfmod.song.SongLibrary.RESERVED_MOD_SUBDIRS
                            .contains(name.toLowerCase(java.util.Locale.ROOT)))
                    .filter(name -> !name.equalsIgnoreCase(current))
                    .sorted(String.CASE_INSENSITIVE_ORDER)
                    .toList();
        } catch (IOException error) {
            return List.of();
        }
    }

    /**
     * Exports the current world into an installed pack's {@code worlds/} folder. Works from any
     * singleplayer world (an ordinary save or another pack's world). Returns an error message,
     * or {@code null} on success.
     */
    public static synchronized String startExport(String pack) {
        if (active()) return "A world transfer is already in progress.";
        Minecraft minecraft = Minecraft.getInstance();
        var server = minecraft.getSingleplayerServer();
        if (server == null || !minecraft.hasSingleplayerServer()) {
            return "This only works from your own singleplayer world.";
        }
        if (pack == null || pack.isBlank() || pack.contains("/") || pack.contains("\\")) {
            return "Invalid pack name.";
        }
        if (ModContentScope.isModWorld() && ModContentScope.activeMod()
                .map(m -> pack.equalsIgnoreCase(m.id())).orElse(false)) {
            return "This world is already in '" + pack + "'.";
        }
        Path modRoot = ModContentScope.modsRoot().resolve(pack);
        if (!Files.isDirectory(modRoot)) return "No installed pack named '" + pack + "'.";

        Path worldRoot = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
        String name = worldRoot.getFileName() == null ? null : worldRoot.getFileName().toString();
        if (name == null || name.isBlank()) return "Could not resolve the current world folder.";
        Path worldsRoot = modRoot.resolve("worlds");
        Path destination = worldsRoot.resolve(name);
        if (Files.exists(destination)) return "The pack already has a world named '" + name + "'.";

        return begin(worldRoot, destination, worldsRoot, name, "export to '" + pack + "'");
    }

    /**
     * Imports the current bundled mod world back into Minecraft's saves folder. Only works while
     * in a mod world; an ordinary save is already there. Returns an error message, or
     * {@code null} on success.
     */
    public static synchronized String startImport() {
        if (active()) return "A world transfer is already in progress.";
        Minecraft minecraft = Minecraft.getInstance();
        var server = minecraft.getSingleplayerServer();
        if (server == null || !minecraft.hasSingleplayerServer()) {
            return "This only works from your own singleplayer world.";
        }
        if (!ModContentScope.isModWorld()) {
            return "This world is already in your saves.";
        }
        Path worldRoot = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
        String name = worldRoot.getFileName() == null ? null : worldRoot.getFileName().toString();
        if (name == null || name.isBlank()) return "Could not resolve the current world folder.";
        Path savesRoot = minecraft.getLevelSource().getBaseDir().toAbsolutePath().normalize();
        Path destination = savesRoot.resolve(name);
        if (Files.exists(destination)) return "A save named '" + name + "' already exists.";

        return begin(worldRoot, destination, savesRoot, name, "import to saves");
    }

    /** Shared transition start for export/import once the source and destination are resolved. */
    private static String begin(Path src, Path dst, Path dstWorldsRoot, String name, String what) {
        source = src;
        target = dst;
        targetWorldsRoot = dstWorldsRoot;
        worldName = name;
        openTriggered = false;
        moveAttempts = 0;

        BbsChromaSkyControl.applyBlack();
        DirectionalShadingControl.setBlockShading(false);
        DirectionalShadingControl.setEntityShading(false);
        hideShadows = true;
        irisWasEnabled = IrisCompat.shadersEnabled();
        if (irisWasEnabled) IrisCompat.setShaders(false);

        phase = Phase.EFFECT;
        timer = EFFECT_TICKS;
        progress = 0.05F;
        status = "Preparing...";
        FnfMod.LOGGER.info(LOG + "start: {} world='{}' source={} target={} iris={}",
                what, name, src, dst, irisWasEnabled);
        return null;
    }

    /** Client-tick pump. Safe to call every tick; a no-op while idle. */
    public static synchronized void tick() {
        if (phase == Phase.IDLE) return;
        Minecraft minecraft = Minecraft.getInstance();

        switch (phase) {
            case EFFECT -> {
                progress = 0.05F + 0.10F * (1.0F - timer / (float) EFFECT_TICKS);
                if (--timer > 0) return;
                FnfMod.LOGGER.info(LOG + "effect done; scheduling disconnect");
                playTeleport(1.0F);
                minecraft.setScreen(new WorldTransitionScreen());
                // Disconnect must run OUTSIDE this tick: calling it inline stops and joins the
                // integrated server re-entrantly on the tick thread and never returns. Deferring
                // it to the next task boundary makes it behave like a normal Save-and-Quit.
                minecraft.execute(() -> {
                    FnfMod.LOGGER.info(LOG + "disconnecting now");
                    if (minecraft.level != null) minecraft.level.disconnect();
                    minecraft.disconnect(new WorldTransitionScreen());
                    FnfMod.LOGGER.info(LOG + "disconnect() returned; server={} level={}",
                            minecraft.getSingleplayerServer() != null, minecraft.level != null);
                });
                phase = Phase.WAIT_STOP;
                timer = STOP_TIMEOUT;
                progress = 0.25F;
                status = "Saving and closing world...";
            }
            case WAIT_STOP -> {
                boolean serverGone = minecraft.getSingleplayerServer() == null;
                boolean levelGone = minecraft.level == null;
                if (timer % 20 == 0) {
                    FnfMod.LOGGER.info(LOG + "wait-stop: serverGone={} levelGone={} remaining={}",
                            serverGone, levelGone, timer);
                }
                if (serverGone && levelGone) {
                    status = "Moving world...";
                    progress = Math.min(0.55F, progress + 0.01F);
                    if (tryMove()) {
                        FnfMod.LOGGER.info(LOG + "move succeeded; opening moved world");
                        status = "Opening moved world...";
                        openMovedWorld(minecraft);
                        phase = Phase.WAIT_LOAD;
                        timer = LOAD_TIMEOUT;
                    } else if (++moveAttempts >= MOVE_ATTEMPTS) {
                        abortToTitle(minecraft, "move failed after " + moveAttempts + " attempts");
                    }
                    // else: retry the move on the next tick (no blocking sleep)
                } else if (--timer <= 0) {
                    abortToTitle(minecraft, "timed out waiting for the server to stop");
                }
            }
            case WAIT_LOAD -> {
                // Wait until the player's own chunk is loaded before entering, so we don't drop
                // into unloaded space (Minecraft's own terrain-download screen normally does this,
                // but it is redirected to the black screen during the transition).
                boolean loaded = openTriggered && minecraft.level != null && minecraft.player != null
                        && minecraft.level.getChunkSource().getChunkNow(
                        minecraft.player.chunkPosition().x, minecraft.player.chunkPosition().z) != null;
                if (loaded) {
                    FnfMod.LOGGER.info(LOG + "moved world loaded; finishing");
                    playTeleport(0.8F);
                    progress = 1.0F;
                    minecraft.setScreen(null); // leave the black screen, enter the world
                    restoreVisuals();
                    reset();
                } else {
                    // Prefer Minecraft's real chunk-load percentage; fall back to a slow ramp
                    // for the terrain-download phase, which reports no numeric progress.
                    float chunks = mcChunkFraction();
                    if (chunks >= 0F) progress = 0.4F + 0.6F * chunks;
                    else progress = Math.min(0.9F, progress + 0.004F);
                    status = "Loading new location...";
                    if (--timer <= 0) abortToTitle(minecraft, "timed out loading the moved world");
                }
            }
            default -> {}
        }
    }

    private static Field progressListenerField;
    private static volatile Object capturedProgressListener;

    /**
     * Grabs the chunk-progress listener off a Minecraft LevelLoadingScreen before it is replaced
     * by the black transition screen, so real load progress is still available for the bar.
     */
    public static void captureLoadScreen(Screen screen) {
        if (!(screen instanceof LevelLoadingScreen)) return;
        try {
            if (progressListenerField == null) {
                for (Field field : LevelLoadingScreen.class.getDeclaredFields()) {
                    if (field.getType().getSimpleName().equals("StoringChunkProgressListener")) {
                        field.setAccessible(true);
                        progressListenerField = field;
                        break;
                    }
                }
            }
            if (progressListenerField != null) capturedProgressListener = progressListenerField.get(screen);
        } catch (Throwable ignored) {
        }
    }

    /** Minecraft's real chunk-load fraction (0..1) from the captured listener, else -1. */
    private static float mcChunkFraction() {
        Object listener = capturedProgressListener;
        if (listener == null) return -1F;
        try {
            int percent = (int) listener.getClass().getMethod("getProgress").invoke(listener);
            return Math.max(0F, Math.min(1F, percent / 100F));
        } catch (Throwable ignored) {
            return -1F;
        }
    }

    private static boolean tryMove() {
        try {
            Files.createDirectories(targetWorldsRoot);
            try {
                Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (Exception atomicFailed) {
                try {
                    Files.move(source, target);
                } catch (Exception renameFailed) {
                    copyTree(source, target);
                    deleteTree(source);
                }
            }
            boolean ok = Files.isDirectory(target);
            if (!ok) FnfMod.LOGGER.warn(LOG + "move reported no target dir at {}", target);
            return ok;
        } catch (Exception error) {
            if (moveAttempts == 0 || moveAttempts + 1 >= MOVE_ATTEMPTS) {
                FnfMod.LOGGER.warn(LOG + "move attempt {} failed: {}", moveAttempts, error.toString());
            }
            return false;
        }
    }

    private static void openMovedWorld(Minecraft minecraft) {
        try {
            DataFixer dataFixer = DataFixers.getDataFixer();
            DirectoryValidator validator = new DirectoryValidator(new PathAllowList(List.of()));
            LevelStorageSource storage = new LevelStorageSource(targetWorldsRoot, targetWorldsRoot,
                    validator, dataFixer);
            WorldOpenFlows flows = new WorldOpenFlows(minecraft, storage);
            openTriggered = true;
            flows.openWorld(worldName, () -> abortToTitle(minecraft, "opening the moved world was cancelled"));
        } catch (Exception error) {
            FnfMod.LOGGER.error(LOG + "failed to open moved world {}", target, error);
            abortToTitle(minecraft, "could not open the moved world");
        }
    }

    private static void abortToTitle(Minecraft minecraft, String failure) {
        FnfMod.LOGGER.warn(LOG + "aborting: {}", failure);
        restoreVisuals();
        minecraft.execute(() -> minecraft.setScreen(new TitleScreen()));
        reset();
    }

    private static void restoreVisuals() {
        if (irisWasEnabled) IrisCompat.setShaders(true);
        BbsChromaSkyControl.restore();
        DirectionalShadingControl.restore();
        hideShadows = false;
    }

    private static void reset() {
        phase = Phase.IDLE;
        openTriggered = false;
        moveAttempts = 0;
        progress = 0F;
        status = "";
        capturedProgressListener = null;
        source = target = targetWorldsRoot = null;
        worldName = null;
    }

    private static void playTeleport(float pitch) {
        Minecraft.getInstance().getSoundManager().play(
                SimpleSoundInstance.forUI(SoundEvents.ENDERMAN_TELEPORT, pitch));
    }

    private static void copyTree(Path from, Path to) throws IOException {
        try (Stream<Path> walk = Files.walk(from)) {
            for (Path path : (Iterable<Path>) walk::iterator) {
                Path rel = from.relativize(path);
                Path dest = to.resolve(rel.toString());
                if (Files.isDirectory(path)) Files.createDirectories(dest);
                else {
                    if (dest.getParent() != null) Files.createDirectories(dest.getParent());
                    Files.copy(path, dest, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                }
            });
        }
    }
}
