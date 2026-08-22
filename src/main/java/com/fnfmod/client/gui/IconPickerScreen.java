package com.fnfmod.client.gui;

import com.fnfmod.client.ClientOptions;
import com.fnfmod.client.math.Easing;
import com.fnfmod.client.render.IconLibrary;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Foreground searchable picker for a settings health icon. */
public class IconPickerScreen extends Screen implements TextInputAwareScreen {
    private static final int ROW = 24;
    private static final long SCROLL_TWEEN_NANOS = 200_000_000L;

    private final Screen parent;
    private final boolean player;
    private EditBox search;
    private List<String> all = List.of();
    private List<String> visible = List.of();
    private int selectedIndex;
    private double scrollPx;
    private double scrollTargetPx;
    private double scrollFromPx;
    private long scrollTweenStart;
    private boolean scrollTweenActive;

    public IconPickerScreen(Screen parent, boolean player) {
        super(Component.literal(player ? "Player Icon" : "Opponent Icon"));
        this.parent = parent;
        this.player = player;
    }

    @Override
    protected void init() {
        ArrayList<String> icons = new ArrayList<>();
        icons.add(ClientOptions.SONG_ICON);
        icons.add("");
        icons.addAll(IconLibrary.list());
        all = icons;
        visible = List.copyOf(all);
        String current = player ? ClientOptions.get().playerIcon : ClientOptions.get().botIcon;
        selectedIndex = Math.max(0, indexOf(visible, current));
        resetScroll(true);

        search = addRenderableWidget(new EditBox(font, panelX() + 12, panelY() + 26,
                panelWidth() - 24, 18, Component.literal("Search icons")));
        search.setHint(Component.literal("Search icons..."));
        search.setMaxLength(128);
        search.setResponder(this::filter);
        setFocused(search);
        search.setFocused(true);
    }

    private void filter(String queryText) {
        String query = queryText == null ? "" : queryText.trim().toLowerCase(Locale.ROOT);
        if (query.isEmpty()) {
            visible = List.copyOf(all);
        } else {
            ArrayList<String> filtered = new ArrayList<>();
            filtered.add(ClientOptions.SONG_ICON);
            filtered.add("");
            for (String icon : all) {
                if (!icon.isEmpty() && !icon.equals(ClientOptions.SONG_ICON)
                        && icon.toLowerCase(Locale.ROOT).contains(query)) filtered.add(icon);
            }
            visible = filtered;
        }
        selectedIndex = 0;
        resetScroll(false);
    }

    private int panelWidth() { return Math.min(380, Math.max(250, width - 32)); }

    private int panelHeight() { return Math.min(310, Math.max(180, height - 32)); }

    private int panelX() { return (width - panelWidth()) / 2; }

    private int panelY() { return Math.max(8, (height - panelHeight()) / 2); }

    private int listTop() { return panelY() + 50; }

    private int listHeight() { return Math.max(ROW, ((panelHeight() - 96) / ROW) * ROW); }

    private double maxScroll() { return Math.max(0, visible.size() * (double) ROW - listHeight()); }

    private void resetScroll(boolean centerSelection) {
        double target = centerSelection ? selectedIndex * (double) ROW - (listHeight() - ROW) / 2.0 : 0;
        scrollPx = scrollTargetPx = scrollFromPx = Mth.clamp(target, 0, maxScroll());
        scrollTweenActive = false;
    }

    private void updateScrollTween() {
        if (!scrollTweenActive) return;
        double progress = (System.nanoTime() - scrollTweenStart) / (double) SCROLL_TWEEN_NANOS;
        if (progress >= 1) {
            scrollPx = scrollTargetPx;
            scrollTweenActive = false;
            return;
        }
        double eased = Easing.apply("expoOut", progress);
        scrollPx = scrollFromPx + (scrollTargetPx - scrollFromPx) * eased;
    }

    private void scrollTo(double target) {
        target = Mth.clamp(target, 0, maxScroll());
        if (Math.abs(target - scrollTargetPx) < 0.01) return;
        updateScrollTween();
        scrollFromPx = scrollPx;
        scrollTargetPx = target;
        scrollTweenStart = System.nanoTime();
        scrollTweenActive = Math.abs(scrollTargetPx - scrollFromPx) > 0.01;
        if (!scrollTweenActive) scrollPx = scrollTargetPx;
    }

    private void clampSelection() {
        if (visible.isEmpty()) {
            selectedIndex = 0;
            resetScroll(false);
            return;
        }
        selectedIndex = Mth.clamp(selectedIndex, 0, visible.size() - 1);
        double top = selectedIndex * (double) ROW;
        double target = scrollTargetPx;
        if (top < target) target = top;
        else if (top + ROW > target + listHeight()) target = top + ROW - listHeight();
        scrollTo(target);
    }

    private void choose() {
        if (visible.isEmpty()) return;
        clampSelection();
        String name = visible.get(selectedIndex);
        if (player) ClientOptions.get().playerIcon = name;
        else ClientOptions.get().botIcon = name;
        ClientOptions.save();
        onClose();
    }

