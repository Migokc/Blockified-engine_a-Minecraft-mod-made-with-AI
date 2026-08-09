package com.fnfmod.client.gui.machine;

import com.fnfmod.net.FnfPayloads;
import com.fnfmod.world.ChunkLoaderPointService;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

/** Compact in-game editor for one persistent chunk-loader point. */
public final class ChunkLoaderPointEditorScreen extends Screen {

    private final BlockPos pos;
    private String pointTag;
    private int radius;
    private boolean enabled;
    private EditBox tagBox;
    private RadiusSlider radiusSlider;
    private Button enabledButton;
    private String status = "";
    private boolean statusError;

    public ChunkLoaderPointEditorScreen(BlockPos pos, String tag, int radius, boolean enabled) {
        super(Component.literal("Chunk Loader Point"));
        this.pos = pos;
        this.pointTag = tag;
        this.radius = ChunkLoaderPointService.clampRadius(radius);
        this.enabled = enabled;
    }

    @Override
    protected void init() {
        clearWidgets();
        int panelX = width / 2 - 120;
        int top = Math.max(32, height / 2 - 82);

        tagBox = new EditBox(font, panelX, top + 24, 240, 20, Component.literal("Point tag"));
        tagBox.setMaxLength(64);
        tagBox.setFilter(value -> value.matches("[a-zA-Z0-9_-]*"));
        tagBox.setValue(pointTag);
        addRenderableWidget(tagBox);

        radiusSlider = addRenderableWidget(new RadiusSlider(panelX, top + 58, 240, 20, radius));
        enabledButton = addRenderableWidget(Button.builder(enabledLabel(), button -> {
            enabled = !enabled;
            button.setMessage(enabledLabel());
        }).bounds(panelX, top + 88, 240, 20).build());

        addRenderableWidget(Button.builder(Component.literal("Save"), button -> save())
                .bounds(panelX + 38, top + 126, 78, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Cancel"), button -> onClose())
                .bounds(panelX + 124, top + 126, 78, 20).build());
        setInitialFocus(tagBox);
    }

    private Component enabledLabel() {
        return Component.literal("Enabled: " + (enabled ? "On" : "Off"));
    }

    private void save() {
        pointTag = tagBox.getValue().trim().toLowerCase(java.util.Locale.ROOT);
        radius = radiusSlider.radius();
        if (pointTag.isBlank()) {
            status = "Tag cannot be empty.";
            statusError = true;
            return;
        }
        PacketDistributor.sendToServer(new FnfPayloads.ChunkLoaderEditC2S(
                pos, pointTag, radius, enabled));
        status = "Saving...";
        statusError = false;
    }

    public void onServerResult(boolean success, String message, String tag, int radius, boolean enabled) {
        this.status = message;
        this.statusError = !success;
        this.pointTag = tag;
        this.radius = ChunkLoaderPointService.clampRadius(radius);
        this.enabled = enabled;
        if (tagBox != null) tagBox.setValue(tag);
        if (radiusSlider != null) radiusSlider.setRadius(this.radius);
        if (enabledButton != null) enabledButton.setMessage(enabledLabel());
    }

    @Override
    public void render(GuiGraphics gui, int mouseX, int mouseY, float partialTick) {
        super.render(gui, mouseX, mouseY, partialTick);
        int panelX = width / 2 - 120;
        int top = Math.max(32, height / 2 - 82);
        gui.drawCenteredString(font, title, width / 2, top - 18, 0xFFFFFFFF);
        gui.drawString(font, "ID / Lua tag", panelX, top + 12, 0xFFBBBBBB, false);
        int count = (radius * 2 + 1) * (radius * 2 + 1);
        gui.drawCenteredString(font, count + " ticking chunks while enabled",
                width / 2, top + 112, radius >= 8 ? 0xFFFFAA55 : 0xFFAAAAAA);
        gui.drawCenteredString(font,
                "Lua: chunkLoadPoints." + (pointTag.isBlank() ? "<tag>" : pointTag) + ".enabled",
                width / 2, top + 154, 0xFF888888);
        if (!status.isBlank()) gui.drawCenteredString(font, status, width / 2, top + 170,
                statusError ? 0xFFFF7777 : 0xFF77DD88);
    }

    @Override public boolean isPauseScreen() { return false; }

    private final class RadiusSlider extends AbstractSliderButton {
        RadiusSlider(int x, int y, int width, int height, int radius) {
            super(x, y, width, height, Component.empty(), radius / (double) ChunkLoaderPointService.MAX_RADIUS);
            updateMessage();
        }

        int radius() {
            return ChunkLoaderPointService.clampRadius((int) Math.round(value * ChunkLoaderPointService.MAX_RADIUS));
        }

        void setRadius(int radius) {
            value = ChunkLoaderPointService.clampRadius(radius)
                    / (double) ChunkLoaderPointService.MAX_RADIUS;
            updateMessage();
        }

        @Override protected void updateMessage() {
            ChunkLoaderPointEditorScreen.this.radius = radius();
            setMessage(Component.literal("Radius: " + radius() + " chunk" + (radius() == 1 ? "" : "s")));
        }

        @Override protected void applyValue() { updateMessage(); }
    }
}
