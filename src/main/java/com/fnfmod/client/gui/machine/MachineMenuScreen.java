package com.fnfmod.client.gui.machine;

import com.fnfmod.client.ClientSession;
import com.fnfmod.client.gui.CharacterEditorScreen;
import com.fnfmod.client.gui.FnfSettingsScreen;
import com.fnfmod.client.gui.SongDetailScreen;
import com.fnfmod.client.gui.editor.ChartEditorScreen;
import com.fnfmod.client.lua.MachineMenuRuntime;
import com.fnfmod.gameplay.PlaybackMode;
import com.fnfmod.machine.MachineDefinition;
import com.fnfmod.machine.MachineLibrary;
import com.fnfmod.net.FnfPayloads;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.List;

/** Screen rendered and controlled by a machine profile's sandboxed menu.lua. */
public final class MachineMenuScreen extends Screen implements MachineMenuRuntime.Host {

    private final BlockPos pos;
    private final String profileId;
    private final String compatibilityError;
    private final List<FnfPayloads.SongInfo> songs;
    private String currentData;
    private MachineMenuRuntime runtime;
    private String loadError;
    private boolean closing;
    // Silent preload gate: while menuLoading the screen renders nothing and input
    // stays blocked. Heavy asset decoding runs on a worker thread; the cheap GL
    // upload finalizes on the render thread afterwards.
    private boolean menuLoading;
    private java.util.Queue<Runnable> mainPreloadTasks;
    private volatile boolean preloadDecodeDone;
    private final java.util.concurrent.atomic.AtomicBoolean preloadCancelled =
            new java.util.concurrent.atomic.AtomicBoolean();
    private Thread preloadThread;

    public MachineMenuScreen(BlockPos pos, String profileId, String initialData,
                             String compatibilityError, List<FnfPayloads.SongInfo> songs) {
        super(Component.literal("Funkin' Machine"));
        this.pos = pos;
        this.profileId = MachineLibrary.canonical(profileId);
        this.currentData = initialData;
        this.compatibilityError = compatibilityError;
        this.songs = songs == null ? List.of() : List.copyOf(songs);
    }

    @Override
    protected void init() {
        closing = false;
        loadError = null;
        MachineDefinition definition = MachineLibrary.find(profileId).orElse(null);
        if (compatibilityError != null) {
            loadError = compatibilityError;
        } else if (definition == null) {
            loadError = "Profile unavailable. Install matching mod/version.";
        } else if (runtime == null) {
            runtime = new MachineMenuRuntime(definition, currentData, songs, this);
            if (!runtime.loaded()) loadError = runtime.error();
            else beginPreload();
        } else if (!runtime.loaded()) {
            loadError = runtime.error();
        }
        if (loadError != null) {
            addRenderableWidget(Button.builder(Component.literal("Open Default Song Menu"), button ->
                    openSongSelect(FnfPayloads.LeaveC2S.RETURN_MACHINE_MENU))
                    .bounds(width / 2 - 100, height / 2 + 24, 200, 20).build());
        }
    }

    private void beginPreload() {
        java.util.List<Runnable> background = runtime.takeBackgroundPreloadTasks();
        mainPreloadTasks = new java.util.ArrayDeque<>(runtime.takeMainPreloadTasks());
        if (background.isEmpty() && mainPreloadTasks.isEmpty()) {
            runtime.open();
            menuLoading = false;
            return;
        }
        menuLoading = true;
        if (background.isEmpty()) {
            preloadDecodeDone = true;
            return;
        }
        // Decode heavy assets off the render thread so the game does not freeze.
        preloadThread = new Thread(() -> {
            for (Runnable task : background) {
                if (preloadCancelled.get()) break;
                try {
                    task.run();
                } catch (Throwable ignored) {
                    // A single bad asset must not stall the whole preload.
                }
            }
            preloadDecodeDone = true;
        }, "fnf-menu-preload");
        preloadThread.setDaemon(true);
        preloadThread.start();
    }

    /**
     * Once background decoding is done, finalizes the assets on the render thread in
     * time-sliced batches (each finalize is only a cheap GL upload), then opens the menu.
     */
    private void processPreload() {
        if (mainPreloadTasks == null) {
            menuLoading = false;
            return;
        }
        if (!preloadDecodeDone) return; // still decoding in the background; keep the gate held
        long deadline = System.nanoTime() + 8_000_000L;
        Runnable task;
        while ((task = mainPreloadTasks.poll()) != null) {
            try {
                task.run();
            } catch (Throwable ignored) {
                // A single bad asset must not block opening; it falls back at render.
            }
            if (System.nanoTime() >= deadline) break;
        }
        if (mainPreloadTasks.isEmpty()) {
            mainPreloadTasks = null;
            menuLoading = false;
            if (runtime != null) runtime.open();
        }
    }

    @Override
    public void tick() {
        if (!menuLoading && runtime != null) runtime.tick();
    }

    @Override
    protected void renderMenuBackground(GuiGraphics gui) {
        // Custom machine menus draw their own background; skip Minecraft's dark
        // in-world menu tint entirely so the scene behind the menu stays clean.
        // (Not Lua-configurable — always off for custom menus.)
    }

    @Override
    protected void renderBlurredBackground(float partialTick) {
        // While the menu is still loading, the gate is invisible: no blur yet, so the
        // player just sees the world with input held until the menu is ready.
        if (menuLoading) return;
        // A menu that controls the blur overrides the player's option for its own
        // screen only; otherwise vanilla behavior (the player's setting) is kept.
        if (runtime != null && runtime.loaded() && runtime.isMenuBlurControlled()) {
            com.fnfmod.client.render.MenuBlur.render(minecraft, (float) runtime.currentMenuBlur(), partialTick);
            return;
        }
        super.renderBlurredBackground(partialTick);
    }

