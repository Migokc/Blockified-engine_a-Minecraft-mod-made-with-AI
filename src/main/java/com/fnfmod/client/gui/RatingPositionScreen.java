package com.fnfmod.client.gui;

import com.fnfmod.client.ClientOptions;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

/** Drag a preview rating popup to set where ratings appear during gameplay. */
public class RatingPositionScreen extends Screen {

    private final Screen parent;
    /** working position as screen fractions */
    private double fx, fy;
    private boolean dragging;

    public RatingPositionScreen(Screen parent) {
        super(Component.literal("Rating Position"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        var o = ClientOptions.get();
        fx = o.ratingX >= 0 ? o.ratingX : 0.73;
        fy = o.ratingY >= 0 ? o.ratingY : 0.40;

        addRenderableWidget(Button.builder(Component.literal("Apply"), b -> {
            o.ratingX = fx;
            o.ratingY = fy;
            ClientOptions.save();
            minecraft.setScreen(parent);
        }).bounds(width / 2 - 104, height - 28, 100, 20).build());

        addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> minecraft.setScreen(parent))
                .bounds(width / 2 + 4, height - 28, 100, 20).build());

        addRenderableWidget(Button.builder(Component.literal("Reset to Default"), b -> {
            fx = 0.73;
            fy = 0.40;
        }).bounds(width / 2 - 60, height - 52, 120, 20).build());
    }

    private int px() { return (int) (fx * width); }
    private int py() { return (int) (fy * height); }

    @Override
    public void render(GuiGraphics gui, int mouseX, int mouseY, float partialTick) {
        super.render(gui, mouseX, mouseY, partialTick);
        gui.drawCenteredString(font, "Drag the rating to reposition it", width / 2, 16, 0xFFFFFF);

        // preview rating popups stacked at the chosen spot
        drawPreview(gui, "SICK!!", 0xFF66FFFF, 0);
        drawPreview(gui, "GOOD", 0xFF66FF66, -14);

        // handle marker
        gui.fill(px() - 2, py() - 2, px() + 2, py() + 2, 0x88FFFFFF);
    }

    private void drawPreview(GuiGraphics gui, String text, int color, int dy) {
        gui.drawCenteredString(font, text, px(), py() + dy, color);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button == 0 && Math.abs(mx - px()) < 60 && Math.abs(my - py()) < 24) {
            dragging = true;
            return true;
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double ddx, double ddy) {
        if (dragging) {
            fx = Mth.clamp(mx / width, 0.05, 0.95);
            fy = Mth.clamp(my / height, 0.08, 0.92);
            return true;
        }
        return super.mouseDragged(mx, my, button, ddx, ddy);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        dragging = false;
        return super.mouseReleased(mx, my, button);
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }
}
