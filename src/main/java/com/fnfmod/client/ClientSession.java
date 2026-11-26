package com.fnfmod.client;

import com.fnfmod.FnfMod;
import com.fnfmod.block.FunkinMachineBlock;
import com.fnfmod.character.CharacterTransform;
import com.fnfmod.gameplay.PlaybackMode;
import com.fnfmod.gameplay.PlaybackPolicy;
import com.fnfmod.chart.SongChart;
import com.fnfmod.client.anim.CharacterAnimations;
import com.fnfmod.client.audio.SongPlayer;
import com.fnfmod.client.gameplay.SongWarnings;
import com.fnfmod.client.gameplay.GameplayAssetPreloader;
import com.fnfmod.client.gui.GameplayScreen;
import com.fnfmod.client.render.NoteStyle;
import com.fnfmod.client.gui.WaitingScreen;
import com.fnfmod.client.render.WarningFlag;
import com.fnfmod.net.FnfPayloads;
import com.fnfmod.song.SongEntry;
import com.fnfmod.song.SongCompatibility;
import com.fnfmod.song.SongLibrary;
import com.fnfmod.client.gameplay.CharacterSelectionRouting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.network.PacketDistributor;

import java.io.IOException;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Client-side state for the current Funkin' Machine session:
 * local compatibility verification plus chart/audio/graphics preloading.
 * The server sends hashes only; song and asset bytes never cross the network.
 */
public final class ClientSession {

    public static BlockPos activePos;
    public static String songId = "";
    public static String difficulty = "";
    public static boolean duet;
    /** Known before ReadyC2S so character-opp.json can supply the stage rotation. */
    public static boolean opponentSide;
    /** Solo side choice sent with the last song selection: 0 player, 1 opponent, 2 both. */
    public static byte pendingPlaySide;
    public static PlaybackMode pendingPlaybackMode = PlaybackMode.MINECRAFT;
    /** Destination after solo gameplay exits; built-in selection remains the safe default. */
    public static byte pendingSongExitTarget = FnfPayloads.LeaveC2S.RETURN_SELECTOR;
    public static PlaybackMode playbackMode = PlaybackMode.MINECRAFT;
    /** Server-resolved rich-resource permission for the selected playback mode/source. */
    public static boolean songAssets = true;
    /** Dedicated servers disable gameplay Lua; integrated/LAN servers retain it. */
    public static boolean luaAllowed = true;
    /** Policy announced by the connected server before a song is selected. */
    public static boolean serverAllowsGameplayLua = true;
    /** The current chooser belongs to an automatic mod-world menu and cannot exit to world. */
    public static boolean lockedWorldMenu;

    public static SongChart chart;
    public static SongPlayer preloadedPlayer;
    public static Path resolvedFolder;

    /** Invalidates asynchronous hash checks when a session changes or the client disconnects. */
    private static long generation;
    private static boolean selectionPreparing;
    private static BlockPos taggedStagePos;
    private static Direction taggedStageFacing;
    private static long readySentAtNanos;
    private static java.util.function.Consumer<FnfPayloads.MachineTaggedPlayResultS2C> taggedPlayListener;

    public static void expectTaggedPlay(java.util.function.Consumer<FnfPayloads.MachineTaggedPlayResultS2C> listener) {
        taggedPlayListener = listener;
    }

    public static void onTaggedPlayResult(FnfPayloads.MachineTaggedPlayResultS2C result) {
        if (taggedPlayListener == null || !result.menuPos().equals(activePos)) return;
        var listener = taggedPlayListener;
        taggedPlayListener = null;
        if (result.error().isEmpty()) setTaggedStage(result.stagePos(), result.facing());
        // The same menu may currently be behind Minecraft's pause/settings screen.
        listener.accept(result);
    }

    public static void setTaggedStage(BlockPos pos, byte facing) {
        taggedStagePos = pos.immutable();
        Direction value = Direction.from3DDataValue(facing);
        taggedStageFacing = value.getAxis().isHorizontal() ? value : Direction.NORTH;
    }