    @Override
    public void render(GuiGraphics gui, int mouseX, int mouseY, float partialTick) {
        super.render(gui, mouseX, mouseY, partialTick);
        if (menuLoading) {
            processPreload();
            return;
        }
        if (runtime != null && runtime.loaded()) runtime.render(gui, font, mouseX, mouseY);
        String runtimeError = runtime == null ? loadError : runtime.error();
        if (runtimeError != null) {
            gui.drawCenteredString(font, Component.literal("Machine menu error"), width / 2,
                    height / 2 - 30, 0xFFFF7777);
            gui.drawCenteredString(font, Component.literal(trim(runtimeError, 100)), width / 2,
                    height / 2 - 14, 0xFFCCCCCC);
            gui.drawCenteredString(font, Component.literal("Fallback keeps built-in song selection available."),
                    width / 2, height / 2, 0xFF999999);
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!menuLoading && runtime != null && runtime.loaded()
                && runtime.mouseClicked(mouseX, mouseY, button)) return true;
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public void openSongSelect(byte returnTarget) {
        ClientSession.pendingSongExitTarget = FnfPayloads.LeaveC2S.normalizeReturnTarget(returnTarget);
        PacketDistributor.sendToServer(new FnfPayloads.MachineMenuActionC2S(pos, (byte) 0, ""));
    }

    @Override
    public boolean openSongDetails(String songId) {
        FnfPayloads.SongInfo song = songs.stream()
                .filter(value -> value.id().equalsIgnoreCase(songId == null ? "" : songId.trim()))
                .findFirst().orElse(null);
        if (song == null || minecraft == null) {
            message("Song not found: " + songId);
            return false;
        }
        minecraft.setScreen(new SongDetailScreen(this, pos, song));
        return true;
    }

    @Override
    public boolean playSong(String songId, String difficulty, boolean duet,
                            byte playSide, byte playbackMode, byte returnTarget) {
        FnfPayloads.SongInfo song = songs.stream()
                .filter(value -> value.id().equalsIgnoreCase(songId == null ? "" : songId.trim()))
                .findFirst().orElse(null);
        if (song == null || !song.difficulties().contains(difficulty)) {
            message(song == null ? "Song not found: " + songId : "Difficulty not found: " + difficulty);
            return false;
        }
        ClientSession.activePos = pos;
        ClientSession.pendingPlaySide = duet ? 0 : (byte) Math.max(0, Math.min(2, playSide));
        ClientSession.pendingPlaybackMode = PlaybackMode.fromNetworkId(playbackMode);
        ClientSession.pendingSongExitTarget = FnfPayloads.LeaveC2S.normalizeReturnTarget(returnTarget);
        PacketDistributor.sendToServer(new FnfPayloads.MachineDirectPlayC2S(pos, song.id(), difficulty,
                duet, ClientSession.pendingPlaySide, ClientSession.pendingPlaybackMode.networkId()));
        return true;
    }

    @Override
    public void openSettings() {
        if (minecraft != null) minecraft.setScreen(new FnfSettingsScreen(this));
    }

    @Override
    public void openCharacterEditor() {
        if (minecraft != null) minecraft.setScreen(new CharacterEditorScreen(this));
    }

    @Override
    public void openChartEditor(String songId, String difficulty) {
        if (minecraft == null) return;
        ClientSession.leave();
        String selectedSong = songId == null || songId.isBlank() ? null : songId.trim();
        String selectedDifficulty = difficulty == null || difficulty.isBlank() ? null : difficulty.trim();
        minecraft.setScreen(new ChartEditorScreen(selectedSong, selectedDifficulty,
                null, null, null, pos));
    }

    @Override
    public int screenWidth() {
        return width;
    }

    @Override
    public int screenHeight() {
        return height;
    }

    @Override
    public void closeMenu() {
        if (!closing && minecraft != null) {
            ClientSession.leave();
            minecraft.setScreen(null);
        }
    }

    @Override
    public void onClose() {
        if (!closing) ClientSession.leave();
        super.onClose();
    }

    @Override
    public void saveData(String snbt) {
        if (snbt == null || snbt.length() > 32767) {
            if (minecraft != null && minecraft.player != null) {
                minecraft.player.displayClientMessage(Component.literal(
                        "machineData is too large to save (32 KiB maximum)."), true);
            }
            return;
        }
        currentData = snbt;
        PacketDistributor.sendToServer(new FnfPayloads.MachineMenuActionC2S(pos, (byte) 1, snbt));
    }

    @Override
    public void removed() {
        closing = true;
        // Esc/close during loading cancels the background decode and frees anything
        // it decoded that never got uploaded.
        preloadCancelled.set(true);
        menuLoading = false;
        mainPreloadTasks = null;
        com.fnfmod.client.render.MachineTextureCache.dropPending();
        com.fnfmod.client.render.MachineAtlasCache.dropPending();
        if (runtime != null) {
            runtime.close();
            saveData(runtime.dataSnbt());
            runtime = null;
        }
        super.removed();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private static String trim(String value, int max) {
        if (value == null) return "Unknown error";
        return value.length() <= max ? value : value.substring(0, max - 3) + "...";
    }

    private void message(String text) {
        if (minecraft != null && minecraft.player != null) {
            minecraft.player.displayClientMessage(Component.literal(text), true);
        }
    }
}
