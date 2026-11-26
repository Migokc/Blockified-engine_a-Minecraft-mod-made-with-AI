package com.fnfmod.client.gui;

import com.fnfmod.client.ModDownloadManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Non-blocking details for the one active mod download. Esc collapses it. */
public final class ModDownloadProgressScreen extends Screen {
    private final Screen parent;
    private Button pauseButton, cancelButton;

    public ModDownloadProgressScreen(Screen parent) {
        super(Component.literal("Mod Download"));
        this.parent = parent;
    }

    @Override protected void init() {
        int x = width / 2 - 175, y = height / 2 + 48;
        pauseButton = addRenderableWidget(Button.builder(Component.literal("Pause"), button -> {
            ModDownloadManager.Job job = ModDownloadManager.current();
            if (job == null) return;
            if (job.paused()) job.resume(); else job.pause();
        }).bounds(x + 12, y, 105, 20).build());
        cancelButton = addRenderableWidget(Button.builder(Component.literal("Cancel download"), button -> {
            ModDownloadManager.Job job = ModDownloadManager.current();
            if (job != null) job.cancel();
        }).bounds(x + 122, y, 120, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Hide"), button -> onClose())
                .bounds(x + 247, y, 91, 20).build());
    }

    @Override public void render(GuiGraphics gui, int mouseX, int mouseY, float partialTick) {
        BlockifiedScreenStyle.backdrop(gui, width, height);
        int x = width / 2 - 175, y = height / 2 - 78;
        BlockifiedScreenStyle.panel(gui, x, y, 350, 164);
        gui.drawCenteredString(font, "MOD DOWNLOAD", width / 2, y + 13,
                BlockifiedScreenStyle.ACCENT);
        ModDownloadManager.Job job = ModDownloadManager.current();
        if (job != null) {
            pauseButton.active = !job.finished() && job.installed() == null;
            cancelButton.active = !job.finished() && job.installed() == null;
            pauseButton.setMessage(Component.literal(job.paused() ? "Resume" : "Pause"));
            gui.drawString(font, font.plainSubstrByWidth(job.name(), 320), x + 15, y + 36,
                    BlockifiedScreenStyle.TEXT, false);
            String phase = job.paused() ? "Paused · " + job.phase() : job.phase();
            gui.drawString(font, phase, x + 15, y + 54, BlockifiedScreenStyle.TEXT_MUTED, false);
            gui.fill(x + 15, y + 76, x + 335, y + 88, 0xFF3C3046);
            float ratio = job.total() > 0 ? Math.min(1f, job.done() / (float) job.total()) : 0;
            gui.fill(x + 15, y + 76, x + 15 + (int) (320 * ratio), y + 88, 0xFFFF702E);
            String amount = bytes(job.done()) + (job.total() > 0 ? " / " + bytes(job.total()) : "")
                    + (job.total() > 0 ? "  (" + Math.round(ratio * 100) + "%)" : "");
            gui.drawString(font, amount, x + 15, y + 96, BlockifiedScreenStyle.TEXT_MUTED, false);
            if (!job.error().isBlank()) gui.drawString(font,
                    font.plainSubstrByWidth(job.error(), 320), x + 15, y + 111, 0xFFEE7777, false);
            else if (job.installed() != null) gui.drawString(font, "Installed: " +
                    font.plainSubstrByWidth(job.installed().getFileName().toString(), 270),
                    x + 15, y + 111, 0xFF8BE69D, false);
        }
        super.render(gui, mouseX, mouseY, partialTick);
    }

    private static String bytes(long count) {
        return count >= 1_000_000_000L
                ? String.format(java.util.Locale.ROOT, "%.2f GB", count / 1_000_000_000.0)
                : String.format(java.util.Locale.ROOT, "%.1f MB", count / 1_000_000.0);
    }

    @Override public void renderBackground(GuiGraphics gui, int mouseX, int mouseY, float partialTick) {}

    @Override public void onClose() {
        ModDownloadManager.collapse();
        Minecraft.getInstance().setScreen(parent);
    }
}
