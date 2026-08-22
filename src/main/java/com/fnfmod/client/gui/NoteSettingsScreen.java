package com.fnfmod.client.gui;

import com.fnfmod.FnfMod;
import com.fnfmod.chart.SongChart;
import com.fnfmod.client.ClientOptions;
import com.fnfmod.client.audio.SongPlayer;
import com.fnfmod.client.gameplay.PsychAssetResolver;
import com.fnfmod.client.math.Easing;
import com.fnfmod.client.render.NoteSkinConfig;
import com.fnfmod.client.render.NoteStyle;
import com.fnfmod.client.render.PsychNoteTextureCache;
import com.fnfmod.gameplay.PlaybackMode;
import com.fnfmod.gameplay.PlaybackPolicy;
import com.fnfmod.song.SongEntry;
import com.fnfmod.song.SongLibrary;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.util.Mth;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Gameplay-style note preview and editor for colors, assets, and skin.json transforms. */
public final class NoteSettingsScreen extends Screen {
    private enum TransformPart {
        NOTE("Note"), RECEPTOR("Receptor"), SUSTAIN("Sustain"), SPLASH("Splash"), HOLD_COVER("Hold Cover");
        final String label;
        TransformPart(String label) { this.label = label; }
    }

    private enum ColorPart {
        PRIMARY("Base", "noteColorBase"),
        SECONDARY("Highlight", "noteColorHighlight"),
        OUTLINE("Outline", "noteColorOutline");
        final String label;
        final String optionKey;
        ColorPart(String label, String optionKey) { this.label = label; this.optionKey = optionKey; }
    }

    private record PlacedWidget(AbstractWidget widget, int baseY) {}
    private record SideLabel(String text, int baseY) {}
    private record PreviewNoteHit(int lane, float x, float y, float size) {}
    private record PreviewCoverEnd(int lane, int visualLane, double startedAtMs) {}
    private record OptionsSnapshot(String noteSkin, String splashSkin, String holdSplashSkin,
                                   double offsetMs, int[] primary, int[] secondary, int[] outline) {}

    private final Screen parent;
    private final List<PlacedWidget> scrollingWidgets = new ArrayList<>();
    private final List<SideLabel> sideLabels = new ArrayList<>();
    private List<SongEntry> installedSongs;
    private SongEntry previewEntry;
    private String previewDifficulty = "normal";
    private SongChart previewChart;
    private final SongChart demoChart = createDemoChart();
    private SongPlayer previewAudio;
    private PsychNoteTextureCache previewTextures;
    private PsychAssetResolver previewResolver;
    private long previewClockNano = System.nanoTime();
    private long previewAudioFinishedNano;
    private final SongChart.Note[] previewAnimatedNote = new SongChart.Note[8];
    private final boolean[] previewAnimationLoops = new boolean[8];
    private final long[] previewAnimationStartedNanos = new long[8];
    private final List<PreviewCoverEnd> previewCoverEnds = new ArrayList<>();
    private double previousPreviewPosition = Double.NaN;
    private final List<AbstractWidget> transportWidgets = new ArrayList<>();
    private Button previewPlayButton;
    private boolean draggingPreviewTimeline;
    private double previewTimelineLastMouseX;
    private double previewTimelineDragPosition;
    private boolean demoPaused;
    private double demoPausedPosition;

    private int selectedLane;
    private ColorPart colorPart = ColorPart.PRIMARY;
    private final Button[] colorPartButtons = new Button[3];
    private final List<PreviewNoteHit> previewNoteHits = new ArrayList<>();
    private final SongChart.Note[] frameAnimationNotes = new SongChart.Note[8];
    private final boolean[] frameAnimationLoops = new boolean[8];
    private double cachedPreviewMaxSustainMs = maxSustainMs(demoChart);
    private double cachedPreviewEndMs = previewEndMs(demoChart);
    private boolean pixelPreview;
    private TransformPart transformPart = TransformPart.NOTE;
    private NoteSkinConfig workingConfig = NoteSkinConfig.DEFAULT;
    private Path configTarget;
    private boolean configDirty;
    private boolean updatingFields;
    private EditBox hexField;
    private EditBox scaleField;
    private EditBox alphaField;
    private EditBox xField;
    private EditBox yField;
    private int colorPickerBaseY;
    private float hue;
    private float saturation;
    private float brightness;
    private static final int PICKER_SIZE = 72;
    private static final int HUE_WIDTH = 12;
    /** Leaves a clear gap between the transport buttons and timeline time readout. */
    private static final int TRANSPORT_RAISE = 12;
    /** Horizontal gap between the opponent and player receptor groups. */
    private static final float STRUM_GROUP_GAP = 30f;
    private NativeImage colorSquarePixels;
    private DynamicTexture colorSquareTexture;
    private ResourceLocation colorSquareTextureId;
    private DynamicTexture hueTexture;
    private ResourceLocation hueTextureId;
    private float colorSquareHue = Float.NaN;
    /** 0 = none, 1 = saturation/brightness square, 2 = hue strip. */
    private int colorDragTarget;
    private String status = "Choose a chart to preview its notes and song.";

    private double controlScroll;
    private double controlScrollTarget;
    private double controlScrollFrom;
    private long controlScrollStart;
    private boolean controlScrollTween;
    private int contentBottom;
    private static final long SCROLL_NANOS = 200_000_000L;

    private boolean previousPixel;
    private boolean previousSongRgb;
    private boolean capturedStyle;
    private OptionsSnapshot optionsSnapshot;

