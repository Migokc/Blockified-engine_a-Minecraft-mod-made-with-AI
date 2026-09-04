package com.fnfmod.client.gui.machine;

import com.fnfmod.client.gui.BlockifiedScreenStyle;
import com.fnfmod.machine.MachineDefinition;
import com.fnfmod.machine.MachineHitboxService;
import com.fnfmod.machine.MachineLibrary;
import com.fnfmod.net.FnfPayloads;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;

/** Extensible landing screen for world and machine authoring tools. */
public final class FunkinDesignerScreen extends Screen {

    private enum Tab { MACHINES, BLOCKS }
    private enum BlockTool { CHUNK_LOADER, STANDALONE_MACHINE }

    private final boolean machineToolsEnabled;
    private Tab tab;
    private BlockTool blockTool = BlockTool.CHUNK_LOADER;
    private byte hitboxMode = MachineHitboxService.FULL_BLOCKS;
    private String selectedProfile;
    private String machineTag = "machine_1";
    private EditBox machineTagBox;
    private int profileScroll;
    private int machineListX, machineListY, machineListWidth, machineVisibleProfiles;
    private int panelX, panelY, panelWidth, panelHeight;
    private int contentX, contentY, contentWidth, contentHeight;

    public FunkinDesignerScreen(String suggestedProfile, boolean machineToolsEnabled) {
        super(Component.literal("Funkin' Designer"));
        this.machineToolsEnabled = machineToolsEnabled;
        this.selectedProfile = MachineLibrary.find(suggestedProfile).map(MachineDefinition::id)
                .orElse(MachineDefinition.DEFAULT_ID);
        this.tab = machineToolsEnabled ? Tab.MACHINES : Tab.BLOCKS;
    }

    @Override protected void init() { rebuild(); }

    private void rebuild() {
        if (machineTagBox != null) machineTag = machineTagBox.getValue();
        clearWidgets();
        machineTagBox = null;
        panelWidth = Math.max(300, Math.min(560, width - 20));
        panelHeight = Math.max(230, Math.min(340, height - 20));
        panelX = (width - panelWidth) / 2;
        panelY = (height - panelHeight) / 2;
        contentX = panelX + 14;
        contentY = panelY + 86;
        contentWidth = panelWidth - 28;
        int footerY = panelY + panelHeight - 30;
        contentHeight = footerY - contentY - 8;

        int tabGap = 6;
        int tabWidth = (contentWidth - tabGap) / 2;
        Button machines = addRenderableWidget(Button.builder(Component.literal("Machines"), button -> {
            tab = Tab.MACHINES;
            rebuild();
        }).bounds(contentX, panelY + 58, tabWidth, 20).build());
        machines.active = tab != Tab.MACHINES;
        Button blocks = addRenderableWidget(Button.builder(Component.literal("Blocks"), button -> {
            tab = Tab.BLOCKS;
            rebuild();
        }).bounds(contentX + tabWidth + tabGap, panelY + 58,
                contentWidth - tabWidth - tabGap, 20).build());
        blocks.active = tab != Tab.BLOCKS;

        int actionWidth = Math.min(190, Math.max(130, contentWidth / 2));
        int actionX = contentX + contentWidth - actionWidth - 12;
        int actionY = contentY + contentHeight - 32;
        if (tab == Tab.MACHINES) {
            buildMachineTools(actionX, actionY, actionWidth);
        } else {
            buildBlockTools(actionX, actionY, actionWidth);
        }

        addRenderableWidget(Button.builder(Component.literal("Close"), button -> onClose())
                .bounds(contentX, footerY, Math.min(100, contentWidth), 20).build());
    }

