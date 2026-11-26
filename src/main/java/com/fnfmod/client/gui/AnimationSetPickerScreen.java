package com.fnfmod.client.gui;

import com.fnfmod.client.ClientOptions;
import com.fnfmod.client.FnfKeys;
import com.fnfmod.client.anim.CharacterAnimations;
import com.fnfmod.client.gameplay.NativeFilePicker;
import com.fnfmod.client.gameplay.WorldCharacter;
import com.fnfmod.client.gameplay.WorldCharacterLibrary;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.nio.file.Files;
import java.nio.file.Path;

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
    private final TweenedScroll scroll = new TweenedScroll(SCROLL_TWEEN_NANOS);
    private boolean draggingScrollThumb;
    private String previewedSet = "";
    private String previewedAction = "idle";
    private int idleBeat;
    private boolean previewHasSecondIdle;
    private long returnToIdleAt;
    private long nextIdleAt;
    private String playerSkinSource;
    private String botSkinSource;
    private Path playerSkinFile;
    private Path botSkinFile;
    private boolean playerSkinSlim;
    private boolean botSkinSlim;
    private boolean characterFlipped;
    private boolean characterDirectionsFlipped;
    private boolean characterFrameCenterCamera;
    private boolean characterDialogOpen;
    private String playerSkinAccount = "";
    private String botSkinAccount = "";
    private SkinDialog skinDialog = SkinDialog.NONE;
    private Path pendingSkinFile;
    private EditBox accountField;
    private boolean accountLoading;
    private String accountError = "";
    private enum ContentTab { CHARACTERS, ANIMATIONS }
    private ContentTab contentTab = ContentTab.ANIMATIONS;
    private WorldCharacter previewCharacter;
    private long previewCharacterUpdateNano;
    private final Map<String, Path> characterPaths = new LinkedHashMap<>();

    private enum SkinDialog { NONE, SOURCE, MODEL, ACCOUNT }

    public AnimationSetPickerScreen(Screen parent, boolean opponent) {
        super(Component.literal(opponent ? "Opponent Animations" : "Player Animations"));
        this.parent = parent;
        this.opponent = opponent;
    }

    @Override
    protected void init() {
        String current = opponent ? ClientOptions.get().opponentAnimationSet
                : ClientOptions.get().animationSet;
        String currentType = opponent ? ClientOptions.get().opponentAnimationSetType
                : ClientOptions.get().animationSetType;
        contentTab = ClientOptions.PERFORMER_TYPE_2D.equals(
                ClientOptions.normalizePerformerType(currentType))
                ? ContentTab.CHARACTERS : ContentTab.ANIMATIONS;
        reloadEntries();
        ClientOptions options = ClientOptions.get();
        playerSkinSource = options.playerSkinSource;
        botSkinSource = options.botSkinSource;
        playerSkinFile = pathOrNull(options.playerSkinFile);
        botSkinFile = pathOrNull(options.botSkinFile);
        playerSkinSlim = options.playerSkinSlim;
        botSkinSlim = options.botSkinSlim;
        characterFlipped = opponent ? options.opponentAnimationSetFlipped
                : options.animationSetFlipped;
        characterDirectionsFlipped = opponent ? options.opponentAnimationSetDirectionsFlipped
                : options.animationSetDirectionsFlipped;
        characterFrameCenterCamera = opponent ? options.opponentAnimationSetFrameCenterCamera
                : options.animationSetFrameCenterCamera;
        playerSkinAccount = options.playerSkinAccount;
        botSkinAccount = options.botSkinAccount;
        selectedIndex = Math.max(0, indexOf(visible, current));
        resetScroll(true);

        search = addRenderableWidget(new EditBox(font, listX(), panelY() + 28,
                listWidth(), 18,
                Component.literal("Search performers")));
        search.setHint(Component.literal(contentTab == ContentTab.CHARACTERS
                ? "Search characters..." : "Search animations..."));
        search.setMaxLength(128);
        search.setResponder(this::filter);
        setFocused(search);
        search.setFocused(true);
        previewSelection();
    }

    private void reloadEntries() {
        characterPaths.clear();
        if (contentTab == ContentTab.CHARACTERS) {
            ArrayList<String> characters = new ArrayList<>();
            characters.add(CharacterAnimations.DEFAULT_SET);
            for (WorldCharacterLibrary.Entry entry : WorldCharacterLibrary.list()) {
                String selection = entry.id();
                characters.add(selection);
                characterPaths.put(selection.toLowerCase(Locale.ROOT), entry.json());
            }
            all = List.copyOf(characters);
        } else {
            all = List.copyOf(CharacterAnimations.listSets());
        }
        visible = all;
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

    private int listX() { return panelX() + 12; }

    private int listWidth() { return listRight() - listX(); }

    private int tabY() { return panelY() + 50; }

    private int listTop() { return panelY() + 74; }

    private int listHeight() { return Math.max(ROW, ((panelHeight() - 118) / ROW) * ROW); }

    private double maxScroll() { return Math.max(0, visible.size() * (double) ROW - listHeight()); }

    private void resetScroll(boolean centerSelection) {
        double target = centerSelection ? selectedIndex * (double) ROW - (listHeight() - ROW) / 2.0 : 0;
        scroll.reset(target, maxScroll());
    }

    private void scrollTo(double target) {
        scroll.animateTo(target, maxScroll());
    }

    private void clampSelection() {
        if (visible.isEmpty()) {
            selectedIndex = 0;
            resetScroll(false);
            return;
        }
        selectedIndex = Mth.clamp(selectedIndex, 0, visible.size() - 1);
        double top = selectedIndex * (double) ROW;
        double target = scroll.target();
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
        closeCharacterPreview();
        String set = selectedSet();
        previewedSet = set;
        previewedAction = "idle";
        idleBeat = 0;
        previewHasSecondIdle = contentTab == ContentTab.ANIMATIONS
                && CharacterAnimations.hasAction(set, role(), "idle2");
        returnToIdleAt = 0;
        nextIdleAt = 0;
        if (contentTab == ContentTab.CHARACTERS) {
            Path json = characterPaths.get(set.toLowerCase(Locale.ROOT));
            if (json == null) json = WorldCharacterLibrary.resolve(set);
            previewCharacter = json == null ? null : WorldCharacter.load(json);
            if (previewCharacter != null) {
                previewCharacter.setBillboard(false);
                previewCharacter.setRenderMode("flat");
                if (characterFlipped) previewCharacter.setFlipX(!previewCharacter.flipX());
                previewCharacterUpdateNano = System.nanoTime();
                previewedAction = previewCharacter.current();
                previewHasSecondIdle = previewCharacter.animationNames().stream()
                        .anyMatch(name -> name.equalsIgnoreCase("idle2")
                                || name.equalsIgnoreCase("danceRight"));
                nextIdleAt = System.nanoTime() + PREVIEW_BEAT_NANOS;
            }
            CharacterAnimations.stopPreview();
            return;
        }
        if (minecraft == null || minecraft.player == null) return;
        if (set.isEmpty() || CharacterAnimations.isDisabled(set)) {
            CharacterAnimations.stopPreview();
            return;
        }
        CharacterAnimations.prepare(minecraft.player, set, role(), selectedSkinChoice());
        playIdlePreview();
    }

    private void playPreview(String action, boolean temporary) {
        if (previewCharacter != null) {
            String psychAction = switch (action) {
                case "left" -> characterDirectionsFlipped ? "singRIGHT" : "singLEFT";
                case "down" -> "singDOWN";
                case "up" -> "singUP";
                case "right" -> characterDirectionsFlipped ? "singLEFT" : "singRIGHT";
                default -> action;
            };
            if (previewCharacter.play(psychAction, true)) previewedAction = psychAction;
            returnToIdleAt = temporary ? System.nanoTime() + PREVIEW_BEAT_NANOS : 0;
            nextIdleAt = 0;
            return;
        }
        if (minecraft == null || minecraft.player == null || previewedSet.isEmpty()
                || CharacterAnimations.isDisabled(previewedSet)) return;
        long now = System.nanoTime();
        previewedAction = action;
        CharacterAnimations.play(minecraft.player, previewedSet, role(), action,
                selectedSkinChoice());
        returnToIdleAt = temporary ? now + PREVIEW_BEAT_NANOS : 0;
        nextIdleAt = 0;
    }

    /** Match gameplay: characters with two idles alternate them once per preview beat. */
    private void playIdlePreview() {
        if (previewCharacter != null) {
            previewCharacter.dance(true);
            previewedAction = previewCharacter.current();
            idleBeat++;
            returnToIdleAt = 0;
            nextIdleAt = System.nanoTime() + PREVIEW_BEAT_NANOS;
            return;
        }
        if (minecraft == null || minecraft.player == null || previewedSet.isEmpty()
                || CharacterAnimations.isDisabled(previewedSet)) return;
        boolean loop = CharacterAnimations.loopIdle(previewedSet, role());
        boolean second = !loop && previewHasSecondIdle && (idleBeat & 1) == 1;
        String action = second ? "idle2" : "idle";
        previewedAction = action;
        if (CharacterAnimations.play(minecraft.player, previewedSet, role(), action,
                selectedSkinChoice()) == null && second) {
            // A stale/inherited mapping can claim idle2 even when the selected
            // form cannot play it. Downgrade this preview to the single-idle cadence.
            previewHasSecondIdle = false;
            previewedAction = "idle";
            CharacterAnimations.play(minecraft.player, previewedSet, role(), "idle",
                    selectedSkinChoice());
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
        if (previewCharacter != null) {
            double dt = previewCharacterUpdateNano == 0 ? 0
                    : Math.min(0.1, (now - previewCharacterUpdateNano) / 1.0e9);
            previewCharacterUpdateNano = now;
            previewCharacter.update(dt, 125, 1);
            previewedAction = previewCharacter.current();
        }
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
        ClientOptions options = ClientOptions.get();
        if (opponent) {
            options.opponentAnimationSet = set;
            options.opponentAnimationSetType = contentTab == ContentTab.CHARACTERS
                    ? ClientOptions.PERFORMER_TYPE_2D : ClientOptions.PERFORMER_TYPE_3D;
            options.opponentAnimationSetFlipped = characterFlipped;
            options.opponentAnimationSetDirectionsFlipped = characterDirectionsFlipped;
            options.opponentAnimationSetFrameCenterCamera = characterFrameCenterCamera;
            options.botSkinSource = botSkinSource;
            options.botSkinFile = botSkinFile == null ? "" : botSkinFile.toString();
            options.botSkinSlim = botSkinSlim;
            options.botSkinAccount = botSkinAccount;
            options.botUsePlayerSkin = ClientOptions.SKIN_SOURCE_PLAYER.equals(botSkinSource);
        } else {
            options.animationSet = set;
            options.animationSetType = contentTab == ContentTab.CHARACTERS
                    ? ClientOptions.PERFORMER_TYPE_2D : ClientOptions.PERFORMER_TYPE_3D;
            options.animationSetFlipped = characterFlipped;
            options.animationSetDirectionsFlipped = characterDirectionsFlipped;
            options.animationSetFrameCenterCamera = characterFrameCenterCamera;
            options.playerSkinSource = playerSkinSource;
            options.playerSkinFile = playerSkinFile == null ? "" : playerSkinFile.toString();
            options.playerSkinSlim = playerSkinSlim;
            options.playerSkinAccount = playerSkinAccount;
            options.playerUsePlayerSkin = ClientOptions.SKIN_SOURCE_PLAYER.equals(playerSkinSource);
        }
        ClientOptions.save();
        onClose();
    }

    private CharacterAnimations.SkinChoice selectedSkinChoice() {
        String source = opponent ? botSkinSource : playerSkinSource;
        Path file = opponent ? botSkinFile : playerSkinFile;
        boolean slim = opponent ? botSkinSlim : playerSkinSlim;
        String account = opponent ? botSkinAccount : playerSkinAccount;
        if (ClientOptions.SKIN_SOURCE_PLAYER.equals(source)) return CharacterAnimations.SkinChoice.player();
        if (ClientOptions.SKIN_SOURCE_FILE.equals(source) && file != null) {
            return CharacterAnimations.SkinChoice.file(file, slim);
        }
        if (ClientOptions.SKIN_SOURCE_ACCOUNT.equals(source) && account != null && !account.isBlank()) {
            return CharacterAnimations.SkinChoice.account(account);
        }
        return CharacterAnimations.SkinChoice.form();
    }

    private boolean skinChoiceAllowed() {
        String set = selectedSet();
        return contentTab == ContentTab.ANIMATIONS && !set.isEmpty()
                && CharacterAnimations.allowsPlayerSkinSelection(set, role());
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
        String source = opponent ? botSkinSource : playerSkinSource;
        if (ClientOptions.SKIN_SOURCE_PLAYER.equals(source)) return owner + " Skin: Yours";
        if (ClientOptions.SKIN_SOURCE_FILE.equals(source)) {
            Path file = opponent ? botSkinFile : playerSkinFile;
            boolean slim = opponent ? botSkinSlim : playerSkinSlim;
            String name = file == null ? "Missing" : file.getFileName().toString();
            return trim(owner + " Skin: " + name + " (" + (slim ? "Slim" : "Wide") + ")", 31);
        }
        if (ClientOptions.SKIN_SOURCE_ACCOUNT.equals(source)) {
            String account = opponent ? botSkinAccount : playerSkinAccount;
            return trim(owner + " Skin: @" + (account == null || account.isBlank()
                    ? "Account" : account), 31);
        }
        return owner + " Skin: Form";
    }

    @Override
    public void render(GuiGraphics gui, int mouseX, int mouseY, float partialTick) {
        BlockifiedScreenStyle.backdrop(gui, width, height);
        int x0 = panelX(), y0 = panelY(), boxW = panelWidth(), boxH = panelHeight();
        int listRight = listRight();
        BlockifiedScreenStyle.panel(gui, x0, y0, boxW, boxH);
        gui.drawCenteredString(font, title, x0 + boxW / 2, y0 + 9, 0xFFFFFFFF);
        int tabWidth = (listWidth() - 4) / 2;
        renderTab(gui, listX(), tabY(), tabWidth, "Characters",
                contentTab == ContentTab.CHARACTERS, mouseX, mouseY);
        renderTab(gui, listX() + tabWidth + 4, tabY(), listWidth() - tabWidth - 4,
                "Animations", contentTab == ContentTab.ANIMATIONS, mouseX, mouseY);

        // Merely rendering must not force the selected row back onscreen. Wheel
        // and thumb scrolling are viewport operations; only keyboard selection
        // changes should call clampSelection() to follow the active row.
        if (visible.isEmpty()) selectedIndex = 0;
        else selectedIndex = Mth.clamp(selectedIndex, 0, visible.size() - 1);
        scroll.update();
        scroll.clampTo(maxScroll());
        int top = listTop(), height = listHeight();
        int first = Math.max(0, (int) Math.floor(scroll.position() / ROW));
        double offset = scroll.position() - first * (double) ROW;
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
                    selected ? BlockifiedScreenStyle.ACCENT_DARK
                            : hovered ? BlockifiedScreenStyle.ACCENT_DEEP : 0xFF20202A);
            gui.drawString(font, displayName(name), x0 + 18, rowY + 8,
                    selected ? 0xFFFFFFFF : 0xFFCCCCCC, false);
        }
        gui.disableScissor();
        if (visible.isEmpty()) {
            gui.drawCenteredString(font, contentTab == ContentTab.CHARACTERS
                            ? "No matching characters" : "No matching animations",
                    (x0 + 12 + listRight) / 2,
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
                contentTab == ContentTab.CHARACTERS ? "Options..." : skinButtonLabel(),
                mouseX, mouseY, contentTab == ContentTab.CHARACTERS || skinButtonEnabled());
        renderButton(gui, x0 + 22 + buttonWidth * 2, buttonY, buttonWidth, "Select", mouseX, mouseY);
        super.render(gui, mouseX, mouseY, partialTick);
        if (characterDialogOpen) renderCharacterDialog(gui, mouseX, mouseY);
        else if (skinDialog != SkinDialog.NONE) renderSkinDialog(gui, mouseX, mouseY);
    }

    private void renderCharacterDialog(GuiGraphics gui, int mouseX, int mouseY) {
        gui.pose().pushPose();
        gui.pose().translate(0, 0, 1000);
        gui.fill(0, 0, width, height, 0xB8000000);
        int w = Math.min(290, width - 32), h = 134;
        int x = (width - w) / 2, y = (height - h) / 2;
        BlockifiedScreenStyle.panel(gui, x, y, w, h);
        gui.drawCenteredString(font, "2D Character Options", x + w / 2, y + 10, 0xFFFFFFFF);
        renderModalButton(gui, x + 12, y + 30, w - 24,
                characterFlipped ? "Sprite: Flipped" : "Sprite: Normal", mouseX, mouseY);
        renderModalButton(gui, x + 12, y + 54, w - 24,
                characterDirectionsFlipped ? "LEFT/RIGHT: Swapped" : "LEFT/RIGHT: Normal",
                mouseX, mouseY);
        renderModalButton(gui, x + 12, y + 78, w - 24,
                characterFrameCenterCamera ? "Camera: Frame Center" : "Camera: JSON Offset",
                mouseX, mouseY);
        renderModalButton(gui, x + 12, y + 102, w - 24, "Done", mouseX, mouseY);
        gui.pose().popPose();
    }

    private void renderSkinDialog(GuiGraphics gui, int mouseX, int mouseY) {
        pollAccountSkin();
        if (skinDialog == SkinDialog.NONE) return;
        gui.pose().pushPose();
        gui.pose().translate(0, 0, 1000);
        gui.fill(0, 0, width, height, 0xB8000000);
        int w = Math.min(280, width - 32);
        int rows = skinDialog == SkinDialog.SOURCE ? 5
                : skinDialog == SkinDialog.ACCOUNT ? 4 : 3;
        int h = 38 + rows * 24;
        int x = (width - w) / 2;
        int y = (height - h) / 2;
        BlockifiedScreenStyle.panel(gui, x, y, w, h);
        gui.drawCenteredString(font, skinDialog == SkinDialog.SOURCE
                        ? "Choose skin source" : skinDialog == SkinDialog.ACCOUNT
                        ? "Minecraft account name" : "Is this skin Slim or Wide?",
                x + w / 2, y + 10, 0xFFFFFFFF);
        if (skinDialog == SkinDialog.SOURCE) {
            renderModalButton(gui, x + 12, y + 30, w - 24, "Form's Skin", mouseX, mouseY);
            renderModalButton(gui, x + 12, y + 54, w - 24, "Current Player Skin", mouseX, mouseY);
            renderModalButton(gui, x + 12, y + 78, w - 24, "Minecraft Account...", mouseX, mouseY);
            renderModalButton(gui, x + 12, y + 102, w - 24, "Choose PNG File...", mouseX, mouseY);
            renderModalButton(gui, x + 12, y + 126, w - 24, "Cancel", mouseX, mouseY);
        } else if (skinDialog == SkinDialog.ACCOUNT) {
            if (accountField != null) {
                accountField.setX(x + 12);
                accountField.setY(y + 30);
                accountField.setWidth(w - 24);
                accountField.render(gui, mouseX, mouseY, 0);
            }
            String action = accountLoading ? "Loading..." : "Use Account";
            renderModalButton(gui, x + 12, y + 54, w - 24, action, mouseX, mouseY,
                    !accountLoading);
            renderModalButton(gui, x + 12, y + 78, w - 24, "Cancel", mouseX, mouseY);
            if (!accountError.isBlank()) {
                gui.drawCenteredString(font, accountError, x + w / 2, y + 102, 0xFFFF7777);
            }
        } else {
            String file = pendingSkinFile == null ? "" : trim(pendingSkinFile.getFileName().toString(), 32);
            gui.drawCenteredString(font, file, x + w / 2, y + 25, 0xFFAAAEC5);
            renderModalButton(gui, x + 12, y + 42, w - 24, "Wide (Steve)", mouseX, mouseY);
            renderModalButton(gui, x + 12, y + 66, w - 24, "Slim (Alex)", mouseX, mouseY);
            renderModalButton(gui, x + 12, y + 90, w - 24, "Cancel", mouseX, mouseY);
        }
        gui.pose().popPose();
    }

    private void renderModalButton(GuiGraphics gui, int x, int y, int w, String text,
                                   int mouseX, int mouseY) {
        renderButton(gui, x, y, w, text, mouseX, mouseY);
    }

    private void renderModalButton(GuiGraphics gui, int x, int y, int w, String text,
                                   int mouseX, int mouseY, boolean enabled) {
        renderButton(gui, x, y, w, text, mouseX, mouseY, enabled);
    }

    private void renderScrollbar(GuiGraphics gui, int listRight, int top, int height) {
        double max = maxScroll();
        if (max <= 0) return;
        int trackX = listRight - 3;
        int thumbHeight = Math.max(12,
                (int) Math.round(height * (height / (visible.size() * (double) ROW))));
        int thumbY = top + (int) Math.round((height - thumbHeight) * (scroll.position() / max));
        gui.fill(trackX, top, trackX + 2, top + height, 0xFF252733);
        gui.fill(trackX, thumbY, trackX + 2, thumbY + thumbHeight,
                BlockifiedScreenStyle.ACCENT);
    }

    private int scrollbarX() { return listRight() - 3; }

    private int scrollbarThumbHeight() {
        return Math.max(12, (int) Math.round(listHeight()
                * (listHeight() / Math.max(1.0, visible.size() * (double) ROW))));
    }

    private void dragScrollbarTo(double mouseY) {
        double max = maxScroll();
        if (max <= 0) return;
        int thumb = scrollbarThumbHeight();
        double fraction = (mouseY - listTop() - thumb / 2.0)
                / Math.max(1.0, listHeight() - thumb);
        scroll.reset(Mth.clamp(fraction, 0, 1) * max, max);
    }

    private void renderPreview(GuiGraphics gui, int left, int top, int right, int bottom) {
        if (right - left < 70 || bottom - top < 80) return;
        BlockifiedScreenStyle.inner(gui, left, top, right - left, bottom - top);
        gui.drawCenteredString(font, displayName(previewedSet), (left + right) / 2,
                top + 7, 0xFFFFFFFF);

        int previewBottom = bottom - 24;
        int centerX = (left + right) / 2;
        int centerY = (top + previewBottom) / 2 + 16;
        if (previewCharacter != null) {
            int availableW = Math.max(20, right - left - 18);
            int availableH = Math.max(20, previewBottom - top - 24);
            float scaledW = (float) (Math.max(1, previewCharacter.refW())
                    * Math.max(0.01, Math.abs(previewCharacter.scaleX())));
            float scaledH = (float) (Math.max(1, previewCharacter.refH())
                    * Math.max(0.01, Math.abs(previewCharacter.scaleY())));
            float fit = Math.min(availableW / scaledW, availableH / scaledH);
            previewCharacter.drawPreview(gui, centerX, centerY, fit);
        } else if (minecraft != null && minecraft.player != null && !previewedSet.isEmpty()
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

    private void renderTab(GuiGraphics gui, int x, int y, int w, String text, boolean selected,
                           int mouseX, int mouseY) {
        boolean hover = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + 20;
        gui.fill(x, y, x + w, y + 20, selected ? BlockifiedScreenStyle.ACCENT_DARK
                : hover ? BlockifiedScreenStyle.ACCENT_DEEP : 0xFF252733);
        if (selected) gui.fill(x, y + 18, x + w, y + 20, BlockifiedScreenStyle.ACCENT);
        gui.drawCenteredString(font, text, x + w / 2, y + 6,
                selected ? 0xFFFFFFFF : BlockifiedScreenStyle.TEXT_MUTED);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (characterDialogOpen) {
            if (keyCode == GLFW.GLFW_KEY_ESCAPE) characterDialogOpen = false;
            return true;
        }
        if (skinDialog != SkinDialog.NONE) {
            if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
                skinDialog = skinDialog == SkinDialog.ACCOUNT ? SkinDialog.SOURCE : SkinDialog.NONE;
                pendingSkinFile = null;
                accountField = null;
                accountLoading = false;
                accountError = "";
            } else if (skinDialog == SkinDialog.ACCOUNT && accountField != null) {
                if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
                    if (!accountLoading) applyAccountField();
                } else if (!accountLoading) accountField.keyPressed(keyCode, scanCode, modifiers);
            }
            return true;
        }
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
        if (characterDialogOpen) return clickCharacterDialog(mouseX, mouseY, button);
        if (skinDialog != SkinDialog.NONE) return clickSkinDialog(mouseX, mouseY, button);
        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            int x0 = panelX(), y0 = panelY(), boxW = panelWidth(), boxH = panelHeight();
            int listRight = listRight(), top = listTop();
            int tabWidth = (listWidth() - 4) / 2;
            if (mouseY >= tabY() && mouseY < tabY() + 20) {
                if (mouseX >= listX() && mouseX < listX() + tabWidth) {
                    switchTab(ContentTab.CHARACTERS);
                    return true;
                }
                if (mouseX >= listX() + tabWidth + 4 && mouseX < listX() + listWidth()) {
                    switchTab(ContentTab.ANIMATIONS);
                    return true;
                }
            }
            if (maxScroll() > 0 && mouseX >= scrollbarX() - 3 && mouseX <= scrollbarX() + 5
                    && mouseY >= top && mouseY <= top + listHeight()) {
                draggingScrollThumb = true;
                dragScrollbarTo(mouseY);
                return true;
            }
            if (mouseX >= x0 + 12 && mouseX < listRight
                    && mouseY >= top && mouseY < top + listHeight()) {
                search.setFocused(false);
                setFocused(null);
                scroll.update();
                int index = (int) Math.floor((mouseY - top + scroll.position()) / ROW);
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
                if (contentTab != ContentTab.CHARACTERS && !skinButtonEnabled()) return true;
                search.setFocused(false); setFocused(null);
                if (contentTab == ContentTab.CHARACTERS) characterDialogOpen = true;
                else skinDialog = SkinDialog.SOURCE;
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
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT && draggingScrollThumb) {
            dragScrollbarTo(mouseY);
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) draggingScrollThumb = false;
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        if (skinDialog == SkinDialog.ACCOUNT && accountField != null) {
            if (accountLoading) return true;
            return accountField.charTyped(codePoint, modifiers);
        }
        return super.charTyped(codePoint, modifiers);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (characterDialogOpen || skinDialog != SkinDialog.NONE) return true;
        if (scrollY != 0) {
            scrollTo(scroll.target() - scrollY * ROW * 1.5);
        }
        return true;
    }

    @Override
    public void renderBackground(GuiGraphics gui, int mouseX, int mouseY, float partialTick) {
        // render() owns the opaque foreground dialog and avoids menu-blur overlap.
    }

    @Override
    public void onClose() {
        closeCharacterPreview();
        CharacterAnimations.stopPreview();
        if (minecraft != null) minecraft.setScreen(parent);
    }

    @Override
    public void removed() {
        super.removed();
        if (!(minecraft != null && minecraft.screen == this)) {
            closeCharacterPreview();
            CharacterAnimations.stopPreview();
        }
    }

    @Override
    public boolean isTextInputActive() {
        return characterDialogOpen || skinDialog == SkinDialog.ACCOUNT
                || search != null && search.isFocused();
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

    private void switchTab(ContentTab tab) {
        if (tab == contentTab) return;
        contentTab = tab;
        reloadEntries();
        visible = all;
        selectedIndex = 0;
        resetScroll(false);
        if (search != null) {
            search.setValue("");
            search.setHint(Component.literal(tab == ContentTab.CHARACTERS
                    ? "Search characters..." : "Search animations..."));
        }
        previewSelection();
    }

    private boolean clickCharacterDialog(double mouseX, double mouseY, int button) {
        if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT) return true;
        int w = Math.min(290, width - 32), h = 134;
        int x = (width - w) / 2, y = (height - h) / 2;
        if (mouseX < x + 12 || mouseX >= x + w - 12) return true;
        int row = modalRow(mouseY, y + 30, 4);
        if (row == 0) {
            characterFlipped = !characterFlipped;
            previewSelection();
            characterDialogOpen = true;
        } else if (row == 1) {
            characterDirectionsFlipped = !characterDirectionsFlipped;
        } else if (row == 2) {
            characterFrameCenterCamera = !characterFrameCenterCamera;
        } else if (row == 3) characterDialogOpen = false;
        return true;
    }

    private void closeCharacterPreview() {
        if (previewCharacter != null) previewCharacter.close();
        previewCharacter = null;
        previewCharacterUpdateNano = 0;
    }

    private boolean clickSkinDialog(double mouseX, double mouseY, int button) {
        if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT) return true;
        int w = Math.min(280, width - 32);
        int rows = skinDialog == SkinDialog.SOURCE ? 5
                : skinDialog == SkinDialog.ACCOUNT ? 4 : 3;
        int h = 38 + rows * 24;
        int x = (width - w) / 2;
        int y = (height - h) / 2;
        if (mouseX < x + 12 || mouseX >= x + w - 12) return true;
        if (skinDialog == SkinDialog.SOURCE) {
            int row = modalRow(mouseY, y + 30, 5);
            if (row == 0) applySkinSource(ClientOptions.SKIN_SOURCE_FORM, null, false);
            else if (row == 1) applySkinSource(ClientOptions.SKIN_SOURCE_PLAYER, null, false);
            else if (row == 2) {
                openAccountField();
            } else if (row == 3) {
                NativeFilePicker.openFile("Choose Minecraft skin", new String[]{"*.png"}, "PNG skin")
                        .filter(Files::isRegularFile)
                        .ifPresent(path -> {
                            pendingSkinFile = path.toAbsolutePath().normalize();
                            skinDialog = SkinDialog.MODEL;
                        });
            } else if (row == 4) skinDialog = SkinDialog.NONE;
        } else if (skinDialog == SkinDialog.ACCOUNT) {
            if (!accountLoading && accountField != null
                    && accountField.mouseClicked(mouseX, mouseY, button)) return true;
            int row = modalRow(mouseY, y + 54, 2);
            if (row == 0 && !accountLoading) applyAccountField();
            else if (row == 1) {
                accountLoading = false;
                accountError = "";
                accountField = null;
                skinDialog = SkinDialog.SOURCE;
            }
        } else {
            int row = modalRow(mouseY, y + 42, 3);
            if (row == 0 || row == 1) {
                applySkinSource(ClientOptions.SKIN_SOURCE_FILE, pendingSkinFile, row == 1);
                pendingSkinFile = null;
            } else if (row == 2) {
                pendingSkinFile = null;
                skinDialog = SkinDialog.SOURCE;
            }
        }
        return true;
    }

    private static int modalRow(double mouseY, int top, int rows) {
        for (int row = 0; row < rows; row++) {
            int y = top + row * 24;
            if (mouseY >= y && mouseY < y + 18) return row;
        }
        return -1;
    }

    private void applySkinSource(String source, Path file, boolean slim) {
        if (opponent) {
            botSkinSource = source;
            if (file != null) botSkinFile = file;
            botSkinSlim = slim;
        } else {
            playerSkinSource = source;
            if (file != null) playerSkinFile = file;
            playerSkinSlim = slim;
        }
        skinDialog = SkinDialog.NONE;
        previewSelection();
    }

    private void openAccountField() {
        accountField = new EditBox(font, 0, 0, 100, 18, Component.literal("Minecraft account"));
        accountField.setMaxLength(16);
        accountField.setFilter(value -> value.matches("[A-Za-z0-9_]{0,16}"));
        accountField.setValue(opponent ? botSkinAccount : playerSkinAccount);
        accountField.setFocused(true);
        accountLoading = false;
        accountError = "";
        skinDialog = SkinDialog.ACCOUNT;
    }

    private void applyAccountField() {
        if (accountField == null || accountField.getValue().isBlank()) {
            accountError = "Enter a Minecraft account name";
            return;
        }
        accountLoading = true;
        accountError = "";
        pollAccountSkin();
    }

    private void pollAccountSkin() {
        if (!accountLoading || accountField == null) return;
        String account = accountField.getValue().trim();
        String status = CharacterAnimations.accountSkinStatus(account);
        if ("loading".equals(status)) return;
        accountLoading = false;
        if (!"ready".equals(status)) {
            accountError = "Account or skin could not be loaded";
            return;
        }
        if (opponent) botSkinAccount = account;
        else playerSkinAccount = account;
        accountField = null;
        applySkinSource(ClientOptions.SKIN_SOURCE_ACCOUNT, null, false);
    }

    private static Path pathOrNull(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return Path.of(value).toAbsolutePath().normalize();
        } catch (RuntimeException ignored) {
            return null;
        }
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