    public NoteSettingsScreen(Screen parent) {
        super(Component.literal("Note Settings"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        if (!capturedStyle) {
            capturedStyle = true;
            previousPixel = NoteStyle.pixelUi();
            previousSongRgb = NoteStyle.songRgbAllowed();
            optionsSnapshot = captureOptions();
        }
        if (installedSongs == null) {
            SongLibrary.rescan();
            installedSongs = SongLibrary.getSongs().values().stream()
                    .filter(song -> song != null && song.fullModLayout && !song.difficulties.isEmpty())
                    .sorted(Comparator.comparing(this::songLabel, String.CASE_INSENSITIVE_ORDER))
                    .toList();
            reloadPreviewAssets(true);
        }
        buildControls();
    }

    private int panelWidth() { return Math.min(276, Math.max(220, width / 2)); }
    private int panelX() { return width - panelWidth(); }
    private int viewportTop() { return 38; }
    private int viewportBottom() { return height - 34; }
    private int controlX() { return panelX() + 10; }
    private int controlWidth() { return panelWidth() - 20; }
    private int shownY(int baseY) { return baseY - (int) Math.round(controlScroll); }

    private void buildControls() {
        clearWidgets();
        scrollingWidgets.clear();
        sideLabels.clear();
        transportWidgets.clear();
        int x = controlX();
        int w = controlWidth();
        int y = 42;

        addScroll(Button.builder(Component.literal(noteSkinLabel()), button -> {
            ClientOptions.get().noteSkin = cycle(NoteStyle.listSkins(), ClientOptions.get().noteSkin, false);
            reloadPreviewAssets(true, true);
            buildControls();
        }).bounds(x, shownY(y), w, 20).build(), y);
        y += 24;

        Button splash = addScroll(Button.builder(Component.literal(splashLabel()), button -> {
            ClientOptions.get().splashSkin = cycle(NoteStyle.listSplashes(), ClientOptions.get().splashSkin, true);
            reloadPreviewAssets(false, true);
            buildControls();
        }).bounds(x, shownY(y), w, 20).build(), y);
        splash.active = !NoteStyle.skinHasOwnSplash() && !ClientOptions.isLocked("splashSkin");
        y += 24;

        Button hold = addScroll(Button.builder(Component.literal(holdLabel()), button -> {
            ClientOptions.get().holdSplashSkin = cycle(NoteStyle.listHoldSplashes(),
                    ClientOptions.get().holdSplashSkin, false);
            reloadPreviewAssets(false, true);
            buildControls();
        }).bounds(x, shownY(y), w, 20).build(), y);
        hold.active = !NoteStyle.skinHasOwnHoldCover() && !ClientOptions.isLocked("holdSplashSkin");
        y += 28;

        addScroll(Button.builder(Component.literal("Preview Chart: "
                        + (previewEntry == null ? "None" : songLabel(previewEntry) + " [" + previewDifficulty + "]")), button ->
                minecraft.setScreen(new NotePreviewChartPickerScreen(this, installedSongs)))
                .bounds(x, shownY(y), w, 20).build(), y);
        y += 24;

        int delayButtonWidth = 22;
        int delaySliderX = x + delayButtonWidth + 4;
        int delaySliderWidth = w - delayButtonWidth * 2 - 8;
        Button delayMinus = addScroll(Button.builder(Component.literal("-"), button -> {
            nudgeDelay(hasShiftDown() ? -8 : -1);
            buildControls();
        }).bounds(x, shownY(y), delayButtonWidth, 20).build(), y);
        var delay = addScroll(new net.minecraft.client.gui.components.AbstractSliderButton(
                delaySliderX, shownY(y), delaySliderWidth, 20, delayLabel(), delaySliderValue()) {
            @Override protected void updateMessage() {
                setMessage(delayLabel());
            }

            @Override protected void applyValue() {
                ClientOptions.get().offsetMs = Math.round(value * 2000.0 - 1000.0);
                previousPreviewPosition = Double.NaN;
                updateMessage();
            }
        }, y);
        Button delayPlus = addScroll(Button.builder(Component.literal("+"), button -> {
            nudgeDelay(hasShiftDown() ? 8 : 1);
            buildControls();
        }).bounds(x + w - delayButtonWidth, shownY(y), delayButtonWidth, 20).build(), y);
        delayMinus.active = delayPlus.active = !ClientOptions.isLocked("offsetMs");
        delay.active = !ClientOptions.isLocked("offsetMs");
        y += 24;

        Button skinRgb = addScroll(Button.builder(skinRgbLabel(), button -> {
            workingConfig = new NoteSkinConfig(!workingConfig.rgb(), workingConfig.note(),
                    workingConfig.receptor(), workingConfig.sustain(), workingConfig.splash(),
                    workingConfig.holdCover());
            NoteStyle.previewSkinConfig(workingConfig);
            configDirty = true;
            button.setMessage(skinRgbLabel());
        }).bounds(x, shownY(y), w, 20).build(), y);
        skinRgb.active = configTarget != null;
        y += 24;

        addScroll(Button.builder(Component.literal("Preview UI: " + (pixelPreview ? "Pixel" : "Normal")), button -> {
            pixelPreview = !pixelPreview;
            reloadPreviewAssets(false);
            buildControls();
        }).bounds(x, shownY(y), w, 20).build(), y);
        y += 24;

        int colorW = (w - 8) / 3;
        for (int i = 0; i < ColorPart.values().length; i++) {
            ColorPart part = ColorPart.values()[i];
            int index = i;
            colorPartButtons[i] = addScroll(Button.builder(Component.literal(part.label), button -> {
                colorPart = part;
                syncHexField();
            }).bounds(x + i * (colorW + 4), shownY(y), i == 2 ? w - (colorW + 4) * 2 : colorW, 20).build(), y);
        }
        y += 24;

        sideLabels.add(new SideLabel("Hex", y));
        hexField = new EditBox(font, x + 34, shownY(y) + 2, 72, 16, Component.literal("hex"));
        hexField.setMaxLength(6);
        hexField.setResponder(this::applyHex);
        hexField.setEditable(!ClientOptions.isLocked(colorPart.optionKey));
        addScroll(hexField, y);
        syncHexField();
        Button resetColor = addScroll(Button.builder(Component.literal("Reset"), button -> resetSelectedColor())
                .bounds(x + 112, shownY(y), w - 112, 20).build(), y);
        resetColor.active = !ClientOptions.isLocked(colorPart.optionKey);
        y += 25;
        colorPickerBaseY = y;
        y += 82;

        addScroll(Button.builder(Component.literal("Edit Part: " + transformPart.label), button -> {
            transformPart = TransformPart.values()[Math.floorMod(transformPart.ordinal()
                    + (hasShiftDown() ? -1 : 1), TransformPart.values().length)];
            buildControls();
        }).bounds(x, shownY(y), w, 20).build(), y);
        y += 25;

        scaleField = numericField("Scale", x, w, y); y += 23;
        alphaField = numericField("Alpha", x, w, y); y += 23;
        xField = numericField("X", x, w, y); y += 23;
        yField = numericField("Y", x, w, y); y += 27;
        syncTransformFields();

        contentBottom = y;

        int footerX = panelX() + 10;
        int footerW = (panelWidth() - 24) / 2;
        addRenderableWidget(Button.builder(Component.literal("Save"), button -> saveAndReturn())
                .bounds(footerX, height - 28, footerW, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Cancel"), button -> cancelAndReturn())
                .bounds(footerX + footerW + 4, height - 28, footerW, 20).build());
        buildTransportControls();
        clampScroll();
        placeScrollingWidgets();
    }

    private void buildTransportControls() {
        int center = panelX() / 2;
        int buttonY = height - 55 - TRANSPORT_RAISE;
        int buttonW = 34;
        int gap = 4;
        int left = center - (buttonW * 4 + gap * 3) / 2;
        addTransport(Button.builder(Component.literal("|<"), button -> previewSeek(0))
                .bounds(left, buttonY, buttonW, 20).build());
        addTransport(Button.builder(Component.literal("<<"), button -> previewSeek(previewPosition() - 5000))
                .bounds(left + buttonW + gap, buttonY, buttonW, 20).build());
        previewPlayButton = addTransport(Button.builder(Component.literal("||"), button -> togglePlayback())
                .bounds(left + (buttonW + gap) * 2, buttonY, buttonW, 20).build());
        addTransport(Button.builder(Component.literal(">>"), button -> previewSeek(previewPosition() + 5000))
                .bounds(left + (buttonW + gap) * 3, buttonY, buttonW, 20).build());
    }

    private <T extends AbstractWidget> T addTransport(T widget) {
        addRenderableWidget(widget);
        transportWidgets.add(widget);
        return widget;
    }

    private static double delaySliderValue() {
        return Mth.clamp((ClientOptions.get().offsetMs + 1000.0) / 2000.0, 0.0, 1.0);
    }

    private static Component delayLabel() {
        return Component.literal("Delay: " + Math.round(ClientOptions.get().offsetMs) + " ms");
    }

    private void nudgeDelay(int milliseconds) {
        ClientOptions.get().offsetMs = Mth.clamp(
                ClientOptions.get().offsetMs + milliseconds, -1000.0, 1000.0);
        previousPreviewPosition = Double.NaN;
    }

    private EditBox numericField(String label, int x, int width, int baseY) {
        sideLabels.add(new SideLabel(label, baseY));
        EditBox box = new EditBox(font, x + 58, shownY(baseY) + 2, width - 58, 16,
                Component.literal(label));
        box.setMaxLength(24);
        box.setResponder(value -> applyTransformFields());
        box.setEditable(configTarget != null);
        return addScroll(box, baseY);
    }

    private <T extends AbstractWidget> T addScroll(T widget, int baseY) {
        addRenderableWidget(widget);
        scrollingWidgets.add(new PlacedWidget(widget, baseY));
        return widget;
    }

    private String cycle(List<String> options, String current, boolean includeOff) {
        List<String> ring = new ArrayList<>();
        if (includeOff) ring.add("");
        if (options != null) ring.addAll(options);
        if (ring.isEmpty()) return current == null ? "" : current;
        int index = ring.indexOf(current == null ? "" : current);
        if (index < 0) index = 0;
        return ring.get(Math.floorMod(index + (hasShiftDown() ? -1 : 1), ring.size()));
    }

    private String noteSkinLabel() {
        String value = ClientOptions.get().noteSkin;
        return "Note Skin: " + (value == null || value.isBlank() ? "Default" : value);
    }

    private String splashLabel() {
        if (NoteStyle.skinHasOwnSplash()) return "Splashes: From note skin";
        String value = ClientOptions.get().splashSkin;
        return "Splashes: " + (value == null || value.isBlank() ? "OFF" : value);
    }

    private String holdLabel() {
        if (NoteStyle.skinHasOwnHoldCover()) return "Hold Cover: From note skin";
        String value = ClientOptions.get().holdSplashSkin;
        if (value == null || value.isBlank() || value.equalsIgnoreCase(ClientOptions.NOTE_SKIN_DEFAULT)) {
            value = "Default (chart)";
        } else if (value.equalsIgnoreCase(ClientOptions.NOTE_SKIN_NONE)) value = "OFF";
        return "Hold Cover: " + value;
    }

    private Component skinRgbLabel() {
        return Component.literal("Skin Uses RGB: " + (workingConfig.rgb() ? "ON" : "OFF"));
    }

    private String songLabel(SongEntry song) {
        if (song == null) return "None";
        String name = song.displayName == null || song.displayName.isBlank() ? song.id : song.displayName;
        String pack = song.modRoot == null || song.modRoot.getFileName() == null ? ""
                : song.modRoot.getFileName().toString();
        return pack.isBlank() ? name : pack + " / " + name;
    }

    List<SongEntry> installedSongs() { return installedSongs == null ? List.of() : installedSongs; }

    String previewDifficultyFor(SongEntry song) {
        if (song != null && song == previewEntry && song.difficulties.contains(previewDifficulty)) {
            return previewDifficulty;
        }
        return song != null && song.difficulties.contains("normal")
                ? "normal" : song == null || song.difficulties.isEmpty() ? "normal" : song.difficulties.get(0);
    }

    void selectPreviewSong(SongEntry song, String difficulty) {
        if (song != null && parent instanceof FnfSettingsScreen settings) {
            settings.stopParentMachineAudio();
        }
        previewEntry = song;
        previewDifficulty = song == null || difficulty == null || difficulty.isBlank()
                ? "normal" : difficulty;
        loadPreviewChart();
    }

    private void loadPreviewChart() {
        disposePreviewAudio();
        previewChart = null;
        previewClockNano = System.nanoTime();
        demoPaused = false;
        demoPausedPosition = 0;
        clearPreviewAnimations();
        previousPreviewPosition = Double.NaN;
        if (previewEntry != null) {
            try {
                previewChart = SongLibrary.loadChart(previewEntry, previewDifficulty);
                Path inst = previewEntry.instFor(previewDifficulty);
                if (inst != null) {
                    previewAudio = new SongPlayer();
                    previewAudio.load(inst, previewEntry.voicesFor(previewDifficulty),
                            previewEntry.voicesPlayerFor(previewDifficulty),
                            previewEntry.voicesOpponentFor(previewDifficulty));
                    previewAudio.start();
                }
                status = "Previewing " + songLabel(previewEntry) + " [" + previewDifficulty + "]";
            } catch (Exception error) {
                status = "Could not load preview: " + error.getMessage();
                disposePreviewAudio();
            }
        }
        pixelPreview = previewChart != null && previewChart.pixelUi;
        if (previewEntry != null && previewChart != null) {
            PlaybackPolicy policy = PlaybackPolicy.resolve(PlaybackMode.LEGACY, previewEntry);
            PsychAssetResolver resolver = new PsychAssetResolver(
                    previewEntry.folder, previewEntry, policy, previewChart.stage);
            pixelPreview |= resolver.stageUsesPixelUi(previewChart.stage);
        }
        SongChart shown = displayedChart();
        cachedPreviewMaxSustainMs = maxSustainMs(shown);
        cachedPreviewEndMs = previewEndMs(shown);
        reloadPreviewAssets(ClientOptions.NOTE_SKIN_DEFAULT.equalsIgnoreCase(ClientOptions.get().noteSkin));
    }

    private void reloadPreviewAssets(boolean resetConfig) {
        reloadPreviewAssets(resetConfig, false);
    }

    private void reloadPreviewAssets(boolean resetConfig, boolean forceStyleReload) {
        if (previewTextures != null) previewTextures.close();
        previewTextures = null;
        previewResolver = null;
        if (previewEntry != null && previewChart != null) {
            PlaybackPolicy policy = PlaybackPolicy.resolve(PlaybackMode.LEGACY, previewEntry);
            previewResolver = new PsychAssetResolver(previewEntry.folder, previewEntry, policy, previewChart.stage);
            previewTextures = new PsychNoteTextureCache(previewResolver.customNoteRoots(),
                    policy.allows(previewEntry, SongLibrary.ExternalContent.IMAGES));
        }
        NoteStyle.setSongRgbAllowed(previewChart == null || !previewChart.disableNoteRgb);
        String noteTexture = previewChart == null || previewChart.noteTexture == null
                ? "" : previewChart.noteTexture.trim();
        Path[] note = previewTextures == null || noteTexture.isBlank()
                ? null : previewTextures.resolveSkinFiles(noteTexture);
        String holdTexture = previewChart == null || previewChart.holdSplashTexture == null
                ? "" : previewChart.holdSplashTexture.trim();
        Path[] hold = previewTextures == null ? null
                : previewTextures.resolveHoldSplashFiles(holdTexture, pixelPreview);
        Path holdPng = hold == null ? null : hold[0];
        Path holdXml = hold == null ? null : hold[1];
        if (note == null) {
            NoteStyle.useSongSkin(null, null, null, pixelPreview, holdPng, holdXml,
                    !holdTexture.isBlank());
        } else {
            NoteStyle.useSongSkin(note[0], note[1], note[2], pixelPreview, holdPng, holdXml,
                    !holdTexture.isBlank());
        }
        // Global note/splash/hold choices are not part of useSongSkin's path cache key.
        // Explicitly rebuild when one of those settings changes so this screen updates now.
        if (forceStyleReload) NoteStyle.reload();
        if (resetConfig || workingConfig == null) workingConfig = NoteStyle.currentSkinConfig();
        else NoteStyle.previewSkinConfig(workingConfig);
        configTarget = NoteStyle.currentSkinConfigFile();
    }

    private void togglePlayback() {
        if (previewAudio != null) {
            if (!previewAudio.isStarted()) previewAudio.start();
            else if (previewAudio.isPaused()) previewAudio.resume();
            else previewAudio.pause();
        } else if (demoPaused) {
            previewClockNano = System.nanoTime() - (long) (demoPausedPosition * 1_000_000.0);
            demoPaused = false;
        } else {
            demoPausedPosition = previewPosition();
            demoPaused = true;
        }
        updatePlaybackButton();
    }

    private void updatePlaybackButton() {
        if (previewPlayButton == null) return;
        boolean playing = previewAudio != null
                ? previewAudio.isStarted() && !previewAudio.isPaused() && !previewAudio.isFinished()
                : !demoPaused;
        previewPlayButton.setMessage(Component.literal(playing ? "||" : ">"));
    }

    private int timelineLeft() { return 24; }
    private int timelineRight() { return Math.max(timelineLeft() + 20, panelX() - 24); }
    private int timelineY() { return height - 25; }

    private boolean overPreviewTimeline(double mouseX, double mouseY) {
        return mouseX >= timelineLeft() && mouseX <= timelineRight()
                && mouseY >= timelineY() - 4 && mouseY <= timelineY() + 7;
    }

    private void seekPreviewFromMouse(double mouseX) {
        double fraction = Mth.clamp((mouseX - timelineLeft())
                / Math.max(1.0, timelineRight() - timelineLeft()), 0.0, 1.0);
        previewSeek(fraction * previewDuration());
    }

    private void previewSeek(double chartMs) {
        double target = Mth.clamp(chartMs, 0.0, previewDuration());
        if (previewAudio != null) {
            boolean paused = previewAudio.isPaused();
            if (previewAudio.isFinished()) {
                previewAudio.reset();
                previewAudio.start();
            }
            double offset = (previewChart == null ? 0 : previewChart.offsetMs)
                    + ClientOptions.get().offsetMs;
            previewAudio.seekMs(Math.max(0, target - offset));
            previewAudioFinishedNano = 0;
            if (paused && !previewAudio.isPaused()) previewAudio.pause();
        } else {
            demoPausedPosition = target;
            previewClockNano = System.nanoTime() - (long) (target * 1_000_000.0);
        }
        clearPreviewAnimations();
    }

    private void syncHexField() {
        if (hexField == null) return;
        updatingFields = true;
        int color = selectedColor();
        float[] hsb = java.awt.Color.RGBtoHSB((color >> 16) & 0xFF, (color >> 8) & 0xFF,
                color & 0xFF, null);
        hue = hsb[0];
        saturation = hsb[1];
        brightness = hsb[2];
        hexField.setValue(String.format(Locale.ROOT, "%06X", color & 0xFFFFFF));
        updatingFields = false;
    }

    private void applyHex(String raw) {
        if (updatingFields || raw == null || !raw.matches("(?i)[0-9a-f]{6}")) return;
        int color;
        try { color = Integer.parseInt(raw, 16); }
        catch (Exception ignored) { return; }
        setSelectedColor(color);
        NoteStyle.rebuildLaneColors(selectedLane);
    }

    private void resetSelectedColor() {
        int[] defaults = switch (colorPart) {
            case PRIMARY -> ClientOptions.defaultBase();
            case SECONDARY -> ClientOptions.defaultHighlight();
            case OUTLINE -> ClientOptions.defaultOutline();
        };
        setSelectedColor(defaults[selectedLane]);
        NoteStyle.rebuildLaneColors(selectedLane);
        syncHexField();
    }

    private int selectedColor() {
        ClientOptions options = ClientOptions.get();
        return switch (colorPart) {
            case PRIMARY -> options.noteColorBase[selectedLane];
            case SECONDARY -> options.noteColorHighlight[selectedLane];
            case OUTLINE -> options.noteColorOutline[selectedLane];
        } & 0xFFFFFF;
    }

    private void setSelectedColor(int color) {
        int rgb = color & 0xFFFFFF;
        ClientOptions options = ClientOptions.get();
        switch (colorPart) {
            case PRIMARY -> options.noteColorBase[selectedLane] = rgb;
            case SECONDARY -> options.noteColorHighlight[selectedLane] = rgb;
            case OUTLINE -> options.noteColorOutline[selectedLane] = rgb;
        }
    }

    private boolean beginColorPick(double mouseX, double mouseY) {
        if (ClientOptions.isLocked(colorPart.optionKey)) return false;
        if (mouseY < viewportTop() || mouseY >= viewportBottom()) return false;
        int sx = controlX(), sy = shownY(colorPickerBaseY);
        if (mouseX >= sx && mouseX < sx + 72 && mouseY >= sy && mouseY < sy + 72) {
            colorDragTarget = 1;
            return updateColorPick(mouseX, mouseY);
        }
        if (mouseX >= sx + 80 && mouseX < sx + 92 && mouseY >= sy && mouseY < sy + 72) {
            colorDragTarget = 2;
            return updateColorPick(mouseX, mouseY);
        }
        return false;
    }

    private boolean updateColorPick(double mouseX, double mouseY) {
        int sx = controlX(), sy = shownY(colorPickerBaseY);
        if (colorDragTarget == 1) {
            saturation = Mth.clamp((float) ((mouseX - sx) / 71.0), 0f, 1f);
            brightness = 1f - Mth.clamp((float) ((mouseY - sy) / 71.0), 0f, 1f);
        } else if (colorDragTarget == 2) {
            hue = Mth.clamp((float) ((mouseY - sy) / 71.0), 0f, 1f);
        } else return false;
        int color = java.awt.Color.HSBtoRGB(hue, saturation, brightness) & 0xFFFFFF;
        setSelectedColor(color);
        NoteStyle.rebuildLaneColors(selectedLane);
        updatingFields = true;
        if (hexField != null) hexField.setValue(String.format(Locale.ROOT, "%06X", color));
        updatingFields = false;
        return true;
    }

    private NoteSkinConfig.Part currentPart() {
        return switch (transformPart) {
            case NOTE -> workingConfig.note();
            case RECEPTOR -> workingConfig.receptor();
            case SUSTAIN -> workingConfig.sustain();
            case SPLASH -> workingConfig.splash();
            case HOLD_COVER -> workingConfig.holdCover();
        };
    }

    private void syncTransformFields() {
        if (scaleField == null) return;
        NoteSkinConfig.Part part = currentPart();
        updatingFields = true;
        scaleField.setValue(format(part.scale()));
        alphaField.setValue(format(part.alpha()));
        xField.setValue(format(part.x()));
        yField.setValue(format(part.y()));
        updatingFields = false;
    }

    private static String format(float value) {
        if (Math.abs(value - Math.round(value)) < 0.0001f) return Integer.toString(Math.round(value));
        return String.format(Locale.ROOT, "%.4f", value).replaceAll("0+$", "").replaceAll("\\.$", "");
    }

    private void applyTransformFields() {
        if (updatingFields || scaleField == null || workingConfig == null) return;
        NoteSkinConfig.Part old = currentPart();
        NoteSkinConfig.Part changed = new NoteSkinConfig.Part(
                parse(scaleField.getValue(), old.scale(), 0.05f, 20f),
                parse(alphaField.getValue(), old.alpha(), 0f, 1f),
                parse(xField.getValue(), old.x(), -4096f, 4096f),
                parse(yField.getValue(), old.y(), -4096f, 4096f));
        workingConfig = switch (transformPart) {
            case NOTE -> new NoteSkinConfig(workingConfig.rgb(), changed, workingConfig.receptor(), workingConfig.sustain(),
                    workingConfig.splash(), workingConfig.holdCover());
            case RECEPTOR -> new NoteSkinConfig(workingConfig.rgb(), workingConfig.note(), changed, workingConfig.sustain(),
                    workingConfig.splash(), workingConfig.holdCover());
            case SUSTAIN -> new NoteSkinConfig(workingConfig.rgb(), workingConfig.note(), workingConfig.receptor(), changed,
                    workingConfig.splash(), workingConfig.holdCover());
            case SPLASH -> new NoteSkinConfig(workingConfig.rgb(), workingConfig.note(), workingConfig.receptor(),
                    workingConfig.sustain(), changed, workingConfig.holdCover());
            case HOLD_COVER -> new NoteSkinConfig(workingConfig.rgb(), workingConfig.note(), workingConfig.receptor(),
                    workingConfig.sustain(), workingConfig.splash(), changed);
        };
        NoteStyle.previewSkinConfig(workingConfig);
        configDirty = true;
    }

    private static float parse(String text, float fallback, float min, float max) {
        try {
            float value = Float.parseFloat(text);
            return Float.isFinite(value) ? Mth.clamp(value, min, max) : fallback;
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private void saveAndReturn() {
        applyTransformFields();
        if (configDirty && configTarget != null) {
            try {
                NoteSkinConfig.saveFile(configTarget, workingConfig);
                configDirty = false;
            } catch (Exception error) {
                status = "Could not save skin config: " + error.getMessage();
                return;
            }
        }
        ClientOptions.save();
        finishAndReturn();
    }

    private static OptionsSnapshot captureOptions() {
        ClientOptions options = ClientOptions.get();
        return new OptionsSnapshot(options.noteSkin, options.splashSkin, options.holdSplashSkin,
                options.offsetMs, options.noteColorBase.clone(), options.noteColorHighlight.clone(),
                options.noteColorOutline.clone());
    }

    private void restoreOptions() {
        if (optionsSnapshot == null) return;
        ClientOptions options = ClientOptions.get();
        options.noteSkin = optionsSnapshot.noteSkin();
        options.splashSkin = optionsSnapshot.splashSkin();
        options.holdSplashSkin = optionsSnapshot.holdSplashSkin();
        options.offsetMs = optionsSnapshot.offsetMs();
        options.noteColorBase = optionsSnapshot.primary().clone();
        options.noteColorHighlight = optionsSnapshot.secondary().clone();
        options.noteColorOutline = optionsSnapshot.outline().clone();
        ClientOptions.save();
        for (int lane = 0; lane < 4; lane++) NoteStyle.rebuildLaneColors(lane);
    }

    private void updateScroll() {
        if (!controlScrollTween) return;
        double progress = (System.nanoTime() - controlScrollStart) / (double) SCROLL_NANOS;
        if (progress >= 1) {
            controlScroll = controlScrollTarget;
            controlScrollTween = false;
        } else {
            double eased = Easing.apply("expoOut", progress);
            controlScroll = controlScrollFrom + (controlScrollTarget - controlScrollFrom) * eased;
        }
        placeScrollingWidgets();
    }

    private double maxScroll() { return Math.max(0, contentBottom - viewportBottom() + 4); }
    private void clampScroll() {
        controlScroll = Mth.clamp(controlScroll, 0, maxScroll());
        controlScrollTarget = Mth.clamp(controlScrollTarget, 0, maxScroll());
    }

    private void scrollTo(double target) {
        updateScroll();
        controlScrollFrom = controlScroll;
        controlScrollTarget = Mth.clamp(target, 0, maxScroll());
        controlScrollStart = System.nanoTime();
        controlScrollTween = Math.abs(controlScrollTarget - controlScrollFrom) > 0.01;
        if (!controlScrollTween) controlScroll = controlScrollTarget;
        placeScrollingWidgets();
    }

    private void placeScrollingWidgets() {
        int top = viewportTop(), bottom = viewportBottom();
        for (PlacedWidget placed : scrollingWidgets) {
            int y = shownY(placed.baseY());
            placed.widget().setY(y);
            placed.widget().visible = y + placed.widget().getHeight() > top && y < bottom;
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (mouseX >= panelX()) {
            scrollTo(controlScrollTarget - scrollY * 30);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && selectPreviewNote(mouseX, mouseY)) return true;
        if (button == 0 && overPreviewTimeline(mouseX, mouseY)) {
            draggingPreviewTimeline = true;
            seekPreviewFromMouse(mouseX);
            previewTimelineLastMouseX = mouseX;
            previewTimelineDragPosition = Mth.clamp((mouseX - timelineLeft())
                    / Math.max(1.0, timelineRight() - timelineLeft()), 0.0, 1.0)
                    * previewDuration();
            return true;
        }
        if (super.mouseClicked(mouseX, mouseY, button)) return true;
        return button == 0 && mouseX >= panelX() && beginColorPick(mouseX, mouseY);
    }

    private boolean selectPreviewNote(double mouseX, double mouseY) {
        if (mouseX >= panelX()) return false;
        for (int i = previewNoteHits.size() - 1; i >= 0; i--) {
            PreviewNoteHit hit = previewNoteHits.get(i);
            float radius = hit.size() * 0.62f;
            if (Math.abs(mouseX - hit.x()) <= radius && Math.abs(mouseY - hit.y()) <= radius) {
                selectedLane = Math.floorMod(hit.lane(), 4);
                syncHexField();
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (button == 0 && draggingPreviewTimeline) {
            double pixels = Math.max(1.0, timelineRight() - timelineLeft());
            double rate = hasShiftDown() ? 0.2 : 1.0;
            previewTimelineDragPosition = Mth.clamp(previewTimelineDragPosition
                    + (mouseX - previewTimelineLastMouseX) / pixels * previewDuration() * rate,
                    0.0, previewDuration());
            previewTimelineLastMouseX = mouseX;
            previewSeek(previewTimelineDragPosition);
            return true;
        }
        if (button == 0 && colorDragTarget != 0) return updateColorPick(mouseX, mouseY);
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        draggingPreviewTimeline = false;
        colorDragTarget = 0;
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public void render(GuiGraphics gui, int mouseX, int mouseY, float partialTick) {
        updateScroll();
        if (previewAudio != null) {
            previewAudio.applyVolumes();
            if (previewAudio.isFinished()) {
                // Charts can legitimately contain a sustain whose tail extends beyond
                // the instrumental. Keep advancing the visual preview until every tail
                // has completed instead of restarting as soon as OpenAL stops the audio.
                if (previewAudioFinishedNano == 0) previewAudioFinishedNano = System.nanoTime();
                if (previewPosition() >= cachedPreviewEndMs + 600) {
                    previewAudio.reset();
                    previewAudio.start();
                    previewAudioFinishedNano = 0;
                    clearPreviewAnimations();
                }
            } else {
                previewAudioFinishedNano = 0;
            }
        }
        // The scrolling controls render manually inside one scissor so partially visible
        // fields cannot overlap the title or fixed Back button.
        for (PlacedWidget placed : scrollingWidgets) placed.widget().visible = false;
        for (AbstractWidget widget : transportWidgets) widget.visible = false;
        int split = panelX();
        BlockifiedScreenStyle.backdrop(gui, width, height);
        BlockifiedScreenStyle.panel(gui, 4, 4, width - 8, height - 8);
        BlockifiedScreenStyle.inner(gui, 8, 34, split - 16, height - 42);
        BlockifiedScreenStyle.inner(gui, split, 34, panelWidth() - 8, height - 68);
        gui.fill(split, 34, split + 3, height - 34, BlockifiedScreenStyle.ACCENT);
        super.render(gui, mouseX, mouseY, partialTick);
        gui.drawString(font, "BLOCKIFIED SETTINGS", 14, 13,
                BlockifiedScreenStyle.ACCENT, false);
        gui.drawCenteredString(font, "Note Settings", width / 2, 14,
                BlockifiedScreenStyle.TEXT);
        gui.drawCenteredString(font, "GAMEPLAY PREVIEW", split / 2, 24,
                BlockifiedScreenStyle.TEXT_SECTION);
        gui.drawCenteredString(font, status, split / 2, height - 68 - TRANSPORT_RAISE,
                status.startsWith("Could not") ? 0xFFFF7777 : 0xFFAAAAAA);

        gui.enableScissor(8, 34, split - 8, height - 8);
        renderGameplayPreview(gui, split);
        gui.disableScissor();

        renderTransport(gui, mouseX, mouseY, partialTick);

        gui.enableScissor(panelX(), viewportTop(), width, viewportBottom());
        for (PlacedWidget placed : scrollingWidgets) {
            int y = placed.widget().getY();
            boolean visible = y + placed.widget().getHeight() > viewportTop() && y < viewportBottom();
            placed.widget().visible = visible;
            if (visible) placed.widget().render(gui, mouseX, mouseY, partialTick);
        }
        for (SideLabel label : sideLabels) {
            int y = shownY(label.baseY()) + 6;
            if (y >= viewportTop() && y < viewportBottom()) {
                gui.drawString(font, label.text(), controlX(), y, 0xFFBBBBBB);
            }
        }
        Button selectedColorButton = colorPartButtons[colorPart.ordinal()];
        if (selectedColorButton != null && selectedColorButton.visible) {
            gui.renderOutline(selectedColorButton.getX() - 1, selectedColorButton.getY() - 1,
                    selectedColorButton.getWidth() + 2, selectedColorButton.getHeight() + 2, 0xFFFFFFFF);
        }
        renderColorPicker(gui);
        int swatchY = hexField == null ? -100 : hexField.getY() + 2;
        if (swatchY >= viewportTop() && swatchY < viewportBottom()) {
            gui.fill(controlX() + 108, swatchY, controlX() + 111, swatchY + 16,
                    0xFF000000 | selectedColor());
        }
        gui.disableScissor();

        if (configTarget == null) {
            gui.drawCenteredString(font, "Current note skin has no editable JSON target.",
                    panelX() + panelWidth() / 2, height - 40, 0xFFFF9977);
        } else if (configDirty) {
            gui.drawCenteredString(font, "Unsaved transform changes", panelX() + panelWidth() / 2,
                    height - 40, 0xFFFFCC66);
        }
    }

    private void renderTransport(GuiGraphics gui, int mouseX, int mouseY, float partialTick) {
        updatePlaybackButton();
        for (AbstractWidget widget : transportWidgets) {
            widget.visible = true;
            widget.render(gui, mouseX, mouseY, partialTick);
        }
        double duration = previewDuration();
        double position = Mth.clamp(previewPosition(), 0, duration);
        int left = timelineLeft(), right = timelineRight(), y = timelineY();
        gui.fill(left, y, right, y + 3, 0xFF303039);
        int filled = left + (int) Math.round((right - left) * position / Math.max(1, duration));
        gui.fill(left, y, filled, y + 3, BlockifiedScreenStyle.ACCENT);
        gui.fill(filled - 2, y - 3, filled + 3, y + 6, 0xFFFFFFFF);
        gui.drawCenteredString(font, formatTime(position) + " / " + formatTime(duration),
                (left + right) / 2, y - 12, 0xFFCCCCCC);
    }

    private static String formatTime(double milliseconds) {
        long seconds = Math.max(0, Math.round(milliseconds / 1000.0));
        return String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
    }

    private void renderColorPicker(GuiGraphics gui) {
        int sx = controlX(), sy = shownY(colorPickerBaseY);
        if (sy + 72 <= viewportTop() || sy >= viewportBottom()) return;
        ensureColorPickerTextures();
        if (colorSquareTextureId != null) {
            gui.blit(colorSquareTextureId, sx, sy, PICKER_SIZE, PICKER_SIZE,
                    0, 0, PICKER_SIZE, PICKER_SIZE, PICKER_SIZE, PICKER_SIZE);
        }
        if (hueTextureId != null) {
            gui.blit(hueTextureId, sx + 80, sy, HUE_WIDTH, PICKER_SIZE,
                    0, 0, HUE_WIDTH, PICKER_SIZE, HUE_WIDTH, PICKER_SIZE);
        }
        int cx = sx + Math.round(saturation * 71f);
        int cy = sy + Math.round((1f - brightness) * 71f);
        gui.fill(cx - 2, cy - 2, cx + 3, cy + 3, 0xFFFFFFFF);
        gui.fill(cx - 1, cy - 1, cx + 2, cy + 2,
                0xFF000000 | (java.awt.Color.HSBtoRGB(hue, saturation, brightness) & 0xFFFFFF));
        int hy = sy + Math.round(hue * 71f);
        gui.fill(sx + 78, hy - 1, sx + 94, hy + 2, 0xFFFFFFFF);
    }

    /**
     * The picker used to submit one GUI rectangle per pixel row/column every
     * frame. Cache those gradients as two tiny textures; only the square needs
     * a 72x72 upload when the selected hue actually changes.
     */
    private void ensureColorPickerTextures() {
        if (hueTextureId == null) {
            NativeImage pixels = new NativeImage(HUE_WIDTH, PICKER_SIZE, true);
            for (int y = 0; y < PICKER_SIZE; y++) {
                int color = hsbAbgr(y / (float) (PICKER_SIZE - 1), 1f, 1f);
                for (int x = 0; x < HUE_WIDTH; x++) pixels.setPixelRGBA(x, y, color);
            }
            hueTexture = new DynamicTexture(pixels);
            hueTextureId = FnfMod.id("ui/note_settings_hue_"
                    + Integer.toHexString(System.identityHashCode(this)));
            minecraft.getTextureManager().register(hueTextureId, hueTexture);
        }
        if (colorSquareTextureId == null) {
            colorSquarePixels = new NativeImage(PICKER_SIZE, PICKER_SIZE, true);
            colorSquareTexture = new DynamicTexture(colorSquarePixels);
            colorSquareTextureId = FnfMod.id("ui/note_settings_square_"
                    + Integer.toHexString(System.identityHashCode(this)));
            minecraft.getTextureManager().register(colorSquareTextureId, colorSquareTexture);
        }
        if (Float.compare(colorSquareHue, hue) == 0) return;
        for (int y = 0; y < PICKER_SIZE; y++) {
            float value = 1f - y / (float) (PICKER_SIZE - 1);
            for (int x = 0; x < PICKER_SIZE; x++) {
                float sat = x / (float) (PICKER_SIZE - 1);
                colorSquarePixels.setPixelRGBA(x, y, hsbAbgr(hue, sat, value));
            }
        }
        colorSquareTexture.upload();
        colorSquareHue = hue;
    }

    /** Converts java.awt ARGB to NativeImage's ABGR storage order. */
    private static int hsbAbgr(float hue, float saturation, float brightness) {
        int argb = java.awt.Color.HSBtoRGB(hue, saturation, brightness);
        return (argb & 0xFF00FF00) | ((argb >>> 16) & 0xFF) | ((argb & 0xFF) << 16);
    }

    private void disposeColorPickerTextures() {
        if (colorSquareTextureId != null) minecraft.getTextureManager().release(colorSquareTextureId);
        else if (colorSquareTexture != null) colorSquareTexture.close();
        if (hueTextureId != null) minecraft.getTextureManager().release(hueTextureId);
        else if (hueTexture != null) hueTexture.close();
        colorSquareTextureId = hueTextureId = null;
        colorSquareTexture = hueTexture = null;
        colorSquarePixels = null;
        colorSquareHue = Float.NaN;
    }

    private void renderGameplayPreview(GuiGraphics gui, int split) {
        int available = Math.max(180, split - 28);
        float noteSize = Math.min(46f, (available - STRUM_GROUP_GAP) / 9.5f);
        float spacing = noteSize * 1.08f;
        float totalWidth = spacing * 7 + noteSize + STRUM_GROUP_GAP;
        float startX = Math.max(18, (split - totalWidth) / 2f + noteSize / 2f);
        float receptorY = ClientOptions.get().downscroll ? height - 106f : 62f;
        previewNoteHits.clear();
        double songPosition = previewPosition();
        SongChart shownChart = displayedChart();
        ClientOptions options = ClientOptions.get();
        double requestedSpeed = options.constantScrollSpeed
                ? options.scrollSpeedMult
                : Math.max(0.01, shownChart.speed) * options.scrollSpeedMult;
        // This is an asset/settings preview in roughly half a gameplay viewport.
        // Divide loaded-song speed proportionally for this smaller viewport instead
        // of clamping it; relative differences between chart speeds remain visible.
        double previewSpeed = previewChart == null ? requestedSpeed : requestedSpeed / 2.0;
        double pxPerMs = 0.32 * Math.max(0.01, previewSpeed);

        java.util.Arrays.fill(frameAnimationNotes, null);
        java.util.Arrays.fill(frameAnimationLoops, false);
        int first = lowerBound(shownChart.notes, songPosition - cachedPreviewMaxSustainMs - 400);
        int visibleEnd = first;
        double visibleFuture = (height + 200) / Math.max(0.01, pxPerMs);
        for (; visibleEnd < shownChart.notes.size(); visibleEnd++) {
            SongChart.Note note = shownChart.notes.get(visibleEnd);
            double delta = note.timeMs - songPosition;
            if (delta > visibleFuture) break;
            boolean activeHold = previewBotHolds(note, songPosition);
            double sinceHit = songPosition - note.timeMs;
            int visualLane = (note.playerSide ? 4 : 0) + Math.floorMod(note.lane, 4);
            if (activeHold || (sinceHit >= 0 && sinceHit < 150)) {
                // A sustain owns the lane until its tail, even if a nearby tap overlaps it.
                if (frameAnimationNotes[visualLane] == null || activeHold) {
                    frameAnimationNotes[visualLane] = note;
                    frameAnimationLoops[visualLane] = activeHold;
                }
            }
        }
        long nowNanos = System.nanoTime();
        for (int lane = 0; lane < 8; lane++) {
            float laneX = previewLaneX(startX, spacing, lane);
            previewNoteHits.add(new PreviewNoteHit(lane, laneX, receptorY, noteSize));
            SongChart.Note animationNote = frameAnimationNotes[lane];
            if (animationNote != null) {
                if (previewAnimatedNote[lane] != animationNote
                        || previewAnimationLoops[lane] != frameAnimationLoops[lane]) {
                    previewAnimatedNote[lane] = animationNote;
                    previewAnimationLoops[lane] = frameAnimationLoops[lane];
                    previewAnimationStartedNanos[lane] = nowNanos;
                }
                long frame = Math.max(0L, (long) ((nowNanos - previewAnimationStartedNanos[lane])
                        * 24.0 / 1_000_000_000.0));
                NoteStyle.drawConfirmReceptor(gui, lane % 4, frame, frameAnimationLoops[lane],
                        laneX, receptorY, noteSize);
            } else {
                previewAnimatedNote[lane] = null;
                previewAnimationStartedNanos[lane] = 0;
                NoteStyle.drawReceptor(gui, lane % 4, laneX, receptorY, noteSize, 0);
            }
            if (lane % 4 == selectedLane) {
                int radius = Math.max(4, Math.round(noteSize * 0.6f));
                gui.renderOutline(Math.round(laneX) - radius, Math.round(receptorY) - radius,
                        radius * 2, radius * 2, 0xFFFFFFFF);
            }
        }

        NoteSkinConfig.Part splashTransform = NoteStyle.currentSkinConfig().splash();
        String chartSplash = ClientOptions.NOTE_SKIN_DEFAULT.equalsIgnoreCase(ClientOptions.get().noteSkin)
                && shownChart.noteSplashTexture != null ? shownChart.noteSplashTexture.trim() : "";
        for (int i = first; i < visibleEnd; i++) {
            SongChart.Note note = shownChart.notes.get(i);
            double delta = note.timeMs - songPosition;
            int visualLane = (note.playerSide ? 4 : 0) + Math.floorMod(note.lane, 4);
            float laneX = previewLaneX(startX, spacing, visualLane);
            float headY = (float) (ClientOptions.get().downscroll
                    ? receptorY - delta * pxPerMs : receptorY + delta * pxPerMs);
            double endDelta = note.timeMs + note.sustainMs - songPosition;
            float endY = (float) (ClientOptions.get().downscroll
                    ? receptorY - endDelta * pxPerMs : receptorY + endDelta * pxPerMs);
            boolean activeHold = previewBotHolds(note, songPosition);
            if (note.sustainMs > 30 && endDelta > 0) {
                float from = activeHold ? receptorY : headY;
                NoteStyle.drawHoldPiece(gui, note.lane, laneX, Math.min(from, endY), Math.max(from, endY),
                        noteSize, ClientOptions.get().downscroll);
            }
            if (delta >= 0 && headY > 25 && headY < height - 16) {
                NoteStyle.drawNote(gui, note.lane, laneX, headY, noteSize);
                previewNoteHits.add(new PreviewNoteHit(note.lane, laneX, headY, noteSize));
            }
            if (activeHold && NoteStyle.hasHoldCover(note.lane)) {
                long holdFrame = (long) ((songPosition - note.timeMs) * 24.0 / 1000.0);
                NoteStyle.drawHoldCover(gui, note.lane, holdFrame, laneX, receptorY, noteSize);
            }
            double holdEnd = note.timeMs + note.sustainMs;
            if (note.sustainMs > 30 && Double.isFinite(previousPreviewPosition)
                    && songPosition >= previousPreviewPosition
                    && previousPreviewPosition < holdEnd && songPosition >= holdEnd) {
                spawnPreviewCoverEnd(note.lane, visualLane, holdEnd);
            }
            double sinceHit = songPosition - note.timeMs;
            if (sinceHit >= 0 && sinceHit < 280) {
                int frame = (int) (sinceHit * 24 / 1000.0);
                boolean custom = false;
                if (!chartSplash.isBlank() && previewTextures != null
                        && frame < previewTextures.splashFrames(chartSplash, note.lane, 0)) {
                    custom = previewTextures.drawSplash(gui, chartSplash, note.lane, 0, frame,
                            laneX, receptorY, noteSize * 2.2f, splashTransform);
                }
                if (!custom && NoteStyle.splashVariants(note.lane) > 0
                        && frame < NoteStyle.splashFrameCount(note.lane, 0)) {
                    NoteStyle.drawSplash(gui, note.lane, 0, frame,
                            laneX, receptorY, noteSize * 2.2f);
                }
            }
        }
        for (var iterator = previewCoverEnds.iterator(); iterator.hasNext(); ) {
            PreviewCoverEnd end = iterator.next();
            int frame = (int) Math.max(0, (songPosition - end.startedAtMs()) * 24.0 / 1000.0);
            int total = NoteStyle.holdCoverEndFrames(end.lane());
            if (total <= 0 || frame >= total) {
                iterator.remove();
                continue;
            }
            NoteStyle.drawHoldCoverEnd(gui, end.lane(), frame,
                    previewLaneX(startX, spacing, end.visualLane()), receptorY, noteSize);
        }
        previousPreviewPosition = songPosition;
    }

    private void spawnPreviewCoverEnd(int lane, int visualLane, double startedAtMs) {
        if (NoteStyle.holdCoverEndFrames(lane) <= 0) return;
        previewCoverEnds.add(new PreviewCoverEnd(lane, visualLane, startedAtMs));
        if (previewCoverEnds.size() > 16) previewCoverEnds.remove(0);
    }

    /**
     * Keep the bot pressed on the render where a sustain tail crosses the receptor.
     * Without this crossing frame, normal frame-sized position jumps release on the
     * last frame before the tail arrives and make a clean hold look dropped.
     */
    private boolean previewBotHolds(SongChart.Note note, double position) {
        if (note.sustainMs <= 30 || position < note.timeMs) return false;
        double end = note.timeMs + note.sustainMs;
        if (position <= end) return true;
        return Double.isFinite(previousPreviewPosition)
                && previousPreviewPosition <= end
                && position - previousPreviewPosition < 250;
    }

    private static float previewLaneX(float startX, float spacing, int visualLane) {
        return startX + visualLane * spacing + (visualLane >= 4 ? STRUM_GROUP_GAP : 0f);
    }

    private SongChart displayedChart() {
        return previewChart == null || previewChart.notes.isEmpty() ? demoChart : previewChart;
    }

    private static SongChart createDemoChart() {
        SongChart chart = new SongChart();
        chart.title = "Note Settings Demo";
        chart.startBpm = 120;
        chart.speed = 1;
        for (boolean player : new boolean[]{false, true}) {
            for (int beat = 0; beat < 12; beat++) {
                int lane = beat % 4;
                double sustain = beat == 3 ? 1100 : beat == 8 ? 700 : 0;
                chart.notes.add(new SongChart.Note(700 + beat * 420, lane, player, sustain, ""));
            }
        }
        chart.notes.sort(Comparator.comparingDouble(note -> note.timeMs));
        return chart;
    }

    private static double maxSustainMs(SongChart chart) {
        double longest = 0;
        for (SongChart.Note note : chart.notes) longest = Math.max(longest, note.sustainMs);
        return longest;
    }

    private static double previewEndMs(SongChart chart) {
        double end = 0;
        for (SongChart.Note note : chart.notes) {
            end = Math.max(end, note.timeMs + Math.max(0, note.sustainMs));
        }
        return end;
    }

    private double previewDuration() {
        if (previewAudio == null) return Math.max(3000, cachedPreviewEndMs + 1600);
        double offset = (previewChart == null ? 0 : previewChart.offsetMs)
                + ClientOptions.get().offsetMs;
        return Math.max(cachedPreviewEndMs, previewAudio.durationMs() + Math.max(0, offset));
    }

    private void clearPreviewAnimations() {
        java.util.Arrays.fill(previewAnimatedNote, null);
        java.util.Arrays.fill(previewAnimationLoops, false);
        java.util.Arrays.fill(previewAnimationStartedNanos, 0);
        previewCoverEnds.clear();
        previousPreviewPosition = Double.NaN;
    }

    private double previewPosition() {
        if (previewAudio != null) {
            double audioPosition = previewAudio.positionMs();
            if (previewAudio.isFinished() && previewAudioFinishedNano > 0) {
                audioPosition = previewAudio.durationMs()
                        + (System.nanoTime() - previewAudioFinishedNano) / 1_000_000.0;
            }
            return audioPosition + (previewChart == null ? 0 : previewChart.offsetMs)
                    + ClientOptions.get().offsetMs;
        }
        if (demoPaused) return demoPausedPosition;
        double elapsed = (System.nanoTime() - previewClockNano) / 1_000_000.0;
        return elapsed % previewDuration();
    }

    private static int lowerBound(List<SongChart.Note> notes, double time) {
        int low = 0, high = notes.size();
        while (low < high) {
            int mid = (low + high) >>> 1;
            if (notes.get(mid).timeMs < time) low = mid + 1;
            else high = mid;
        }
        return low;
    }

    private void disposePreviewAudio() {
        if (previewAudio != null) previewAudio.dispose();
        previewAudio = null;
        previewAudioFinishedNano = 0;
    }

    private void cancelAndReturn() {
        restoreOptions();
        finishAndReturn();
    }

    private void finishAndReturn() {
        disposePreviewAudio();
        disposeColorPickerTextures();
        if (previewTextures != null) previewTextures.close();
        previewTextures = null;
        NoteStyle.setSongRgbAllowed(previousSongRgb);
        NoteStyle.useSongSkin(null, null, null, previousPixel);
        minecraft.setScreen(parent);
    }

    @Override
    public void onClose() {
        cancelAndReturn();
    }

    @Override
    public void renderBackground(GuiGraphics gui, int mouseX, int mouseY, float partialTick) {
        // render() owns the complete settings backdrop.
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
