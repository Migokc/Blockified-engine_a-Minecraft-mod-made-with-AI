package com.fnfmod.client.gui;

import com.fnfmod.client.ClientOptions;
import com.fnfmod.client.AutoWorldMenuClient;
import com.fnfmod.client.ClientSession;
import com.fnfmod.client.ClientStorySession;
import com.fnfmod.client.ScoreStore;
import com.fnfmod.client.math.Easing;
import com.fnfmod.client.render.IconLibrary;
import com.fnfmod.gameplay.PlaybackMode;
import com.fnfmod.net.FnfPayloads;
import com.fnfmod.song.WeekDefinition;
import com.fnfmod.song.WeekLibrary;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.controls.KeyBindsScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

import java.util.List;
import java.util.Locale;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.nio.file.Path;

/** Searchable Freeplay/Story Mode/Settings hub opened by a Funkin' Machine. */
public class SongSelectScreen extends Screen {
    private enum Tab { FREEPLAY("Freeplay"), STORY("Story Mode"), SETTINGS("Settings");
        final String label; Tab(String label) { this.label = label; } }
    /** Menu choices survive screen recreation after songs, weeks, and machine closing. */
    private static Tab rememberedTab = Tab.FREEPLAY;
    private static Path rememberedModRoot;
    private record Section(String title, List<FnfPayloads.SongInfo> songs) {}
    private record Card(FnfPayloads.SongInfo song, int x, int y) {}
    private static final ResourceLocation ALL_ICON = ResourceLocation.fromNamespaceAndPath(
            "fnfmod", "textures/gui/freeplay_all.png");
    private static final ResourceLocation GROUPED_ICON = ResourceLocation.fromNamespaceAndPath(
            "fnfmod", "textures/gui/freeplay_by_mod_week.png");

    private final BlockPos pos;
    private final List<FnfPayloads.SongInfo> songs;
    private final List<WeekDefinition> allWeeks;
    private final List<FreeplayCatalog.Mod> mods;
    private List<FnfPayloads.SongInfo> visible;
    private List<Section> sections = List.of();
    private boolean groupedFreeplay;
    private int selectedMod;
    private String modQuery = "";
    private List<Integer> matchingMods = List.of();
    private double modScrollPx, modScrollTargetPx, modScrollFromPx;
    private long modScrollTweenStartNano;
    private boolean modScrollTweenActive;
    private boolean draggingModThumb;
    private List<WeekDefinition> visibleWeeks;
    private String query = "";
    private Tab tab = rememberedTab;

    private static final int BOX_W = 84, BOX_H = 92, GAP = 6, WEEK_ROW_H = 28;
    private static final long SCROLL_TWEEN_NANOS = 200_000_000L;
    private double scrollPx, scrollTargetPx, scrollFromPx;
    private long scrollTweenStartNano;
    private boolean scrollTweenActive, draggingThumb;
    private double storySongScrollPx, storySongScrollTargetPx, storySongScrollFromPx;
    private long storySongScrollTweenStartNano;
    private boolean storySongScrollTweenActive, draggingStorySongThumb;
    private EditBox searchBox;
    private EditBox modSearchBox;
    private int selectedWeek, difficultyIndex;
    private PlaybackMode storyPlaybackMode = PlaybackMode.MINECRAFT;
    private Button storyDifficulty, storyPlaybackModeButton, storyPlay;
    private Button creditsButton;
    private Button layoutButton;
    private boolean showStoryDifficultyList;
    private static final int DIFFICULTY_ROW_H = 13;
    private final java.util.Map<Button, String> settingsButtons = new java.util.LinkedHashMap<>();

    public SongSelectScreen(BlockPos pos, List<FnfPayloads.SongInfo> songs) {
        super(Component.literal("Song Menu"));
        this.pos = pos;
        this.songs = songs == null ? List.of() : List.copyOf(songs);
        allWeeks = WeekLibrary.storyWeeks(this.songs);
        mods = FreeplayCatalog.build(this.songs);
        for (int index = 0; index < mods.size(); index++) {
            if (mods.get(index).root().equals(rememberedModRoot)) {
                selectedMod = index;
                break;
            }
        }
        matchingMods = matchingMods("");
        groupedFreeplay = ClientOptions.get().freeplayByModAndWeek;
        visible = freeplaySongs();
        visibleWeeks = allWeeks;
    }

