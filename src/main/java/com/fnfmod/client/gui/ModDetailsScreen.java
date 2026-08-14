package com.fnfmod.client.gui;

import com.fnfmod.client.render.IconLibrary;
import com.fnfmod.song.ModPackInfo;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Read-only Psych pack.json/pack.png view opened from the Mods settings list. */
public final class ModDetailsScreen extends Screen {
    private final Screen parent;
    private final ModPackInfo pack;

    public ModDetailsScreen(Screen parent, ModPackInfo pack) {
        super(Component.literal(pack.name()));
        this.parent = parent;
        this.pack = pack;
    }

    @Override
    protected void init() {
        addRenderableWidget(Button.builder(Component.literal("Back"), button -> onClose())
                .bounds(width / 2 - 60, height - 32, 120, 20).build());
    }

    @Override
    public void render(GuiGraphics gui, int mouseX, int mouseY, float partialTick) {
        super.render(gui, mouseX, mouseY, partialTick);
        int iconSize = Math.min(96, Math.max(48, height / 4));
        int iconY = 34;
        if (pack.icon() != null) {
            IconLibrary.drawFile(gui, pack.icon().toString(), 0,
                    width / 2f, iconY + iconSize / 2f, iconSize, false);
        } else {
            gui.fill(width / 2 - iconSize / 2, iconY, width / 2 + iconSize / 2,
                    iconY + iconSize, 0x55333333);
            gui.drawCenteredString(font, "No pack.png", width / 2,
                    iconY + iconSize / 2 - 4, 0xFFAAAAAA);
        }
        int textY = iconY + iconSize + 14;
        gui.drawCenteredString(font, pack.name(), width / 2, textY, 0xFFFFFFFF);
        textY += 20;
        int wrapWidth = Math.min(420, Math.max(140, width - 40));
        for (var line : font.split(Component.literal(pack.description()), wrapWidth)) {
            gui.drawCenteredString(font, line, width / 2, textY, 0xFFCCCCCC);
            textY += font.lineHeight + 2;
        }
        String path = pack.root().toString();
        if (font.width(path) > wrapWidth) {
            path = "..." + font.plainSubstrByWidth(path, wrapWidth - font.width("..."), true);
        }
        gui.drawCenteredString(font, path, width / 2, Math.min(height - 48, textY + 12), 0xFF777777);
    }

    @Override
    public void onClose() {
        if (minecraft != null) minecraft.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() { return false; }
}
