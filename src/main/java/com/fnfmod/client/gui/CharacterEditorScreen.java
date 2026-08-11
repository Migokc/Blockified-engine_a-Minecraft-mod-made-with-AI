package com.fnfmod.client.gui;

import com.mojang.blaze3d.systems.RenderSystem;
import com.fnfmod.client.FnfKeys;
import com.fnfmod.client.anim.CharacterDefinitionFile;
import com.fnfmod.client.anim.CharacterAnimations;
import com.fnfmod.client.math.Easing;
import com.fnfmod.character.CharacterDefinitionPaths;
import com.fnfmod.song.SongLibrary;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.lwjgl.glfw.GLFW;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Visual editor for named Blockified character JSON definitions and BBS states. */
public final class CharacterEditorScreen extends Screen implements TextInputAwareScreen {
    private static final int PANEL = 0xE0181820;
    private static final int FIELD_H = 18;
    private static final int DIALOG_ROW_HEIGHT = 18;
    private static final long DIALOG_SCROLL_TWEEN_NANOS = 200_000_000L;
    private static final long DIALOG_PREVIEW_BEAT_NANOS = 500_000_000L;
    private static final String CURRENT_FORM_OPTION = "<Current BBS form>";
    private static final String INHERIT_FORM_OPTION = "<Inherit player form>";

    private enum EditorTab {
        CHARACTER("Character"), MODEL("Model"), ANIMATIONS("Animations");

        final String label;
        EditorTab(String label) { this.label = label; }
    }

    private enum SelectionKind { CHARACTER, ANIMATION, FORM }

    private final Screen parent;
    private final List<String> actions = new ArrayList<>(List.of(CharacterAnimations.ACTIONS));
    private final List<String> sets = new ArrayList<>();
    private final List<String> forms = new ArrayList<>();

    private CharacterDefinitionFile playerDefinition;
    private CharacterDefinitionFile opponentDefinition;
    private int setIndex;
    private String currentSetName = "default";
    private int actionIndex;
    private int formIndex = -1;
    private boolean opponent;
    private boolean loadingFields;
    private boolean playerDirty;
    private boolean opponentDirty;
    private String loadedPlayerForm = "";
    private String loadedOpponentForm = "";
    private boolean playerFormChanged;
    private boolean opponentFormChanged;
    private boolean loadedFormPrepared;
    private String status = "";
    private String draftSetName = "default";
    private EditorTab activeTab = EditorTab.CHARACTER;

    private EditBox setName;
    private EditBox icon;
    private EditBox vocalsFile;
    private EditBox rotation;
    private EditBox cameraX;
    private EditBox cameraY;
    private EditBox actionName;
    private EditBox state;
    private EditBox actionCameraX;
    private EditBox actionCameraY;
    private Button roleButton;
    private Button formButton;
    private Button actionButton;
    private Button loopIdleButton;
    private Button colorButton;
    private boolean colorPickerOpen;
    private int colorPickerOriginal;
    private int colorPickerValue;
    private float colorHue;
    private float colorSat;
    private float colorBri;
    private int colorDrag;
    private EditBox colorHexField;
    private boolean updatingColorHex;
    private boolean loadDialogOpen;
    private SelectionKind loadDialogKind = SelectionKind.CHARACTER;
    private EditBox loadSearchField;
    private int loadDialogSelected;
    private double loadDialogScrollPx;
    private double loadDialogScrollTargetPx;
    private double loadDialogScrollFromPx;
    private long loadDialogScrollTweenStart;
    private boolean loadDialogScrollTweenActive;
    private String dialogSelectedAnimation = "";
    private String dialogPreviewAnimation = "";
    private int dialogIdleBeat;
    private long dialogNextIdlePreview;
    private long dialogReturnToSelection;

    public CharacterEditorScreen(Screen parent) {
        super(Component.literal("Blockified Character Editor"));
        this.parent = parent;
        refreshChoices();
        loadSet(0);
    }

