package com.fnfmod.client;

import com.fnfmod.FnfMod;
import com.fnfmod.chart.SongChart;
import com.fnfmod.client.audio.SongPlayer;
import com.fnfmod.client.gui.GameplayScreen;
import com.fnfmod.client.gui.WaitingScreen;
import com.fnfmod.net.FnfPayloads;
import com.fnfmod.session.SessionManager;
import com.fnfmod.song.SongEntry;
import com.fnfmod.song.SongLibrary;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
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
    /** Solo side choice sent with the last song selection: 0 player, 1 opponent, 2 both. */
    public static byte pendingPlaySide;

    public static SongChart chart;
    public static SongPlayer preloadedPlayer;
    public static Path resolvedFolder;

    private static List<FnfPayloads.FileMeta> manifest = List.of();
    private static final Map<String, byte[][]> receiving = new HashMap<>();
    private static List<String> missingFiles = new ArrayList<>();

    private ClientSession() {}

    public static void reset() {
        activePos = null;
        songId = "";
        difficulty = "";
        duet = false;
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
            PacketDistributor.sendToServer(new FnfPayloads.LeaveC2S(activePos, false));
        }
        reset();
    }

    // ------------------------------------------------------------------

    public static void onManifest(FnfPayloads.FileManifestS2C payload) {
        activePos = payload.pos();
        songId = payload.songId();
        difficulty = payload.difficulty();
        duet = payload.duet();
        manifest = payload.files();
        receiving.clear();

        Minecraft.getInstance().setScreen(new WaitingScreen(Component.literal("Loading song...")));

        // hash checking can be slow for big oggs -> background thread
        CompletableFuture.supplyAsync(ClientSession::checkLocalFiles).whenComplete((result, err) -> {
            Minecraft.getInstance().execute(() -> {
                if (err != null) {
                    fail("Error checking song files: " + err.getMessage());
                    return;
                }
                if (result.allLocal) {
                    resolvedFolder = SongLibrary.songsDir().resolve(songId);
                    finishLoad();
                } else {
                    resolvedFolder = SongLibrary.cacheDir().resolve(sanitize(songId));
                    missingFiles = result.missingInCache;
                    if (missingFiles.isEmpty()) {
                        finishLoad();
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

    private record LocalCheck(boolean allLocal, List<String> missingInCache) {}

    private static LocalCheck checkLocalFiles() {
        Path songDir = SongLibrary.songsDir().resolve(songId);
        boolean allLocal = true;
        for (FnfPayloads.FileMeta meta : manifest) {
            if (!matches(songDir.resolve(meta.name()), meta)) {
                allLocal = false;
                break;
            }
        }
        if (allLocal) return new LocalCheck(true, List.of());

        Path cacheDir = SongLibrary.cacheDir().resolve(sanitize(songId));
        List<String> missing = new ArrayList<>();
        for (FnfPayloads.FileMeta meta : manifest) {
            if (!matches(cacheDir.resolve(meta.name()), meta)) {
                missing.add(meta.name());
            }
        }
        return new LocalCheck(false, missing);
    }

    private static boolean matches(Path file, FnfPayloads.FileMeta meta) {
        try {
            return Files.isRegularFile(file)
                    && Files.size(file) == meta.size()
                    && SessionManager.sha1(file).equalsIgnoreCase(meta.sha1());
        } catch (IOException e) {
            return false;
        }
    }

    public static void onChunk(FnfPayloads.FileChunkS2C payload) {
        if (!payload.songId().equals(songId)) return;
        String name = sanitize(payload.fileName());
        byte[][] chunks = receiving.computeIfAbsent(name, k -> new byte[payload.totalChunks()][]);
        if (payload.chunkIndex() < 0 || payload.chunkIndex() >= chunks.length) return;
        chunks[payload.chunkIndex()] = payload.data();

        for (byte[] c : chunks) {
            if (c == null) return; // still waiting
        }
        // file complete -> write to cache
        try {
            Files.createDirectories(resolvedFolder);
            int total = 0;
            for (byte[] c : chunks) total += c.length;
            byte[] all = new byte[total];
            int off = 0;
            for (byte[] c : chunks) {
                System.arraycopy(c, 0, all, off, c.length);
                off += c.length;
            }
            Files.write(resolvedFolder.resolve(name), all);
        } catch (IOException e) {
            fail("Could not save downloaded file " + name + ": " + e.getMessage());
            return;
        }
        receiving.remove(name);
        missingFiles.remove(payload.fileName());
        if (missingFiles.isEmpty()) finishLoad();
    }

    private static String sanitize(String name) {
        return name.replaceAll("[^a-zA-Z0-9 ._()\\[\\]-]", "_");
    }

    private static void finishLoad() {
        try {
            SongLibrary.touchCacheEntry(resolvedFolder); // keeps used songs safe from cache pruning
            SongEntry entry = SongLibrary.scanSongDir(resolvedFolder);
            if (entry == null) throw new IOException("Song folder is invalid");
            chart = SongLibrary.loadChart(entry, difficulty);

            if (preloadedPlayer != null) preloadedPlayer.dispose();
            preloadedPlayer = new SongPlayer();
            preloadedPlayer.load(entry.instFor(difficulty),
                    chart.needsVoices ? entry.voicesFor(difficulty) : null,
                    chart.needsVoices ? entry.voicesPlayerFor(difficulty) : null,
                    chart.needsVoices ? entry.voicesOpponentFor(difficulty) : null);

            PacketDistributor.sendToServer(new FnfPayloads.ReadyC2S(activePos,
                    ClientOptions.get().animationSet));
            Minecraft.getInstance().setScreen(new WaitingScreen(Component.literal(
                    duet ? "Waiting for the other player..." : "Get ready...")));
        } catch (Exception e) {
            FnfMod.LOGGER.error("Failed to load song {}", songId, e);
            fail("Failed to load song: " + e.getMessage());
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
        Minecraft.getInstance().setScreen(new GameplayScreen(
                payload.pos(), chart, player, mode,
                partner, payload.partnerName(), payload.partnerAnimSet(), startAt));
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