    private ClientSession() {}

    public static void reset() {
        generation++;
        selectionPreparing = false;
        readySentAtNanos = 0;
        taggedStagePos = null;
        taggedStageFacing = null;
        taggedPlayListener = null;
        activePos = null;
        songId = "";
        difficulty = "";
        duet = false;
        opponentSide = false;
        pendingSongExitTarget = FnfPayloads.LeaveC2S.RETURN_SELECTOR;
        playbackMode = PlaybackMode.MINECRAFT;
        songAssets = true;
        luaAllowed = true;
        lockedWorldMenu = false;
        chart = null;
        resolvedFolder = null;
        if (preloadedPlayer != null) {
            preloadedPlayer.dispose();
            preloadedPlayer = null;
        }
    }

    public static void leave() {
        if (activePos != null) {
            PacketDistributor.sendToServer(new FnfPayloads.LeaveC2S(activePos, false,
                    FnfPayloads.LeaveC2S.RETURN_WORLD));
        }
        reset();
    }

    /**
     * Builds a compact local manifest off-thread, then submits the song choice.
     * The server coordinates the session but never needs to have the song installed.
     */
    public static boolean prepareSongSelection(BlockPos pos, String requestedSongId,
                                               String requestedDifficulty, boolean isDuet,
                                               byte playSide, PlaybackMode mode,
                                               boolean showWaitingScreen,
                                               Consumer<String> failed,
                                               Consumer<FnfPayloads.SongSelectionMeta> submit) {
        if (pos == null || requestedSongId == null || requestedSongId.isBlank()
                || requestedDifficulty == null || requestedDifficulty.isBlank()
                || submit == null || selectionPreparing) return false;

        Minecraft mc = Minecraft.getInstance();
        Screen previous = mc.screen;
        activePos = pos.immutable();
        pendingPlaySide = isDuet ? 0 : (byte) Math.max(0, Math.min(2, playSide));
        pendingPlaybackMode = mode == null ? PlaybackMode.MINECRAFT : mode;
        duet = isDuet;
        selectionPreparing = true;
        long requestGeneration = ++generation;
        if (showWaitingScreen) {
            mc.setScreen(new WaitingScreen(Component.literal("Preparing local song files...")));
        } else if (mc.player != null) {
            mc.player.displayClientMessage(Component.literal("Preparing local song files..."), true);
        }

        String songId = requestedSongId.trim();
        String difficulty = requestedDifficulty.trim();
        PlaybackMode selectedMode = pendingPlaybackMode;
        boolean allowLua = serverAllowsGameplayLua;
        CompletableFuture.supplyAsync(() -> createSelectionMeta(songId, difficulty, selectedMode, allowLua))
                .whenComplete((meta, error) -> mc.execute(() -> {
                    if (requestGeneration != generation) return;
                    selectionPreparing = false;
                    if (error != null) {
                        Throwable cause = error instanceof java.util.concurrent.CompletionException
                                && error.getCause() != null ? error.getCause() : error;
                        String message = cause.getMessage() == null ? cause.getClass().getSimpleName()
                                : cause.getMessage();
                        if (mc.player != null) mc.player.displayClientMessage(Component.literal(
                                "Could not prepare local song: " + message), false);
                        if (failed != null) failed.accept(message);
                        if (showWaitingScreen) mc.setScreen(previous);
                        return;
                    }
                    submit.accept(meta);
                }));
        return true;
    }