    @Override
    protected void init() {
        clearFieldReferences();
        int x = optionsX();
        int panelWidth = optionsWidth();
        int tabWidth = Math.max(1, (panelWidth - 4) / EditorTab.values().length);
        int tabX = x;
        for (EditorTab tab : EditorTab.values()) {
            Button button = addRenderableWidget(Button.builder(Component.literal(tab.label), ignored -> switchTab(tab))
                    .bounds(tabX, 26, tabWidth, 20).build());
            button.active = tab != activeTab;
            tabX += tabWidth + 2;
        }

        roleButton = addRenderableWidget(Button.builder(roleLabel(), button -> switchRole())
                .bounds(x, 48, panelWidth, 20).build());
        switch (activeTab) {
            case CHARACTER -> buildCharacterTab(x, panelWidth);
            case MODEL -> buildModelTab(x, panelWidth);
            case ANIMATIONS -> buildAnimationsTab(x, panelWidth);
        }

        int actionY = height - 26;
        int actionWidth = Math.max(1, (panelWidth - 6) / 4);
        addRenderableWidget(Button.builder(Component.literal("Load..."), button -> openLoadDialog())
                .bounds(x, actionY, actionWidth, 20).build());
        addRenderableWidget(Button.builder(Component.literal("New"), button -> newSet())
                .bounds(x + actionWidth + 2, actionY, actionWidth, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Save"), button -> save())
                .bounds(x + (actionWidth + 2) * 2, actionY, actionWidth, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Back"), button -> onClose())
                .bounds(x + (actionWidth + 2) * 3, actionY,
                        panelWidth - (actionWidth + 2) * 3, 20).build());
        fillFields();
        prepareLoadedForm();
    }

    private void buildCharacterTab(int x, int panelWidth) {
        setName = edit(x, 84, panelWidth, value -> { draftSetName = value; markDirty(); });
        icon = edit(x, 116, panelWidth, value -> { if (!loadingFields) current().icon = value.trim(); markDirty(); });
        vocalsFile = edit(x, 148, panelWidth,
                value -> { if (!loadingFields) current().vocalsFile = value.trim(); markDirty(); });
        colorButton = addRenderableWidget(Button.builder(Component.literal(colorLabel()), button -> chooseColor())
                .bounds(x, 172, panelWidth, 20).build());
    }

    private void buildModelTab(int x, int panelWidth) {
        formButton = addRenderableWidget(Button.builder(formLabel(), button -> openSelectionDialog(SelectionKind.FORM))
                .bounds(x, 84, panelWidth, 20).build());
        rotation = edit(x, 118, panelWidth,
                value -> { if (!loadingFields) current().rotation = number(value); markDirty(); });
        int half = Math.max(24, (panelWidth - 4) / 2);
        cameraX = edit(x, 150, half,
                value -> { if (!loadingFields) current().cameraX = number(value); markDirty(); });
        cameraY = edit(x + half + 4, 150, panelWidth - half - 4,
                value -> { if (!loadingFields) current().cameraY = number(value); markDirty(); });
        addRenderableWidget(Button.builder(Component.literal("Preview State"), button -> preview())
                .bounds(x, 174, panelWidth, 20).build());
    }

    private void buildAnimationsTab(int x, int panelWidth) {
        actionButton = addRenderableWidget(Button.builder(actionLabel(), button -> openSelectionDialog(SelectionKind.ANIMATION))
                .bounds(x, 74, panelWidth, 20).build());
        int half = Math.max(24, (panelWidth - 4) / 2);
        actionName = edit(x, 110, half, value -> markDirty());
        state = edit(x + half + 4, 110, panelWidth - half - 4,
                value -> { if (!loadingFields) current().action(currentActionName()).state = value.trim(); markDirty(); });
        actionCameraX = edit(x, 144, half,
                value -> { if (!loadingFields) current().action(currentActionName()).cameraX = number(value); markDirty(); });
        actionCameraY = edit(x + half + 4, 144, panelWidth - half - 4,
                value -> { if (!loadingFields) current().action(currentActionName()).cameraY = number(value); markDirty(); });
        int small = Math.max(20, (panelWidth - 104) / 2);
        addRenderableWidget(Button.builder(Component.literal("+ Add"), button -> addCustomAnimation())
                .bounds(x, 168, small, 20).build());
        addRenderableWidget(Button.builder(Component.literal("- Remove"), button -> removeCustomAnimation())
                .bounds(x + small + 2, 168, small, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Preview"), button -> preview())
                .bounds(x + small * 2 + 4, 168, panelWidth - small * 2 - 4, 20).build());
        loopIdleButton = addRenderableWidget(Button.builder(loopIdleLabel(), button -> toggleLoopIdle())
                .bounds(x, 192, panelWidth, 20).build());
    }

    private void switchTab(EditorTab tab) {
        if (tab == activeTab) return;
        captureFields();
        activeTab = tab;
        rebuildUi();
    }

    private void rebuildUi() {
        clearWidgets();
        init();
    }

    private void clearFieldReferences() {
        setName = icon = vocalsFile = rotation = cameraX = cameraY = null;
        actionName = state = actionCameraX = actionCameraY = null;
        roleButton = formButton = actionButton = loopIdleButton = colorButton = null;
    }

    private EditBox edit(int x, int y, int width, java.util.function.Consumer<String> responder) {
        EditBox box = addRenderableWidget(new EditBox(font, x, y, width, FIELD_H, Component.empty()));
        box.setMaxLength(256);
        box.setResponder(responder);
        return box;
    }

    private void refreshChoices() {
        String selected = sets.isEmpty() ? "default" : sets.get(Mth.clamp(setIndex, 0, sets.size() - 1));
        sets.clear();
        sets.add("default");
        for (String name : CharacterDefinitionPaths.selectableGlobalNames()) {
            if (indexOfIgnoreCase(sets, name) < 0) sets.add(name);
        }
        setIndex = Math.max(0, indexOfIgnoreCase(sets, selected));

        forms.clear();
        forms.addAll(CharacterAnimations.listBbsForms());
        addFormChoice(playerDefinition == null ? "" : playerDefinition.form);
        addFormChoice(opponentDefinition == null ? "" : opponentDefinition.form);
    }

    private void addFormChoice(String form) {
        if (form != null && !form.isBlank() && indexOfIgnoreCase(forms, form) < 0) forms.add(form);
    }

    private void loadSet(int index) {
        if (sets.isEmpty()) sets.add("default");
        setIndex = Math.floorMod(index, sets.size());
        String name = sets.get(setIndex);
        currentSetName = name;
        draftSetName = name;
        Path playerFile = definitionFile(name, false);
        Path opponentFile = definitionFile(name, true);
        playerDefinition = CharacterDefinitionFile.load(playerFile);
        opponentDefinition = CharacterDefinitionFile.load(opponentFile);
        loadedPlayerForm = playerDefinition.form;
        loadedOpponentForm = opponentDefinition.form;
        playerFormChanged = opponentFormChanged = false;
        loadedFormPrepared = false;
        addFormChoice(loadedPlayerForm);
        addFormChoice(loadedOpponentForm);
        rebuildActions("idle");
        playerDirty = opponentDirty = false;
        status = "Editing " + playerFile.getFileName();
        fillFields();
        prepareLoadedForm();
    }

    private Path definitionFile(String rawName, boolean opponent) {
        String name = safeName(rawName);
        if (CharacterAnimations.DEFAULT_SET.equalsIgnoreCase(name)) {
            Path canonical = canonicalFile(name, opponent);
            if (Files.isRegularFile(canonical)) return canonical;
            Path legacy = SongLibrary.animationsDir().resolve(CharacterAnimations.DEFAULT_SET)
                    .resolve(opponent ? "character-opp.json" : "character.json");
            return Files.isRegularFile(legacy) ? legacy : canonical;
        }
        Path existing = CharacterDefinitionPaths.globalCharacterJsonExact(name, opponent);
        if (existing != null) return existing;
        String suffix = opponent ? "-opp" : "";
        return SongLibrary.animationsDir().resolve(name + suffix + ".json").normalize();
    }

    private void newSet() {
        captureFields();
        String name = uniqueName("new-character");
        currentSetName = name;
        draftSetName = name;
        playerDefinition = CharacterDefinitionFile.load(definitionFile(name, false));
        opponentDefinition = CharacterDefinitionFile.load(definitionFile(name, true));
        rebuildActions("idle");
        playerDirty = true;
        opponentDirty = false;
        status = "New character: choose a BBS form and save";
        opponent = false;
        activeTab = EditorTab.CHARACTER;
        rebuildUi();
    }

    private void switchRole() {
        String selected = currentActionName();
        captureFields();
        opponent = !opponent;
        loadedFormPrepared = false;
        rebuildActions(selected);
        fillFields();
        prepareLoadedForm();
    }

    private void addCustomAnimation() {
        captureFields();
        String created = current().addAnimation("custom-animation");
        rebuildActions(created);
        markDirty();
        fillActionFields();
        actionName.setFocused(true);
        status = "Added custom animation; enter its event name and BBS state";
    }

    private void removeCustomAnimation() {
        captureFields();
        String selected = currentActionName();
        if (!current().removeAnimation(selected)) {
            status = "Built-in animation slots cannot be removed";
            return;
        }
        int previous = Math.max(0, actionIndex - 1);
        rebuildActions(null);
        actionIndex = Math.min(previous, actions.size() - 1);
        markDirty();
        fillActionFields();
        status = "Removed custom animation " + selected;
    }

    private void rebuildActions(String preferred) {
        actions.clear();
        actions.addAll(List.of(CharacterAnimations.ACTIONS));
        if (current() != null) {
            for (String name : current().animationNames()) {
                if (actions.stream().noneMatch(existing -> existing.equalsIgnoreCase(name))) actions.add(name);
            }
        }
        int preferredIndex = indexOfIgnoreCase(actions, preferred);
        actionIndex = preferredIndex >= 0 ? preferredIndex
                : Mth.clamp(actionIndex, 0, Math.max(0, actions.size() - 1));
    }

    private void fillFields() {
        if (current() == null) return;
        loadingFields = true;
        CharacterDefinitionFile definition = current();
        if (setName != null) setName.setValue(draftSetName);
        if (icon != null) icon.setValue(definition.icon);
        if (vocalsFile != null) vocalsFile.setValue(definition.vocalsFile);
        if (rotation != null) rotation.setValue(decimal(definition.rotation));
        if (cameraX != null) cameraX.setValue(decimal(definition.cameraX));
        if (cameraY != null) cameraY.setValue(decimal(definition.cameraY));
        formIndex = indexOfIgnoreCase(forms, definition.form);
        fillActionFields();
        loadingFields = false;
        if (roleButton != null) roleButton.setMessage(roleLabel());
        if (formButton != null) formButton.setMessage(formLabel());
        if (actionButton != null) actionButton.setMessage(actionLabel());
        if (loopIdleButton != null) loopIdleButton.setMessage(loopIdleLabel());
        if (colorButton != null) colorButton.setMessage(Component.literal(colorLabel()));
    }

    private void fillActionFields() {
        String name = currentActionName();
        CharacterDefinitionFile.Action action = current().action(name);
        loadingFields = true;
        if (actionName != null) {
            actionName.setValue(name);
            actionName.setEditable(!current().isPreset(name));
        }
        if (state != null) state.setValue(action.state);
        if (actionCameraX != null) actionCameraX.setValue(decimal(action.cameraX));
        if (actionCameraY != null) actionCameraY.setValue(decimal(action.cameraY));
        loadingFields = false;
        if (actionButton != null) actionButton.setMessage(actionLabel());
    }

    private void captureFields() {
        captureFields(true);
    }

    private void captureFields(boolean commitAnimationName) {
        CharacterDefinitionFile definition = current();
        if (definition == null) return;
        if (setName != null) draftSetName = setName.getValue();
        if (icon != null) definition.icon = icon.getValue().trim();
        if (vocalsFile != null) definition.vocalsFile = vocalsFile.getValue().trim();
        if (rotation != null) definition.rotation = number(rotation.getValue());
        if (cameraX != null) definition.cameraX = number(cameraX.getValue());
        if (cameraY != null) definition.cameraY = number(cameraY.getValue());
        String selectedName = currentActionName();
        CharacterDefinitionFile.Action action = definition.action(selectedName);
        if (state != null) action.state = state.getValue().trim();
        if (actionCameraX != null) action.cameraX = number(actionCameraX.getValue());
        if (actionCameraY != null) action.cameraY = number(actionCameraY.getValue());
        if (commitAnimationName && actionName != null && !definition.isPreset(selectedName)) {
            String requestedName = actionName.getValue().trim();
            String renamed = definition.renameAnimation(selectedName, requestedName);
            if (renamed != null && !renamed.equals(selectedName)) {
                actions.set(actionIndex, renamed);
                selectedName = renamed;
            } else if (!requestedName.isBlank() && !requestedName.equalsIgnoreCase(selectedName)) {
                status = "Animation name is reserved or already exists";
                loadingFields = true;
                actionName.setValue(selectedName);
                loadingFields = false;
            }
            if (actionButton != null) actionButton.setMessage(actionLabel());
        }
    }

    private void preview() {
        captureFields();
        previewAction(currentActionName(), true);
    }

    private boolean previewAction(String selectedAction, boolean updateStatus) {
        if (minecraft == null || minecraft.player == null) return false;
        CharacterDefinitionFile definition = current();
        CharacterDefinitionFile fallback = playerDefinition;
        String form = definition.form.isBlank() && opponent ? fallback.form : definition.form;
        CharacterDefinitionFile.Action action = definition.action(selectedAction);
        String stateName = action.state;
        if (stateName.isBlank() && opponent) {
            CharacterDefinitionFile.Action inherited = fallback.findAction(selectedAction);
            if (inherited != null) stateName = inherited.state;
        }
        if (stateName.isBlank()) stateName = selectedAction;
        boolean played = CharacterAnimations.preview(minecraft.player, form,
                bundledDefinitionForCurrentRole(), stateName);
        if (updateStatus) {
            status = played ? "Previewing " + stateName : "State not found on the selected BBS form";
        }
        return played;
    }

    private Path bundledDefinitionForCurrentRole() {
        if (!opponent) return playerFormChanged ? null : playerDefinition.file();
        if (current().form.isBlank()) return playerFormChanged ? null : playerDefinition.file();
        return opponentFormChanged ? null : opponentDefinition.file();
    }

    private void prepareLoadedForm() {
        if (loadedFormPrepared || minecraft == null || minecraft.player == null || current() == null) return;
        String form = current().form;
        if (form.isBlank() && opponent) form = playerDefinition.form;
        if (form.isBlank()) return;
        loadedFormPrepared = CharacterAnimations.preparePreview(
                minecraft.player, form, bundledDefinitionForCurrentRole());
    }

    private void save() {
        captureFields();
        String name = safeName(draftSetName);
        if (name.isBlank()) {
            status = "Enter a character name";
            return;
        }
        try {
            boolean bundledPlayer = false;
            boolean bundledOpponent = false;
            boolean custom = !CharacterAnimations.DEFAULT_SET.equalsIgnoreCase(name);
            boolean hasOpponent = opponentIsUsed();

            if (custom) {
                Path folder = SongLibrary.animationsDir().resolve(name).normalize();
                Files.createDirectories(folder);
                playerDefinition.saveAs(folder, false);
                if (hasOpponent) opponentDefinition.saveAs(folder, true);
                else {
                    Files.deleteIfExists(folder.resolve("character-opp.json"));
                    Files.deleteIfExists(folder.resolve("character-opp.form.json"));
                }
                Files.deleteIfExists(canonicalFile(name, false));
                Files.deleteIfExists(canonicalFile(name, true));

                if (!playerDefinition.form.isBlank()) {
                    bundledPlayer = CharacterAnimations.bundleForm(playerDefinition.form, folder, "character");
                }
                if (hasOpponent && !opponentDefinition.form.isBlank()
                        && !opponentDefinition.form.equalsIgnoreCase(playerDefinition.form)) {
                    bundledOpponent = CharacterAnimations.bundleForm(
                            opponentDefinition.form, folder, "character-opp");
                }
            } else {
                playerDefinition.saveAs(canonicalFile(name, false));
                if (hasOpponent) opponentDefinition.saveAs(canonicalFile(name, true));
                else Files.deleteIfExists(canonicalFile(name, true));
            }

            CharacterAnimations.reload();
            refreshChoices();
            setIndex = Math.max(0, indexOfIgnoreCase(sets, name));
            loadSet(setIndex);
            if (!custom) {
                status = "Saved default character JSON (default assets are not replaced)";
            } else if (playerDefinition.form.isBlank()) {
                status = "Saved animations/" + name + "; no player BBS form was selected to bundle";
            } else if (!bundledPlayer) {
                status = "Saved animations/" + name + "; player BBS assets were unavailable";
            } else if (hasOpponent && !opponentDefinition.form.isBlank()
                    && !opponentDefinition.form.equalsIgnoreCase(playerDefinition.form)
                    && !bundledOpponent) {
                status = "Saved and bundled player; opponent BBS assets were unavailable";
            } else {
                status = "Saved self-contained character to animations/" + name;
            }
        } catch (Exception error) {
            status = "Save failed: " + error.getMessage();
        }
    }

    /**
     * Opponent data is optional. Persist it whenever it contains a meaningful
     * opponent override, regardless of whether character-opp.json existed when
     * the editor was opened.
     */
    private boolean opponentIsUsed() {
        if (opponentDefinition == null) return false;
        if (!opponentDefinition.form.isBlank() || !opponentDefinition.icon.isBlank()
                || !opponentDefinition.vocalsFile.isBlank() || opponentDefinition.healthColor >= 0
                || opponentDefinition.loopIdle || nonZero(opponentDefinition.rotation)
                || nonZero(opponentDefinition.cameraX) || nonZero(opponentDefinition.cameraY)) {
            return true;
        }
        for (String name : opponentDefinition.animationNames()) {
            CharacterDefinitionFile.Action action = opponentDefinition.findAction(name);
            if (action != null && (!action.state.isBlank()
                    || nonZero(action.cameraX) || nonZero(action.cameraY))) return true;
        }
        return false;
    }

    private static boolean nonZero(float value) {
        return Math.abs(value) > 0.0001F;
    }

    private void markDirty() {
        if (loadingFields) return;
        if (opponent) opponentDirty = true;
        else playerDirty = true;
    }

    private Component loopIdleLabel() {
        boolean loop = current() != null && current().loopIdle;
        return Component.literal("Idle: " + (loop ? "Loop animation" : "Bop on beat"));
    }

    private void toggleLoopIdle() {
        if (current() == null) return;
        current().loopIdle = !current().loopIdle;
        markDirty();
        if (loopIdleButton != null) loopIdleButton.setMessage(loopIdleLabel());
    }

    private String colorLabel() {
        int color = current() == null ? -1 : current().healthColor;
        return color < 0 ? "Character Color: unset" : String.format("Character Color: #%06X", color);
    }

    private void chooseColor() {
        CharacterDefinitionFile definition = current();
        if (definition == null) return;
        colorPickerOriginal = definition.healthColor;
        colorPickerValue = definition.healthColor < 0
                ? (opponent ? 0xAF66CE : 0x31B0D1) : definition.healthColor & 0xFFFFFF;
        float[] hsb = java.awt.Color.RGBtoHSB((colorPickerValue >> 16) & 255,
                (colorPickerValue >> 8) & 255, colorPickerValue & 255, null);
        colorHue = hsb[0];
        colorSat = hsb[1];
        colorBri = hsb[2];
        colorDrag = 0;
        colorHexField = new EditBox(font, 0, 0, 80, 16, Component.literal("Hex color"));
        colorHexField.setMaxLength(6);
        colorHexField.setFilter(value -> value.matches("[0-9a-fA-F]{0,6}"));
        colorHexField.setResponder(value -> {
            if (updatingColorHex || !value.matches("[0-9a-fA-F]{6}")) return;
            colorPickerValue = Integer.parseInt(value, 16);
            float[] next = java.awt.Color.RGBtoHSB((colorPickerValue >> 16) & 255,
                    (colorPickerValue >> 8) & 255, colorPickerValue & 255, null);
            colorHue = next[0]; colorSat = next[1]; colorBri = next[2];
            definition.healthColor = colorPickerValue;
        });
        updateColorHex();
        colorPickerOpen = true;
    }

    private void updateColorHex() {
        if (colorHexField == null) return;
        updatingColorHex = true;
        colorHexField.setValue(String.format(Locale.ROOT, "%06X", colorPickerValue & 0xFFFFFF));
        updatingColorHex = false;
    }

    private void updateColorPicker(double mouseX, double mouseY) {
        int x0 = colorPickerX(), y0 = colorPickerY();
        int squareX = x0 + 12, squareY = y0 + 28, squareSize = 80;
        int hueX = x0 + 12, hueY = y0 + 120, hueWidth = colorPickerWidth() - 24;
        if (colorDrag == 1) {
            colorSat = (float) Mth.clamp((mouseX - squareX) / (squareSize - 1.0), 0, 1);
            colorBri = 1f - (float) Mth.clamp((mouseY - squareY) / (squareSize - 1.0), 0, 1);
        } else if (colorDrag == 2) {
            colorHue = (float) Mth.clamp((mouseX - hueX) / (hueWidth - 1.0), 0, 1);
        }
        colorPickerValue = java.awt.Color.HSBtoRGB(colorHue, colorSat, colorBri) & 0xFFFFFF;
        current().healthColor = colorPickerValue;
        updateColorHex();
    }

    private void closeColorPicker(boolean save) {
        CharacterDefinitionFile definition = current();
        if (definition != null) {
            definition.healthColor = save ? colorPickerValue : colorPickerOriginal;
            if (save) markDirty();
        }
        colorPickerOpen = false;
        colorDrag = 0;
        colorHexField = null;
        if (colorButton != null) colorButton.setMessage(Component.literal(colorLabel()));
    }

    private int colorPickerWidth() { return Math.min(260, Math.max(220, width - 24)); }
    private int colorPickerHeight() { return 176; }
    private int colorPickerX() { return (width - colorPickerWidth()) / 2; }
    private int colorPickerY() { return Math.max(8, (height - colorPickerHeight()) / 2); }

    private CharacterDefinitionFile current() {
        return opponent ? opponentDefinition : playerDefinition;
    }

    private Component roleLabel() {
        return Component.literal(opponent ? "Opponent" : "Player");
    }

    private Component formLabel() {
        String form = current() == null ? "" : current().form;
        if (form.isBlank()) form = opponent ? "Inherit player" : "Current BBS form";
        return Component.literal(trim(form, 28));
    }

    private Component actionLabel() {
        return Component.literal(trim("Anim " + (actionIndex + 1) + "/" + Math.max(1, actions.size())
                + ": " + currentActionName(), 34));
    }

    private String currentActionName() {
        if (actions.isEmpty()) actions.addAll(List.of(CharacterAnimations.ACTIONS));
        actionIndex = Mth.clamp(actionIndex, 0, actions.size() - 1);
        return actions.get(actionIndex);
    }

    private int previewRight() {
        return Math.max(184, Math.min(width - 224, Math.round(width * 0.46f)));
    }

    private int optionsX() { return previewRight() + 8; }

    private int optionsWidth() { return Math.max(1, width - optionsX() - 8); }

    private void openLoadDialog() {
        openSelectionDialog(SelectionKind.CHARACTER);
    }

    private void openSelectionDialog(SelectionKind kind) {
        captureFields();
        if (kind == SelectionKind.CHARACTER || kind == SelectionKind.FORM) refreshChoices();
        if (kind == SelectionKind.ANIMATION) rebuildActions(currentActionName());
        loadDialogKind = kind;
        loadDialogOpen = true;
        List<String> all = filteredDialogItems("");
        String selected = switch (kind) {
            case CHARACTER -> currentSetName;
            case ANIMATION -> currentActionName();
            case FORM -> current().form.isBlank()
                    ? (opponent ? INHERIT_FORM_OPTION : CURRENT_FORM_OPTION) : current().form;
        };
        loadDialogSelected = Math.max(0, indexOfIgnoreCase(all, selected));
        resetDialogScroll(true);
        loadSearchField = new EditBox(font, 0, 0, 100, FIELD_H,
                Component.literal("Search " + dialogItemName() + "s"));
        loadSearchField.setMaxLength(128);
        loadSearchField.setResponder(value -> {
            loadDialogSelected = 0;
            resetDialogScroll(false);
        });
        setFocused(loadSearchField);
        loadSearchField.setFocused(true);
        if (kind == SelectionKind.ANIMATION && !all.isEmpty()) {
            previewDialogAnimation(all.get(loadDialogSelected), false);
        } else {
            dialogSelectedAnimation = dialogPreviewAnimation = "";
            dialogNextIdlePreview = dialogReturnToSelection = 0;
        }
    }

    private void closeLoadDialog() {
        loadDialogOpen = false;
        if (loadSearchField != null) loadSearchField.setFocused(false);
        loadSearchField = null;
        setFocused(null);
    }

    private void cancelLoadDialog() {
        boolean restoreAnimation = loadDialogKind == SelectionKind.ANIMATION;
        closeLoadDialog();
        if (restoreAnimation) previewAction(currentActionName(), true);
    }

    private List<String> dialogItems() {
        return switch (loadDialogKind) {
            case CHARACTER -> List.copyOf(sets);
            case ANIMATION -> List.copyOf(actions);
            case FORM -> {
                ArrayList<String> available = new ArrayList<>();
                available.add(opponent ? INHERIT_FORM_OPTION : CURRENT_FORM_OPTION);
                available.addAll(forms);
                yield available;
            }
        };
    }

    private List<String> filteredDialogItems(String query) {
        String wanted = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        List<String> available = dialogItems();
        if (wanted.isBlank()) return available;
        return available.stream().filter(name -> name.toLowerCase(Locale.ROOT).contains(wanted)).toList();
    }

    private List<String> filteredDialogItems() {
        return filteredDialogItems(loadSearchField == null ? "" : loadSearchField.getValue());
    }

    private String dialogItemName() {
        return switch (loadDialogKind) {
            case CHARACTER -> "character";
            case ANIMATION -> "animation";
            case FORM -> "form";
        };
    }

    private String dialogTitle() {
        return switch (loadDialogKind) {
            case CHARACTER -> "Load Character";
            case ANIMATION -> "Select Animation";
            case FORM -> "Select BBS Form";
        };
    }

    private int loadDialogWidth() {
        return loadDialogKind == SelectionKind.ANIMATION
                ? Math.min(620, Math.max(300, width - 32))
                : Math.min(380, Math.max(250, width - 32));
    }

    private int loadDialogHeight() { return Math.min(310, Math.max(180, height - 32)); }

    private int loadDialogX() { return (width - loadDialogWidth()) / 2; }

    private int loadDialogY() { return Math.max(8, (height - loadDialogHeight()) / 2); }

    private int loadDialogListRight(int x0, int boxW) {
        return loadDialogKind == SelectionKind.ANIMATION
                ? x0 + Math.max(138, (boxW - 24) * 45 / 100)
                : x0 + boxW - 12;
    }

    private int loadDialogVisibleRows() { return Math.max(1, (loadDialogHeight() - 96) / 18); }

    private int loadDialogListHeight() { return loadDialogVisibleRows() * DIALOG_ROW_HEIGHT; }

    private double loadDialogMaxScroll() {
        return Math.max(0, filteredDialogItems().size() * (double) DIALOG_ROW_HEIGHT
                - loadDialogListHeight());
    }

    private void resetDialogScroll(boolean centerSelection) {
        double target = centerSelection
                ? loadDialogSelected * (double) DIALOG_ROW_HEIGHT
                    - (loadDialogListHeight() - DIALOG_ROW_HEIGHT) / 2.0
                : 0;
        loadDialogScrollPx = loadDialogScrollTargetPx = loadDialogScrollFromPx =
                Mth.clamp(target, 0, loadDialogMaxScroll());
        loadDialogScrollTweenActive = false;
    }

    private void updateDialogScrollTween() {
        if (!loadDialogScrollTweenActive) return;
        double progress = (System.nanoTime() - loadDialogScrollTweenStart)
                / (double) DIALOG_SCROLL_TWEEN_NANOS;
        if (progress >= 1) {
            loadDialogScrollPx = loadDialogScrollTargetPx;
            loadDialogScrollTweenActive = false;
            return;
        }
        double eased = Easing.apply("expoOut", progress);
        loadDialogScrollPx = loadDialogScrollFromPx
                + (loadDialogScrollTargetPx - loadDialogScrollFromPx) * eased;
    }

    private void scrollDialogTo(double target) {
        target = Mth.clamp(target, 0, loadDialogMaxScroll());
        if (Math.abs(target - loadDialogScrollTargetPx) < 0.01) return;
        updateDialogScrollTween();
        loadDialogScrollFromPx = loadDialogScrollPx;
        loadDialogScrollTargetPx = target;
        loadDialogScrollTweenStart = System.nanoTime();
        loadDialogScrollTweenActive = Math.abs(loadDialogScrollTargetPx - loadDialogScrollFromPx) > 0.01;
        if (!loadDialogScrollTweenActive) loadDialogScrollPx = loadDialogScrollTargetPx;
    }

    private void clampLoadDialogSelection() {
        List<String> filtered = filteredDialogItems();
        if (filtered.isEmpty()) {
            loadDialogSelected = 0;
            resetDialogScroll(false);
            return;
        }
        loadDialogSelected = Mth.clamp(loadDialogSelected, 0, filtered.size() - 1);
        double top = loadDialogSelected * (double) DIALOG_ROW_HEIGHT;
        double target = loadDialogScrollTargetPx;
        if (top < target) target = top;
        else if (top + DIALOG_ROW_HEIGHT > target + loadDialogListHeight()) {
            target = top + DIALOG_ROW_HEIGHT - loadDialogListHeight();
        }
        scrollDialogTo(target);
    }

    private void confirmLoadDialog() {
        List<String> filtered = filteredDialogItems();
        if (filtered.isEmpty()) return;
        clampLoadDialogSelection();
        String selected = filtered.get(loadDialogSelected);
        SelectionKind kind = loadDialogKind;
        closeLoadDialog();
        switch (kind) {
            case CHARACTER -> {
                int index = indexOfIgnoreCase(sets, selected);
                if (index >= 0) {
                    loadSet(index);
                    rebuildUi();
                }
            }
            case ANIMATION -> {
                int index = indexOfIgnoreCase(actions, selected);
                if (index >= 0) {
                    actionIndex = index;
                    fillActionFields();
                    preview();
                }
            }
            case FORM -> selectForm(selected);
        }
    }

    private void selectForm(String selected) {
        String form = selected.equals(CURRENT_FORM_OPTION) || selected.equals(INHERIT_FORM_OPTION)
                ? "" : selected;
        current().form = form;
        formIndex = indexOfIgnoreCase(forms, form);
        if (opponent) opponentFormChanged = !form.equalsIgnoreCase(loadedOpponentForm);
        else playerFormChanged = !form.equalsIgnoreCase(loadedPlayerForm);
        loadedFormPrepared = false;
        markDirty();
        if (formButton != null) formButton.setMessage(formLabel());
        preview();
    }

    private void previewHighlightedAnimation() {
        if (loadDialogKind != SelectionKind.ANIMATION) return;
        List<String> filtered = filteredDialogItems();
        if (filtered.isEmpty()) return;
        loadDialogSelected = Mth.clamp(loadDialogSelected, 0, filtered.size() - 1);
        previewDialogAnimation(filtered.get(loadDialogSelected), false);
    }

    private void previewDialogAnimation(String animation, boolean temporary) {
        if (animation == null || animation.isBlank()) return;
        long now = System.nanoTime();
        if (!temporary) dialogSelectedAnimation = animation;
        if (!temporary && isIdleAnimation(animation) && !current().loopIdle
                && hasConfiguredAction("idle") && hasConfiguredAction("idle2")) {
            dialogIdleBeat = "idle2".equalsIgnoreCase(animation) ? 1 : 0;
            previewNextDialogIdle();
            dialogReturnToSelection = 0;
            dialogNextIdlePreview = now + DIALOG_PREVIEW_BEAT_NANOS;
            return;
        }
        dialogPreviewAnimation = animation;
        previewAction(animation, false);
        dialogReturnToSelection = temporary ? now + DIALOG_PREVIEW_BEAT_NANOS : 0;
        dialogNextIdlePreview = !temporary && isIdleAnimation(animation) && !current().loopIdle
                ? now + DIALOG_PREVIEW_BEAT_NANOS : 0;
    }

    private void updateDialogAnimationPreview() {
        if (!loadDialogOpen || loadDialogKind != SelectionKind.ANIMATION) return;
        long now = System.nanoTime();
        if (dialogReturnToSelection > 0 && now >= dialogReturnToSelection) {
            dialogReturnToSelection = 0;
            previewDialogAnimation(dialogSelectedAnimation, false);
            return;
        }
        if (dialogNextIdlePreview > 0 && now >= dialogNextIdlePreview) {
            if (isIdleAnimation(dialogSelectedAnimation)
                    && hasConfiguredAction("idle") && hasConfiguredAction("idle2")) {
                previewNextDialogIdle();
            } else {
                previewAction(dialogSelectedAnimation, false);
            }
            dialogNextIdlePreview = now + DIALOG_PREVIEW_BEAT_NANOS;
        }
    }

    private void previewNextDialogIdle() {
        String animation = (dialogIdleBeat++ & 1) == 1 ? "idle2" : "idle";
        dialogPreviewAnimation = animation;
        previewAction(animation, false);
    }

    private boolean hasConfiguredAction(String animation) {
        CharacterDefinitionFile.Action action = current().findAction(animation);
        return action != null && action.state != null && !action.state.isBlank();
    }

    private static boolean isIdleAnimation(String animation) {
        return "idle".equalsIgnoreCase(animation) || "idle2".equalsIgnoreCase(animation);
    }

    @Override
    public void render(GuiGraphics gui, int mouseX, int mouseY, float partialTick) {
        if (loadDialogOpen) {
            gui.pose().pushPose();
            gui.pose().translate(0, 0, 1000);
            renderLoadDialog(gui, mouseX, mouseY);
            gui.pose().popPose();
            return;
        }
        if (colorPickerOpen) {
            gui.pose().pushPose();
            gui.pose().translate(0, 0, 1000);
            renderColorPicker(gui, mouseX, mouseY);
            gui.pose().popPose();
            return;
        }
        gui.fill(0, 0, width, height, 0xFF101014);
        int rightPanelX = optionsX() - 4;
        gui.fill(rightPanelX, 24, width - 4, height - 32, PANEL);
        gui.drawCenteredString(font, title, width / 2, 10, 0xFFFFFFFF);

        int x = optionsX();
        int panelWidth = optionsWidth();
        int half = Math.max(24, (panelWidth - 4) / 2);
        switch (activeTab) {
            case CHARACTER -> {
                label(gui, "Character name", x, 74);
                label(gui, "Health icon", x, 106);
                label(gui, "Vocal prefix", x, 138);
            }
            case MODEL -> {
                label(gui, "BBS form", x, 74);
                label(gui, "Base rotation", x, 108);
                label(gui, "Base camera X", x, 140);
                label(gui, "Base camera Y", x + half + 4, 140);
            }
            case ANIMATIONS -> {
                label(gui, "Animation name", x, 100);
                label(gui, "BBS state", x + half + 4, 100);
                label(gui, "Camera X", x, 134);
                label(gui, "Camera Y", x + half + 4, 134);
            }
        }

        renderPreview(gui);
        gui.drawString(font, trim(status, Math.max(16, previewRight() / 6)), 12,
                height - 18, 0xFFCCCCCC, false);
        super.render(gui, mouseX, mouseY, partialTick);
    }

    private void renderLoadDialog(GuiGraphics gui, int mouseX, int mouseY) {
        gui.fill(0, 0, width, height, 0xF20A0A10);
        int boxW = loadDialogWidth(), boxH = loadDialogHeight();
        int x0 = loadDialogX(), y0 = loadDialogY();
        int listRight = loadDialogListRight(x0, boxW);
        gui.fill(x0, y0, x0 + boxW, y0 + boxH, 0xFF101018);
        gui.renderOutline(x0, y0, boxW, boxH, 0xFF6A70FF);
        gui.drawCenteredString(font, dialogTitle(), x0 + boxW / 2, y0 + 9, 0xFFFFFFFF);

        if (loadSearchField != null) {
            loadSearchField.setX(x0 + 12);
            loadSearchField.setY(y0 + 26);
            loadSearchField.setWidth(listRight - x0 - 16);
            loadSearchField.render(gui, mouseX, mouseY, 0);
        }

        List<String> filtered = filteredDialogItems();
        clampLoadDialogSelection();
        updateDialogScrollTween();
        int listTop = y0 + 50;
        int listHeight = loadDialogListHeight();
        int first = Math.max(0, (int) Math.floor(loadDialogScrollPx / DIALOG_ROW_HEIGHT));
        double rowOffset = loadDialogScrollPx - first * (double) DIALOG_ROW_HEIGHT;
        int drawnRows = loadDialogVisibleRows() + 2;
        gui.enableScissor(x0 + 12, listTop, listRight, listTop + listHeight);
        for (int row = 0; row < drawnRows; row++) {
            int index = first + row;
            if (index >= filtered.size()) break;
            int rowY = listTop + row * DIALOG_ROW_HEIGHT - (int) Math.round(rowOffset);
            boolean selected = index == loadDialogSelected;
            boolean hovered = mouseX >= x0 + 12 && mouseX < listRight
                    && mouseY >= Math.max(rowY, listTop)
                    && mouseY < Math.min(rowY + DIALOG_ROW_HEIGHT - 1, listTop + listHeight);
            gui.fill(x0 + 12, rowY, listRight, rowY + DIALOG_ROW_HEIGHT - 1,
                    selected ? 0xFF4B5070 : hovered ? 0xFF303442 : 0xFF20202A);
            gui.drawString(font, filtered.get(index), x0 + 17, rowY + 4,
                    selected ? 0xFFFFFFFF : 0xFFCCCCCC, false);
        }
        gui.disableScissor();
        if (filtered.isEmpty()) {
            gui.drawCenteredString(font, "No matching " + dialogItemName() + "s", x0 + boxW / 2,
                    listTop + 8, 0xFFAAAAAA);
        }

        double maxScroll = loadDialogMaxScroll();
        if (maxScroll > 0) {
            int trackX = listRight - 3;
            int thumbHeight = Math.max(12, (int) Math.round(listHeight
                    * (listHeight / (filtered.size() * (double) DIALOG_ROW_HEIGHT))));
            int thumbY = listTop + (int) Math.round((listHeight - thumbHeight)
                    * (loadDialogScrollPx / maxScroll));
            gui.fill(trackX, listTop, trackX + 2, listTop + listHeight, 0xFF252733);
            gui.fill(trackX, thumbY, trackX + 2, thumbY + thumbHeight, 0xFF8A90C0);
        }
        if (loadDialogKind == SelectionKind.ANIMATION) {
            updateDialogAnimationPreview();
            renderAnimationDialogPreview(gui, listRight + 7, y0 + 26,
                    x0 + boxW - 12, y0 + boxH - 35);
        }

        int buttonY = y0 + boxH - 27;
        int buttonWidth = (boxW - 28) / 2;
        renderColorButton(gui, x0 + 10, buttonY, buttonWidth, "Cancel", mouseX, mouseY);
        renderColorButton(gui, x0 + 18 + buttonWidth, buttonY,
                buttonWidth, loadDialogKind == SelectionKind.CHARACTER ? "Load" : "Select", mouseX, mouseY);
    }

    private void renderAnimationDialogPreview(GuiGraphics gui, int left, int top, int right, int bottom) {
        if (right - left < 70 || bottom - top < 80) return;
        gui.fill(left, top, right, bottom, 0xFF161720);
        gui.renderOutline(left, top, right - left, bottom - top, 0xFF454A68);
        String animation = dialogPreviewAnimation.isBlank()
                ? dialogSelectedAnimation : dialogPreviewAnimation;
        gui.drawCenteredString(font, trim(animation, Math.max(8, (right - left) / 7)),
                (left + right) / 2, top + 7, 0xFFFFFFFF);

        CharacterDefinitionFile definition = current();
        CharacterDefinitionFile.Action action = definition.findAction(animation);
        float actionX = action == null ? 0 : action.cameraX;
        float actionY = action == null ? 0 : action.cameraY;
        int previewBottom = bottom - 22;
        int centerX = (left + right) / 2;
        int centerY = (top + previewBottom) / 2 + 16;
        int shiftX = Math.round(-(definition.cameraX + actionX) * 12);
        int shiftY = Math.round((definition.cameraY + actionY) * 12);
        gui.fill(centerX - 7, centerY, centerX + 8, centerY + 1, 0xAAFF4444);
        gui.fill(centerX, centerY - 7, centerX + 1, centerY + 8, 0xAAFF4444);
        if (minecraft != null && minecraft.player != null) {
            int scale = Mth.clamp((previewBottom - top) / 3, 30, 82);
            renderFixedCharacter(gui, left + 3, top + 18, right - 3, previewBottom,
                    centerX + shiftX, centerY + shiftY, scale, definition.rotation,
                    minecraft.player);
        }
        gui.drawCenteredString(font, notePreviewHint(), (left + right) / 2,
                bottom - 16, 0xFFAAAEC5);
    }

    private String notePreviewHint() {
        StringBuilder keys = new StringBuilder();
        for (int i = 0; i < FnfKeys.NOTE_KEYS.length; i++) {
            if (i > 0) keys.append('/');
            keys.append(FnfKeys.NOTE_KEYS[i].getTranslatedKeyMessage().getString());
        }
        return trim(keys + ": preview directions", 38);
    }

    private void renderColorPicker(GuiGraphics gui, int mouseX, int mouseY) {
        gui.fill(0, 0, width, height, 0xF20A0A10);
        int boxW = colorPickerWidth(), boxH = colorPickerHeight();
        int x0 = colorPickerX(), y0 = colorPickerY();
        gui.fill(x0, y0, x0 + boxW, y0 + boxH, 0xFF101018);
        gui.renderOutline(x0, y0, boxW, boxH, 0xFF6A70FF);
        gui.drawCenteredString(font, "Character waveform / health color",
                x0 + boxW / 2, y0 + 9, 0xFFFFFFFF);

        int squareX = x0 + 12, squareY = y0 + 28, squareSize = 80;
        for (int column = 0; column < squareSize; column++) {
            int color = java.awt.Color.HSBtoRGB(colorHue, column / (squareSize - 1f), 1f);
            gui.fill(squareX + column, squareY, squareX + column + 1, squareY + squareSize,
                    0xFF000000 | (color & 0xFFFFFF));
        }
        gui.fillGradient(squareX, squareY, squareX + squareSize, squareY + squareSize,
                0x00000000, 0xFF000000);
        int cursorX = squareX + Math.round(colorSat * (squareSize - 1));
        int cursorY = squareY + Math.round((1 - colorBri) * (squareSize - 1));
        gui.fill(cursorX - 2, cursorY - 2, cursorX + 3, cursorY + 3, 0xFFFFFFFF);
        gui.fill(cursorX - 1, cursorY - 1, cursorX + 2, cursorY + 2,
                0xFF000000 | colorPickerValue);

        int rightX = squareX + squareSize + 14;
        int rightWidth = x0 + boxW - 12 - rightX;
        gui.fill(rightX, squareY, rightX + rightWidth, squareY + 36,
                0xFF000000 | colorPickerValue);
        gui.renderOutline(rightX, squareY, rightWidth, 36, 0xFF6A7080);
        gui.drawString(font, "Hex", rightX, squareY + 45, 0xFFBBBBBB, false);
        if (colorHexField != null) {
            colorHexField.setX(rightX);
            colorHexField.setY(squareY + 56);
            colorHexField.setWidth(rightWidth);
            colorHexField.render(gui, mouseX, mouseY, 0);
        }

        int hueX = x0 + 12, hueY = y0 + 120, hueWidth = boxW - 24;
        for (int column = 0; column < hueWidth; column++) {
            int color = java.awt.Color.HSBtoRGB(column / (hueWidth - 1f), 1f, 1f);
            gui.fill(hueX + column, hueY, hueX + column + 1, hueY + 10,
                    0xFF000000 | (color & 0xFFFFFF));
        }
        int hueCursor = hueX + Math.round(colorHue * (hueWidth - 1));
        gui.fill(hueCursor - 1, hueY - 1, hueCursor + 2, hueY + 11, 0xFFFFFFFF);

        int buttonY = y0 + boxH - 26;
        int buttonWidth = (boxW - 28) / 2;
        renderColorButton(gui, x0 + 10, buttonY, buttonWidth, "Cancel", mouseX, mouseY);
        renderColorButton(gui, x0 + 18 + buttonWidth, buttonY, buttonWidth, "Done", mouseX, mouseY);
    }

    private void renderColorButton(GuiGraphics gui, int x, int y, int buttonWidth, String text,
                                   int mouseX, int mouseY) {
        boolean hover = mouseX >= x && mouseX < x + buttonWidth && mouseY >= y && mouseY < y + 18;
        gui.fill(x, y, x + buttonWidth, y + 18, hover ? 0xFF505675 : 0xFF303442);
        gui.renderOutline(x, y, buttonWidth, 18, 0xFF6A7080);
        gui.drawCenteredString(font, text, x + buttonWidth / 2, y + 5, 0xFFFFFFFF);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (loadDialogOpen) {
            boolean typingSearch = loadSearchField != null && loadSearchField.isFocused();
            int previewLane = loadDialogKind == SelectionKind.ANIMATION && !typingSearch
                    ? FnfKeys.laneForKey(keyCode, scanCode) : -1;
            if (keyCode == GLFW.GLFW_KEY_ESCAPE) cancelLoadDialog();
            else if (previewLane >= 0) {
                previewDialogAnimation(new String[]{"left", "down", "up", "right"}[previewLane], true);
            }
            else if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
                confirmLoadDialog();
            } else if (keyCode == GLFW.GLFW_KEY_UP || keyCode == GLFW.GLFW_KEY_DOWN) {
                loadDialogSelected += keyCode == GLFW.GLFW_KEY_UP ? -1 : 1;
                clampLoadDialogSelection();
                previewHighlightedAnimation();
            } else if (typingSearch) {
                loadSearchField.keyPressed(keyCode, scanCode, modifiers);
            }
            return true;
        }
        if (colorPickerOpen) {
            if (keyCode == GLFW.GLFW_KEY_ESCAPE) closeColorPicker(false);
            else if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
                closeColorPicker(true);
            } else if (colorHexField != null) {
                colorHexField.keyPressed(keyCode, scanCode, modifiers);
            }
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        if (loadDialogOpen) {
            if (loadSearchField != null && loadSearchField.isFocused()) {
                loadSearchField.charTyped(codePoint, modifiers);
            }
            return true;
        }
        if (colorPickerOpen) {
            if (colorHexField != null) colorHexField.charTyped(codePoint, modifiers);
            return true;
        }
        return super.charTyped(codePoint, modifiers);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (loadDialogOpen) {
            if (loadSearchField != null && loadSearchField.mouseClicked(mouseX, mouseY, button)) {
                setFocused(loadSearchField);
            }
            if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT) return true;
            int x0 = loadDialogX(), y0 = loadDialogY();
            int boxW = loadDialogWidth(), boxH = loadDialogHeight();
            int listRight = loadDialogListRight(x0, boxW);
            int listTop = y0 + 50;
            if (mouseX >= x0 + 12 && mouseX < listRight
                    && mouseY >= listTop && mouseY < listTop + loadDialogListHeight()) {
                loadSearchField.setFocused(false);
                setFocused(null);
                updateDialogScrollTween();
                int index = (int) Math.floor((mouseY - listTop + loadDialogScrollPx)
                        / DIALOG_ROW_HEIGHT);
                if (index >= 0 && index < filteredDialogItems().size()) {
                    loadDialogSelected = index;
                    previewHighlightedAnimation();
                }
                return true;
            }
            if (loadDialogKind == SelectionKind.ANIMATION && mouseX >= listRight
                    && mouseX < x0 + boxW && mouseY >= y0 + 24 && mouseY < y0 + boxH - 32) {
                loadSearchField.setFocused(false);
                setFocused(null);
                return true;
            }
            int buttonY = y0 + boxH - 27;
            int buttonWidth = (boxW - 28) / 2;
            if (mouseY >= buttonY && mouseY < buttonY + 18
                    && mouseX >= x0 + 10 && mouseX < x0 + 10 + buttonWidth) {
                cancelLoadDialog();
            } else if (mouseY >= buttonY && mouseY < buttonY + 18
                    && mouseX >= x0 + 18 + buttonWidth
                    && mouseX < x0 + 18 + buttonWidth * 2) {
                confirmLoadDialog();
            }
            return true;
        }
        if (!colorPickerOpen) {
            boolean handled = super.mouseClicked(mouseX, mouseY, button);
            // AbstractContainerEventHandler focuses the clicked opener after its
            // onPress callback returns. Reclaim focus here so modal fields opened
            // by that callback can accept text immediately.
            if (loadDialogOpen && loadSearchField != null) {
                setFocused(loadSearchField);
                loadSearchField.setFocused(true);
            } else if (colorPickerOpen && colorHexField != null) {
                setFocused(colorHexField);
                colorHexField.setFocused(true);
            }
            return handled;
        }
        if (colorHexField != null && colorHexField.mouseClicked(mouseX, mouseY, button)) {
            setFocused(colorHexField);
        }
        if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT) return true;
        int x0 = colorPickerX(), y0 = colorPickerY(), boxW = colorPickerWidth();
        int squareX = x0 + 12, squareY = y0 + 28;
        int hueX = x0 + 12, hueY = y0 + 120, hueWidth = boxW - 24;
        int buttonY = y0 + colorPickerHeight() - 26;
        int buttonWidth = (boxW - 28) / 2;
        if (mouseX >= squareX && mouseX < squareX + 80
                && mouseY >= squareY && mouseY < squareY + 80) {
            colorDrag = 1;
            updateColorPicker(mouseX, mouseY);
        } else if (mouseX >= hueX && mouseX < hueX + hueWidth
                && mouseY >= hueY && mouseY < hueY + 10) {
            colorDrag = 2;
            updateColorPicker(mouseX, mouseY);
        } else if (mouseY >= buttonY && mouseY < buttonY + 18
                && mouseX >= x0 + 10 && mouseX < x0 + 10 + buttonWidth) {
            closeColorPicker(false);
        } else if (mouseY >= buttonY && mouseY < buttonY + 18
                && mouseX >= x0 + 18 + buttonWidth
                && mouseX < x0 + 18 + buttonWidth * 2) {
            closeColorPicker(true);
        }
        return true;
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (loadDialogOpen) return true;
        if (colorPickerOpen) {
            if (colorDrag != 0) updateColorPicker(mouseX, mouseY);
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (loadDialogOpen) return true;
        if (colorPickerOpen) {
            colorDrag = 0;
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (loadDialogOpen) {
            if (scrollY != 0) {
                loadDialogSelected += scrollY > 0 ? -1 : 1;
                clampLoadDialogSelection();
                previewHighlightedAnimation();
            }
            return true;
        }
        if (colorPickerOpen) return true;
        if (scrollY != 0 && actionButton != null && actionButton.visible
                && actionButton.isMouseOver(mouseX, mouseY)) {
            captureFields();
            actionIndex = Math.floorMod(actionIndex + (scrollY > 0 ? -1 : 1), actions.size());
            fillActionFields();
            preview();
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    /**
     * This editor paints its own opaque background before calling Screen.render
     * for widgets. Minecraft 1.21's default implementation runs the pause-menu
     * blur shader, which would blur the editor UI that was already drawn.
     */
    @Override
    public void renderBackground(GuiGraphics gui, int mouseX, int mouseY, float partialTick) {
        // Intentionally empty: render() owns the complete editor background.
    }

    private void renderPreview(GuiGraphics gui) {
        int left = 8;
        int right = previewRight() - 4;
        int top = 26;
        int bottom = height - 32;
        if (right - left < 80 || bottom - top < 100) return;
        gui.fill(left, top, right, bottom, PANEL);
        gui.drawCenteredString(font, "BBS Form Preview", (left + right) / 2, top + 7, 0xFFFFFFFF);

        // Keep numeric previews live while an edit box has focus.
        captureFields(false);
        CharacterDefinitionFile definition = current();
        CharacterDefinitionFile.Action action = definition.action(currentActionName());
        float camX = definition.cameraX + action.cameraX;
        float camY = definition.cameraY + action.cameraY;
        int centerX = (left + right) / 2;
        int centerY = (top + bottom) / 2 + 12;
        int shiftX = Math.round(-camX * 18);
        int shiftY = Math.round(camY * 18);

        gui.fill(centerX - 8, centerY, centerX + 9, centerY + 1, 0xAAFF4444);
        gui.fill(centerX, centerY - 8, centerX + 1, centerY + 9, 0xAAFF4444);
        if (minecraft != null && minecraft.player != null) {
            int scale = Mth.clamp((bottom - top) / 3, 34, 90);
            renderFixedCharacter(gui, left + 4, top + 20, right - 4, bottom - 8,
                    centerX + shiftX, centerY + shiftY, scale, definition.rotation,
                    minecraft.player);
        }
        gui.drawString(font, "Camera: " + decimal(camX) + ", " + decimal(camY),
                left + 6, bottom - 16, 0xFFBBBBBB, false);
    }

    /**
     * InventoryScreen's convenience preview intentionally follows the mouse and
     * turns the head farther than the body. The character editor instead uses a
     * fixed PlayState-like camera: zero rotation faces forward, and the JSON value
     * rotates the whole form as one unit. Integer screen coordinates also avoid the
     * soft sampling visible while the old preview continuously rendered off-axis.
     */
    private static void renderFixedCharacter(GuiGraphics gui, int clipLeft, int clipTop,
                                             int clipRight, int clipBottom,
                                             int centerX, int centerY, int scale,
                                             float rotation, LivingEntity entity) {
        float oldBody = entity.yBodyRot;
        float oldY = entity.getYRot();
        float oldX = entity.getXRot();
        float oldHead = entity.yHeadRot;
        float oldHeadPrevious = entity.yHeadRotO;
        float facing = 180f + rotation;

        // Flush the crisp 2D panels before the entity renderer changes shader,
        // projection, lighting and depth state. Otherwise those buffered quads
        // can be submitted through the preview's 3D state and look softened.
        gui.flush();
        gui.enableScissor(clipLeft, clipTop, clipRight, clipBottom);
        try {
            entity.yBodyRot = facing;
            entity.setYRot(facing);
            entity.setXRot(0);
            entity.yHeadRot = facing;
            entity.yHeadRotO = facing;

            float entityScale = entity.getScale();
            Vector3f translation = new Vector3f(0,
                    entity.getBbHeight() / 2f + 0.15f * entityScale, 0);
            Quaternionf pose = new Quaternionf().rotateZ((float) Math.PI);
            Quaternionf camera = new Quaternionf();
            InventoryScreen.renderEntityInInventory(gui, centerX, centerY,
                    scale / entityScale, translation, pose, camera, entity);
        } finally {
            entity.yBodyRot = oldBody;
            entity.setYRot(oldY);
            entity.setXRot(oldX);
            entity.yHeadRot = oldHead;
            entity.yHeadRotO = oldHeadPrevious;
            gui.disableScissor();
            gui.flush();
            RenderSystem.disableDepthTest();
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            gui.setColor(1f, 1f, 1f, 1f);
        }
    }

    private void label(GuiGraphics gui, String text, int x, int y) {
        gui.drawString(font, text, x, y, 0xFFBBBBBB, false);
    }

    @Override
    public void onClose() {
        CharacterAnimations.stopPreview();
        if (minecraft != null) minecraft.setScreen(parent);
    }

    @Override
    public void removed() {
        super.removed();
        if (!(minecraft != null && minecraft.screen == this)) CharacterAnimations.stopPreview();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public boolean isTextInputActive() {
        return loadDialogOpen || colorPickerOpen
                || (getFocused() instanceof EditBox edit && edit.isFocused());
    }

    private String uniqueName(String base) {
        String name = base;
        int suffix = 2;
        while (Files.exists(canonicalFile(name, false))
                || Files.isDirectory(SongLibrary.animationsDir().resolve(name))) {
            name = base + "-" + suffix++;
        }
        return name;
    }

    private static Path canonicalFile(String rawName, boolean opponent) {
        String name = safeName(rawName);
        return SongLibrary.animationsDir().resolve(name + (opponent ? "-opp" : "") + ".json")
                .normalize();
    }

    private static String safeName(String raw) {
        if (raw == null) return "";
        String cleaned = raw.trim().replaceAll("[^A-Za-z0-9._ -]", "_");
        while (cleaned.endsWith(".")) cleaned = cleaned.substring(0, cleaned.length() - 1);
        return cleaned;
    }

    private static int indexOfIgnoreCase(List<String> values, String value) {
        if (value == null) return -1;
        for (int i = 0; i < values.size(); i++) {
            if (values.get(i).equalsIgnoreCase(value)) return i;
        }
        return -1;
    }

    private static float number(String text) {
        try {
            float value = Float.parseFloat(text.trim());
            return Float.isFinite(value) ? value : 0;
        } catch (Exception ignored) {
            return 0;
        }
    }

    private static String decimal(float value) {
        if (Math.abs(value - Math.round(value)) < 0.0001f) return Integer.toString(Math.round(value));
        return String.format(Locale.ROOT, "%.3f", value).replaceAll("0+$", "").replaceAll("\\.$", "");
    }

    private String trim(String text, int maxChars) {
        if (text == null) return "";
        if (text.length() <= maxChars) return text;
        return text.substring(0, Math.max(1, maxChars - 1)) + "…";
    }
}
