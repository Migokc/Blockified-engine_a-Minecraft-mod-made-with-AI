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
 * (Controls / Adjust Delay and Combo / Visuals and UI / Gameplay),
 * each opening its own page.
 */
public class FnfSettingsScreen extends Screen {

    private final Screen parent;
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
    private boolean filtersOpen;
    private record PermissionWidget(Button button, SongLibrary.ExternalContent content) {}
    private final List<PermissionWidget> permissionWidgets = new ArrayList<>();
    private final Set<SongLibrary.ExternalContent> permissionDragVisited =
            java.util.EnumSet.noneOf(SongLibrary.ExternalContent.class);
    private boolean draggingPermissions;
    private boolean permissionDragEnables;
    private String permissionTargetLabel = "";
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
        pageWidgetBaseY.clear();
        folderWidgets.clear();
        suppressedFolderWidgets.clear();
        permissionWidgets.clear();
        if (category == null) {
            initCategories();
            capturePageWidgets();
            addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose())
                    .bounds(width / 2 - 60, height - 32, 120, 20).build());
        } else {
            switch (category) {
                case "delay" -> initDelay();
                case "visuals" -> initVisuals();
                case "gameplay" -> initGameplay();
                case "folders" -> initFolders();
                case "colors" -> initColors();
            }
            if (!"folders".equals(category)) capturePageWidgets();
            addRenderableWidget(Button.builder(Component.literal("Back"), b -> switchTo(null))
                    .bounds(width / 2 - 60, height - 32, 120, 20).build());
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
        category = newCategory;
        pageScrollPx = pageScrollTargetPx = pageScrollFromPx = 0;
        pageScrollTweenActive = false;
        init();
    }

    private int rowY(int index) {
        return 50 + index * 26 - (int) Math.round(pageScrollPx);
    }

    private int pageViewportTop() { return 44; }
    private int pageViewportBottom() {
        int reserved = "visuals".equals(category) ? 64 : "delay".equals(category) ? 54 : 40;
        return Math.max(pageViewportTop() + 20, height - reserved);
    }

    private int pageContentBottom() {
        return switch (category == null ? "categories" : category) {
            case "categories" -> 50 + 6 * 26 + 20;
            case "visuals" -> 50 + 7 * 26 + 20;
            case "gameplay" -> 50 + 8 * 26 + 20;
            case "colors" -> 50 + 3 * 26 + 78 + 28;
            case "delay" -> 50 + 26 + 20;
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
        addRenderableWidget(Button.builder(Component.literal("Mods"),
                        b -> switchTo("folders"))
                .bounds(x, rowY(5), w, 20).build());
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

    /** True while the current mod world forces the note colour values (locked in-game). */
    private static boolean noteColorValuesLocked() {
        return ClientOptions.isLocked("noteColorBase") || ClientOptions.isLocked("noteColorOutline");
    }

    private void initColors() {
        colorDragTarget = 0;
        int w = 170;
        int x = width / 2 - 85;
        boolean enabledLocked = ClientOptions.isLocked("noteColorsEnabled");
        boolean valuesLocked = noteColorValuesLocked();

        var toggle = addRenderableWidget(Button.builder(coloredNotesLabel(), b -> {
            ClientOptions.get().noteColorsEnabled = !ClientOptions.get().noteColorsEnabled;
            ClientOptions.save();
            b.setMessage(coloredNotesLabel());
        }).bounds(x, rowY(0), w, 20).build());
        toggle.active = !enabledLocked;

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
            ClientOptions.get().noteColorOutline[selLane] = ClientOptions.defaultOutline()[selLane];
            ClientOptions.save();
            NoteStyle.rebuildLaneColors(selLane);
            loadSelectedColor();
        }).bounds(x + 96, rowY(2), 74, 20).build());
        reset.active = !valuesLocked;

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
        if ("colors".equals(category) && button == 0 && colorDragTarget != 0) {
            return updateColorPick(mouseX, mouseY);
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        draggingFolderThumb = false;
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
            scrollFoldersToPx(folderScrollTargetPx - scrollY * (FOLDER_ROW_H / 2.0));
            return true;
        }
        if (scrollY != 0 && pageMaxScroll() > 0) {
            scrollPageTo(pageScrollTargetPx - scrollY * 13.0);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    private void initFolders() {
        Button permissionsToggle = addRenderableWidget(Button.builder(
                Component.literal(filtersOpen ? "Close Permissions" : "Permissions"), b -> {
            filtersOpen = !filtersOpen;
            switchTo("folders");
        }).bounds(8, 18, 104, 20).build());
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
            filtersOpen = false;
        }
        permissionsToggle.active = selectedFolder != null;
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
            int controls = source.installed() ? 0 : source.direct() ? 84 : 63;
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
                switchTo("folders");
            }).bounds(listX + arrowWidth, baseY, listW - controls - arrowWidth, 20).build());
            Path sourceSelection = source.direct() ? source.path() : null;
            pathButton.active = !sourceId.equals(selectedFolder)
                    || !java.util.Objects.equals(sourceSelection, selectedPackRoot);
            folderWidgets.add(new FolderWidget(pathButton, baseY, sourceIcon));
            if (source.direct() && !source.packs().isEmpty()) {
                Button info = addRenderableWidget(Button.builder(Component.literal("i"), button ->
                                minecraft.setScreen(new ModDetailsScreen(this, source.packs().get(0))))
                        .bounds(listX + listW - 84, baseY, 20, 20).build());
                folderWidgets.add(new FolderWidget(info, baseY, null));
            }
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
                                switchTo("folders");
                            })
                            .bounds(listX + 20, packY, listW - 43, 20).build());
                    mod.active = !(sourceId.equals(selectedFolder)
                            && pack.root().equals(selectedPackRoot));
                    folderWidgets.add(new FolderWidget(mod, packY, icon));
                    Button info = addRenderableWidget(Button.builder(Component.literal("i"), button ->
                                    minecraft.setScreen(new ModDetailsScreen(this, pack)))
                            .bounds(listX + listW - 22, packY, 20, 20).build());
                    folderWidgets.add(new FolderWidget(info, packY, null));
                }
            }
        }

        if (filtersOpen && selectedFolder != null) initPermissionPanel();
    }

    private String displayPath(Path path) {
        int count = path.getNameCount();
        if (count <= 3) return path.toString();
        return "..." + java.io.File.separator + path.subpath(count - 3, count);
    }

    private int permissionPanelWidth() { return Math.min(150, Math.max(108, width / 3)); }

    private void initPermissionPanel() {
        if (selectedPackRoot != null) permissionTargetLabel = ModPackInfo.read(selectedPackRoot).name();
        else if (selectedFolder != null && selectedFolder.equals(
                SongLibrary.modsDir().toAbsolutePath().normalize().toString())) permissionTargetLabel = "Installed Mods";
        else {
            try { permissionTargetLabel = displayPath(Path.of(selectedFolder)); }
            catch (Exception ignored) { permissionTargetLabel = "Selected source"; }
        }
        SongLibrary.ExternalContent[] types = SongLibrary.ExternalContent.values();
        int panelW = permissionPanelWidth();
        int availableRows = Math.max(2, (height - 112) / 23);
        int columns = Math.max(2, (types.length + availableRows - 1) / availableRows);
        columns = Math.min(3, columns);
        int gap = 3;
        int buttonW = (panelW - 12 - gap * (columns - 1)) / columns;
        int top = 64;
        var selected = selectedPermissionContent();
        for (int i = 0; i < types.length; i++) {
            SongLibrary.ExternalContent type = types[i];
            int x = 6 + (i % columns) * (buttonW + gap);
            int y = top + (i / columns) * 23;
            Button button = addRenderableWidget(Button.builder(Component.literal(permissionName(type)), b -> {
                boolean enable = !selectedPermissionContent().contains(type);
                setSelectedPermission(type, enable);
            }).bounds(x, y, buttonW, 20).build());
            button.setAlpha(selected.contains(type) ? 1.0f : 0.42f);
            permissionWidgets.add(new PermissionWidget(button, type));
        }
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
        if (!filtersOpen) return false;
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
        int reservedLeft = filtersOpen ? permissionPanelWidth() + 18 : 20;
        return Math.min(360, Math.max(130, width - reservedLeft - 20));
    }
    private int folderListX() {
        if (!filtersOpen) return width / 2 - folderListWidth() / 2;
        int left = permissionPanelWidth() + 12;
        return left + Math.max(0, (width - left - folderListWidth()) / 2);
    }
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

    private void initDelay() {
        int y = rowY(1);
        int cx = width / 2;
        lockIf(addRenderableWidget(Button.builder(Component.literal("-10"), b -> nudgeOffset(-10))
                .bounds(cx - 90, y, 40, 20).build()), "offsetMs");
        lockIf(addRenderableWidget(Button.builder(Component.literal("-1"), b -> nudgeOffset(-1))
                .bounds(cx - 46, y, 40, 20).build()), "offsetMs");
        lockIf(addRenderableWidget(Button.builder(Component.literal("+1"), b -> nudgeOffset(1))
                .bounds(cx + 6, y, 40, 20).build()), "offsetMs");
        lockIf(addRenderableWidget(Button.builder(Component.literal("+10"), b -> nudgeOffset(10))
                .bounds(cx + 50, y, 40, 20).build()), "offsetMs");
    }

    private void nudgeOffset(double delta) {
        ClientOptions.get().offsetMs += delta;
        ClientOptions.save();
    }

    /** Grays out and disables a control whose setting is forced by the current mod world. */
    private static <T extends net.minecraft.client.gui.components.AbstractWidget> T lockIf(T widget, String field) {
        if (ClientOptions.isLocked(field)) widget.active = false;
        return widget;
    }

    private void initVisuals() {
        int w = 170;
        int x = width / 2 - w / 2;
        lockIf(addRenderableWidget(Button.builder(noteSkinLabel(), b ->
                cycle(NoteStyle.listSkins(), ClientOptions.get().noteSkin, false, next -> {
                    ClientOptions.get().noteSkin = next;
                    ClientOptions.save();
                    NoteStyle.reload();
                    b.setMessage(noteSkinLabel());
                })).bounds(x, rowY(0), w, 20).build()), "noteSkin");

        Button splashBtn = addRenderableWidget(Button.builder(splashLabel(), b ->
                cycle(NoteStyle.listSplashes(), ClientOptions.get().splashSkin, true, next -> {
                    ClientOptions.get().splashSkin = next;
                    ClientOptions.save();
                    NoteStyle.reload();
                    b.setMessage(splashLabel());
                })).bounds(x, rowY(1), w, 20).build());
        splashBtn.active = !NoteStyle.skinHasOwnSplash() && !ClientOptions.isLocked("splashSkin");

        lockIf(addRenderableWidget(Button.builder(animsLabel(false), b ->
                minecraft.setScreen(new AnimationSetPickerScreen(this, false)))
                .bounds(x, rowY(2), w, 20).build()), "animationSet");

        lockIf(addRenderableWidget(Button.builder(animsLabel(true), b ->
                minecraft.setScreen(new AnimationSetPickerScreen(this, true)))
                .bounds(x, rowY(3), w, 20).build()), "opponentAnimationSet");

        lockIf(addRenderableWidget(Button.builder(hudStyleLabel(), b ->
                cycle(List.of("default", "abbreviated", "numbers", "vanilla", "fnf"),
                        ClientOptions.get().hudStyle, false, next -> {
                            ClientOptions.get().hudStyle = next;
                            ClientOptions.save();
                            b.setMessage(hudStyleLabel());
                        })).bounds(x, rowY(4), w, 20).build()), "hudStyle");

        // icon selectors open a searchable list
        lockIf(addRenderableWidget(Button.builder(iconLabel(true),
                b -> minecraft.setScreen(new IconPickerScreen(this, true)))
                .bounds(x, rowY(5), w, 20).build()), "playerIcon");
        lockIf(addRenderableWidget(Button.builder(iconLabel(false),
                b -> minecraft.setScreen(new IconPickerScreen(this, false)))
                .bounds(x, rowY(6), w, 20).build()), "botIcon");
        addRenderableWidget(Button.builder(Component.literal("Rating Position..."),
                b -> minecraft.setScreen(new RatingPositionScreen(this)))
                .bounds(x, rowY(7), w, 20).build());
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

    private void initGameplay() {
        int w = 170;
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
        if ("folders".equals(category) && filtersOpen) renderPermissionPanelBackground(gui);
        if ("folders".equals(category)) {
            // Render list rows ourselves inside one scissor. Merely hiding rows
            // whose origins are outside the viewport allowed partially visible
            // buttons/text to paint over the filter controls.
            for (FolderWidget row : folderWidgets) row.widget().visible = false;
        }
        super.render(gui, mouseX, mouseY, partialTick);
        if ("folders".equals(category)) renderFolderRows(gui, mouseX, mouseY, partialTick);
        String title = switch (category == null ? "" : category) {
            case "delay" -> "Adjust Delay and Combo";
            case "visuals" -> "Visuals and UI";
            case "gameplay" -> "Gameplay";
            case "folders" -> "Mods";
            case "colors" -> "Note Colors";
            default -> "Options";
        };
        gui.drawCenteredString(font, title, width / 2, 20, 0xFFFFFF);

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
                gui.fill(trackX, thumbY, trackX + 5, thumbY + thumbH, 0xFFAAAAAA);
            }
            if (selectedFolder == null) {
                gui.drawCenteredString(font, "Installed mods are always available",
                        width / 2, folderListBottom() + 3, 0xAAAAAA);
            }
        } else if (pageMaxScroll() > 0) {
            int top = pageViewportTop(), bottom = pageViewportBottom();
            int trackH = bottom - top;
            int contentH = pageContentBottom() - pageViewportTop();
            int thumbH = Math.max(16, trackH * trackH / Math.max(trackH, contentH));
            int thumbY = top + (int) ((trackH - thumbH) * (pageScrollPx / pageMaxScroll()));
            gui.fill(width - 7, top, width - 4, bottom, 0x55000000);
            gui.fill(width - 7, thumbY, width - 4, thumbY + thumbH, 0xFFAAAAAA);
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

    private void renderPermissionPanelBackground(GuiGraphics gui) {
        int panelW = permissionPanelWidth();
        gui.fill(2, 42, panelW, height - 40, 0xE6181822);
        gui.renderOutline(2, 42, panelW - 2, height - 82, 0xFF555566);
        String label = shortenPath(permissionTargetLabel, panelW - 12);
        gui.drawCenteredString(font, label, panelW / 2, 49, 0xFFFFFFFF);
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
