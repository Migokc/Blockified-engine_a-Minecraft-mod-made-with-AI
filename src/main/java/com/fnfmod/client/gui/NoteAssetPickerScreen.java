package com.fnfmod.client.gui;

import com.fnfmod.client.ClientOptions;
import com.fnfmod.client.math.Easing;
import com.fnfmod.client.render.NoteStyle;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Searchable note-art chooser with an animated preview. */
public final class NoteAssetPickerScreen extends Screen implements TextInputAwareScreen {
    private enum Tab { CURRENT_MOD, GLOBAL }

    public enum Kind {
        NOTE_SKIN("Note Skin", "Search note skins..."),
        SPLASH("Note Splash", "Search splashes..."),
        HOLD_COVER("Hold Splash", "Search hold splashes...");

        final String title;
        final String hint;
        Kind(String title, String hint) {
            this.title = title;
            this.hint = hint;
        }
    }

    private static final int ROW = 24;
    private static final long SCROLL_TWEEN_NANOS = 200_000_000L;
    private static final int UP_LANE = 2;

    private final NoteSettingsScreen parent;
    private final Kind kind;
    private final String original;
    private final String originalSource;
    private Tab tab = Tab.GLOBAL;
    private EditBox search;
    private List<String> all = List.of();
    private List<String> visible = List.of();
    private int selectedIndex;
    private double scrollPx;
    private double scrollTargetPx;
    private double scrollFromPx;
    private long scrollTweenStart;
    private boolean scrollTweenActive;

    public NoteAssetPickerScreen(NoteSettingsScreen parent, Kind kind) {
        super(Component.literal(kind.title));
        this.parent = parent;
        this.kind = kind;
        this.original = currentValue();
        this.originalSource = currentSource();
    }

    @Override
    protected void init() {
        tab = initialTab();
        reloadTabValues(true);

        search = addRenderableWidget(new EditBox(font, listX(), panelY() + 28,
                listWidth(), 18, Component.literal(kind.hint)));
        search.setHint(Component.literal(kind.hint));
        search.setMaxLength(128);
        search.setResponder(this::filter);
        setFocused(search);
        search.setFocused(true);
    }

    private int panelWidth() { return Math.min(560, Math.max(300, width - 28)); }
    private int panelHeight() { return Math.min(340, Math.max(210, height - 28)); }
    private int panelX() { return (width - panelWidth()) / 2; }
    private int panelY() { return Math.max(7, (height - panelHeight()) / 2); }
    private int listX() { return panelX() + 12; }
    private int listWidth() { return Math.max(136, (panelWidth() - 36) / 2); }
    private int previewX() { return listX() + listWidth() + 12; }
    private int previewWidth() { return panelX() + panelWidth() - 12 - previewX(); }
    private int tabY() { return panelY() + 50; }
    private int listTop() { return panelY() + 74; }
    private int listHeight() { return Math.max(ROW, ((panelHeight() - 122) / ROW) * ROW); }
    private double maxScroll() { return Math.max(0, visible.size() * (double) ROW - listHeight()); }

    private Tab initialTab() {
        if (ClientOptions.NOTE_ASSET_SOURCE_CURRENT.equalsIgnoreCase(originalSource)) {
            return Tab.CURRENT_MOD;
        }
        if (ClientOptions.NOTE_ASSET_SOURCE_GLOBAL.equalsIgnoreCase(originalSource)) return Tab.GLOBAL;
        return indexOf(valuesFor(Tab.CURRENT_MOD), original) >= 0 ? Tab.CURRENT_MOD : Tab.GLOBAL;
    }

    private List<String> valuesFor(Tab wanted) {
        ArrayList<String> values = new ArrayList<>();
        if (wanted == Tab.GLOBAL && kind == Kind.SPLASH) values.add("");
        values.addAll(switch (kind) {
            case NOTE_SKIN -> wanted == Tab.CURRENT_MOD
                    ? NoteStyle.listCurrentModSkins() : NoteStyle.listGlobalSkins();
            case SPLASH -> wanted == Tab.CURRENT_MOD
                    ? NoteStyle.listCurrentModSplashes() : NoteStyle.listGlobalSplashes();
            case HOLD_COVER -> wanted == Tab.CURRENT_MOD
                    ? NoteStyle.listCurrentModHoldSplashes() : NoteStyle.listGlobalHoldSplashes();
        });
        return List.copyOf(values);
    }

