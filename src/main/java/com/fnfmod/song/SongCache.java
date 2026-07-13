package com.fnfmod.song;

import com.fnfmod.FnfMod;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Comparator;
import java.util.stream.Stream;

/** Filesystem operations for multiplayer-downloaded song data. */
public final class SongCache {
    private SongCache() {}

    public static long sizeBytes(Path cacheDirectory) {
        long[] total = {0};
        try (Stream<Path> files = Files.walk(cacheDirectory)) {
            files.filter(Files::isRegularFile).forEach(file -> {
                try {
                    total[0] += Files.size(file);
                } catch (IOException ignored) {}
            });
        } catch (IOException ignored) {}
        return total[0];
    }

    public static void clear(Path cacheDirectory) {
        deleteChildren(cacheDirectory);
        FnfMod.LOGGER.info("Cleared FNF song download cache");
    }

    /** Deletes cached songs that have not been used for the requested number of days. */
    public static void prune(Path cacheDirectory, int days) {
        long cutoff = System.currentTimeMillis() - Math.max(0, days) * 24L * 60 * 60 * 1000;
        try (Stream<Path> directories = Files.list(cacheDirectory)) {
            directories.filter(Files::isDirectory).forEach(directory -> {
                try {
                    if (Files.getLastModifiedTime(directory).toMillis() < cutoff) {
                        deleteTree(directory);
                        FnfMod.LOGGER.info("Pruned stale cached song {}", directory.getFileName());
                    }
                } catch (IOException ignored) {}
            });
        } catch (IOException ignored) {}
    }

    /** Refreshes an entry timestamp without allowing paths outside the cache root. */
    public static void touch(Path cacheDirectory, Path entryDirectory) {
        if (entryDirectory == null) return;
        Path root = cacheDirectory.toAbsolutePath().normalize();
        Path entry = entryDirectory.toAbsolutePath().normalize();
        try {
            if (Files.isDirectory(entry) && entry.startsWith(root)) {
                Files.setLastModifiedTime(entry, FileTime.fromMillis(System.currentTimeMillis()));
            }
        } catch (IOException ignored) {}
    }

    private static void deleteChildren(Path root) {
        if (!Files.isDirectory(root)) return;
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).filter(path -> !path.equals(root)).forEach(SongCache::delete);
        } catch (IOException ignored) {}
    }

    private static void deleteTree(Path root) {
        if (!Files.isDirectory(root)) return;
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(SongCache::delete);
        } catch (IOException ignored) {}
    }

    private static void delete(Path path) {
        try {
            Files.delete(path);
        } catch (IOException ignored) {}
    }
}
