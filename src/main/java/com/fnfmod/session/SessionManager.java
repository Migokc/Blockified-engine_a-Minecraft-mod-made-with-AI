package com.fnfmod.session;

import com.fnfmod.FnfMod;
import com.fnfmod.block.FunkinMachineBlock;
import com.fnfmod.net.FnfPayloads;
import com.fnfmod.song.SongEntry;
import com.fnfmod.song.SongLibrary;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Server-side: one session per Funkin' Machine block. */
@EventBusSubscriber(modid = FnfMod.MODID)
public final class SessionManager {

    private static final int CHUNK_SIZE = 400 * 1024;
    private static final long MAX_SONG_BYTES = 256L * 1024 * 1024;

    private record Key(ResourceKey<Level> dim, BlockPos pos) {}

    private enum State { CHOOSING, WAITING_GUEST, PREPARING, PLAYING }

    private static final class Session {
        Key key;
        ServerPlayer host;
        ServerPlayer guest;
        String songId = "";
        String difficulty = "";
        boolean duet;
        State state = State.CHOOSING;
        boolean hostReady, guestReady;
        boolean hostEnded, guestEnded;
        String hostAnimSet = "default";
        String guestAnimSet = "default";
        /** solo only: 0 = player, 1 = opponent, 2 = both */
        byte playSide = 0;
        /** decorative bot armor stand in solo play */
        ArmorStand botStand;
    }

    private static final Map<Key, Session> SESSIONS = new HashMap<>();

    /** Where each participant stood before being placed on the stage. */
    private record ReturnPoint(double x, double y, double z, float yaw, float pitch) {}

    private static final Map<UUID, ReturnPoint> RETURN_POINTS = new HashMap<>();
    /** Real health captured before a vanilla-HUD song, restored afterwards. */
    private static final Map<UUID, Float> HEALTH_BEFORE = new HashMap<>();
    /** Invulnerability state before the song, restored afterwards. */
    private static final Map<UUID, Boolean> INVULN_BEFORE = new HashMap<>();

    private SessionManager() {}

    @SubscribeEvent
    public static void onServerStarting(ServerStartingEvent event) {
        SongLibrary.rescan();
    }

