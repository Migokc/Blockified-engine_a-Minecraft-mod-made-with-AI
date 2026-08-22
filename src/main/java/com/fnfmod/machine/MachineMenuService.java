package com.fnfmod.machine;

import com.fnfmod.FnfMod;
import com.fnfmod.block.FunkinMachineBlockEntity;
import com.fnfmod.net.FnfPayloads;
import com.fnfmod.session.SessionManager;
import com.fnfmod.song.SongEntry;
import com.fnfmod.song.SongLibrary;
import com.fnfmod.world.ModContentScope;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

import java.nio.file.Files;

/** Opens custom machine menus and validates their server-bound actions. */
public final class MachineMenuService {

    private MachineMenuService() {}

    public static void onInteract(ServerPlayer player, BlockPos pos) {
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
                pos, definition.id(), machine.machineData().toString(),
                ModContentScope.activeMod().map(ModContentScope.ActiveMod::id).orElse(""),
                MachineLibrary.packVersion(), SessionManager.availableSongs()));
    }

    public static void handleAction(ServerPlayer player, FnfPayloads.MachineMenuActionC2S payload) {
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
        if (machine(player, payload.pos()) == null) return;
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

    private static void saveData(MachineDataHolder machine, String snbt) {
        if (snbt == null || snbt.length() > 32767) return;
        try {
            CompoundTag data = TagParser.parseTag(snbt);
            machine.setMachineData(data);
        } catch (Exception e) {
            FnfMod.LOGGER.warn("Rejected invalid machineData: {}", e.toString());
        }
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