    @Override protected void init() {
        String previous = searchBox == null ? query : searchBox.getValue();
        int x = contentX(), w = contentWidth();
        boolean splitSearch = tab == Tab.FREEPLAY && groupedFreeplay;
        int songSearchW = splitSearch ? (w - 4) / 2 : w;
        searchBox = addRenderableWidget(new EditBox(font, x, 20, songSearchW, 16, Component.literal("search")));
        searchBox.setHint(Component.literal(tab == Tab.STORY ? "Search weeks or songs..."
                : tab == Tab.SETTINGS ? "Search settings..." : "Search songs..."));
        searchBox.setValue(previous);
        searchBox.setResponder(this::applyFilter);
        modSearchBox = null;
        if (splitSearch) {
            modSearchBox = addRenderableWidget(new EditBox(font, x + songSearchW + 4, 20,
                    w - songSearchW - 4, 16, Component.literal("Search mods")));
            modSearchBox.setHint(Component.literal("Search mods..."));
            modSearchBox.setValue(modQuery);
            modSearchBox.setResponder(this::applyModFilter);
        }
        int tabWidth = (w - 4) / 3, tabX = x;
        layoutButton = addRenderableWidget(Button.builder(Component.empty(), ignored -> {
            groupedFreeplay = !groupedFreeplay;
            ClientOptions.get().freeplayByModAndWeek = groupedFreeplay;
            ClientOptions.save();
            rebuildWidgets();
        }).bounds(x, 40, 20, 20).build());
        layoutButton.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal(
                "Freeplay layout: " + (groupedFreeplay ? "by mod and week" : "all songs"))));
        for (Tab value : Tab.values()) {
            int buttonX = value == Tab.FREEPLAY ? tabX + 20 : tabX;
            int buttonW = value == Tab.FREEPLAY ? tabWidth - 20 : tabWidth;
            Button button = addRenderableWidget(Button.builder(Component.literal(value.label), ignored -> switchTab(value))
                    .bounds(buttonX, 40, buttonW, 20).build());
            button.active = value != tab;
            tabX += tabWidth + 2;
        }
        if (tab == Tab.STORY) buildStoryControls();
        else if (tab == Tab.SETTINGS) buildSettingsControls();
        applyFilter(previous);
    }

    private void switchTab(Tab next) {
        if (tab == next) return;
        tab = next;
        rememberedTab = next;
        query = "";
        scrollPx = scrollTargetPx = scrollFromPx = 0;
        scrollTweenActive = false;
        resetStorySongScroll();
        rebuildWidgets();
    }

    private void buildStoryControls() {
        int rightX = storyRightX(), rightW = contentX() + contentWidth() - rightX;
        int footerY = listY() + listHeight() - 46, half = (rightW - 6) / 2;
        storyDifficulty = addRenderableWidget(Button.builder(storyDifficultyLabel(), button ->
                showStoryDifficultyList = !showStoryDifficultyList)
                .bounds(rightX, footerY, half, 20).build());
        storyPlaybackModeButton = addRenderableWidget(Button.builder(storyPlaybackModeLabel(), button -> {
            storyPlaybackMode = storyPlaybackMode.next(hasShiftDown());
            button.setMessage(storyPlaybackModeLabel());
        }).bounds(rightX + half + 6, footerY, rightW - half - 6, 20).build());
        storyPlay = addRenderableWidget(Button.builder(Component.literal("Play Week"), button -> playSelectedWeek())
                .bounds(rightX, footerY + 24, rightW, 20).build());
        storyDifficulty.active = selectedWeek() != null;
        storyPlaybackModeButton.active = selectedWeek() != null;
        storyPlay.active = selectedWeek() != null;
    }

    private void buildSettingsControls() {
        settingsButtons.clear();
        int panelW = Math.min(420, contentWidth() - 24), x = width / 2 - panelW / 2;
        int y = listY() + 18, gap = 8, buttonW = (panelW - gap) / 2;
        settingButton("Note Settings", x, y, buttonW, () -> minecraft.setScreen(new NoteSettingsScreen(this)));
        settingButton("Controls", x + buttonW + gap, y, buttonW,
                () -> minecraft.setScreen(new KeyBindsScreen(this, minecraft.options)));
        settingButton("Visuals and UI", x, y + 36, buttonW,
                () -> minecraft.setScreen(new FnfSettingsScreen(this, "visuals")));
        settingButton("Gameplay", x + buttonW + gap, y + 36, buttonW,
                () -> minecraft.setScreen(new FnfSettingsScreen(this, "gameplay")));
        settingButton("Mods", x, y + 72, panelW,
                () -> minecraft.setScreen(new FnfSettingsScreen(this, "folders")));
        creditsButton = addRenderableWidget(Button.builder(Component.literal("C"), ignored ->
                        minecraft.setScreen(new CreditsScreen(this)))
                .bounds(contentX() + contentWidth() - 24, listY() + listHeight() - 24, 20, 20).build());
    }

    private void settingButton(String label, int x, int y, int width, Runnable action) {
        Button button = addRenderableWidget(Button.builder(Component.literal(label), ignored -> action.run())
                .bounds(x, y, width, 28).build());
        settingsButtons.put(button, label.toLowerCase(Locale.ROOT));
    }

    private void applyFilter(String raw) {
        query = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (tab == Tab.FREEPLAY) {
            List<FnfPayloads.SongInfo> source = groupedFreeplay && matchingMods.isEmpty() ? List.of()
                    : groupedFreeplay && !mods.isEmpty()
                    ? mods.get(Mth.clamp(selectedMod, 0, mods.size() - 1)).songs() : freeplaySongs();
            visible = query.isEmpty() ? source : source.stream().filter(song ->
                    song.name().toLowerCase(Locale.ROOT).contains(query)
                            || song.id().toLowerCase(Locale.ROOT).contains(query)).toList();
            if (groupedFreeplay) {
                Set<String> hidden = new HashSet<>();
                for (WeekDefinition week : WeekLibrary.all()) if (week.hideFreeplay()) {
                    for (WeekDefinition.Song song : week.songs()) hidden.add(song.id().toLowerCase(Locale.ROOT));
                }
                visible = visible.stream().filter(song -> !hidden.contains(song.id().toLowerCase(Locale.ROOT))).toList();
                sections = buildSections(visible);
            } else sections = List.of();
            if (layoutButton != null) layoutButton.setTooltip(net.minecraft.client.gui.components.Tooltip.create(
                    Component.literal("Freeplay layout: " + (groupedFreeplay ? "by mod and week" : "all songs"))));
        } else if (tab == Tab.STORY) {
            String selectedId = selectedWeek() == null ? "" : selectedWeek().id();
            visibleWeeks = query.isEmpty() ? allWeeks : allWeeks.stream().filter(week ->
                    week.displayName().toLowerCase(Locale.ROOT).contains(query)
                            || week.id().toLowerCase(Locale.ROOT).contains(query)
                            || week.songs().stream().anyMatch(song -> song.id().toLowerCase(Locale.ROOT).contains(query))).toList();
            selectedWeek = indexOfWeek(visibleWeeks, selectedId);
            difficultyIndex = 0;
            resetStorySongScroll();
            if (storyDifficulty != null) {
                storyDifficulty.active = !visibleWeeks.isEmpty();
                storyDifficulty.setMessage(storyDifficultyLabel());
            }
            if (storyPlaybackModeButton != null) storyPlaybackModeButton.active = !visibleWeeks.isEmpty();
            if (storyPlay != null) storyPlay.active = !visibleWeeks.isEmpty();
        } else if (tab == Tab.SETTINGS) {
            for (var entry : settingsButtons.entrySet()) {
                entry.getKey().visible = query.isEmpty() || entry.getValue().contains(query);
            }
            if (creditsButton != null) {
                creditsButton.visible = query.isEmpty() || "credits".contains(query);
            }
        }
        scrollPx = scrollTargetPx = scrollFromPx = 0;
        scrollTweenActive = false;
        showStoryDifficultyList = false;
    }

    private List<Integer> matchingMods(String search) {
        List<Integer> indices = new ArrayList<>();
        for (int i = 0; i < mods.size(); i++) {
            FreeplayCatalog.Mod mod = mods.get(i);
            if (search.isEmpty() || mod.name().toLowerCase(Locale.ROOT).contains(search)
                    || mod.root().getFileName().toString().toLowerCase(Locale.ROOT).contains(search)) indices.add(i);
        }
        return List.copyOf(indices);
    }

    private void applyModFilter(String raw) {
        modQuery = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        matchingMods = matchingMods(modQuery);
        if (!matchingMods.isEmpty() && !matchingMods.contains(selectedMod)) selectedMod = matchingMods.get(0);
        modScrollPx = modScrollTargetPx = modScrollFromPx = 0;
        modScrollTweenActive = false;
        applyFilter(searchBox.getValue());
    }

    private List<FnfPayloads.SongInfo> freeplaySongs() {
        java.util.Set<String> hidden = new java.util.HashSet<>();
        for (WeekDefinition week : WeekLibrary.all()) if (week.hideFreeplay()) {
            for (WeekDefinition.Song song : week.songs()) {
                hidden.add(song.id().trim().toLowerCase(Locale.ROOT));
            }
        }
        return songs.stream().filter(song -> !hidden.contains(song.id().trim().toLowerCase(Locale.ROOT))
                && !hidden.contains(song.name().trim().toLowerCase(Locale.ROOT))).toList();
    }

    private List<Section> buildSections(List<FnfPayloads.SongInfo> source) {
        List<Section> result = new ArrayList<>();
        Set<String> used = new HashSet<>();
        for (WeekDefinition week : WeekLibrary.all()) {
            if (week.hideFreeplay()) continue;
            List<FnfPayloads.SongInfo> members = new ArrayList<>();
            for (WeekDefinition.Song weekSong : week.songs()) {
                for (FnfPayloads.SongInfo song : source) {
                    String key = song.id().toLowerCase(Locale.ROOT);
                    if (!used.contains(key) && (song.id().equalsIgnoreCase(weekSong.id())
                            || song.name().equalsIgnoreCase(weekSong.id()))) {
                        members.add(song);
                        used.add(key);
                        break;
                    }
                }
            }
            if (!members.isEmpty()) result.add(new Section(week.displayName(), List.copyOf(members)));
        }
        List<FnfPayloads.SongInfo> unassigned = source.stream()
                .filter(song -> !used.contains(song.id().toLowerCase(Locale.ROOT))).toList();
        if (!unassigned.isEmpty()) result.add(new Section("Other songs", unassigned));
        return List.copyOf(result);
    }

    private int groupedHeight() {
        int height = 0;
        for (Section section : sections) {
            height += 20 + (section.songs().size() + columns() - 1) / columns() * (BOX_H + GAP) + 5;
        }
        return height;
    }

    private int modRowY() { return 78; }
    private int modRowHeight() { return 24; }
    private int modBoxWidth() { return Math.max(90, (int) (contentWidth() * 0.78)); }
    private int modBoxX() { return contentX() + (contentWidth() - modBoxWidth()) / 2; }
    private int modViewportX() { return modBoxX() + 3; }
    private int modViewportWidth() { return modBoxWidth() - 6; }
    private int modWidth(FreeplayCatalog.Mod mod) { return Mth.clamp(font.width(mod.name()) + 20, 76, 156); }
    private int modsContentWidth() {
        int total = 0;
        for (int index : matchingMods) total += modWidth(mods.get(index)) + 4;
        return Math.max(0, total - 4);
    }
    private double modsMaxScroll() { return Math.max(0, modsContentWidth() - modViewportWidth()); }

    private int contentX() { return 20; }
    private int contentWidth() { return Math.max(120, width - 40); }
    private int listY() { return tab == Tab.FREEPLAY && groupedFreeplay ? 108 : 66; }
    private int listHeight() { return Math.max(40, height - listY() - 18); }
    private int columns() { return Math.max(1, (contentWidth() + GAP) / (BOX_W + GAP)); }
    private int rows() { return (visible.size() + columns() - 1) / Math.max(1, columns()); }
    private int contentHeight() { return tab == Tab.STORY ? visibleWeeks.size() * WEEK_ROW_H
            : tab == Tab.FREEPLAY ? groupedFreeplay ? groupedHeight() : rows() * (BOX_H + GAP) : 0; }
    private double maxScroll() { return Math.max(0, contentHeight() - listHeight()); }
    private boolean hasScrollbar() { return contentHeight() > listHeight(); }
    private int gridLeft() {
        int used = columns() * BOX_W + (columns() - 1) * GAP;
        return contentX() + Math.max(0, (contentWidth() - used) / 2);
    }
    private int storyListWidth() { return Math.max(118, Math.min(210, contentWidth() / 3)); }
    private int storyRightX() { return contentX() + storyListWidth() + 10; }

    @Override public void render(GuiGraphics gui, int mouseX, int mouseY, float partialTick) {
        // Screen.render() applies Minecraft's menu blur. Calling it after the
        // song cards made the entire selector part of the blur input, so text,
        // icons and cards appeared behind a tinted/soft layer. Apply the world
        // background first; renderBackground() below suppresses the later pass.
        super.renderBackground(gui, mouseX, mouseY, partialTick);
        updateScrollTween();
        updateModScrollTween();
        updateStorySongScrollTween();
        BlockifiedScreenStyle.translucentPanel(gui, contentX() - 6, 15, contentWidth() + 12, height - 27);
        // GUI fills and textures use different render buffers. Commit the shell
        // before any menu content so a delayed panel batch cannot cover songs.
        gui.flush();
        switch (tab) {
            case FREEPLAY -> renderFreeplay(gui, mouseX, mouseY);
            case STORY -> renderStory(gui, mouseX, mouseY);
            case SETTINGS -> renderSettings(gui);
        }
        // Widgets render last so the Story preview panel cannot cover its difficulty/play controls.
        super.render(gui, mouseX, mouseY, partialTick);
        if (layoutButton != null) {
            ResourceLocation icon = groupedFreeplay ? ALL_ICON : GROUPED_ICON;
            gui.blit(icon, layoutButton.getX() + 2, layoutButton.getY() + 2, 16, 16,
                    0, 0, 42, 42, 42, 42);
        }
        if (tab == Tab.STORY && showStoryDifficultyList) {
            renderStoryDifficultyList(gui, mouseX, mouseY);
        }
        gui.drawCenteredString(font, "Song Menu", width / 2, 5, 0xFFFFFFFF);
    }

    @Override
    public void renderBackground(GuiGraphics gui, int mouseX, int mouseY, float partialTick) {
        // render() deliberately applies the blur before any Song Menu content.
    }

    private void renderFreeplay(GuiGraphics gui, int mouseX, int mouseY) {
        if (groupedFreeplay) {
            renderMods(gui, mouseX, mouseY);
            renderGroupedFreeplay(gui, mouseX, mouseY);
            return;
        }
        int x = contentX(), y = listY(), w = contentWidth(), h = listHeight();
        if (visible.isEmpty()) {
            gui.drawCenteredString(font, query.isEmpty() ? "No songs found!" : "No songs match \"" + query + "\"",
                    x + w / 2, y + 30, 0xFFFF6666);
            return;
        }
        clampScroll();
        int cols = columns(), left = gridLeft();
        gui.enableScissor(x, y, x + w, y + h);
        // The machine menu is a pure 2D overlay. Card surfaces had already
        // written depth at the same GUI plane as their icons/text, allowing the
        // depth test to reject the later content even after buffers were flushed.
        gui.flush();
        RenderSystem.disableDepthTest();
        // Submit every opaque/translucent card surface first, then commit it
        // before icons and names. This gives deterministic ordering regardless
        // of how Minecraft groups GUI render types internally.
        for (int i = 0; i < visible.size(); i++) {
            int bx = left + i % cols * (BOX_W + GAP), by = (int) (y + i / cols * (BOX_H + GAP) - scrollPx);
            if (by + BOX_H >= y && by <= y + h) drawSongBoxBackground(gui, bx, by, mouseX, mouseY);
        }
        gui.flush();
        for (int i = 0; i < visible.size(); i++) {
            int bx = left + i % cols * (BOX_W + GAP), by = (int) (y + i / cols * (BOX_H + GAP) - scrollPx);
            if (by + BOX_H >= y && by <= y + h) drawSongBoxContent(gui, visible.get(i), bx, by);
        }
        // Flush while the list scissor is still active; otherwise deferred icon
        // quads could escape the selector viewport after it is disabled.
        gui.flush();
        RenderSystem.enableDepthTest();
        gui.disableScissor();
        drawScrollbar(gui, x + w - 5, y, h);
    }

    private void renderMods(GuiGraphics gui, int mouseX, int mouseY) {
        int x = modViewportX(), w = modViewportWidth(), y = modRowY();
        modScrollPx = Mth.clamp(modScrollPx, 0, modsMaxScroll());
        BlockifiedScreenStyle.inner(gui, modBoxX(), y - 3, modBoxWidth(), modRowHeight() + 2);
        gui.flush();
        gui.enableScissor(x, y, x + w, y + 20);
        int left = x - (int) modScrollPx;
        for (int index : matchingMods) {
            FreeplayCatalog.Mod mod = mods.get(index);
            int buttonW = modWidth(mod);
            boolean hover = mouseX >= left && mouseX < left + buttonW && mouseY >= y && mouseY < y + 20;
            gui.fill(left, y, left + buttonW, y + 20,
                    index == selectedMod ? BlockifiedScreenStyle.ACCENT_MEDIUM
                            : hover ? BlockifiedScreenStyle.ACCENT_FAINT : 0x99330C47);
            gui.renderOutline(left, y, buttonW, 20,
                    index == selectedMod ? BlockifiedScreenStyle.ACCENT : 0x66542B68);
            gui.drawCenteredString(font, trim(mod.name(), buttonW - 10), left + buttonW / 2, y + 6,
                    index == selectedMod ? 0xFFFFFFFF : BlockifiedScreenStyle.TEXT_SECTION);
            left += buttonW + 4;
        }
        gui.flush();
        gui.disableScissor();
        int trackY = 69;
        gui.fill(x, trackY, x + w, trackY + 4, 0x66000000);
        if (modsMaxScroll() > 0) {
            int thumbW = Math.max(18, (int) ((double) w * w / modsContentWidth()));
            int thumbX = x + (int) ((w - thumbW) * modScrollPx / modsMaxScroll());
            gui.fill(thumbX, trackY, thumbX + thumbW, trackY + 4, BlockifiedScreenStyle.ACCENT);
        } else gui.fill(x, trackY, x + w, trackY + 4, BlockifiedScreenStyle.ACCENT_FAINT);
    }

    private void renderGroupedFreeplay(GuiGraphics gui, int mouseX, int mouseY) {
        int x = contentX(), y = listY(), w = contentWidth(), h = listHeight();
        if (visible.isEmpty()) {
            String empty = matchingMods.isEmpty() && !modQuery.isEmpty() ? "No matching mods"
                    : query.isEmpty() ? "No songs in this mod" : "No matching songs";
            gui.drawCenteredString(font, empty,
                    x + w / 2, y + 28, BlockifiedScreenStyle.TEXT_MUTED);
            return;
        }
        clampScroll();
        List<Card> cards = new ArrayList<>();
        int sectionY = y - (int) scrollPx;
        gui.enableScissor(x, y, x + w, y + h);
        gui.flush();
        RenderSystem.disableDepthTest();
        for (Section section : sections) {
            if (sectionY + 17 >= y && sectionY < y + h) {
                gui.fill(x + 3, sectionY, x + w - 12, sectionY + 16, 0x99251431);
                gui.fill(x + 3, sectionY, x + 6, sectionY + 16, BlockifiedScreenStyle.ACCENT);
                gui.renderOutline(x + 3, sectionY, w - 15, 16, 0x66542B68);
                gui.drawString(font, trim(section.title(), w - 30), x + 12, sectionY + 4,
                        BlockifiedScreenStyle.TEXT_SECTION, false);
            }
            int cardY = sectionY + 20;
            for (int i = 0; i < section.songs().size(); i++) {
                int bx = gridLeft() + i % columns() * (BOX_W + GAP);
                int by = cardY + i / columns() * (BOX_H + GAP);
                if (by + BOX_H >= y && by <= y + h) {
                    drawSongBoxBackground(gui, bx, by, mouseX, mouseY);
                    cards.add(new Card(section.songs().get(i), bx, by));
                }
            }
            sectionY += 20 + (section.songs().size() + columns() - 1) / columns() * (BOX_H + GAP) + 5;
        }
        gui.flush();
        for (Card card : cards) drawSongBoxContent(gui, card.song(), card.x(), card.y());
        gui.flush();
        RenderSystem.enableDepthTest();
        gui.disableScissor();
        drawScrollbar(gui, x + w - 5, y, h);
    }

    private void renderStory(GuiGraphics gui, int mouseX, int mouseY) {
        int x = contentX(), y = listY(), h = listHeight(), listW = storyListWidth();
        BlockifiedScreenStyle.inner(gui, x, y, listW, h);
        int rightX = storyRightX(), rightW = x + contentWidth() - rightX;
        BlockifiedScreenStyle.inner(gui, rightX, y, rightW, h);
        if (visibleWeeks.isEmpty()) {
            gui.drawCenteredString(font, "No playable weeks found", width / 2, y + 28, 0xFFFF7777);
            gui.drawCenteredString(font, "Add a Psych week or V-Slice level with available songs.", width / 2, y + 44,
                    BlockifiedScreenStyle.TEXT_MUTED);
            return;
        }
        clampScroll();
        gui.enableScissor(x, y, x + listW, y + h);
        for (int i = 0; i < visibleWeeks.size(); i++) {
            int rowY = (int) (y + i * WEEK_ROW_H - scrollPx);
            if (rowY + WEEK_ROW_H < y || rowY > y + h) continue;
            boolean selected = i == selectedWeek;
            boolean hover = mouseX >= x && mouseX < x + listW && mouseY >= rowY
                    && mouseY < rowY + WEEK_ROW_H && mouseY >= y && mouseY < y + h;
            gui.fill(x + 3, rowY + 2, x + listW - 3, rowY + WEEK_ROW_H - 2,
                    selected ? BlockifiedScreenStyle.ACCENT_STRONG
                            : hover ? BlockifiedScreenStyle.ACCENT_SOFT : 0x4420162A);
            if (selected) gui.fill(x + 3, rowY + 2, x + 6, rowY + WEEK_ROW_H - 2, BlockifiedScreenStyle.ACCENT);
            gui.drawString(font, trim(visibleWeeks.get(i).displayName(), listW - 18), x + 10, rowY + 10,
                    selected ? 0xFFFFFFFF : 0xFFCEC6D0, false);
        }
        gui.disableScissor();
        drawScrollbar(gui, x + listW - 5, y, h);
        renderSelectedWeek(gui, selectedWeek(), rightX, y, rightW, h);
    }

    private void renderSelectedWeek(GuiGraphics gui, WeekDefinition week, int x, int y, int w, int h) {
        if (week == null) return;
        boolean compact = storyCompactLayout();
        if (!compact) {
            gui.drawCenteredString(font, trim(week.displayName(), w - 16), x + w / 2, y + 8,
                    BlockifiedScreenStyle.ACCENT);
        }
        int imageTop = storyImageTop();
        int imageHeight = storyImageHeight();
        if (week.imageFile() != null) IconLibrary.drawImageFile(gui, week.imageFile().toString(), x + w / 2f,
                imageTop + imageHeight / 2f, Math.max(40, w - 28), imageHeight);
        else {
            gui.fill(x + 12, imageTop, x + w - 12, imageTop + imageHeight, 0x44251531);
            gui.drawCenteredString(font, "No storymenu image", x + w / 2,
                    imageTop + Math.max(0, imageHeight / 2 - 4), BlockifiedScreenStyle.TEXT_MUTED);
        }
        int songY = storySongsTop(), total = 0;
        String difficulty = storyDifficultyValue();
        int scoreY = storyScoreY();
        int songsBottom = storySongsBottom();
        int songsViewportHeight = Math.max(0, songsBottom - songY);
        int songsContentHeight = week.songs().size() * 16;
        double songsMaxScroll = Math.max(0, songsContentHeight - songsViewportHeight);
        storySongScrollPx = Mth.clamp(storySongScrollPx, 0, songsMaxScroll);
        storySongScrollTargetPx = Mth.clamp(storySongScrollTargetPx, 0, songsMaxScroll);
        if (songsViewportHeight > 0) gui.enableScissor(x, songY, x + w, songsBottom);
        for (int i = 0; i < week.songs().size(); i++) {
            WeekDefinition.Song weekSong = week.songs().get(i);
            FnfPayloads.SongInfo song = findSong(weekSong.id());
            int score = song == null ? 0 : score(song, difficulty);
            total += score;
            int rowY = (int) (songY + i * 16 - storySongScrollPx);
            if (rowY + 16 < songY || rowY > songsBottom) continue;
            gui.fill(x + 12, rowY + 2, x + 15, rowY + 12, 0xFF000000 | weekSong.color());
            gui.drawString(font, (i + 1) + ". " + trim(weekSong.id(), Math.max(24, w - 88)), x + 20,
                    rowY + 3, song == null ? 0xFF777777 : 0xFFFFFFFF, false);
            String value = String.valueOf(score);
            gui.drawString(font, value, x + w - 12 - font.width(value), rowY + 3,
                    BlockifiedScreenStyle.ACCENT_LIGHT, false);
        }
        if (songsViewportHeight > 0) gui.disableScissor();
        if (songsMaxScroll > 0 && songsViewportHeight > 0) {
            int trackX = x + w - 5;
            int thumbH = Math.max(12, (int) ((double) songsViewportHeight * songsViewportHeight / songsContentHeight));
            int thumbY = songY + (int) ((songsViewportHeight - thumbH) * (storySongScrollPx / songsMaxScroll));
            gui.fill(trackX, songY, trackX + 4, songsBottom, 0x55000000);
            gui.fill(trackX, thumbY, trackX + 4, thumbY + thumbH, BlockifiedScreenStyle.ACCENT);
        }
        String scoreText = "Week Score: " + total;
        if (compact) {
            gui.drawString(font, trim(scoreText, Math.max(30, w / 2 - 10)), x + 8, scoreY,
                    BlockifiedScreenStyle.ACCENT_LIGHT, false);
            String name = trim(week.displayName(), Math.max(30, w / 2 - 10));
            gui.drawString(font, name, x + w - 8 - font.width(name), y + 8,
                    BlockifiedScreenStyle.ACCENT, false);
        } else {
            gui.drawCenteredString(font, scoreText, x + w / 2, scoreY,
                    BlockifiedScreenStyle.ACCENT_LIGHT);
        }
    }

    private void renderSettings(GuiGraphics gui) {
        gui.drawCenteredString(font, "Choose a settings section", width / 2, listY() + 5,
                BlockifiedScreenStyle.TEXT_SECTION);
        gui.drawCenteredString(font, "Note Settings closes preview audio after a chart is selected.", width / 2,
                listY() + 128, BlockifiedScreenStyle.TEXT_MUTED);
    }

    private void drawSongBoxBackground(GuiGraphics gui, int x, int y, int mouseX, int mouseY) {
        boolean hover = mouseX >= x && mouseX < x + BOX_W && mouseY >= y && mouseY < y + BOX_H
                && mouseY >= listY() && mouseY < listY() + listHeight();
        gui.fill(x, y, x + BOX_W, y + BOX_H, 0x5A1B1025);
        gui.fill(x + 2, y + 2, x + BOX_W - 2, y + BOX_H - 25, 0x33330C47);
        if (hover) gui.fill(x + 1, y + 1, x + BOX_W - 1, y + BOX_H - 1,
                0x1AB82BFF);
        int nameTop = y + BOX_H - 22;
        gui.fill(x + 2, nameTop - 1, x + BOX_W - 2, y + BOX_H - 2, 0x60330C47);
        gui.fill(x + 2, nameTop - 1, x + BOX_W - 2, nameTop + 1,
                hover ? 0x80B82BFF : 0x33B82BFF);
        gui.renderOutline(x, y, BOX_W, BOX_H,
                hover ? 0x80D681FF : 0x44542B68);
    }

    private void drawSongBoxContent(GuiGraphics gui, FnfPayloads.SongInfo song, int x, int y) {
        int iconSize = (int) (BOX_W * 0.68f);
        float cx = x + BOX_W / 2f, cy = y + 8 + iconSize / 2f;
        if (IconLibrary.hasFile(song.opponentIconPath())) IconLibrary.drawFile(gui, song.opponentIconPath(), 0, cx, cy, iconSize, false);
        else if (IconLibrary.has(song.opponentIcon())) IconLibrary.draw(gui, song.opponentIcon(), 0, cx, cy, iconSize);
        int nameTop = y + BOX_H - 22;
        gui.drawCenteredString(font, trim(song.name(), BOX_W - 10), x + BOX_W / 2, nameTop + 6, 0xFFFFFFFF);
    }

    private void playSelectedWeek() {
        WeekDefinition week = selectedWeek();
        if (week != null) ClientStorySession.start(pos, week, storyDifficultyValue(), storyPlaybackMode, songs);
    }
    private WeekDefinition selectedWeek() {
        return visibleWeeks.isEmpty() ? null : visibleWeeks.get(Mth.clamp(selectedWeek, 0, visibleWeeks.size() - 1));
    }
    private List<String> storyDifficulties(WeekDefinition week) {
        return week == null || week.difficulties().isEmpty() ? List.of("normal") : week.difficulties();
    }
    private String storyDifficultyValue() {
        List<String> values = storyDifficulties(selectedWeek());
        return values.get(Mth.clamp(difficultyIndex, 0, values.size() - 1));
    }
    private Component storyDifficultyLabel() { return Component.literal("Difficulty: " + storyDifficultyValue()); }
    private Component storyPlaybackModeLabel() { return Component.literal("Look: " + storyPlaybackMode.displayName()); }

    private int storyDifficultyListHeight() {
        return Math.max(1, storyDifficulties(selectedWeek()).size()) * DIFFICULTY_ROW_H + 2;
    }
    private int storyDifficultyListY() {
        return Math.max(listY(), storyDifficulty.getY() - storyDifficultyListHeight() - 3);
    }
    private void renderStoryDifficultyList(GuiGraphics gui, int mouseX, int mouseY) {
        if (storyDifficulty == null) return;
        List<String> values = storyDifficulties(selectedWeek());
        int x = storyDifficulty.getX(), y = storyDifficultyListY();
        int w = storyDifficulty.getWidth(), h = storyDifficultyListHeight();
        gui.pose().pushPose();
        gui.pose().translate(0, 0, 200);
        gui.fill(x - 1, y - 1, x + w + 1, y + h + 1, 0xFF000000);
        gui.fill(x, y, x + w, y + h, 0xF0181820);
        for (int i = 0; i < values.size(); i++) {
            int rowY = y + 1 + i * DIFFICULTY_ROW_H;
            boolean hover = mouseX >= x && mouseX < x + w
                    && mouseY >= rowY && mouseY < rowY + DIFFICULTY_ROW_H;
            if (i == difficultyIndex) gui.fill(x, rowY, x + w, rowY + DIFFICULTY_ROW_H,
                    BlockifiedScreenStyle.ACCENT_MEDIUM);
            else if (hover) gui.fill(x, rowY, x + w, rowY + DIFFICULTY_ROW_H, 0x33FFFFFF);
            gui.drawString(font, trim(values.get(i), w - 10), x + 5, rowY + 3,
                    i == difficultyIndex ? 0xFFFFFFFF : 0xFFCCCCCC, false);
        }
        gui.pose().popPose();
    }
    private int score(FnfPayloads.SongInfo song, String difficulty) {
        String chosen = song.difficulties().stream().filter(value -> value.equalsIgnoreCase(difficulty)).findFirst()
                .orElse(song.difficulties().isEmpty() ? "normal" : song.difficulties().get(0));
        ScoreStore.Record value = ScoreStore.get(song.id(), chosen, ClientOptions.get().playAs % 3);
        return value == null ? 0 : value.score;
    }
    private FnfPayloads.SongInfo findSong(String id) {
        return songs.stream().filter(song -> song.id().equalsIgnoreCase(id) || song.name().equalsIgnoreCase(id))
                .findFirst().orElse(null);
    }
    private static int indexOfWeek(List<WeekDefinition> values, String id) {
        for (int i = 0; i < values.size(); i++) if (values.get(i).id().equalsIgnoreCase(id)) return i;
        return 0;
    }
    private String trim(String text, int maxWidth) {
        if (text == null || font.width(text) <= maxWidth) return text == null ? "" : text;
        String value = text;
        while (value.length() > 1 && font.width(value + "…") > maxWidth) value = value.substring(0, value.length() - 1);
        return value + "…";
    }

    @Override public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && tab == Tab.FREEPLAY && groupedFreeplay) {
            if (modsMaxScroll() > 0 && mouseX >= modViewportX() && mouseX < modViewportX() + modViewportWidth()
                    && mouseY >= 68 && mouseY <= 75) {
                draggingModThumb = true;
                dragModsTo(mouseX);
                return true;
            }
            if (mouseX >= modViewportX() && mouseX < modViewportX() + modViewportWidth()
                    && mouseY >= modRowY() && mouseY < modRowY() + 20) {
                int left = modViewportX() - (int) modScrollPx;
                for (int index : matchingMods) {
                    int buttonW = modWidth(mods.get(index));
                    if (mouseX >= left && mouseX < left + buttonW) {
                        selectedMod = index;
                        rememberedModRoot = mods.get(index).root();
                        applyFilter(searchBox.getValue());
                        return true;
                    }
                    left += buttonW + 4;
                }
            }
        }
        if (tab == Tab.STORY && showStoryDifficultyList && storyDifficulty != null) {
            int x = storyDifficulty.getX(), y = storyDifficultyListY();
            int w = storyDifficulty.getWidth(), h = storyDifficultyListHeight();
            if (mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h) {
                List<String> values = storyDifficulties(selectedWeek());
                int index = (int) ((mouseY - y - 1) / DIFFICULTY_ROW_H);
                if (index >= 0 && index < values.size()) {
                    difficultyIndex = index;
                    storyDifficulty.setMessage(storyDifficultyLabel());
                }
                showStoryDifficultyList = false;
                return true;
            }
            showStoryDifficultyList = false;
            if (storyDifficulty.isMouseOver(mouseX, mouseY)) return true;
        }
        if (button == 0 && tab == Tab.STORY && storySongsHaveScrollbar()) {
            int sx = storyRightX() + (contentX() + contentWidth() - storyRightX()) - 5;
            int top = storySongsTop(), bottom = storySongsBottom();
            if (mouseX >= sx - 1 && mouseX <= sx + 5 && mouseY >= top && mouseY <= bottom) {
                draggingStorySongThumb = true;
                dragStorySongsTo(mouseY);
                return true;
            }
        }
        if (button == 0 && hasScrollbar()) {
            int sx = scrollbarX();
            if (mouseX >= sx - 1 && mouseX <= sx + 5 && mouseY >= listY() && mouseY <= listY() + listHeight()) {
                draggingThumb = true; dragTo(mouseY); return true;
            }
        }
        if (button == 0 && tab == Tab.FREEPLAY) {
            int index = songAt(mouseX, mouseY);
            if (index >= 0) { minecraft.setScreen(new SongDetailScreen(this, pos, visible.get(index))); return true; }
        } else if (button == 0 && tab == Tab.STORY && mouseX >= contentX()
                && mouseX < contentX() + storyListWidth() && mouseY >= listY() && mouseY < listY() + listHeight()) {
            int index = (int) ((mouseY - listY() + scrollPx) / WEEK_ROW_H);
            if (index >= 0 && index < visibleWeeks.size()) {
                selectedWeek = index; difficultyIndex = 0;
                showStoryDifficultyList = false;
                resetStorySongScroll();
                if (storyDifficulty != null) storyDifficulty.setMessage(storyDifficultyLabel());
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }
    private int songAt(double mouseX, double mouseY) {
        if (mouseX < contentX() || mouseX >= contentX() + contentWidth()
                || mouseY < listY() || mouseY >= listY() + listHeight()) return -1;
        if (groupedFreeplay) {
            int sectionY = listY() - (int) scrollPx;
            for (Section section : sections) {
                int cardY = sectionY + 20;
                for (int i = 0; i < section.songs().size(); i++) {
                    int bx = gridLeft() + i % columns() * (BOX_W + GAP);
                    int by = cardY + i / columns() * (BOX_H + GAP);
                    if (mouseX >= bx && mouseX < bx + BOX_W && mouseY >= by && mouseY < by + BOX_H)
                        return visible.indexOf(section.songs().get(i));
                }
                sectionY += 20 + (section.songs().size() + columns() - 1) / columns() * (BOX_H + GAP) + 5;
            }
            return -1;
        }
        int relX = (int) mouseX - gridLeft(), relY = (int) (mouseY - listY() + scrollPx);
        if (relX < 0 || relY < 0) return -1;
        int col = relX / (BOX_W + GAP), row = relY / (BOX_H + GAP);
        if (col >= columns() || relX % (BOX_W + GAP) >= BOX_W || relY % (BOX_H + GAP) >= BOX_H) return -1;
        int index = row * columns() + col;
        return index < visible.size() ? index : -1;
    }

    private int scrollbarX() { return tab == Tab.STORY ? contentX() + storyListWidth() - 5
            : contentX() + contentWidth() - 5; }
    private void drawScrollbar(GuiGraphics gui, int x, int y, int height) {
        if (!hasScrollbar()) return;
        gui.fill(x, y, x + 4, y + height, 0x55000000);
        int thumbH = Math.max(16, (int) ((double) height * height / contentHeight()));
        int thumbY = y + (int) ((height - thumbH) * (scrollPx / maxScroll()));
        gui.fill(x, thumbY, x + 4, thumbY + thumbH, BlockifiedScreenStyle.ACCENT);
    }
    private void clampScroll() {
        scrollPx = Mth.clamp(scrollPx, 0, maxScroll());
        scrollTargetPx = Mth.clamp(scrollTargetPx, 0, maxScroll());
    }
    private void dragTo(double mouseY) {
        int h = listHeight(), thumbH = Math.max(16, (int) ((double) h * h / Math.max(1, contentHeight())));
        double fraction = (mouseY - listY() - thumbH / 2.0) / Math.max(1, h - thumbH);
        scrollPx = Mth.clamp(fraction, 0, 1) * maxScroll();
        scrollTargetPx = scrollFromPx = scrollPx; scrollTweenActive = false;
    }
    private void dragModsTo(double mouseX) {
        if (modsMaxScroll() <= 0) return;
        int w = modViewportWidth();
        int thumbW = Math.max(18, (int) ((double) w * w / modsContentWidth()));
        double fraction = (mouseX - modViewportX() - thumbW / 2.0) / Math.max(1, w - thumbW);
        modScrollPx = Mth.clamp(fraction, 0, 1) * modsMaxScroll();
        modScrollTargetPx = modScrollFromPx = modScrollPx;
        modScrollTweenActive = false;
    }
    private void updateModScrollTween() {
        if (!modScrollTweenActive) return;
        double progress = (System.nanoTime() - modScrollTweenStartNano) / (double) SCROLL_TWEEN_NANOS;
        if (progress >= 1) {
            modScrollPx = modScrollTargetPx;
            modScrollTweenActive = false;
        } else modScrollPx = modScrollFromPx + (modScrollTargetPx - modScrollFromPx)
                * Easing.apply("expoOut", progress);
    }
    private void scrollModsTo(double target) {
        updateModScrollTween();
        modScrollFromPx = modScrollPx;
        modScrollTargetPx = Mth.clamp(target, 0, modsMaxScroll());
        modScrollTweenStartNano = System.nanoTime();
        modScrollTweenActive = Math.abs(modScrollTargetPx - modScrollFromPx) > 0.01;
        if (!modScrollTweenActive) modScrollPx = modScrollTargetPx;
    }
    private void updateScrollTween() {
        if (!scrollTweenActive) return;
        double progress = (System.nanoTime() - scrollTweenStartNano) / (double) SCROLL_TWEEN_NANOS;
        if (progress >= 1) { scrollPx = scrollTargetPx; scrollTweenActive = false; return; }
        scrollPx = scrollFromPx + (scrollTargetPx - scrollFromPx) * Easing.apply("expoOut", progress);
    }
    private void scrollTo(double target) {
        updateScrollTween();
        scrollFromPx = scrollPx; scrollTargetPx = Mth.clamp(target, 0, maxScroll());
        scrollTweenStartNano = System.nanoTime();
        scrollTweenActive = Math.abs(scrollTargetPx - scrollFromPx) > 0.01;
        if (!scrollTweenActive) scrollPx = scrollTargetPx;
    }

    @Override public boolean mouseDragged(double x, double y, int button, double dx, double dy) {
        if (draggingModThumb) { dragModsTo(x); return true; }
        if (draggingStorySongThumb) { dragStorySongsTo(y); return true; }
        if (draggingThumb) { dragTo(y); return true; }
        return super.mouseDragged(x, y, button, dx, dy);
    }
    @Override public boolean mouseReleased(double x, double y, int button) {
        draggingModThumb = false;
        draggingThumb = false;
        draggingStorySongThumb = false;
        return super.mouseReleased(x, y, button);
    }
    @Override public boolean mouseScrolled(double x, double y, double sx, double sy) {
        if (tab == Tab.FREEPLAY && groupedFreeplay && y >= 66 && y < listY()) {
            scrollModsTo(modScrollTargetPx - (sx != 0 ? sx : sy) * 60);
        } else if (tab == Tab.STORY && x >= storyRightX() && x < contentX() + contentWidth()
                && y >= storySongsTop() && y < storySongsBottom()) {
            scrollStorySongsTo(storySongScrollTargetPx - sy * 32.0);
        } else if (tab != Tab.SETTINGS) {
            scrollTo(scrollTargetPx - sy * (tab == Tab.STORY ? WEEK_ROW_H * 2.0 : BOX_H / 2.0));
        }
        return true;
    }

    private boolean storyCompactLayout() { return listHeight() < 230; }
    private int storyImageTop() { return listY() + (storyCompactLayout() ? 20 : 22); }
    private int storyImageHeight() {
        if (!storyCompactLayout()) return 64;
        return Mth.clamp((listHeight() - 104) / 2, 28, 52);
    }
    private int storySongsTop() { return storyImageTop() + storyImageHeight() + 8; }
    private int storyScoreY() {
        return storyCompactLayout() ? listY() + 8 : listY() + listHeight() - 58;
    }
    private int storySongsBottom() {
        int controlsTop = listY() + listHeight() - 46;
        int bottom = storyCompactLayout() ? controlsTop - 4 : storyScoreY() - 4;
        return Math.max(storySongsTop() + 12, bottom);
    }
    private int storySongsViewportHeight() { return Math.max(0, storySongsBottom() - storySongsTop()); }
    private int storySongsContentHeight() {
        WeekDefinition week = selectedWeek();
        return week == null ? 0 : week.songs().size() * 16;
    }
    private double storySongsMaxScroll() {
        return Math.max(0, storySongsContentHeight() - storySongsViewportHeight());
    }
    private boolean storySongsHaveScrollbar() { return storySongsMaxScroll() > 0; }
    private void resetStorySongScroll() {
        storySongScrollPx = storySongScrollTargetPx = storySongScrollFromPx = 0;
        storySongScrollTweenActive = false;
        draggingStorySongThumb = false;
    }
    private void scrollStorySongsTo(double target) {
        updateStorySongScrollTween();
        storySongScrollFromPx = storySongScrollPx;
        storySongScrollTargetPx = Mth.clamp(target, 0, storySongsMaxScroll());
        storySongScrollTweenStartNano = System.nanoTime();
        storySongScrollTweenActive = Math.abs(storySongScrollTargetPx - storySongScrollFromPx) > 0.01;
        if (!storySongScrollTweenActive) storySongScrollPx = storySongScrollTargetPx;
    }
    private void updateStorySongScrollTween() {
        if (!storySongScrollTweenActive) return;
        double progress = (System.nanoTime() - storySongScrollTweenStartNano) / (double) SCROLL_TWEEN_NANOS;
        if (progress >= 1) {
            storySongScrollPx = storySongScrollTargetPx;
            storySongScrollTweenActive = false;
        } else {
            storySongScrollPx = storySongScrollFromPx
                    + (storySongScrollTargetPx - storySongScrollFromPx) * Easing.apply("expoOut", progress);
        }
    }
    private void dragStorySongsTo(double mouseY) {
        int top = storySongsTop(), h = storySongsViewportHeight(), content = storySongsContentHeight();
        int thumbH = Math.max(12, (int) ((double) h * h / Math.max(1, content)));
        double fraction = (mouseY - top - thumbH / 2.0) / Math.max(1, h - thumbH);
        storySongScrollPx = Mth.clamp(fraction, 0, 1) * storySongsMaxScroll();
        storySongScrollTargetPx = storySongScrollFromPx = storySongScrollPx;
        storySongScrollTweenActive = false;
    }
    @Override public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if ((keyCode == GLFW.GLFW_KEY_8 || keyCode == GLFW.GLFW_KEY_KP_8)
                && (searchBox == null || !searchBox.isFocused())
                && (modSearchBox == null || !modSearchBox.isFocused())) {
            minecraft.setScreen(new SongSelectActionsScreen(this, pos)); return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }
    @Override public void onClose() {
        if (ClientSession.lockedWorldMenu) {
            AutoWorldMenuClient.openPauseMenu(this, ClientStorySession::clear);
            return;
        }
        ClientStorySession.clear();
        ClientSession.leave();
        super.onClose();
    }
    @Override public boolean isPauseScreen() { return false; }
}
