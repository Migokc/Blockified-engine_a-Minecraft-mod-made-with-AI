package com.fnfmod.client.gui.machine;

import com.fnfmod.machine.MachineDefinition;
import com.fnfmod.machine.MachineHitboxService;
import com.fnfmod.machine.MachineLibrary;
import com.fnfmod.net.FnfPayloads;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;

/** Chooses virtual-machine profile and full-block vs precise hitbox selection. */
public final class HitboxBuilderScreen extends Screen {

    private String selectedProfile;
    private byte mode = MachineHitboxService.FULL_BLOCKS;
    private int scroll;

    public HitboxBuilderScreen(String suggestedProfile) {
        super(Component.literal("Machine Hitbox Builder"));
        selectedProfile = MachineLibrary.find(suggestedProfile).map(MachineDefinition::id)
                .orElse(MachineDefinition.DEFAULT_ID);
    }

    @Override
    protected void init() {
        rebuild();
    }

    private void rebuild() {
        clearWidgets();
        int left = width / 2 - 190;
        int top = 48;
        List<MachineDefinition> profiles = new ArrayList<>(MachineLibrary.all().values());
        int visible = Math.max(4, Math.min(8, (height - 130) / 22));
        scroll = Math.max(0, Math.min(scroll, Math.max(0, profiles.size() - visible)));
        for (int i = 0; i < visible && scroll + i < profiles.size(); i++) {
            MachineDefinition profile = profiles.get(scroll + i);
            String prefix = profile.id().equals(selectedProfile) ? "> " : "";
            addRenderableWidget(Button.builder(Component.literal(prefix + profile.displayName()), button -> {
                selectedProfile = profile.id();
                rebuild();
            }).bounds(left, top + i * 22, 180, 20).build());
        }
        if (profiles.size() > visible) {
            addRenderableWidget(Button.builder(Component.literal("^"), button -> {
                scroll = Math.max(0, scroll - 1);
                rebuild();
            }).bounds(left + 160, top - 22, 20, 20).build());
            addRenderableWidget(Button.builder(Component.literal("v"), button -> {
                scroll = Math.min(profiles.size() - visible, scroll + 1);
                rebuild();
            }).bounds(left + 160, top + visible * 22, 20, 20).build());
        }

        int right = width / 2 + 8;
        addRenderableWidget(Button.builder(Component.literal(
                mode == MachineHitboxService.FULL_BLOCKS ? "> Full Blocks" : "Full Blocks"), button -> {
            mode = MachineHitboxService.FULL_BLOCKS;
            rebuild();
        }).bounds(right, top, 180, 20).build());
        addRenderableWidget(Button.builder(Component.literal(
                mode == MachineHitboxService.PRECISE ? "> Precise" : "Precise"), button -> {
            mode = MachineHitboxService.PRECISE;
            rebuild();
        }).bounds(right, top + 24, 180, 20).build());

        addRenderableWidget(Button.builder(Component.literal("Start Selection"), button -> {
            PacketDistributor.sendToServer(new FnfPayloads.HitboxBuilderC2S((byte) 0, mode, selectedProfile));
            minecraft.setScreen(null);
        }).bounds(right, top + 86, 180, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Cancel Existing Selection"), button -> {
            PacketDistributor.sendToServer(new FnfPayloads.HitboxBuilderC2S((byte) 1, mode, selectedProfile));
            minecraft.setScreen(null);
        }).bounds(right, top + 110, 180, 20).build());
    }

    @Override
    public void render(GuiGraphics gui, int mouseX, int mouseY, float partialTick) {
        super.render(gui, mouseX, mouseY, partialTick);
        gui.drawCenteredString(font, title, width / 2, 14, 0xFFFFFFFF);
        gui.drawCenteredString(font,
                Component.literal("Choose profile and selection precision. Then mark two corners with Designer."),
                width / 2, 30, 0xFFBBBBBB);
        int right = width / 2 + 8;
        int y = 104;
        String explanation = mode == MachineHitboxService.FULL_BLOCKS
                ? "Snaps entity bounds to block-grid lines."
                : "Uses exact clicked world positions.";
        gui.drawString(font, explanation, right, y, 0xFFCCCCCC, false);
        gui.drawString(font, "Entity volume; overlaps blocks safely.", right, y + 14, 0xFF999999, false);
        gui.drawString(font, "Maximum: 64 blocks per axis.", right, y + 26, 0xFF999999, false);
        gui.drawString(font, "After corner 2, place temporary", right, y + 46, 0xFFAAAAAA, false);
        gui.drawString(font, "Machine Anchor at stage origin.", right, y + 58, 0xFFAAAAAA, false);
    }

    @Override public boolean isPauseScreen() { return false; }
}
