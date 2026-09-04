package com.fnfmod.client.gui.machine;

import com.fnfmod.block.FunkinMachineBlockEntity;
import com.fnfmod.client.gui.BlockifiedScreenStyle;
import com.fnfmod.client.gui.TextInputAwareScreen;
import com.fnfmod.machine.MachineDefinition;
import com.fnfmod.machine.MachineLibrary;
import com.fnfmod.net.FnfPayloads;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Host-side machine profile editor with live preview and structured machine.json controls. */
public final class MachineEditorScreen extends Screen implements TextInputAwareScreen {
    private enum Tab { APPEARANCE, MENU, BEHAVIOR, FILES }
    private enum BehaviorType { STRING, NUMBER, BOOLEAN }

    private static final String[] FACES = {"all", "side", "front", "back", "left", "right", "top", "bottom"};
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final BlockPos pos;
    private String originalId;
    private String selectedId;
    private String loadedDraftId = "";
    private String status = "Select a profile. Changes preview on the placed machine.";
    private boolean statusError;
    private Tab tab = Tab.APPEARANCE;
    private EditBox newId;
    private String newIdValue = "new-machine";
    private EditBox displayNameField;
    private EditBox menuField;
    private EditBox textureField;
    private EditBox behaviorKeyField;
    private EditBox behaviorValueField;
    private String draftDisplayName = "";
    private String draftMenu = "menu.lua";
    private final LinkedHashMap<String, String> draftTextures = new LinkedHashMap<>();
    private JsonObject draftBehavior = new JsonObject();
    private int selectedFace;
    private String selectedBehaviorKey = "";
    private String pendingBehaviorValue = "";
    private BehaviorType behaviorType = BehaviorType.STRING;
    private int scroll;
    private int panelX, panelY, panelWidth, panelHeight;
    private int listX, listWidth, contentX, contentWidth, contentTop, footerY, visibleProfiles;

    public MachineEditorScreen(BlockPos pos, String profileId) {
        super(Component.literal("Funkin' Designer"));
        this.pos = pos;
        this.originalId = MachineLibrary.canonical(profileId);
        this.selectedId = this.originalId;
    }

    @Override protected void init() {
        if (!loadedDraftId.equals(selectedId)) loadDraft(MachineLibrary.get(selectedId));
        rebuild();
    }

    private void rebuild() {
        if (newId != null) newIdValue = newId.getValue();
        clearWidgets();
        displayNameField = menuField = textureField = behaviorKeyField = behaviorValueField = null;
        panelWidth = Math.max(360, Math.min(680, width - 20));
        panelHeight = Math.max(250, Math.min(390, height - 20));
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
            addRenderableWidget(Button.builder(Component.literal(title(value)), button -> {
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
                loadDraft(profile);
                preview(selectedId);
                rebuild();
            }).bounds(listX, profileTop + i * 22, listWidth, 20).build());
            profileButton.active = !selected;
        }
        if (profiles.size() > visibleProfiles) {
            addRenderableWidget(Button.builder(Component.literal("^"), button -> {
                scroll = Math.max(0, scroll - 1); rebuild();
            }).bounds(listX + listWidth - 42, contentTop, 20, 20).build());
            addRenderableWidget(Button.builder(Component.literal("v"), button -> {
                scroll = Math.min(profiles.size() - visibleProfiles, scroll + 1); rebuild();
            }).bounds(listX + listWidth - 20, contentTop, 20, 20).build());
        }

        MachineDefinition definition = MachineLibrary.get(selectedId);
        boolean editable = definition != null && !definition.builtIn() && definition.root() != null;
        buildTabControls(editable);

