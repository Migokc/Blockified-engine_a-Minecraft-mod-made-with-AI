package com.fnfmod.client.gui;

import com.fnfmod.client.ClientOptions;
import com.fnfmod.client.FnfKeys;
import com.fnfmod.client.anim.CharacterAnimations;
import com.fnfmod.client.math.Easing;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Searchable player/opponent animation-set picker with a live BBS preview. */
public final class AnimationSetPickerScreen extends Screen implements TextInputAwareScreen {
    private static final int ROW = 24;
    private static final long SCROLL_TWEEN_NANOS = 200_000_000L;
    private static final long PREVIEW_BEAT_NANOS = 500_000_000L; // 120 BPM
    private static final String[] DIRECTION_ACTIONS = {"left", "down", "up", "right"};

    private final Screen parent;
    private final boolean opponent;
    private EditBox search;
    private List<String> all = List.of();
    private List<String> visible = List.of();
    private int selectedIndex;
    private double scrollPx;
    private double scrollTargetPx;
    private double scrollFromPx;
    private long scrollTweenStart;
    private boolean scrollTweenActive;
    private String previewedSet = "";
    private String previewedAction = "idle";
    private int idleBeat;
    private boolean previewHasSecondIdle;
    private long returnToIdleAt;
    private long nextIdleAt;
    private boolean playerUsePlayerSkin;
    private boolean botUsePlayerSkin;

    public AnimationSetPickerScreen(Screen parent, boolean opponent) {
        super(Component.literal(opponent ? "Opponent Animations" : "Player Animations"));
        this.parent = parent;
        this.opponent = opponent;
    }

    @Override
    protected void init() {
        all = List.copyOf(CharacterAnimations.listSets());
        visible = all;
        String current = opponent ? ClientOptions.get().opponentAnimationSet
                : ClientOptions.get().animationSet;
        playerUsePlayerSkin = ClientOptions.get().playerUsePlayerSkin;
        botUsePlayerSkin = ClientOptions.get().botUsePlayerSkin;
        selectedIndex = Math.max(0, indexOf(visible, current));
        resetScroll(true);

        search = addRenderableWidget(new EditBox(font, panelX() + 12, panelY() + 26,
                Math.max(80, listRight() - panelX() - 20), 18,
                Component.literal("Search animations")));
        search.setHint(Component.literal("Search animations..."));
        search.setMaxLength(128);
        search.setResponder(this::filter);
        setFocused(search);
        search.setFocused(true);
        previewSelection();
    }

    private String role() { return opponent ? "opponent" : "player"; }

    private void filter(String queryText) {
        String query = queryText == null ? "" : queryText.trim().toLowerCase(Locale.ROOT);
        if (query.isEmpty()) {
            visible = all;
        } else {
            ArrayList<String> filtered = new ArrayList<>();
            for (String name : all) {
                if (displayName(name).toLowerCase(Locale.ROOT).contains(query)) filtered.add(name);
            }
            visible = filtered;
        }
        selectedIndex = 0;
        resetScroll(false);
        previewSelection();
    }

    private int panelWidth() { return Math.min(620, Math.max(330, width - 32)); }

    private int panelHeight() { return Math.min(350, Math.max(210, height - 32)); }

    private int panelX() { return (width - panelWidth()) / 2; }

    private int panelY() { return Math.max(8, (height - panelHeight()) / 2); }

    private int listRight() {
        return panelX() + Math.max(154, Math.min(250, (int) (panelWidth() * 0.43)));
    }

    private int listTop() { return panelY() + 50; }

    private int listHeight() { return Math.max(ROW, ((panelHeight() - 96) / ROW) * ROW); }

    private double maxScroll() { return Math.max(0, visible.size() * (double) ROW - listHeight()); }

    private void resetScroll(boolean centerSelection) {
        double target = centerSelection ? selectedIndex * (double) ROW - (listHeight() - ROW) / 2.0 : 0;
        scrollPx = scrollTargetPx = scrollFromPx = Mth.clamp(target, 0, maxScroll());
        scrollTweenActive = false;
    }

