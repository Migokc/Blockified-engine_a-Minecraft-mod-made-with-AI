package com.fnfmod.client.gui.machine;

import com.fnfmod.block.FunkinMachineBlockEntity;
import com.fnfmod.client.gui.BlockifiedScreenStyle;
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
    private int panelX;
    private int panelY;
    private int panelWidth;
    private int panelHeight;
    private int listX;
    private int listWidth;
    private int contentX;
    private int contentWidth;
    private int contentTop;
    private int footerY;
    private int visibleProfiles;

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
        panelWidth = Math.max(330, Math.min(650, width - 20));
        panelHeight = Math.max(238, Math.min(370, height - 20));
        panelX = (width - panelWidth) / 2;
        panelY = (height - panelHeight) / 2;
        int gap = 10;
        listX = panelX + 14;
        listWidth = Math.max(112, Math.min(160, panelWidth / 3));
        contentX = listX + listWidth + gap;
        contentWidth = panelX + panelWidth - 14 - contentX;
        contentTop = panelY + 82;
        footerY = panelY + panelHeight - 30;

        int tabWidth = Math.max(45, (contentWidth - 6) / Tab.values().length);
        int tabX = contentX;
        for (Tab value : Tab.values()) {
            String label = title(value);
            addRenderableWidget(Button.builder(Component.literal(label), button -> {
                tab = value;
                rebuild();
            }).bounds(tabX, panelY + 54, tabWidth, 20).build()).active = tab != value;
            tabX += tabWidth + 2;
        }

        List<MachineDefinition> profiles = new ArrayList<>(MachineLibrary.all().values());
        visibleProfiles = Math.max(3, Math.min(11, (footerY - contentTop - 26) / 22));
        scroll = Math.max(0, Math.min(scroll, Math.max(0, profiles.size() - visibleProfiles)));
        int profileTop = contentTop + 22;
        for (int i = 0; i < visibleProfiles && scroll + i < profiles.size(); i++) {
            MachineDefinition profile = profiles.get(scroll + i);
            boolean selected = profile.id().equals(selectedId);
            Button profileButton = addRenderableWidget(Button.builder(Component.literal(
                    selected ? "Selected · " + profile.displayName() : profile.displayName()), button -> {
                selectedId = profile.id();
                preview(selectedId);
                rebuild();
            }).bounds(listX, profileTop + i * 22, listWidth, 20).build());
            profileButton.active = !selected;
        }
        if (profiles.size() > visibleProfiles) {
            addRenderableWidget(Button.builder(Component.literal("^"), button -> {
                scroll = Math.max(0, scroll - 1);
                rebuild();
            }).bounds(listX + listWidth - 42, contentTop, 20, 20).build());
            addRenderableWidget(Button.builder(Component.literal("v"), button -> {
                scroll = Math.min(profiles.size() - visibleProfiles, scroll + 1);
                rebuild();
            }).bounds(listX + listWidth - 20, contentTop, 20, 20).build());
        }

        int bottom = footerY;
        int footerX = panelX + 14;
        int footerWidth = panelWidth - 28;
        int saveWidth = Math.min(76, Math.max(48, footerWidth / 6));
        addRenderableWidget(Button.builder(Component.literal("Save"), button ->
                send((byte) 0, selectedId)).bounds(footerX, bottom, saveWidth, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Reload"), button -> {
            MachineLibrary.rescan();
            send((byte) 2, "");
            rebuild();
        }).bounds(footerX + saveWidth + 4, bottom, saveWidth, 20).build());

        int saveAsWidth = Math.min(70, Math.max(52, footerWidth / 6));
        int fieldX = footerX + saveWidth * 2 + 8;
        int fieldWidth = Math.max(48, footerX + footerWidth - saveAsWidth - 4 - fieldX);
        newId = new EditBox(font, fieldX, bottom, fieldWidth, 20, Component.literal("Profile ID"));
        newId.setValue(newIdValue);
        newId.setMaxLength(64);
        addRenderableWidget(newId);
        addRenderableWidget(Button.builder(Component.literal("Save As"), button -> {
            newIdValue = newId.getValue();
            send((byte) 1, newIdValue);
        }).bounds(footerX + footerWidth - saveAsWidth, bottom, saveAsWidth, 20).build());
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
        if (mouseX >= listX && mouseX <= listX + listWidth
                && mouseY >= contentTop && mouseY <= footerY - 6) {
            scroll = Math.max(0, scroll - (int) Math.signum(scrollY));
            rebuild();
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public void render(GuiGraphics gui, int mouseX, int mouseY, float partialTick) {
        BlockifiedScreenStyle.backdrop(gui, width, height);
        BlockifiedScreenStyle.panel(gui, panelX, panelY, panelWidth, panelHeight);
        BlockifiedScreenStyle.header(gui, font, panelX + 16, panelY + 13,
                "FUNKIN' DESIGNER", "Machine profiles",
                "Preview a profile live, inspect its parts, then save it to this machine.");
        BlockifiedScreenStyle.inner(gui, listX, contentTop - 4,
                listWidth, footerY - contentTop - 4);
        BlockifiedScreenStyle.inner(gui, contentX, contentTop - 4,
                contentWidth, footerY - contentTop - 4);
        BlockifiedScreenStyle.section(gui, font, "PROFILES", listX + 8, contentTop - 14);
        BlockifiedScreenStyle.section(gui, font, "PROFILE DETAILS", contentX + 8, contentTop - 14);
        super.render(gui, mouseX, mouseY, partialTick);
        gui.drawString(font, "Profile: " + selectedId, contentX + 9, contentTop + 8,
                BlockifiedScreenStyle.TEXT, false);

        MachineDefinition definition = MachineLibrary.get(selectedId);
        int y = contentTop + 30;
        switch (tab) {
            case APPEARANCE -> {
                line(gui, contentX + 9, y, "Live preview active on machine in world.");
                line(gui, contentX + 9, y + 14, "Texture faces: " +
                        (definition.textures().isEmpty() ? "built-in" : String.join(", ", definition.textures().keySet())));
                line(gui, contentX + 9, y + 28, "Put PNG files inside profile textures/ folder.");
            }
            case MENU -> {
                line(gui, contentX + 9, y, "Lua menu: " + path(definition.menuScript()));
                line(gui, contentX + 9, y + 14, "Edit file externally, then press Reload.");
            }
            case BEHAVIOR -> {
                line(gui, contentX + 9, y, "Behavior JSON:");
                line(gui, contentX + 9, y + 14, trim(definition.behavior().toString(), 72));
            }
            case FILES -> {
                line(gui, contentX + 9, y, "Profile folder:");
                line(gui, contentX + 9, y + 14, path(definition.root()));
                line(gui, contentX + 9, y + 42, "machine.json defines ID, name, textures, menu, behavior.");
            }
        }
        gui.drawString(font, trim(status, Math.max(12, contentWidth / 6)), contentX, footerY - 14,
                statusError ? 0xFFFF7777 : 0xFF88DD88, false);
    }

    @Override
    public void renderBackground(GuiGraphics gui, int mouseX, int mouseY, float partialTick) {
        // render() owns the complete editor backdrop.
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
