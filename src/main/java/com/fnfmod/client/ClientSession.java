package com.fnfmod.client;

import com.fnfmod.FnfMod;
import com.fnfmod.block.FunkinMachineBlock;
import com.fnfmod.character.CharacterTransform;
import com.fnfmod.gameplay.PlaybackMode;
import com.fnfmod.chart.SongChart;
import com.fnfmod.client.anim.CharacterAnimations;
import com.fnfmod.client.audio.SongPlayer;
import com.fnfmod.client.gameplay.SongWarnings;
import com.fnfmod.client.gameplay.GameplayAssetPreloader;
import com.fnfmod.client.gui.GameplayScreen;
import com.fnfmod.client.gui.WaitingScreen;
import com.fnfmod.client.render.WarningFlag;
import com.fnfmod.net.FnfPayloads;
import com.fnfmod.session.SessionManager;
import com.fnfmod.song.SongEntry;
import com.fnfmod.song.SongLibrary;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.network.PacketDistributor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Client-side state for the current Funkin' Machine session:
 * manifest verification, file download assembly, chart + audio preloading.
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

    public static SongChart chart;
    public static SongPlayer preloadedPlayer;
    public static Path resolvedFolder;

    private static List<FnfPayloads.FileMeta> manifest = List.of();
    private static final Map<String, byte[][]> receiving = new HashMap<>();
    private static List<String> missingFiles = new ArrayList<>();
    /** Invalidates asynchronous hash checks when a session changes or the client disconnects. */
    private static long generation;

    private ClientSession() {}

    public static void reset() {
        generation++;
        activePos = null;
        songId = "";
        difficulty = "";
        duet = false;
        opponentSide = false;
        pendingSongExitTarget = FnfPayloads.LeaveC2S.RETURN_SELECTOR;
        playbackMode = PlaybackMode.MINECRAFT;
        songAssets = true;
        chart = null;
        resolvedFolder = null;
        manifest = List.of();
        receiving.clear();
        missingFiles.clear();
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

    // ------------------------------------------------------------------

    public static void onManifest(FnfPayloads.FileManifestS2C payload) {
        activePos = payload.pos();
        songId = payload.songId();
        difficulty = payload.difficulty();
        duet = payload.duet();
        opponentSide = payload.opponentSide();
        playbackMode = PlaybackMode.fromNetworkId(payload.playbackMode());
        songAssets = payload.songAssets();
        manifest = payload.files();
        receiving.clear();
        long requestGeneration = ++generation;
        String requestedSongId = songId;
        String requestedDifficulty = difficulty;
        PlaybackMode requestedMode = playbackMode;
        boolean requestedAssets = songAssets;
        List<FnfPayloads.FileMeta> requestedManifest = List.copyOf(manifest);
        Path requestedCacheDir = cacheVariantFolder(requestedSongId, requestedDifficulty,
                requestedMode, requestedAssets);

        Minecraft.getInstance().setScreen(new WaitingScreen(Component.literal("Loading song...")));

        // hash checking can be slow for big oggs -> background thread
        CompletableFuture.supplyAsync(() -> checkLocalFiles(requestedSongId, requestedDifficulty,
                requestedMode, requestedAssets, requestedManifest, requestedCacheDir)).whenComplete((result, err) -> {
            Minecraft.getInstance().execute(() -> {
                if (requestGeneration != generation) return;
                if (err != null) {
                    fail("Error checking song files: " + err.getMessage());
                    return;
                }
                if (result.sourceEntry != null) {
                    resolvedFolder = result.sourceEntry.folder;
                    finishLoad(result.sourceEntry);
                } else if (result.allLocal) {
                    resolvedFolder = SongLibrary.songsDir().resolve(songId);
                    finishLoad(null);
                } else {
                    resolvedFolder = requestedCacheDir;
                    missingFiles = result.missingInCache;
                    if (missingFiles.isEmpty()) {
                        finishLoad(null);
                    } else {
                        Minecraft.getInstance().setScreen(new WaitingScreen(
                                Component.literal("Downloading song from server...")));
                        PacketDistributor.sendToServer(new FnfPayloads.RequestFilesC2S(
                                activePos, songId, new ArrayList<>(missingFiles)));
                    }
                }
            });
        });
    }

    private record LocalCheck(SongEntry sourceEntry, boolean allLocal, List<String> missingInCache) {}

    private static LocalCheck checkLocalFiles(String requestedSongId, String requestedDifficulty,
                                              PlaybackMode requestedMode, boolean requestedAssets,
                                              List<FnfPayloads.FileMeta> requestedManifest,
                                              Path cacheDir) {
        SongEntry sourceEntry = SongLibrary.get(requestedSongId);
        if (sourceEntry != null && matchesEntry(sourceEntry, requestedDifficulty,
                new com.fnfmod.gameplay.PlaybackPolicy(requestedMode, requestedAssets), requestedManifest)) {
            return new LocalCheck(sourceEntry, false, List.of());
        }
        Path songDir = SongLibrary.songsDir().resolve(requestedSongId);
        boolean allLocal = true;
        for (FnfPayloads.FileMeta meta : requestedManifest) {
            if (!matches(manifestPath(songDir, meta.name()), meta)) {
                allLocal = false;
                break;
            }
        }
        if (allLocal) return new LocalCheck(null, true, List.of());

        List<String> missing = new ArrayList<>();
        for (FnfPayloads.FileMeta meta : requestedManifest) {
            if (!matches(manifestPath(cacheDir, meta.name()), meta)) {
                missing.add(meta.name());
            }
        }
        return new LocalCheck(null, false, missing);
    }

    private static boolean matchesEntry(SongEntry entry, String requestedDifficulty,
                                        com.fnfmod.gameplay.PlaybackPolicy policy,
                                        List<FnfPayloads.FileMeta> requestedManifest) {
        Map<String, List<Path>> byName = new HashMap<>();
        for (Path file : entry.transferFiles(requestedDifficulty, policy)) {
            byName.computeIfAbsent(entry.transferName(file), ignored -> new ArrayList<>()).add(file);
        }
        for (FnfPayloads.FileMeta meta : requestedManifest) {
            List<Path> candidates = byName.get(meta.name());
            if (candidates == null || candidates.stream().noneMatch(path -> matches(path, meta))) return false;
        }
        return true;
    }

    private static Path cacheVariantFolder(String requestedSongId, String requestedDifficulty,
                                           PlaybackMode requestedMode, boolean requestedAssets) {
        String songKey = sanitize(requestedSongId) + "__" + Integer.toHexString(requestedSongId.hashCode());
        String difficultyKey = sanitize(requestedDifficulty) + "__"
                + Integer.toHexString(requestedDifficulty.hashCode());
        String variant = difficultyKey + "__" + requestedMode.name().toLowerCase()
                + (requestedAssets ? "__assets" : "__restricted");
        return SongLibrary.cacheDir().resolve(songKey).resolve(variant);
    }

    private static boolean matches(Path file, FnfPayloads.FileMeta meta) {
        try {
            return file != null && Files.isRegularFile(file)
                    && Files.size(file) == meta.size()
                    && SessionManager.sha1(file).equalsIgnoreCase(meta.sha1());
        } catch (IOException e) {
            return false;
        }
    }

    public static void onChunk(FnfPayloads.FileChunkS2C payload) {
        if (!payload.songId().equals(songId)) return;
        String name = safeRelativeName(payload.fileName());
        if (name == null) {
            fail("Server sent an unsafe song resource path");
            return;
        }
        byte[][] chunks = receiving.computeIfAbsent(name, k -> new byte[payload.totalChunks()][]);
        if (payload.chunkIndex() < 0 || payload.chunkIndex() >= chunks.length) return;
        chunks[payload.chunkIndex()] = payload.data();

        for (byte[] c : chunks) {
            if (c == null) return; // still waiting
        }
        // file complete -> write to cache
        try {
            Path target = manifestPath(resolvedFolder, name);
            if (target == null) throw new IOException("unsafe resource path");
            Files.createDirectories(target.getParent());
            int total = 0;
            for (byte[] c : chunks) total += c.length;
            byte[] all = new byte[total];
            int off = 0;
            for (byte[] c : chunks) {
                System.arraycopy(c, 0, all, off, c.length);
                off += c.length;
            }
            Files.write(target, all);
        } catch (IOException e) {
            fail("Could not save downloaded file " + name + ": " + e.getMessage());
            return;
        }
        receiving.remove(name);
        missingFiles.remove(payload.fileName());
        if (missingFiles.isEmpty()) finishLoad(null);
    }

    private static String sanitize(String name) {
        return name.replaceAll("[^a-zA-Z0-9 ._()\\[\\]-]", "_");
    }

    private static String safeRelativeName(String raw) {
        if (raw == null) return null;
        String value = raw.replace('\\', '/').trim();
        if (value.isBlank() || value.startsWith("/") || value.contains(":") || value.contains("\u0000")) return null;
        Path relative;
        try { relative = Path.of(value).normalize(); }
        catch (Exception ignored) { return null; }
        if (relative.isAbsolute() || relative.startsWith("..")) return null;
        return relative.toString().replace('\\', '/');
    }

    private static Path manifestPath(Path root, String raw) {
        String relative = safeRelativeName(raw);
        if (root == null || relative == null) return null;
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path path = normalizedRoot.resolve(relative).normalize();
        return path.startsWith(normalizedRoot) ? path : null;
    }

    private static void finishLoad(SongEntry directEntry) {
        try {
            SongLibrary.touchCacheEntry(resolvedFolder); // keeps used songs safe from cache pruning
            SongEntry entry = directEntry != null ? directEntry : SongLibrary.scanSongDir(resolvedFolder);
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
                    new com.fnfmod.gameplay.PlaybackPolicy(playbackMode, songAssets);
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
            String animationSet = ClientOptions.get().animationSet;
            Direction facing = Direction.NORTH;
            if (Minecraft.getInstance().level != null) {
                BlockState state = Minecraft.getInstance().level.getBlockState(activePos);
                if (state.hasProperty(FunkinMachineBlock.FACING)) {
                    facing = state.getValue(FunkinMachineBlock.FACING);
                }
            }
            CharacterTransform transform = CharacterTransform.load(
                    animationSet, animationRoot, facing, opponentSide,
                    opponentSide ? chart.player2 : chart.player1);
            PacketDistributor.sendToServer(new FnfPayloads.ReadyC2S(activePos, animationSet,
                    transform.positionOffset().x, transform.positionOffset().y,
                    transform.positionOffset().z, transform.rotationOffset()));
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
        preloadedPlayer = null; // ownership moves to the screen
        UUID partner = payload.partnerId().orElse(null);
        // server sends a relative delay; wall clocks across machines can't be trusted
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

        Minecraft.getInstance().setScreen(new GameplayScreen(
                payload.pos(), chart, player, mode,
                partner, payload.partnerName(), payload.partnerAnimSet(), payload.botEntityId(), startAt));
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
