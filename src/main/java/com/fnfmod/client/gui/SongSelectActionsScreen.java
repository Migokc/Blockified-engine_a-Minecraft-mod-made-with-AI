package com.fnfmod.client.gui;

import com.fnfmod.client.ClientSession;
import com.fnfmod.client.gui.editor.ChartEditorScreen;
import com.fnfmod.client.render.IconLibrary;
import com.fnfmod.net.FnfPayloads;
import com.fnfmod.song.SongLibrary;
import com.fnfmod.song.WeekLibrary;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

/** Secondary song-selector actions, opened with 8 so the main selector stays uncluttered. */
public final class SongSelectActionsScreen extends Screen {
    private final SongSelectScreen parent;
    private final BlockPos machinePos;
    private int panelX;
    private int panelY;
    private int panelWidth;
    private int panelHeight;

    public SongSelectActionsScreen(SongSelectScreen parent, BlockPos machinePos) {
        super(Component.literal("Song Selector Actions"));
        this.parent = parent;
        this.machinePos = machinePos;
    }

    @Override
    protected void init() {
        panelWidth = Math.max(300, Math.min(480, width - 24));
        panelHeight = Math.max(226, Math.min(286, height - 24));
        panelX = (width - panelWidth) / 2;
        panelY = (height - panelHeight) / 2;
        int gap = 8;
        int cardWidth = (panelWidth - 28 - gap) / 2;
        int left = panelX + 14;
        int right = left + cardWidth + gap;
        int firstRow = panelY + 76;
        java.nio.file.Path worldRoot = com.fnfmod.world.ModContentScope.activeMod()
                .map(com.fnfmod.world.ModContentScope.ActiveMod::worldRoot).orElse(null);
        boolean showWorldSettings = worldRoot != null && minecraft.hasSingleplayerServer()
                && com.fnfmod.world.ModContentScope.isModWorld()
                && !com.fnfmod.world.ModWorldOptions.hideSettingsButton();

        addRenderableWidget(Button.builder(Component.literal("Chart Editor"), button -> {
            ClientSession.leave();
            minecraft.setScreen(new ChartEditorScreen(null, null, null, null, null, machinePos));
        }).bounds(left, firstRow, cardWidth, 28).build());

        addRenderableWidget(Button.builder(Component.literal("Character Editor"), button ->
                minecraft.setScreen(new CharacterEditorScreen(this)))
                .bounds(right, firstRow, cardWidth, 28).build());

        addRenderableWidget(Button.builder(Component.literal("Reload Songs"), button -> {
            SongLibrary.rescan();
            WeekLibrary.rescan();
            IconLibrary.rescan();
            PacketDistributor.sendToServer(new FnfPayloads.ReloadC2S(
                    SongLibrary.processNonce(), SongLibrary.rescanGeneration()));
        }).bounds(left, firstRow + 36, cardWidth, 28).build());

        addRenderableWidget(Button.builder(Component.literal("Week Maker"), button ->
                minecraft.setScreen(new WeekMakerScreen(this)))
                .bounds(right, firstRow + 36, cardWidth, 28).build());

        if (showWorldSettings) {
            addRenderableWidget(Button.builder(Component.literal("World Settings"), button ->
                    minecraft.setScreen(new WorldSettingsScreen(this, worldRoot)))
                    .bounds(left, firstRow + 72, panelWidth - 28, 24).build());
        }

        int footerY = panelY + panelHeight - 34;
        int footerWidth = (panelWidth - 34) / 2;
        addRenderableWidget(Button.builder(Component.literal("Close Machine"), button -> {
            ClientSession.leave();
            minecraft.setScreen(null);
        }).bounds(left, footerY, footerWidth, 20).build());

        addRenderableWidget(Button.builder(Component.literal("Back"), button -> onClose())
                .bounds(left + footerWidth + 6, footerY, panelWidth - 28 - footerWidth - 6, 20).build());
    }

    @Override
    public void render(GuiGraphics gui, int mouseX, int mouseY, float partialTick) {
        BlockifiedScreenStyle.backdrop(gui, width, height);
        BlockifiedScreenStyle.panel(gui, panelX, panelY, panelWidth, panelHeight);
        BlockifiedScreenStyle.header(gui, font, panelX + 16, panelY + 13,
                "BLOCKIFIED ENGINE", "Song tools",
                "Editors, content refresh, and world-specific controls.");
        BlockifiedScreenStyle.inner(gui, panelX + 14, panelY + 68,
                panelWidth - 28, panelHeight - 108);
        super.render(gui, mouseX, mouseY, partialTick);
    }

    @Override
    public void renderBackground(GuiGraphics gui, int mouseX, int mouseY, float partialTick) {
        // This screen owns its opaque backdrop; avoid applying the menu blur twice.
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_8 || keyCode == GLFW.GLFW_KEY_KP_8) {
            onClose();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
