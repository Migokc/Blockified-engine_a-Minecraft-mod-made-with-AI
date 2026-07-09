package com.fnfmod.client.gui;

import com.fnfmod.client.ClientOptions;
import com.fnfmod.client.render.IconLibrary;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Searchable list picker for a health icon (player or bot). */
public class IconPickerScreen extends Screen {

    private final Screen parent;
    private final boolean player;
    private EditBox search;
    private List<String> all;
    private List<String> visible;
    private double scroll;
    private static final int ROW = 30;

    public IconPickerScreen(Screen parent, boolean player) {
        super(Component.literal(player ? "Player Icon" : "Bot Icon"));
        this.parent = parent;
        this.player = player;
    }

    @Override
    protected void init() {
        all = new ArrayList<>();
        all.add(""); // "none"
        all.addAll(IconLibrary.list());
        visible = all;

        search = addRenderableWidget(new EditBox(font, listX(), 30, listW(), 16, Component.literal("search")));
        search.setHint(Component.literal("Search icons..."));
        search.setResponder(this::filter);

        addRenderableWidget(Button.builder(Component.literal("Back"), b -> onClose())
                .bounds(width / 2 - 60, height - 28, 120, 20).build());
    }

    private void filter(String q) {
        String query = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);
        if (query.isEmpty()) {
            visible = all;
        } else {
            visible = new ArrayList<>();
            visible.add("");
            for (String n : all) {
                if (!n.isEmpty() && n.toLowerCase(Locale.ROOT).contains(query)) visible.add(n);
            }
        }
        scroll = 0;
    }

    private int listX() { return width / 2 - 130; }
    private int listW() { return 260; }
    private int listTop() { return 54; }
    private int listBottom() { return height - 34; }

    private void choose(String name) {
        if (player) ClientOptions.get().playerIcon = name;
        else ClientOptions.get().botIcon = name;
        ClientOptions.save();
        onClose();
    }

    @Override
    public void render(GuiGraphics gui, int mouseX, int mouseY, float partialTick) {
        super.render(gui, mouseX, mouseY, partialTick);
        gui.drawCenteredString(font, title, width / 2, 14, 0xFFFFFF);

        int x = listX(), w = listW(), top = listTop(), bottom = listBottom();
        gui.fill(x - 4, top - 2, x + w + 4, bottom + 2, 0x88000000);
        gui.enableScissor(x - 4, top, x + w + 4, bottom);

        String current = player ? ClientOptions.get().playerIcon : ClientOptions.get().botIcon;
        int maxVisible = (bottom - top) / ROW + 1;
        int first = (int) scroll;
        for (int i = first; i < Math.min(visible.size(), first + maxVisible + 1); i++) {
            String name = visible.get(i);
            int ry = top + (i - first) * ROW;
            boolean hover = mouseX >= x && mouseX < x + w && mouseY >= ry && mouseY < ry + ROW;
            boolean sel = name.equals(current);
            if (sel) gui.fill(x, ry, x + w, ry + ROW, 0x66FF44AA);
            else if (hover) gui.fill(x, ry, x + w, ry + ROW, 0x33FFFFFF);
            if (name.isEmpty()) {
                gui.drawString(font, "None", x + 34, ry + ROW / 2 - 4, 0xCCCCCC);
            } else {
                IconLibrary.draw(gui, name, 0, x + 16, ry + ROW / 2f, 26);
                gui.drawString(font, name, x + 34, ry + ROW / 2 - 4, 0xFFFFFF);
            }
        }
        gui.disableScissor();
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        int x = listX(), w = listW(), top = listTop(), bottom = listBottom();
        if (button == 0 && mx >= x && mx < x + w && my >= top && my < bottom) {
            int idx = (int) scroll + (int) ((my - top) / ROW);
            if (idx >= 0 && idx < visible.size()) {
                choose(visible.get(idx));
                return true;
            }
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        int maxVisible = (listBottom() - listTop()) / ROW;
        scroll = Mth.clamp(scroll - sy, 0, Math.max(0, visible.size() - maxVisible));
        return true;
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
