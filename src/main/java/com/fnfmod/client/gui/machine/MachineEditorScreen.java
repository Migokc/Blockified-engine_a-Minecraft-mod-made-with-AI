package com.fnfmod.client.gui.machine;

import com.fnfmod.block.FunkinMachineBlockEntity;
import com.fnfmod.machine.MachineDefinition;
import com.fnfmod.machine.MachineLibrary;
import com.fnfmod.net.FnfPayloads;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;

/** Host-side machine profile editor with live in-world texture preview. */
public final class MachineEditorScreen extends Screen {

    private enum Tab { APPEARANCE, MENU, BEHAVIOR, FILES }

    private final BlockPos pos;
    private String originalId;
    private String selectedId;
    private String status = "Select a profile. Changes preview on the placed machine.";
    private boolean statusError;
    private Tab tab = Tab.APPEARANCE;
    private EditBox newId;
    private String newIdValue = "new-machine";
    private int scroll;

    public MachineEditorScreen(BlockPos pos, String profileId) {
        super(Component.literal("Funkin' Designer"));
        this.pos = pos;
        this.originalId = MachineLibrary.canonical(profileId);
        this.selectedId = this.originalId;
    }

    @Override
    protected void init() {
        rebuild();
    }

    private void rebuild() {
        if (newId != null) newIdValue = newId.getValue();
        clearWidgets();
        int panelX = width / 2 - 220;
        int top = 30;

        int tabX = panelX + 126;
        for (Tab value : Tab.values()) {
            String label = title(value);
            addRenderableWidget(Button.builder(Component.literal(label), button -> {
                tab = value;
                rebuild();
            }).bounds(tabX, top, 78, 20).build()).active = tab != value;
            tabX += 80;
        }

        List<MachineDefinition> profiles = new ArrayList<>(MachineLibrary.all().values());
        int visible = Math.max(4, Math.min(10, (height - 110) / 22));
        scroll = Math.max(0, Math.min(scroll, Math.max(0, profiles.size() - visible)));
        for (int i = 0; i < visible && scroll + i < profiles.size(); i++) {
            MachineDefinition profile = profiles.get(scroll + i);
            String prefix = profile.id().equals(selectedId) ? "> " : "";
            addRenderableWidget(Button.builder(Component.literal(prefix + profile.displayName()), button -> {
                selectedId = profile.id();
                preview(selectedId);
                rebuild();
            }).bounds(panelX, top + i * 22, 120, 20).build());
        }
        if (profiles.size() > visible) {
            addRenderableWidget(Button.builder(Component.literal("^"), button -> {
                scroll = Math.max(0, scroll - 1);
                rebuild();
            }).bounds(panelX + 100, top - 22, 20, 20).build());
            addRenderableWidget(Button.builder(Component.literal("v"), button -> {
                scroll = Math.min(profiles.size() - visible, scroll + 1);
                rebuild();
            }).bounds(panelX + 100, top + visible * 22, 20, 20).build());
        }

        int bottom = height - 28;
        addRenderableWidget(Button.builder(Component.literal("Save"), button ->
                send((byte) 0, selectedId)).bounds(panelX + 126, bottom, 72, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Reload"), button -> {
            MachineLibrary.rescan();
            send((byte) 2, "");
            rebuild();
        }).bounds(panelX + 202, bottom, 72, 20).build());

        newId = new EditBox(font, panelX + 280, bottom, 96, 20, Component.literal("Profile ID"));
        newId.setValue(newIdValue);
        newId.setMaxLength(64);
        addRenderableWidget(newId);
        addRenderableWidget(Button.builder(Component.literal("Save As"), button -> {
            newIdValue = newId.getValue();
            send((byte) 1, newIdValue);
        }).bounds(panelX + 380, bottom, 70, 20).build());
    }

    private void send(byte action, String value) {
        PacketDistributor.sendToServer(new FnfPayloads.MachineEditC2S(pos, action, value == null ? "" : value));
    }

    private void preview(String id) {
        if (minecraft != null && minecraft.level != null
                && minecraft.level.getBlockEntity(pos) instanceof FunkinMachineBlockEntity machine) {
            machine.setProfileId(id);
        }
    }

    public void onServerResult(boolean success, String message, String profileId, boolean refresh) {
        status = message;
        statusError = !success;
        if (refresh) MachineLibrary.rescan();
        if (success) {
            originalId = MachineLibrary.canonical(profileId);
            selectedId = originalId;
            preview(originalId);
        }
        rebuild();
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (mouseX < width / 2 - 94) {
            scroll = Math.max(0, scroll - (int) Math.signum(scrollY));
            rebuild();
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public void render(GuiGraphics gui, int mouseX, int mouseY, float partialTick) {
        super.render(gui, mouseX, mouseY, partialTick);
        int panelX = width / 2 - 220;
        int contentX = panelX + 132;
        gui.drawCenteredString(font, title, width / 2, 10, 0xFFFFFFFF);
        gui.drawString(font, "Profiles", panelX, 16, 0xFFBBBBBB, false);
        gui.drawString(font, "Profile: " + selectedId, contentX, 58, 0xFFFFFFFF, false);

        MachineDefinition definition = MachineLibrary.get(selectedId);
        int y = 78;
        switch (tab) {
            case APPEARANCE -> {
                line(gui, contentX, y, "Live preview active on machine in world.");
                line(gui, contentX, y + 14, "Texture faces: " +
                        (definition.textures().isEmpty() ? "built-in" : String.join(", ", definition.textures().keySet())));
                line(gui, contentX, y + 28, "Put PNG files inside profile textures/ folder.");
            }
            case MENU -> {
                line(gui, contentX, y, "Lua menu: " + path(definition.menuScript()));
                line(gui, contentX, y + 14, "Edit file externally, then press Reload.");
            }
            case BEHAVIOR -> {
                line(gui, contentX, y, "Behavior JSON:");
                line(gui, contentX, y + 14, trim(definition.behavior().toString(), 72));
            }
            case FILES -> {
                line(gui, contentX, y, "Profile folder:");
                line(gui, contentX, y + 14, path(definition.root()));
                line(gui, contentX, y + 42, "machine.json defines ID, name, textures, menu, behavior.");
            }
        }
        gui.drawString(font, status, contentX, height - 44,
                statusError ? 0xFFFF7777 : 0xFF88DD88, false);
    }

    private void line(GuiGraphics gui, int x, int y, String text) {
        gui.drawString(font, trim(text, 82), x, y, 0xFFCCCCCC, false);
    }

    private static String path(java.nio.file.Path path) {
        return path == null ? "built-in" : path.toString();
    }

    private static String trim(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max - 3) + "...";
    }

    private static String title(Tab tab) {
        String lower = tab.name().toLowerCase(java.util.Locale.ROOT);
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }

    @Override
    public void onClose() {
        preview(originalId);
        super.onClose();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
