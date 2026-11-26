package com.fnfmod.client.gui;

import com.fnfmod.song.GameBananaInstaller;
import com.fnfmod.client.ModDownloadManager;
import com.fnfmod.song.SongLibrary;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import java.net.URI;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Lets the player choose one archive; no network file is installed by merely opening a listing. */
public final class GameBananaModFilesScreen extends Screen {
    private static final int ROW_HEIGHT = 29;
    private final Screen parent;
    private final Runnable afterInstall;
    private final String modId;
    private final String modName;
    private final String modUrl;
    private List<GameBananaInstaller.FileOption> files = List.of();
    private GameBananaInstaller.FileOption selected;
    private String status = "Loading file choices...";
    private int scroll;
    private boolean installed;
    private boolean installing;
    private Button installButton;
    private Button backButton;

    public GameBananaModFilesScreen(GameBananaBrowserScreen parent, String modId, String modName,
                                    String modUrl, boolean installed) {
        this(parent, modId, modName, modUrl, installed, () -> parent.markInstalled(modId));
    }

    public GameBananaModFilesScreen(Screen parent, String modId, String modName,
                                    String modUrl, boolean installed, Runnable afterInstall) {
        super(Component.literal("GameBanana Mod Files"));
        this.parent = parent;
        this.afterInstall = afterInstall;
        this.modId = modId;
        this.modName = modName;
        this.modUrl = modUrl;
        this.installed = installed;
    }

    @Override protected void init() {
        int x = panelX(), w = panelWidth();
        addRenderableWidget(Button.builder(Component.literal("GameBanana page"), button -> {
            try { Util.getPlatform().openUri(URI.create(modUrl)); }
            catch (Exception ignored) {}
        }).bounds(x + 12, height - 36, 124, 20).build());
        installButton = addRenderableWidget(Button.builder(Component.literal("Download selected"),
                button -> installSelected()).bounds(x + 142, height - 36, 144, 20).build());
        backButton = addRenderableWidget(Button.builder(Component.literal("Back"), button -> onClose())
                .bounds(x + w - 72, height - 36, 60, 20).build());
        updateButtons();
        if (files.isEmpty() && !installed) fetchFiles();
        else if (installed) status = "Already installed. Existing files will not be replaced.";
    }

    private int panelWidth() { return Math.min(width - 16, 620); }
    private int panelX() { return (width - panelWidth()) / 2; }
    private int listTop() { return 91; }
    private int listBottom() { return height - 80; }
    private int visibleRows() { return Math.max(1, (listBottom() - listTop()) / ROW_HEIGHT); }

    private void updateButtons() {
        if (installButton != null) installButton.active = !installed && !installing
                && selected != null && selected.installable();
        if (backButton != null) backButton.active = true;
    }

    private void fetchFiles() {
        CompletableFuture.supplyAsync(() -> {
            try { return GameBananaInstaller.files(modId); }
            catch (Exception error) { throw new java.util.concurrent.CompletionException(error); }
        }).whenComplete((result, error) -> Minecraft.getInstance().execute(() -> {
            if (Minecraft.getInstance().screen != this) return;
            if (error != null) status = "File list unavailable: " + message(error);
            else {
                files = result;
                status = result.isEmpty() ? "This mod has no downloadable files."
                        : "Choose a ZIP, 7z or RAR archive to download.";
            }
        }));
    }

    private void installSelected() {
        if (selected == null || !selected.installable() || installed || installing) return;
        GameBananaInstaller.FileOption choice = selected;
        ModDownloadManager.Job job = ModDownloadManager.startGameBanana(modId, modName, choice, () -> {
            installed = true;
            installing = false;
            afterInstall.run();
            status = "Installed. Return to the song menu to try again.";
            updateButtons();
        });
        if (job == null) {
            status = "Another mod download is already active.";
            return;
        }
        installing = true;
        updateButtons();
        ModDownloadManager.showDetails(this);
    }

    private static String message(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null) cause = cause.getCause();
        return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
    }

    @Override public void render(GuiGraphics gui, int mouseX, int mouseY, float partialTick) {
        if (installing) {
            ModDownloadManager.Job job = ModDownloadManager.current();
            if (job != null && job.finished() && job.installed() == null) {
                installing = false;
                status = "Install failed: " + job.error();
                updateButtons();
            }
        }
        BlockifiedScreenStyle.backdrop(gui, width, height);
        int x = panelX(), w = panelWidth();
        BlockifiedScreenStyle.panel(gui, x, 8, w, height - 16);
        gui.drawCenteredString(font, font.plainSubstrByWidth(modName, w - 32), width / 2, 19,
                BlockifiedScreenStyle.ACCENT);
        gui.drawString(font, font.plainSubstrByWidth(status, w - 24), x + 12, 47,
                BlockifiedScreenStyle.TEXT_MUTED, false);
        gui.drawString(font, "Downloaded packs may contain Lua. Install only mods you trust.",
                x + 12, 68, BlockifiedScreenStyle.TEXT_MUTED, false);
        BlockifiedScreenStyle.inner(gui, x + 10, listTop() - 2, w - 20, listBottom() - listTop() + 4);
        scroll = Mth.clamp(scroll, 0, Math.max(0, files.size() - visibleRows()));
        gui.enableScissor(x + 12, listTop(), x + w - 12, listBottom());
        for (int i = scroll; i < files.size() && i < scroll + visibleRows() + 1; i++) {
            GameBananaInstaller.FileOption file = files.get(i);
            int y = listTop() + (i - scroll) * ROW_HEIGHT;
            boolean selectedRow = file.equals(selected);
            gui.fill(x + 14, y, x + w - 14, y + ROW_HEIGHT - 2,
                    selectedRow ? BlockifiedScreenStyle.ACCENT_DEEP : BlockifiedScreenStyle.PANEL_SOFT);
            gui.fill(x + 14, y, x + 17, y + ROW_HEIGHT - 2,
                    file.installable() ? BlockifiedScreenStyle.ACCENT : 0xFF777077);
            gui.drawString(font, font.plainSubstrByWidth(file.name(), w - 150), x + 22, y + 5,
                    file.installable() ? BlockifiedScreenStyle.TEXT : BlockifiedScreenStyle.TEXT_MUTED, false);
            String size = file.size() < 0 ? "?" : String.format("%.1f MB", file.size() / 1048576.0);
            gui.drawString(font, size, x + w - 87, y + 5, BlockifiedScreenStyle.TEXT_MUTED, false);
        }
        gui.disableScissor();
        gui.drawString(font, "ZIP, 7z and RAR up to 1.5 GB; existing packs stay untouched.",
                x + 12, height - 60, BlockifiedScreenStyle.TEXT_MUTED, false);
        super.render(gui, mouseX, mouseY, partialTick);
    }

    @Override public void renderBackground(GuiGraphics gui, int mouseX, int mouseY, float partialTick) {}

    @Override public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && mouseX >= panelX() + 14 && mouseX < panelX() + panelWidth() - 14
                && mouseY >= listTop() && mouseY < listBottom()) {
            int index = scroll + ((int) mouseY - listTop()) / ROW_HEIGHT;
            if (index >= 0 && index < files.size()) {
                selected = files.get(index);
                updateButtons();
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (mouseY >= listTop() && mouseY < listBottom()) {
            scroll = Mth.clamp(scroll - (int) Math.signum(scrollY) * 2,
                    0, Math.max(0, files.size() - visibleRows()));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override public void onClose() {
        Minecraft.getInstance().setScreen(parent);
    }
}
