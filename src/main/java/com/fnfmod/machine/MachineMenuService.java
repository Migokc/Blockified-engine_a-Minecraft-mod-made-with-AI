package com.fnfmod.machine;

import com.fnfmod.FnfMod;
import com.fnfmod.block.FunkinMachineBlockEntity;
import com.fnfmod.net.FnfPayloads;
import com.fnfmod.session.SessionManager;
import com.fnfmod.song.SongEntry;
import com.fnfmod.song.SongLibrary;
import com.fnfmod.world.ModContentScope;
import com.fnfmod.world.ModWorldOptions;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Opens custom machine menus and validates their server-bound actions. */
public final class MachineMenuService {

    /** Virtual menu origins and persistent menuData for automatic world menus. */
    private static final Map<UUID, BlockPos> automaticOrigins = new HashMap<>();
    private static final Map<UUID, CompoundTag> automaticData = new HashMap<>();

    private MachineMenuService() {}

    public static void onInteract(ServerPlayer player, BlockPos pos) {
        if (isAutomatic(player, pos) && ModWorldOptions.autoOpenMenu()) {
            openAutomatic(player);
            return;
        }
        MachineDataHolder machine = machine(player, pos);
        MachineDefinition definition = machine == null ? null : MachineLibrary.find(machine.profileId()).orElse(null);
        if (definition == null || definition.builtIn() || definition.menuScript() == null
                || !Files.isRegularFile(definition.menuScript()) || !customMenusAllowed(player)) {
            SessionManager.onInteract(player, pos);
            return;
        }
        if (!SessionManager.prepareCustomMenu(player, pos)) {
            SessionManager.onInteract(player, pos);
            return;
        }
        PacketDistributor.sendToPlayer(player, new FnfPayloads.OpenMachineMenuS2C(
                pos, definition.id(), machine.machineTag(), machine.machineData().toString(),
                ModContentScope.activeMod().map(ModContentScope.ActiveMod::id).orElse(""),
                MachineLibrary.packVersion(), SessionManager.availableSongs(), false));
    }

    /** Opens the configured pack menu without requiring a physical machine block. */
    public static void openAutomatic(ServerPlayer player) {
        if (player == null || !customMenusAllowed(player) || !ModWorldOptions.autoOpenMenu()) return;
        MachineDefinition definition = MachineLibrary.find(ModWorldOptions.autoMenuProfile()).orElse(null);
        if (definition == null || definition.builtIn() || definition.menuScript() == null
                || !Files.isRegularFile(definition.menuScript())) {
            player.displayClientMessage(net.minecraft.network.chat.Component.literal(
                    "Automatic Lua menu profile is missing or invalid."), true);
            return;
        }
        BlockPos pos = automaticOrigins.get(player.getUUID());
        if (pos == null) {
            BlockPos base = player.blockPosition();
            for (int offset = 0; offset < 32; offset++) {
                BlockPos candidate = base.offset(offset, 0, 0);
                if (automaticOrigins.containsValue(candidate)) continue;
                if (SessionManager.prepareCustomMenu(player, candidate)) {
                    pos = candidate;
                    automaticOrigins.put(player.getUUID(), candidate);
                    break;
                }
            }
        } else if (!SessionManager.prepareCustomMenu(player, pos)) {
            return;
        }
        if (pos == null) return;
        CompoundTag data = automaticData.computeIfAbsent(player.getUUID(), ignored -> new CompoundTag());
        PacketDistributor.sendToPlayer(player, new FnfPayloads.OpenMachineMenuS2C(
                pos, definition.id(), "world", data.toString(),
                ModContentScope.activeMod().map(ModContentScope.ActiveMod::id).orElse(""),
                MachineLibrary.packVersion(), SessionManager.availableSongs(), true));
    }

    public static void handleAction(ServerPlayer player, FnfPayloads.MachineMenuActionC2S payload) {
        if (isAutomatic(player, payload.pos())) {
            if (!customMenusAllowed(player) || !ModWorldOptions.autoOpenMenu()) return;
            switch (payload.action()) {
                case 0 -> SessionManager.onInteract(player, payload.pos());
                case 1 -> saveAutomaticData(player, payload.value());
                default -> FnfMod.LOGGER.warn("Ignored unknown automatic menu action {}", payload.action());
            }
            return;
        }
        MachineDataHolder machine = machine(player, payload.pos());
        if (machine == null || !customMenusAllowed(player)) return;
        switch (payload.action()) {
            case 0 -> SessionManager.onInteract(player, payload.pos());
            case 1 -> saveData(machine, payload.value());
            default -> FnfMod.LOGGER.warn("Ignored unknown machine menu action {}", payload.action());
        }
    }

