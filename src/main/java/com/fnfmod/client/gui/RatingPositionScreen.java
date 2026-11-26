package com.fnfmod.client.gui;

import com.fnfmod.client.ClientOptions;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

/** Drag and resize the rating, combo, and optional hit-timing label independently. */
public class RatingPositionScreen extends Screen {

    private enum DragTarget { NONE, RATING, COMBO, MS }

    private final Screen parent;
    private double fx, fy;
    private double ratingSize, comboSize, msSize;
    private double comboOffsetX, comboOffsetY, msOffsetX, msOffsetY;
    private DragTarget dragTarget = DragTarget.NONE;
    private boolean initialized;

    public RatingPositionScreen(Screen parent) {
        super(Component.literal("Rating, Combo, and Timing Position"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        var options = ClientOptions.get();
        if (!initialized) {
            fx = options.ratingX >= 0 ? options.ratingX : 0.73;
            fy = options.ratingY >= 0 ? options.ratingY : 0.40;
            ratingSize = options.ratingSize;
            comboSize = options.comboSize;
            msSize = options.ratingMsSize;
            comboOffsetX = options.comboOffsetX;
            comboOffsetY = options.comboOffsetY;
            msOffsetX = options.ratingMsOffsetX;
            msOffsetY = options.ratingMsOffsetY;
            initialized = true;
        }

        int controlWidth = Math.min(220, width - 24);
        int controlLeft = (width - controlWidth) / 2;
        addSizeControls(controlLeft, controlWidth, height - 136, this::changeRatingSize);
        if (!oldStyle()) {
            addSizeControls(controlLeft, controlWidth, height - 112, this::changeComboSize);
        }
        addSizeControls(controlLeft, controlWidth, height - (oldStyle() ? 112 : 88), this::changeMsSize);

        addRenderableWidget(Button.builder(Component.literal("Reset to Default"), b -> {
            fx = 0.73;
            fy = 0.40;
            ratingSize = 1.0;
            if (!oldStyle()) {
                comboSize = 1.0;
                comboOffsetX = comboOffsetY = 0;
            }
            msSize = 1.0;
            msOffsetX = 8;
            msOffsetY = 0;
        }).bounds(width / 2 - 60, height - 58, 120, 20).build());

        addRenderableWidget(Button.builder(Component.literal("Apply"), b -> {
            options.ratingX = fx;
            options.ratingY = fy;
            options.ratingSize = ratingSize;
            options.comboSize = comboSize;
            options.ratingMsSize = msSize;
            options.comboOffsetX = comboOffsetX;
            options.comboOffsetY = comboOffsetY;
            options.ratingMsOffsetX = msOffsetX;
            options.ratingMsOffsetY = msOffsetY;
            ClientOptions.save();
            minecraft.setScreen(parent);
        }).bounds(width / 2 - 104, height - 28, 100, 20).build());

        addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> minecraft.setScreen(parent))
                .bounds(width / 2 + 4, height - 28, 100, 20).build());
    }

    private void addSizeControls(int left, int width, int y, java.util.function.IntConsumer change) {
        addRenderableWidget(Button.builder(Component.literal("-"), b -> change.accept(-1))
                .bounds(left, y, 22, 20).build());
        addRenderableWidget(Button.builder(Component.literal("+"), b -> change.accept(1))
                .bounds(left + width - 22, y, 22, 20).build());
    }

    private void changeRatingSize(int direction) { ratingSize = changeSize(ratingSize, direction); }
    private void changeComboSize(int direction) { comboSize = changeSize(comboSize, direction); }
    private void changeMsSize(int direction) { msSize = changeSize(msSize, direction); }

    private double changeSize(double size, int direction) {
        double step = hasShiftDown() ? 0.01 : 0.05;
        return Mth.clamp(Math.round((size + direction * step) * 100.0) / 100.0, 0.25, 2.0);
    }

    private int px() { return (int) (fx * width); }
    private int py() { return (int) (fy * height); }
    private float canvasScale() { return Math.min(width / 1280f, height / 720f); }
    private boolean oldStyle() { return "old".equals(ClientOptions.get().ratingStyle); }
    private float legacyHudScale() {
        return "fnf".equals(ClientOptions.effectiveHudStyle()) ? 2f : 1f;
    }
    private float previewLegacyRatingHeight(float canvasScale) {
        return font.lineHeight * legacyHudScale() * (float) ratingSize * canvasScale;
    }

