package com.fnfmod.client.gui;

import com.fnfmod.client.world.ModWorlds;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

/** Lists the Minecraft worlds bundled inside FNF mod packs and loads the chosen one. */
public class ModWorldSelectScreen extends Screen {

    private final Screen parent;
    private List<ModWorlds.Entry> worlds = List.of();
    private int scroll;

    private static final int ROW_HEIGHT = 22;
    private static final int TOP = 40;
    private static final int BOTTOM_MARGIN = 40;

    public ModWorldSelectScreen(Screen parent) {
        super(Component.literal("Mod Worlds"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        worlds = ModWorlds.scan();
        int maxScroll = Math.max(0, worlds.size() - visibleRows());
        scroll = Math.max(0, Math.min(scroll, maxScroll));
        rebuild();
    }

    private void rebuild() {
        clearWidgets();
        int width = 300;
        int x = this.width / 2 - width / 2;
        int y = TOP;
        int rows = visibleRows();
        for (int i = 0; i < rows && scroll + i < worlds.size(); i++) {
            ModWorlds.Entry entry = worlds.get(scroll + i);
            addRenderableWidget(Button.builder(
                            Component.literal(entry.displayName()),
                            b -> ModWorlds.play(minecraft, entry))
                    .bounds(x, y, width, 20).build());
            y += ROW_HEIGHT;
        }

        if (worlds.size() > rows) {
            addRenderableWidget(Button.builder(Component.literal("▲"), b -> {
                scroll = Math.max(0, scroll - 1);
                rebuild();
            }).bounds(x + width + 4, TOP, 20, 20).build());
            addRenderableWidget(Button.builder(Component.literal("▼"), b -> {
                scroll = Math.min(Math.max(0, worlds.size() - rows), scroll + 1);
                rebuild();
            }).bounds(x + width + 4, TOP + rows * ROW_HEIGHT - 20, 20, 20).build());
        }

        addRenderableWidget(Button.builder(Component.literal("Back"), b -> onClose())
                .bounds(this.width / 2 - 100, this.height - 28, 200, 20).build());
    }

    private int visibleRows() {
        return Math.max(1, (height - TOP - BOTTOM_MARGIN) / ROW_HEIGHT);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (worlds.size() > visibleRows()) {
            int max = Math.max(0, worlds.size() - visibleRows());
            scroll = Math.max(0, Math.min(max, scroll - (int) Math.signum(scrollY)));
            rebuild();
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public void render(GuiGraphics gui, int mouseX, int mouseY, float partialTick) {
        super.render(gui, mouseX, mouseY, partialTick);
        gui.drawCenteredString(font, title, this.width / 2, 16, 0xFFFFFF);
        if (worlds.isEmpty()) {
            gui.drawCenteredString(font, Component.literal(
                    "No bundled worlds found."), this.width / 2, this.height / 2 - 10, 0xFFAAAAAA);
            gui.drawCenteredString(font, Component.literal(
                    "Add them under config/fnfmod/mods/<mod>/worlds/<world>/"),
                    this.width / 2, this.height / 2 + 4, 0xFF888888);
        }
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }
}