    private void buildMachineTools(int actionX, int actionY, int actionWidth) {
        int inset = 12;
        int gap = 10;
        machineListX = contentX + inset;
        machineListY = contentY + 42;
        machineListWidth = Math.max(110, (contentWidth - inset * 2 - gap) / 2);
        int rightX = machineListX + machineListWidth + gap;
        int rightWidth = contentX + contentWidth - inset - rightX;

        List<MachineDefinition> profiles = new ArrayList<>(MachineLibrary.all().values());
        machineVisibleProfiles = Math.max(1, Math.min(7,
                Math.max(1, (actionY - machineListY - 2) / 22)));
        profileScroll = Math.max(0, Math.min(profileScroll,
                Math.max(0, profiles.size() - machineVisibleProfiles)));
        for (int i = 0; i < machineVisibleProfiles && profileScroll + i < profiles.size(); i++) {
            MachineDefinition profile = profiles.get(profileScroll + i);
            boolean selected = profile.id().equals(selectedProfile);
            Button profileButton = addRenderableWidget(Button.builder(Component.literal(
                    selected ? "Selected · " + profile.displayName() : profile.displayName()), button -> {
                selectedProfile = profile.id();
                rebuild();
            }).bounds(machineListX, machineListY + i * 22, machineListWidth, 20).build());
            profileButton.active = machineToolsEnabled && !selected;
        }

        int modeWidth = Math.max(50, (rightWidth - 6) / 2);
        Button full = addRenderableWidget(Button.builder(Component.literal("Full Blocks"), button -> {
            hitboxMode = MachineHitboxService.FULL_BLOCKS;
            rebuild();
        }).bounds(rightX, machineListY, modeWidth, 20).build());
        full.active = machineToolsEnabled && hitboxMode != MachineHitboxService.FULL_BLOCKS;
        Button precise = addRenderableWidget(Button.builder(Component.literal("Precise"), button -> {
            hitboxMode = MachineHitboxService.PRECISE;
            rebuild();
        }).bounds(rightX + modeWidth + 6, machineListY,
                rightWidth - modeWidth - 6, 20).build());
        precise.active = machineToolsEnabled && hitboxMode != MachineHitboxService.PRECISE;

        if (actionY >= machineListY + 48) {
            Button cancel = addRenderableWidget(Button.builder(Component.literal("Cancel Active Selection"), button -> {
                PacketDistributor.sendToServer(new FnfPayloads.HitboxBuilderC2S(
                        (byte) 1, hitboxMode, selectedProfile));
                minecraft.setScreen(null);
            }).bounds(rightX, machineListY + 26, rightWidth, 20).build());
            cancel.active = machineToolsEnabled;
        }

        Button start = addRenderableWidget(Button.builder(Component.literal("Start Hitbox Selection"), button -> {
            PacketDistributor.sendToServer(new FnfPayloads.HitboxBuilderC2S(
                    (byte) 0, hitboxMode, selectedProfile));
            minecraft.setScreen(null);
        }).bounds(actionX, actionY, actionWidth, 20).build());
        start.active = machineToolsEnabled;
    }

    private void buildBlockTools(int actionX, int actionY, int actionWidth) {
        int inset = 12;
        int controlsX = contentX + inset;
        int controlsWidth = contentWidth - inset * 2;
        int gap = 6;
        int half = (controlsWidth - gap) / 2;
        Button loader = addRenderableWidget(Button.builder(Component.literal("Chunk Loader"), button -> {
            blockTool = BlockTool.CHUNK_LOADER;
            rebuild();
        }).bounds(controlsX, contentY + 24, half, 20).build());
        loader.active = blockTool != BlockTool.CHUNK_LOADER;
        Button machine = addRenderableWidget(Button.builder(Component.literal("Virtual Machine"), button -> {
            blockTool = BlockTool.STANDALONE_MACHINE;
            rebuild();
        }).bounds(controlsX + half + gap, contentY + 24,
                controlsWidth - half - gap, 20).build());
        machine.active = blockTool != BlockTool.STANDALONE_MACHINE && machineToolsEnabled;

        if (blockTool == BlockTool.STANDALONE_MACHINE) {
            int profileWidth = Math.max(100, controlsWidth / 2);
            Button profile = addRenderableWidget(Button.builder(profileLabel(), button -> {
                cycleProfile();
                button.setMessage(profileLabel());
            }).bounds(controlsX, contentY + 50, profileWidth, 20).build());
            profile.active = machineToolsEnabled;
            machineTagBox = new EditBox(font, controlsX + profileWidth + gap, contentY + 50,
                    controlsWidth - profileWidth - gap, 20, Component.literal("Machine ID"));
            machineTagBox.setHint(Component.literal("machine id / Lua tag"));
            machineTagBox.setMaxLength(64);
            machineTagBox.setFilter(value -> value.matches("[a-zA-Z0-9_-]*"));
            machineTagBox.setValue(machineTag);
            machineTagBox.setEditable(machineToolsEnabled);
            addRenderableWidget(machineTagBox);
        }

        String label = blockTool == BlockTool.CHUNK_LOADER
                ? "Arm Chunk Loader" : "Arm Virtual Machine";
        Button arm = addRenderableWidget(Button.builder(Component.literal(label), button -> armPlacement())
                .bounds(actionX, actionY, actionWidth, 20).build());
        arm.active = blockTool == BlockTool.CHUNK_LOADER || machineToolsEnabled;
    }

    private Component profileLabel() {
        MachineDefinition definition = MachineLibrary.get(selectedProfile);
        return Component.literal("Profile: " + (definition == null ? selectedProfile : definition.displayName()));
    }

