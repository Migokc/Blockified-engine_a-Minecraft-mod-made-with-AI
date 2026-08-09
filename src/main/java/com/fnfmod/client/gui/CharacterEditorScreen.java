package com.fnfmod.client.gui;

import com.mojang.blaze3d.systems.RenderSystem;
import com.fnfmod.client.anim.CharacterDefinitionFile;
import com.fnfmod.client.anim.CharacterAnimations;
import com.fnfmod.client.gameplay.NativeFilePicker;
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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Visual editor for named Blockified character JSON definitions and BBS states. */
public final class CharacterEditorScreen extends Screen {
    private static final int PANEL = 0xE0181820;
    private static final int FIELD_H = 18;

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
    private String status = "";

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
    private Button setButton;
    private Button roleButton;
    private Button formButton;
    private Button actionButton;
    private Button loopIdleButton;

    public CharacterEditorScreen(Screen parent) {
        super(Component.literal("Blockified Character Editor"));
        this.parent = parent;
        refreshChoices();
        loadSet(0);
    }

    @Override
    protected void init() {
        int left = 16;
        int editorWidth = Math.max(190, Math.min(360, width / 2 - 12));
        int fieldX = left + 82;
        int fieldWidth = Math.max(80, editorWidth - 92);
        int half = Math.max(36, (fieldWidth - 4) / 2);
        int y = 34;

        setButton = addRenderableWidget(Button.builder(setLabel(), button -> cycleSet(hasShiftDown() ? -1 : 1))
                .bounds(left, y, 78, 20).build());
        setName = edit(fieldX, y + 1, fieldWidth, value -> markDirty());
        y += 24;

        roleButton = addRenderableWidget(Button.builder(roleLabel(), button -> switchRole())
                .bounds(left, y, 78, 20).build());
        formButton = addRenderableWidget(Button.builder(formLabel(), button -> cycleForm(hasShiftDown() ? -1 : 1))
                .bounds(fieldX, y, fieldWidth, 20).build());
        y += 26;

        icon = edit(fieldX, y, fieldWidth, value -> markDirty());
        y += 22;
        vocalsFile = edit(fieldX, y, fieldWidth, value -> markDirty());
        y += 22;
        rotation = edit(fieldX, y, fieldWidth, value -> markDirty());
        y += 22;
        cameraX = edit(fieldX, y, half, value -> markDirty());
        cameraY = edit(fieldX + half + 4, y, half, value -> markDirty());
        y += 28;

        actionButton = addRenderableWidget(Button.builder(actionLabel(), button -> cycleAction(hasShiftDown() ? -1 : 1))
                .bounds(left, y, 78, 20).build());
        actionName = edit(fieldX, y + 1, half, value -> markDirty());
        state = edit(fieldX + half + 4, y + 1, half, value -> markDirty());
        y += 24;
        addRenderableWidget(Button.builder(Component.literal("+"), button -> addCustomAnimation())
                .bounds(left, y, 37, 20).build());
        addRenderableWidget(Button.builder(Component.literal("-"), button -> removeCustomAnimation())
                .bounds(left + 41, y, 37, 20).build());
        actionCameraX = edit(fieldX, y, half, value -> markDirty());
        actionCameraY = edit(fieldX + half + 4, y, half, value -> markDirty());
        y += 26;

        addRenderableWidget(Button.builder(Component.literal("Preview State"), button -> preview())
                .bounds(left, y, 100, 20).build());
        addRenderableWidget(Button.builder(Component.literal("New"), button -> newSet())
                .bounds(left + 104, y, 58, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Save"), button -> save())
                .bounds(left + 166, y, 70, 20).build());
        y += 26;
        loopIdleButton = addRenderableWidget(Button.builder(loopIdleLabel(), button -> toggleLoopIdle())
                .bounds(left, y, 236, 20).build());
        y += 26;
        addRenderableWidget(Button.builder(Component.literal(colorLabel()), button -> chooseColor())
                .bounds(left, y, 236, 20).build());
        y += 26;
        addRenderableWidget(Button.builder(Component.literal("Bundle BBS Model + Texture"),
                        button -> bundleAssets())
                .bounds(left, y, 236, 20).build());

        addRenderableWidget(Button.builder(Component.literal("Back"), button -> onClose())
                .bounds(width - 86, height - 26, 76, 20).build());
        fillFields();
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
    }

    private void loadSet(int index) {
        if (sets.isEmpty()) sets.add("default");
        setIndex = Math.floorMod(index, sets.size());
        String name = sets.get(setIndex);
        currentSetName = name;
        Path playerFile = definitionFile(name, false);
        Path opponentFile = definitionFile(name, true);
        playerDefinition = CharacterDefinitionFile.load(playerFile);
        opponentDefinition = CharacterDefinitionFile.load(opponentFile);
        rebuildActions("idle");
        playerDirty = opponentDirty = false;
        status = "Editing " + playerFile.getFileName();
        if (setName != null) fillFields();
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

    private void cycleSet(int direction) {
        captureFields();
        loadSet(setIndex + direction);
    }

    private void newSet() {
        captureFields();
        String name = uniqueName("new-character");
        currentSetName = name;
        playerDefinition = CharacterDefinitionFile.load(definitionFile(name, false));
        opponentDefinition = CharacterDefinitionFile.load(definitionFile(name, true));
        rebuildActions("idle");
        playerDirty = true;
        opponentDirty = false;
        status = "New character: choose a BBS form and save";
        loadingFields = true;
        setName.setValue(currentSetName);
        loadingFields = false;
        opponent = false;
        fillFields();
    }

    private void switchRole() {
        String selected = currentActionName();
        captureFields();
        opponent = !opponent;
        rebuildActions(selected);
        fillFields();
    }

    private void cycleForm(int direction) {
        if (forms.isEmpty()) {
            status = "No BBS forms found";
            return;
        }
        String current = current().form;
        int found = indexOfIgnoreCase(forms, current);
        formIndex = Math.floorMod((found < 0 ? 0 : found) + direction, forms.size());
        current().form = forms.get(formIndex);
        markDirty();
        if (formButton != null) formButton.setMessage(formLabel());
        preview();
    }

    private void cycleAction(int direction) {
        captureFields();
        actionIndex = Math.floorMod(actionIndex + direction, actions.size());
        fillActionFields();
        preview();
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
        if (setName == null) return;
        loadingFields = true;
        CharacterDefinitionFile definition = current();
        setName.setValue(currentSetName);
        icon.setValue(definition.icon);
        vocalsFile.setValue(definition.vocalsFile);
        rotation.setValue(decimal(definition.rotation));
        cameraX.setValue(decimal(definition.cameraX));
        cameraY.setValue(decimal(definition.cameraY));
        formIndex = indexOfIgnoreCase(forms, definition.form);
        fillActionFields();
        loadingFields = false;
        setButton.setMessage(setLabel());
        roleButton.setMessage(roleLabel());
        formButton.setMessage(formLabel());
        actionButton.setMessage(actionLabel());
        if (loopIdleButton != null) loopIdleButton.setMessage(loopIdleLabel());
    }

    private void fillActionFields() {
        String name = currentActionName();
        CharacterDefinitionFile.Action action = current().action(name);
        loadingFields = true;
        actionName.setValue(name);
        actionName.setEditable(!current().isPreset(name));
        state.setValue(action.state);
        actionCameraX.setValue(decimal(action.cameraX));
        actionCameraY.setValue(decimal(action.cameraY));
        loadingFields = false;
        if (actionButton != null) actionButton.setMessage(actionLabel());
    }

    private void captureFields() {
        captureFields(true);
    }

    private void captureFields(boolean commitAnimationName) {
        if (setName == null) return;
        CharacterDefinitionFile definition = current();
        definition.icon = icon.getValue().trim();
        definition.vocalsFile = vocalsFile.getValue().trim();
        definition.rotation = number(rotation.getValue());
        definition.cameraX = number(cameraX.getValue());
        definition.cameraY = number(cameraY.getValue());
        String selectedName = currentActionName();
        CharacterDefinitionFile.Action action = definition.action(selectedName);
        action.state = state.getValue().trim();
        action.cameraX = number(actionCameraX.getValue());
        action.cameraY = number(actionCameraY.getValue());
        if (commitAnimationName && !definition.isPreset(selectedName)) {
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
        if (minecraft == null || minecraft.player == null) return;
        CharacterDefinitionFile definition = current();
        CharacterDefinitionFile fallback = playerDefinition;
        String form = definition.form.isBlank() && opponent ? fallback.form : definition.form;
        String selectedAction = currentActionName();
        CharacterDefinitionFile.Action action = definition.action(selectedAction);
        String stateName = action.state;
        if (stateName.isBlank() && opponent) {
            CharacterDefinitionFile.Action inherited = fallback.findAction(selectedAction);
            if (inherited != null) stateName = inherited.state;
        }
        if (stateName.isBlank()) stateName = selectedAction;
        boolean played = CharacterAnimations.preview(minecraft.player, form, stateName);
        status = played ? "Previewing " + stateName : "State not found on the selected BBS form";
    }

    private void save() {
        captureFields();
        String name = safeName(setName.getValue());
        if (name.isBlank()) {
            status = "Enter a character set name";
            return;
        }
        boolean renamed = !name.equalsIgnoreCase(currentSetName);
        Path playerOutput = renamed ? canonicalFile(name, false) : playerDefinition.file();
        Path opponentOutput = renamed ? canonicalFile(name, true) : opponentDefinition.file();
        try {
            if (playerDirty || renamed || (!opponentDirty && !Files.exists(playerDefinition.file()))) {
                playerDefinition.saveAs(playerOutput);
            }
            if (opponentDirty || renamed && Files.exists(opponentDefinition.file())) {
                opponentDefinition.saveAs(opponentOutput);
            }
            CharacterAnimations.reload();
            refreshChoices();
            setIndex = Math.max(0, indexOfIgnoreCase(sets, name));
            currentSetName = name;
            playerDirty = opponentDirty = false;
            status = "Saved " + playerOutput.getFileName();
            if (setButton != null) setButton.setMessage(setLabel());
        } catch (Exception error) {
            status = "Save failed: " + error.getMessage();
        }
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
        NativeFilePicker.pickColor("Character waveform / health color",
                definition.healthColor < 0 ? (opponent ? 0xAF66CE : 0x31B0D1) : definition.healthColor)
                .ifPresent(color -> {
                    definition.healthColor = color;
                    markDirty();
                    refreshEditorWidgets();
                });
    }

    private void refreshEditorWidgets() {
        clearWidgets();
        init();
    }

    /**
     * Copies the selected BBS form's model + texture into this character's own
     * animations/&lt;name&gt;/ folder (converting to folder layout) so the character
     * is self-contained and can be shared without a separate BBS export.
     */
    private void bundleAssets() {
        captureFields();
        String base = safeName(setName.getValue());
        if (base.isBlank()) {
            status = "Enter a character name first";
            return;
        }
        if (CharacterAnimations.DEFAULT_SET.equalsIgnoreCase(base)) {
            status = "Give the character a non-default name first";
            return;
        }
        String form = current() == null ? "" : current().form;
        if (form == null || form.isBlank()) {
            status = "Pick a BBS form to bundle";
            return;
        }
        try {
            Path folder = SongLibrary.animationsDir().resolve(base).normalize();
            Files.createDirectories(folder);
            playerDefinition.saveAs(folder, false);
            if (!opponentDefinition.form.isBlank() || Files.exists(opponentDefinition.file())) {
                opponentDefinition.saveAs(folder, true);
            }
            Files.deleteIfExists(SongLibrary.animationsDir().resolve(base + ".json"));
            Files.deleteIfExists(SongLibrary.animationsDir().resolve(base + "-opp.json"));

            boolean ok = CharacterAnimations.bundleForm(form, folder, "character");
            if (!opponentDefinition.form.isBlank()
                    && !opponentDefinition.form.equalsIgnoreCase(playerDefinition.form)) {
                CharacterAnimations.bundleForm(opponentDefinition.form, folder, "character-opp");
            }

            CharacterAnimations.reload();
            refreshChoices();
            setIndex = Math.max(0, indexOfIgnoreCase(sets, base));
            loadSet(setIndex);
            status = ok ? "Bundled model + texture into animations/" + base + " (self-contained)"
                    : "Saved folder, but the BBS form/assets were unavailable to bundle";
        } catch (Exception error) {
            status = "Bundle failed: " + error.getMessage();
        }
    }

    private CharacterDefinitionFile current() {
        return opponent ? opponentDefinition : playerDefinition;
    }

    private Component setLabel() {
        return Component.literal("Set " + (setIndex + 1) + "/" + Math.max(1, sets.size()));
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
        return Component.literal("Anim " + (actionIndex + 1) + "/" + Math.max(1, actions.size()));
    }

    private String currentActionName() {
        if (actions.isEmpty()) actions.addAll(List.of(CharacterAnimations.ACTIONS));
        actionIndex = Mth.clamp(actionIndex, 0, actions.size() - 1);
        return actions.get(actionIndex);
    }

    @Override
    public void render(GuiGraphics gui, int mouseX, int mouseY, float partialTick) {
        gui.fill(0, 0, width, height, 0xFF101014);
        int editorRight = Math.max(206, Math.min(376, width / 2 + 4));
        gui.fill(8, 26, editorRight, Math.min(height - 34, 344), PANEL);
        gui.drawCenteredString(font, title, width / 2, 10, 0xFFFFFFFF);

        label(gui, "Icon", 16, 89);
        label(gui, "Vocal prefix", 16, 111);
        label(gui, "Rotation", 16, 133);
        label(gui, "Base cam X / Y", 16, 155);
        int editorWidth = Math.max(190, Math.min(360, width / 2 - 12));
        int fieldX = 16 + 82;
        int fieldWidth = Math.max(80, editorWidth - 92);
        int half = Math.max(36, (fieldWidth - 4) / 2);
        label(gui, "Name", fieldX, 165);
        label(gui, "BBS state", fieldX + half + 4, 165);

        renderPreview(gui);
        gui.drawString(font, trim(status, Math.max(20, width - 24)), 12, height - 18, 0xFFCCCCCC, false);
        super.render(gui, mouseX, mouseY, partialTick);
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
        int left = Math.max(218, Math.min(390, width / 2 + 12));
        int right = width - 12;
        int top = 30;
        int bottom = height - 34;
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
