package com.fnfmod.machine;

import com.fnfmod.FnfMod;
import com.fnfmod.block.FunkinMachineBlockEntity;
import com.fnfmod.block.MachineAnchorBlockEntity;
import com.fnfmod.net.FnfPayloads;
import com.fnfmod.world.ModContentScope;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.Optional;

/** Server-authoritative host-only machine editor operations. */
public final class MachineEditorService {

    private static final Map<UUID, String> copiedProfiles = new HashMap<>();

    private MachineEditorService() {}

    public static Optional<String> copiedProfile(ServerPlayer player) {
        return Optional.ofNullable(copiedProfiles.get(player.getUUID()));
    }

    public static void open(ServerPlayer player, BlockPos pos) {
        MachineDataHolder machine = machine(player, pos);
        if (machine == null) return;
        if (!canEdit(player)) {
            player.displayClientMessage(Component.literal(
                    "Funkin' Designer works only for the singleplayer/LAN host inside a bundled mod world."), true);
            return;
        }
        PacketDistributor.sendToPlayer(player,
                new FnfPayloads.OpenMachineEditorS2C(pos, machine.profileId()));
    }

    public static void copyOrApply(ServerPlayer player, BlockPos pos) {
        MachineDataHolder machine = machine(player, pos);
        if (machine == null || !canEdit(player)) return;
        String copied = copiedProfiles.get(player.getUUID());
        if (copied == null || copied.equals(machine.profileId())) {
            copiedProfiles.put(player.getUUID(), machine.profileId());
            player.displayClientMessage(Component.literal("Copied machine profile: " + machine.profileId()), true);
            return;
        }
        if (MachineLibrary.find(copied).isEmpty()) {
            copiedProfiles.remove(player.getUUID());
            player.displayClientMessage(Component.literal("Copied profile is no longer available."), true);
            return;
        }
        machine.setProfileId(copied);
        player.displayClientMessage(Component.literal("Applied machine profile: " + copied), true);
    }

    public static void clearCopied(ServerPlayer player) {
        copiedProfiles.remove(player.getUUID());
        player.displayClientMessage(Component.literal("Cleared copied machine profile."), true);
    }

    public static void handleEdit(ServerPlayer player, FnfPayloads.MachineEditC2S payload) {
        MachineDataHolder machine = machine(player, payload.pos());
        if (machine == null || !canEdit(player)) return;
        switch (payload.action()) {
            case 0 -> saveSelection(player, machine, payload.value());
            case 1 -> createProfile(player, machine, payload.value());
            case 2 -> reload(player, machine);
            default -> result(player, false, "Unknown editor action.", machine.profileId(), false);
        }
    }

    private static void saveSelection(ServerPlayer player, MachineDataHolder machine, String id) {
        MachineDefinition definition = MachineLibrary.find(id).orElse(null);
        if (definition == null) {
            result(player, false, "Profile is unavailable in this world.", machine.profileId(), false);
            return;
        }
        machine.setProfileId(definition.id());
        result(player, true, "Saved " + definition.displayName() + ".", definition.id(), false);
    }

    private static void createProfile(ServerPlayer player, MachineDataHolder machine, String requested) {
        String localId = requested == null ? "" : requested.trim().toLowerCase(Locale.ROOT);
        if (!localId.matches("[a-z0-9_.-]{1,64}")) {
            result(player, false, "Use 1-64 lowercase letters, numbers, '.', '_' or '-'.",
                    machine.profileId(), false);
            return;
        }
        ModContentScope.ActiveMod active = ModContentScope.activeMod().orElse(null);
        if (active == null) return;
        Path directory = ModContentScope.resolveActive("machines/" + localId).orElse(null);
        if (directory == null || Files.exists(directory)) {
            result(player, false, "Profile already exists or path is invalid.", machine.profileId(), false);
            return;
        }
        try {
            Files.createDirectories(directory.resolve("textures"));
            String display = title(localId);
            Files.writeString(directory.resolve("machine.json"), """
                    {
                      "id": "%s",
                      "displayName": "%s",
                      "menu": "menu.lua",
                      "texture": "textures/machine.png",
                      "behavior": {}
                    }
                    """.formatted(localId, display));
            Files.writeString(directory.resolve("menu.lua"), """
                    -- Blockified machine menu
                    local title = ui.label('title', '%s', 0.5, 0.16)
                    local play = ui.button('play', 'Choose Song', 0.5, 0.42, 180, 24)
                    local settings = ui.button('settings', 'Settings', 0.5, 0.54, 180, 24)

                    function play:onClick()
                        machine.openSongSelect()
                    end

                    function settings:onClick()
                        machine.openSettings()
                    end
                    """.formatted(display.replace("'", "\\'")));
            MachineLibrary.rescan();
            String id = MachineLibrary.canonical(active.id() + ":" + localId);
            machine.setProfileId(id);
            result(player, true, "Created " + id + ". Add textures/machine.png, then Reload.", id, true);
        } catch (Exception e) {
            FnfMod.LOGGER.error("Could not create machine profile {}", directory, e);
            result(player, false, "Could not create profile: " + e.getMessage(), machine.profileId(), false);
        }
    }

    private static void reload(ServerPlayer player, MachineDataHolder machine) {
        MachineLibrary.rescan();
        String id = MachineLibrary.find(machine.profileId()).isPresent()
                ? machine.profileId() : MachineDefinition.DEFAULT_ID;
        machine.setProfileId(id);
        result(player, true, "Machine files reloaded.", id, true);
    }

    private static MachineDataHolder machine(ServerPlayer player, BlockPos pos) {
        if (pos == null || !MachineHitboxService.canReachAnchor(player, pos)) return null;
        if (player.level().getBlockEntity(pos) instanceof MachineAnchorBlockEntity anchor) return anchor;
        return FunkinMachineBlockEntity.getOrCreate(player.level(), pos);
    }

    private static boolean canEdit(ServerPlayer player) {
        return MachineHitboxService.canEdit(player);
    }

    private static void result(ServerPlayer player, boolean success, String message,
                               String profileId, boolean refresh) {
        PacketDistributor.sendToPlayer(player,
                new FnfPayloads.MachineEditorResultS2C(success, message, profileId, refresh));
    }

    private static String title(String id) {
        String[] words = id.replace('-', ' ').replace('_', ' ').split(" +");
        StringBuilder out = new StringBuilder();
        for (String word : words) {
            if (word.isEmpty()) continue;
            if (!out.isEmpty()) out.append(' ');
            out.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return out.isEmpty() ? "Machine" : out.toString();
    }
}