    private void updateScrollTween() {
        if (!scrollTweenActive) return;
        double progress = (System.nanoTime() - scrollTweenStart) / (double) SCROLL_TWEEN_NANOS;
        if (progress >= 1) {
            scrollPx = scrollTargetPx;
            scrollTweenActive = false;
            return;
        }
        double eased = Easing.apply("expoOut", progress);
        scrollPx = scrollFromPx + (scrollTargetPx - scrollFromPx) * eased;
    }

    private void scrollTo(double target) {
        target = Mth.clamp(target, 0, maxScroll());
        if (Math.abs(target - scrollTargetPx) < 0.01) return;
        updateScrollTween();
        scrollFromPx = scrollPx;
        scrollTargetPx = target;
        scrollTweenStart = System.nanoTime();
        scrollTweenActive = Math.abs(scrollTargetPx - scrollFromPx) > 0.01;
        if (!scrollTweenActive) scrollPx = scrollTargetPx;
    }

    private void clampSelection() {
        if (visible.isEmpty()) {
            selectedIndex = 0;
            resetScroll(false);
            return;
        }
        selectedIndex = Mth.clamp(selectedIndex, 0, visible.size() - 1);
        double top = selectedIndex * (double) ROW;
        double target = scrollTargetPx;
        if (top < target) target = top;
        else if (top + ROW > target + listHeight()) target = top + ROW - listHeight();
        scrollTo(target);
    }

    private String selectedSet() {
        if (visible.isEmpty()) return "";
        clampSelection();
        return visible.get(selectedIndex);
    }

    private void previewSelection() {
        String set = selectedSet();
        previewedSet = set;
        previewedAction = "idle";
        idleBeat = 0;
        previewHasSecondIdle = CharacterAnimations.hasAction(set, role(), "idle2");
        returnToIdleAt = 0;
        nextIdleAt = 0;
        if (minecraft == null || minecraft.player == null) return;
        if (set.isEmpty() || CharacterAnimations.isDisabled(set)) {
            CharacterAnimations.stopPreview();
            return;
        }
        CharacterAnimations.prepare(minecraft.player, set, role(), selectedUsePlayerSkin());
        playIdlePreview();
    }

    private void playPreview(String action, boolean temporary) {
        if (minecraft == null || minecraft.player == null || previewedSet.isEmpty()
                || CharacterAnimations.isDisabled(previewedSet)) return;
        long now = System.nanoTime();
        previewedAction = action;
        CharacterAnimations.play(minecraft.player, previewedSet, role(), action,
                selectedUsePlayerSkin());
        returnToIdleAt = temporary ? now + PREVIEW_BEAT_NANOS : 0;
        nextIdleAt = 0;
    }

    /** Match gameplay: characters with two idles alternate them once per preview beat. */
    private void playIdlePreview() {
        if (minecraft == null || minecraft.player == null || previewedSet.isEmpty()
                || CharacterAnimations.isDisabled(previewedSet)) return;
        boolean loop = CharacterAnimations.loopIdle(previewedSet, role());
        boolean second = !loop && previewHasSecondIdle && (idleBeat & 1) == 1;
        String action = second ? "idle2" : "idle";
        previewedAction = action;
        if (CharacterAnimations.play(minecraft.player, previewedSet, role(), action,
                selectedUsePlayerSkin()) == null && second) {
            // A stale/inherited mapping can claim idle2 even when the selected
            // form cannot play it. Downgrade this preview to the single-idle cadence.
            previewHasSecondIdle = false;
            previewedAction = "idle";
            CharacterAnimations.play(minecraft.player, previewedSet, role(), "idle",
                    selectedUsePlayerSkin());
        }
        idleBeat++;
        returnToIdleAt = 0;
        // Two idles alternate each beat. A single idle bops every two beats,
        // matching normal FNF character cadence at the 120 BPM preview tempo.
        nextIdleAt = loop ? 0 : System.nanoTime() + PREVIEW_BEAT_NANOS
                * (previewHasSecondIdle ? 1 : 2);
    }