        int footerX = panelX + 14;
        int footerWidth = panelWidth - 28;
        int actionWidth = Math.max(44, Math.min(66, (footerWidth - 112) / 4));
        addRenderableWidget(Button.builder(Component.literal("Apply"), button ->
                send((byte) 0, selectedId)).bounds(footerX, footerY, actionWidth, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Reload"), button -> {
            MachineLibrary.rescan(); send((byte) 2, "");
        }).bounds(footerX + actionWidth + 4, footerY, actionWidth, 20).build());

        int saveAsWidth = Math.max(52, Math.min(70, footerWidth / 5));
        int fieldX = footerX + actionWidth * 2 + 8;
        int fieldWidth = Math.max(54, footerX + footerWidth - saveAsWidth - 4 - fieldX);
        newId = new EditBox(font, fieldX, footerY, fieldWidth, 20, Component.literal("New profile ID"));
        newId.setHint(Component.literal("new profile id"));
        newId.setValue(newIdValue);
        newId.setMaxLength(64);
        addRenderableWidget(newId);
        addRenderableWidget(Button.builder(Component.literal("Save As"), button -> {
            newIdValue = newId.getValue(); send((byte) 1, newIdValue);
        }).bounds(footerX + footerWidth - saveAsWidth, footerY, saveAsWidth, 20).build());
    }

    private void buildTabControls(boolean editable) {
        int x = contentX + 9, w = contentWidth - 18, y = contentTop + 27;
        switch (tab) {
            case APPEARANCE -> {
                displayNameField = textField(x + 82, y, Math.max(50, w - 82), "Display name",
                        draftDisplayName, 128, value -> draftDisplayName = value, editable);
                int row = y + 26;
                addRenderableWidget(Button.builder(Component.literal("Face: " + FACES[selectedFace]), button -> {
                    selectedFace = (selectedFace + 1) % FACES.length; rebuild();
                }).bounds(x, row, 78, 20).build()).active = editable;
                String face = FACES[selectedFace];
                textureField = textField(x + 82, row, Math.max(50, w - 82), "textures/file.png",
                        draftTextures.getOrDefault(face, ""), 256, value -> {
                            if (value.isBlank()) draftTextures.remove(face); else draftTextures.put(face, value);
                        }, editable);
                addRenderableWidget(Button.builder(Component.literal("Clear selected face"), button -> {
                    draftTextures.remove(FACES[selectedFace]); rebuild();
                }).bounds(x, row + 26, w, 20).build()).active = editable
                        && draftTextures.containsKey(FACES[selectedFace]);
            }
            case MENU -> menuField = textField(x + 72, y, Math.max(50, w - 72), "menu.lua",
                    draftMenu, 256, value -> draftMenu = value, editable);
            case BEHAVIOR -> buildBehaviorControls(x, y, w, editable);
            case FILES -> { }
        }
        Button saveProfile = addRenderableWidget(Button.builder(Component.literal("Save Profile Changes"), button ->
                send((byte) 3, profileJson())).bounds(x, footerY - 25, w, 20).build());
        saveProfile.active = editable;
    }

    private void buildBehaviorControls(int x, int y, int w, boolean editable) {
        List<String> keys = primitiveBehaviorKeys();
        String selected = selectedBehaviorKey.isBlank() ? "+ New property" : selectedBehaviorKey;
        addRenderableWidget(Button.builder(Component.literal("Property: " + selected), button -> {
            int index = selectedBehaviorKey.isBlank() ? -1 : keys.indexOf(selectedBehaviorKey);
            index++;
            selectedBehaviorKey = index >= keys.size() ? "" : keys.get(index);
            loadBehaviorEditorValue(); rebuild();
        }).bounds(x, y, w, 20).build()).active = editable;

        int half = (w - 4) / 2;
        behaviorKeyField = textField(x, y + 26, half, "property name", selectedBehaviorKey,
                64, value -> selectedBehaviorKey = value, editable);
        addRenderableWidget(Button.builder(Component.literal("Type: " + behaviorType.name().toLowerCase(Locale.ROOT)), button -> {
            behaviorType = BehaviorType.values()[(behaviorType.ordinal() + 1) % BehaviorType.values().length];
            rebuild();
        }).bounds(x + half + 4, y + 26, w - half - 4, 20).build()).active = editable;
        behaviorValueField = textField(x, y + 52, w, "property value", pendingBehaviorValue,
                256, value -> pendingBehaviorValue = value, editable);
        addRenderableWidget(Button.builder(Component.literal("Add / Update"), button -> saveBehaviorProperty())
                .bounds(x, y + 78, half, 20).build()).active = editable;
        addRenderableWidget(Button.builder(Component.literal("Remove"), button -> {
            if (!selectedBehaviorKey.isBlank()) draftBehavior.remove(selectedBehaviorKey);
            selectedBehaviorKey = ""; rebuild();
        }).bounds(x + half + 4, y + 78, w - half - 4, 20).build()).active = editable
                && !selectedBehaviorKey.isBlank() && draftBehavior.has(selectedBehaviorKey);
    }

    private EditBox textField(int x, int y, int w, String hint, String value, int max,
                              java.util.function.Consumer<String> responder, boolean editable) {
        EditBox field = new EditBox(font, x, y, w, 20, Component.literal(hint));
        field.setHint(Component.literal(hint));
        field.setMaxLength(max);
        field.setValue(value == null ? "" : value);
        field.setResponder(responder);
        field.setEditable(editable);
        addRenderableWidget(field);
        return field;
    }

    private void saveBehaviorProperty() {
        String key = behaviorKeyField == null ? selectedBehaviorKey : behaviorKeyField.getValue().trim();
        String value = behaviorValueField == null ? "" : behaviorValueField.getValue().trim();
        if (!key.matches("[A-Za-z0-9_.-]{1,64}")) {
            status = "Behavior names use letters, numbers, '.', '_' or '-'."; statusError = true; return;
        }
        try {
            switch (behaviorType) {
                case STRING -> draftBehavior.addProperty(key, value);
                case NUMBER -> draftBehavior.addProperty(key, Double.parseDouble(value));
                case BOOLEAN -> {
                    if (!value.equalsIgnoreCase("true") && !value.equalsIgnoreCase("false"))
                        throw new IllegalArgumentException("Boolean values must be true or false.");
                    draftBehavior.addProperty(key, Boolean.parseBoolean(value));
                }
            }
            selectedBehaviorKey = key;
            pendingBehaviorValue = value;
            status = "Behavior property staged. Save Profile Changes to write it.";
            statusError = false; rebuild();
        } catch (Exception error) { status = error.getMessage(); statusError = true; }
    }

    private List<String> primitiveBehaviorKeys() {
        ArrayList<String> keys = new ArrayList<>();
        for (Map.Entry<String, JsonElement> entry : draftBehavior.entrySet())
            if (entry.getValue().isJsonPrimitive()) keys.add(entry.getKey());
        return keys;
    }

    private String behaviorValue(String key) {
        if (key == null || key.isBlank() || !draftBehavior.has(key)
                || !draftBehavior.get(key).isJsonPrimitive()) return "";
        return draftBehavior.get(key).getAsString();
    }

    private void loadBehaviorEditorValue() {
        if (selectedBehaviorKey.isBlank() || !draftBehavior.has(selectedBehaviorKey)) {
            behaviorType = BehaviorType.STRING;
            pendingBehaviorValue = "";
            return;
        }
        var primitive = draftBehavior.getAsJsonPrimitive(selectedBehaviorKey);
        pendingBehaviorValue = primitive.getAsString();
        behaviorType = primitive.isBoolean() ? BehaviorType.BOOLEAN
                : primitive.isNumber() ? BehaviorType.NUMBER : BehaviorType.STRING;
    }

    private void loadDraft(MachineDefinition definition) {
        loadedDraftId = definition == null ? "" : definition.id();
        draftDisplayName = definition == null ? "" : definition.displayName();
        draftMenu = definition == null || definition.menuScript() == null || definition.root() == null
                ? "menu.lua" : relative(definition.root(), definition.menuScript());
        draftTextures.clear();
        if (definition != null && definition.root() != null)
            definition.textures().forEach((face, path) -> draftTextures.put(face, relative(definition.root(), path)));
        draftBehavior = definition == null ? new JsonObject() : definition.behavior().deepCopy();
        selectedBehaviorKey = primitiveBehaviorKeys().stream().findFirst().orElse("");
        loadBehaviorEditorValue();
    }

    private String profileJson() {
        JsonObject output = new JsonObject();
        output.addProperty("profileId", selectedId);
        output.addProperty("displayName", draftDisplayName);
        output.addProperty("menu", draftMenu);
        String all = draftTextures.get("all");
        if (all != null && !all.isBlank()) output.addProperty("texture", all);
        JsonObject faces = new JsonObject();
        draftTextures.forEach((face, path) -> {
            if (!face.equals("all") && path != null && !path.isBlank()) faces.addProperty(face, path);
        });
        output.add("textures", faces);
        output.add("behavior", draftBehavior.deepCopy());
        return GSON.toJson(output);
    }

    private static String relative(Path root, Path file) {
        try { return root.toAbsolutePath().normalize().relativize(file.toAbsolutePath().normalize())
                .toString().replace('\\', '/'); }
        catch (Exception ignored) { return file == null ? "" : file.toString(); }
    }

    private void send(byte action, String value) {
        PacketDistributor.sendToServer(new FnfPayloads.MachineEditC2S(pos, action, value == null ? "" : value));
    }
    private void preview(String id) {
        if (minecraft != null && minecraft.level != null
                && minecraft.level.getBlockEntity(pos) instanceof FunkinMachineBlockEntity machine) machine.setProfileId(id);
    }

    public void onServerResult(boolean success, String message, String profileId, boolean refresh) {
        status = message; statusError = !success;
        if (refresh) MachineLibrary.rescan();
        if (success) {
            originalId = MachineLibrary.canonical(profileId); selectedId = originalId;
            preview(originalId); loadDraft(MachineLibrary.get(originalId));
        }
        rebuild();
    }

    @Override public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (mouseX >= listX && mouseX <= listX + listWidth && mouseY >= contentTop && mouseY <= footerY - 6) {
            scroll = Math.max(0, scroll - (int) Math.signum(scrollY)); rebuild(); return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override public void render(GuiGraphics gui, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(gui, mouseX, mouseY, partialTick);
        BlockifiedScreenStyle.backdrop(gui, width, height);
        BlockifiedScreenStyle.panel(gui, panelX, panelY, panelWidth, panelHeight);
        BlockifiedScreenStyle.header(gui, font, panelX + 16, panelY + 13, "FUNKIN' DESIGNER", "Machine profiles",
                "Edit profile fields, preview the machine, then save without leaving Minecraft.");
        BlockifiedScreenStyle.inner(gui, listX, contentTop - 4, listWidth, footerY - contentTop - 4);
        BlockifiedScreenStyle.inner(gui, contentX, contentTop - 4, contentWidth, footerY - contentTop - 4);
        BlockifiedScreenStyle.section(gui, font, "PROFILES", listX + 8, contentTop - 14);
        BlockifiedScreenStyle.section(gui, font, "PROFILE DETAILS", contentX + 8, contentTop - 14);
        super.render(gui, mouseX, mouseY, partialTick);
        MachineDefinition definition = MachineLibrary.get(selectedId);
        int x = contentX + 9, y = contentTop + 8;
        gui.drawString(font, "Profile: " + selectedId, x, y, BlockifiedScreenStyle.TEXT, false);
        switch (tab) {
            case APPEARANCE -> { line(gui, x, y + 24, "Display name"); line(gui, x, y + 76, "Relative PNG paths inside this profile."); }
            case MENU -> { line(gui, x, y + 24, "Menu Lua"); line(gui, x, y + 50, "Relative path ending in .lua."); }
            case BEHAVIOR -> line(gui, x, y + 128, "Properties support strings, numbers, and booleans.");
            case FILES -> {
                line(gui, x, y + 24, "Profile folder:"); line(gui, x, y + 38, path(definition.root()));
                line(gui, x, y + 66, "Save Profile Changes writes machine.json.");
                line(gui, x, y + 80, "Lua and PNG contents remain separate files.");
            }
        }
        gui.drawString(font, trim(status, Math.max(16, contentWidth / 6)), contentX, footerY - 39,
                statusError ? 0xFFFF7777 : 0xFF88DD88, false);
    }

    @Override public void renderBackground(GuiGraphics gui, int mouseX, int mouseY, float partialTick) { }
    private void line(GuiGraphics gui, int x, int y, String text) {
        gui.drawString(font, trim(text, Math.max(18, contentWidth / 6)), x, y, 0xFFCCCCCC, false);
    }
    private static String path(Path path) { return path == null ? "built-in" : path.toString(); }
    private static String trim(String value, int max) {
        if (value == null) return "";
        return value.length() <= max ? value : value.substring(0, Math.max(0, max - 3)) + "...";
    }
    private static String title(Tab tab) {
        String lower = tab.name().toLowerCase(Locale.ROOT);
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }

    @Override public boolean isTextInputActive() {
        return (newId != null && newId.isFocused()) || (displayNameField != null && displayNameField.isFocused())
                || (menuField != null && menuField.isFocused()) || (textureField != null && textureField.isFocused())
                || (behaviorKeyField != null && behaviorKeyField.isFocused())
                || (behaviorValueField != null && behaviorValueField.isFocused());
    }
    @Override public void onClose() { preview(originalId); super.onClose(); }
    @Override public boolean isPauseScreen() { return false; }
}
