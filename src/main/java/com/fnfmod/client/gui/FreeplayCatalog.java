package com.fnfmod.client.gui;

import com.fnfmod.net.FnfPayloads;
import com.fnfmod.song.ModPackInfo;
import com.fnfmod.song.SongEntry;
import com.fnfmod.song.SongLibrary;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/** Local presentation groups for the optional mod/week Freeplay layout. Playback still uses SongInfo. */
final class FreeplayCatalog {
    record Mod(Path root, String name, List<FnfPayloads.SongInfo> songs) {}

    private FreeplayCatalog() {}

    static List<Mod> build(List<FnfPayloads.SongInfo> available) {
        List<Path> roots = SongLibrary.orderedContentRoots(SongLibrary.ExternalContent.CHARTS).stream()
                .map(path -> path.toAbsolutePath().normalize()).distinct().toList();
        Map<Path, LinkedHashSet<String>> ids = new LinkedHashMap<>();
        for (Path root : roots) ids.put(root, new LinkedHashSet<>());
        Path local = SongLibrary.songsDir().toAbsolutePath().normalize();
        ids.putIfAbsent(local, new LinkedHashSet<>());

        Map<String, SongEntry> library = SongLibrary.getSongs();
        for (FnfPayloads.SongInfo song : available) {
            SongEntry entry = library.get(song.id());
            if (entry == null) {
                entry = library.values().stream().filter(value -> value.id.equalsIgnoreCase(song.id()))
                        .findFirst().orElse(null);
            }
            LinkedHashSet<Path> owners = new LinkedHashSet<>();
            if (entry != null) {
                if (entry.isVslice()) {
                    for (SongEntry.VSliceVariation variation : entry.vsliceVariations.values()) {
                        Path owner = closestRoot(variation.chartFile, roots);
                        if (owner != null) owners.add(owner);
                    }
                } else {
                    Path owner = closestRoot(entry.modRoot != null ? entry.modRoot : entry.folder, roots);
                    if (owner != null) owners.add(owner);
                }
            }
            if (owners.isEmpty()) owners.add(local);
            for (Path owner : owners) ids.computeIfAbsent(owner, ignored -> new LinkedHashSet<>()).add(song.id());
        }

        List<Mod> result = new ArrayList<>();
        for (var group : ids.entrySet()) {
            if (group.getValue().isEmpty()) continue;
            List<FnfPayloads.SongInfo> groupSongs = available.stream()
                    .filter(song -> group.getValue().contains(song.id())).toList();
            if (!groupSongs.isEmpty()) result.add(new Mod(group.getKey(), label(group.getKey(), local), groupSongs));
        }
        return List.copyOf(result);
    }

    private static Path closestRoot(Path source, List<Path> roots) {
        if (source == null) return null;
        Path normalized = source.toAbsolutePath().normalize();
        Path best = null;
        for (Path root : roots) {
            if (normalized.startsWith(root) && (best == null || root.getNameCount() > best.getNameCount())) best = root;
        }
        return best;
    }

    private static String label(Path root, Path local) {
        if (root.equals(local)) return "Local songs";
        Path file = root.getFileName();
        if (file != null && file.toString().equalsIgnoreCase("assets")) {
            Path parent = root.getParent();
            return parent != null && parent.getFileName() != null
                    ? parent.getFileName() + "\\" + file : root.toString();
        }
        return ModPackInfo.read(root).name();
    }
}
