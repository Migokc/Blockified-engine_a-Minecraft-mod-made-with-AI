package com.fnfmod.client.gui.machine;

import com.fnfmod.client.gui.BlockifiedScreenStyle;
import com.fnfmod.machine.MachineDefinition;
import com.fnfmod.machine.MachineLibrary;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.Comparator;
import java.util.List;

/** Profile picker for the visual Menu Lua Editor. */
public final class MachineMenuEditorSelectScreen extends Screen {
    private final Screen parent;
    private List<MachineDefinition> profiles = List.of();
    private int scroll;

    public MachineMenuEditorSelectScreen(Screen parent) {
        super(Component.literal("Menu Lua Editor"));
        this.parent = parent;
    }

    @Override protected void init() {
        profiles = MachineLibrary.all().values().stream()
                .filter(value -> !value.builtIn() && value.menuScript() != null)
                .sorted(Comparator.comparing(MachineDefinition::displayName, String.CASE_INSENSITIVE_ORDER))
                .toList();
        scroll = Math.max(0, Math.min(scroll, Math.max(0, profiles.size() - visibleRows())));
        int w = Math.min(420, width - 32), x = (width - w) / 2, y = 58;
        for (int i = 0; i < visibleRows() && scroll + i < profiles.size(); i++) {
            MachineDefinition profile = profiles.get(scroll + i);
            addRenderableWidget(Button.builder(Component.literal(profile.displayName()), button ->
                    minecraft.setScreen(new MachineMenuEditorScreen(this, profile)))
                    .bounds(x, y + i * 24, w, 20).build());
        }
        addRenderableWidget(Button.builder(Component.literal("Back"), button -> onClose())
                .bounds(width / 2 - 70, height - 30, 140, 20).build());
    }

    private int visibleRows() { return Math.max(1, (height - 104) / 24); }

    @Override public boolean mouseScrolled(double x, double y, double sx, double sy) {
        int next = Math.max(0, Math.min(Math.max(0, profiles.size() - visibleRows()), scroll - (int) Math.signum(sy)));
        if (next != scroll) { scroll = next; rebuildWidgets(); }
        return true;
    }

    @Override public void render(GuiGraphics gui, int mouseX, int mouseY, float partialTick) {
        BlockifiedScreenStyle.backdrop(gui, width, height);
        BlockifiedScreenStyle.panel(gui, width / 2 - Math.min(230, width / 2 - 8), 8,
                Math.min(460, width - 16), height - 16);
        gui.drawCenteredString(font, "MENU LUA EDITOR", width / 2, 20, BlockifiedScreenStyle.ACCENT);
        gui.drawCenteredString(font, "Choose the machine profile whose menu you want to edit.",
                width / 2, 36, BlockifiedScreenStyle.TEXT_MUTED);
        super.render(gui, mouseX, mouseY, partialTick);
        if (profiles.isEmpty()) gui.drawCenteredString(font, "No editable menu.lua profiles in this mod.",
                width / 2, height / 2, 0xFFFF8888);
    }

    @Override public void renderBackground(GuiGraphics gui, int mouseX, int mouseY, float partialTick) {}
    @Override public void onClose() { minecraft.setScreen(parent); }
}
