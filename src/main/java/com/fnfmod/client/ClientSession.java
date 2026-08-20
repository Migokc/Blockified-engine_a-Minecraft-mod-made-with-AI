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
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
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
    /** Dedicated servers disable gameplay Lua; integrated/LAN servers retain it. */
    public static boolean luaAllowed = true;

    public static SongChart chart;
    public static SongPlayer preloadedPlayer;
    public static Path resolvedFolder;

    private static List<FnfPayloads.FileMeta> manifest = List.of();
    private static final Map<String, FnfPayloads.FileMeta> manifestByName = new HashMap<>();
    private static final Map<String, IncomingFile> receiving = new HashMap<>();
    private static List<String> missingFiles = new ArrayList<>();
    private static final int MAX_CONCURRENT_FILES = 8;
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
        luaAllowed = true;
        chart = null;
        resolvedFolder = null;
        manifest = List.of();
        manifestByName.clear();
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
        luaAllowed = payload.luaAllowed();
        List<FnfPayloads.FileMeta> checkedManifest = validateManifest(payload.files());
        if (checkedManifest == null) {
            fail("Server sent an invalid or oversized song manifest");
            return;
        }
        manifest = checkedManifest;
        manifestByName.clear();
        for (FnfPayloads.FileMeta meta : manifest) manifestByName.put(meta.name(), meta);
        receiving.clear();
        long requestGeneration = ++generation;
        String requestedSongId = songId;
        String requestedDifficulty = difficulty;
        PlaybackMode requestedMode = playbackMode;
        boolean requestedAssets = songAssets;
        boolean requestedLua = luaAllowed;
        List<FnfPayloads.FileMeta> requestedManifest = List.copyOf(manifest);
        Path requestedCacheDir = cacheVariantFolder(requestedSongId, requestedDifficulty,
                requestedMode, requestedAssets, requestedLua);

        Minecraft.getInstance().setScreen(new WaitingScreen(Component.literal("Loading song...")));

        // hash checking can be slow for big oggs -> background thread
        CompletableFuture.supplyAsync(() -> checkLocalFiles(requestedSongId, requestedDifficulty,
                requestedMode, requestedAssets, requestedLua, requestedManifest, requestedCacheDir)).whenComplete((result, err) -> {
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
                                              boolean requestedLua,
                                              List<FnfPayloads.FileMeta> requestedManifest,
                                              Path cacheDir) {
        SongEntry sourceEntry = SongLibrary.get(requestedSongId);
        if (sourceEntry != null && matchesEntry(sourceEntry, requestedDifficulty,
                new com.fnfmod.gameplay.PlaybackPolicy(requestedMode, requestedAssets, requestedLua),
                requestedManifest)) {
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
                                           PlaybackMode requestedMode, boolean requestedAssets,
                                           boolean requestedLua) {
        String songKey = sanitize(requestedSongId) + "__" + Integer.toHexString(requestedSongId.hashCode());
        String difficultyKey = sanitize(requestedDifficulty) + "__"
                + Integer.toHexString(requestedDifficulty.hashCode());
        String variant = difficultyKey + "__" + requestedMode.name().toLowerCase()
                + (requestedAssets ? "__assets" : "__restricted")
                + (requestedLua ? "__lua" : "__no_lua");
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

    private static List<FnfPayloads.FileMeta> validateManifest(List<FnfPayloads.FileMeta> files) {
        if (files == null || files.size() > FnfPayloads.MAX_MANIFEST_FILES) return null;
        List<FnfPayloads.FileMeta> checked = new ArrayList<>(files.size());
        java.util.HashSet<String> names = new java.util.HashSet<>();
        long total = 0;
        for (FnfPayloads.FileMeta meta : files) {
            if (meta == null) return null;
            String name = safeRelativeName(meta.name());
            if (name == null || name.length() > FnfPayloads.MAX_FILE_NAME_LENGTH || !names.add(name)) return null;
            if (meta.size() < 0) return null;
            if (meta.sha1() == null || !meta.sha1().matches("(?i)[0-9a-f]{40}")) return null;
            if (Long.MAX_VALUE - total < meta.size()) return null;
            total += meta.size();
            checked.add(new FnfPayloads.FileMeta(name, meta.size(), meta.sha1().toLowerCase(java.util.Locale.ROOT)));
        }
        return List.copyOf(checked);
    }

    private static final class IncomingFile {
        final FnfPayloads.FileMeta meta;
        final byte[][] chunks;

        IncomingFile(FnfPayloads.FileMeta meta, int chunkCount) {
            this.meta = meta;
            this.chunks = new byte[chunkCount][];
        }
    }

    public static void onChunk(FnfPayloads.FileChunkS2C payload) {
        if (!payload.songId().equals(songId)) return;
        String name = safeRelativeName(payload.fileName());
        FnfPayloads.FileMeta meta = name == null ? null : manifestByName.get(name);
        if (meta == null || !missingFiles.contains(name)) {
            fail("Server sent a song resource that was not requested");
            return;
        }
        int expectedChunks = (int) Math.max(1,
                (meta.size() + FnfPayloads.MAX_CHUNK_BYTES - 1) / FnfPayloads.MAX_CHUNK_BYTES);
        if (payload.totalChunks() != expectedChunks || payload.chunkIndex() < 0
                || payload.chunkIndex() >= expectedChunks || payload.data() == null) {
            fail("Server sent invalid song chunk metadata");
            return;
        }
        long start = (long) payload.chunkIndex() * FnfPayloads.MAX_CHUNK_BYTES;
        int expectedBytes = (int) Math.min(FnfPayloads.MAX_CHUNK_BYTES, Math.max(0, meta.size() - start));
        if (payload.data().length != expectedBytes) {
            fail("Server sent a song chunk with the wrong size");
            return;
        }
        IncomingFile incoming = receiving.get(name);
        if (incoming == null) {
            if (receiving.size() >= MAX_CONCURRENT_FILES) {
                fail("Server exceeded the concurrent song-file limit");
                return;
            }
            incoming = new IncomingFile(meta, expectedChunks);
            receiving.put(name, incoming);
        }
        if (incoming.chunks.length != expectedChunks || incoming.chunks[payload.chunkIndex()] != null) {
            fail("Server sent a duplicate or inconsistent song chunk");
            return;
        }
        incoming.chunks[payload.chunkIndex()] = payload.data();

        for (byte[] c : incoming.chunks) {
            if (c == null) return; // still waiting
        }
        // Complete file: stream chunks into a temporary cache file, verify the
        // manifest size and SHA-1, then publish it atomically.
        Path temporary = null;
        try {
            Path target = manifestPath(resolvedFolder, name);
            if (target == null) throw new IOException("unsafe resource path");
            Files.createDirectories(target.getParent());
            temporary = target.resolveSibling(target.getFileName() + ".part-" + UUID.randomUUID());
            try (OutputStream output = Files.newOutputStream(temporary)) {
                for (byte[] chunk : incoming.chunks) output.write(chunk);
            }
            if (Files.size(temporary) != meta.size()
                    || !SessionManager.sha1(temporary).equalsIgnoreCase(meta.sha1())) {
                throw new IOException("download failed size/hash verification");
            }
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            fail("Could not save downloaded file " + name + ": " + e.getMessage());
            return;
        } finally {
            if (temporary != null) {
                try { Files.deleteIfExists(temporary); }
                catch (IOException ignored) {}
            }
        }
        receiving.remove(name);
        missingFiles.remove(name);
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
            // Animation choices belong to the chart role, not to whichever human
            // happens to control it. A player joining/choosing Dad therefore sends
            // the Opponent Anims selection to the server.
            String animationSet = opponentSide
                    ? ClientOptions.get().opponentAnimationSet
                    : ClientOptions.get().animationSet;
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
