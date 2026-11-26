package com.fnfmod.song;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry;
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile;
import com.github.junrar.Archive;

/** Local-only archive checks; no GameBanana downloads or Minecraft instance required. */
public final class GameBananaInstallerChecks {
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("blockified-gamebanana-check-");
        try {
            Map<String, String> wrapped = new LinkedHashMap<>();
            wrapped.put("mods/My Pack/pack.json", "{}");
            wrapped.put("mods/My Pack/data/test/test.json", "{}");
            wrapped.put("mods/My Pack/assets/shared/images/example.png", "image");
            Path archive = archive(root, "wrapped.zip", wrapped);
            Path output = root.resolve("output");
            GameBananaInstaller.extract(archive, output);
            require(Files.isRegularFile(output.resolve("pack.json")), "pack root not stripped");
            require(Files.isRegularFile(output.resolve("data/test/test.json")), "chart missing");
            require(Files.isRegularFile(output.resolve("assets/shared/images/example.png")), "asset missing");

            reject(root, "traversal.zip", Map.of("pack.json", "{}", "../outside.txt", "bad"));
            reject(root, "executable.zip", Map.of("pack.json", "{}", "install.exe", "bad"));
            reject(root, "multiple.zip", Map.of("A/data/a.json", "{}", "B/data/b.json", "{}"));
            Path source = root.resolve("source.json");
            Files.writeString(source, "{}");
            Path sevenZip = root.resolve("pack.7z");
            try (SevenZOutputFile seven = new SevenZOutputFile(sevenZip.toFile())) {
                SevenZArchiveEntry entry = seven.createArchiveEntry(source.toFile(), "mod/data/test/test.json");
                seven.putArchiveEntry(entry);
                seven.write(Files.readAllBytes(source));
                seven.closeArchiveEntry();
                seven.finish();
            }
            Path sevenOutput = root.resolve("seven-output");
            GameBananaInstaller.extract(sevenZip, sevenOutput);
            require(Files.isRegularFile(sevenOutput.resolve("data/test/test.json")),
                    "7z chart missing");
            String rarFixture = System.getenv("BLOCKIFIED_RAR_FIXTURE");
            if (rarFixture != null && !rarFixture.isBlank()) {
                try (Archive rar = new Archive(Path.of(rarFixture).toFile())) {
                    require(!rar.getFileHeaders().isEmpty(), "RAR has no entries");
                    var small = rar.getFileHeaders().stream()
                            .filter(header -> !header.isDirectory()
                                    && header.getFullUnpackSize() > 0
                                    && header.getFullUnpackSize() < 1024 * 1024)
                            .findFirst().orElseThrow();
                    var outputBytes = new java.io.ByteArrayOutputStream();
                    rar.extractFile(small, outputBytes);
                    require(outputBytes.size() > 0, "RAR entry did not decode");
                }
            }
            System.out.println("ZIP and 7z installation checks passed.");
        } finally {
            try (var files = Files.walk(root)) {
                for (Path file : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(file);
            }
        }
    }

    private static void reject(Path root, String name, Map<String, String> entries) throws Exception {
        Path archive = archive(root, name, entries);
        try {
            GameBananaInstaller.extract(archive, root.resolve(name + "-output"));
            throw new AssertionError(name + " was accepted");
        } catch (IOException expected) {
            // Unsafe or ambiguous archives must not install.
        }
    }

    private static Path archive(Path root, String name, Map<String, String> entries) throws Exception {
        Path result = root.resolve(name);
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(result))) {
            for (var entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return result;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
