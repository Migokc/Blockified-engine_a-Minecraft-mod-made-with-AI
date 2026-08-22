package com.fnfmod.client.gui;

import com.fnfmod.client.math.Easing;
import com.fnfmod.client.render.IconLibrary;
import com.fnfmod.song.SongEntry;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Centered Song-Selector-style chart picker for Note Settings. */
public final class NotePreviewChartPickerScreen extends Screen {
    private static final int CARD_W = 84, CARD_H = 92, GAP = 6, LAYER = BlockifiedScreenStyle.PANEL_SOFT;
    private static final int DIFFICULTY_ROW_H = 13;
    private static final long SCROLL_NANOS = 200_000_000L;
    private final NoteSettingsScreen parent;
    private final List<SongEntry> all;
    private List<SongEntry> filtered;
    private SongEntry difficultySong;
    private int difficultyAnchorX, difficultyAnchorY;
    private EditBox search;
    private double scroll, scrollTarget, scrollFrom;
    private long scrollStarted;
    private boolean scrollTween, draggingThumb;

    public NotePreviewChartPickerScreen(NoteSettingsScreen parent, List<SongEntry> songs) {
        super(Component.literal("Choose Preview Chart"));
        this.parent = parent;
        this.all = songs == null ? List.of() : List.copyOf(songs);
        this.filtered = this.all;
    }

    @Override protected void init() {
        String query = search == null ? "" : search.getValue();
        clearWidgets();
        search = addRenderableWidget(new EditBox(font, panelX(), 28, panelWidth(), 16,
                Component.literal("Search charts")));
        search.setHint(Component.literal("Search songs or mods..."));
        search.setValue(query);
        search.setResponder(this::filter);
        addRenderableWidget(Button.builder(Component.literal("No chart / demo"), b -> select(null))
                .bounds(width / 2 - 124, height - 27, 120, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> onClose())
                .bounds(width / 2 + 4, height - 27, 120, 20).build());
    }

    private void filter(String raw) {
        String query = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (query.isBlank()) filtered = all;
        else {
            filtered = new ArrayList<>();
            for (SongEntry song : all) if (label(song).toLowerCase(Locale.ROOT).contains(query)
                    || song.id != null && song.id.toLowerCase(Locale.ROOT).contains(query)) filtered.add(song);
        }
        scroll = scrollTarget = scrollFrom = 0;
        scrollTween = false;
        difficultySong = null;
    }

    private int panelWidth() { return Math.min(624, Math.max(180, width - 28)); }
    private int panelX() { return (width - panelWidth()) / 2; }
    private int listY() { return 52; }
    private int listHeight() { return Math.max(40, height - listY() - 38); }
    private int itemCount() { return filtered.size(); }
    private int columns() { return Math.max(1, (panelWidth() + GAP) / (CARD_W + GAP)); }
    private int rows() { return (itemCount() + columns() - 1) / columns(); }
    private int contentHeight() { return rows() * (CARD_H + GAP); }
    private double maxScroll() { return Math.max(0, contentHeight() - listHeight()); }
    private boolean hasScrollbar() { return maxScroll() > 0; }
    private int gridLeft() {
        int used = columns() * CARD_W + (columns() - 1) * GAP;
        return panelX() + Math.max(0, (panelWidth() - used) / 2);
    }

    @Override public void render(GuiGraphics gui, int mouseX, int mouseY, float partialTick) {
        int shellX = Math.max(6, panelX() - 10);
        int shellWidth = Math.min(width - 12, panelWidth() + 20);
        BlockifiedScreenStyle.backdrop(gui, width, height);
        BlockifiedScreenStyle.panel(gui, shellX, 6, shellWidth, height - 12);
        BlockifiedScreenStyle.inner(gui, panelX() - 4, listY() - 4,
                panelWidth() + 8, listHeight() + 8);
        super.render(gui, mouseX, mouseY, partialTick);
        updateScroll();
        gui.drawCenteredString(font, "Choose a Preview Chart", width / 2, 14, 0xFFFFFFFF);
        int x = panelX(), y = listY(), w = panelWidth(), h = listHeight();
        gui.fill(x - 4, y - 4, x + w + 4, y + h + 4, LAYER);
        if (filtered.isEmpty()) {
            gui.drawCenteredString(font, "No installed-mod charts found", width / 2, y + 28, 0xFFFF7777);
            return;
        }
        scroll = Mth.clamp(scroll, 0, maxScroll());
        scrollTarget = Mth.clamp(scrollTarget, 0, maxScroll());
        int cols = columns(), left = gridLeft();
        gui.enableScissor(x, y, x + w, y + h);
        for (int i = 0; i < filtered.size(); i++) {
            int bx = left + i % cols * (CARD_W + GAP);
            int by = (int) (y + i / cols * (CARD_H + GAP) - scroll);
            if (by + CARD_H >= y && by <= y + h) drawCard(gui, filtered.get(i), bx, by, mouseX, mouseY);
        }
        gui.disableScissor();
        drawScrollbar(gui);
        if (difficultySong != null) drawDifficultyDropdown(gui, mouseX, mouseY);
    }