    private static FnfPayloads.SongSelectionMeta createSelectionMeta(String requestedSongId,
                                                                      String requestedDifficulty,
                                                                      PlaybackMode mode,
                                                                      boolean allowLua) {
        SongEntry found = SongLibrary.get(requestedSongId);
        if (found == null) {
            found = SongLibrary.getSongs().values().stream()
                    .filter(value -> value.id.equalsIgnoreCase(requestedSongId))
                    .findFirst().orElse(null);
        }
        final SongEntry entry = found;
        if (entry == null) throw new IllegalStateException(
                "Song '" + requestedSongId + "' is not installed in this player's song library");
        String difficulty = entry.difficulties.stream()
                .filter(value -> value.equalsIgnoreCase(requestedDifficulty)).findFirst()
                .orElseThrow(() -> new IllegalStateException("Difficulty '" + requestedDifficulty
                        + "' is not installed for " + entry.displayName));
        PlaybackPolicy resolved = PlaybackPolicy.resolve(mode, entry);
        PlaybackPolicy policy = new PlaybackPolicy(resolved.mode(), resolved.songAssets(), allowLua);
        try {
            SongCompatibility.Stamp stamp = SongCompatibility.stamp(entry, difficulty, policy);
            FnfPayloads.SongSelectionMeta result = new FnfPayloads.SongSelectionMeta(
                    stamp.fileCount(), stamp.totalBytes(), stamp.sha256(),
                    com.fnfmod.song.GameBananaModLinks.songModUrl(entry),
                    policy.songAssets(), policy.luaAllowed());
            if (!result.valid()) throw new IllegalStateException("Local song manifest is invalid");
            return result;
        } catch (IOException error) {
            throw new IllegalStateException("Could not fingerprint " + entry.displayName
                    + ": " + error.getMessage(), error);
        }
    }

    // ------------------------------------------------------------------

    public static void onCompatibility(FnfPayloads.SongCompatibilityS2C payload) {
        activePos = payload.pos();
        if (!activePos.equals(taggedStagePos)) {
            taggedStagePos = null;
            taggedStageFacing = null;
        }
        songId = payload.songId();
        difficulty = payload.difficulty();
        duet = payload.duet();
        opponentSide = payload.opponentSide();
        playbackMode = PlaybackMode.fromNetworkId(payload.playbackMode());
        songAssets = payload.songAssets();
        luaAllowed = payload.luaAllowed();
        selectionPreparing = false;
        SongCompatibility.Stamp expected = new SongCompatibility.Stamp(
                payload.fileCount(), payload.totalBytes(), payload.contentSha256());
        if (!expected.valid()) {
            fail("Server sent an invalid song compatibility fingerprint");
            return;
        }
        long requestGeneration = ++generation;
        String requestedSongId = songId;
        String requestedDifficulty = difficulty;
        PlaybackMode requestedMode = playbackMode;
        boolean requestedAssets = songAssets;
        boolean requestedLua = luaAllowed;

        Minecraft.getInstance().setScreen(new WaitingScreen(Component.literal(
                "Checking local song files...")));

        // Hash checking can be slow for big OGGs, but only hashes/metadata are
        // exchanged. Missing or changed files must be installed locally.
        CompletableFuture.supplyAsync(() -> checkLocalFiles(requestedSongId, requestedDifficulty,
                requestedMode, requestedAssets, requestedLua, expected)).whenComplete((result, err) -> {
            Minecraft.getInstance().execute(() -> {
                if (requestGeneration != generation) return;
                if (err != null) {
                    fail("Error checking song files: " + err.getMessage());
                    return;
                }
                if (result.error != null) {
                    var modLink = com.fnfmod.song.ModDownloadLinks.parse(payload.modUrl());
                    if (modLink != null) {
                        fail(result.error);
                        Minecraft.getInstance().setScreen(
                                new com.fnfmod.client.gui.MissingGameBananaModScreen(requestedSongId,
                                        modLink.url()));
                    } else if (isMissingLocalSong(result.error)) {
                        fail(result.error);
                        Minecraft.getInstance().setScreen(
                                new com.fnfmod.client.gui.MissingGameBananaModScreen(requestedSongId, ""));
                    } else fail(result.error);
                    return;
                }
                resolvedFolder = result.sourceEntry.folder;
                finishLoad(result.sourceEntry);
            });
        });
    }

