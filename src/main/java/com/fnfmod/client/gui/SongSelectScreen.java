package com.fnfmod.client.gui;

import com.fnfmod.client.ClientSession;
import com.fnfmod.client.anim.CharacterAnimations;
import com.fnfmod.client.gui.editor.ChartEditorScreen;
import com.fnfmod.client.render.IconLibrary;
import com.fnfmod.client.render.NoteStyle;
import com.fnfmod.client.math.Easing;
import com.fnfmod.net.FnfPayloads;
import com.fnfmod.song.SongLibrary;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Song selection menu — a scrollable grid of song boxes. Clicking one opens its detail screen. */
public class SongSelectScreen extends Screen {

    private final BlockPos pos;
    private final List<FnfPayloads.SongInfo> songs;
    private List<FnfPayloads.SongInfo> visible;
    private String query = "";

    // box layout (GUI units, so consistent physical size across resolutions)
    private static final int BOX_W = 84;
    private static final int BOX_H = 92;
    private static final int GAP = 6;
    private static final int LAYER = 0x88000000; // same alpha as the list background; stacks darker

    private double scrollPx = 0;
    private double scrollTargetPx;
    private double scrollFromPx;
    private long scrollTweenStartNano;
    private boolean scrollTweenActive;
    private static final long SCROLL_TWEEN_NANOS = 200_000_000L;
    private boolean draggingThumb = false;
    private EditBox searchBox;

    public SongSelectScreen(BlockPos pos, List<FnfPayloads.SongInfo> songs) {
        super(Component.literal("Funkin' Machine"));
        this.pos = pos;
        this.songs = songs;
        this.visible = songs;
    }

    private void applyFilter(String newQuery) {
        query = newQuery == null ? "" : newQuery.trim().toLowerCase(Locale.ROOT);
        if (query.isEmpty()) {
            visible = songs;
        } else {
            visible = new ArrayList<>();
            for (FnfPayloads.SongInfo s : songs) {
                if (s.name().toLowerCase(Locale.ROOT).contains(query)
                        || s.id().toLowerCase(Locale.ROOT).contains(query)) {
                    visible.add(s);
                }
            }
        }
        scrollPx = scrollTargetPx = scrollFromPx = 0;
        scrollTweenActive = false;
    }

    @Override
    protected void init() {
        int right = width - 110;

        String prevQuery = searchBox == null ? "" : searchBox.getValue();
        searchBox = addRenderableWidget(new EditBox(font, listX(), 24, listWidth(), 14, Component.literal("search")));
        searchBox.setHint(Component.literal("Search songs..."));
        searchBox.setValue(prevQuery);
        searchBox.setResponder(this::applyFilter);

        addRenderableWidget(Button.builder(Component.literal("Chart Editor"), b -> {
            ClientSession.leave();
            // Carry the machine through: it anchors the playtest stage, so without
            // it the editor cannot place performers or the camera and playtesting
            // is disabled entirely.
            minecraft.setScreen(new ChartEditorScreen(null, null, null, null, null, pos));
        }).bounds(right, 40, 100, 20).build());

        addRenderableWidget(Button.builder(Component.literal("Character Editor"), b ->
                minecraft.setScreen(new CharacterEditorScreen(this))).bounds(right, 62, 100, 20).build());

        addRenderableWidget(Button.builder(Component.literal("Settings"), b ->
                minecraft.setScreen(new FnfSettingsScreen(this))).bounds(right, 84, 100, 20).build());

        addRenderableWidget(Button.builder(Component.literal("Reload Songs"), b -> {
            SongLibrary.rescan();
            IconLibrary.rescan();
            CharacterAnimations.reload();
            NoteStyle.reload();
            PacketDistributor.sendToServer(new FnfPayloads.ReloadC2S());
        }).bounds(right, 106, 100, 20).build());

        addRenderableWidget(Button.builder(Component.literal("Close"), b -> {
            ClientSession.leave();
            onClose();
        }).bounds(right, height - 26, 100, 20).build());
    }

    // ------------------------------------------------------------------ layout

    private int listX() { return 20; }
    private int listY() { return 46; }
    private int listWidth() { return width - 150; }
    private int listHeight() { return height - 66; }

