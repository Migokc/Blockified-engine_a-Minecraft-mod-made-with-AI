package com.fnfmod.client.gui;

import com.fnfmod.client.ModDownloadManager;
import com.fnfmod.song.ModDownloadLinks;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.net.URI;

/** Server-supplied mod_url.txt links require an explicit player choice before downloading. */
public final class MissingGameBananaModScreen extends Screen {
    private final String song;
    private final ModDownloadLinks.Link link;
    private boolean aware;
    private Button downloadButton;
    private String status = "";

    public MissingGameBananaModScreen(String song, String url) {
        super(Component.literal("Missing Song Mod"));
        this.song = song;
        this.link = ModDownloadLinks.parse(url);
    }

    @Override protected void init() {
        int x = width / 2 - 150;
        int top = height / 2 - 105;
        if (link == null) {
            addRenderableWidget(Button.builder(Component.literal("Close"), button -> onClose())
                    .bounds(width / 2 - 55, top + 132, 110, 20).build());
            return;
        }
        boolean external = link.host() != ModDownloadLinks.Host.GAMEBANANA;
        if (external) {
            addRenderableWidget(Button.builder(Component.literal("I'm Aware"), button -> {
                aware = true;
                downloadButton.active = true;
            }).bounds(x + 15, top + 103, 125, 20).build());
        }
        downloadButton = addRenderableWidget(Button.builder(Component.literal("Download in-game"), button -> {
            if (external && !aware) return;
            if (link.host() == ModDownloadLinks.Host.GAMEBANANA) {
                Minecraft.getInstance().setScreen(new GameBananaModFilesScreen(this,
                        link.gameBananaId(), "Song mod for " + song, link.url(), false,
                        () -> status = "Installed. Rejoin the stage to continue."));
            } else {
                ModDownloadManager.Job job = ModDownloadManager.startExternal(link,
                        "Song mod for " + song, () -> status = "Installed. Rejoin the stage to continue.");
                if (job == null) status = "Another mod download is active.";
                else ModDownloadManager.showDetails(this);
            }
        }).bounds(x + 145, top + 103, 140, 20).build());
        downloadButton.active = !external || aware;
        addRenderableWidget(Button.builder(Component.literal("Open source page"), button -> {
            Util.getPlatform().openUri(URI.create(link.url()));
        }).bounds(x + 15, top + 132, 125, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Not now"), button -> onClose())
                .bounds(x + 145, top + 132, 140, 20).build());
    }

    @Override public void render(GuiGraphics gui, int mouseX, int mouseY, float partialTick) {
        BlockifiedScreenStyle.backdrop(gui, width, height);
        int x = width / 2 - 150, top = height / 2 - 105;
        BlockifiedScreenStyle.panel(gui, x, top, 300, 210);
        if (link == null) {
            gui.drawCenteredString(font, "You don't have the required mod", width / 2,
                    top + 38, BlockifiedScreenStyle.ACCENT);
            gui.drawCenteredString(font, "Song: " + font.plainSubstrByWidth(song, 220), width / 2,
                    top + 66, BlockifiedScreenStyle.TEXT);
            gui.drawCenteredString(font, "No usable mod_url.txt download link is available.", width / 2,
                    top + 90, BlockifiedScreenStyle.TEXT_MUTED);
            gui.drawCenteredString(font, "Install matching local files to join this song.", width / 2,
                    top + 105, BlockifiedScreenStyle.TEXT_MUTED);
            super.render(gui, mouseX, mouseY, partialTick);
            return;
        }
        gui.drawCenteredString(font, "You don't have the required mod for " +
                font.plainSubstrByWidth(song, 175), width / 2, top + 15,
                BlockifiedScreenStyle.ACCENT);
        gui.drawCenteredString(font, "Each player checks their own local files.", width / 2,
                top + 38, BlockifiedScreenStyle.TEXT_MUTED);
        if (link != null) {
            String source = switch (link.host()) {
                case GAMEBANANA -> "GameBanana";
                case GOOGLE_DRIVE -> "Google Drive";
                case MEDIAFIRE -> "MediaFire";
                case MEGA -> "MEGA";
            };
            gui.drawCenteredString(font, "Source: " + source, width / 2, top + 55,
                    BlockifiedScreenStyle.TEXT);
            if (link.host() != ModDownloadLinks.Host.GAMEBANANA) {
                gui.drawCenteredString(font, "External host: Blockified cannot verify this mod.",
                        width / 2, top + 73, 0xFFFFB56A);
                gui.drawCenteredString(font, "Review the link and choose I'm Aware first.",
                        width / 2, top + 86, BlockifiedScreenStyle.TEXT_MUTED);
            } else {
                gui.drawCenteredString(font, "Choose a file before downloading.", width / 2,
                        top + 79, BlockifiedScreenStyle.TEXT_MUTED);
            }
        }
        if (!status.isBlank()) gui.drawCenteredString(font,
                font.plainSubstrByWidth(status, 270), width / 2, top + 172,
                BlockifiedScreenStyle.TEXT_MUTED);
        super.render(gui, mouseX, mouseY, partialTick);
    }

    @Override public void renderBackground(GuiGraphics gui, int mouseX, int mouseY, float partialTick) {}

    @Override public void onClose() { Minecraft.getInstance().setScreen(null); }
}
