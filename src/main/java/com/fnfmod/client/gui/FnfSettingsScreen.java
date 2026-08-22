package com.fnfmod.client.gui;

import com.fnfmod.client.ClientOptions;
import com.fnfmod.client.anim.CharacterAnimations;
import com.fnfmod.client.audio.HitsoundPlayer;
import com.fnfmod.client.math.Easing;
import com.fnfmod.client.render.NoteStyle;
import com.fnfmod.client.render.IconLibrary;
import com.fnfmod.song.ModPackInfo;
import com.fnfmod.song.SongLibrary;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.controls.KeyBindsScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import java.util.List;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.nio.file.Path;

/**
 * Options menu laid out like Psych Engine's: a category list
 * (Controls / Visuals and UI / Gameplay / Mods),
 * each opening its own page.
 */
public class FnfSettingsScreen extends Screen {

    private final Screen parent;
    /** True when the Song Menu opened a section directly, bypassing the legacy category page. */
    private boolean directCategory;
    /** null = category list, otherwise the open category */
    private String category;

    private static final double[] SCROLL_SPEEDS = {0.5, 0.75, 1.0, 1.25, 1.5, 1.75, 2.0};
    private static final int FOLDER_ROW_H = 26;
    private static final long SCROLL_TWEEN_NANOS = 200_000_000L;
    private double pageScrollPx;
    private double pageScrollTargetPx;
    private double pageScrollFromPx;
    private long pageScrollTweenStart;
    private boolean pageScrollTweenActive;
    private final Map<AbstractWidget, Integer> pageWidgetBaseY = new IdentityHashMap<>();
    private double folderScrollPx;
    private double folderScrollTargetPx;
    private double folderScrollFromPx;
    private long folderScrollTweenStart;
    private boolean folderScrollTweenActive;
    private record FolderWidget(AbstractWidget widget, int baseY, String iconPath) {}
    private final List<FolderWidget> folderWidgets = new ArrayList<>();
    private final Set<AbstractWidget> suppressedFolderWidgets = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<String> expandedSources = new HashSet<>();
    private int folderTotalRows;
    private boolean draggingFolderThumb;
    private String selectedFolder;
    private Path selectedPackRoot;
    private record PermissionWidget(Button button, SongLibrary.ExternalContent content, int baseY) {}
    private final List<PermissionWidget> permissionWidgets = new ArrayList<>();
    private final Set<SongLibrary.ExternalContent> permissionDragVisited =
            java.util.EnumSet.noneOf(SongLibrary.ExternalContent.class);
    private boolean draggingPermissions;
    private boolean permissionDragEnables;
    private String permissionTargetLabel = "";
    private ModPackInfo selectedPackInfo;
    private double packScrollPx, packScrollTargetPx, packScrollFromPx;
    private long packScrollTweenStart;
    private boolean packScrollTweenActive, draggingPackThumb;
    private int packContentHeight;
    // Directory edits rescan the whole library, which is heavy; defer that until
    // the user leaves the Directories page instead of running it per change.
    private boolean foldersDirty;

    public FnfSettingsScreen(Screen parent) {
        super(Component.literal("Options"));
        this.parent = parent;
    }

    /** Opens one built-in category directly (used by the Song Menu Settings tab). */
    public FnfSettingsScreen(Screen parent, String category) {
        this(parent);
        this.directCategory = true;
        this.category = switch (category == null ? "" : category) {
            case "visuals", "gameplay", "folders", "colors" -> category;
            default -> null;
        };
    }

    /** Prevents custom machine music from competing with a selected Note Settings chart. */
    void stopParentMachineAudio() {
        if (parent instanceof com.fnfmod.client.gui.machine.MachineMenuScreen menu) {
            menu.stopSharedAudio();
        }
    }

