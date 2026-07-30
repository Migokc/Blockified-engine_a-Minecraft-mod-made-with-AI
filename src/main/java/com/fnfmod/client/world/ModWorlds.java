package com.fnfmod.client.world;

import com.fnfmod.FnfMod;
import com.fnfmod.song.SongLibrary;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.worldselection.WorldOpenFlows;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.validation.DirectoryValidator;
import net.minecraft.world.level.validation.PathAllowList;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Minecraft worlds bundled inside FNF mod packs.
 *
 * <p>A pack may ship playable worlds under {@code config/fnfmod/mods/<mod>/worlds/<name>/}
 * (each a normal Minecraft save folder, i.e. containing {@code level.dat}). They
 * are listed by {@link com.fnfmod.client.gui.ModWorldSelectScreen} and loaded as
 * singleplayer worlds. The world is opened <b>in place</b> through a level storage
 * source rooted at the pack's {@code worlds/} folder, so progress saves straight
 * back into the mod folder and nothing is ever copied into Minecraft's saves.
 */
public final class ModWorlds {

    public record Entry(String mod, String name, Path source) {
        public String displayName() {
            return mod + " — " + name;
        }
    }

    private ModWorlds() {}

    /** Every bundled world across all installed mod packs, sorted by mod then name. */
    public static List<Entry> scan() {
        List<Entry> entries = new ArrayList<>();
        Path modsDir = SongLibrary.modsDir();
        if (!Files.isDirectory(modsDir)) return entries;
        try (Stream<Path> mods = Files.list(modsDir)) {
            mods.filter(Files::isDirectory).forEach(mod -> {
                Path worldsDir = mod.resolve("worlds");
                if (!Files.isDirectory(worldsDir)) return;
                try (Stream<Path> worlds = Files.list(worldsDir)) {
                    worlds.filter(ModWorlds::isWorld).forEach(world ->
                            entries.add(new Entry(mod.getFileName().toString(),
                                    world.getFileName().toString(), world)));
                } catch (IOException e) {
                    FnfMod.LOGGER.warn("Could not scan worlds in {}: {}", worldsDir, e.toString());
                }
            });
        } catch (IOException e) {
            FnfMod.LOGGER.warn("Could not scan mod worlds: {}", e.toString());
        }
        entries.sort(Comparator.comparing((Entry e) -> e.mod, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(e -> e.name, String.CASE_INSENSITIVE_ORDER));
        return entries;
    }

    private static boolean isWorld(Path path) {
        return Files.isDirectory(path) && Files.isRegularFile(path.resolve("level.dat"));
    }

    /**
     * Opens the bundled world in place: a level storage source rooted at the
     * pack's {@code worlds/} folder loads the world by its folder name, so the
     * integrated server reads and saves it right there in the mod folder.
     */
    public static void play(Minecraft minecraft, Entry entry) {
        Path worldsRoot = entry.source().getParent();
        if (worldsRoot == null) return;
        try {
            DirectoryValidator validator = new DirectoryValidator(new PathAllowList(List.of()));
            LevelStorageSource source = new LevelStorageSource(worldsRoot, worldsRoot,
                    validator, DataFixers.getDataFixer());
            WorldOpenFlows flows = new WorldOpenFlows(minecraft, source);
            flows.openWorld(entry.name(), () -> minecraft.setScreen(null));
        } catch (Exception e) {
            FnfMod.LOGGER.error("Failed to open bundled world {}", entry.displayName(), e);
        }
    }
}