    private int columns() {
        return Math.max(1, (listWidth() + GAP) / (BOX_W + GAP));
    }

    private int rows() {
        return (visible.size() + columns() - 1) / Math.max(1, columns());
    }

    private int contentHeight() {
        return rows() * (BOX_H + GAP);
    }

    private double maxScroll() {
        return Math.max(0, contentHeight() - listHeight());
    }

    private boolean hasScrollbar() {
        return contentHeight() > listHeight();
    }

    /** Top-left x of a column, centering the grid within the list area. */
    private int gridLeft() {
        int cols = columns();
        int used = cols * BOX_W + (cols - 1) * GAP;
        return listX() + Math.max(0, (listWidth() - used) / 2);
    }

    // ------------------------------------------------------------------ render

    @Override
    public void render(GuiGraphics gui, int mouseX, int mouseY, float partialTick) {
        super.render(gui, mouseX, mouseY, partialTick);
        updateScrollTween();
        gui.drawCenteredString(font, "Funkin' Machine - Select a Song", width / 2, 15, 0xFFFFFF);

        int x = listX(), y = listY(), w = listWidth(), h = listHeight();
        gui.fill(x - 4, y - 4, x + w + 4, y + h + 4, LAYER);

        if (visible.isEmpty()) {
            if (!query.isEmpty()) {
                gui.drawCenteredString(font, "No songs match \"" + query + "\"", x + w / 2, y + 30, 0xFFFF6666);
            } else {
                gui.drawCenteredString(font, "No songs found!", x + w / 2, y + 30, 0xFFFF6666);
                gui.drawCenteredString(font, "Use config/fnfmod/songs/<name> or mods/<pack>", x + w / 2, y + 46, 0xFFAAAAAA);
                gui.drawCenteredString(font, "or add a mod folder in Settings > Directories", x + w / 2, y + 60, 0xFFAAAAAA);
            }
            return;
        }

        scrollPx = Mth.clamp(scrollPx, 0, maxScroll());
        scrollTargetPx = Mth.clamp(scrollTargetPx, 0, maxScroll());
        int cols = columns();
        int left = gridLeft();

        gui.enableScissor(x, y, x + w, y + h);
        for (int i = 0; i < visible.size(); i++) {
            int col = i % cols;
            int row = i / cols;
            int bx = left + col * (BOX_W + GAP);
            int by = (int) (y + row * (BOX_H + GAP) - scrollPx);
            if (by + BOX_H < y || by > y + h) continue; // off-screen
            drawSongBox(gui, visible.get(i), bx, by, mouseX, mouseY);
        }
        gui.disableScissor();

        drawScrollbar(gui);
    }

    private void drawSongBox(GuiGraphics gui, FnfPayloads.SongInfo s, int bx, int by, int mouseX, int mouseY) {
        boolean hovered = mouseX >= bx && mouseX < bx + BOX_W && mouseY >= by && mouseY < by + BOX_H
                && mouseY >= listY() && mouseY < listY() + listHeight();

        // box body (same alpha as background -> stacks darker)
        gui.fill(bx, by, bx + BOX_W, by + BOX_H, LAYER);
        if (hovered) gui.fill(bx, by, bx + BOX_W, by + BOX_H, 0x22FFFFFF);

        // opponent icon centered in the upper area, sized to the reference circle.
        // prefer the per-mod file (avoids cross-mod name clashes); fall back to a named icon.
        int iconSize = (int) (BOX_W * 0.68f);
        float cx = bx + BOX_W / 2f;
        float cy = by + 8 + iconSize / 2f;
        if (IconLibrary.hasFile(s.opponentIconPath())) {
            IconLibrary.drawFile(gui, s.opponentIconPath(), 0, cx, cy, iconSize, false);
        } else if (IconLibrary.has(s.opponentIcon())) {
            IconLibrary.draw(gui, s.opponentIcon(), 0, cx, cy, iconSize);
        }

        // name box at the bottom, darker again
        int nameTop = by + BOX_H - 22;
        gui.fill(bx + 3, nameTop, bx + BOX_W - 3, by + BOX_H - 4, LAYER);
        String name = trim(s.name(), BOX_W - 10);
        gui.drawCenteredString(font, name, bx + BOX_W / 2, nameTop + 6, 0xFFFFFFFF);
    }