    private static boolean isMissingLocalSong(String error) {
        return error != null && (error.startsWith("Missing local song ")
                || error.startsWith("Missing local difficulty ")
                || error.startsWith("Local song files do not match "));
    }

    private record LocalCheck(SongEntry sourceEntry, String error) {}

    private static LocalCheck checkLocalFiles(String requestedSongId, String requestedDifficulty,
                                              PlaybackMode requestedMode, boolean requestedAssets,
                                              boolean requestedLua,
                                              SongCompatibility.Stamp expected) {
        SongEntry sourceEntry = SongLibrary.get(requestedSongId);
        if (sourceEntry == null) {
            sourceEntry = SongLibrary.getSongs().values().stream()
                    .filter(value -> value.id.equalsIgnoreCase(requestedSongId))
                    .findFirst().orElse(null);
        }
        if (sourceEntry == null) {
            return new LocalCheck(null, "Missing local song '" + requestedSongId
                    + "'. Install its mod or song files locally to join.");
        }
        if (sourceEntry.difficulties.stream().noneMatch(
                value -> value.equalsIgnoreCase(requestedDifficulty))) {
            return new LocalCheck(null, "Missing local difficulty '" + requestedDifficulty
                    + "' for " + requestedSongId + ". Install a chart with this difficulty.");
        }
        try {
            SongCompatibility.Stamp local = SongCompatibility.stamp(sourceEntry, requestedDifficulty,
                    new com.fnfmod.gameplay.PlaybackPolicy(
                            requestedMode, requestedAssets, requestedLua));
            if (local.fileCount() != expected.fileCount()
                    || local.totalBytes() != expected.totalBytes()
                    || !local.sha256().equalsIgnoreCase(expected.sha256())) {
                return new LocalCheck(null, "Local song files do not match the server for '"
                        + requestedSongId + "' (server: " + expected.fileCount() + " files, "
                        + expected.totalBytes() + " bytes; local: " + local.fileCount()
                        + " files, " + local.totalBytes()
                        + " bytes). Both players need matching song resources, but may keep them in different local mods/folders.");
            }
        } catch (IOException error) {
            return new LocalCheck(null, "Could not verify local song '" + requestedSongId
                    + "': " + error.getMessage());
        }
        return new LocalCheck(sourceEntry, null);
    }

    private static void finishLoad(SongEntry directEntry) {
        try {
            SongEntry entry = directEntry;
            if (entry == null) throw new IOException("Song folder is invalid");
            chart = SongLibrary.loadChart(entry, difficulty);

            if (preloadedPlayer != null) preloadedPlayer.dispose();
            preloadedPlayer = new SongPlayer();
            preloadedPlayer.load(entry.instFor(difficulty),
                    chart.needsVoices ? entry.voicesFor(difficulty) : null,
                    chart.needsVoices ? entry.voicesPlayerFor(difficulty) : null,
                    chart.needsVoices ? entry.voicesOpponentFor(difficulty) : null);

            long preloadGeneration = generation;
            SongChart preloadChart = chart;
            Path preloadFolder = resolvedFolder;
            String preloadSongId = songId;
            com.fnfmod.gameplay.PlaybackPolicy preloadPolicy =
                    new com.fnfmod.gameplay.PlaybackPolicy(playbackMode, songAssets, luaAllowed);
            Minecraft.getInstance().setScreen(new WaitingScreen(Component.literal("Preparing graphics...")));
            CompletableFuture.supplyAsync(() -> GameplayAssetPreloader.prepare(
                    preloadChart, preloadSongId, preloadFolder, entry, preloadPolicy))
                    .whenComplete((plan, preloadError) -> Minecraft.getInstance().execute(() -> {
                        if (preloadGeneration != generation) return;
                        if (preloadError != null) {
                            FnfMod.LOGGER.warn("Could not preload gameplay graphics for {}: {}",
                                    preloadSongId, preloadError.toString());
                        } else if (plan != null) {
                            try {
                                // Character-set resolution must be active before
                                // the preload plan warms BBS forms referenced by
                                // Change Character events.
                                CharacterAnimations.useSongFolder(
                                        preloadPolicy.songAssets() ? entry.animationRoot() : null,
                                        preloadChart.player1, preloadChart.player2);
                                plan.warm();
                            } catch (Throwable uploadError) {
                                FnfMod.LOGGER.warn("Could not finish preloading gameplay graphics for {}: {}",
                                        preloadSongId, uploadError.toString());
                            }
                        }
                        finishReady(entry);
                    }));
        } catch (Exception e) {
            FnfMod.LOGGER.error("Failed to load song {}", songId, e);
            fail("Failed to load song: " + e.getMessage());
        }
    }