    @Override
    public void render(GuiGraphics gui, int mouseX, int mouseY, float partialTick) {
        drawPreview(gui);
        super.render(gui, mouseX, mouseY, partialTick);
        gui.drawCenteredString(font, oldStyle() ? "Rating and Timing Position"
                : "Rating, Combo, and Timing Position", width / 2, 12,
                BlockifiedScreenStyle.TEXT);
        gui.drawCenteredString(font, oldStyle()
                        ? "Drag the rating or timing text; Shift-click +/- for 1%"
                        : "Drag each preview separately; Shift-click +/- for 1%",
                width / 2, 26, BlockifiedScreenStyle.TEXT);
        gui.drawCenteredString(font, "Rating: " + Math.round(ratingSize * 100) + "%",
                width / 2, height - 130, BlockifiedScreenStyle.TEXT);
        if (!oldStyle()) {
            gui.drawCenteredString(font, "Combo: " + Math.round(comboSize * 100) + "%",
                    width / 2, height - 106, BlockifiedScreenStyle.TEXT);
        }
        gui.drawCenteredString(font, "MS text: " + Math.round(msSize * 100) + "%"
                        + (ClientOptions.get().ratingShowMs ? " (enabled)" : " (disabled in gameplay)"),
                width / 2, height - (oldStyle() ? 106 : 82), BlockifiedScreenStyle.TEXT);
    }

    private void drawPreview(GuiGraphics gui) {
        float scale = canvasScale();
        String rating = previewRatingText();
        float ratingScale = (oldStyle() ? legacyHudScale() : RatingPopupText.DEFAULT_RATING_SCALE)
                * (float) ratingSize * scale;
        float ratingX = px();
        float ratingY = previewRatingY();
        drawRating(gui, rating, ratingX, ratingY, ratingScale, 0xFF66FFFF);

        String digits = "123";
        if (!oldStyle()) {
        float firstX = RatingPopupText.firstDigitOffset(font, rating,
                (float) ratingSize, (float) comboSize);
        float step = RatingPopupText.digitStep(font, (float) comboSize);
        for (int i = 0; i < digits.length(); i++) {
            float x = px() + (float) (firstX + comboOffsetX + i * step) * scale;
            float y = py() + (float) (30f + comboOffsetY) * scale;
            RatingPopupText.draw(gui, font, digits.substring(i, i + 1), x, y,
                    RatingPopupText.DEFAULT_COMBO_SCALE * (float) comboSize * scale,
                    0xFFF7F7F7, 1f);
        }
        }

        float msX = ratingX + font.width(rating) * ratingScale * 0.5f + (float) msOffsetX * scale;
        float msY = ratingY + (float) msOffsetY * scale;
        drawMsPreview(gui, "+7ms", msX, msY, scale, 0xFFFFDD33,
                ClientOptions.get().ratingShowMs ? 1f : 0.45f);
    }

    private void drawRating(GuiGraphics gui, String text, float x, float y, float scale, int color) {
        if (oldStyle()) drawLegacy(gui, text, x, y, scale, color);
        else RatingPopupText.draw(gui, font, text, x, y, scale, color, 1f);
    }

    private void drawLegacy(GuiGraphics gui, String text, float x, float y, float scale, int color) {
        gui.pose().pushPose();
        gui.pose().translate(x, y, 0);
        gui.pose().scale(scale, scale, 1f);
        gui.drawCenteredString(font, text, 0, -font.lineHeight / 2, color);
        gui.pose().popPose();
    }

    private void drawMsPreview(GuiGraphics gui, String text, float x, float y,
                               float canvasScale, int color, float alpha) {
        float scale = RatingPopupText.DEFAULT_COMBO_SCALE * (float) msSize * 0.42f * canvasScale;
        int a = Math.clamp(Math.round(alpha * 255f), 0, 255);
        int fill = a << 24 | color & 0xFFFFFF;
        int shadow = a << 24 | darken(color) & 0xFFFFFF;
        gui.pose().pushPose();
        gui.pose().translate(x, y, 0);
        gui.pose().scale(scale, scale, 1f);
        int left = -font.width(text) / 2;
        int top = -font.lineHeight / 2;
        gui.drawString(font, text, left + 1, top + 1, shadow, false);
        gui.drawString(font, text, left, top, fill, false);
        gui.pose().popPose();
    }