    @SubscribeEvent
    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer sp) {
            List<Key> toCancel = new ArrayList<>();
            for (var e : SESSIONS.entrySet()) {
                Session s = e.getValue();
                if (s.host == sp || s.guest == sp) toCancel.add(e.getKey());
            }
            for (Key k : toCancel) cancel(SESSIONS.get(k), sp, "Partner disconnected");
            restorePosition(sp); // covers finishing the song and logging out from the results screen
        }
    }

    private static Key keyOf(ServerPlayer player, BlockPos pos) {
        return new Key(player.level().dimension(), pos);
    }

    public static void onInteract(ServerPlayer player, BlockPos pos) {
        Key key = keyOf(player, pos);
        Session session = SESSIONS.get(key);

        if (session == null || session.host == player) {
            if (session == null || session.state == State.CHOOSING || session.state == State.WAITING_GUEST) {
                session = new Session();
                session.key = key;
                session.host = player;
                SESSIONS.put(key, session);
                PacketDistributor.sendToPlayer(player, new FnfPayloads.OpenMenuS2C(
                        pos, (byte) 0, player.getGameProfile().getName(), songList()));
            }
            return;
        }

        if (session.state == State.WAITING_GUEST && session.guest == null) {
            session.guest = player;
            session.state = State.PREPARING;
            // tell both sides
            PacketDistributor.sendToPlayer(session.host, new FnfPayloads.SessionStateS2C(
                    pos, (byte) 1, session.songId, session.difficulty, player.getGameProfile().getName()));
            SongEntry entry = SongLibrary.get(session.songId);
            if (entry != null) {
                PacketDistributor.sendToPlayer(player, new FnfPayloads.FileManifestS2C(
                        pos, session.songId, session.difficulty, true, manifest(entry, session.difficulty)));
            }
            return;
        }

        // machine is busy
        PacketDistributor.sendToPlayer(player, new FnfPayloads.OpenMenuS2C(
                pos, (byte) 2, session.host.getGameProfile().getName(), List.of()));
    }

    public static void onSelectSong(ServerPlayer player, FnfPayloads.SelectSongC2S payload) {
        Session session = SESSIONS.get(keyOf(player, payload.pos()));
        if (session == null || session.host != player) return;
        if (session.state != State.CHOOSING && session.state != State.WAITING_GUEST) return;

        SongEntry entry = SongLibrary.get(payload.songId());
        if (entry == null) {
            player.sendSystemMessage(Component.literal("Song not found on server: " + payload.songId()));
            return;
        }
        session.songId = payload.songId();
        session.difficulty = payload.difficulty();
        session.duet = payload.duet();
        session.playSide = payload.duet() ? 0 : (byte) Math.max(0, Math.min(2, payload.playSide()));
        session.hostReady = false;
        session.guestReady = false;

        if (payload.duet()) {
            session.state = State.WAITING_GUEST;
            PacketDistributor.sendToPlayer(player, new FnfPayloads.SessionStateS2C(
                    payload.pos(), (byte) 0, session.songId, session.difficulty, ""));
        } else {
            session.state = State.PREPARING;
        }
        PacketDistributor.sendToPlayer(player, new FnfPayloads.FileManifestS2C(
                payload.pos(), session.songId, session.difficulty, payload.duet(), manifest(entry, session.difficulty)));
    }

    public static void onRequestFiles(ServerPlayer player, FnfPayloads.RequestFilesC2S payload) {
        Session session = SESSIONS.get(keyOf(player, payload.pos()));
        if (session == null || (session.host != player && session.guest != player)) return;
        SongEntry entry = SongLibrary.get(payload.songId());
        if (entry == null) return;

        List<Path> candidates = entry.allTransferFiles();
        long total = 0;
        for (Path f : candidates) {
            try {
                total += Files.size(f);
            } catch (IOException ignored) {}
        }
        if (total > MAX_SONG_BYTES) {
            player.sendSystemMessage(Component.literal("Song is too large to transfer."));
            return;
        }

        for (Path f : candidates) {
            String name = f.getFileName().toString();
            if (!payload.fileNames().contains(name)) continue;
            try {
                byte[] bytes = Files.readAllBytes(f);
                int chunks = Math.max(1, (bytes.length + CHUNK_SIZE - 1) / CHUNK_SIZE);
                for (int i = 0; i < chunks; i++) {
                    int from = i * CHUNK_SIZE;
                    int to = Math.min(bytes.length, from + CHUNK_SIZE);
                    byte[] slice = new byte[to - from];
                    System.arraycopy(bytes, from, slice, 0, slice.length);
                    PacketDistributor.sendToPlayer(player, new FnfPayloads.FileChunkS2C(
                            payload.songId(), name, i, chunks, slice));
                }
            } catch (IOException e) {
                FnfMod.LOGGER.error("Failed to send song file {}", f, e);
            }
        }
    }

    public static void onReady(ServerPlayer player, FnfPayloads.ReadyC2S payload) {
        Session session = SESSIONS.get(keyOf(player, payload.pos()));
        if (session == null || session.state != State.PREPARING && session.state != State.WAITING_GUEST) return;

        if (session.host == player) {
            session.hostReady = true;
            session.hostAnimSet = payload.animSet();
        }
        if (session.guest == player) {
            session.guestReady = true;
            session.guestAnimSet = payload.animSet();
        }

        if (!session.duet && session.hostReady) {
            session.state = State.PLAYING;
            long startAt = 1500; // relative delay in ms
            placeOnStage(session.host, payload.pos(), session.playSide);
            spawnBotStand(session, payload.pos());
            PacketDistributor.sendToPlayer(session.host, new FnfPayloads.StartSongS2C(
                    payload.pos(), session.songId, session.difficulty, true, Optional.empty(), "", "", startAt));
        } else if (session.duet && session.hostReady && session.guestReady && session.guest != null) {
            session.state = State.PLAYING;
            long startAt = 3000; // relative delay in ms
            placeOnStage(session.host, payload.pos(), (byte) 0);
            placeOnStage(session.guest, payload.pos(), (byte) 1);
            UUID hostId = session.host.getUUID();
            UUID guestId = session.guest.getUUID();
            PacketDistributor.sendToPlayer(session.host, new FnfPayloads.StartSongS2C(
                    payload.pos(), session.songId, session.difficulty, true, Optional.of(guestId),
                    session.guest.getGameProfile().getName(), session.guestAnimSet, startAt));
            PacketDistributor.sendToPlayer(session.guest, new FnfPayloads.StartSongS2C(
                    payload.pos(), session.songId, session.difficulty, false, Optional.of(hostId),
                    session.host.getGameProfile().getName(), session.hostAnimSet, startAt));
        }
    }

    /**
     * Puts a participant on the stage: 2 blocks in front of the machine (its
     * FACING direction), player side to the camera's right, opponent to the
     * left, both facing away from the machine (toward the camera).
     */
    private static void placeOnStage(ServerPlayer player, BlockPos machinePos, byte playSide) {
        RETURN_POINTS.putIfAbsent(player.getUUID(), new ReturnPoint(
                player.getX(), player.getY(), player.getZ(), player.getYRot(), player.getXRot()));

        Direction facing = Direction.NORTH;
        BlockState state = player.serverLevel().getBlockState(machinePos);
        if (state.hasProperty(FunkinMachineBlock.FACING)) {
            facing = state.getValue(FunkinMachineBlock.FACING);
        }
        // camera looks back toward the machine; its screen-right is facing.getCounterClockWise()
        Direction right = facing.getCounterClockWise();
        double side = playSide == 0 ? 1.5 : playSide == 1 ? -1.5 : 0.0; // both = center stage
        double x = machinePos.getX() + 0.5 + facing.getStepX() * 2.0 + right.getStepX() * side;
        double z = machinePos.getZ() + 0.5 + facing.getStepZ() * 2.0 + right.getStepZ() * side;
        float yaw = facing.toYRot(); // face away from the machine = toward the camera
        player.teleportTo(player.serverLevel(), x, machinePos.getY(), z, yaw, 0f);
        // non-interactive while playing (no incoming damage); restored on session end
        INVULN_BEFORE.putIfAbsent(player.getUUID(), player.isInvulnerable());
        player.setInvulnerable(true);
    }

    /** Spawns the decorative bot armor stand on the opposite stage spot from the player. */
    private static void spawnBotStand(Session session, BlockPos machinePos) {
        ServerPlayer host = session.host;
        if (!(host.level() instanceof ServerLevel level)) return;
        Direction facing = Direction.NORTH;
        BlockState state = level.getBlockState(machinePos);
        if (state.hasProperty(FunkinMachineBlock.FACING)) facing = state.getValue(FunkinMachineBlock.FACING);
        Direction right = facing.getCounterClockWise();
        // player is on 'playSide'; the bot sits on the opposite side (or opponent slot in "both")
        double botSide = session.playSide == 0 ? -1.5 : 1.5;
        double x = machinePos.getX() + 0.5 + facing.getStepX() * 2.0 + right.getStepX() * botSide;
        double z = machinePos.getZ() + 0.5 + facing.getStepZ() * 2.0 + right.getStepZ() * botSide;
        // face toward the camera but angled slightly to the side the player is on
        float yaw = facing.toYRot() + (session.playSide == 0 ? 20f : -20f);

        ArmorStand stand = new ArmorStand(level, x, machinePos.getY(), z);
        stand.setYRot(yaw);
        stand.setYBodyRot(yaw);
        stand.setInvulnerable(true);
        stand.setNoGravity(true);
        stand.setNoBasePlate(true);
        stand.setShowArms(true);
        // disable all slot interactions so it can't be equipped or looted
        stand.setInvulnerable(true);
        if (level.addFreshEntity(stand)) {
            session.botStand = stand;
        }
    }

    private static void removeBotStand(Session session) {
        if (session != null && session.botStand != null) {
            session.botStand.discard();
            session.botStand = null;
        }
    }

    public static void onNoteEvent(ServerPlayer player, FnfPayloads.NoteEventC2S payload) {
        Session session = SESSIONS.get(keyOf(player, payload.pos()));
        if (session == null || session.state != State.PLAYING) return;
        ServerPlayer other = session.host == player ? session.guest : session.host;
        if (other != null) {
            PacketDistributor.sendToPlayer(other, new FnfPayloads.PartnerNoteS2C(
                    payload.lane(), payload.judgement(), payload.combo(), payload.score()));
        }
    }

    public static void onSongEnd(ServerPlayer player, FnfPayloads.SongEndC2S payload) {
        Session session = SESSIONS.get(keyOf(player, payload.pos()));
        if (session == null) return;
        if (session.host == player) session.hostEnded = true;
        if (session.guest == player) session.guestEnded = true;

        ServerPlayer other = session.host == player ? session.guest : session.host;
        if (other != null) {
            PacketDistributor.sendToPlayer(other, new FnfPayloads.PartnerEndS2C(
                    payload.score(), payload.misses(), payload.accuracy(), payload.failed()));
        }
        boolean allEnded = session.hostEnded && (session.guest == null || session.guestEnded || !session.duet);
        if (allEnded) {
            removeBotStand(session);
            SESSIONS.remove(session.key);
        }
    }

    public static void onReloadRequest(ServerPlayer player) {
        boolean dedicated = player.getServer() != null && player.getServer().isDedicatedServer();
        boolean rescanned = false;
        // ops can always rescan; on integrated/LAN servers anyone may (it's the host's folder)
        if (player.hasPermissions(2) || !dedicated) {
            SongLibrary.rescan();
            rescanned = true;
        }

        // if this player has the song menu open, push them the fresh list
        for (Session s : SESSIONS.values()) {
            if (s.host == player && s.state == State.CHOOSING) {
                PacketDistributor.sendToPlayer(player, new FnfPayloads.OpenMenuS2C(
                        s.key.pos(), (byte) 0, player.getGameProfile().getName(), songList()));
                break;
            }
        }

        if (rescanned) {
            player.sendSystemMessage(Component.literal(
                    "Song library reloaded: " + SongLibrary.getSongs().size() + " song(s)."));
        } else {
            player.sendSystemMessage(Component.literal(
                    "Song list refreshed. (Rescanning the server's folder requires op.)"));
        }
    }

    public static void onLeave(ServerPlayer player, BlockPos pos, boolean finishedOnly) {
        if (finishedOnly) {
            restorePosition(player);
            return;
        }
        Session session = SESSIONS.get(keyOf(player, pos));
        if (session == null) {
            restorePosition(player);
            return;
        }
        if (session.host == player || session.guest == player) {
            cancel(session, player, player.getGameProfile().getName() + " left");
        }
    }

    /** Teleports the player back to where they stood before the song, if recorded. */
    private static void restorePosition(ServerPlayer player) {
        Boolean inv = INVULN_BEFORE.remove(player.getUUID());
        if (inv != null) {
            try {
                player.setInvulnerable(inv);
            } catch (Exception ignored) {}
        }
        Float hp = HEALTH_BEFORE.remove(player.getUUID());
        if (hp != null) {
            try {
                player.setHealth(Math.max(1f, Math.min(player.getMaxHealth(), hp)));
            } catch (Exception ignored) {}
        }
        ReturnPoint rp = RETURN_POINTS.remove(player.getUUID());
        if (rp == null) return;
        try {
            // works during logout too — the restored position is what gets saved
            player.teleportTo(player.serverLevel(), rp.x, rp.y, rp.z, rp.yaw, rp.pitch);
        } catch (Exception e) {
            FnfMod.LOGGER.warn("Could not restore position of {}: {}", player.getGameProfile().getName(), e.toString());
        }
    }

    /** Vanilla HUD: set the player's real health without ever killing them. */
    public static void onSetHealth(ServerPlayer player, float health) {
        // only within an active session (return point recorded at song start)
        if (!RETURN_POINTS.containsKey(player.getUUID())) return;
        HEALTH_BEFORE.putIfAbsent(player.getUUID(), player.getHealth());
        float clamped = Math.max(1f, Math.min(player.getMaxHealth(), health));
        player.setHealth(clamped);
    }

    private static void cancel(Session session, ServerPlayer leaver, String reason) {
        if (session == null) return;
        removeBotStand(session);
        SESSIONS.remove(session.key);
        for (ServerPlayer p : new ServerPlayer[]{session.host, session.guest}) {
            if (p == null) continue;
            if (p != leaver && !p.hasDisconnected()) {
                PacketDistributor.sendToPlayer(p, new FnfPayloads.SessionCancelS2C(session.key.pos(), reason));
            }
            restorePosition(p);
        }
    }

    private static List<FnfPayloads.SongInfo> songList() {
        List<FnfPayloads.SongInfo> out = new ArrayList<>();
        for (SongEntry e : SongLibrary.getSongs().values()) {
            String iconPath = e.opponentIconFile != null ? e.opponentIconFile.toAbsolutePath().toString() : "";
            out.add(new FnfPayloads.SongInfo(e.id, e.displayName, List.copyOf(e.difficulties), e.opponentIcon, iconPath));
        }
        return out;
    }

    private static List<FnfPayloads.FileMeta> manifest(SongEntry entry, String difficulty) {
        List<FnfPayloads.FileMeta> out = new ArrayList<>();
        for (Path f : entry.transferFiles(difficulty)) {
            try {
                out.add(new FnfPayloads.FileMeta(f.getFileName().toString(), Files.size(f), sha1(f)));
            } catch (IOException e) {
                FnfMod.LOGGER.error("Failed to hash {}", f, e);
            }
        }
        return out;
    }

    public static String sha1(Path f) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] digest = md.digest(Files.readAllBytes(f));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new IOException(e);
        }
    }
}