    @Override
    public void render(GuiGraphics gui, int mouseX, int mouseY, float partialTick) {
        BlockifiedScreenStyle.backdrop(gui, width, height);
        int x0 = panelX(), y0 = panelY(), boxW = panelWidth(), boxH = panelHeight();
        BlockifiedScreenStyle.panel(gui, x0, y0, boxW, boxH);
        gui.drawCenteredString(font, title, x0 + boxW / 2, y0 + 9, 0xFFFFFFFF);

        if (visible.isEmpty()) selectedIndex = 0;
        else selectedIndex = Mth.clamp(selectedIndex, 0, visible.size() - 1);
        updateScrollTween();
        int top = listTop(), height = listHeight();
        int first = Math.max(0, (int) Math.floor(scrollPx / ROW));
        double offset = scrollPx - first * (double) ROW;
        gui.enableScissor(x0 + 12, top, x0 + boxW - 12, top + height);
        for (int row = 0; row < height / ROW + 2; row++) {
            int index = first + row;
            if (index >= visible.size()) break;
            String name = visible.get(index);
            int rowY = top + row * ROW - (int) Math.round(offset);
            boolean selected = index == selectedIndex;
            boolean hovered = mouseX >= x0 + 12 && mouseX < x0 + boxW - 12
                    && mouseY >= Math.max(top, rowY) && mouseY < Math.min(top + height, rowY + ROW - 1);
            gui.fill(x0 + 12, rowY, x0 + boxW - 12, rowY + ROW - 1,
                    selected ? BlockifiedScreenStyle.ACCENT_DARK
                            : hovered ? BlockifiedScreenStyle.ACCENT_DEEP : 0xFF20202A);
            if (name.equals(ClientOptions.SONG_ICON)) {
                gui.drawString(font, "Default (current song)", x0 + 18, rowY + 8, 0xFFFFFFFF, false);
            } else if (name.isEmpty()) {
                gui.drawString(font, "None", x0 + 18, rowY + 8, 0xFFCCCCCC, false);
            } else {
                IconLibrary.draw(gui, name, 0, x0 + 23, rowY + ROW / 2f, 20);
                gui.drawString(font, name, x0 + 39, rowY + 8, 0xFFFFFFFF, false);
            }
        }
        gui.disableScissor();

        double max = maxScroll();
        if (max > 0) {
            int trackX = x0 + boxW - 9;
            int thumbHeight = Math.max(12, (int) Math.round(height * (height / (visible.size() * (double) ROW))));
            int thumbY = top + (int) Math.round((height - thumbHeight) * (scrollPx / max));
            gui.fill(trackX, top, trackX + 2, top + height, 0xFF252733);
            gui.fill(trackX, thumbY, trackX + 2, thumbY + thumbHeight,
                    BlockifiedScreenStyle.ACCENT);
        }

        int buttonY = y0 + boxH - 27;
        int buttonWidth = (boxW - 28) / 2;
        renderButton(gui, x0 + 10, buttonY, buttonWidth, "Cancel", mouseX, mouseY);
        renderButton(gui, x0 + 18 + buttonWidth, buttonY, buttonWidth, "Select", mouseX, mouseY);
        super.render(gui, mouseX, mouseY, partialTick);
    }

    private void renderButton(GuiGraphics gui, int x, int y, int buttonWidth, String text,
                              int mouseX, int mouseY) {
        boolean hover = mouseX >= x && mouseX < x + buttonWidth && mouseY >= y && mouseY < y + 18;
        gui.fill(x, y, x + buttonWidth, y + 18, hover ? 0xFF505675 : 0xFF303442);
        gui.renderOutline(x, y, buttonWidth, 18, 0xFF6A7080);
        gui.drawCenteredString(font, text, x + buttonWidth / 2, y + 5, 0xFFFFFFFF);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            onClose();
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
            choose();
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_UP || keyCode == GLFW.GLFW_KEY_DOWN) {
            selectedIndex += keyCode == GLFW.GLFW_KEY_UP ? -1 : 1;
            clampSelection();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            int x0 = panelX(), y0 = panelY(), boxW = panelWidth(), boxH = panelHeight();
            int top = listTop();
            if (mouseX >= x0 + 12 && mouseX < x0 + boxW - 12
                    && mouseY >= top && mouseY < top + listHeight()) {
                search.setFocused(false);
                setFocused(null);
                updateScrollTween();
                int index = (int) Math.floor((mouseY - top + scrollPx) / ROW);
                if (index >= 0 && index < visible.size()) selectedIndex = index;
                return true;
            }
            int buttonY = y0 + boxH - 27;
            int buttonWidth = (boxW - 28) / 2;
            if (mouseY >= buttonY && mouseY < buttonY + 18
                    && mouseX >= x0 + 10 && mouseX < x0 + 10 + buttonWidth) {
                onClose();
                return true;
            }
            if (mouseY >= buttonY && mouseY < buttonY + 18
                    && mouseX >= x0 + 18 + buttonWidth && mouseX < x0 + 18 + buttonWidth * 2) {
                choose();
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (scrollY != 0) {
            // Wheel movement navigates the viewport; selection changes only by
            // click/keyboard so scrolling a long icon library cannot overwrite it.
            scrollTo(scrollTargetPx - scrollY * ROW * 1.5);
        }
        return true;
    }

    @Override
    public void renderBackground(GuiGraphics gui, int mouseX, int mouseY, float partialTick) {
        // render() owns the opaque foreground dialog and avoids menu-blur overlap.
    }

    @Override
    public void onClose() {
        if (minecraft != null) minecraft.setScreen(parent);
    }

    @Override
    public boolean isTextInputActive() {
        return search != null && search.isFocused();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private static int indexOf(List<String> values, String wanted) {
        if (wanted == null) return -1;
        for (int i = 0; i < values.size(); i++) {
            if (values.get(i).equalsIgnoreCase(wanted)) return i;
        }
        return -1;
    }
}
