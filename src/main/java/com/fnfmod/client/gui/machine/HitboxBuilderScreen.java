package com.fnfmod.client.gui.machine;

import com.fnfmod.client.gui.BlockifiedScreenStyle;
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
    private int panelX;
    private int panelY;
    private int panelWidth;
    private int panelHeight;
    private int leftX;
    private int leftWidth;
    private int rightX;
    private int rightWidth;
    private int contentTop;
    private int footerY;
    private int visibleProfiles;

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
        panelWidth = Math.max(280, Math.min(580, width - 20));
        panelHeight = Math.max(220, Math.min(350, height - 20));
        panelX = (width - panelWidth) / 2;
        panelY = (height - panelHeight) / 2;
        int innerGap = 12;
        leftX = panelX + 14;
        leftWidth = (panelWidth - 40 - innerGap) / 2;
        rightX = leftX + leftWidth + innerGap;
        rightWidth = panelX + panelWidth - 14 - rightX;
        contentTop = panelY + 58;
        footerY = panelY + panelHeight - 30;

        List<MachineDefinition> profiles = new ArrayList<>(MachineLibrary.all().values());
        visibleProfiles = Math.max(2, Math.min(9, (footerY - contentTop - 28) / 22));
        scroll = Math.max(0, Math.min(scroll, Math.max(0, profiles.size() - visibleProfiles)));
        int profileTop = contentTop + 24;
        for (int i = 0; i < visibleProfiles && scroll + i < profiles.size(); i++) {
            MachineDefinition profile = profiles.get(scroll + i);
            boolean selected = profile.id().equals(selectedProfile);
            Button profileButton = addRenderableWidget(Button.builder(Component.literal(
                    selected ? "Selected · " + profile.displayName() : profile.displayName()), button -> {
                selectedProfile = profile.id();
                rebuild();
            }).bounds(leftX, profileTop + i * 22, leftWidth, 20).build());
            profileButton.active = !selected;
        }
        if (profiles.size() > visibleProfiles) {
            addRenderableWidget(Button.builder(Component.literal("^"), button -> {
                scroll = Math.max(0, scroll - 1);
                rebuild();
            }).bounds(leftX + leftWidth - 42, contentTop, 20, 20).build());
            addRenderableWidget(Button.builder(Component.literal("v"), button -> {
                scroll = Math.min(profiles.size() - visibleProfiles, scroll + 1);
                rebuild();
            }).bounds(leftX + leftWidth - 20, contentTop, 20, 20).build());
        }

        int modeWidth = Math.max(54, (rightWidth - 6) / 2);
        Button full = addRenderableWidget(Button.builder(Component.literal("Full Blocks"), button -> {
            mode = MachineHitboxService.FULL_BLOCKS;
            rebuild();
        }).bounds(rightX, contentTop + 24, modeWidth, 20).build());
        full.active = mode != MachineHitboxService.FULL_BLOCKS;
        Button precise = addRenderableWidget(Button.builder(Component.literal("Precise"), button -> {
            mode = MachineHitboxService.PRECISE;
            rebuild();
        }).bounds(rightX + modeWidth + 6, contentTop + 24, rightWidth - modeWidth - 6, 20).build());
        precise.active = mode != MachineHitboxService.PRECISE;

        int footerGap = 6;
        int footerButtonWidth = (panelWidth - 28 - footerGap * 2) / 3;
        addRenderableWidget(Button.builder(Component.literal("Close"), button -> onClose())
                .bounds(panelX + 14, footerY, footerButtonWidth, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Cancel Selection"), button -> {
            PacketDistributor.sendToServer(new FnfPayloads.HitboxBuilderC2S((byte) 1, mode, selectedProfile));
            minecraft.setScreen(null);
        }).bounds(panelX + 14 + footerButtonWidth + footerGap, footerY, footerButtonWidth, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Start Selection"), button -> {
            PacketDistributor.sendToServer(new FnfPayloads.HitboxBuilderC2S((byte) 0, mode, selectedProfile));
            minecraft.setScreen(null);
        }).bounds(panelX + 14 + (footerButtonWidth + footerGap) * 2,
                footerY, footerButtonWidth, 20).build());
    }

    @Override
    public void render(GuiGraphics gui, int mouseX, int mouseY, float partialTick) {
        BlockifiedScreenStyle.backdrop(gui, width, height);
        BlockifiedScreenStyle.panel(gui, panelX, panelY, panelWidth, panelHeight);
        BlockifiedScreenStyle.inner(gui, panelX + 14, contentTop - 4,
                leftWidth, footerY - contentTop - 2);
        BlockifiedScreenStyle.inner(gui, rightX, contentTop - 4,
                rightWidth, footerY - contentTop - 2);
        BlockifiedScreenStyle.header(gui, font, panelX + 16, panelY + 13,
                "FUNKIN' DESIGNER", "Virtual Machine Hitbox",
                "Choose a machine profile and how precisely its interactive area is marked.");
        gui.drawString(font, "MACHINE PROFILE", leftX + 8, contentTop + 4,
                BlockifiedScreenStyle.TEXT_SECTION, false);
        gui.drawString(font, "SELECTION MODE", rightX + 8, contentTop + 4,
                BlockifiedScreenStyle.TEXT_SECTION, false);

        int textX = rightX + 9;
        int textY = contentTop + 55;
        if (mode == MachineHitboxService.FULL_BLOCKS) {
            gui.drawString(font, "Block-aligned volume", textX, textY, 0xFFFFFFFF, false);
            gui.drawString(font, "Corners snap to the block grid.", textX, textY + 14, 0xFFB7AFBC, false);
            gui.drawString(font, "Best for doors and full blocks.", textX, textY + 26, 0xFF8E8695, false);
        } else {
            gui.drawString(font, "Crosshair-accurate volume", textX, textY, 0xFFFFFFFF, false);
            gui.drawString(font, "Corners use the exact aimed point.", textX, textY + 14, 0xFFB7AFBC, false);
            gui.drawString(font, "A yellow crosshair marks corner one.", textX, textY + 26, 0xFFFFD84A, false);
        }
        int detailBottom = footerY - 22;
        if (textY + 50 <= detailBottom)
            gui.drawString(font, "1  Aim and mark the first corner", textX, textY + 50, 0xFFB7AFBC, false);
        if (textY + 64 <= detailBottom)
            gui.drawString(font, "2  Mark the opposite corner", textX, textY + 64, 0xFFB7AFBC, false);
        if (textY + 78 <= detailBottom)
            gui.drawString(font, "3  Place the temporary anchor", textX, textY + 78, 0xFFB7AFBC, false);
        gui.drawString(font, "Maximum size: 64 blocks per axis", textX, footerY - 20, 0xFF8E8695, false);

        super.render(gui, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (mouseX >= leftX && mouseX <= leftX + leftWidth
                && mouseY >= contentTop && mouseY <= footerY - 6) {
            List<MachineDefinition> profiles = new ArrayList<>(MachineLibrary.all().values());
            int next = Math.max(0, Math.min(Math.max(0, profiles.size() - visibleProfiles),
                    scroll - (int) Math.signum(scrollY)));
            if (next != scroll) {
                scroll = next;
                rebuild();
            }
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override public boolean isPauseScreen() { return false; }
}