    private void updatePreview() {
        long now = System.nanoTime();
        if (returnToIdleAt > 0 && now >= returnToIdleAt) {
            returnToIdleAt = 0;
            playIdlePreview();
        } else if (nextIdleAt > 0 && now >= nextIdleAt) {
            playIdlePreview();
        }
    }

    private void choose() {
        String set = selectedSet();
        if (set.isEmpty()) return;
        if (opponent) ClientOptions.get().opponentAnimationSet = set;
        else ClientOptions.get().animationSet = set;
        if (opponent) ClientOptions.get().botUsePlayerSkin = botUsePlayerSkin;
        else ClientOptions.get().playerUsePlayerSkin = playerUsePlayerSkin;
        ClientOptions.save();
        onClose();
    }

    private boolean selectedUsePlayerSkin() {
        return opponent ? botUsePlayerSkin : playerUsePlayerSkin;
    }

    private boolean skinChoiceAllowed() {
        String set = selectedSet();
        return !set.isEmpty() && CharacterAnimations.allowsPlayerSkinSelection(set, role());
    }

    private boolean skinChoiceSupported() {
        String set = selectedSet();
        return !set.isEmpty() && CharacterAnimations.supportsPlayerSkin(set, role());
    }

    private boolean skinButtonEnabled() {
        return skinChoiceAllowed() && skinChoiceSupported();
    }

    private String skinButtonLabel() {
        String owner = opponent ? "Bot" : "Player";
        if (!skinChoiceAllowed()) return owner + " Skin: Locked";
        if (!skinChoiceSupported()) return owner + " Skin: Unavailable";
        return owner + (selectedUsePlayerSkin() ? " Skin: Yours" : " Skin: Form");
    }

    @Override
    public void render(GuiGraphics gui, int mouseX, int mouseY, float partialTick) {
        gui.fill(0, 0, width, height, 0xF20A0A10);
        int x0 = panelX(), y0 = panelY(), boxW = panelWidth(), boxH = panelHeight();
        int listRight = listRight();
        gui.fill(x0, y0, x0 + boxW, y0 + boxH, 0xFF101018);
        gui.renderOutline(x0, y0, boxW, boxH, 0xFF6A70FF);
        gui.drawCenteredString(font, title, x0 + boxW / 2, y0 + 9, 0xFFFFFFFF);

        clampSelection();
        updateScrollTween();
        int top = listTop(), height = listHeight();
        int first = Math.max(0, (int) Math.floor(scrollPx / ROW));
        double offset = scrollPx - first * (double) ROW;
        gui.enableScissor(x0 + 12, top, listRight, top + height);
        for (int row = 0; row < height / ROW + 2; row++) {
            int index = first + row;
            if (index >= visible.size()) break;
            String name = visible.get(index);
            int rowY = top + row * ROW - (int) Math.round(offset);
            boolean selected = index == selectedIndex;
            boolean hovered = mouseX >= x0 + 12 && mouseX < listRight
                    && mouseY >= Math.max(top, rowY) && mouseY < Math.min(top + height, rowY + ROW - 1);
            gui.fill(x0 + 12, rowY, listRight, rowY + ROW - 1,
                    selected ? 0xFF4B5070 : hovered ? 0xFF303442 : 0xFF20202A);
            gui.drawString(font, displayName(name), x0 + 18, rowY + 8,
                    selected ? 0xFFFFFFFF : 0xFFCCCCCC, false);
        }
        gui.disableScissor();
        if (visible.isEmpty()) {
            gui.drawCenteredString(font, "No matching animations", (x0 + 12 + listRight) / 2,
                    top + 8, 0xFFAAAAAA);
        }

        renderScrollbar(gui, listRight, top, height);
        updatePreview();
        renderPreview(gui, listRight + 7, y0 + 26, x0 + boxW - 12, y0 + boxH - 35);

        int buttonY = y0 + boxH - 27;
        int buttonCount = 3;
        int buttonWidth = (boxW - 12 - (buttonCount - 1) * 8) / buttonCount;
        renderButton(gui, x0 + 6, buttonY, buttonWidth, "Cancel", mouseX, mouseY);
        renderButton(gui, x0 + 14 + buttonWidth, buttonY, buttonWidth,
                skinButtonLabel(), mouseX, mouseY, skinButtonEnabled());
        renderButton(gui, x0 + 22 + buttonWidth * 2, buttonY, buttonWidth, "Select", mouseX, mouseY);
        super.render(gui, mouseX, mouseY, partialTick);
    }