    private int darken(int color) {
        return ((color >> 16 & 255) / 3) << 16 | ((color >> 8 & 255) / 3) << 8 | (color & 255) / 3;
    }

    private float msX() {
        float scale = canvasScale();
        float ratingScale = (oldStyle() ? legacyHudScale() : RatingPopupText.DEFAULT_RATING_SCALE)
                * (float) ratingSize * scale;
        return px() + font.width(previewRatingText()) * ratingScale * 0.5f + (float) msOffsetX * scale;
    }

    private String previewRatingText() { return oldStyle() ? "SICK!! 123" : "SICK!!"; }

    private float previewRatingY() {
        float scale = canvasScale();
        return oldStyle() ? py() + previewLegacyRatingHeight(scale) * 0.5f
                : py() - 60f * scale;
    }

    private float previewComboY() {
        float scale = canvasScale();
        if (oldStyle()) {
            float comboHeight = font.lineHeight * legacyHudScale() * (float) comboSize * scale;
            return py() + previewLegacyRatingHeight(scale) + scale
                    + ((float) comboOffsetY * scale) + comboHeight * 0.5f;
        }
        return py() + (30f + (float) comboOffsetY) * scale;
    }

    private float msY() { return previewRatingY() + (float) msOffsetY * canvasScale(); }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (super.mouseClicked(mx, my, button)) return true;
        if (button != 0) return false;
        float scale = canvasScale();
        if (Math.abs(mx - msX()) < Math.max(24, 40 * scale)
                && Math.abs(my - msY()) < Math.max(14, 24 * scale)) {
            dragTarget = DragTarget.MS;
        } else if (!oldStyle()
                && Math.abs(mx - (px() + comboOffsetX * scale)) < Math.max(75, 125 * scale)
                && Math.abs(my - previewComboY()) < Math.max(24, 40 * scale)) {
            dragTarget = DragTarget.COMBO;
        } else if (ratingPreviewHit(mx, my, scale)) {
            dragTarget = DragTarget.RATING;
        } else {
            return false;
        }
        return true;
    }

    private boolean ratingPreviewHit(double mx, double my, float canvasScale) {
        if (!oldStyle()) {
            return Math.abs(mx - px()) < Math.max(70, 190 * canvasScale)
                    && Math.abs(my - previewRatingY()) < Math.max(26, 60 * canvasScale);
        }
        float textScale = legacyHudScale() * (float) ratingSize * canvasScale;
        float halfWidth = font.width(previewRatingText()) * textScale * 0.5f + 4f * canvasScale;
        float halfHeight = font.lineHeight * textScale * 0.5f + 4f * canvasScale;
        return Math.abs(mx - px()) <= halfWidth
                && Math.abs(my - previewRatingY()) <= halfHeight;
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        float scale = Math.max(0.01f, canvasScale());
        switch (dragTarget) {
            case RATING -> {
                fx = Mth.clamp(mx / width, 0.0, 1.0);
                if (oldStyle()) {
                    // Legacy gameplay stores the text's top edge; the editor
                    // drags its visible center and keeps the full glyph on-screen.
                    float textHeight = previewLegacyRatingHeight(scale);
                    float top = Mth.clamp((float) my - textHeight * 0.5f,
                            0f, Math.max(0f, height - textHeight));
                    fy = top / height;
                } else {
                    // The stacked style's gameplay anchor sits 60 HUD units
                    // below the visible rating center, so preserve that offset.
                    fy = (my + 60f * scale) / height;
                }
                return true;
            }
            case COMBO -> {
                comboOffsetX += dx / scale;
                comboOffsetY += dy / scale;
                return true;
            }
            case MS -> {
                msOffsetX += dx / scale;
                msOffsetY += dy / scale;
                return true;
            }
            default -> { return super.mouseDragged(mx, my, button, dx, dy); }
        }
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        dragTarget = DragTarget.NONE;
        return super.mouseReleased(mx, my, button);
    }

    @Override
    public void onClose() { minecraft.setScreen(parent); }
    @Override
    public void renderBackground(GuiGraphics gui, int mouseX, int mouseY, float partialTick) {}
    @Override
    protected void renderBlurredBackground(float partialTick) {}
    @Override
    protected void renderMenuBackground(GuiGraphics gui) {}
}