    public static void handleDirectPlay(ServerPlayer player, FnfPayloads.MachineDirectPlayC2S payload) {
        // Direct play is also used by the built-in Story Mode playlist between
        // songs. It still requires a reachable real/anchored machine and every
        // song/difficulty is validated below; only executable custom menus remain
        // restricted to integrated mod worlds.
        boolean automatic = isAutomatic(player, payload.pos());
        if (automatic && (!customMenusAllowed(player) || !ModWorldOptions.autoOpenMenu())) return;
        if (!automatic && machine(player, payload.pos()) == null) return;
        SongEntry song = SongLibrary.get(payload.songId());
        if (song == null) {
            player.displayClientMessage(net.minecraft.network.chat.Component.literal(
                    "Song not found: " + payload.songId()), true);
            return;
        }
        if (!song.difficulties.contains(payload.difficulty())) {
            player.displayClientMessage(net.minecraft.network.chat.Component.literal(
                    "Difficulty not found: " + payload.difficulty()), true);
            return;
        }
        if (!SessionManager.onDirectSelectSong(player, payload)) {
            player.displayClientMessage(net.minecraft.network.chat.Component.literal(
                    "Machine is busy or owned by another player."), true);
        }
    }

    /** A menu may target any named standalone anchor in its dimension, without block reach. */
    public static void handleTaggedPlay(ServerPlayer player, FnfPayloads.MachineTaggedPlayC2S payload) {
        if (!customMenusAllowed(player) || !SessionManager.ownsIdleMenu(player, payload.menuPos())) {
            taggedError(player, payload, "Open a custom menu before choosing a virtual machine.");
            return;
        }
        boolean automatic = isAutomatic(player, payload.menuPos());
        if (automatic ? !ModWorldOptions.autoOpenMenu() : machine(player, payload.menuPos()) == null) {
            taggedError(player, payload, "The menu's original machine is no longer available.");
            return;
        }
        String tag = VirtualMachineData.normalizeTag(payload.machineTag());
        if (tag.isEmpty()) {
            taggedError(player, payload, "Invalid virtual-machine tag.");
            return;
        }
        SongEntry song = SongLibrary.get(payload.songId());
        if (song == null || !song.difficulties.contains(payload.difficulty())) {
            taggedError(player, payload, song == null ? "Song not found: " + payload.songId()
                    : "Difficulty not found: " + payload.difficulty());
            return;
        }
        var level = player.serverLevel();
        var index = VirtualMachineData.get(level);
        var matches = index.find(tag);
        if (matches.size() != 1) {
            taggedError(player, payload, matches.isEmpty() ? "Virtual machine not found: " + tag
                    : "Several virtual machines use tag '" + tag + "'. Give each one a unique tag.");
            return;
        }
        BlockPos target = matches.getFirst();
        level.getChunkAt(target);
        if (!(level.getBlockEntity(target) instanceof com.fnfmod.block.MachineAnchorBlockEntity anchor)
                || !anchor.standalone() || !anchor.machineTag().equals(tag)) {
            // An external edit may have left an old index entry. Never launch at empty space.
            if (level.getBlockEntity(target) instanceof com.fnfmod.block.MachineAnchorBlockEntity current
                    && current.standalone()) index.put(target, current.machineTag());
            else index.remove(target);
            taggedError(player, payload, "Virtual machine was removed or renamed: " + tag);
            return;
        }
        if (!SessionManager.onTaggedSelectSong(player, payload.menuPos(),
                new FnfPayloads.MachineDirectPlayC2S(target, song.id, payload.difficulty(), payload.duet(),
                        payload.playSide(), payload.playbackMode()))) {
            taggedError(player, payload, "Virtual machine is busy, or the song could not be prepared: " + tag);
        }
    }

    private static void taggedError(ServerPlayer player, FnfPayloads.MachineTaggedPlayC2S payload, String message) {
        PacketDistributor.sendToPlayer(player, new FnfPayloads.MachineTaggedPlayResultS2C(
                payload.menuPos(), payload.menuPos(), (byte) 2, message));
    }

    private static void saveData(MachineDataHolder machine, String snbt) {
        if (snbt == null || snbt.length() > 32767) return;
        try {
            CompoundTag data = TagParser.parseTag(snbt);
            machine.setMachineData(data);
        } catch (Exception e) {
            FnfMod.LOGGER.warn("Rejected invalid machineData: {}", e.toString());
        }
    }

    private static void saveAutomaticData(ServerPlayer player, String snbt) {
        if (snbt == null || snbt.length() > 32767) return;
        try {
            automaticData.put(player.getUUID(), TagParser.parseTag(snbt));
        } catch (Exception e) {
            FnfMod.LOGGER.warn("Rejected invalid automatic machineData: {}", e.toString());
        }
    }

    private static boolean isAutomatic(ServerPlayer player, BlockPos pos) {
        return player != null && pos != null && pos.equals(automaticOrigins.get(player.getUUID()));
    }

    public static void clearPlayer(ServerPlayer player) {
        if (player == null) return;
        automaticOrigins.remove(player.getUUID());
        automaticData.remove(player.getUUID());
    }

    private static MachineDataHolder machine(ServerPlayer player, BlockPos pos) {
        if (pos == null || !MachineHitboxService.canReachAnchor(player, pos)) {
            return null;
        }
        if (player.level().getBlockEntity(pos) instanceof MachineDataHolder holder) return holder;
        return FunkinMachineBlockEntity.getOrCreate(player.level(), pos);
    }

    private static boolean customMenusAllowed(ServerPlayer player) {
        return player.getServer() != null && !player.getServer().isDedicatedServer()
                && ModContentScope.isModWorld();
    }
}