    private void renderScrollbar(GuiGraphics gui, int listRight, int top, int height) {
        double max = maxScroll();
        if (max <= 0) return;
        int trackX = listRight - 3;
        int thumbHeight = Math.max(12,
                (int) Math.round(height * (height / (visible.size() * (double) ROW))));
        int thumbY = top + (int) Math.round((height - thumbHeight) * (scrollPx / max));
        gui.fill(trackX, top, trackX + 2, top + height, 0xFF252733);
        gui.fill(trackX, thumbY, trackX + 2, thumbY + thumbHeight, 0xFF8A90C0);
    }

    private void renderPreview(GuiGraphics gui, int left, int top, int right, int bottom) {
        if (right - left < 70 || bottom - top < 80) return;
        gui.fill(left, top, right, bottom, 0xFF161720);
        gui.renderOutline(left, top, right - left, bottom - top, 0xFF454A68);
        gui.drawCenteredString(font, displayName(previewedSet), (left + right) / 2,
                top + 7, 0xFFFFFFFF);

        int previewBottom = bottom - 24;
        int centerX = (left + right) / 2;
        int centerY = (top + previewBottom) / 2 + 16;
        if (minecraft != null && minecraft.player != null && !previewedSet.isEmpty()
                && !CharacterAnimations.isDisabled(previewedSet)) {
            float[] camera = CharacterAnimations.baseCameraOffset(previewedSet, role());
            int shiftX = Math.round(-camera[0] * 12);
            int shiftY = Math.round(camera[1] * 12);
            gui.fill(centerX - 7, centerY, centerX + 8, centerY + 1, 0xAAFF4444);
            gui.fill(centerX, centerY - 7, centerX + 1, centerY + 8, 0xAAFF4444);
            int scale = Mth.clamp((previewBottom - top) / 3, 30, 82);
            renderFixedCharacter(gui, left + 3, top + 18, right - 3, previewBottom,
                    centerX + shiftX, centerY + shiftY, scale,
                    CharacterAnimations.rotation(previewedSet, role()), minecraft.player);
        } else {
            gui.drawCenteredString(font, CharacterAnimations.isDisabled(previewedSet)
                            ? "BBS animation disabled" : "Preview unavailable",
                    centerX, centerY, 0xFFAAAAAA);
        }
        gui.drawCenteredString(font, previewedAction, centerX, bottom - 29, 0xFFFFFFFF);
        gui.drawCenteredString(font, previewKeyHint(), centerX, bottom - 16, 0xFFAAAEC5);
    }

    private String previewKeyHint() {
        StringBuilder keys = new StringBuilder();
        for (int i = 0; i < FnfKeys.NOTE_KEYS.length; i++) {
            if (i > 0) keys.append('/');
            keys.append(FnfKeys.NOTE_KEYS[i].getTranslatedKeyMessage().getString());
        }
        return trim(keys + ": preview directions", 38);
    }

    private void renderButton(GuiGraphics gui, int x, int y, int buttonWidth, String text,
                              int mouseX, int mouseY) {
        renderButton(gui, x, y, buttonWidth, text, mouseX, mouseY, true);
    }