    private static void finishReady(SongEntry entry) {
        try {
            Path animationRoot = songAssets ? entry.animationRoot() : null;
            CharacterAnimations.useSongFolder(animationRoot, chart.player1, chart.player2);
            // Explicit Player characters and BBS animations follow the human onto
            // Dad's side; Default (song) still uses the chart's character slots.
            ClientOptions options = ClientOptions.get();
            boolean useOpponentChoice = CharacterSelectionRouting.localUsesOpponentChoice(
                    opponentSide, options);
            String animationSet = useOpponentChoice
                    ? options.opponentAnimationSet : options.animationSet;
            String animationType = useOpponentChoice
                    ? options.opponentAnimationSetType : options.animationSetType;
            boolean usesSongDefault = CharacterAnimations.DEFAULT_SET.equalsIgnoreCase(animationSet);
            if (usesSongDefault) {
                String chartType = chart.performerType(!opponentSide);
                if (!chartType.isBlank()) animationType = chartType;
            }
            boolean reversePlayer2D = opponentSide
                    && CharacterSelectionRouting.player2DFollowsOpponentSide(options);
            boolean animationFlipped = (useOpponentChoice
                    ? options.opponentAnimationSetFlipped : options.animationSetFlipped) ^ reversePlayer2D;
            boolean directionsFlipped = (useOpponentChoice
                    ? options.opponentAnimationSetDirectionsFlipped : options.animationSetDirectionsFlipped)
                    ^ reversePlayer2D;
            boolean frameCenterCamera = useOpponentChoice
                    ? options.opponentAnimationSetFrameCenterCamera
                    : options.animationSetFrameCenterCamera;
            Direction facing = Direction.NORTH;
            if (Minecraft.getInstance().level != null) {
                BlockState state = Minecraft.getInstance().level.getBlockState(activePos);
                if (state.hasProperty(FunkinMachineBlock.FACING)) {
                    facing = state.getValue(FunkinMachineBlock.FACING);
                }
            }
            CharacterTransform transform = ClientOptions.PERFORMER_TYPE_2D.equals(
                    ClientOptions.normalizePerformerType(animationType))
                    ? CharacterTransform.DEFAULT
                    : CharacterTransform.load(animationSet, animationRoot,
                    activePos.equals(taggedStagePos) ? taggedStageFacing : facing, opponentSide,
                    opponentSide ? chart.player2 : chart.player1);
            PacketDistributor.sendToServer(new FnfPayloads.ReadyC2S(activePos, animationSet,
                    animationType, animationFlipped, directionsFlipped, frameCenterCamera,
                    transform.positionOffset().x, transform.positionOffset().y,
                    transform.positionOffset().z, transform.rotationOffset(), noteVisuals()));
            readySentAtNanos = System.nanoTime();
            Minecraft.getInstance().setScreen(new WaitingScreen(Component.literal(
                    duet ? "Waiting for the other player..." : "Get ready...")));
        } catch (Exception e) {
            FnfMod.LOGGER.error("Failed to prepare song {}", songId, e);
            fail("Failed to prepare song: " + e.getMessage());
        }
    }