    private String trim(String text, int maxWidth) {
        if (font.width(text) <= maxWidth) return text;
        String s = text;
        while (s.length() > 1 && font.width(s + "…") > maxWidth) s = s.substring(0, s.length() - 1);
        return s + "…";
    }

    private void drawScrollbar(GuiGraphics gui) {
        if (!hasScrollbar()) return;
        int x = listX() + listWidth() - 5;
        int y = listY();
        int h = listHeight();
        gui.fill(x, y, x + 4, y + h, 0x55000000);
        int thumbH = Math.max(16, (int) ((double) h * h / contentHeight()));
        int thumbY = y + (int) ((h - thumbH) * (scrollPx / maxScroll()));
        gui.fill(x, thumbY, x + 4, thumbY + thumbH, 0xAAFFFFFF);
    }

    // ------------------------------------------------------------------ input

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // scrollbar thumb
        if (button == 0 && hasScrollbar()) {
            int sx = listX() + listWidth() - 5;
            if (mouseX >= sx - 1 && mouseX <= sx + 5 && mouseY >= listY() && mouseY <= listY() + listHeight()) {
                draggingThumb = true;
                dragTo(mouseY);
                return true;
            }
        }
        // a song box
        int idx = boxAt(mouseX, mouseY);
        if (button == 0 && idx >= 0) {
            minecraft.setScreen(new SongDetailScreen(this, pos, visible.get(idx)));
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    private int boxAt(double mouseX, double mouseY) {
        int x = listX(), y = listY(), w = listWidth(), h = listHeight();
        if (mouseX < x || mouseX >= x + w || mouseY < y || mouseY >= y + h) return -1;
        int cols = columns();
        int left = gridLeft();
        int relY = (int) (mouseY - y + scrollPx);
        int col = (int) ((mouseX - left)) / (BOX_W + GAP);
        int colInBox = (int) (mouseX - left) - col * (BOX_W + GAP);
        int row = relY / (BOX_H + GAP);
        int rowInBox = relY - row * (BOX_H + GAP);
        if (col < 0 || col >= cols || colInBox >= BOX_W || rowInBox >= BOX_H) return -1;
        int idx = row * cols + col;
        return idx >= 0 && idx < visible.size() ? idx : -1;
    }

    private void dragTo(double mouseY) {
        int y = listY(), h = listHeight();
        int thumbH = Math.max(16, (int) ((double) h * h / contentHeight()));
        double frac = (mouseY - y - thumbH / 2.0) / (h - thumbH);
        scrollPx = Mth.clamp(frac, 0, 1) * maxScroll();
        scrollTargetPx = scrollFromPx = scrollPx;
        scrollTweenActive = false;
    }

    private void updateScrollTween() {
        if (!scrollTweenActive) return;
        double progress = (System.nanoTime() - scrollTweenStartNano) / (double) SCROLL_TWEEN_NANOS;
        if (progress >= 1) {
            scrollPx = scrollTargetPx;
            scrollTweenActive = false;
            return;
        }
        double eased = Easing.apply("expoOut", progress);
        scrollPx = scrollFromPx + (scrollTargetPx - scrollFromPx) * eased;
    }

    private void scrollTo(double target) {
        updateScrollTween();
        scrollFromPx = scrollPx;
        scrollTargetPx = Mth.clamp(target, 0, maxScroll());
        scrollTweenStartNano = System.nanoTime();
        scrollTweenActive = Math.abs(scrollTargetPx - scrollFromPx) > 0.01;
        if (!scrollTweenActive) scrollPx = scrollTargetPx;
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dx, double dy) {
        if (draggingThumb) {
            dragTo(mouseY);
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dx, dy);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        draggingThumb = false;
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        scrollTo(scrollTargetPx - scrollY * (BOX_H / 2.0));
        return true;
    }

    @Override
    public void onClose() {
        ClientSession.leave();
        super.onClose();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