    private void cycleProfile() {
        List<MachineDefinition> profiles = new ArrayList<>(MachineLibrary.all().values());
        if (profiles.isEmpty()) return;
        int index = 0;
        for (int i = 0; i < profiles.size(); i++) {
            if (profiles.get(i).id().equals(selectedProfile)) { index = i; break; }
        }
        selectedProfile = profiles.get((index + 1) % profiles.size()).id();
    }

    private void armPlacement() {
        if (blockTool == BlockTool.STANDALONE_MACHINE && machineTagBox != null) {
            machineTag = machineTagBox.getValue();
        }
        byte action = blockTool == BlockTool.CHUNK_LOADER
                ? FnfPayloads.DesignerActionC2S.START_CHUNK_LOADER
                : FnfPayloads.DesignerActionC2S.START_STANDALONE_MACHINE;
        PacketDistributor.sendToServer(new FnfPayloads.DesignerActionC2S(
                action, selectedProfile, blockTool == BlockTool.STANDALONE_MACHINE ? machineTag : ""));
        minecraft.setScreen(null);
    }

    @Override
    public void render(GuiGraphics gui, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(gui, mouseX, mouseY, partialTick);
        BlockifiedScreenStyle.backdrop(gui, width, height);
        BlockifiedScreenStyle.panel(gui, panelX, panelY, panelWidth, panelHeight);
        BlockifiedScreenStyle.header(gui, font, panelX + 16, panelY + 13,
                "FUNKIN' DESIGNER", "Creator tools",
                "Choose a tool, configure it, then use the Designer in the world.");
        BlockifiedScreenStyle.inner(gui, contentX, contentY, contentWidth, contentHeight);
        BlockifiedScreenStyle.section(gui, font,
                tab == Tab.MACHINES ? "INTERACTIVE AREAS" : "PLACEABLE BLOCKS",
                contentX + 10, contentY - 2);
        super.render(gui, mouseX, mouseY, partialTick);

        int textX = contentX + 14;
        if (tab == Tab.MACHINES) {
            int splitX = machineListX + machineListWidth + 10;
            gui.drawString(font, "MACHINE PROFILE", machineListX, contentY + 26,
                    BlockifiedScreenStyle.TEXT_SECTION, false);
            gui.drawString(font, "SELECTION MODE", splitX, contentY + 26,
                    BlockifiedScreenStyle.TEXT_SECTION, false);
            if (machineToolsEnabled) {
                if (contentHeight >= 160) {
                    int detailY = machineListY + 55;
                    gui.drawString(font, hitboxMode == MachineHitboxService.PRECISE
                                    ? "Crosshair-accurate volume" : "Block-aligned volume",
                            splitX, detailY, BlockifiedScreenStyle.TEXT, false);
                    gui.drawString(font, hitboxMode == MachineHitboxService.PRECISE
                                    ? "Corners use the exact aimed point." : "Corners snap to the block grid.",
                            splitX, detailY + 14, BlockifiedScreenStyle.TEXT_MUTED, false);
                }
            } else {
                gui.drawString(font, "Available in a bundled mod world.", splitX,
                        machineListY + 55, 0xFFFFAA55, false);
            }
        } else if (contentHeight >= 150) {
            int textY = contentY + 82;
            if (blockTool == BlockTool.CHUNK_LOADER) {
                gui.drawString(font, "Blue crosshair: next Designer click places a chunk loader.", textX,
                        textY, 0xFF77B9FF, false);
            } else {
                gui.drawString(font, "Orange crosshair: next Designer click places the tagged machine.", textX,
                        textY, 0xFFFFAA55, false);
                gui.drawString(font, "Click the placed block again to open its menu.", textX,
                        textY + 14, BlockifiedScreenStyle.TEXT_MUTED, false);
            }
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (tab == Tab.MACHINES && mouseX >= machineListX
                && mouseX <= machineListX + machineListWidth
                && mouseY >= machineListY && mouseY <= machineListY + machineVisibleProfiles * 22) {
            int maximum = Math.max(0, MachineLibrary.all().size() - machineVisibleProfiles);
            int next = Math.max(0, Math.min(maximum,
                    profileScroll - (int) Math.signum(scrollY)));
            if (next != profileScroll) {
                profileScroll = next;
                rebuild();
            }
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public void renderBackground(GuiGraphics gui, int mouseX, int mouseY, float partialTick) {
        // Minecraft blurs the world first; this complete foreground is rendered afterward.
    }

    @Override public boolean isPauseScreen() { return false; }
}
