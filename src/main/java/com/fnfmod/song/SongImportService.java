package com.fnfmod.song;

import com.fnfmod.chart.SongChart;
import com.fnfmod.chart.PsychChartWriter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/** Imports only charts/events/metadata/audio into a new complete mod template. */
public final class SongImportService {
    public record Request(
            String songId,
            String loadedDifficulty,
            SongChart chart,
            SongEntry entry,
            Path originalDirectory,
            Path eventDefinitionRoot,
            Path targetModDirectory
    ) {}

    public record Result(int copiedFiles, String sourceName, Path targetModDirectory) {}

    private SongImportService() {}

    public static boolean canImport(Request request) {
        Path target = request.targetModDirectory.toAbsolutePath().normalize();
        return request.entry != null || findSourceRoot(request, target) != null;
    }

    public static Result importCompleteSong(Request request) throws IOException {
        Path target = request.targetModDirectory.toAbsolutePath().normalize();
        Path modsRoot = SongLibrary.modsDir().toAbsolutePath().normalize();
        if (!target.startsWith(modsRoot) || target.equals(modsRoot)) {
            throw new IOException("Import target must be a folder inside config/fnfmod/mods");
        }
        Path source = findSourceRoot(request, target);
        if (source == null && request.entry == null) {
            throw new IOException("Could not find the song's source mod");
        }

        ModTemplateService.create(target, request.songId);
        Path chartTarget = target.resolve("data").resolve(request.songId);
        Path audioTarget = target.resolve("songs").resolve(request.songId);
        int copied = copyChartsAndMetadata(request.entry, chartTarget);
        copied += copySongAudio(request.entry, request.loadedDifficulty, audioTarget);
        copied += writePlayableCharts(request, chartTarget);
        Files.writeString(chartTarget.resolve("events.json"), PsychChartWriter.writeEvents(request.chart));
        copied++;

        String sourceName = source == null ? "song source"
                : (source.getFileName() == null ? source.toString() : source.getFileName().toString());
        return new Result(copied, sourceName, target);
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

    private static int copySongAudio(SongEntry entry, String difficulty, Path target) throws IOException {
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
        copied += copy(entry.instFor(difficulty), target.resolve("Inst.ogg"));
        copied += copy(entry.voicesFor(difficulty), target.resolve("Voices.ogg"));
        copied += copy(entry.voicesPlayerFor(difficulty), target.resolve("Voices-Player.ogg"));
        copied += copy(entry.voicesOpponentFor(difficulty), target.resolve("Voices-Opponent.ogg"));
        return copied;
    }

    private static int copyChartsAndMetadata(SongEntry entry, Path target) throws IOException {
        if (entry == null) return 0;
        LinkedHashSet<Path> files = new LinkedHashSet<>();
        addFile(files, entry.metaFile);
        addFile(files, entry.eventsFile);
        entry.legacyChartFiles.values().forEach(path -> addFile(files, path));
        entry.chartOverrides.values().forEach(path -> addFile(files, path));
        for (SongEntry.VSliceVariation variation : entry.vsliceVariations.values()) {
            addFile(files, variation.chartFile);
            addFile(files, variation.metadataFile);
        }
        int copied = 0;
        for (Path file : files) copied += copy(file, target.resolve(file.getFileName()));
        return copied;
    }

    /** Converts every known format/difficulty to Psych JSON so the imported pack stays playable. */
    private static int writePlayableCharts(Request request, Path target) throws IOException {
        LinkedHashSet<String> difficulties = new LinkedHashSet<>();
        if (request.entry != null) difficulties.addAll(request.entry.difficulties);
        difficulties.add(request.loadedDifficulty == null || request.loadedDifficulty.isBlank()
                ? "normal" : request.loadedDifficulty);
        int written = 0;
        for (String difficulty : difficulties) {
            SongChart value;
            if (sameDifficulty(difficulty, request.loadedDifficulty) || request.entry == null) {
                value = request.chart;
            } else {
                try {
                    value = SongLibrary.loadChart(request.entry, difficulty);
                } catch (Exception ignored) {
                    continue;
                }
            }
            String suffix = sameDifficulty(difficulty, "normal") ? "" : "-" + sanitize(difficulty);
            Files.writeString(target.resolve(request.songId + suffix + ".json"), PsychChartWriter.write(value));
            written++;
        }
        return written;
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