    @Override
    protected void init() {
        clearWidgets();
        pageWidgetBaseY.clear();
        folderWidgets.clear();
        suppressedFolderWidgets.clear();
        permissionWidgets.clear();
        selectedPackInfo = null;
        packContentHeight = 0;
        if (category == null) {
            initCategories();
            capturePageWidgets();
            addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose())
                    .bounds(width / 2 - 80, height - 32, 160, 20).build());
        } else {
            switch (category) {
                case "visuals" -> initVisuals();
                case "gameplay" -> initGameplay();
                case "folders" -> initFolders();
                case "colors" -> initColors();
            }
            if (!"folders".equals(category)) capturePageWidgets();
            addRenderableWidget(Button.builder(Component.literal("Back"), b -> leaveCategory())
                    .bounds(width / 2 - 80, height - 32, 160, 20).build());
        }
        clampScrolls();
        applyPageWidgetScroll();
        applyFolderWidgetScroll();
    }

    private void switchTo(String newCategory) {
        // Apply deferred directory changes when leaving the Directories page
        // (Back, Done, or Esc all funnel through here).
        if ("folders".equals(category) && !"folders".equals(newCategory) && foldersDirty) {
            foldersDirty = false;
            SongLibrary.rescan();
        }
        if ("colors".equals(category) && !"colors".equals(newCategory)) restoreColorPreview();
        category = newCategory;
        pageScrollPx = pageScrollTargetPx = pageScrollFromPx = 0;
        pageScrollTweenActive = false;
        init();
    }

    private void leaveCategory() {
        if (!directCategory) {
            switchTo(null);
            return;
        }
        // Direct sections belong to the Song Menu's Settings tab. Apply the
        // same deferred cleanup as switchTo(), then return there immediately.
        if ("folders".equals(category) && foldersDirty) {
            foldersDirty = false;
            SongLibrary.rescan();
        }
        if ("colors".equals(category)) restoreColorPreview();
        minecraft.setScreen(parent);
    }

    private int rowY(int index) {
        return 50 + index * 26 - (int) Math.round(pageScrollPx);
    }

    private int pageViewportTop() { return 44; }
    private int pageViewportBottom() {
        int reserved = "visuals".equals(category) ? 64 : 40;
        return Math.max(pageViewportTop() + 20, height - reserved);
    }

    private int pageContentBottom() {
        return switch (category == null ? "categories" : category) {
            case "categories" -> 50 + 5 * 26 + 20;
            case "visuals" -> 50 + 6 * 26 + 20;
            case "gameplay" -> 50 + 9 * 26 + 20;
            case "colors" -> 50 + 4 * 26 + 78 + 28;
            default -> pageViewportBottom();
        };
    }

    private double pageMaxScroll() {
        return Math.max(0, pageContentBottom() - pageViewportBottom());
    }

    private void capturePageWidgets() {
        int offset = (int) Math.round(pageScrollPx);
        for (var child : children()) {
            if (child instanceof AbstractWidget widget) {
                pageWidgetBaseY.put(widget, widget.getY() + offset);
            }
        }
    }

    private void applyPageWidgetScroll() {
        int offset = (int) Math.round(pageScrollPx);
        int top = pageViewportTop(), bottom = pageViewportBottom();
        for (var entry : pageWidgetBaseY.entrySet()) {
            AbstractWidget widget = entry.getKey();
            int y = entry.getValue() - offset;
            widget.setY(y);
            widget.visible = y + widget.getHeight() > top && y < bottom;
        }
    }

    private void clampScrolls() {
        pageScrollPx = Mth.clamp(pageScrollPx, 0, pageMaxScroll());
        pageScrollTargetPx = Mth.clamp(pageScrollTargetPx, 0, pageMaxScroll());
        double folderMax = folderMaxScrollPx(folderTotalRows);
        folderScrollPx = Mth.clamp(folderScrollPx, 0, folderMax);
        folderScrollTargetPx = Mth.clamp(folderScrollTargetPx, 0, folderMax);
    }

    private void scrollPageTo(double target) {
        updatePageScrollTween();
        pageScrollFromPx = pageScrollPx;
        pageScrollTargetPx = Mth.clamp(target, 0, pageMaxScroll());
        pageScrollTweenStart = System.nanoTime();
        pageScrollTweenActive = Math.abs(pageScrollTargetPx - pageScrollFromPx) > 0.01;
        if (!pageScrollTweenActive) pageScrollPx = pageScrollTargetPx;
    }

    private void updatePageScrollTween() {
        if (!pageScrollTweenActive) return;
        double progress = (System.nanoTime() - pageScrollTweenStart) / (double) SCROLL_TWEEN_NANOS;
        if (progress >= 1) {
            pageScrollPx = pageScrollTargetPx;
            pageScrollTweenActive = false;
        } else {
            double eased = Easing.apply("expoOut", progress);
            pageScrollPx = pageScrollFromPx + (pageScrollTargetPx - pageScrollFromPx) * eased;
        }
        applyPageWidgetScroll();
    }

    // ------------------------------------------------------------------ pages

    private void initCategories() {
        int w = Math.min(260, width - 56);
        int x = width / 2 - w / 2;
        addRenderableWidget(Button.builder(Component.literal("Note Settings"),
                        b -> minecraft.setScreen(new NoteSettingsScreen(this)))
                .bounds(x, rowY(0), w, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Controls"),
                        b -> minecraft.setScreen(new KeyBindsScreen(this, minecraft.options)))
                .bounds(x, rowY(1), w, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Visuals and UI"),
                        b -> switchTo("visuals"))
                .bounds(x, rowY(2), w, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Gameplay"),
                        b -> switchTo("gameplay"))
                .bounds(x, rowY(3), w, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Mods"),
                        b -> switchTo("folders"))
                .bounds(x, rowY(4), w, 20).build());
    }

    // ------------------------------------------------------------------ note colors

    private int selLane;
    private boolean selOutline;
    private float hue, sat, bri;
    private EditBox hexBox;
    private boolean updatingHex;
    private boolean colorDirty;
    /** Legacy inline note preview state; the dedicated Note Settings screen supersedes it. */
    private boolean colorPixelPreview;
    private boolean colorPreviewCaptured;
    private boolean colorPreviewPreviousPixel;
    private boolean colorPreviewPreviousSongRgb;
    /** 0 = none, 1 = saturation/brightness square, 2 = hue bar. */
    private int colorDragTarget;

    private int sbX() { return width / 2 - 85; }
    private int sbY() { return rowY(3); }
    private int hueY() { return sbY() + 78; }

    /** True while the current mod world forces the note colour values (locked in-game). */
    private static boolean noteColorValuesLocked() {
        return ClientOptions.isLocked("noteColorBase")
                || ClientOptions.isLocked("noteColorHighlight")
                || ClientOptions.isLocked("noteColorOutline");
    }

    private void initColors() {
        beginColorPreview();
        colorDragTarget = 0;
        int w = 170;
        int x = width / 2 - 85;
        boolean valuesLocked = noteColorValuesLocked();

        addRenderableWidget(Button.builder(pixelPreviewLabel(), b -> {
            colorPixelPreview = !colorPixelPreview;
            NoteStyle.setPixelUi(colorPixelPreview);
            b.setMessage(pixelPreviewLabel());
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
        hexBox.setEditable(!valuesLocked);
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

        var reset = addRenderableWidget(Button.builder(Component.literal("Reset Lane"), b -> {
            ClientOptions.get().noteColorBase[selLane] = ClientOptions.defaultBase()[selLane];
            ClientOptions.get().noteColorHighlight[selLane] = ClientOptions.defaultHighlight()[selLane];
            ClientOptions.get().noteColorOutline[selLane] = ClientOptions.defaultOutline()[selLane];
            ClientOptions.save();
            NoteStyle.rebuildLaneColors(selLane);
            loadSelectedColor();
        }).bounds(x + 96, rowY(2), 74, 20).build());
        reset.active = !valuesLocked;

        loadSelectedColor();
    }

    private Component pixelPreviewLabel() {
        return Component.literal("Preview UI: " + (colorPixelPreview ? "Pixel" : "Normal"));
    }

    private void beginColorPreview() {
        if (colorPreviewCaptured) return;
        colorPreviewCaptured = true;
        colorPreviewPreviousPixel = NoteStyle.pixelUi();
        colorPreviewPreviousSongRgb = NoteStyle.songRgbAllowed();
        colorPixelPreview = colorPreviewPreviousPixel;
        // The settings preview demonstrates the player's RGB switch, independently of a
        // paused song's disableNoteRGB author setting.
        NoteStyle.setSongRgbAllowed(true);
    }

    private void restoreColorPreview() {
        if (!colorPreviewCaptured) return;
        colorPreviewCaptured = false;
        NoteStyle.setSongRgbAllowed(colorPreviewPreviousSongRgb);
        NoteStyle.setPixelUi(colorPreviewPreviousPixel);
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
        // The saturation/brightness square and hue bar edit the colour value, so
        // they are inert while the world forces the note colours. Lane selection
        // (clicking a preview note) stays available.
        if (!noteColorValuesLocked()) {
            if (mx >= sx && mx < sx + 72 && my >= sy && my < sy + 72) {
                colorDragTarget = 1;
                return updateColorPick(mx, my);
            }
            if (mx >= sx && mx < sx + 170 && my >= hueY() && my < hueY() + 10) {
                colorDragTarget = 2;
                return updateColorPick(mx, my);
            }
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
        if ("folders".equals(category) && button == 0 && beginPermissionPaint(mouseX, mouseY)) return true;
        if ("folders".equals(category) && button == 0 && clickPackScrollbar(mouseX, mouseY)) return true;
        if ("folders".equals(category) && button == 0 && clickFolderScrollbar(mouseX, mouseY)) return true;
        if (super.mouseClicked(mouseX, mouseY, button)) return true;
        return "colors".equals(category) && button == 0 && beginColorPick(mouseX, mouseY);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if ("folders".equals(category) && button == 0 && draggingPermissions) {
            continuePermissionPaint(mouseX, mouseY);
            return true;
        }
        if ("folders".equals(category) && draggingFolderThumb) {
            scrollFoldersTo(mouseY);
            return true;
        }
        if ("folders".equals(category) && draggingPackThumb) {
            scrollPackTo(mouseY);
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
        draggingPackThumb = false;
        draggingPermissions = false;
        permissionDragVisited.clear();
        if (button == 0) colorDragTarget = 0;
        // rebuilding the recolored sheets is heavy, so do it once the drag ends
        if (colorDirty) applyColorNow();
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if ("folders".equals(category)) {
            if (scrollY == 0) return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
            if (mouseX >= permissionPanelX() - 6
                    && mouseX < permissionPanelX() + permissionPanelWidth() + 6) {
                scrollPackToPx(packScrollTargetPx - scrollY * 18.0);
            } else {
                scrollFoldersToPx(folderScrollTargetPx - scrollY * (FOLDER_ROW_H / 2.0));
            }
            return true;
        }
        if (scrollY != 0 && pageMaxScroll() > 0) {
            scrollPageTo(pageScrollTargetPx - scrollY * 13.0);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    private void initFolders() {
        int headerWidth = Math.min(170, folderListWidth());
        int headerX = folderListX() + (folderListWidth() - headerWidth) / 2;
        addRenderableWidget(Button.builder(Component.literal("Add Mods Folder..."), b -> pickFolder())
                .bounds(headerX, rowY(0), headerWidth, 20).build());

        long cacheBytes = SongLibrary.cacheSizeBytes();
        addRenderableWidget(Button.builder(
                Component.literal(String.format("Clear Download Cache (%.1f MB)", cacheBytes / 1048576.0)), b -> {
                    SongLibrary.clearCache();
                    switchTo("folders");
                }).bounds(headerX, height - 56, headerWidth, 20).build());
        List<String> folders = SongLibrary.getExternalFolders();
        String installedSource = SongLibrary.modsDir().toAbsolutePath().normalize().toString();
        if (selectedFolder != null
                && !selectedFolder.equals(installedSource) && !folders.contains(selectedFolder)) {
            selectedFolder = null;
            selectedPackRoot = null;
        }
        record Source(String key, Path path, String label, boolean installed, boolean direct,
                      int externalIndex, List<ModPackInfo> packs) {}
        List<Source> sources = new ArrayList<>();
        sources.add(new Source("installed", SongLibrary.modsDir(), "Installed Mods", true, false, -1,
                SongLibrary.discoverModPacks(SongLibrary.modsDir())));
        for (int i = 0; i < folders.size(); i++) {
            try {
                Path path = Path.of(folders.get(i)).toAbsolutePath().normalize();
                boolean direct = SongLibrary.isDirectModPath(path);
                List<ModPackInfo> packs = SongLibrary.discoverModPacks(path);
                boolean namedAssets = path.getFileName() != null
                        && path.getFileName().toString().equalsIgnoreCase("assets");
                String label = direct && !packs.isEmpty() && !namedAssets
                        ? packs.get(0).name() : displayPath(path);
                sources.add(new Source("external:" + folders.get(i), path, label, false, direct, i, packs));
            } catch (Exception ignored) {}
        }
        if (selectedPackRoot != null) {
            boolean stillPresent = sources.stream().anyMatch(source -> {
                String sourceId = source.installed() ? installedSource : folders.get(source.externalIndex());
                return sourceId.equals(selectedFolder) && source.packs().stream()
                        .anyMatch(pack -> pack.root().equals(selectedPackRoot));
            });
            if (!stillPresent) selectedPackRoot = null;
        }
        folderTotalRows = sources.size();
        for (Source source : sources) {
            if (!source.direct() && expandedSources.contains(source.key())) {
                folderTotalRows += source.packs().size();
            }
        }
        folderScrollPx = Mth.clamp(folderScrollPx, 0, folderMaxScrollPx(folderTotalRows));
        folderScrollTargetPx = Mth.clamp(folderScrollTargetPx, 0, folderMaxScrollPx(folderTotalRows));
        int listX = folderListX();
        int listW = folderListWidth();
        int row = 0;
        for (Source source : sources) {
            int baseY = folderListTop() + row++ * FOLDER_ROW_H;
            boolean hasPacks = !source.direct() && !source.packs().isEmpty();
            int controls = source.installed() ? 0 : 63;
            int arrowWidth = hasPacks ? 21 : 0;
            if (hasPacks) {
                String arrow = expandedSources.contains(source.key()) ? "▼" : "▶";
                Button expand = addRenderableWidget(Button.builder(Component.literal(arrow), button -> {
                    if (!expandedSources.add(source.key())) expandedSources.remove(source.key());
                    switchTo("folders");
                }).bounds(listX, baseY, 20, 20).build());
                folderWidgets.add(new FolderWidget(expand, baseY, null));
            }
            String sourceIcon = source.direct() && source.packs().size() == 1
                    && source.packs().get(0).icon() != null
                    ? source.packs().get(0).icon().toString() : null;
            String sourceLabel = shortenPath(source.label(), listW - controls - arrowWidth - 24);
            String sourceId = source.installed() ? installedSource : folders.get(source.externalIndex());
            Button pathButton = addRenderableWidget(Button.builder(Component.literal(
                    (sourceIcon == null ? "" : "    ") + sourceLabel), button -> {
                selectedFolder = sourceId;
                selectedPackRoot = source.direct() ? source.path() : null;
                resetPackScroll();
                switchTo("folders");
            }).bounds(listX + arrowWidth, baseY, listW - controls - arrowWidth, 20).build());
            Path sourceSelection = source.direct() ? source.path() : null;
            pathButton.active = !sourceId.equals(selectedFolder)
                    || !java.util.Objects.equals(sourceSelection, selectedPackRoot);
            folderWidgets.add(new FolderWidget(pathButton, baseY, sourceIcon));
            String f = source.installed() ? null : folders.get(source.externalIndex());
            int folderIndex = source.externalIndex();
            Button up = addRenderableWidget(Button.builder(Component.literal("↑"), b -> moveFolder(f, -1))
                    .bounds(listX + listW - 62, baseY, 20, 20).build());
            up.active = !source.installed() && folderIndex > 0;
            up.setMessage(Component.literal("↑"));
            up.visible = !source.installed();
            if (source.installed()) suppressedFolderWidgets.add(up);
            folderWidgets.add(new FolderWidget(up, baseY, null));
            Button down = addRenderableWidget(Button.builder(Component.literal("↓"), b -> moveFolder(f, 1))
                    .bounds(listX + listW - 41, baseY, 20, 20).build());
            down.active = !source.installed() && folderIndex < folders.size() - 1;
            down.setMessage(Component.literal("↓"));
            down.visible = !source.installed();
            if (source.installed()) suppressedFolderWidgets.add(down);
            folderWidgets.add(new FolderWidget(down, baseY, null));
            Button remove = addRenderableWidget(Button.builder(Component.literal("X"), b -> {
                var list = new java.util.ArrayList<>(SongLibrary.getExternalFolders());
                int removed = list.indexOf(f);
                list.remove(f);
                if (f.equals(selectedFolder)) {
                    selectedFolder = list.isEmpty() ? installedSource
                            : list.get(Math.min(Math.max(0, removed), list.size() - 1));
                    selectedPackRoot = null;
                }
                SongLibrary.setExternalFolders(list);
                foldersDirty = true;
                switchTo("folders");
            }).bounds(listX + listW - 20, baseY, 20, 20).build());
            remove.active = remove.visible = !source.installed();
            if (source.installed()) suppressedFolderWidgets.add(remove);
            folderWidgets.add(new FolderWidget(remove, baseY, null));
            if (!source.direct() && expandedSources.contains(source.key())) {
                for (ModPackInfo pack : source.packs()) {
                    int packY = folderListTop() + row++ * FOLDER_ROW_H;
                    String label = shortenPath(pack.name(), listW - 48);
                    String icon = pack.icon() == null ? null : pack.icon().toString();
                    Button mod = addRenderableWidget(Button.builder(Component.literal(
                            (icon == null ? "" : "    ") + label),
                            button -> {
                                selectedFolder = sourceId;
                                selectedPackRoot = pack.root();
                                resetPackScroll();
                                switchTo("folders");
                            })
                            .bounds(listX + 20, packY, listW - 20, 20).build());
                    mod.active = !(sourceId.equals(selectedFolder)
                            && pack.root().equals(selectedPackRoot));
                    folderWidgets.add(new FolderWidget(mod, packY, icon));
                }
            }
        }

        if (selectedFolder != null) initPackPanel();
    }

    private String displayPath(Path path) {
        int count = path.getNameCount();
        if (count <= 3) return path.toString();
        return "..." + java.io.File.separator + path.subpath(count - 3, count);
    }

    private int permissionPanelX() { return 16; }
    private int permissionPanelWidth() { return Math.min(150, Math.max(108, width / 3)); }
    private int packViewportTop() { return 48; }
    private int packViewportBottom() { return Math.max(packViewportTop() + 24, height - 42); }
    private int packIconSize() { return Math.min(48, Math.max(28, permissionPanelWidth() - 30)); }
    private int packNameY() { return packViewportTop() + 8 + packIconSize() + 6; }
    private int packDescriptionY() { return packNameY() + font.lineHeight + 5; }
    private List<net.minecraft.util.FormattedCharSequence> packDescriptionLines() {
        String description = selectedPackInfo == null ? "Select a mod to inspect it."
                : selectedPackInfo.description();
        return font.split(Component.literal(description), permissionPanelWidth() - 10);
    }
    private int packPathY() {
        return packDescriptionY() + packDescriptionLines().size() * (font.lineHeight + 1) + 5;
    }
    private int packMetadataY() { return packPathY() + font.lineHeight + 5; }
    private List<net.minecraft.util.FormattedCharSequence> packMetadataLines() {
        if (selectedPackInfo == null) return List.of();
        List<net.minecraft.util.FormattedCharSequence> lines = new ArrayList<>();
        if (!selectedPackInfo.version().isBlank()) {
            lines.addAll(font.split(Component.literal("Version: " + selectedPackInfo.version()),
                    permissionPanelWidth() - 10));
        }
        if (!selectedPackInfo.license().isBlank()) {
            lines.addAll(font.split(Component.literal("License: " + selectedPackInfo.license()),
                    permissionPanelWidth() - 10));
        }
        if (!selectedPackInfo.contributors().isEmpty()) {
            lines.add(net.minecraft.util.FormattedCharSequence.forward("Contributors",
                    net.minecraft.network.chat.Style.EMPTY));
            for (ModPackInfo.Contributor contributor : selectedPackInfo.contributors()) {
                String role = contributor.role().isBlank() ? "" : " — " + contributor.role();
                lines.addAll(font.split(Component.literal(contributor.name() + role),
                        permissionPanelWidth() - 10));
            }
        }
        return lines;
    }
    private int packPermissionsTitleY() {
        return packMetadataY() + packMetadataLines().size() * (font.lineHeight + 1) + 5;
    }
    private int packPermissionTop() { return packPermissionsTitleY() + 14; }

    private void initPackPanel() {
        Path selectedPath;
        try { selectedPath = selectedPackRoot != null ? selectedPackRoot : Path.of(selectedFolder); }
        catch (Exception ignored) { selectedPath = SongLibrary.modsDir(); }
        selectedPackInfo = selectedPackRoot != null ? ModPackInfo.read(selectedPackRoot)
                : new ModPackInfo(selectedPath, selectedPath.equals(SongLibrary.modsDir().toAbsolutePath().normalize())
                ? "Installed Mods" : displayPath(selectedPath),
                selectedPackRoot == null ? "Mod source containing one or more packs." : "No description provided.",
                null, "", "", List.of());
        permissionTargetLabel = selectedPackInfo.name();
        SongLibrary.ExternalContent[] types = SongLibrary.ExternalContent.values();
        int panelW = permissionPanelWidth();
        int columns = panelW >= 138 ? 2 : 1;
        int gap = 3;
        int buttonW = (panelW - gap * (columns - 1)) / columns;
        int top = packPermissionTop();
        var selected = selectedPermissionContent();
        for (int i = 0; i < types.length; i++) {
            SongLibrary.ExternalContent type = types[i];
            int x = permissionPanelX() + (i % columns) * (buttonW + gap);
            int y = top + (i / columns) * 23;
            Button button = addRenderableWidget(Button.builder(Component.literal(permissionName(type)), b -> {
                boolean enable = !selectedPermissionContent().contains(type);
                setSelectedPermission(type, enable);
            }).bounds(x, y, buttonW, 20).build());
            button.setAlpha(selected.contains(type) ? 1.0f : 0.42f);
            permissionWidgets.add(new PermissionWidget(button, type, y));
        }
        int rows = (types.length + columns - 1) / columns;
        packContentHeight = top + rows * 23 + 8 - packViewportTop();
        clampPackScroll();
    }

    private double packMaxScrollPx() {
        return Math.max(0, packContentHeight - (packViewportBottom() - packViewportTop()));
    }
    private void clampPackScroll() {
        packScrollPx = Mth.clamp(packScrollPx, 0, packMaxScrollPx());
        packScrollTargetPx = Mth.clamp(packScrollTargetPx, 0, packMaxScrollPx());
        applyPackWidgetScroll();
    }
    private void resetPackScroll() {
        packScrollPx = packScrollTargetPx = packScrollFromPx = 0;
        packScrollTweenActive = false;
        draggingPackThumb = false;
    }
    private void scrollPackToPx(double target) {
        updatePackScrollTween();
        packScrollFromPx = packScrollPx;
        packScrollTargetPx = Mth.clamp(target, 0, packMaxScrollPx());
        packScrollTweenStart = System.nanoTime();
        packScrollTweenActive = Math.abs(packScrollTargetPx - packScrollFromPx) > 0.01;
        if (!packScrollTweenActive) packScrollPx = packScrollTargetPx;
    }
    private void updatePackScrollTween() {
        if (!packScrollTweenActive) return;
        double progress = (System.nanoTime() - packScrollTweenStart) / (double) SCROLL_TWEEN_NANOS;
        if (progress >= 1) {
            packScrollPx = packScrollTargetPx;
            packScrollTweenActive = false;
        } else {
            packScrollPx = packScrollFromPx + (packScrollTargetPx - packScrollFromPx)
                    * Easing.apply("expoOut", progress);
        }
        applyPackWidgetScroll();
    }
    private void applyPackWidgetScroll() {
        int offset = (int) Math.round(packScrollPx);
        int top = packViewportTop(), bottom = packViewportBottom();
        for (PermissionWidget permission : permissionWidgets) {
            int y = permission.baseY() - offset;
            permission.button().setY(y);
            permission.button().visible = y + permission.button().getHeight() > top && y < bottom;
        }
    }
    private void scrollPackTo(double mouseY) {
        double max = packMaxScrollPx();
        if (max <= 0) return;
        int top = packViewportTop(), height = packViewportBottom() - top;
        int thumb = Math.max(14, (int) (height * height / (double) Math.max(height, packContentHeight)));
        double fraction = (mouseY - top - thumb / 2.0) / Math.max(1, height - thumb);
        packScrollPx = Mth.clamp(fraction, 0, 1) * max;
        packScrollTargetPx = packScrollFromPx = packScrollPx;
        packScrollTweenActive = false;
        applyPackWidgetScroll();
    }
    private boolean clickPackScrollbar(double mouseX, double mouseY) {
        if (packMaxScrollPx() <= 0) return false;
        int x = permissionPanelX() + permissionPanelWidth() + 1;
        if (mouseX < x - 2 || mouseX >= x + 7
                || mouseY < packViewportTop() || mouseY >= packViewportBottom()) return false;
        draggingPackThumb = true;
        scrollPackTo(mouseY);
        return true;
    }

    private String permissionName(SongLibrary.ExternalContent type) {
        return switch (type) {
            case CHARACTERS -> "Chars";
            default -> type.label;
        };
    }

    private java.util.EnumSet<SongLibrary.ExternalContent> selectedPermissionContent() {
        if (selectedFolder == null) return SongLibrary.allExternalContent();
        return selectedPackRoot == null
                ? SongLibrary.getExternalFolderContent(selectedFolder)
                : SongLibrary.getExternalPackContent(selectedFolder, selectedPackRoot);
    }

    private void setSelectedPermission(SongLibrary.ExternalContent type, boolean enabled) {
        if (selectedFolder == null) return;
        if (selectedPackRoot == null) {
            SongLibrary.setExternalFolderContent(selectedFolder, type, enabled);
        } else {
            SongLibrary.setExternalPackContent(selectedFolder, selectedPackRoot, type, enabled);
        }
        foldersDirty = true;
        var selected = selectedPermissionContent();
        for (PermissionWidget permission : permissionWidgets) {
            permission.button().setAlpha(selected.contains(permission.content()) ? 1.0f : 0.42f);
        }
    }

    private boolean beginPermissionPaint(double mouseX, double mouseY) {
        if (selectedFolder == null) return false;
        for (PermissionWidget permission : permissionWidgets) {
            if (!permission.button().visible || !permission.button().isMouseOver(mouseX, mouseY)) continue;
            permissionDragEnables = !selectedPermissionContent().contains(permission.content());
            draggingPermissions = true;
            permissionDragVisited.clear();
            paintPermission(permission);
            return true;
        }
        return false;
    }

    private void continuePermissionPaint(double mouseX, double mouseY) {
        for (PermissionWidget permission : permissionWidgets) {
            if (permission.button().visible && permission.button().isMouseOver(mouseX, mouseY)) {
                paintPermission(permission);
            }
        }
    }

    private void paintPermission(PermissionWidget permission) {
        if (!permissionDragVisited.add(permission.content())) return;
        setSelectedPermission(permission.content(), permissionDragEnables);
    }

    private void moveFolder(String folder, int direction) {
        var folders = new java.util.ArrayList<>(SongLibrary.getExternalFolders());
        int index = folders.indexOf(folder);
        int next = index + direction;
        if (index < 0 || next < 0 || next >= folders.size()) return;
        java.util.Collections.swap(folders, index, next);
        SongLibrary.setExternalFolders(folders);
        foldersDirty = true;

        double rowTop = next * (double) FOLDER_ROW_H;
        double rowBottom = rowTop + FOLDER_ROW_H;
        double viewport = folderVisibleRows() * (double) FOLDER_ROW_H;
        if (rowTop < folderScrollTargetPx) folderScrollTargetPx = rowTop;
        else if (rowBottom > folderScrollTargetPx + viewport) folderScrollTargetPx = rowBottom - viewport;
        folderScrollTargetPx = Mth.clamp(folderScrollTargetPx, 0, folderMaxScrollPx(folderTotalRows));
        folderScrollPx = folderScrollTargetPx;
        folderScrollTweenActive = false;
        switchTo("folders");
    }

    private int folderListTop() { return rowY(1); }
    private int folderListBottom() {
        return Math.max(folderListTop() + FOLDER_ROW_H, height - 64);
    }
    private int folderListWidth() {
        int available = folderAreaRight() - folderAreaLeft() - 12;
        return Math.min(360, Math.max(70, available));
    }
    private int folderListX() {
        return folderAreaLeft() + Math.max(0, (folderAreaRight() - folderAreaLeft() - folderListWidth()) / 2);
    }
    private int folderAreaLeft() { return permissionPanelX() + permissionPanelWidth() + 10; }
    private int folderAreaRight() { return width - 16; }
    private int folderVisibleRows() {
        return Math.max(1, (folderListBottom() - folderListTop()) / FOLDER_ROW_H);
    }
    private int folderMaxScroll(int total) { return Math.max(0, total - folderVisibleRows()); }
    private double folderMaxScrollPx(int total) { return folderMaxScroll(total) * (double) FOLDER_ROW_H; }
    private int folderScrollbarX() { return folderListX() + folderListWidth() + 4; }

    private int folderThumbHeight(int total) {
        int trackH = folderListBottom() - folderListTop();
        return Math.max(16, trackH * folderVisibleRows() / Math.max(1, total));
    }

    private void scrollFoldersTo(double mouseY) {
        int total = folderTotalRows;
        double max = folderMaxScrollPx(total);
        if (max <= 0) return;
        int top = folderListTop();
        int trackH = folderListBottom() - top;
        int thumbH = folderThumbHeight(total);
        double fraction = (mouseY - top - thumbH / 2.0) / Math.max(1, trackH - thumbH);
        folderScrollPx = Mth.clamp(fraction, 0, 1) * max;
        folderScrollTargetPx = folderScrollFromPx = folderScrollPx;
        folderScrollTweenActive = false;
        applyFolderWidgetScroll();
    }

    private void scrollFoldersToPx(double target) {
        updateFolderScrollTween();
        folderScrollFromPx = folderScrollPx;
        folderScrollTargetPx = Mth.clamp(target, 0,
                folderMaxScrollPx(folderTotalRows));
        folderScrollTweenStart = System.nanoTime();
        folderScrollTweenActive = Math.abs(folderScrollTargetPx - folderScrollFromPx) > 0.01;
        if (!folderScrollTweenActive) folderScrollPx = folderScrollTargetPx;
    }

    private void updateFolderScrollTween() {
        if (!folderScrollTweenActive) return;
        double progress = (System.nanoTime() - folderScrollTweenStart) / (double) SCROLL_TWEEN_NANOS;
        if (progress >= 1) {
            folderScrollPx = folderScrollTargetPx;
            folderScrollTweenActive = false;
        } else {
            double eased = Easing.apply("expoOut", progress);
            folderScrollPx = folderScrollFromPx + (folderScrollTargetPx - folderScrollFromPx) * eased;
        }
        applyFolderWidgetScroll();
    }

    private void applyFolderWidgetScroll() {
        int offset = (int) Math.round(folderScrollPx);
        int top = folderListTop(), bottom = folderListBottom();
        for (FolderWidget row : folderWidgets) {
            int y = row.baseY() - offset;
            row.widget().setY(y);
            row.widget().visible = !suppressedFolderWidgets.contains(row.widget())
                    && y + row.widget().getHeight() > top && y < bottom;
        }
    }

    private boolean clickFolderScrollbar(double mouseX, double mouseY) {
        int total = folderTotalRows;
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
                if (minecraft.screen == this && "folders".equals(category)) {
                    switchTo("folders");
                    scrollFoldersToPx(Double.MAX_VALUE);
                }
            });
        }, "fnf-folder-picker").start();
    }

    /** Grays out and disables a control whose setting is forced by the current mod world. */
    private static <T extends net.minecraft.client.gui.components.AbstractWidget> T lockIf(T widget, String field) {
        if (ClientOptions.isLocked(field)) widget.active = false;
        return widget;
    }

    private void initVisuals() {
        int w = Math.min(260, width - 56);
        int x = width / 2 - w / 2;
        lockIf(addRenderableWidget(Button.builder(animsLabel(false), b ->
                minecraft.setScreen(new AnimationSetPickerScreen(this, false)))
                .bounds(x, rowY(0), w, 20).build()), "animationSet");

        lockIf(addRenderableWidget(Button.builder(animsLabel(true), b ->
                minecraft.setScreen(new AnimationSetPickerScreen(this, true)))
                .bounds(x, rowY(1), w, 20).build()), "opponentAnimationSet");

        lockIf(addRenderableWidget(Button.builder(hudStyleLabel(), b ->
                cycle(List.of("default", "abbreviated", "numbers", "vanilla", "fnf"),
                        ClientOptions.get().hudStyle, false, next -> {
                            ClientOptions.get().hudStyle = next;
                            ClientOptions.save();
                            b.setMessage(hudStyleLabel());
                        })).bounds(x, rowY(2), w, 20).build()), "hudStyle");

        // icon selectors open a searchable list
        lockIf(addRenderableWidget(Button.builder(iconLabel(true),
                b -> minecraft.setScreen(new IconPickerScreen(this, true)))
                .bounds(x, rowY(3), w, 20).build()), "playerIcon");
        lockIf(addRenderableWidget(Button.builder(iconLabel(false),
                b -> minecraft.setScreen(new IconPickerScreen(this, false)))
                .bounds(x, rowY(4), w, 20).build()), "botIcon");
        addRenderableWidget(Button.builder(Component.literal("Rating Position..."),
                b -> minecraft.setScreen(new RatingPositionScreen(this)))
                .bounds(x, rowY(5), w, 20).build());
    }

    private Component iconLabel(boolean player) {
        String cur = player ? ClientOptions.get().playerIcon : ClientOptions.get().botIcon;
        String shown = ClientOptions.SONG_ICON.equals(cur) ? "Default (song)"
                : cur == null || cur.isEmpty() ? "None" : cur;
        return Component.literal((player ? "Player Icon: " : "Opponent Icon: ") + shown);
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

    private Component holdSplashLabel() {
        if (NoteStyle.skinHasOwnHoldCover()) {
            return Component.literal("Hold Cover: (from skin)");
        }
        String current = ClientOptions.get().holdSplashSkin;
        String shown = current == null || current.isBlank()
                || current.equalsIgnoreCase(ClientOptions.NOTE_SKIN_DEFAULT) ? "Default (chart)"
                : current.equalsIgnoreCase(ClientOptions.NOTE_SKIN_NONE) ? "OFF" : current;
        return Component.literal("Hold Cover: " + shown);
    }

    private void initGameplay() {
        int w = Math.min(260, width - 56);
        int x = width / 2 - w / 2;
        lockIf(addRenderableWidget(Button.builder(toggleLabel("Downscroll", ClientOptions.get().downscroll), b -> {
            ClientOptions.get().downscroll = !ClientOptions.get().downscroll;
            ClientOptions.save();
            b.setMessage(toggleLabel("Downscroll", ClientOptions.get().downscroll));
        }).bounds(x, rowY(0), w, 20).build()), "downscroll");

        lockIf(addRenderableWidget(Button.builder(toggleLabel("Middlescroll", ClientOptions.get().middlescroll), b -> {
            ClientOptions.get().middlescroll = !ClientOptions.get().middlescroll;
            ClientOptions.save();
            b.setMessage(toggleLabel("Middlescroll", ClientOptions.get().middlescroll));
        }).bounds(x, rowY(1), w, 20).build()), "middlescroll");

        lockIf(addRenderableWidget(Button.builder(toggleLabel("Ghost Tapping", ClientOptions.get().ghostTapping), b -> {
            ClientOptions.get().ghostTapping = !ClientOptions.get().ghostTapping;
            ClientOptions.save();
            b.setMessage(toggleLabel("Ghost Tapping", ClientOptions.get().ghostTapping));
        }).bounds(x, rowY(2), w, 20).build()), "ghostTapping");

        // scroll speed slider (0.35 - 6) + constant/multiplicative mode toggle
        lockIf(addRenderableWidget(new net.minecraft.client.gui.components.AbstractSliderButton(
                x, rowY(3), w - 52, 20, scrollSpeedMsg(),
                (ClientOptions.get().scrollSpeedMult - 0.35) / (6.0 - 0.35)) {
            @Override protected void updateMessage() { setMessage(scrollSpeedMsg()); }
            @Override protected void applyValue() {
                ClientOptions.get().scrollSpeedMult = 0.35 + value * (6.0 - 0.35);
                ClientOptions.save();
            }
        }), "scrollSpeedMult");
        lockIf(addRenderableWidget(Button.builder(scrollModeLabel(), b -> {
            ClientOptions.get().constantScrollSpeed = !ClientOptions.get().constantScrollSpeed;
            ClientOptions.save();
            b.setMessage(scrollModeLabel());
        }).bounds(x + w - 48, rowY(3), 48, 20).build()), "constantScrollSpeed");

        lockIf(addRenderableWidget(Button.builder(hitsoundLabel(), b ->
                cycle(HitsoundPlayer.list(), ClientOptions.get().hitsound, true, next -> {
                    ClientOptions.get().hitsound = next;
                    ClientOptions.save();
                    b.setMessage(hitsoundLabel());
                    HitsoundPlayer.play();
                })).bounds(x, rowY(4), w, 20).build()), "hitsound");

        // hitsound volume slider (0% - 100%)
        lockIf(addRenderableWidget(new net.minecraft.client.gui.components.AbstractSliderButton(
                x, rowY(5), w, 20, hitsoundVolMsg(), ClientOptions.get().hitsoundVolume) {
            @Override protected void updateMessage() { setMessage(hitsoundVolMsg()); }
            @Override protected void applyValue() {
                long previousPercent = Math.round(ClientOptions.get().hitsoundVolume * 100);
                ClientOptions.get().hitsoundVolume = value;
                ClientOptions.save();
                if (previousPercent != Math.round(value * 100)) HitsoundPlayer.play();
            }
        }), "hitsoundVolume");

        lockIf(addRenderableWidget(Button.builder(toggleLabel("Botplay", ClientOptions.get().botplay), b -> {
            ClientOptions.get().botplay = !ClientOptions.get().botplay;
            ClientOptions.save();
            b.setMessage(toggleLabel("Botplay", ClientOptions.get().botplay));
        }).bounds(x, rowY(6), w, 20).build()), "botplay");

        lockIf(addRenderableWidget(Button.builder(songWarningsLabel(), b ->
                cycle(List.of("on", "off", "blockified", "song"),
                        ClientOptions.get().songWarnings, false, next -> {
                            ClientOptions.get().songWarnings = next;
                            ClientOptions.save();
                            b.setMessage(songWarningsLabel());
                        })).bounds(x, rowY(7), w, 20).build()), "songWarnings");

        lockIf(addRenderableWidget(Button.builder(preciseInputLabel(), b -> {
            ClientOptions.get().preciseInput = !ClientOptions.get().preciseInput;
            ClientOptions.save();
            b.setMessage(preciseInputLabel());
        }).bounds(x, rowY(8), w, 20).build()), "preciseInput");
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

        gui.drawString(font, "Hex:", sx - 4, rowY(3) + 6, 0xFFFFFF);

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
            gui.drawCenteredString(font, "Current skin has no atlas available for RGB colors.",
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

    private Component animsLabel(boolean opponent) {
        String selected = opponent ? ClientOptions.get().opponentAnimationSet
                : ClientOptions.get().animationSet;
        String shown = CharacterAnimations.NONE_SET.equalsIgnoreCase(selected) ? "None"
                : CharacterAnimations.DEFAULT_SET.equalsIgnoreCase(selected) ? "Default (song)"
                : selected + ".json";
        return Component.literal((opponent ? "Opponent Anims: " : "Player Anims: ") + shown);
    }

    private Component scrollSpeedLabel() {
        return Component.literal(String.format("Scroll Speed: x%.2f", ClientOptions.get().scrollSpeedMult));
    }

    // ------------------------------------------------------------------

    @Override
    public void render(GuiGraphics gui, int mouseX, int mouseY, float partialTick) {
        updatePageScrollTween();
        updateFolderScrollTween();
        updatePackScrollTween();
        int shellX = "folders".equals(category) ? 6 : Math.max(6, width / 2 - Math.min(230, (width - 12) / 2));
        int shellWidth = "folders".equals(category) ? width - 12 : Math.min(460, width - 12);
        BlockifiedScreenStyle.backdrop(gui, width, height);
        BlockifiedScreenStyle.panel(gui, shellX, 6, shellWidth, height - 12);
        BlockifiedScreenStyle.inner(gui, shellX + 10, pageViewportTop() - 2,
                shellWidth - 20, pageViewportBottom() - pageViewportTop() + 4);
        if ("folders".equals(category)) renderPackPanelBackground(gui);
        if ("folders".equals(category)) {
            // Render list rows ourselves inside one scissor. Merely hiding rows
            // whose origins are outside the viewport allowed partially visible
            // buttons/text to paint over the filter controls.
            for (FolderWidget row : folderWidgets) row.widget().visible = false;
            for (PermissionWidget permission : permissionWidgets) permission.button().visible = false;
        } else {
            // Render scrolling page controls ourselves under one scissor. Merely
            // hiding controls whose origins left the viewport allowed partially
            // visible buttons to paint over headers/footers on compact screens.
            for (AbstractWidget widget : pageWidgetBaseY.keySet()) widget.visible = false;
        }
        super.render(gui, mouseX, mouseY, partialTick);
        if ("folders".equals(category)) {
            renderFolderRows(gui, mouseX, mouseY, partialTick);
            renderPackPanelContent(gui, mouseX, mouseY, partialTick);
        }
        else renderPageRows(gui, mouseX, mouseY, partialTick);
        String title = switch (category == null ? "" : category) {
            case "visuals" -> "Visuals and UI";
            case "gameplay" -> "Gameplay";
            case "folders" -> "Mods";
            case "colors" -> "Note Settings";
            default -> "Options";
        };
        gui.drawString(font, "BLOCKIFIED SETTINGS", shellX + 14, 13,
                BlockifiedScreenStyle.ACCENT, false);
        gui.drawCenteredString(font, title, width / 2, 27, BlockifiedScreenStyle.TEXT);

        if ("colors".equals(category)) {
            gui.enableScissor(0, pageViewportTop(), width, pageViewportBottom());
            renderColorPicker(gui);
            gui.disableScissor();
        }

        if ("folders".equals(category)) {
            if (folderMaxScroll(folderTotalRows) > 0) {
                int trackX = folderScrollbarX();
                int trackTop = folderListTop();
                int trackH = folderListBottom() - trackTop;
                int thumbH = folderThumbHeight(folderTotalRows);
                int thumbY = trackTop + (int) ((trackH - thumbH)
                        * (folderScrollPx / folderMaxScrollPx(folderTotalRows)));
                gui.fill(trackX, trackTop, trackX + 5, folderListBottom(), 0x55000000);
                gui.fill(trackX, thumbY, trackX + 5, thumbY + thumbH,
                        BlockifiedScreenStyle.ACCENT);
            }
        } else if (pageMaxScroll() > 0) {
            int top = pageViewportTop(), bottom = pageViewportBottom();
            int trackH = bottom - top;
            int contentH = pageContentBottom() - pageViewportTop();
            int thumbH = Math.max(16, trackH * trackH / Math.max(trackH, contentH));
            int thumbY = top + (int) ((trackH - thumbH) * (pageScrollPx / pageMaxScroll()));
            gui.fill(width - 7, top, width - 4, bottom, 0x55000000);
            gui.fill(width - 7, thumbY, width - 4, thumbY + thumbH,
                    BlockifiedScreenStyle.ACCENT);
        }

        if ("visuals".equals(category)) {
            gui.drawCenteredString(font, "animations/<name>.json  icons/<pack>/<name>.png",
                    width / 2, hintY(1), 0xAAAAAA);
            gui.drawCenteredString(font, "All folders under config/fnfmod/",
                    width / 2, hintY(0), 0xAAAAAA);
        }
    }

    private void renderPackPanelBackground(GuiGraphics gui) {
        int panelX = permissionPanelX();
        int panelW = permissionPanelWidth();
        BlockifiedScreenStyle.inner(gui, panelX - 6, 42, panelW + 12, height - 82);
    }

    private void renderPackPanelContent(GuiGraphics gui, int mouseX, int mouseY, float partialTick) {
        int panelX = permissionPanelX(), panelW = permissionPanelWidth();
        int top = packViewportTop(), bottom = packViewportBottom();
        gui.enableScissor(panelX - 4, top, panelX + panelW + 5, bottom);
        int offset = (int) Math.round(packScrollPx);
        if (selectedPackInfo == null) {
            gui.drawCenteredString(font, "Select a mod", panelX + panelW / 2, top + 10,
                    BlockifiedScreenStyle.TEXT_MUTED);
        } else {
            int iconSize = packIconSize(), iconTop = top + 8 - offset;
            if (selectedPackInfo.icon() != null) {
                IconLibrary.drawFile(gui, selectedPackInfo.icon().toString(), 0,
                        panelX + panelW / 2f, iconTop + iconSize / 2f, iconSize, false);
            } else {
                gui.fill(panelX + panelW / 2 - iconSize / 2, iconTop,
                        panelX + panelW / 2 + iconSize / 2, iconTop + iconSize, 0x55333333);
                gui.drawCenteredString(font, "No icon", panelX + panelW / 2,
                        iconTop + iconSize / 2 - 4, BlockifiedScreenStyle.TEXT_MUTED);
            }
            String name = shortenPath(selectedPackInfo.name(), panelW - 8);
            gui.drawCenteredString(font, name, panelX + panelW / 2, packNameY() - offset,
                    BlockifiedScreenStyle.ACCENT);
            int descriptionY = packDescriptionY() - offset;
            for (var line : packDescriptionLines()) {
                gui.drawCenteredString(font, line, panelX + panelW / 2, descriptionY,
                        BlockifiedScreenStyle.TEXT_SECTION);
                descriptionY += font.lineHeight + 1;
            }
            String path = shortenPath(displayPath(selectedPackInfo.root()), panelW - 8);
            gui.drawCenteredString(font, path, panelX + panelW / 2, packPathY() - offset,
                    BlockifiedScreenStyle.TEXT_MUTED);
            int metadataY = packMetadataY() - offset;
            for (var line : packMetadataLines()) {
                gui.drawCenteredString(font, line, panelX + panelW / 2, metadataY,
                        BlockifiedScreenStyle.TEXT_SECTION);
                metadataY += font.lineHeight + 1;
            }
            gui.drawCenteredString(font, "Permissions", panelX + panelW / 2,
                    packPermissionsTitleY() - offset, BlockifiedScreenStyle.TEXT);
        }
        for (PermissionWidget permission : permissionWidgets) {
            Button button = permission.button();
            int y = permission.baseY() - offset;
            button.setY(y);
            button.visible = y + button.getHeight() > top && y < bottom;
            if (button.visible) button.render(gui, mouseX, mouseY, partialTick);
        }
        gui.flush();
        gui.disableScissor();
        if (packMaxScrollPx() > 0) {
            int height = bottom - top;
            int thumb = Math.max(14, (int) (height * height / (double) Math.max(height, packContentHeight)));
            int thumbY = top + (int) ((height - thumb) * (packScrollPx / packMaxScrollPx()));
            int x = panelX + panelW + 1;
            gui.fill(x, top, x + 4, bottom, 0x55000000);
            gui.fill(x, thumbY, x + 4, thumbY + thumb, BlockifiedScreenStyle.ACCENT);
        }
    }

    @Override
    public void renderBackground(GuiGraphics gui, int mouseX, int mouseY, float partialTick) {
        // render() owns the full background so widgets stay crisp and unblurred.
    }

    private void renderFolderRows(GuiGraphics gui, int mouseX, int mouseY, float partialTick) {
        int top = folderListTop(), bottom = folderListBottom();
        gui.enableScissor(folderListX(), top, folderListX() + folderListWidth(), bottom);
        for (FolderWidget row : folderWidgets) {
            AbstractWidget widget = row.widget();
            int y = widget.getY();
            boolean visible = !suppressedFolderWidgets.contains(widget)
                    && y + widget.getHeight() > top && y < bottom;
            widget.visible = visible;
            if (!visible) continue;
            widget.render(gui, mouseX, mouseY, partialTick);
            if (row.iconPath() != null) {
                IconLibrary.drawFile(gui, row.iconPath(), 0,
                        widget.getX() + 11, widget.getY() + widget.getHeight() / 2f,
                        16, false);
            }
        }
        gui.disableScissor();
    }

    private void renderPageRows(GuiGraphics gui, int mouseX, int mouseY, float partialTick) {
        int top = pageViewportTop(), bottom = pageViewportBottom();
        gui.enableScissor(0, top, width, bottom);
        for (var entry : pageWidgetBaseY.entrySet()) {
            AbstractWidget widget = entry.getKey();
            int y = widget.getY();
            boolean visible = y + widget.getHeight() > top && y < bottom;
            widget.visible = visible;
            if (visible) widget.render(gui, mouseX, mouseY, partialTick);
        }
        gui.disableScissor();
    }

    /** Y for a hint line sitting just above the Back button (line 0 = closest). */
    private int hintY(int lineFromBottom) {
        return (height - 32) - 12 - lineFromBottom * 10;
    }

    @Override
    public void onClose() {
        if (category != null) {
            leaveCategory();
        } else {
            minecraft.setScreen(parent);
        }
    }

    @Override
    public void removed() {
        restoreColorPreview();
        super.removed();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
