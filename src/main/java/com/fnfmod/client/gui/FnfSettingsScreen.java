package com.fnfmod.client.gui;

import com.fnfmod.client.ClientOptions;
import com.fnfmod.client.anim.CharacterAnimations;
import com.fnfmod.client.audio.HitsoundPlayer;
import com.fnfmod.client.render.NoteStyle;
import com.fnfmod.song.SongLibrary;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.controls.KeyBindsScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import java.util.List;

/**
 * Options menu laid out like Psych Engine's: a category list
 * (Controls / Adjust Delay and Combo / Visuals and UI / Gameplay),
 * each opening its own page.
 */
public class FnfSettingsScreen extends Screen {

    private final Screen parent;
    /** null = category list, otherwise the open category */
    private String category;

    private static final double[] SCROLL_SPEEDS = {0.5, 0.75, 1.0, 1.25, 1.5, 1.75, 2.0};
    private static final int FOLDER_ROW_H = 26;
    private int folderScroll;
    private boolean draggingFolderThumb;
    private String selectedFolder;
    // Directory edits rescan the whole library, which is heavy; defer that until
    // the user leaves the Directories page instead of running it per change.
    private boolean foldersDirty;

    public FnfSettingsScreen(Screen parent) {
        super(Component.literal("Options"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        clearWidgets();
        if (category == null) {
            initCategories();
        } else {
            switch (category) {
                case "delay" -> initDelay();
                case "visuals" -> initVisuals();
                case "gameplay" -> initGameplay();
                case "folders" -> initFolders();
                case "colors" -> initColors();
            }
            addRenderableWidget(Button.builder(Component.literal("Back"), b -> switchTo(null))
                    .bounds(width / 2 - 60, height - 32, 120, 20).build());
        }
    }

    private void switchTo(String newCategory) {
        // Apply deferred directory changes when leaving the Directories page
        // (Back, Done, or Esc all funnel through here).
        if ("folders".equals(category) && !"folders".equals(newCategory) && foldersDirty) {
            foldersDirty = false;
            SongLibrary.rescan();
        }
        category = newCategory;
        init();
    }

    private int rowY(int index) {
        return 50 + index * 26;
    }

    // ------------------------------------------------------------------ pages

    private void initCategories() {
        int w = 160;
        int x = width / 2 - w / 2;
        addRenderableWidget(Button.builder(Component.literal("Note Colors"),
                        b -> switchTo("colors"))
                .bounds(x, rowY(0), w, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Controls"),
                        b -> minecraft.setScreen(new KeyBindsScreen(this, minecraft.options)))
                .bounds(x, rowY(1), w, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Adjust Delay and Combo"),
                        b -> switchTo("delay"))
                .bounds(x, rowY(2), w, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Visuals and UI"),
                        b -> switchTo("visuals"))
                .bounds(x, rowY(3), w, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Gameplay"),
                        b -> switchTo("gameplay"))
                .bounds(x, rowY(4), w, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Directories"),
                        b -> switchTo("folders"))
                .bounds(x, rowY(5), w, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose())
                .bounds(width / 2 - 60, height - 32, 120, 20).build());
    }

    // ------------------------------------------------------------------ note colors

    private int selLane;
    private boolean selOutline;
    private float hue, sat, bri;
    private EditBox hexBox;
    private boolean updatingHex;
    private boolean colorDirty;
    /** 0 = none, 1 = saturation/brightness square, 2 = hue bar. */
    private int colorDragTarget;

    private int sbX() { return width / 2 - 85; }
    private int sbY() { return rowY(3); }
    private int hueY() { return sbY() + 78; }

    private void initColors() {
        colorDragTarget = 0;
        int w = 170;
        int x = width / 2 - 85;

        addRenderableWidget(Button.builder(coloredNotesLabel(), b -> {
            ClientOptions.get().noteColorsEnabled = !ClientOptions.get().noteColorsEnabled;
            ClientOptions.save();
            b.setMessage(coloredNotesLabel());
        }).bounds(x, rowY(0), w, 20).build());

        String[] laneNames = {"Left", "Down", "Up", "Right"};
        addRenderableWidget(Button.builder(Component.literal("Note: " + laneNames[selLane]), b -> {
            selLane = (selLane + 1) % 4;
            b.setMessage(Component.literal("Note: " + laneNames[selLane]));
            loadSelectedColor();
        }).bounds(x, rowY(1), 82, 20).build());

        addRenderableWidget(Button.builder(Component.literal(selOutline ? "Part: Outline" : "Part: Base"), b -> {
            selOutline = !selOutline;
            b.setMessage(Component.literal(selOutline ? "Part: Outline" : "Part: Base"));
            loadSelectedColor();
        }).bounds(x + 88, rowY(1), 82, 20).build());

        hexBox = addRenderableWidget(new EditBox(font, x + 20, rowY(2) + 2, 62, 16, Component.literal("hex")));
        hexBox.setMaxLength(6);
        hexBox.setResponder(s -> {
            if (updatingHex) return;
            if (s.matches("[0-9a-fA-F]{6}")) {
                setSelectedColor(Integer.parseInt(s, 16));
                float[] hsb = java.awt.Color.RGBtoHSB(
                        (selectedColor() >> 16) & 0xFF, (selectedColor() >> 8) & 0xFF, selectedColor() & 0xFF, null);
                hue = hsb[0]; sat = hsb[1]; bri = hsb[2];
                applyColorNow();
            }
        });

        addRenderableWidget(Button.builder(Component.literal("Reset Lane"), b -> {
            ClientOptions.get().noteColorBase[selLane] = ClientOptions.defaultBase()[selLane];
            ClientOptions.get().noteColorOutline[selLane] = ClientOptions.defaultOutline()[selLane];
            ClientOptions.save();
            NoteStyle.rebuildLaneColors(selLane);
            loadSelectedColor();
        }).bounds(x + 96, rowY(2), 74, 20).build());

        loadSelectedColor();
    }

    private Component coloredNotesLabel() {
        return Component.literal("Colored Notes: " + (ClientOptions.get().noteColorsEnabled ? "ON" : "OFF"));
    }

    private int selectedColor() {
        var o = ClientOptions.get();
        return (selOutline ? o.noteColorOutline : o.noteColorBase)[selLane] & 0xFFFFFF;
    }

    private void setSelectedColor(int rgb) {
        var o = ClientOptions.get();
        (selOutline ? o.noteColorOutline : o.noteColorBase)[selLane] = rgb & 0xFFFFFF;
    }

    private void loadSelectedColor() {
        int c = selectedColor();
        float[] hsb = java.awt.Color.RGBtoHSB((c >> 16) & 0xFF, (c >> 8) & 0xFF, c & 0xFF, null);
        hue = hsb[0]; sat = hsb[1]; bri = hsb[2];
        if (hexBox != null) {
            updatingHex = true;
            hexBox.setValue(String.format("%06X", c));
            updatingHex = false;
        }
    }

    private void applyColorPreview() {
        int rgb = java.awt.Color.HSBtoRGB(hue, sat, bri) & 0xFFFFFF;
        setSelectedColor(rgb);
        if (hexBox != null) {
            updatingHex = true;
            hexBox.setValue(String.format("%06X", rgb));
            updatingHex = false;
        }
        colorDirty = true;
    }

    private void applyColorNow() {
        ClientOptions.save();
        NoteStyle.rebuildLaneColors(selLane);
        colorDirty = false;
    }

    private boolean beginColorPick(double mx, double my) {
        int sx = sbX(), sy = sbY();
        if (mx >= sx && mx < sx + 72 && my >= sy && my < sy + 72) {
            colorDragTarget = 1;
            return updateColorPick(mx, my);
        }
        if (mx >= sx && mx < sx + 170 && my >= hueY() && my < hueY() + 10) {
            colorDragTarget = 2;
            return updateColorPick(mx, my);
        }
        // clicking a preview note selects that lane
        for (int i = 0; i < 4; i++) {
            int px = width / 2 + 8 + (i % 2) * 40;
            int py = sbY() + 4 + (i / 2) * 40;
            if (mx >= px && mx < px + 34 && my >= py && my < py + 34) {
                selLane = i;
                loadSelectedColor();
                switchTo("colors");
                return true;
            }
        }
        return false;
    }

    /** Keeps an active picker drag captured and clamps it to the selected box. */
    private boolean updateColorPick(double mx, double my) {
        int sx = sbX(), sy = sbY();
        if (colorDragTarget == 1) {
            sat = (float) Mth.clamp((mx - sx) / 71.0, 0.0, 1.0);
            bri = 1f - (float) Mth.clamp((my - sy) / 71.0, 0.0, 1.0);
        } else if (colorDragTarget == 2) {
            hue = (float) Mth.clamp((mx - sx) / 169.0, 0.0, 1.0);
        } else {
            return false;
        }
        applyColorPreview();
        return true;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if ("folders".equals(category) && button == 0 && clickFolderScrollbar(mouseX, mouseY)) return true;
        if (super.mouseClicked(mouseX, mouseY, button)) return true;
        return "colors".equals(category) && button == 0 && beginColorPick(mouseX, mouseY);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if ("folders".equals(category) && draggingFolderThumb) {
            scrollFoldersTo(mouseY);
            return true;
        }
        if ("colors".equals(category) && button == 0 && colorDragTarget != 0) {
            return updateColorPick(mouseX, mouseY);
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        draggingFolderThumb = false;
        if (button == 0) colorDragTarget = 0;
        // rebuilding the recolored sheets is heavy, so do it once the drag ends
        if (colorDirty) applyColorNow();
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if ("folders".equals(category)) {
            if (scrollY == 0) return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
            int old = folderScroll;
            int step = Math.max(1, (int) Math.ceil(Math.abs(scrollY)));
            folderScroll = Mth.clamp(folderScroll - (scrollY > 0 ? step : -step),
                    0, folderMaxScroll(SongLibrary.getExternalFolders().size()));
            if (folderScroll != old) init();
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    private void initFolders() {
        addRenderableWidget(Button.builder(Component.literal("Add Folder..."), b -> pickFolder())
                .bounds(width / 2 - 85, rowY(0), 170, 20).build());

        long cacheBytes = SongLibrary.cacheSizeBytes();
        addRenderableWidget(Button.builder(
                Component.literal(String.format("Clear Download Cache (%.1f MB)", cacheBytes / 1048576.0)), b -> {
                    SongLibrary.clearCache();
                    switchTo("folders");
                }).bounds(width / 2 - 85, height - 56, 170, 20).build());
        List<String> folders = SongLibrary.getExternalFolders();
        if (selectedFolder == null || !folders.contains(selectedFolder)) {
            selectedFolder = folders.isEmpty() ? null : folders.get(0);
        }
        folderScroll = Mth.clamp(folderScroll, 0, folderMaxScroll(folders.size()));
        int visible = folderVisibleRows();
        int listX = folderListX();
        int listW = folderListWidth();
        for (int row = 0; row < visible && folderScroll + row < folders.size(); row++) {
            int folderIndex = folderScroll + row;
            String folder = folders.get(folderIndex);
            final String f = folder;
            String pathLabel = (folder.equals(selectedFolder) ? "> " : "")
                    + shortenPath(folder, listW - 76);
            addRenderableWidget(Button.builder(Component.literal(pathLabel), b -> {
                selectedFolder = f;
                switchTo("folders");
            }).bounds(listX, folderListTop() + row * FOLDER_ROW_H, listW - 68, 20).build());
            Button up = addRenderableWidget(Button.builder(Component.literal("↑"), b -> moveFolder(f, -1))
                    .bounds(listX + listW - 64, folderListTop() + row * FOLDER_ROW_H, 20, 20).build());
            up.active = folderIndex > 0;
            Button down = addRenderableWidget(Button.builder(Component.literal("↓"), b -> moveFolder(f, 1))
                    .bounds(listX + listW - 43, folderListTop() + row * FOLDER_ROW_H, 20, 20).build());
            down.active = folderIndex < folders.size() - 1;
            addRenderableWidget(Button.builder(Component.literal("X"), b -> {
                var list = new java.util.ArrayList<>(SongLibrary.getExternalFolders());
                int removed = list.indexOf(f);
                list.remove(f);
                if (f.equals(selectedFolder)) {
                    selectedFolder = list.isEmpty() ? null
                            : list.get(Math.min(Math.max(0, removed), list.size() - 1));
                }
                SongLibrary.setExternalFolders(list);
                foldersDirty = true;
                switchTo("folders");
            }).bounds(listX + listW - 22, folderListTop() + row * FOLDER_ROW_H, 20, 20).build());
        }

        if (selectedFolder != null) {
            var selected = SongLibrary.getExternalFolderContent(selectedFolder);
            SongLibrary.ExternalContent[] types = SongLibrary.ExternalContent.values();
            int columns = folderChecklistColumns();
            int gap = 3;
            int buttonW = (listW - gap * (columns - 1)) / columns;
            int top = folderListBottom() + 15;
            for (int i = 0; i < types.length; i++) {
                SongLibrary.ExternalContent type = types[i];
                int x = listX + (i % columns) * (buttonW + gap);
                int y = top + (i / columns) * 22;
                String name = switch (type) {
                    case AUDIO -> "Audio";
                    case CHARACTERS -> "Chars";
                    default -> type.label;
                };
                boolean enabled = selected.contains(type);
                addRenderableWidget(Button.builder(Component.literal((enabled ? "[x] " : "[ ] ") + name), b -> {
                    SongLibrary.setExternalFolderContent(selectedFolder, type, !enabled);
                    foldersDirty = true;
                    switchTo("folders");
                }).bounds(x, y, buttonW, 20).build());
            }
        }
    }

    private void moveFolder(String folder, int direction) {
        var folders = new java.util.ArrayList<>(SongLibrary.getExternalFolders());
        int index = folders.indexOf(folder);
        int next = index + direction;
        if (index < 0 || next < 0 || next >= folders.size()) return;
        java.util.Collections.swap(folders, index, next);
        SongLibrary.setExternalFolders(folders);
        foldersDirty = true;

        int visible = folderVisibleRows();
        if (next < folderScroll) folderScroll = next;
        else if (next >= folderScroll + visible) folderScroll = next - visible + 1;
        switchTo("folders");
    }

    private int folderListTop() { return rowY(1); }
    private int folderChecklistColumns() { return folderListWidth() >= 240 ? 4 : 2; }
    private int folderChecklistRows() {
        return (SongLibrary.ExternalContent.values().length + folderChecklistColumns() - 1)
                / folderChecklistColumns();
    }
    private int folderChecklistHeight() { return 15 + folderChecklistRows() * 22; }
    private int folderListBottom() {
        return Math.max(folderListTop() + FOLDER_ROW_H, height - 76 - folderChecklistHeight());
    }
    private int folderListWidth() { return Math.min(360, Math.max(170, width - 40)); }
    private int folderListX() { return width / 2 - folderListWidth() / 2; }
    private int folderVisibleRows() {
        return Math.max(1, (folderListBottom() - folderListTop()) / FOLDER_ROW_H);
    }
    private int folderMaxScroll(int total) { return Math.max(0, total - folderVisibleRows()); }
    private int folderScrollbarX() { return folderListX() + folderListWidth() + 4; }

    private int folderThumbHeight(int total) {
        int trackH = folderListBottom() - folderListTop();
        return Math.max(16, trackH * folderVisibleRows() / Math.max(1, total));
    }

    private void scrollFoldersTo(double mouseY) {
        int total = SongLibrary.getExternalFolders().size();
        int max = folderMaxScroll(total);
        if (max <= 0) return;
        int top = folderListTop();
        int trackH = folderListBottom() - top;
        int thumbH = folderThumbHeight(total);
        double fraction = (mouseY - top - thumbH / 2.0) / Math.max(1, trackH - thumbH);
        int next = Mth.clamp((int) Math.round(fraction * max), 0, max);
        if (next != folderScroll) {
            folderScroll = next;
            init();
        }
    }

    private boolean clickFolderScrollbar(double mouseX, double mouseY) {
        int total = SongLibrary.getExternalFolders().size();
        if (folderMaxScroll(total) <= 0) return false;
        int x = folderScrollbarX();
        if (mouseX < x - 2 || mouseX >= x + 8
                || mouseY < folderListTop() || mouseY >= folderListBottom()) return false;
        draggingFolderThumb = true;
        scrollFoldersTo(mouseY);
        return true;
    }

    private void pickFolder() {
        // Native dialog blocks, so it runs off-thread. Uses the modern Explorer-style
        // file selector (choose any file inside the folder) rather than the legacy tree.
        new Thread(() -> {
            var folder = com.fnfmod.client.gameplay.NativeFilePicker.selectFolder(
                    "Select a Psych Engine mod folder");
            if (folder.isEmpty()) return;
            String picked = folder.get().toString();
            minecraft.execute(() -> {
                var list = new java.util.ArrayList<>(SongLibrary.getExternalFolders());
                if (!list.contains(picked)) list.add(picked);
                selectedFolder = picked;
                SongLibrary.setExternalFolders(list);
                foldersDirty = true;
                folderScroll = folderMaxScroll(list.size());
                if (minecraft.screen == this && "folders".equals(category)) switchTo("folders");
            });
        }, "fnf-folder-picker").start();
    }

    private void initDelay() {
        int y = rowY(1);
        int cx = width / 2;
        addRenderableWidget(Button.builder(Component.literal("-10"), b -> nudgeOffset(-10))
                .bounds(cx - 90, y, 40, 20).build());
        addRenderableWidget(Button.builder(Component.literal("-1"), b -> nudgeOffset(-1))
                .bounds(cx - 46, y, 40, 20).build());
        addRenderableWidget(Button.builder(Component.literal("+1"), b -> nudgeOffset(1))
                .bounds(cx + 6, y, 40, 20).build());
        addRenderableWidget(Button.builder(Component.literal("+10"), b -> nudgeOffset(10))
                .bounds(cx + 50, y, 40, 20).build());
    }

    private void nudgeOffset(double delta) {
        ClientOptions.get().offsetMs += delta;
        ClientOptions.save();
    }

    private void initVisuals() {
        int w = 170;
        int x = width / 2 - w / 2;
        addRenderableWidget(Button.builder(noteSkinLabel(), b ->
                cycle(NoteStyle.listSkins(), ClientOptions.get().noteSkin, false, next -> {
                    ClientOptions.get().noteSkin = next;
                    ClientOptions.save();
                    NoteStyle.reload();
                    b.setMessage(noteSkinLabel());
                })).bounds(x, rowY(0), w, 20).build());

        Button splashBtn = addRenderableWidget(Button.builder(splashLabel(), b ->
                cycle(NoteStyle.listSplashes(), ClientOptions.get().splashSkin, true, next -> {
                    ClientOptions.get().splashSkin = next;
                    ClientOptions.save();
                    NoteStyle.reload();
                    b.setMessage(splashLabel());
                })).bounds(x, rowY(1), w, 20).build());
        splashBtn.active = !NoteStyle.skinHasOwnSplash();

        addRenderableWidget(Button.builder(animsLabel(), b ->
                cycle(CharacterAnimations.listSets(), ClientOptions.get().animationSet, false, next -> {
                    ClientOptions.get().animationSet = next;
                    ClientOptions.save();
                    b.setMessage(animsLabel());
                })).bounds(x, rowY(2), w, 20).build());

        addRenderableWidget(Button.builder(hudStyleLabel(), b ->
                cycle(List.of("default", "abbreviated", "numbers", "vanilla", "fnf"),
                        ClientOptions.get().hudStyle, false, next -> {
                            ClientOptions.get().hudStyle = next;
                            ClientOptions.save();
                            b.setMessage(hudStyleLabel());
                        })).bounds(x, rowY(3), w, 20).build());

        // icon selectors open a searchable list
        addRenderableWidget(Button.builder(iconLabel(true),
                b -> minecraft.setScreen(new IconPickerScreen(this, true)))
                .bounds(x, rowY(4), w, 20).build());
        addRenderableWidget(Button.builder(iconLabel(false),
                b -> minecraft.setScreen(new IconPickerScreen(this, false)))
                .bounds(x, rowY(5), w, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Rating Position..."),
                b -> minecraft.setScreen(new RatingPositionScreen(this)))
                .bounds(x, rowY(6), w, 20).build());
    }

    private Component iconLabel(boolean player) {
        String cur = player ? ClientOptions.get().playerIcon : ClientOptions.get().botIcon;
        String shown = ClientOptions.SONG_ICON.equals(cur) ? "Default (song)"
                : cur == null || cur.isEmpty() ? "None" : cur;
        return Component.literal((player ? "Player Icon: " : "Bot Icon: ") + shown);
    }

    private Component hudStyleLabel() {
        String s = ClientOptions.get().hudStyle;
        String name = switch (s) {
            case "abbreviated" -> "Abbreviated";
            case "numbers" -> "Numbers";
            case "vanilla" -> "Vanilla";
            case "fnf" -> "FNF";
            default -> "Default";
        };
        return Component.literal("HUD: " + name);
    }

    private Component splashLabel() {
        if (NoteStyle.skinHasOwnSplash()) {
            return Component.literal("Splashes: (from skin)");
        }
        String cur = ClientOptions.get().splashSkin;
        return Component.literal("Splashes: " + (cur == null || cur.isEmpty() ? "OFF" : cur));
    }

    private void initGameplay() {
        int w = 170;
        int x = width / 2 - w / 2;
        addRenderableWidget(Button.builder(toggleLabel("Downscroll", ClientOptions.get().downscroll), b -> {
            ClientOptions.get().downscroll = !ClientOptions.get().downscroll;
            ClientOptions.save();
            b.setMessage(toggleLabel("Downscroll", ClientOptions.get().downscroll));
        }).bounds(x, rowY(0), w, 20).build());

        addRenderableWidget(Button.builder(toggleLabel("Middlescroll", ClientOptions.get().middlescroll), b -> {
            ClientOptions.get().middlescroll = !ClientOptions.get().middlescroll;
            ClientOptions.save();
            b.setMessage(toggleLabel("Middlescroll", ClientOptions.get().middlescroll));
        }).bounds(x, rowY(1), w, 20).build());

        addRenderableWidget(Button.builder(toggleLabel("Ghost Tapping", ClientOptions.get().ghostTapping), b -> {
            ClientOptions.get().ghostTapping = !ClientOptions.get().ghostTapping;
            ClientOptions.save();
            b.setMessage(toggleLabel("Ghost Tapping", ClientOptions.get().ghostTapping));
        }).bounds(x, rowY(2), w, 20).build());

        // scroll speed slider (0.35 - 6) + constant/multiplicative mode toggle
        addRenderableWidget(new net.minecraft.client.gui.components.AbstractSliderButton(
                x, rowY(3), w - 52, 20, scrollSpeedMsg(),
                (ClientOptions.get().scrollSpeedMult - 0.35) / (6.0 - 0.35)) {
            @Override protected void updateMessage() { setMessage(scrollSpeedMsg()); }
            @Override protected void applyValue() {
                ClientOptions.get().scrollSpeedMult = 0.35 + value * (6.0 - 0.35);
                ClientOptions.save();
            }
        });
        addRenderableWidget(Button.builder(scrollModeLabel(), b -> {
            ClientOptions.get().constantScrollSpeed = !ClientOptions.get().constantScrollSpeed;
            ClientOptions.save();
            b.setMessage(scrollModeLabel());
        }).bounds(x + w - 48, rowY(3), 48, 20).build());

        addRenderableWidget(Button.builder(hitsoundLabel(), b ->
                cycle(HitsoundPlayer.list(), ClientOptions.get().hitsound, true, next -> {
                    ClientOptions.get().hitsound = next;
                    ClientOptions.save();
                    b.setMessage(hitsoundLabel());
                    HitsoundPlayer.play();
                })).bounds(x, rowY(4), w, 20).build());

        // hitsound volume slider (0% - 100%)
        addRenderableWidget(new net.minecraft.client.gui.components.AbstractSliderButton(
                x, rowY(5), w, 20, hitsoundVolMsg(), ClientOptions.get().hitsoundVolume) {
            @Override protected void updateMessage() { setMessage(hitsoundVolMsg()); }
            @Override protected void applyValue() {
                ClientOptions.get().hitsoundVolume = value;
                ClientOptions.save();
                HitsoundPlayer.play();
            }
        });

        addRenderableWidget(Button.builder(toggleLabel("Botplay", ClientOptions.get().botplay), b -> {
            ClientOptions.get().botplay = !ClientOptions.get().botplay;
            ClientOptions.save();
            b.setMessage(toggleLabel("Botplay", ClientOptions.get().botplay));
        }).bounds(x, rowY(6), w, 20).build());

        addRenderableWidget(Button.builder(songWarningsLabel(), b ->
                cycle(List.of("on", "off", "blockified", "song"),
                        ClientOptions.get().songWarnings, false, next -> {
                            ClientOptions.get().songWarnings = next;
                            ClientOptions.save();
                            b.setMessage(songWarningsLabel());
                        })).bounds(x, rowY(7), w, 20).build());

        addRenderableWidget(Button.builder(preciseInputLabel(), b -> {
            ClientOptions.get().preciseInput = !ClientOptions.get().preciseInput;
            ClientOptions.save();
            b.setMessage(preciseInputLabel());
        }).bounds(x, rowY(8), w, 20).build());
    }

    private Component preciseInputLabel() {
        String state = ClientOptions.get().preciseInput ? "On" : "Off";
        // The high-rate backend is Windows-only; elsewhere the toggle still routes
        // input through the same path but stays frame-bound, so say so honestly.
        String suffix = com.fnfmod.client.input.WindowsRawKeyBackend.isSupported()
                ? "" : " (Windows only)";
        return Component.literal("Precise Input: " + state + suffix);
    }

    private Component songWarningsLabel() {
        String value = switch (ClientOptions.get().songWarnings) {
            case "off" -> "Off";
            case "blockified" -> "Blockified only";
            case "song" -> "Song only";
            default -> "On";
        };
        return Component.literal("Song Warnings: " + value);
    }

    private Component scrollSpeedMsg() {
        return Component.literal(String.format("Scroll Speed: %.2f", ClientOptions.get().scrollSpeedMult));
    }

    private Component scrollModeLabel() {
        return Component.literal(ClientOptions.get().constantScrollSpeed ? "Const" : "Mult");
    }

    private Component hitsoundVolMsg() {
        return Component.literal(String.format("Hitsound Volume: %d%%",
                Math.round(ClientOptions.get().hitsoundVolume * 100)));
    }

    /** Cycles a value forward, or backward when Shift is held; "" = an OFF/none slot at the ends. */
    private void cycle(List<String> options, String current, boolean hasOff, java.util.function.Consumer<String> setter) {
        List<String> ring = new java.util.ArrayList<>();
        if (hasOff) ring.add("");
        ring.addAll(options);
        int idx = Math.max(0, ring.indexOf(current == null ? "" : current));
        int dir = hasShiftDown() ? -1 : 1;
        int next = (idx + dir + ring.size()) % ring.size();
        setter.accept(ring.get(next));
    }

    private void renderColorPicker(GuiGraphics gui) {
        int sx = sbX(), sy = sbY();

        gui.drawString(font, "Hex:", sx - 4, rowY(2) + 6, 0xFFFFFF);

        // saturation/brightness square for the current hue
        for (int col = 0; col < 72; col++) {
            int c = java.awt.Color.HSBtoRGB(hue, col / 71f, 1f);
            gui.fill(sx + col, sy, sx + col + 1, sy + 72, 0xFF000000 | (c & 0xFFFFFF));
        }
        gui.fillGradient(sx, sy, sx + 72, sy + 72, 0x00000000, 0xFF000000);
        int cx = sx + (int) (sat * 71);
        int cy = sy + (int) ((1 - bri) * 71);
        gui.fill(cx - 2, cy - 2, cx + 3, cy + 3, 0xFFFFFFFF);
        gui.fill(cx - 1, cy - 1, cx + 2, cy + 2, 0xFF000000 | selectedColor());

        // hue bar
        for (int col = 0; col < 170; col++) {
            int c = java.awt.Color.HSBtoRGB(col / 169f, 1f, 1f);
            gui.fill(sx + col, hueY(), sx + col + 1, hueY() + 10, 0xFF000000 | (c & 0xFFFFFF));
        }
        int hx = sx + (int) (hue * 169);
        gui.fill(hx - 1, hueY() - 1, hx + 2, hueY() + 11, 0xFFFFFFFF);

        // Show a contextual swatch only while a picker is captured. The hue
        // swatch is the pure hue; the square swatch is the combined H/S/B color.
        if (colorDragTarget == 1) {
            drawColorSwatch(gui, cx + 7, cy - 21, selectedColor());
        } else if (colorDragTarget == 2) {
            int pureHue = java.awt.Color.HSBtoRGB(hue, 1f, 1f) & 0xFFFFFF;
            drawColorSwatch(gui, hx + 6, hueY() - 3, pureHue);
        }

        // live note previews (click to select a lane)
        for (int i = 0; i < 4; i++) {
            int px = width / 2 + 8 + (i % 2) * 40;
            int py = sy + 4 + (i / 2) * 40;
            if (i == selLane) {
                gui.fill(px - 2, py - 2, px + 36, py + 36, 0x66FFFFFF);
            }
            NoteStyle.drawNote(gui, i, px + 17, py + 17, 30);
        }

        if (!NoteStyle.skinIsColorable()) {
            gui.drawCenteredString(font, "Current skin has no RGB template - colors won't apply to it.",
                    width / 2, hueY() + 16, 0xFFFF8866);
        }
    }

    private static void drawColorSwatch(GuiGraphics gui, int x, int y, int rgb) {
        gui.fill(x - 1, y - 1, x + 17, y + 17, 0xFFFFFFFF);
        gui.fill(x, y, x + 16, y + 16, 0xFF000000 | (rgb & 0xFFFFFF));
    }

    /** Shortens a path from the front so its tail (the useful part) stays visible. */
    private String shortenPath(String path, int maxWidth) {
        if (font.width(path) <= maxWidth) return path;
        String s = path;
        while (s.length() > 4 && font.width("..." + s) > maxWidth) {
            s = s.substring(1);
        }
        return "..." + s;
    }

    private Component hitsoundLabel() {
        String cur = ClientOptions.get().hitsound;
        return Component.literal("Hitsound: " + (cur == null || cur.isEmpty()
                ? "OFF" : cur.replaceFirst("(?i)\\.ogg$", "")));
    }

    private Component hitsoundVolLabel() {
        return Component.literal(String.format("Hitsound Volume: %d%%",
                Math.round(ClientOptions.get().hitsoundVolume * 100)));
    }

    // ------------------------------------------------------------------ labels

    private Component toggleLabel(String name, boolean v) {
        return Component.literal(name + ": " + (v ? "ON" : "OFF"));
    }

    private Component noteSkinLabel() {
        String skin = ClientOptions.get().noteSkin;
        String shown = ClientOptions.NOTE_SKIN_DEFAULT.equalsIgnoreCase(skin) ? "Default (chart)"
                : ClientOptions.NOTE_SKIN_NONE.equalsIgnoreCase(skin) ? "None (procedural)" : skin;
        return Component.literal("Note Skin: " + shown);
    }

    private Component animsLabel() {
        String selected = ClientOptions.get().animationSet;
        String shown = CharacterAnimations.NONE_SET.equalsIgnoreCase(selected) ? "None"
                : CharacterAnimations.DEFAULT_SET.equalsIgnoreCase(selected) ? "Default (song)"
                : selected + ".json";
        return Component.literal("Animations: " + shown);
    }

    private Component scrollSpeedLabel() {
        return Component.literal(String.format("Scroll Speed: x%.2f", ClientOptions.get().scrollSpeedMult));
    }

    // ------------------------------------------------------------------

    @Override
    public void render(GuiGraphics gui, int mouseX, int mouseY, float partialTick) {
        super.render(gui, mouseX, mouseY, partialTick);
        String title = switch (category == null ? "" : category) {
            case "delay" -> "Adjust Delay and Combo";
            case "visuals" -> "Visuals and UI";
            case "gameplay" -> "Gameplay";
            case "folders" -> "Directories";
            case "colors" -> "Note Colors";
            default -> "Options";
        };
        gui.drawCenteredString(font, title, width / 2, 20, 0xFFFFFF);

        if ("colors".equals(category)) {
            renderColorPicker(gui);
        }

        if ("folders".equals(category)) {
            List<String> folders = SongLibrary.getExternalFolders();
            if (folders.isEmpty()) {
                gui.drawCenteredString(font, "No folders added.", width / 2, folderListTop() + 6, 0x888888);
            } else if (folderMaxScroll(folders.size()) > 0) {
                int trackX = folderScrollbarX();
                int trackTop = folderListTop();
                int trackH = folderListBottom() - trackTop;
                int thumbH = folderThumbHeight(folders.size());
                int thumbY = trackTop + (int) ((trackH - thumbH)
                        * (folderScroll / (double) folderMaxScroll(folders.size())));
                gui.fill(trackX, trackTop, trackX + 5, folderListBottom(), 0x55000000);
                gui.fill(trackX, thumbY, trackX + 5, thumbY + thumbH, 0xFFAAAAAA);
            }
            String filterHint = selectedFolder == null
                    ? "Add a directory to choose what it loads"
                    : "Load from selected directory (top = highest priority)";
            gui.drawCenteredString(font, filterHint, width / 2, folderListBottom() + 3, 0xAAAAAA);
        }

        if ("delay".equals(category)) {
            gui.drawCenteredString(font, String.format("Audio Offset: %.0f ms", ClientOptions.get().offsetMs),
                    width / 2, rowY(0) + 6, 0xFFFF66);
            gui.drawCenteredString(font, "Positive = notes judged later. Tune until hits feel centered.",
                    width / 2, hintY(0), 0xAAAAAA);
        } else if ("visuals".equals(category)) {
            gui.drawCenteredString(font, "skins/  splashes/  animations/<name>.json  icons/<pack>/<name>.png",
                    width / 2, hintY(1), 0xAAAAAA);
            gui.drawCenteredString(font, "All folders under config/fnfmod/",
                    width / 2, hintY(0), 0xAAAAAA);
        }
    }

    /** Y for a hint line sitting just above the Back button (line 0 = closest). */
    private int hintY(int lineFromBottom) {
        return (height - 32) - 12 - lineFromBottom * 10;
    }

    @Override
    public void onClose() {
        if (category != null) {
            switchTo(null);
        } else {
            minecraft.setScreen(parent);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