    private void reloadTabValues(boolean centerSelection) {
        all = valuesFor(tab);
        String query = search == null ? "" : search.getValue();
        visible = filteredValues(query);
        boolean sameSource = sourceFor(tab).equalsIgnoreCase(currentSource())
                || ClientOptions.NOTE_ASSET_SOURCE_AUTO.equalsIgnoreCase(currentSource());
        selectedIndex = sameSource ? indexOf(visible, currentValue()) : -1;
        resetScroll(centerSelection);
    }

    private List<String> filteredValues(String text) {
        String query = text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
        return query.isEmpty() ? all : all.stream()
                .filter(value -> displayName(value).toLowerCase(Locale.ROOT).contains(query)).toList();
    }

    private void filter(String text) {
        visible = filteredValues(text);
        int current = indexOf(visible, currentValue());
        // Do not claim the first search result is being previewed until the user
        // actually selects it; the currently loaded asset may no longer be visible.
        selectedIndex = current;
        resetScroll(false);
    }

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
        } else {
            double eased = Easing.apply("expoOut", progress);
            scrollPx = scrollFromPx + (scrollTargetPx - scrollFromPx) * eased;
        }
    }

    private void scrollTo(double target) {
        target = Mth.clamp(target, 0, maxScroll());
        if (Math.abs(target - scrollTargetPx) < 0.01) return;
        updateScrollTween();
        scrollFromPx = scrollPx;
        scrollTargetPx = target;
        scrollTweenStart = System.nanoTime();
        scrollTweenActive = Math.abs(scrollTargetPx - scrollFromPx) > 0.01;
    }

    private void clampSelection() {
        if (visible.isEmpty()) {
            selectedIndex = 0;
            return;
        }
        selectedIndex = Mth.clamp(selectedIndex, 0, visible.size() - 1);
        double top = selectedIndex * (double) ROW;
        double target = scrollTargetPx;
        if (top < target) target = top;
        else if (top + ROW > target + listHeight()) target = top + ROW - listHeight();
        scrollTo(target);
    }

    private void previewSelection() {
        if (visible.isEmpty()) return;
        clampSelection();
        parent.previewNoteAsset(kind, visible.get(selectedIndex), sourceFor(tab));
    }

    private void accept() {
        previewSelection();
        minecraft.setScreen(parent);
    }

    private void cancel() {
        parent.previewNoteAsset(kind, original, originalSource);
        minecraft.setScreen(parent);
    }

    @Override
    public void render(GuiGraphics gui, int mouseX, int mouseY, float partialTick) {
        BlockifiedScreenStyle.backdrop(gui, width, height);
        int x0 = panelX(), y0 = panelY(), boxW = panelWidth(), boxH = panelHeight();
        BlockifiedScreenStyle.panel(gui, x0, y0, boxW, boxH);
        gui.drawCenteredString(font, title, x0 + boxW / 2, y0 + 10, 0xFFFFFFFF);

        int tabWidth = (listWidth() - 4) / 2;
        renderTab(gui, listX(), tabY(), tabWidth, "Current Mod", tab == Tab.CURRENT_MOD,
                mouseX, mouseY);
        renderTab(gui, listX() + tabWidth + 4, tabY(), listWidth() - tabWidth - 4,
                "Global", tab == Tab.GLOBAL, mouseX, mouseY);

        updateScrollTween();
        int top = listTop(), listH = listHeight();
        gui.enableScissor(listX(), top, listX() + listWidth(), top + listH);
        int first = Math.max(0, (int) Math.floor(scrollPx / ROW));
        double offset = scrollPx - first * (double) ROW;
        for (int row = 0; row < listH / ROW + 2; row++) {
            int index = first + row;
            if (index >= visible.size()) break;
            int rowY = top + row * ROW - (int) Math.round(offset);
            boolean selected = index == selectedIndex;
            boolean hovered = mouseX >= listX() && mouseX < listX() + listWidth()
                    && mouseY >= Math.max(top, rowY) && mouseY < Math.min(top + listH, rowY + ROW - 1);
            gui.fill(listX(), rowY, listX() + listWidth(), rowY + ROW - 1,
                    selected ? BlockifiedScreenStyle.ACCENT_DARK
                            : hovered ? BlockifiedScreenStyle.ACCENT_DEEP : 0xFF20202A);
            gui.drawString(font, displayName(visible.get(index)), listX() + 7, rowY + 8,
                    selected ? 0xFFFFFFFF : 0xFFE0D9E5, false);
        }
        gui.disableScissor();
        renderScrollbar(gui, top, listH);
        if (visible.isEmpty()) {
            gui.drawCenteredString(font,
                    tab == Tab.CURRENT_MOD ? "No assets in current mod" : "No global assets",
                    listX() + listWidth() / 2, top + listH / 2 - 4,
                    BlockifiedScreenStyle.TEXT_MUTED);
        }

        BlockifiedScreenStyle.inner(gui, previewX(), top, previewWidth(), listH);
        gui.drawCenteredString(font, "Preview", previewX() + previewWidth() / 2,
                top + 10, BlockifiedScreenStyle.TEXT_MUTED);
        renderPreview(gui, previewX() + previewWidth() / 2f, top + listH / 2f + 5);

        int buttonY = y0 + boxH - 27;
        int buttonWidth = (boxW - 28) / 2;
        renderButton(gui, x0 + 10, buttonY, buttonWidth, "Cancel", mouseX, mouseY);
        renderButton(gui, x0 + 18 + buttonWidth, buttonY, buttonWidth, "Select", mouseX, mouseY);
        super.render(gui, mouseX, mouseY, partialTick);
    }

    private void renderPreview(GuiGraphics gui, float centerX, float centerY) {
        if (selectedIndex < 0 || selectedIndex >= visible.size()) {
            gui.drawCenteredString(font, "Select an item to preview", (int) centerX, (int) centerY - 4,
                    BlockifiedScreenStyle.TEXT_MUTED);
            return;
        }
        String selected = visible.get(selectedIndex);
        if (kind == Kind.SPLASH && selected.isBlank()
                || selected.equalsIgnoreCase(ClientOptions.NOTE_SKIN_NONE)) {
            gui.drawCenteredString(font, "Disabled", (int) centerX, (int) centerY - 4,
                    BlockifiedScreenStyle.TEXT_MUTED);
            return;
        }
        long frame = System.nanoTime() / 41_666_667L; // independent 24 FPS, like 2D characters
        switch (kind) {
            case NOTE_SKIN -> NoteStyle.drawNote(gui, UP_LANE, centerX, centerY, 82);
            case SPLASH -> {
                int variants = NoteStyle.splashVariants(UP_LANE);
                int count = variants == 0 ? 0 : NoteStyle.splashFrameCount(UP_LANE, 0);
                if (count > 0) NoteStyle.drawSplash(gui, UP_LANE, 0,
                        (int) Math.floorMod(frame, count), centerX, centerY, 116);
                else drawUnavailable(gui, centerX, centerY);
            }
            case HOLD_COVER -> {
                int count = NoteStyle.holdCoverFrames(UP_LANE);
                if (NoteStyle.hasHoldCover(UP_LANE) && count > 0) {
                    NoteStyle.drawReceptor(gui, UP_LANE, centerX, centerY, 70, 2);
                    NoteStyle.drawHoldCover(gui, UP_LANE, frame, centerX, centerY, 70);
                } else drawUnavailable(gui, centerX, centerY);
            }
        }
        gui.setColor(1f, 1f, 1f, 1f);
    }

    private void drawUnavailable(GuiGraphics gui, float x, float y) {
        gui.drawCenteredString(font, "No preview animation found", (int) x, (int) y - 4,
                BlockifiedScreenStyle.TEXT_MUTED);
    }

    private void renderScrollbar(GuiGraphics gui, int top, int listH) {
        double max = maxScroll();
        if (max <= 0) return;
        int x = listX() + listWidth() - 3;
        int thumbH = Math.max(12, (int) Math.round(listH * (listH / (visible.size() * (double) ROW))));
        int thumbY = top + (int) Math.round((listH - thumbH) * (scrollPx / max));
        gui.fill(x, top, x + 2, top + listH, 0xFF252733);
        gui.fill(x, thumbY, x + 2, thumbY + thumbH, BlockifiedScreenStyle.ACCENT);
    }

    private void renderButton(GuiGraphics gui, int x, int y, int w, String text, int mouseX, int mouseY) {
        boolean hover = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + 18;
        gui.fill(x, y, x + w, y + 18, hover ? 0xFF505675 : 0xFF303442);
        gui.renderOutline(x, y, w, 18, 0xFF6A7080);
        gui.drawCenteredString(font, text, x + w / 2, y + 5, 0xFFFFFFFF);
    }

    private void renderTab(GuiGraphics gui, int x, int y, int w, String text, boolean selected,
                           int mouseX, int mouseY) {
        boolean hover = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + 20;
        gui.fill(x, y, x + w, y + 20, selected ? BlockifiedScreenStyle.ACCENT_DARK
                : hover ? BlockifiedScreenStyle.ACCENT_DEEP : 0xFF252733);
        if (selected) gui.fill(x, y + 18, x + w, y + 20, BlockifiedScreenStyle.ACCENT);
        gui.drawCenteredString(font, text, x + w / 2, y + 6,
                selected ? 0xFFFFFFFF : BlockifiedScreenStyle.TEXT_MUTED);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            cancel();
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
            accept();
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_UP || keyCode == GLFW.GLFW_KEY_DOWN) {
            selectedIndex += keyCode == GLFW.GLFW_KEY_UP ? -1 : 1;
            clampSelection();
            previewSelection();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            int tabWidth = (listWidth() - 4) / 2;
            if (mouseY >= tabY() && mouseY < tabY() + 20) {
                Tab clicked = null;
                if (mouseX >= listX() && mouseX < listX() + tabWidth) clicked = Tab.CURRENT_MOD;
                else if (mouseX >= listX() + tabWidth + 4
                        && mouseX < listX() + listWidth()) clicked = Tab.GLOBAL;
                if (clicked != null) {
                    tab = clicked;
                    reloadTabValues(true);
                    return true;
                }
            }
            int top = listTop();
            if (mouseX >= listX() && mouseX < listX() + listWidth()
                    && mouseY >= top && mouseY < top + listHeight()) {
                search.setFocused(false);
                setFocused(null);
                updateScrollTween();
                int index = (int) Math.floor((mouseY - top + scrollPx) / ROW);
                if (index >= 0 && index < visible.size()) {
                    selectedIndex = index;
                    previewSelection();
                }
                return true;
            }
            int buttonY = panelY() + panelHeight() - 27;
            int buttonWidth = (panelWidth() - 28) / 2;
            if (mouseY >= buttonY && mouseY < buttonY + 18
                    && mouseX >= panelX() + 10 && mouseX < panelX() + 10 + buttonWidth) {
                cancel();
                return true;
            }
            if (mouseY >= buttonY && mouseY < buttonY + 18
                    && mouseX >= panelX() + 18 + buttonWidth
                    && mouseX < panelX() + 18 + buttonWidth * 2) {
                accept();
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (scrollY != 0 && mouseX >= listX() && mouseX < listX() + listWidth()) {
            scrollTo(scrollTargetPx - scrollY * ROW * 1.5);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override public void onClose() { cancel(); }
    @Override public void renderBackground(GuiGraphics gui, int mouseX, int mouseY, float partialTick) {}
    @Override public boolean isTextInputActive() { return search != null && search.isFocused(); }
    @Override public boolean isPauseScreen() { return false; }

    private String currentValue() {
        return switch (kind) {
            case NOTE_SKIN -> ClientOptions.get().noteSkin;
            case SPLASH -> ClientOptions.get().splashSkin;
            case HOLD_COVER -> ClientOptions.get().holdSplashSkin;
        };
    }

    private String currentSource() {
        return switch (kind) {
            case NOTE_SKIN -> ClientOptions.get().noteSkinSource;
            case SPLASH -> ClientOptions.get().splashSkinSource;
            case HOLD_COVER -> ClientOptions.get().holdSplashSkinSource;
        };
    }

    private static String sourceFor(Tab tab) {
        return tab == Tab.CURRENT_MOD ? ClientOptions.NOTE_ASSET_SOURCE_CURRENT
                : ClientOptions.NOTE_ASSET_SOURCE_GLOBAL;
    }

    private String displayName(String value) {
        if (value == null || value.isBlank()) return "None";
        if (value.equalsIgnoreCase(ClientOptions.NOTE_SKIN_NONE)) return "None";
        if (value.equalsIgnoreCase(ClientOptions.NOTE_SKIN_DEFAULT)) {
            return kind == Kind.NOTE_SKIN ? "Default (current chart)" : "Default (current chart)";
        }
        return value;
    }

    private static int indexOf(List<String> values, String wanted) {
        String match = wanted == null ? "" : wanted;
        for (int i = 0; i < values.size(); i++) {
            if (values.get(i).equalsIgnoreCase(match)) return i;
        }
        return -1;
    }
}
