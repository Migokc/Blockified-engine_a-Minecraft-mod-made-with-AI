package com.fnfmod.song;

import com.fnfmod.FnfMod;
import com.fnfmod.world.ModContentScope;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/** Per-pack Minecraft-look permission for images and fonts, independent of source filters. */
public final class ModMinecraftVisualSettings {
    private static ModMinecraftVisualSettings live;
    private final ExternalDirectoryConfig config;
    private final Path installedModsRoot;
    private final Map<String, Boolean> overrides = new HashMap<>();

    public ModMinecraftVisualSettings(Path configRoot) {
        this(configRoot, SongLibrary.modsDir());
    }

    public ModMinecraftVisualSettings(Path configRoot, Path installedModsRoot) {
        Path root = configRoot.toAbsolutePath().normalize();
        config = new ExternalDirectoryConfig(root);
        this.installedModsRoot = installedModsRoot.toAbsolutePath().normalize();
        overrides.putAll(config.minecraftVisualOverrides());
        migrateOldFile(root.resolve("mod_minecraft_visuals.json"));
    }

    private void migrateOldFile(Path oldFile) {
        if (!Files.isRegularFile(oldFile)) return;
        try {
            JsonObject json = JsonParser.parseString(Files.readString(oldFile)).getAsJsonObject();
            Map<String, Boolean> migrated = new HashMap<>(overrides);
            for (var entry : json.entrySet()) {
                if (entry.getValue().isJsonPrimitive()
                        && entry.getValue().getAsJsonPrimitive().isBoolean()) {
                    migrated.putIfAbsent(entry.getKey(), entry.getValue().getAsBoolean());
                }
            }
            if (!migrated.equals(overrides)) config.setMinecraftVisualOverrides(migrated);
            overrides.clear();
            overrides.putAll(migrated);
            // Preserve the old file for recovery, but it is no longer an active setting.
            Files.move(oldFile, oldFile.resolveSibling("mod_minecraft_visuals.migrated-"
                    + System.currentTimeMillis() + ".bak"));
        } catch (Exception error) {
            FnfMod.LOGGER.warn("Could not migrate mod_minecraft_visuals.json: {}", error.toString());
        }
    }

    public static synchronized ModMinecraftVisualSettings live() {
        if (live == null) live = new ModMinecraftVisualSettings(SongLibrary.root());
        return live;
    }

    public synchronized boolean enabled(Path pack) {
        if (ModContentScope.isModWorld()) return true;
        return configuredEnabled(pack);
    }

    /** The user's saved/default choice, even while a mod world temporarily overrides it. */
    public synchronized boolean configuredEnabled(Path pack) {
        if (pack == null) return true;
        Path normalized = pack.toAbsolutePath().normalize();
        Boolean saved = overrides.get(normalized.toString());
        if (saved != null) return saved;
        // Honor a choice made with the older GameBanana-only button.
        Path oldChoice = normalized.resolve(".blockified-gamebanana-minecraft-art");
        if (Files.isRegularFile(oldChoice)) {
            try { return Boolean.parseBoolean(Files.readString(oldChoice).strip()); }
            catch (IOException ignored) { return false; }
        }
        // External dirs/mods are never trusted by default, even without a URL.
        // Only manually installed packs under config/fnfmod/mods start enabled.
        return normalized.startsWith(installedModsRoot)
                && !Files.isRegularFile(normalized.resolve("mod_url.txt"));
    }

    public synchronized void setEnabled(Path pack, boolean enabled) throws IOException {
        if (pack == null) throw new IOException("Select a mod first");
        Map<String, Boolean> updated = new HashMap<>(overrides);
        updated.put(pack.toAbsolutePath().normalize().toString(), enabled);
        config.setMinecraftVisualOverrides(updated);
        overrides.clear();
        overrides.putAll(updated);
    }
}
