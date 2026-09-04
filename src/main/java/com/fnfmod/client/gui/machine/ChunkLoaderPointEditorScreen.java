package com.fnfmod.client.gui.machine;

import com.fnfmod.client.gui.BlockifiedScreenStyle;
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
    private int panelX;
    private int panelY;
    private int panelWidth;
    private int panelHeight;
    private int contentX;
    private int contentWidth;

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
        panelWidth = Math.max(280, Math.min(420, width - 24));
        panelHeight = Math.max(246, Math.min(286, height - 24));
        panelX = (width - panelWidth) / 2;
        panelY = (height - panelHeight) / 2;
        contentX = panelX + 22;
        contentWidth = panelWidth - 44;
        int top = panelY + 82;

        tagBox = new EditBox(font, contentX, top + 16, contentWidth, 20, Component.literal("Point tag"));
        tagBox.setMaxLength(64);
        tagBox.setFilter(value -> value.matches("[a-zA-Z0-9_-]*"));
        tagBox.setValue(pointTag);
        addRenderableWidget(tagBox);

        radiusSlider = addRenderableWidget(new RadiusSlider(contentX, top + 48, contentWidth, 20, radius));
        enabledButton = addRenderableWidget(Button.builder(enabledLabel(), button -> {
            enabled = !enabled;
            button.setMessage(enabledLabel());
        }).bounds(contentX, top + 76, contentWidth, 20).build());

        int footerY = panelY + panelHeight - 34;
        int buttonWidth = (contentWidth - 8) / 2;
        addRenderableWidget(Button.builder(Component.literal("Save"), button -> save())
                .bounds(contentX, footerY, buttonWidth, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Cancel"), button -> onClose())
                .bounds(contentX + buttonWidth + 8, footerY,
                        contentWidth - buttonWidth - 8, 20).build());
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
        super.renderBackground(gui, mouseX, mouseY, partialTick);
        BlockifiedScreenStyle.backdrop(gui, width, height);
        BlockifiedScreenStyle.panel(gui, panelX, panelY, panelWidth, panelHeight);
        BlockifiedScreenStyle.header(gui, font, panelX + 16, panelY + 13,
                "FUNKIN' DESIGNER", "Chunk loader point",
                "Keep a configurable radius ticking for cameras and staged gameplay.");
        BlockifiedScreenStyle.inner(gui, panelX + 14, panelY + 70,
                panelWidth - 28, panelHeight - 114);
        BlockifiedScreenStyle.section(gui, font, "POINT SETTINGS", contentX, panelY + 62);
        super.render(gui, mouseX, mouseY, partialTick);
        int top = panelY + 82;
        gui.drawString(font, "ID / Lua tag", contentX, top + 5,
                BlockifiedScreenStyle.TEXT_SECTION, false);
        int count = (radius * 2 + 1) * (radius * 2 + 1);
        gui.drawCenteredString(font, count + " ticking chunks while enabled",
                width / 2, top + 103, radius >= 8 ? 0xFFFFAA55 : BlockifiedScreenStyle.TEXT_MUTED);
        String footerHint = status.isBlank()
                ? "Lua: chunkLoadPoints." + (pointTag.isBlank() ? "<tag>" : pointTag) + ".enabled"
                : status;
        gui.drawCenteredString(font, footerHint, width / 2, panelY + panelHeight - 48,
                status.isBlank() ? 0xFF888888 : statusError ? 0xFFFF7777 : 0xFF77DD88);
    }

    @Override
    public void renderBackground(GuiGraphics gui, int mouseX, int mouseY, float partialTick) {
        // render() owns the complete editor backdrop.
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
