package com.fnfmod.client.gui;

import com.fnfmod.client.gameplay.NativeFilePicker;
import com.fnfmod.client.render.IconLibrary;
import com.fnfmod.song.SongLibrary;
import com.fnfmod.song.WeekDefinition;
import com.fnfmod.song.WeekLibrary;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Psych-compatible week authoring screen with an always-live Story Mode preview. */
public final class WeekMakerScreen extends Screen {
    private final Screen parent;
    private EditBox idField, nameField, songsField, difficultiesField;
    private Path imageSource;
    private boolean hideStory, hideFreeplay;
    private Button storyToggle, freeplayToggle;
    private String status = "Songs are comma-separated; each must already exist.";
    private int panelX, panelY, panelW, panelH;

    public WeekMakerScreen(Screen parent) {
        super(Component.literal("Week Maker"));
        this.parent = parent;
    }

    @Override protected void init() {
        panelW = Math.max(520, Math.min(760, width - 24));
        panelH = Math.max(310, Math.min(390, height - 24));
        panelX = (width - panelW) / 2;
        panelY = (height - panelH) / 2;
        int formX = panelX + panelW / 2 + 12, fieldW = panelX + panelW - 16 - formX;
        int y = panelY + 72;
        idField = field(formX, y, fieldW, "week-id", "Week file ID");
        nameField = field(formX, y + 34, fieldW, "My Week", "Story display name");
        songsField = field(formX, y + 68, fieldW, "", "Songs: song-a, song-b");
        difficultiesField = field(formX, y + 102, fieldW, "normal", "Difficulties: easy, normal, hard");
        int buttonW = (fieldW - 6) / 2;
        addRenderableWidget(Button.builder(Component.literal("Choose Image"), ignored -> chooseImage())
                .bounds(formX, y + 134, buttonW, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Load Week"), ignored -> loadWeek())
                .bounds(formX + buttonW + 6, y + 134, fieldW - buttonW - 6, 20).build());
        storyToggle = addRenderableWidget(Button.builder(Component.literal(storyLabel()), button -> {
            hideStory = !hideStory; button.setMessage(Component.literal(storyLabel()));
        }).bounds(formX, y + 162, buttonW, 20).build());
        freeplayToggle = addRenderableWidget(Button.builder(Component.literal(freeplayLabel()), button -> {
            hideFreeplay = !hideFreeplay; button.setMessage(Component.literal(freeplayLabel()));
        }).bounds(formX + buttonW + 6, y + 162, fieldW - buttonW - 6, 20).build());
        int footerY = panelY + panelH - 34;
        addRenderableWidget(Button.builder(Component.literal("Cancel"), ignored -> onClose())
                .bounds(formX, footerY, buttonW, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Save"), ignored -> save())
                .bounds(formX + buttonW + 6, footerY, fieldW - buttonW - 6, 20).build());
    }

    private EditBox field(int x, int y, int width, String value, String hint) {
        EditBox box = addRenderableWidget(new EditBox(font, x, y, width, 20, Component.literal(hint)));
        box.setValue(value); box.setHint(Component.literal(hint)); box.setMaxLength(512);
        return box;
    }

    private String storyLabel() { return hideStory ? "Story: Hidden" : "Story: Visible"; }
    private String freeplayLabel() { return hideFreeplay ? "Freeplay: Hidden" : "Freeplay: Visible"; }

    private void chooseImage() {
        NativeFilePicker.openFile("Choose week image", new String[]{"*.png"}, "PNG image")
                .filter(Files::isRegularFile).ifPresent(path -> {
                    imageSource = path.toAbsolutePath().normalize();
                    status = "Image: " + imageSource.getFileName();
                });
    }

    private void loadWeek() {
        NativeFilePicker.openFile("Load Psych week", new String[]{"*.json"}, "Psych week JSON")
                .filter(Files::isRegularFile).ifPresent(file -> {
                    WeekDefinition week = WeekLibrary.read(file.getParent().getParent(), file);
                    if (week == null) { status = "That file is not a usable Psych week."; return; }
                    idField.setValue(week.id()); nameField.setValue(week.displayName());
                    songsField.setValue(String.join(", ", week.songs().stream().map(WeekDefinition.Song::id).toList()));
                    difficultiesField.setValue(String.join(", ", week.difficulties()));
                    hideStory = week.hideStoryMode(); hideFreeplay = week.hideFreeplay();
                    imageSource = week.imageFile();
                    storyToggle.setMessage(Component.literal(storyLabel()));
                    freeplayToggle.setMessage(Component.literal(freeplayLabel()));
                    status = "Loaded " + file.getFileName();
                });
    }

    private void save() {
        String id = safeId(idField.getValue());
        List<String> songIds = csv(songsField.getValue());
        if (id.isBlank() || songIds.isEmpty()) { status = "A week ID and at least one song are required."; return; }
        List<WeekDefinition.Song> songs = songIds.stream()
                .map(song -> new WeekDefinition.Song(song, "dad", 0x9271FD)).toList();
        List<String> difficulties = csv(difficultiesField.getValue());
        if (difficulties.isEmpty()) difficulties = List.of("normal");
        Path root = SongLibrary.modsDir();
        Path file = root.resolve("weeks").resolve(id + ".json");
        Path image = null;
        try {
            if (imageSource != null && Files.isRegularFile(imageSource)) {
                image = root.resolve("images").resolve("storymenu").resolve(id + ".png");
                Files.createDirectories(image.getParent());
                if (!imageSource.equals(image)) Files.copy(imageSource, image, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception error) {
            status = "Could not copy image: " + error.getMessage(); return;
        }
        WeekDefinition week = new WeekDefinition(id, value(nameField, id), value(nameField, id), songs,
                difficulties, List.of("", "", ""), "stage", "", true, false, hideStory, hideFreeplay,
                root, file, image);
        if (!WeekLibrary.write(file, week)) { status = "Could not save the week."; return; }
        try {
            Path orderFile = root.resolve("weeks").resolve("weekList.txt");
            List<String> order = Files.isRegularFile(orderFile) ? Files.readAllLines(orderFile) : new ArrayList<>();
            if (order.stream().noneMatch(value -> value.trim().equalsIgnoreCase(id))) {
                order.add(id);
                Files.write(orderFile, order);
            }
        } catch (Exception error) {
            status = "Week saved, but weekList.txt could not be updated.";
        }
        SongLibrary.rescan(); WeekLibrary.rescan();
        if (!status.startsWith("Week saved")) status = "Saved " + file;
    }

    private static String value(EditBox field, String fallback) {
        String value = field.getValue().trim(); return value.isBlank() ? fallback : value;
    }
    private static String safeId(String value) {
        return value.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]+", "-")
                .replaceAll("^-+|-+$", "");
    }
    private static List<String> csv(String value) {
        List<String> result = new ArrayList<>();
        for (String item : value.split(",")) if (!item.trim().isBlank()) result.add(item.trim());
        return List.copyOf(result);
    }

    @Override public void render(GuiGraphics gui, int mouseX, int mouseY, float partialTick) {
        BlockifiedScreenStyle.backdrop(gui, width, height);
        BlockifiedScreenStyle.panel(gui, panelX, panelY, panelW, panelH);
        BlockifiedScreenStyle.header(gui, font, panelX + 16, panelY + 13,
                "BLOCKIFIED ENGINE", "Week Maker", "Creates standard Psych Engine week files.");
        int previewX = panelX + 14, previewY = panelY + 66, previewW = panelW / 2 - 22;
        BlockifiedScreenStyle.inner(gui, previewX, previewY, previewW, panelH - 108);
        gui.drawCenteredString(font, value(nameField, "Week Preview"), previewX + previewW / 2,
                previewY + 10, BlockifiedScreenStyle.ACCENT);
        if (imageSource != null && Files.isRegularFile(imageSource))
            IconLibrary.drawImageFile(gui, imageSource.toString(), previewX + previewW / 2f,
                    previewY + 65, previewW - 30, 76);
        else gui.drawCenteredString(font, "Choose a story image", previewX + previewW / 2,
                previewY + 56, BlockifiedScreenStyle.TEXT_MUTED);
        List<String> previewSongs = csv(songsField == null ? "" : songsField.getValue());
        int songY = previewY + 112;
        for (int i = 0; i < Math.min(previewSongs.size(), 7); i++)
            gui.drawString(font, (i + 1) + ". " + previewSongs.get(i), previewX + 18, songY + i * 14,
                    0xFFFFFFFF, false);
        gui.drawCenteredString(font, "Week Score: 0", previewX + previewW / 2,
                previewY + panelH - 134, BlockifiedScreenStyle.ACCENT_LIGHT);
        int formX = panelX + panelW / 2 + 12;
        int formY = panelY + 72;
        gui.drawString(font, "WEEK ID", formX, formY - 10, BlockifiedScreenStyle.TEXT_SECTION, false);
        gui.drawString(font, "DISPLAY NAME", formX, formY + 24, BlockifiedScreenStyle.TEXT_SECTION, false);
        gui.drawString(font, "SONGS", formX, formY + 58, BlockifiedScreenStyle.TEXT_SECTION, false);
        gui.drawString(font, "DIFFICULTIES", formX, formY + 92, BlockifiedScreenStyle.TEXT_SECTION, false);
        gui.drawString(font, font.plainSubstrByWidth(status, panelW / 2 - 34), formX, panelY + panelH - 50,
                BlockifiedScreenStyle.TEXT_MUTED, false);
        super.render(gui, mouseX, mouseY, partialTick);
    }

    @Override public void renderBackground(GuiGraphics gui, int mouseX, int mouseY, float partialTick) {}
    @Override public void onClose() { minecraft.setScreen(parent); }
    @Override public boolean isPauseScreen() { return false; }
}