    private int difficultyWidth() {
        int widest = 90;
        for (String value : difficulties(difficultySong)) widest = Math.max(widest, font.width(value) + 12);
        return Math.min(180, widest);
    }
    private int difficultyHeight() { return difficulties(difficultySong).size() * DIFFICULTY_ROW_H + 2; }
    private int difficultyX() {
        return Mth.clamp(difficultyAnchorX, panelX(), panelX() + panelWidth() - difficultyWidth());
    }
    private int difficultyY() {
        int wanted = difficultyAnchorY;
        if (wanted + difficultyHeight() > listY() + listHeight()) wanted -= difficultyHeight();
        return Mth.clamp(wanted, listY(), Math.max(listY(), listY() + listHeight() - difficultyHeight()));
    }
    private void drawDifficultyDropdown(GuiGraphics gui, int mouseX, int mouseY) {
        List<String> values = difficulties(difficultySong);
        String current = parent.previewDifficultyFor(difficultySong);
        int x = difficultyX(), y = difficultyY(), w = difficultyWidth(), h = difficultyHeight();
        gui.pose().pushPose();
        gui.pose().translate(0, 0, 200);
        gui.fill(x - 1, y - 1, x + w + 1, y + h + 1, 0xFF000000);
        gui.fill(x, y, x + w, y + h, 0xF0181820);
        for (int i = 0; i < values.size(); i++) {
            int rowY = y + 1 + i * DIFFICULTY_ROW_H;
            boolean hover = mouseX >= x && mouseX < x + w
                    && mouseY >= rowY && mouseY < rowY + DIFFICULTY_ROW_H;
            boolean selected = values.get(i).equalsIgnoreCase(current);
            if (selected) gui.fill(x, rowY, x + w, rowY + DIFFICULTY_ROW_H,
                    BlockifiedScreenStyle.ACCENT_MEDIUM);
            else if (hover) gui.fill(x, rowY, x + w, rowY + DIFFICULTY_ROW_H, 0x33FFFFFF);
            gui.drawString(font, trim(values.get(i), w - 10), x + 5, rowY + 3,
                    selected ? 0xFFFFFFFF : 0xFFCCCCCC, false);
        }
        gui.pose().popPose();
    }

    private void drawCard(GuiGraphics gui, SongEntry song, int x, int y, int mouseX, int mouseY) {
        boolean hover = mouseX >= x && mouseX < x + CARD_W && mouseY >= y && mouseY < y + CARD_H
                && mouseY >= listY() && mouseY < listY() + listHeight();
        gui.fill(x, y, x + CARD_W, y + CARD_H, LAYER);
        if (hover) gui.fill(x, y, x + CARD_W, y + CARD_H, 0x22FFFFFF);
        int iconSize = (int) (CARD_W * .68f);
        float cx = x + CARD_W / 2f, cy = y + 8 + iconSize / 2f;
        String iconPath = song.opponentIconFile == null ? "" : song.opponentIconFile.toString();
        if (IconLibrary.hasFile(iconPath))
            IconLibrary.drawFile(gui, iconPath, 0, cx, cy, iconSize, false);
        else if (IconLibrary.has(song.opponentIcon)) IconLibrary.draw(gui, song.opponentIcon, 0, cx, cy, iconSize);
        int nameTop = y + CARD_H - 22;
        gui.fill(x + 3, nameTop, x + CARD_W - 3, y + CARD_H - 4, LAYER);
        gui.drawCenteredString(font, trim(songName(song), CARD_W - 10), x + CARD_W / 2, nameTop + 6, 0xFFFFFFFF);
    }

    private void drawScrollbar(GuiGraphics gui) {
        if (!hasScrollbar()) return;
        int x = panelX() + panelWidth() - 5, y = listY(), h = listHeight();
        gui.fill(x, y, x + 4, y + h, 0x55000000);
        int thumbH = Math.max(16, (int) ((double) h * h / contentHeight()));
        int thumbY = y + (int) ((h - thumbH) * (scroll / maxScroll()));
        gui.fill(x, thumbY, x + 4, thumbY + thumbH, BlockifiedScreenStyle.ACCENT);
    }

    private int cardAt(double mouseX, double mouseY) {
        if (mouseX < panelX() || mouseX >= panelX() + panelWidth()
                || mouseY < listY() || mouseY >= listY() + listHeight()) return -1;
        int relX = (int) mouseX - gridLeft(), relY = (int) (mouseY - listY() + scroll);
        if (relX < 0 || relY < 0) return -1;
        int cellW = CARD_W + GAP, cellH = CARD_H + GAP;
        int col = relX / cellW, row = relY / cellH;
        if (col >= columns() || relX % cellW >= CARD_W || relY % cellH >= CARD_H) return -1;
        int index = row * columns() + col;
        return index < itemCount() ? index : -1;
    }

