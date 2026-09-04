package com.fnfmod.machine;

import com.fnfmod.FnfMod;
import com.fnfmod.block.FunkinMachineBlockEntity;
import com.fnfmod.block.MachineAnchorBlockEntity;
import com.fnfmod.net.FnfPayloads;
import com.fnfmod.world.ModContentScope;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
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
    private static final Gson PRETTY_JSON = new GsonBuilder().setPrettyPrinting().create();

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
            case 3 -> updateProfile(player, machine, payload.value());
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

    private static void updateProfile(ServerPlayer player, MachineDataHolder machine, String value) {
        try {
            JsonElement parsed = JsonParser.parseString(value == null ? "{}" : value);
            if (!parsed.isJsonObject()) throw new IllegalArgumentException("profile data must be an object");
            JsonObject input = parsed.getAsJsonObject();
            String requestedId = cleanText(input, "profileId", machine.profileId(), 128);
            MachineDefinition definition = MachineLibrary.find(requestedId).orElse(null);
            if (definition == null || definition.builtIn() || definition.root() == null) {
                result(player, false, "Built-in profiles cannot be edited. Use Save As first.",
                        machine.profileId(), false);
                return;
            }
            JsonObject output = new JsonObject();
            String localId = definition.id().substring(definition.id().indexOf(':') + 1);
            output.addProperty("id", localId);
            output.addProperty("displayName", cleanText(input, "displayName", definition.displayName(), 128));

            String menu = safeRelative(input, "menu", "menu.lua", ".lua");
            output.addProperty("menu", menu);
            String allTexture = safeRelative(input, "texture", "", ".png");
            if (!allTexture.isBlank()) output.addProperty("texture", allTexture);

            JsonObject textures = new JsonObject();
            if (input.has("textures") && input.get("textures").isJsonObject()) {
                for (Map.Entry<String, JsonElement> entry : input.getAsJsonObject("textures").entrySet()) {
                    String face = entry.getKey().toLowerCase(Locale.ROOT);
                    if (!isTextureFace(face) || !entry.getValue().isJsonPrimitive()) continue;
                    String path = safeRelativeValue(entry.getValue().getAsString(), ".png");
                    if (!path.isBlank()) textures.addProperty(face, path);
                }
            }
            if (!textures.isEmpty()) output.add("textures", textures);
            JsonObject behavior = input.has("behavior") && input.get("behavior").isJsonObject()
                    ? input.getAsJsonObject("behavior").deepCopy() : new JsonObject();
            if (behavior.size() > 64) throw new IllegalArgumentException("behavior supports at most 64 properties");
            output.add("behavior", behavior);

            Path file = definition.root().resolve("machine.json").normalize();
            if (!file.startsWith(definition.root().toAbsolutePath().normalize())) {
                throw new IllegalArgumentException("invalid profile path");
            }
            Files.writeString(file, PRETTY_JSON.toJson(output));
            MachineLibrary.rescan();
            MachineDefinition updated = MachineLibrary.find(definition.id()).orElse(null);
            if (updated == null) throw new IllegalArgumentException("saved profile could not be reloaded");
            machine.setProfileId(updated.id());
            result(player, true, "Saved profile fields for " + updated.displayName() + ".",
                    updated.id(), true);
        } catch (Exception error) {
            result(player, false, "Could not save profile: " + error.getMessage(),
                    machine.profileId(), false);
        }
    }

    private static String cleanText(JsonObject input, String key, String fallback, int max) {
        String value = input.has(key) && input.get(key).isJsonPrimitive()
                ? input.get(key).getAsString().trim() : fallback;
        if (value.isBlank()) value = fallback;
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static String safeRelative(JsonObject input, String key, String fallback, String extension) {
        String value = input.has(key) && input.get(key).isJsonPrimitive()
                ? input.get(key).getAsString() : fallback;
        return safeRelativeValue(value, extension);
    }

    private static String safeRelativeValue(String value, String extension) {
        if (value == null || value.isBlank()) return "";
        String normalized = value.trim().replace('\\', '/');
        Path relative = Path.of(normalized).normalize();
        if (relative.isAbsolute() || relative.startsWith("..") || normalized.length() > 256
                || extension != null && !normalized.toLowerCase(Locale.ROOT).endsWith(extension)) {
            throw new IllegalArgumentException("invalid relative " + extension + " path: " + value);
        }
        return relative.toString().replace('\\', '/');
    }

    private static boolean isTextureFace(String face) {
        return switch (face) {
            case "side", "front", "back", "left", "right", "top", "bottom" -> true;
            default -> false;
        };
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
