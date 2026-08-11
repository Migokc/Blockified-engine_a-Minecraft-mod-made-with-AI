package com.fnfmod.client.gui.editor;

import com.fnfmod.FnfMod;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Imports chart-editor audio without modifying the user's source files. */
final class ChartAudioImport {
    enum Stem {
        INST("Inst.ogg"),
        VOICES("Voices.ogg"),
        PLAYER("Voices-Player.ogg"),
        OPPONENT("Voices-Opponent.ogg");

        final String fileName;

        Stem(String fileName) {
            this.fileName = fileName;
        }
    }

    record Result(Path stagingFolder, Map<Stem, Path> stems, int selectedCount,
                  int discardedCount, int convertedCount) {
        Path stem(Stem stem) {
            return stems.get(stem);
        }
    }

    private static final long CONVERSION_TIMEOUT_MINUTES = 10;

    private ChartAudioImport() {}

    static Result prepare(List<Path> selected, String playerId, String opponentId) throws IOException {
        List<Path> files = selected == null ? List.of() : selected.stream()
                .map(path -> path.toAbsolutePath().normalize())
                .filter(Files::isRegularFile)
                .toList();
        if (files.isEmpty()) throw new IOException("No readable audio files were selected");

        EnumMap<Stem, Path> sources = new EnumMap<>(Stem.class);
        List<Path> unnamed = new ArrayList<>();
        int discarded = 0;
        for (Path file : files) {
            Stem stem = classify(file, playerId, opponentId);
            if (stem == null) {
                unnamed.add(file);
            } else if (sources.putIfAbsent(stem, file) != null) {
                discarded++;
            }
        }

        if (sources.isEmpty()) {
            // The selection order is meaningful: with no recognizable names the
            // first file is the instrumental and every other file is ignored.
            sources.put(Stem.INST, files.get(0));
            discarded = files.size() - 1;
        } else {
            // A named vocal plus one arbitrarily named backing track is still a
            // useful multi-selection. Only the first unnamed file fills Inst.
            if (!sources.containsKey(Stem.INST) && !unnamed.isEmpty()) {
                sources.put(Stem.INST, unnamed.remove(0));
            }
            discarded += unnamed.size();
        }

        Path staging = Files.createTempDirectory("blockified-chart-audio-");
        EnumMap<Stem, Path> imported = new EnumMap<>(Stem.class);
        int converted = 0;
        try {
            for (Stem stem : Stem.values()) {
                Path source = sources.get(stem);
                if (source == null) continue;
                Path output = staging.resolve(stem.fileName);
                if (extension(source).equals("ogg")) {
                    Files.copy(source, output, StandardCopyOption.REPLACE_EXISTING);
                } else {
                    transcodeToVorbis(source, output, staging);
                    converted++;
                }
                imported.put(stem, output);
            }
            return new Result(staging, Collections.unmodifiableMap(imported), files.size(), discarded, converted);
        } catch (Exception error) {
            delete(staging);
            if (error instanceof IOException io) throw io;
            throw new IOException("Could not import song audio", error);
        }
    }

    static void delete(Path folder) {
        if (folder == null || !Files.exists(folder)) return;
        try (var paths = Files.walk(folder)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {}
            });
        } catch (IOException ignored) {}
    }

    private static Stem classify(Path file, String playerId, String opponentId) {
        String base = withoutExtension(file.getFileName().toString()).toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
        String player = normalize(playerId);
        String opponent = normalize(opponentId);

        if (base.equals("inst") || base.startsWith("inst-")
                || base.equals("instrumental") || base.startsWith("instrumental-")
                || base.equals("music") || base.equals("backing") || base.equals("backing-track")
                || base.equals("karaoke")) return Stem.INST;

        boolean vocalName = base.equals("voices") || base.equals("vocals")
                || base.startsWith("voices-") || base.startsWith("vocals-")
                || base.endsWith("-voices") || base.endsWith("-vocals");
        if (!vocalName) return null;

        if (containsToken(base, "player") || containsToken(base, "bf")
                || containsToken(base, "boyfriend") || (!player.isBlank() && containsToken(base, player))) {
            return Stem.PLAYER;
        }
        if (containsToken(base, "opponent") || containsToken(base, "dad")
                || (!opponent.isBlank() && containsToken(base, opponent))) return Stem.OPPONENT;
        return Stem.VOICES;
    }

    private static boolean containsToken(String value, String token) {
        if (token == null || token.isBlank()) return false;
        return ("-" + value + "-").contains("-" + token + "-");
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
    }

    private static String withoutExtension(String value) {
        int dot = value.lastIndexOf('.');
        return dot <= 0 ? value : value.substring(0, dot);
    }

    private static String extension(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static void transcodeToVorbis(Path source, Path output, Path workingDirectory) throws IOException {
        String ffmpeg = ffmpegExecutable();
        IOException first = runFfmpeg(ffmpeg, source, output, workingDirectory, "libvorbis", false);
        if (first == null) return;
        Files.deleteIfExists(output);
        IOException fallback = runFfmpeg(ffmpeg, source, output, workingDirectory, "vorbis", true);
        if (fallback == null) return;
        fallback.addSuppressed(first);
        throw fallback;
    }

    /** Prefer the FFmpeg path already configured for BBS's video exporter. */
    private static String ffmpegExecutable() {
        try {
            Class<?> utils = Class.forName("mchorse.bbs_mod.utils.FFMpegUtils");
            Method getter = utils.getMethod("getFFMPEG");
            Object value = getter.invoke(null);
            if (value instanceof String path && !path.isBlank()) return path;
        } catch (Throwable ignored) {}
        return "ffmpeg";
    }

    private static IOException runFfmpeg(String executable, Path source, Path output,
                                         Path workingDirectory, String codec,
                                         boolean experimentalVorbis) {
        Path log = workingDirectory.resolve("conversion.log");
        try {
            List<String> command = new ArrayList<>(List.of(executable, "-hide_banner", "-loglevel", "error",
                    "-y", "-i", source.toString(), "-map", "0:a:0", "-vn", "-map_metadata", "-1",
                    "-ac", "2", "-ar", "44100", "-c:a", codec));
            if (experimentalVorbis) command.addAll(List.of("-strict", "-2"));
            command.addAll(List.of("-q:a", "6", output.toString()));
            Process process = new ProcessBuilder(command)
                    .directory(workingDirectory.toFile())
                    .redirectErrorStream(true)
                    .redirectOutput(log.toFile())
                    .start();
            if (!process.waitFor(CONVERSION_TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
                process.destroyForcibly();
                return new IOException("Audio conversion timed out: " + source.getFileName());
            }
            if (process.exitValue() == 0 && Files.isRegularFile(output) && Files.size(output) > 0) return null;
            String detail = Files.isRegularFile(log) ? Files.readString(log).trim() : "";
            if (detail.length() > 500) detail = detail.substring(detail.length() - 500);
            return new IOException("FFmpeg could not convert " + source.getFileName()
                    + (detail.isBlank() ? "" : ": " + detail));
        } catch (Exception error) {
            FnfMod.LOGGER.debug("Chart audio conversion attempt failed", error);
            return new IOException("FFmpeg is unavailable. Configure BBS's Video Encoder Path, then try again", error);
        } finally {
            try {
                Files.deleteIfExists(log);
            } catch (IOException ignored) {}
        }
    }
}