    private void renderButton(GuiGraphics gui, int x, int y, int buttonWidth, String text,
                              int mouseX, int mouseY, boolean enabled) {
        boolean hover = mouseX >= x && mouseX < x + buttonWidth && mouseY >= y && mouseY < y + 18;
        gui.fill(x, y, x + buttonWidth, y + 18,
                !enabled ? 0xFF22232B : hover ? 0xFF505675 : 0xFF303442);
        gui.renderOutline(x, y, buttonWidth, 18, enabled ? 0xFF6A7080 : 0xFF42444F);
        gui.drawCenteredString(font, text, x + buttonWidth / 2, y + 5,
                enabled ? 0xFFFFFFFF : 0xFF777986);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            onClose();
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
            choose();
            return true;
        }
        boolean typing = search != null && search.isFocused();
        int lane = typing ? -1 : FnfKeys.laneForKey(keyCode, scanCode);
        if (lane >= 0) {
            playPreview(DIRECTION_ACTIONS[lane], true);
            return true;
        }
        if (!typing && (keyCode == GLFW.GLFW_KEY_UP || keyCode == GLFW.GLFW_KEY_DOWN)) {
            selectedIndex += keyCode == GLFW.GLFW_KEY_UP ? -1 : 1;
            clampSelection();
            previewSelection();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            int x0 = panelX(), y0 = panelY(), boxW = panelWidth(), boxH = panelHeight();
            int listRight = listRight(), top = listTop();
            if (mouseX >= x0 + 12 && mouseX < listRight
                    && mouseY >= top && mouseY < top + listHeight()) {
                search.setFocused(false);
                setFocused(null);
                updateScrollTween();
                int index = (int) Math.floor((mouseY - top + scrollPx) / ROW);
                if (index >= 0 && index < visible.size()) {
                    selectedIndex = index;
                    previewSelection();
                }
                return true;
            }
            int buttonY = y0 + boxH - 27;
            int buttonCount = 3;
            int buttonWidth = (boxW - 12 - (buttonCount - 1) * 8) / buttonCount;
            if (mouseY >= buttonY && mouseY < buttonY + 18
                    && mouseX >= x0 + 6 && mouseX < x0 + 6 + buttonWidth) {
                onClose();
                return true;
            }
            if (mouseY >= buttonY && mouseY < buttonY + 18
                    && mouseX >= x0 + 14 + buttonWidth
                    && mouseX < x0 + 14 + buttonWidth * 2) {
                if (!skinButtonEnabled()) return true;
                if (opponent) botUsePlayerSkin = !botUsePlayerSkin;
                else playerUsePlayerSkin = !playerUsePlayerSkin;
                previewSelection();
                return true;
            }
            if (mouseY >= buttonY && mouseY < buttonY + 18
                    && mouseX >= x0 + 22 + buttonWidth * 2
                    && mouseX < x0 + 22 + buttonWidth * 3) {
                choose();
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (scrollY != 0) {
            selectedIndex += scrollY > 0 ? -1 : 1;
            clampSelection();
            previewSelection();
        }
        return true;
    }

    @Override
    public void renderBackground(GuiGraphics gui, int mouseX, int mouseY, float partialTick) {
        // render() owns the opaque foreground dialog and avoids menu-blur overlap.
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
    public boolean isTextInputActive() {
        return search != null && search.isFocused();
    }

    @Override
    public boolean isPauseScreen() { return false; }

    private static String displayName(String name) {
        if (CharacterAnimations.NONE_SET.equalsIgnoreCase(name)) return "None";
        if (CharacterAnimations.DEFAULT_SET.equalsIgnoreCase(name)) return "Default (song)";
        return name == null || name.isBlank() ? "None" : name + ".json";
    }

    private static int indexOf(List<String> values, String wanted) {
        if (wanted == null) return -1;
        for (int i = 0; i < values.size(); i++) {
            if (values.get(i).equalsIgnoreCase(wanted)) return i;
        }
        return -1;
    }

    private static String trim(String text, int max) {
        if (text == null || text.length() <= max) return text == null ? "" : text;
        return text.substring(0, Math.max(0, max - 3)) + "...";
    }

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
            InventoryScreen.renderEntityInInventory(gui, centerX, centerY, scale / entityScale,
                    translation, pose, new Quaternionf(), entity);
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
}