    @Override public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && hasScrollbar()) {
            int sx = panelX() + panelWidth() - 5;
            if (mouseX >= sx - 1 && mouseX <= sx + 5 && mouseY >= listY() && mouseY <= listY() + listHeight()) {
                draggingThumb = true; dragTo(mouseY); return true;
            }
        }
        int index = cardAt(mouseX, mouseY);
        if (button == 0 && difficultySong != null) {
            int x = difficultyX(), y = difficultyY(), w = difficultyWidth(), h = difficultyHeight();
            if (mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h) {
                List<String> values = difficulties(difficultySong);
                int difficultyIndex = (int) ((mouseY - y - 1) / DIFFICULTY_ROW_H);
                if (difficultyIndex >= 0 && difficultyIndex < values.size()) {
                    select(difficultySong, values.get(difficultyIndex));
                }
                return true;
            }
            if (index >= 0) {
                SongEntry clicked = filtered.get(index);
                if (clicked == difficultySong) difficultySong = null;
                else openDifficulty(clicked, mouseX, mouseY);
                return true;
            }
            difficultySong = null;
        } else if (button == 0 && index >= 0) {
            openDifficulty(filtered.get(index), mouseX, mouseY);
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override public boolean mouseDragged(double x, double y, int button, double dx, double dy) {
        if (draggingThumb) { dragTo(y); return true; }
        return super.mouseDragged(x, y, button, dx, dy);
    }

    @Override public boolean mouseReleased(double x, double y, int button) {
        draggingThumb = false;
        return super.mouseReleased(x, y, button);
    }

    @Override public boolean mouseScrolled(double x, double y, double sx, double sy) {
        scrollTo(scrollTarget - sy * (CARD_H / 2.0)); return true;
    }

    private void dragTo(double mouseY) {
        int h = listHeight(), thumbH = Math.max(16, (int) ((double) h * h / contentHeight()));
        double fraction = (mouseY - listY() - thumbH / 2.0) / Math.max(1, h - thumbH);
        scroll = Mth.clamp(fraction, 0, 1) * maxScroll();
        scrollTarget = scrollFrom = scroll; scrollTween = false;
    }

    private void scrollTo(double target) {
        updateScroll(); scrollFrom = scroll; scrollTarget = Mth.clamp(target, 0, maxScroll());
        scrollStarted = System.nanoTime(); scrollTween = Math.abs(scrollTarget - scrollFrom) > .01;
    }

    private void updateScroll() {
        if (!scrollTween) return;
        double progress = (System.nanoTime() - scrollStarted) / (double) SCROLL_NANOS;
        if (progress >= 1) { scroll = scrollTarget; scrollTween = false; }
        else scroll = scrollFrom + (scrollTarget - scrollFrom) * Easing.apply("expoOut", progress);
    }

    private void openDifficulty(SongEntry song, double mouseX, double mouseY) {
        difficultySong = song;
        difficultyAnchorX = (int) Math.round(mouseX);
        difficultyAnchorY = (int) Math.round(mouseY);
    }

    private List<String> difficulties(SongEntry song) {
        if (song == null || song.difficulties == null || song.difficulties.isEmpty()) return List.of("normal");
        List<String> result = new ArrayList<>();
        for (String difficulty : song.difficulties) {
            if (difficulty != null && !difficulty.isBlank()) result.add(difficulty);
        }
        return result.isEmpty() ? List.of("normal") : result;
    }

    private void select(SongEntry song) { select(song, "normal"); }
    private void select(SongEntry song, String difficulty) {
        parent.selectPreviewSong(song, difficulty);
        minecraft.setScreen(parent);
    }
    private String label(SongEntry song) {
        String pack = song.modRoot == null || song.modRoot.getFileName() == null ? "Installed mod" : song.modRoot.getFileName().toString();
        return pack + " / " + songName(song);
    }
    private String songName(SongEntry song) { return song.displayName == null || song.displayName.isBlank() ? song.id : song.displayName; }
    private String trim(String text, int maxWidth) {
        if (text == null) return "";
        if (font.width(text) <= maxWidth) return text;
        String value = text;
        while (value.length() > 1 && font.width(value + "…") > maxWidth) value = value.substring(0, value.length() - 1);
        return value + "…";
    }
    @Override public void onClose() {
        minecraft.setScreen(parent);
    }
    @Override public void renderBackground(GuiGraphics gui, int mouseX, int mouseY, float partialTick) {}
    @Override public boolean isPauseScreen() { return false; }
}
