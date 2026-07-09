package com.fnfmod.client.gui;

import com.fnfmod.client.ClientOptions;
import com.fnfmod.client.ClientSession;
import com.fnfmod.client.ScoreStore;
import com.fnfmod.client.render.IconLibrary;
import com.fnfmod.net.FnfPayloads;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.neoforged.neoforge.network.PacketDistributor;

/** Opened when a song is clicked: shows its best score and the play options. */
public class SongDetailScreen extends Screen {

    private final Screen parent;
    private final BlockPos pos;
    private final FnfPayloads.SongInfo song;

    private int difficultyIndex;
    private Button difficultyButton;
    private boolean showDiffList = false;

    private static final int LAYER = 0x88000000;
    private static final int ROW_H = 13;

    public SongDetailScreen(Screen parent, BlockPos pos, FnfPayloads.SongInfo song) {
        super(Component.literal(song.name()));
        this.parent = parent;
        this.pos = pos;
        this.song = song;
        int idx = song.difficulties().indexOf("normal");
        this.difficultyIndex = Math.max(0, idx);
    }

    private String difficulty() {
        if (song.difficulties().isEmpty()) return "normal";
        return song.difficulties().get(Mth.clamp(difficultyIndex, 0, song.difficulties().size() - 1));
    }

    @Override
    protected void init() {
        int cx = width / 2;
        int y = height / 2 + 6;

        // clicking opens a dropdown list of difficulties beside the button
        difficultyButton = addRenderableWidget(Button.builder(diffLabel(), b ->
                showDiffList = !showDiffList).bounds(cx - 100, y, 95, 20).build());

        addRenderableWidget(Button.builder(playAsLabel(), b -> {
            ClientOptions.get().playAs = (ClientOptions.get().playAs + (hasShiftDown() ? 2 : 1)) % 3;
            ClientOptions.save();
            b.setMessage(playAsLabel());
        }).bounds(cx + 5, y, 95, 20).build());

        addRenderableWidget(Button.builder(Component.literal("Play Solo"), b -> startSong(false))
                .bounds(cx - 100, y + 24, 95, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Play VS (2P)"), b -> startSong(true))
                .bounds(cx + 5, y + 24, 95, 20).build());

        addRenderableWidget(Button.builder(Component.literal("Back"), b -> onClose())
                .bounds(cx - 60, height - 30, 120, 20).build());
    }

    private Component diffLabel() {
        return Component.literal("Difficulty: " + difficulty());
    }

    private Component playAsLabel() {
        String[] names = {"Player", "Opponent", "Both"};
        return Component.literal("Play as: " + names[ClientOptions.get().playAs % 3]);
    }

    private void startSong(boolean duet) {
        byte playSide = duet ? 0 : (byte) (ClientOptions.get().playAs % 3);
        ClientSession.pendingPlaySide = playSide;
        PacketDistributor.sendToServer(new FnfPayloads.SelectSongC2S(pos, song.id(), difficulty(), duet, playSide));
        minecraft.setScreen(new WaitingScreen(Component.literal(duet ? "Waiting for player 2..." : "Loading...")));
    }

    @Override
    public void render(GuiGraphics gui, int mouseX, int mouseY, float partialTick) {
        super.render(gui, mouseX, mouseY, partialTick);

        int cx = width / 2;
        // icon + name header
        float iconSize = 46;
        float iconY = 54;
        if (IconLibrary.hasFile(song.opponentIconPath())) {
            IconLibrary.drawFile(gui, song.opponentIconPath(), 0, cx, iconY, iconSize, false);
        } else if (IconLibrary.has(song.opponentIcon())) {
            IconLibrary.draw(gui, song.opponentIcon(), 0, cx, iconY, iconSize);
        }
        gui.pose().pushPose();
        gui.pose().translate(cx, iconY + 34, 0);
        gui.pose().scale(1.4f, 1.4f, 1f);
        gui.drawCenteredString(font, song.name(), 0, 0, 0xFFFFFFFF);
        gui.pose().popPose();

        // best-score panel
        int panelW = 220;
        int px = cx - panelW / 2;
        int py = height / 2 - 74;
        gui.fill(px, py, px + panelW, py + 78, LAYER);
        gui.drawCenteredString(font, "Best Score - " + difficulty(), cx, py + 5, 0xFFFFDD66);

        ScoreStore.Record r = ScoreStore.get(song.id(), difficulty());
        if (r == null) {
            gui.drawCenteredString(font, "No score yet", cx, py + 34, 0xFFAAAAAA);
        } else {
            int ty = py + 18;
            line(gui, px + 8, ty, "Score", String.valueOf(r.score), panelW);
            line(gui, px + 8, ty + 10, "Max Combo", String.valueOf(r.maxCombo), panelW);
            line(gui, px + 8, ty + 20, "Accuracy", String.format("%.2f%%", r.accuracy() * 100), panelW);
            line(gui, px + 8, ty + 30, "Notes Hit", r.hitNotes() + " / " + r.totalNotes, panelW);
            String judge = String.format("Sick %d  Good %d  Bad %d  Shit %d  Miss %d",
                    r.sick, r.good, r.bad, r.shit, r.missed);
            gui.drawCenteredString(font, judge, cx, ty + 44, 0xFFCCCCCC);
        }

        if (showDiffList) renderDiffList(gui, mouseX, mouseY);
    }

    // ------------------------------------------------------------------ difficulty dropdown

    private int dropX() { return difficultyButton.getX() - dropW() - 3; }
    private int dropY() { return difficultyButton.getY(); }
    private int dropW() { return 95; }
    private int dropH() { return Math.max(1, song.difficulties().size()) * ROW_H + 2; }

    private void renderDiffList(GuiGraphics gui, int mouseX, int mouseY) {
        int x = dropX(), y = dropY(), w = dropW();
        gui.fill(x - 1, y - 1, x + w + 1, y + dropH() + 1, 0xFF000000);
        gui.fill(x, y, x + w, y + dropH(), 0xE0181820);
        var diffs = song.difficulties();
        for (int i = 0; i < diffs.size(); i++) {
            int ry = y + 1 + i * ROW_H;
            boolean hover = mouseX >= x && mouseX < x + w && mouseY >= ry && mouseY < ry + ROW_H;
            boolean sel = i == difficultyIndex;
            if (sel) gui.fill(x, ry, x + w, ry + ROW_H, 0x66FF44AA);
            else if (hover) gui.fill(x, ry, x + w, ry + ROW_H, 0x33FFFFFF);
            gui.drawString(font, diffs.get(i), x + 5, ry + 3, sel ? 0xFFFFFFFF : 0xFFCCCCCC, false);
        }
    }

    private void line(GuiGraphics gui, int x, int y, String label, String value, int panelW) {
        gui.drawString(font, label, x, y, 0xFFBBBBBB, false);
        gui.drawString(font, value, x + panelW - 16 - font.width(value), y, 0xFFFFFFFF, false);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (showDiffList) {
            int x = dropX(), y = dropY(), w = dropW();
            if (mx >= x && mx < x + w && my >= y && my < y + dropH()) {
                int i = (int) ((my - y - 1) / ROW_H);
                if (i >= 0 && i < song.difficulties().size()) {
                    difficultyIndex = i;
                    difficultyButton.setMessage(diffLabel());
                }
                showDiffList = false;
                return true;
            }
            // clicking the difficulty button (or anywhere else) just closes the list
            showDiffList = false;
            if (difficultyButton.isMouseOver(mx, my)) return true;
            // fall through so other buttons still work
        }
        return super.mouseClicked(mx, my, button);
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
