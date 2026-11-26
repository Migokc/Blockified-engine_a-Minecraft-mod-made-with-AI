package com.fnfmod.session;

import com.fnfmod.FnfMod;
import com.fnfmod.block.FunkinMachineBlock;
import com.fnfmod.chart.SongChart;
import com.fnfmod.chart.CommandEventPlaceholders;
import com.fnfmod.chart.ChartEventTypes;
import com.fnfmod.character.CharacterTransform;
import com.fnfmod.gameplay.PlaybackMode;
import com.fnfmod.gameplay.PlaybackPolicy;
import com.fnfmod.machine.MachineLibrary;
import com.fnfmod.machine.MachineMenuService;
import com.fnfmod.net.FnfPayloads;
import com.fnfmod.song.SongEntry;
import com.fnfmod.song.SongCompatibility;
import com.fnfmod.song.SongLibrary;
import com.fnfmod.world.ModContentScope;
import com.fnfmod.world.ModWorldOptions;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.network.chat.Component;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Server-side: one session per Funkin' Machine block. */
@EventBusSubscriber(modid = FnfMod.MODID)
public final class SessionManager {

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
        /** Second readiness barrier: PlayState initialized and rendered a complete frame. */
        boolean hostGameplayReady, guestGameplayReady, beginSongSent;
        boolean hostEnded, guestEnded;
        UUID nearbySessionId;
        long nearbyClockNano;
        double nearbyClockMs;
        boolean nearbyPaused;
        String hostAnimSet = "default";
        String guestAnimSet = "default";
        String hostAnimType = "3d";
        String guestAnimType = "3d";
        FnfPayloads.NoteVisuals hostNoteVisuals = FnfPayloads.NoteVisuals.DEFAULT;
        FnfPayloads.NoteVisuals guestNoteVisuals = FnfPayloads.NoteVisuals.DEFAULT;
        boolean hostAnimFlipped;
        boolean guestAnimFlipped;
        boolean hostDirectionsFlipped;
        boolean guestDirectionsFlipped;
        boolean hostFrameCenterCamera;
        boolean guestFrameCenterCamera;
        CharacterTransform hostTransform = CharacterTransform.DEFAULT;
        CharacterTransform guestTransform = CharacterTransform.DEFAULT;
        /** solo only: 0 = player, 1 = opponent, 2 = both */
        byte playSide = 0;
        PlaybackPolicy playbackPolicy = PlaybackPolicy.resolve(PlaybackMode.MINECRAFT, null);
        SongCompatibility.Stamp compatibility;
        String modUrl = "";
        /** decorative bot armor stand in solo play */
        ArmorStand botStand;
        /** invisible command target at the Funkin' Machine/speakers. */
        ArmorStand speakersMarker;
        /** Originating Lua chooser, distinct from the tagged stage used for gameplay. */
        Key menuOrigin;
        ServerLevel menuStageTicketLevel;
        /** Prevents duet clients or duplicate packets from running a server event twice. */
        final Set<Integer> executedServerEvents = new HashSet<>();
        /** A guest rejected for missing files must leave the join marker or explicitly retry. */
        final Set<UUID> autoJoinSuppressedGuests = new HashSet<>();
        long luaCommandWindowNanos;
        int luaCommandsInWindow;
        /** First state seen for each block changed by a song command. */
        final Map<WorldBlockKey, BlockSnapshot> changedBlocks = new HashMap<>();
        /** First weather state per dimension changed synchronously by a song command. */
        final Map<ResourceKey<Level>, WeatherRollback.Snapshot> changedWeather = new HashMap<>();
        /** World time captured at song start, so a song's /time change is undone on exit. */
        Long startGameTime;
        long startDayTime;
        boolean startDaylight;
        /** Each participant's gamemode at song start, restored on exit so a song's /gamemode change is undone. */
        final Map<java.util.UUID, net.minecraft.world.level.GameType> startGameModes = new HashMap<>();
    }

    private static final Map<Key, Session> SESSIONS = new HashMap<>();
    private static int nearbyBroadcastTicks;
    private record MenuReturnRoute(Key menu, Key stage) {}
    // Survives onSongEnd, which removes the session before the results screen closes.
    private static final Map<UUID, MenuReturnRoute> MENU_RETURN_ROUTES = new HashMap<>();
    private static final net.minecraft.server.level.TicketType<BlockPos> MENU_STAGE_TICKET =
            net.minecraft.server.level.TicketType.create("fnfmod_menu_stage",
                    java.util.Comparator.comparingLong(BlockPos::asLong));
    /** One isolated, local-only transaction per chart-editor playtester. */
    private static final Map<UUID, Session> EDITOR_PLAYTESTS = new HashMap<>();

    private record WorldBlockKey(ResourceKey<Level> dimension, BlockPos pos) {}
    private record BlockSnapshot(BlockState state, CompoundTag blockEntity) {}
    /** Commands execute synchronously on server thread. Null outside a song command. */
    private static Session activeMutationSession;
    private static boolean restoringWorld;

    /** Where each participant stood before being placed on the stage. */
    private record ReturnPoint(double x, double y, double z, float yaw, float pitch) {}

    private static final Map<UUID, ReturnPoint> RETURN_POINTS = new HashMap<>();
    /** Full player NBT before stage placement: inventory, XP, effects, abilities, etc. */
    private static final Map<UUID, CompoundTag> PLAYER_STATE_BEFORE = new HashMap<>();
    /** Gamemode is restored after player NBT so loading abilities cannot overwrite it. */
    private static final Map<UUID, net.minecraft.world.level.GameType> PLAYER_GAME_MODE_BEFORE = new HashMap<>();
    private record WorldTimeBefore(ResourceKey<Level> dimension, long gameTime,
                                   long dayTime, boolean daylight) {}
    /** Final-exit fallback: reapplies world time after every other player/session restore. */
    private static final Map<UUID, WorldTimeBefore> WORLD_TIME_BEFORE = new HashMap<>();
    /** Real health captured before a vanilla-HUD song, restored afterwards. */
    private static final Map<UUID, Float> HEALTH_BEFORE = new HashMap<>();
    private record FoodBefore(int level, float saturation, float exhaustion) {}
    private record VanillaHudTarget(float health, int foodLevel) {}
    /** Food state captured before a vanilla-HUD song, restored afterwards. */
    private static final Map<UUID, FoodBefore> FOOD_BEFORE = new HashMap<>();
    /** Authoritative values pinned after each server tick while the song is active. */
    private static final Map<UUID, VanillaHudTarget> VANILLA_HUD_TARGETS = new HashMap<>();
    /** Invulnerability state before the song, restored afterwards. */
    private static final Map<UUID, Boolean> INVULN_BEFORE = new HashMap<>();

    private SessionManager() {}

    @SubscribeEvent
    public static void onServerStarting(ServerStartingEvent event) {
        com.fnfmod.world.ChunkLoaderPointService.onServerStarting();
        ModContentScope.bindWorld(event.getServer().getWorldPath(LevelResource.ROOT));
        ModWorldOptions.loadActiveWorld();
        if (ModWorldOptions.preventSaving()) {
            event.getServer().getAllLevels().forEach(level -> level.noSave = true);
        }
        SongLibrary.rescan();
        MachineLibrary.rescan();
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        MENU_RETURN_ROUTES.clear();
        ModWorldOptions.clear();
        ModContentScope.clear();
        MachineLibrary.rescan();
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        var iterator = VANILLA_HUD_TARGETS.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            ServerPlayer player = event.getServer().getPlayerList().getPlayer(entry.getKey());
            if (player == null || !RETURN_POINTS.containsKey(entry.getKey())) {
                iterator.remove();
                continue;
            }
            applyVanillaHudTarget(player, entry.getValue());
        }
        for (Session session : SESSIONS.values()) {
            if (session.state == State.WAITING_GUEST && session.duet && session.guest == null) {
                updateJoinGuide(event, session);
            }
        }
        if (++nearbyBroadcastTicks >= 10) {
            nearbyBroadcastTicks = 0;
            for (Session session : SESSIONS.values()) broadcastNearbySong(event, session);
        }
    }

    /** The join marker is only a rendered volume. Crossing it replaces a second machine click. */
    private static void updateJoinGuide(ServerTickEvent.Post event, Session session) {
        ServerLevel level = event.getServer().getLevel(session.key.dim());
        if (level == null || session.host == null || session.host.hasDisconnected()) return;
        Vec3 center = guestStageSpot(level, session.key.pos());
        AABB trigger = new AABB(center.x - 0.65, center.y - 0.15, center.z - 0.65,
                center.x + 0.65, center.y + 2.1, center.z + 0.65);
        // Missing-file handling detaches a guest while leaving the chooser's session
        // alive. Do not immediately re-add that same player just because they are
        // still standing inside the marker; let them leave it or explicitly retry.
        for (ServerPlayer candidate : level.players()) {
            if (session.autoJoinSuppressedGuests.contains(candidate.getUUID())
                    && !candidate.getBoundingBox().intersects(trigger)) {
                session.autoJoinSuppressedGuests.remove(candidate.getUUID());
            }
        }
        for (ServerPlayer candidate : level.players()) {
            if (candidate == session.host || candidate.isSpectator() || !candidate.isAlive()
                    || !candidate.getBoundingBox().intersects(trigger)
                    || session.autoJoinSuppressedGuests.contains(candidate.getUUID())
                    || alreadyInSession(candidate)) continue;
            joinGuest(session, candidate);
            return;
        }
        if (nearbyBroadcastTicks != 9) return;
        FnfPayloads.JoinGuideS2C marker = new FnfPayloads.JoinGuideS2C(
                session.key.pos(), center.x, center.y, center.z);
        for (ServerPlayer candidate : level.players()) {
            if (candidate != session.host && candidate.position().distanceToSqr(center) <= 48 * 48) {
                PacketDistributor.sendToPlayer(candidate, marker);
            }
        }
    }

    private static boolean alreadyInSession(ServerPlayer player) {
        for (Session candidate : SESSIONS.values()) {
            if (candidate.host == player || candidate.guest == player) return true;
        }
        return false;
    }

    private static Vec3 guestStageSpot(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        Direction facing = state.hasProperty(FunkinMachineBlock.FACING)
                ? state.getValue(FunkinMachineBlock.FACING) : Direction.NORTH;
        Direction right = facing.getCounterClockWise();
        return new Vec3(pos.getX() + 0.5 + facing.getStepX() * 2.0 - right.getStepX() * 1.5,
                pos.getY(), pos.getZ() + 0.5 + facing.getStepZ() * 2.0 - right.getStepZ() * 1.5);
    }

    private static void broadcastNearbySong(ServerTickEvent.Post event, Session session) {
        if (session.state != State.PLAYING || !session.beginSongSent
                || session.nearbySessionId == null || session.host == null) return;
        long now = System.nanoTime();
        boolean stale = session.nearbyClockNano > 0
                && now - session.nearbyClockNano > 1_500_000_000L;
        boolean paused = session.nearbyPaused || stale;
        double position = session.nearbyClockMs;
        if (!paused) position += (now - session.nearbyClockNano) / 1_000_000.0;
        if (!Double.isFinite(position)) return;
        Vec3 source = Vec3.atCenterOf(session.key.pos());
        List<FnfPayloads.NearbyPerformer> performers = new ArrayList<>(2);
        performers.add(new FnfPayloads.NearbyPerformer(session.host.getUUID(),
                session.hostAnimSet, session.hostAnimType,
                session.hostAnimFlipped, session.hostDirectionsFlipped,
                session.duet || session.playSide != 1));
        if (session.guest != null) performers.add(new FnfPayloads.NearbyPerformer(
                session.guest.getUUID(), session.guestAnimSet, session.guestAnimType,
                session.guestAnimFlipped, session.guestDirectionsFlipped, false));
        for (ServerPlayer listener : event.getServer().getPlayerList().getPlayers()) {
            if (listener == session.host || listener == session.guest
                    || !listener.level().dimension().equals(session.key.dim())
                    || listener.position().distanceToSqr(source) > 64.0 * 64.0) continue;
            PacketDistributor.sendToPlayer(listener, new FnfPayloads.NearbySongS2C(
                    session.nearbySessionId, session.key.pos(), session.songId,
                    session.difficulty, position, paused, performers));
        }
    }

    public static void onSongClock(ServerPlayer player, FnfPayloads.SongClockC2S payload) {
        Session session = SESSIONS.get(keyOf(player, payload.pos()));
        if (session == null || session.state != State.PLAYING || !session.beginSongSent
                || session.host != player || !Double.isFinite(payload.songMs())
                || payload.songMs() < -30_000 || payload.songMs() > 86_400_000) return;
        session.nearbyClockMs = payload.songMs();
        session.nearbyClockNano = System.nanoTime();
        session.nearbyPaused = payload.paused();
    }

    @SubscribeEvent
    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer sp) {
            List<Key> toCancel = new ArrayList<>();
            for (var e : SESSIONS.entrySet()) {
                Session s = e.getValue();
                if (s.host == sp || s.guest == sp) toCancel.add(e.getKey());
            }
            boolean detachedFromActiveDuet = false;
            for (Key k : toCancel) {
                Session session = SESSIONS.get(k);
                if (canDetachWaitingGuest(session, sp)) {
                    detachWaitingGuest(session, sp);
                } else if (canDetachDuetParticipant(session, sp)) {
                    detachDuetParticipant(session, sp);
                    detachedFromActiveDuet = true;
                } else cancel(session, sp, "Partner disconnected");
            }
            Session editor = EDITOR_PLAYTESTS.remove(sp.getUUID());
            if (editor != null) {
                clearSessionActors(editor);
                restoreWorld(editor);
            }
            com.fnfmod.machine.MachineHitboxService.clearPlayer(sp);
            com.fnfmod.machine.MachineMenuService.clearPlayer(sp);
            MENU_RETURN_ROUTES.remove(sp.getUUID());
            // A continuing duet still owns the shared weather/time rollback.
            restorePosition(sp, !detachedFromActiveDuet);
        }
    }

    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        String modId = "";
        String packVersion = "built-in";
        boolean integratedOwner = usesIntegratedOwnerScope(player);
        if (integratedOwner && ModContentScope.isModWorld()) {
            modId = ModContentScope.activeMod().map(ModContentScope.ActiveMod::id).orElse("");
            if (!modId.isBlank()) packVersion = MachineLibrary.packVersion();
        }
        // A remote LAN client uses its own configured mods/directories. Only the
        // integrated-server owner inherits the bundled world's restrictive scope.
        boolean automaticMenu = player.getServer() != null && !player.getServer().isDedicatedServer()
                && ModContentScope.isModWorld() && ModWorldOptions.autoOpenMenu();
        PacketDistributor.sendToPlayer(player, new FnfPayloads.ModScopeS2C(
                modId, packVersion, automaticMenu,
                player.getServer() != null && !player.getServer().isDedicatedServer()));
        if (automaticMenu) {
            MachineMenuService.openAutomatic(player);
        }
    }

    /** Whether this connection represents the local owner of an integrated server. */
    public static boolean usesIntegratedOwnerScope(ServerPlayer player) {
        return player != null && player.getServer() != null
                && !player.getServer().isDedicatedServer()
                && player.getServer().isSingleplayerOwner(player.getGameProfile());
    }

    /** Local bundled-pack identity to send to a client; remote LAN clients keep their own scope. */
    public static String clientModScopeId(ServerPlayer player) {
        if (!usesIntegratedOwnerScope(player) || !ModContentScope.isModWorld()) return "";
        return ModContentScope.activeMod().map(ModContentScope.ActiveMod::id).orElse("");
    }

    public static String clientModScopeVersion(ServerPlayer player) {
        return clientModScopeId(player).isBlank() ? "built-in" : MachineLibrary.packVersion();
    }

    private static Key keyOf(ServerPlayer player, BlockPos pos) {
        return new Key(player.level().dimension(), pos);
    }

    /** Cancels any session whose real/virtual machine origin was removed. */
    public static void onMachineRemoved(ServerLevel level, BlockPos pos) {
        if (level == null || pos == null) return;
        Session session = SESSIONS.get(new Key(level.dimension(), pos));
        if (session != null) cancel(session, null, "Machine removed");
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
                        pos, (byte) 0, player.getGameProfile().getName(), availableSongs()));
            }
            return;
        }

        if (session.state == State.WAITING_GUEST && session.guest == null) {
            // Clicking the machine is an explicit retry, including when the guest
            // stayed inside the guide volume while installing the missing mod.
            session.autoJoinSuppressedGuests.remove(player.getUUID());
            joinGuest(session, player);
            return;
        }

        // machine is busy
        PacketDistributor.sendToPlayer(player, new FnfPayloads.OpenMenuS2C(
                pos, (byte) 2, session.host.getGameProfile().getName(), List.of()));
    }

    private static void joinGuest(Session session, ServerPlayer player) {
        if (session == null || session.state != State.WAITING_GUEST || session.guest != null
                || session.host == player) return;
        session.guest = player;
        session.state = State.PREPARING;
        PacketDistributor.sendToPlayer(session.host, new FnfPayloads.SessionStateS2C(
                session.key.pos(), (byte) 1, session.songId, session.difficulty,
                player.getGameProfile().getName()));
        if (session.compatibility != null) {
            PacketDistributor.sendToPlayer(player, new FnfPayloads.SongCompatibilityS2C(
                    session.key.pos(), session.songId, session.difficulty, true, true,
                    session.playbackPolicy.mode().networkId(), session.playbackPolicy.songAssets(),
                    session.playbackPolicy.luaAllowed(), session.compatibility.fileCount(),
                    session.compatibility.totalBytes(), session.compatibility.sha256(),
                    session.modUrl));
        } else {
            cancel(session, player, "The selected song manifest is unavailable");
        }
    }

    /** Reserves chooser ownership for a custom Lua menu without opening the built-in selector. */
    public static boolean prepareCustomMenu(ServerPlayer player, BlockPos pos) {
        Key key = keyOf(player, pos);
        Session session = SESSIONS.get(key);
        if (session == null) {
            session = new Session();
            session.key = key;
            session.host = player;
            SESSIONS.put(key, session);
            return true;
        }
        return session.host == player
                && (session.state == State.CHOOSING || session.state == State.WAITING_GUEST);
    }

    public static boolean ownsIdleMenu(ServerPlayer player, BlockPos pos) {
        Session session = SESSIONS.get(keyOf(player, pos));
        return session != null && session.host == player && session.guest == null
                && session.state == State.CHOOSING;
    }

    /** Atomically moves an owned Lua chooser to a named stage; never steals another session. */
    public static boolean onTaggedSelectSong(ServerPlayer player, BlockPos menuPos,
                                             FnfPayloads.MachineDirectPlayC2S payload) {
        if (!ownsIdleMenu(player, menuPos)) return false;
        Key origin = keyOf(player, menuPos);
        Key targetKey = keyOf(player, payload.pos());
        Session source = SESSIONS.get(origin);
        if (!origin.equals(targetKey) && SESSIONS.containsKey(targetKey)) return false;
        Session target = origin.equals(targetKey) ? source : new Session();
        target.key = targetKey;
        target.host = player;
        target.menuOrigin = origin;
        target.menuStageTicketLevel = player.serverLevel();
        target.menuStageTicketLevel.getChunkSource().addRegionTicket(MENU_STAGE_TICKET,
                new net.minecraft.world.level.ChunkPos(payload.pos()), 2, payload.pos());
        SESSIONS.put(targetKey, target);
        try {
            onSelectSong(player, new FnfPayloads.SelectSongC2S(payload.pos(), payload.songId(),
                    payload.difficulty(), payload.duet(), payload.playSide(), payload.playbackMode(),
                    payload.meta()));
            if (target.state == State.CHOOSING) return false;
            if (!origin.equals(targetKey)) SESSIONS.remove(origin, source);
            MENU_RETURN_ROUTES.put(player.getUUID(), new MenuReturnRoute(origin, targetKey));
            return true;
        } finally {
            if (target.state == State.CHOOSING) {
                releaseMenuStageTicket(target);
                target.menuOrigin = null;
                if (!origin.equals(targetKey)) SESSIONS.remove(targetKey, target);
            }
        }
    }

    private static void releaseMenuStageTicket(Session session) {
        if (session.menuStageTicketLevel == null) return;
        session.menuStageTicketLevel.getChunkSource().removeRegionTicket(MENU_STAGE_TICKET,
                new net.minecraft.world.level.ChunkPos(session.key.pos()), 2, session.key.pos());
        session.menuStageTicketLevel = null;
    }

    /** Creates/reuses a host session without opening the built-in selector. */
    public static boolean onDirectSelectSong(ServerPlayer player, FnfPayloads.MachineDirectPlayC2S payload) {
        Key key = keyOf(player, payload.pos());
        Session session = SESSIONS.get(key);
        if (session == null) {
            session = new Session();
            session.key = key;
            session.host = player;
            SESSIONS.put(key, session);
        } else if (session.host != player) {
            return false;
        }
        if (session.state != State.CHOOSING && session.state != State.WAITING_GUEST) return false;
        onSelectSong(player, new FnfPayloads.SelectSongC2S(payload.pos(), payload.songId(),
                payload.difficulty(), payload.duet(), payload.playSide(), payload.playbackMode(),
                payload.meta()));
        return true;
    }

    public static void onSelectSong(ServerPlayer player, FnfPayloads.SelectSongC2S payload) {
        Session session = SESSIONS.get(keyOf(player, payload.pos()));
        if (session == null || session.host != player) return;
        if (session.state != State.CHOOSING && session.state != State.WAITING_GUEST) return;

        FnfPayloads.SongSelectionMeta meta = payload.meta();
        if (payload.songId() == null || payload.songId().isBlank() || payload.songId().length() > 256
                || payload.difficulty() == null || payload.difficulty().isBlank()
                || payload.difficulty().length() > 128 || meta == null || !meta.valid()) {
            cancel(session, null, "Invalid local song selection manifest");
            return;
        }
        // Dedicated servers never run gameplay Lua. Rich visual/audio resources
        // may still be used; participants already have byte-identical local copies.
        // The selected player's local manifest is authoritative because song data is
        // intentionally never uploaded to or required on the server.
        if (player.getServer().isDedicatedServer() && meta.luaAllowed()) {
            cancel(session, null, "Gameplay Lua is disabled by this server. Reopen the song selector and try again.");
            return;
        }
        PlaybackPolicy policy = new PlaybackPolicy(
                PlaybackMode.fromNetworkId(payload.playbackMode()), meta.songAssets(), meta.luaAllowed());
        SongCompatibility.Stamp selectedCompatibility = new SongCompatibility.Stamp(
                meta.fileCount(), meta.totalBytes(), meta.contentSha256());

        MENU_RETURN_ROUTES.remove(player.getUUID());
        session.songId = payload.songId();
        session.difficulty = payload.difficulty();
        session.duet = payload.duet();
        session.playSide = payload.duet() ? 0 : (byte) Math.max(0, Math.min(2, payload.playSide()));
        session.playbackPolicy = policy;
        session.compatibility = selectedCompatibility;
        session.modUrl = meta.modUrl();
        session.hostReady = false;
        session.guestReady = false;
        session.hostGameplayReady = false;
        session.guestGameplayReady = false;
        session.beginSongSent = false;
        session.executedServerEvents.clear();

        if (session.menuOrigin != null) {
            BlockState stage = player.serverLevel().getBlockState(payload.pos());
            Direction facing = stage.hasProperty(FunkinMachineBlock.FACING)
                    ? stage.getValue(FunkinMachineBlock.FACING) : Direction.NORTH;
            // Must precede the manifest: ready/preload may run before distant client chunks arrive.
            PacketDistributor.sendToPlayer(player, new FnfPayloads.MachineTaggedPlayResultS2C(
                    session.menuOrigin.pos(), payload.pos(), (byte) facing.get3DDataValue(), ""));
        }

        if (payload.duet()) {
            session.state = State.WAITING_GUEST;
            PacketDistributor.sendToPlayer(player, new FnfPayloads.SessionStateS2C(
                    payload.pos(), (byte) 0, session.songId, session.difficulty, ""));
        } else {
            session.state = State.PREPARING;
        }
        PacketDistributor.sendToPlayer(player, new FnfPayloads.SongCompatibilityS2C(
                payload.pos(), session.songId, session.difficulty, payload.duet(),
                !payload.duet() && session.playSide == 1,
                session.playbackPolicy.mode().networkId(), session.playbackPolicy.songAssets(),
                session.playbackPolicy.luaAllowed(), selectedCompatibility.fileCount(),
                selectedCompatibility.totalBytes(), selectedCompatibility.sha256(),
                session.modUrl));
    }

    public static void onReady(ServerPlayer player, FnfPayloads.ReadyC2S payload) {
        Session session = SESSIONS.get(keyOf(player, payload.pos()));
        if (session == null || session.state != State.PREPARING && session.state != State.WAITING_GUEST) {
            FnfMod.LOGGER.warn("Ignoring song ready from {} at {}: session state {}",
                    player.getGameProfile().getName(), payload.pos(),
                    session == null ? "missing" : session.state);
            PacketDistributor.sendToPlayer(player, new FnfPayloads.SessionCancelS2C(
                    payload.pos(), "Song session expired before loading finished"));
            return;
        }
        FnfMod.LOGGER.info("Song ready: {} ({})", session.songId,
                player.getGameProfile().getName());
        try {

        if (session.host == player) {
            session.hostReady = true;
            session.hostAnimSet = payload.animSet();
            session.hostAnimType = payload.animType();
            session.hostNoteVisuals = payload.noteVisuals();
            session.hostAnimFlipped = payload.animFlipped();
            session.hostDirectionsFlipped = payload.directionsFlipped();
            session.hostFrameCenterCamera = payload.frameCenterCamera();
            session.hostTransform = transformFrom(payload);
        }
        if (session.guest == player) {
            session.guestReady = true;
            session.guestAnimSet = payload.animSet();
            session.guestAnimType = payload.animType();
            session.guestNoteVisuals = payload.noteVisuals();
            session.guestAnimFlipped = payload.animFlipped();
            session.guestDirectionsFlipped = payload.directionsFlipped();
            session.guestFrameCenterCamera = payload.frameCenterCamera();
            session.guestTransform = transformFrom(payload);
        }

        if (!session.duet && session.hostReady) {
            session.state = State.PLAYING;
            long startAt = 1500; // relative delay in ms
            placeOnStage(session.host, payload.pos(), session.playSide, session.hostTransform);
            spawnBotStand(session, payload.pos());
            prepareCommandTargets(session, payload.pos());
            int botEntityId = session.botStand == null ? -1 : session.botStand.getId();
            PacketDistributor.sendToPlayer(session.host, new FnfPayloads.StartSongS2C(
                    payload.pos(), session.songId, session.difficulty, true, Optional.empty(), "", "",
                    "3d", false, false, false,
                    FnfPayloads.NoteVisuals.DEFAULT, botEntityId, startAt));
        } else if (session.duet && session.hostReady && session.guestReady && session.guest != null) {
            session.state = State.PLAYING;
            long startAt = 3000; // relative delay in ms
            placeOnStage(session.host, payload.pos(), (byte) 0, session.hostTransform);
            placeOnStage(session.guest, payload.pos(), (byte) 1, session.guestTransform);
            prepareCommandTargets(session, payload.pos());
            UUID hostId = session.host.getUUID();
            UUID guestId = session.guest.getUUID();
            PacketDistributor.sendToPlayer(session.host, new FnfPayloads.StartSongS2C(
                    payload.pos(), session.songId, session.difficulty, true, Optional.of(guestId),
                    session.guest.getGameProfile().getName(), session.guestAnimSet,
                    session.guestAnimType, session.guestAnimFlipped,
                    session.guestDirectionsFlipped, session.guestFrameCenterCamera,
                    session.guestNoteVisuals, -1, startAt));
            PacketDistributor.sendToPlayer(session.guest, new FnfPayloads.StartSongS2C(
                    payload.pos(), session.songId, session.difficulty, false, Optional.of(hostId),
                    session.host.getGameProfile().getName(), session.hostAnimSet,
                    session.hostAnimType, session.hostAnimFlipped,
                    session.hostDirectionsFlipped, session.hostFrameCenterCamera,
                    session.hostNoteVisuals, -1, startAt));
        }
        } catch (RuntimeException error) {
            FnfMod.LOGGER.error("Could not start song {} after client readiness", session.songId, error);
            cancel(session, null, "Could not start song: " + error.getClass().getSimpleName());
            MENU_RETURN_ROUTES.remove(player.getUUID());
        }
    }

    /**
     * Begins the countdown only after all participating clients have built PlayState,
     * run load-time Lua/events, and rendered once. A fixed packet delay alone could
     * expire while the render thread was still uploading assets, cutting off the intro.
     */
    public static void onGameplayReady(ServerPlayer player, FnfPayloads.GameplayReadyC2S payload) {
        Session session = SESSIONS.get(keyOf(player, payload.pos()));
        if (session == null || session.state != State.PLAYING || session.beginSongSent) return;
        if (session.host == player) session.hostGameplayReady = true;
        else if (session.guest == player) session.guestGameplayReady = true;
        else return;

        boolean everyoneReady = session.duet
                ? session.hostGameplayReady && session.guestGameplayReady && session.guest != null
                : session.hostGameplayReady;
        if (!everyoneReady) return;

        session.beginSongSent = true;
        long delay = session.duet ? 3000 : 1500;
        session.nearbySessionId = UUID.randomUUID();
        session.nearbyClockNano = System.nanoTime();
        session.nearbyClockMs = -delay;
        session.nearbyPaused = false;
        PacketDistributor.sendToPlayer(session.host,
                new FnfPayloads.BeginSongS2C(payload.pos(), delay));
        if (session.duet && session.guest != null) {
            PacketDistributor.sendToPlayer(session.guest,
                    new FnfPayloads.BeginSongS2C(payload.pos(), delay));
        }
    }

    /**
     * Makes a normal solo restart equivalent to leaving and starting again while
     * retaining the active session and already-verified local song files.
     */
    public static void onRestartSong(ServerPlayer player, FnfPayloads.RestartSongC2S payload) {
        Session session = SESSIONS.get(keyOf(player, payload.pos()));
        if (session == null || session.state != State.PLAYING || session.host != player
                || session.duet) return;

        // Restore everything affected by the previous attempt before establishing
        // a fresh rollback baseline for the replacement attempt.
        clearSessionActors(session);
        restoreWorld(session);
        restorePosition(player);

        session.executedServerEvents.clear();
        session.hostEnded = false;
        session.guestEnded = false;
        session.luaCommandWindowNanos = 0;
        session.luaCommandsInWindow = 0;
        session.hostGameplayReady = false;
        session.guestGameplayReady = false;
        session.beginSongSent = false;

        placeOnStage(player, payload.pos(), session.playSide, session.hostTransform);
        spawnBotStand(session, payload.pos());
        prepareCommandTargets(session, payload.pos());
        int botEntityId = session.botStand == null ? -1 : session.botStand.getId();
        PacketDistributor.sendToPlayer(player,
                new FnfPayloads.RestartSongS2C(payload.pos(), botEntityId, 2000));
    }

    /**
     * Opens/restores the rollback journal used by a standalone editor playtest.
     * Editor playtests exist only on an integrated server (singleplayer or LAN),
     * never on a dedicated server.
     */
    public static void onEditorPlaytest(ServerPlayer player, FnfPayloads.EditorPlaytestC2S payload) {
        if (player.getServer() == null || player.getServer().isDedicatedServer()) return;
        Session previous = EDITOR_PLAYTESTS.remove(player.getUUID());
        if (previous != null) {
            clearSessionActors(previous);
            restoreWorld(previous);
            restorePosition(player);
        }

        if (payload.action() == FnfPayloads.EditorPlaytestC2S.END) {
            PacketDistributor.sendToPlayer(player, new FnfPayloads.RollbackCompleteS2C(payload.pos()));
            return;
        }
        if (payload.action() != FnfPayloads.EditorPlaytestC2S.BEGIN
                && payload.action() != FnfPayloads.EditorPlaytestC2S.RESET) return;

        Session session = new Session();
        session.key = keyOf(player, payload.pos());
        session.host = player;
        session.state = State.PLAYING;
        session.playSide = 0;
        capturePlayerState(player);
        // Give the playtest the same solo opponent a real song gets. Spawned before the
        // command targets so it also receives the <opponent> tag, and its entity id is sent
        // to the client, which otherwise has no stand to place the opponent character on.
        spawnBotStand(session, payload.pos());
        prepareCommandTargets(session, payload.pos());
        EDITOR_PLAYTESTS.put(player.getUUID(), session);
        int botEntityId = session.botStand == null ? -1 : session.botStand.getId();
        if (payload.action() == FnfPayloads.EditorPlaytestC2S.RESET) {
            PacketDistributor.sendToPlayer(player,
                    new FnfPayloads.RestartSongS2C(payload.pos(), botEntityId, 0));
        } else {
            PacketDistributor.sendToPlayer(player,
                    new FnfPayloads.EditorBotS2C(payload.pos(), botEntityId));
        }
    }

    private static CharacterTransform transformFrom(FnfPayloads.ReadyC2S payload) {
        Vec3 offset = new Vec3(payload.offsetX(), payload.offsetY(), payload.offsetZ());
        if (!Double.isFinite(offset.x) || !Double.isFinite(offset.y) || !Double.isFinite(offset.z)
                || !Float.isFinite(payload.rotationOffset())) {
            return CharacterTransform.DEFAULT;
        }
        return new CharacterTransform(offset, payload.rotationOffset());
    }

    /**
     * Puts a participant on the stage: 2 blocks in front of the machine (its
     * FACING direction), player side to the camera's right, opponent to the
     * left, both facing away from the machine (toward the camera).
     */
    private static void placeOnStage(ServerPlayer player, BlockPos machinePos, byte playSide,
                                     CharacterTransform transform) {
        capturePlayerState(player);

        Direction facing = Direction.NORTH;
        BlockState state = player.serverLevel().getBlockState(machinePos);
        if (state.hasProperty(FunkinMachineBlock.FACING)) {
            facing = state.getValue(FunkinMachineBlock.FACING);
        }
        // camera looks back toward the machine; its screen-right is facing.getCounterClockWise()
        Direction right = facing.getCounterClockWise();
        Vec3 posOffset = transform.positionOffset();
        // BOTH controls both chart sides, but visually occupies the normal
        // player slot. It is not a third/center stage role.
        double side = playSide == 1 ? -1.5 : 1.5;
        double x = machinePos.getX() + 0.5 + facing.getStepX() * 2.0
                + right.getStepX() * side + posOffset.x;
        double y = machinePos.getY() + posOffset.y;
        double z = machinePos.getZ() + 0.5 + facing.getStepZ() * 2.0
                + right.getStepZ() * side + posOffset.z;
        // character rotation is an offset from the stage's existing default angle
        float yaw = facing.toYRot() + transform.rotationOffset();
        player.teleportTo(player.serverLevel(), x, y, z, yaw, 0f);
        player.setYBodyRot(yaw);
        player.setYHeadRot(yaw);
        // non-interactive while playing (no incoming damage); restored on session end
        INVULN_BEFORE.putIfAbsent(player.getUUID(), player.isInvulnerable());
        player.setInvulnerable(true);
    }

    private static void capturePlayerState(ServerPlayer player) {
        PLAYER_STATE_BEFORE.putIfAbsent(player.getUUID(), player.saveWithoutId(new CompoundTag()));
        RETURN_POINTS.putIfAbsent(player.getUUID(), new ReturnPoint(
                player.getX(), player.getY(), player.getZ(), player.getYRot(), player.getXRot()));
        INVULN_BEFORE.putIfAbsent(player.getUUID(), player.isInvulnerable());
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
        double botSide = session.playSide == 1 ? 1.5 : -1.5;
        double x = machinePos.getX() + 0.5 + facing.getStepX() * 2.0 + right.getStepX() * botSide;
        double z = machinePos.getZ() + 0.5 + facing.getStepZ() * 2.0 + right.getStepZ() * botSide;
        // face toward the camera but angled slightly to the side the player is on
        float yaw = facing.toYRot() + (session.playSide == 1 ? -20f : 20f);

        ArmorStand stand = new ArmorStand(level, x, machinePos.getY(), z);
        stand.setYRot(yaw);
        stand.setYBodyRot(yaw);
        stand.setInvulnerable(true);
        // The solo opponent anchor is a real performer: let it land and fall
        // like the local player. Lua may still toggle this through dad.grav.
        stand.setNoGravity(false);
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

    private static void prepareCommandTargets(Session session, BlockPos machinePos) {
        String playerTag = CommandEventPlaceholders.tag(machinePos, "player");
        String opponentTag = CommandEventPlaceholders.tag(machinePos, "opponent");
        String speakersTag = CommandEventPlaceholders.tag(machinePos, "speakers");

        if (session.duet) {
            session.host.addTag(playerTag);
            if (session.guest != null) session.guest.addTag(opponentTag);
        } else if (session.playSide != 1) {
            session.host.addTag(playerTag);
            if (session.botStand != null) session.botStand.addTag(opponentTag);
        } else if (session.playSide == 1) {
            session.host.addTag(opponentTag);
            if (session.botStand != null) session.botStand.addTag(playerTag);
        }

        ServerLevel level = session.host.serverLevel();
        // Snapshot world time so a song's /time change reverts on exit, while natural
        // day progression during the song is preserved.
        session.startGameTime = level.getGameTime();
        session.startDayTime = level.getDayTime();
        session.startDaylight = level.getGameRules().getBoolean(net.minecraft.world.level.GameRules.RULE_DAYLIGHT);
        // Snapshot participant gamemodes so a song's /gamemode change reverts on exit.
        session.startGameModes.clear();
        captureGameMode(session, session.host);
        captureGameMode(session, session.guest);
        captureExitRollback(session.host, level);
        captureExitRollback(session.guest, level);
        ArmorStand marker = new ArmorStand(level,
                machinePos.getX() + 0.5, machinePos.getY() + 0.5, machinePos.getZ() + 0.5);
        marker.setInvisible(true);
        marker.setNoGravity(true);
        marker.setInvulnerable(true);
        marker.setNoBasePlate(true);
        marker.addTag(speakersTag);
        if (level.addFreshEntity(marker)) session.speakersMarker = marker;
    }

    private static void clearCommandTargets(Session session) {
        if (session == null) return;
        BlockPos pos = session.key.pos();
        for (ServerPlayer participant : new ServerPlayer[]{session.host, session.guest}) {
            if (participant == null) continue;
            participant.removeTag(CommandEventPlaceholders.tag(pos, "player"));
            participant.removeTag(CommandEventPlaceholders.tag(pos, "opponent"));
            participant.removeTag(CommandEventPlaceholders.tag(pos, "speakers"));
        }
        if (session.speakersMarker != null) {
            session.speakersMarker.discard();
            session.speakersMarker = null;
        }
    }

    private static void clearSessionActors(Session session) {
        clearCommandTargets(session);
        removeBotStand(session);
    }

    public static void onCommandEvent(ServerPlayer player, FnfPayloads.CommandEventC2S payload) {
        Session session = SESSIONS.get(keyOf(player, payload.pos()));
        if (session == null || session.state != State.PLAYING
                || (session.host != player && session.guest != player)) return;

        SongEntry entry = SongLibrary.get(session.songId);
        if (entry == null) return;

        try {
            SongChart chart = SongLibrary.loadChart(entry, session.difficulty);
            if (payload.eventIndex() < 0 || payload.eventIndex() >= chart.events.size()) return;
            SongChart.Event event = chart.events.get(payload.eventIndex());
            if (!ChartEventTypes.isMinecraftCommand(event.name)) {
                FnfMod.LOGGER.warn("Rejected unlisted server command event from {} for song {}",
                        player.getGameProfile().getName(), session.songId);
                return;
            }
            if (!session.executedServerEvents.add(payload.eventIndex())) return;

            BlockState machineState = player.serverLevel().getBlockState(payload.pos());
            Direction machineFacing = machineState.hasProperty(FunkinMachineBlock.FACING)
                    ? machineState.getValue(FunkinMachineBlock.FACING) : Direction.NORTH;
            String command = CommandEventPlaceholders.expand(
                    event.value1, payload.pos(), machineFacing,
                    false, playerRoleIsHuman(session), opponentRoleIsHuman(session)).trim();
            while (command.startsWith("/")) command = command.substring(1).trim();
            if (command.isEmpty() || player.getServer() == null) return;
            String trackedCommand = command;
            boolean serverRunner = "server".equalsIgnoreCase(event.value2.trim());
            runTrackedCommand(session, () -> player.getServer().getCommands().performPrefixedCommand(
                    serverRunner
                            ? player.getServer().createCommandSourceStack()
                                    .withLevel(player.serverLevel())
                                    .withPosition(Vec3.atCenterOf(payload.pos()))
                            : player.createCommandSourceStack(),
                    trackedCommand));
        } catch (Exception e) {
            FnfMod.LOGGER.warn("Could not run server command event for {}: {}",
                    session.songId, e.toString());
        }
    }

    public static void onLuaCommand(ServerPlayer player, FnfPayloads.LuaCommandC2S payload) {
        Session session = SESSIONS.get(keyOf(player, payload.pos()));
        boolean editorPlaytest = false;
        if (session == null) {
            session = EDITOR_PLAYTESTS.get(player.getUUID());
            editorPlaytest = session != null;
        }
        // Host-only prevents duet clients from executing same script command twice.
        if (session == null || session.state != State.PLAYING || session.host != player) return;
        var server = player.getServer();
        if (server == null) return;
        boolean serverRunner = "server".equalsIgnoreCase(payload.runner().trim());
        // Lua is client-provided. Never grant arbitrary server-source commands to
        // untrusted dedicated-server players. Player-run commands retain normal permissions.
        if (serverRunner && !player.hasPermissions(2)
                && !server.isSingleplayerOwner(player.getGameProfile())) {
            player.sendSystemMessage(Component.literal(
                    "Blockified Lua: server commands require operator permission"));
            return;
        }
        // Keep the network safety limit for ordinary sessions. An editor
        // playtest is local-only and may replay many legitimate command events
        // in one render frame while catching up from time zero.
        if (!editorPlaytest) {
            long now = System.nanoTime();
            if (now - session.luaCommandWindowNanos >= 1_000_000_000L) {
                session.luaCommandWindowNanos = now;
                session.luaCommandsInWindow = 0;
            }
            if (++session.luaCommandsInWindow > 100) return;
        }

        try {
            BlockState machineState = player.serverLevel().getBlockState(payload.pos());
            Direction machineFacing = machineState.hasProperty(FunkinMachineBlock.FACING)
                    ? machineState.getValue(FunkinMachineBlock.FACING) : Direction.NORTH;
            // A playtest now spawns and tags the same performers a real song does, so its
            // selectors resolve exactly like gameplay. Collapsing them to @s (which this used
            // to do, back when a playtest had no tagged entities) sent every <opponent> and
            // <speakers> command at the person testing instead.
            String command = CommandEventPlaceholders.expand(
                    payload.command(), payload.pos(), machineFacing,
                    false, playerRoleIsHuman(session), opponentRoleIsHuman(session)).trim();
            while (command.startsWith("/")) command = command.substring(1).trim();
            if (command.isEmpty()) return;
            String trackedCommand = command;
            runTrackedCommand(session, () -> server.getCommands().performPrefixedCommand(
                    serverRunner
                            ? server.createCommandSourceStack()
                                    .withLevel(player.serverLevel())
                                    .withPosition(Vec3.atCenterOf(payload.pos()))
                            : player.createCommandSourceStack(),
                    trackedCommand));
        } catch (Exception error) {
            FnfMod.LOGGER.warn("Could not run Lua server command for {}: {}",
                    session.songId, error.toString());
        }
    }

    public static void onNoteEvent(ServerPlayer player, FnfPayloads.NoteEventC2S payload) {
        Session session = SESSIONS.get(keyOf(player, payload.pos()));
        if (session == null || session.state != State.PLAYING
                || player != session.host && player != session.guest) return;
        ServerPlayer other = session.host == player ? session.guest : session.host;
        if (other != null) {
            PacketDistributor.sendToPlayer(other, new FnfPayloads.PartnerNoteS2C(
                    payload.lane(), payload.judgement(), payload.combo(), payload.score()));
        }
        if (session.nearbySessionId != null && payload.lane() >= 0 && payload.lane() < 4) {
            Vec3 source = Vec3.atCenterOf(session.key.pos());
            for (ServerPlayer listener : player.getServer().getPlayerList().getPlayers()) {
                if (listener == session.host || listener == session.guest
                        || !listener.level().dimension().equals(session.key.dim())
                        || listener.position().distanceToSqr(source) > 64.0 * 64.0) continue;
                PacketDistributor.sendToPlayer(listener, new FnfPayloads.NearbyNoteS2C(
                        session.nearbySessionId, player.getUUID(), payload.lane(), payload.judgement() >= 4));
            }
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
            clearSessionActors(session);
            restoreWorld(session);
            releaseMenuStageTicket(session);
            SESSIONS.remove(session.key);
        }
    }

    public static void onReloadRequest(ServerPlayer player, FnfPayloads.ReloadC2S payload) {
        boolean dedicated = player.getServer() != null && player.getServer().isDedicatedServer();
        boolean rescanned = false;
        // ops can always rescan; on integrated/LAN servers anyone may (it's the host's folder)
        if (player.hasPermissions(2) || !dedicated) {
            boolean sameProcessScan = payload != null
                    && payload.processNonce() == SongLibrary.processNonce()
                    && payload.generation() == SongLibrary.rescanGeneration();
            if (!sameProcessScan) SongLibrary.rescan();
            rescanned = true;
        }

        // if this player has the song menu open, push them the fresh list
        for (Session s : SESSIONS.values()) {
            if (s.host == player && s.state == State.CHOOSING) {
                PacketDistributor.sendToPlayer(player, new FnfPayloads.OpenMenuS2C(
                        s.key.pos(), (byte) 0, player.getGameProfile().getName(), availableSongs()));
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

    public static void onLeave(ServerPlayer player, BlockPos pos, boolean finishedOnly, byte requestedReturnTarget) {
        long startedAt = System.nanoTime();
        try {
            completeLeave(player, pos, finishedOnly, requestedReturnTarget);
        } finally {
            long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L;
            if (elapsedMs >= 1000) FnfMod.LOGGER.warn(
                    "Song leave/rollback took {} ms for {} (target {})",
                    elapsedMs, player.getGameProfile().getName(), requestedReturnTarget);
        }
    }

    private static void completeLeave(ServerPlayer player, BlockPos pos,
                                      boolean finishedOnly, byte requestedReturnTarget) {
        MenuReturnRoute route = MENU_RETURN_ROUTES.get(player.getUUID());
        if (route != null && route.stage().pos().equals(pos)) MENU_RETURN_ROUTES.remove(player.getUUID());
        else route = null;
        boolean chartEditorTransition = requestedReturnTarget == FnfPayloads.LeaveC2S.RETURN_CHART_EDITOR;
        Session session = findParticipantSession(player, pos);
        if (canDetachWaitingGuest(session, player)) {
            detachWaitingGuest(session, player);
            restorePosition(player);
        } else if (canDetachDuetParticipant(session, player)) {
            detachDuetParticipant(session, player);
            // World changes belong to the still-playing participant. Restore only
            // this player's inventory, gamemode, health and stage position now.
            restorePosition(player, false);
        } else if (session != null && (session.host == player || session.guest == player)) {
            // finishedOnly is only a hint that SongEnd should have removed the
            // session already. If it did not, leaving it PLAYING makes onInteract
            // refuse to send OpenMenuS2C and strands the client on its return screen.
            if (finishedOnly) FnfMod.LOGGER.warn(
                    "SongEnd left an active session for {}; cleaning it up during leave",
                    player.getGameProfile().getName());
            cancel(session, player, player.getGameProfile().getName() + " left");
        } else {
            restorePosition(player);
        }
        if (chartEditorTransition) {
            // restoreWorld/restorePosition above are synchronous. Sending this last
            // guarantees the client cannot expose the editor before rollback ends.
            PacketDistributor.sendToPlayer(player, new FnfPayloads.RollbackCompleteS2C(pos));
            return;
        }
        // With the old session torn down, recreate only the requested chooser.
        // Custom-menu routing revalidates machine reach, profile, world scope,
        // and LAN policy before sending any executable menu content.
        byte returnTarget = FnfPayloads.LeaveC2S.normalizeReturnTarget(requestedReturnTarget);
        BlockPos menuPos = route != null && player.level().dimension().equals(route.menu().dim())
                ? route.menu().pos() : pos;
        if (returnTarget == FnfPayloads.LeaveC2S.RETURN_SELECTOR) {
            onInteract(player, menuPos);
        } else if (returnTarget == FnfPayloads.LeaveC2S.RETURN_MACHINE_MENU) {
            MachineMenuService.onInteract(player, menuPos);
        }
    }

    private static boolean canDetachWaitingGuest(Session session, ServerPlayer leaver) {
        return session != null && session.duet && session.guest == leaver && session.host != null
                && session.host != leaver && (session.state == State.WAITING_GUEST
                || session.state == State.PREPARING) && !session.beginSongSent;
    }

    /** A guest missing files may leave/download/rejoin without cancelling the chooser's session. */
    private static void detachWaitingGuest(Session session, ServerPlayer leaver) {
        if (!canDetachWaitingGuest(session, leaver)) return;
        BlockPos pos = session.key.pos();
        session.autoJoinSuppressedGuests.add(leaver.getUUID());
        leaver.removeTag(CommandEventPlaceholders.tag(pos, "player"));
        leaver.removeTag(CommandEventPlaceholders.tag(pos, "opponent"));
        session.guest = null;
        session.guestReady = false;
        session.guestGameplayReady = false;
        session.guestEnded = false;
        session.state = State.WAITING_GUEST;
        MENU_RETURN_ROUTES.remove(leaver.getUUID());
        if (!session.host.hasDisconnected()) {
            PacketDistributor.sendToPlayer(session.host, new FnfPayloads.SessionStateS2C(
                    pos, (byte) 0, session.songId, session.difficulty,
                    "Player left before loading; waiting for another player"));
        }
    }

    private static boolean canDetachDuetParticipant(Session session, ServerPlayer leaver) {
        if (session == null || !session.duet || session.state != State.PLAYING
                || !session.beginSongSent) return false;
        ServerPlayer survivor = session.host == leaver ? session.guest
                : session.guest == leaver ? session.host : null;
        if (survivor == null || survivor.hasDisconnected()) return false;
        return survivor == session.host ? !session.hostEnded : !session.guestEnded;
    }

    /** Preserve the shared song/rollback transaction while one human continues solo. */
    private static void detachDuetParticipant(Session session, ServerPlayer leaver) {
        ServerPlayer survivor = session.host == leaver ? session.guest : session.host;
        boolean guestSurvives = survivor == session.guest;
        BlockPos pos = session.key.pos();
        leaver.removeTag(CommandEventPlaceholders.tag(pos, "player"));
        leaver.removeTag(CommandEventPlaceholders.tag(pos, "opponent"));
        session.startGameModes.remove(leaver.getUUID());
        MENU_RETURN_ROUTES.remove(leaver.getUUID());

        if (guestSurvives) {
            // The guest owns the remaining clock and server-side Lua commands.
            session.host = survivor;
            session.hostEnded = session.guestEnded;
            session.hostAnimSet = session.guestAnimSet;
            session.hostAnimType = session.guestAnimType;
            session.hostAnimFlipped = session.guestAnimFlipped;
            session.hostDirectionsFlipped = session.guestDirectionsFlipped;
            session.hostFrameCenterCamera = session.guestFrameCenterCamera;
            session.hostTransform = session.guestTransform;
            session.playSide = 1;
        } else {
            session.playSide = 0;
        }
        session.guest = null;
        session.guestEnded = false;
        session.duet = false;
        spawnBotStand(session, pos);
        if (session.botStand != null) {
            session.botStand.addTag(CommandEventPlaceholders.tag(pos,
                    guestSurvives ? "player" : "opponent"));
        }
        PacketDistributor.sendToPlayer(survivor, new FnfPayloads.PartnerLeftS2C(
                pos, session.botStand == null ? -1 : session.botStand.getId(),
                leaver.getGameProfile().getName()));
    }

    /**
     * Normally the machine position and player's current dimension address the
     * session directly. Fall back to participant identity so a song command that
     * changed dimension cannot strand its world rollback transaction.
     */
    private static Session findParticipantSession(ServerPlayer player, BlockPos pos) {
        Session direct = SESSIONS.get(keyOf(player, pos));
        if (direct != null) return direct;
        for (Session candidate : SESSIONS.values()) {
            if ((candidate.host == player || candidate.guest == player)
                    && candidate.key.pos().equals(pos)) return candidate;
        }
        return null;
    }

    /** Teleports the player back to where they stood before the song, if recorded. */
    private static void restorePosition(ServerPlayer player) {
        restorePosition(player, true);
    }

    private static void restorePosition(ServerPlayer player, boolean restoreWorldTime) {
        CompoundTag playerState = PLAYER_STATE_BEFORE.remove(player.getUUID());
        if (playerState != null) {
            try {
                player.load(playerState);
            } catch (Exception e) {
                FnfMod.LOGGER.warn("Could not restore player state of {}: {}",
                        player.getGameProfile().getName(), e.toString());
            }
        }
        // Must happen after player.load(): its saved abilities/state can otherwise
        // make a correct earlier setGameMode look as though it never restored.
        net.minecraft.world.level.GameType gameMode = PLAYER_GAME_MODE_BEFORE.remove(player.getUUID());
        if (gameMode != null && player.gameMode.getGameModeForPlayer() != gameMode) {
            player.setGameMode(gameMode);
        }
        if (restoreWorldTime) restoreExitWorldTime(player);
        else WORLD_TIME_BEFORE.remove(player.getUUID());
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
        VANILLA_HUD_TARGETS.remove(player.getUUID());
        FoodBefore food = FOOD_BEFORE.remove(player.getUUID());
        if (food != null) {
            try {
                player.getFoodData().setFoodLevel(food.level());
                player.getFoodData().setSaturation(food.saturation());
                player.getFoodData().setExhaustion(food.exhaustion());
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

    /** Vanilla HUD: lock real health and food to Blockified without ever killing the player. */
    public static void onSyncVanillaHud(ServerPlayer player, float health, int foodLevel) {
        // only within an active session (return point recorded at song start)
        if (!RETURN_POINTS.containsKey(player.getUUID())) return;
        HEALTH_BEFORE.putIfAbsent(player.getUUID(), player.getHealth());
        FOOD_BEFORE.putIfAbsent(player.getUUID(), new FoodBefore(
                player.getFoodData().getFoodLevel(), player.getFoodData().getSaturationLevel(),
                player.getFoodData().getExhaustionLevel()));
        VanillaHudTarget target = new VanillaHudTarget(
                Math.max(1f, Math.min(player.getMaxHealth(), health)),
                Math.max(0, Math.min(20, foodLevel)));
        VANILLA_HUD_TARGETS.put(player.getUUID(), target);
        applyVanillaHudTarget(player, target);
    }

    private static void applyVanillaHudTarget(ServerPlayer player, VanillaHudTarget target) {
        player.setHealth(Math.max(1f, Math.min(player.getMaxHealth(), target.health())));
        player.getFoodData().setFoodLevel(target.foodLevel());
        // Keep vanilla exhaustion/regeneration from changing Blockified's display.
        player.getFoodData().setSaturation(0);
        player.getFoodData().setExhaustion(0);
    }

    private static void cancel(Session session, ServerPlayer leaver, String reason) {
        if (session == null) return;
        clearSessionActors(session);
        restoreWorld(session);
        releaseMenuStageTicket(session);
        SESSIONS.remove(session.key);
        for (ServerPlayer p : new ServerPlayer[]{session.host, session.guest}) {
            if (p == null) continue;
            if (p != leaver && !p.hasDisconnected()) {
                PacketDistributor.sendToPlayer(p, new FnfPayloads.SessionCancelS2C(session.key.pos(), reason));
            }
            restorePosition(p);
        }
    }

    private static void runTrackedCommand(Session session, Runnable command) {
        Session previous = activeMutationSession;
        activeMutationSession = session;
        try {
            command.run();
        } finally {
            activeMutationSession = previous;
        }
    }

    /** Called by LevelMutationMixin before setBlock mutates server state. */
    public static void captureBlockBeforeMutation(Level level, BlockPos pos) {
        Session session = activeMutationSession;
        if (session == null || restoringWorld || level.isClientSide()) return;
        captureBlock(session, level, pos);
    }

    /** Called by WeatherMutationMixin before /weather changes a server dimension. */
    public static void captureWeatherBeforeMutation(ServerLevel level) {
        Session session = activeMutationSession;
        if (session == null || restoringWorld || level == null) return;
        session.changedWeather.computeIfAbsent(level.dimension(), ignored -> WeatherRollback.capture(level));
    }

    /** Authorizes and snapshots a chunk-loader property changed by gameplay Lua. */
    public static boolean captureLuaPointMutation(ServerPlayer player, BlockPos machinePos,
                                                  ServerLevel level, BlockPos pointPos) {
        Session session = findParticipantSession(player, machinePos);
        if (session == null) session = EDITOR_PLAYTESTS.get(player.getUUID());
        if (session == null || session.state != State.PLAYING
                || (session.host != player && session.guest != player)
                || level != player.serverLevel()) return false;
        captureBlock(session, level, pointPos);
        return true;
    }

    private static void captureBlock(Session session, Level level, BlockPos pos) {
        if (session == null || restoringWorld || level.isClientSide()) return;
        WorldBlockKey key = new WorldBlockKey(level.dimension(), pos.immutable());
        if (session.changedBlocks.containsKey(key)) return;
        BlockEntity entity = level.getBlockEntity(pos);
        CompoundTag entityTag = entity == null ? null : entity.saveWithFullMetadata(level.registryAccess());
        session.changedBlocks.put(key, new BlockSnapshot(level.getBlockState(pos), entityTag));
    }

    private static void captureGameMode(Session session, ServerPlayer player) {
        if (player != null) {
            session.startGameModes.put(player.getUUID(), player.gameMode.getGameModeForPlayer());
        }
    }

    private static void captureExitRollback(ServerPlayer player, ServerLevel level) {
        if (player == null) return;
        PLAYER_GAME_MODE_BEFORE.putIfAbsent(player.getUUID(), player.gameMode.getGameModeForPlayer());
        WORLD_TIME_BEFORE.putIfAbsent(player.getUUID(), new WorldTimeBefore(
                level.dimension(), level.getGameTime(), level.getDayTime(),
                level.getGameRules().getBoolean(net.minecraft.world.level.GameRules.RULE_DAYLIGHT)));
    }

    private static void restoreExitWorldTime(ServerPlayer player) {
        WorldTimeBefore before = WORLD_TIME_BEFORE.remove(player.getUUID());
        if (before == null || player.getServer() == null) return;
        ServerLevel level = player.getServer().getLevel(before.dimension());
        if (level == null) return;
        long elapsed = Math.max(0, level.getGameTime() - before.gameTime());
        level.setDayTime(before.dayTime() + (before.daylight() ? elapsed : 0));
    }

    /**
     * Whether the {@code player}/{@code opponent} command role maps to a real player this
     * session (see {@link #prepareCommandTargets}). The human sides get an {@code @a}
     * selector so player-only commands like {@code /gamemode} target them; the bot side is
     * an armor stand and keeps {@code @e}. Mirrors the tag assignment exactly.
     */
    private static boolean playerRoleIsHuman(Session session) {
        // player tag: host in duet/normal solo; the bot stand when the human plays side 1.
        return session.duet || session.playSide != 1;
    }

    private static boolean opponentRoleIsHuman(Session session) {
        // opponent tag: the duet guest, or the host when the human plays the opponent side.
        return session.duet ? session.guest != null : session.playSide == 1;
    }

    private static void restoreWorld(Session session) {
        // Undo any /time change: set the day time back to what natural progression
        // would have reached, so only the command's jump is removed. No-op if the
        // song never touched time.
        if (session.startGameTime != null && session.host != null && session.host.getServer() != null) {
            ServerLevel level = session.host.getServer().getLevel(session.key.dim());
            if (level != null) {
                long elapsed = Math.max(0, level.getGameTime() - session.startGameTime);
                level.setDayTime(session.startDayTime + (session.startDaylight ? elapsed : 0));
            }
            session.startGameTime = null;
        }
        // Undo any /gamemode change a song command applied to a participant, so
        // players leave gameplay in the mode they entered with.
        if (!session.startGameModes.isEmpty() && session.host != null && session.host.getServer() != null) {
            var playerList = session.host.getServer().getPlayerList();
            for (var entry : session.startGameModes.entrySet()) {
                ServerPlayer player = playerList.getPlayer(entry.getKey());
                if (player != null && player.gameMode.getGameModeForPlayer() != entry.getValue()) {
                    player.setGameMode(entry.getValue());
                }
            }
            session.startGameModes.clear();
        }
        // Weather is lazy-snapshotted by the setWeatherParameters mixin, so a song
        // that never calls /weather cannot rewind natural rain/thunder progression.
        if (!session.changedWeather.isEmpty() && session.host != null && session.host.getServer() != null) {
            for (var entry : session.changedWeather.entrySet()) {
                ServerLevel level = session.host.getServer().getLevel(entry.getKey());
                if (level != null) WeatherRollback.restore(level, entry.getValue());
            }
            session.changedWeather.clear();
        }
        if (session.changedBlocks.isEmpty() || session.host.getServer() == null) return;
        restoringWorld = true;
        try {
            for (var entry : session.changedBlocks.entrySet()) {
                ServerLevel level = session.host.getServer().getLevel(entry.getKey().dimension());
                if (level == null) continue;
                BlockPos pos = entry.getKey().pos();
                BlockSnapshot snapshot = entry.getValue();
                BlockEntity before = level.getBlockEntity(pos);
                int replacedPointRadius = before instanceof com.fnfmod.block.ChunkLoaderPointBlockEntity point
                        ? point.radius() : 0;
                boolean replacedPointEnabled = before instanceof com.fnfmod.block.ChunkLoaderPointBlockEntity point
                        && point.enabled();
                level.setBlock(pos, snapshot.state(), 3);
                if (snapshot.blockEntity() != null) {
                    BlockEntity entity = level.getBlockEntity(pos);
                    if (entity != null) {
                        entity.loadWithComponents(snapshot.blockEntity(), level.registryAccess());
                        entity.setChanged();
                        if (entity instanceof com.fnfmod.block.ChunkLoaderPointBlockEntity point) {
                            point.reconcileAfterRollback(replacedPointRadius, replacedPointEnabled);
                        }
                    }
                }
            }
        } catch (Exception e) {
            FnfMod.LOGGER.error("Could not fully restore song world changes", e);
        } finally {
            restoringWorld = false;
            session.changedBlocks.clear();
        }
    }

    public static List<FnfPayloads.SongInfo> availableSongs() {
        List<FnfPayloads.SongInfo> out = new ArrayList<>();
        for (SongEntry e : SongLibrary.getSongs().values()) {
            String iconPath = e.opponentIconFile != null ? e.opponentIconFile.toAbsolutePath().toString() : "";
            out.add(new FnfPayloads.SongInfo(e.id, e.displayName, List.copyOf(e.difficulties), e.opponentIcon, iconPath));
        }
        return out;
    }

}
