package com.fnfmod.song;

import com.fnfmod.chart.SongChart;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Copies the portable parts of an externally sourced song into a local song override. */
public final class SongImportService {
    public record Request(
            String songId,
            String loadedDifficulty,
            SongChart chart,
            SongEntry entry,
            Path originalDirectory,
            Path eventDefinitionRoot,
            Path targetDirectory
    ) {}

    public record Result(int copiedFiles, String sourceName) {}

    private SongImportService() {}

    public static boolean canImport(Request request) {
        Path target = request.targetDirectory.toAbsolutePath().normalize();
        return request.entry != null || findSourceRoot(request, target) != null;
    }

    public static Result importCompleteSong(Request request) throws IOException {
        Path target = request.targetDirectory.toAbsolutePath().normalize();
        Path source = findSourceRoot(request, target);
        if (source == null && request.entry == null) {
            throw new IOException("Could not find the song's source mod");
        }

        Files.createDirectories(target);
        int copied = copySongAudio(request.entry, target);
        copied += copyOtherDifficultyCharts(request.entry, request.loadedDifficulty, request.songId, target);
        copied += copySongIcon(request.entry, request.chart, source, target);
        String sourceName = source == null ? "song source"
                : (source.getFileName() == null ? source.toString() : source.getFileName().toString());
        return new Result(copied, sourceName);
    }

    private static Path findSourceRoot(Request request, Path target) {
        LinkedHashSet<Path> candidates = new LinkedHashSet<>();
        if (request.originalDirectory != null) candidates.add(request.originalDirectory);
        if (request.entry != null) {
            if (request.entry.chartOriginRoot != null) candidates.add(request.entry.chartOriginRoot);
            if (request.entry.modRoot != null) candidates.add(request.entry.modRoot);
            if (request.entry.folder != null) candidates.add(request.entry.folder);
        }
        if (request.eventDefinitionRoot != null) candidates.add(request.eventDefinitionRoot);
        for (Path candidate : candidates) {
            Path normalized = candidate.toAbsolutePath().normalize();
            if (!normalized.equals(target) && Files.isDirectory(normalized)) return normalized;
        }
        return null;
    }

    private static int copySongAudio(SongEntry entry, Path target) throws IOException {
        if (entry == null) return 0;
        LinkedHashSet<Path> files = new LinkedHashSet<>();
        addFile(files, entry.instFile);
        addFile(files, entry.voicesFile);
        addFile(files, entry.voicesPlayerFile);
        addFile(files, entry.voicesOpponentFile);
        for (SongEntry.VSliceVariation variation : entry.vsliceVariations.values()) {
            addFile(files, variation.instFile);
            addFile(files, variation.voicesFile);
            addFile(files, variation.voicesPlayerFile);
            addFile(files, variation.voicesOpponentFile);
        }
        int copied = 0;
        for (Path file : files) copied += copy(file, target.resolve(file.getFileName()));
        return copied;
    }

    private static int copySongIcon(SongEntry entry, SongChart chart, Path sourceRoot, Path target)
            throws IOException {
        Path icon = entry == null ? null : entry.opponentIconFile;
        if (icon == null && sourceRoot != null) {
            LinkedHashSet<String> names = new LinkedHashSet<>();
            if (entry != null && entry.opponentIcon != null && !entry.opponentIcon.isBlank()) {
                names.add(entry.opponentIcon);
            }
            if (chart.player2 != null && !chart.player2.isBlank()) names.add(chart.player2);
            search:
            for (String name : names) {
                for (String folder : List.of("images/icons", "icons", "images/characters", "")) {
                    Path directory = folder.isEmpty() ? sourceRoot : sourceRoot.resolve(folder);
                    for (String filename : List.of("icon-" + name + ".png", name + ".png")) {
                        Path candidate = directory.resolve(filename);
                        if (Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)) {
                            icon = candidate;
                            break search;
                        }
                    }
                }
            }
        }
        return icon == null ? 0 : copy(icon, target.resolve("images/icons").resolve(icon.getFileName()));
    }

    private static int copyOtherDifficultyCharts(SongEntry entry, String loadedDifficulty,
                                                  String songId, Path target) throws IOException {
        if (entry == null || entry.format != SongEntry.Format.LEGACY) return 0;
        int copied = 0;
        for (var chartFile : entry.legacyChartFiles.entrySet()) {
            String difficulty = chartFile.getKey();
            if (sameDifficulty(difficulty, loadedDifficulty)) continue;
            Path source = entry.chartOverrides.getOrDefault(difficulty, chartFile.getValue());
            String suffix = difficulty.equalsIgnoreCase("normal") ? "" : "-" + sanitize(difficulty);
            copied += copy(source, target.resolve(songId + suffix + ".json"));
        }
        return copied;
    }

    private static boolean sameDifficulty(String first, String second) {
        if (first == null || second == null) return false;
        return sanitize(first).equals(sanitize(second));
    }

    private static String sanitize(String value) {
        return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "-");
    }

    private static void addFile(Set<Path> files, Path file) {
        if (file != null && Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) files.add(file);
    }

    private static int copy(Path source, Path destination) throws IOException {
        if (source == null || !Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) return 0;
        Path normalizedSource = source.toAbsolutePath().normalize();
        Path normalizedDestination = destination.toAbsolutePath().normalize();
        if (normalizedSource.equals(normalizedDestination)) return 0;
        Files.createDirectories(normalizedDestination.getParent());
        Files.copy(normalizedSource, normalizedDestination, StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.COPY_ATTRIBUTES);
        return 1;
    }
}