    public static void onStart(FnfPayloads.StartSongS2C payload) {
        if (chart == null || preloadedPlayer == null) {
            fail("Song was not loaded in time");
            return;
        }
        SongPlayer player = preloadedPlayer;
        UUID partner = payload.partnerId().orElse(null);
        // The server sends the real countdown only after this PlayState reports
        // that its first complete frame has rendered. Keep a provisional value
        // for construction; awaitServerStart() parks the clock before init runs.
        long startAt = System.currentTimeMillis() + payload.startDelayMs();
        GameplayScreen.PlayMode mode;
        if (partner != null) {
            mode = payload.playerSide() ? GameplayScreen.PlayMode.PLAYER : GameplayScreen.PlayMode.OPPONENT;
        } else {
            mode = switch (pendingPlaySide) {
                case 1 -> GameplayScreen.PlayMode.OPPONENT;
                case 2 -> GameplayScreen.PlayMode.BOTH;
                default -> GameplayScreen.PlayMode.PLAYER;
            };
        }
        // Pre-song advisories (heavy render distance, ...) do not block the song.
        // They are queued now and the gameplay screen slides them up as a flag.
        ClientOptions options = ClientOptions.get();
        if (options.warnBlockified() || options.warnSong()) {
            SongEntry entry = songId == null || songId.isBlank() ? null : SongLibrary.get(songId);
            for (SongWarnings.Warning warning : SongWarnings.collect(
                    new SongWarnings.Context(songId, resolvedFolder, entry),
                    options.warnBlockified(), options.warnSong())) {
                WarningFlag.show(warning.text(), warning.accentColor());
            }
        }

        if (payload.pos().equals(taggedStagePos))
            com.fnfmod.client.gameplay.StageOrientation.set(taggedStageFacing);
        try {
            GameplayScreen gameplay = new GameplayScreen(
                    payload.pos(), chart, player, mode,
                    partner, payload.partnerName(), payload.partnerAnimSet(), payload.partnerAnimType(),
                    payload.partnerAnimFlipped(), payload.partnerDirectionsFlipped(),
                    payload.partnerFrameCenterCamera(),
                    payload.partnerNoteVisuals(),
                    payload.botEntityId(), startAt);
            gameplay.awaitServerStart();
            Minecraft.getInstance().setScreen(gameplay);
            preloadedPlayer = null; // ownership moves to the screen after it opens
            readySentAtNanos = 0;
        } catch (RuntimeException error) {
            FnfMod.LOGGER.error("Failed to open gameplay for song {}", songId, error);
            fail("Failed to open gameplay: " + error.getMessage());
        }
    }

    private static FnfPayloads.NoteVisuals noteVisuals() {
        ClientOptions options = ClientOptions.get();
        return new FnfPayloads.NoteVisuals(options.noteSkin, options.noteSkinSource,
                options.splashSkin, options.splashSkinSource,
                options.holdSplashSkin, options.holdSplashSkinSource,
                options.downscroll,
                options.noteColorBase, options.noteColorHighlight, options.noteColorOutline,
                NoteStyle.currentSkinConfig());
    }

    /** Give a failed ready handshake a visible outcome instead of waiting forever. */
    public static void checkReadyTimeout() {
        if (readySentAtNanos != 0 && !duet
                && System.nanoTime() - readySentAtNanos > 30_000_000_000L) {
            FnfMod.LOGGER.error("Timed out waiting for song start: {} at {}", songId, activePos);
            fail("Song did not start within 30 seconds; check latest.log for the ready handshake");
        }
    }

    public static void onCancel(FnfPayloads.SessionCancelS2C payload) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof GameplayScreen gameplay) {
            gameplay.abort("Session ended: " + payload.reason());
        } else if (mc.screen instanceof WaitingScreen
                || mc.screen instanceof com.fnfmod.client.gui.SongSelectScreen) {
            mc.setScreen(null);
        }
        if (mc.player != null) {
            mc.player.displayClientMessage(Component.literal("FNF session cancelled: " + payload.reason()), false);
        }
        reset();
    }

    private static void fail(String message) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) mc.player.displayClientMessage(Component.literal(message), false);
        mc.setScreen(null);
        leave();
    }
}
