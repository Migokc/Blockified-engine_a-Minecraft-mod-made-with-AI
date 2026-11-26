package com.fnfmod.song;

import com.fnfmod.gameplay.PlaybackPolicy;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

/** Deterministic, path-independent fingerprint for locally installed song resources. */
public final class SongCompatibility {
    public record Stamp(int fileCount, long totalBytes, String sha256) {
        public boolean valid() {
            return fileCount >= 0 && totalBytes >= 0 && sha256 != null
                    && sha256.matches("(?i)[0-9a-f]{64}");
        }
    }

    private record FileStamp(String name, long size, byte[] hash) {}

    private SongCompatibility() {}

    public static Stamp stamp(SongEntry entry, String difficulty, PlaybackPolicy policy)
            throws IOException {
        if (entry == null) throw new IOException("missing song entry");
        List<FileStamp> files = new ArrayList<>();
        long total = 0;
        for (Path file : entry.transferFiles(difficulty, policy)) {
            if (!Files.isRegularFile(file)) throw new IOException("missing " + file);
            long size = Files.size(file);
            if (Long.MAX_VALUE - total < size) throw new IOException("song size overflow");
            total += size;
            files.add(new FileStamp(entry.transferName(file), size, hashFile(file)));
        }
        // Absolute install paths never participate. Name/hash sorting also makes
        // duplicate logical names deterministic across different operating systems.
        files.sort(Comparator.comparing(FileStamp::name)
                .thenComparing(value -> HexFormat.of().formatHex(value.hash()))
                .thenComparingLong(FileStamp::size));
        MessageDigest aggregate = digest();
        for (FileStamp file : files) {
            byte[] name = file.name().replace('\\', '/').getBytes(StandardCharsets.UTF_8);
            aggregate.update(ByteBuffer.allocate(Integer.BYTES).putInt(name.length).array());
            aggregate.update(name);
            aggregate.update(ByteBuffer.allocate(Long.BYTES).putLong(file.size()).array());
            aggregate.update(file.hash());
        }
        return new Stamp(files.size(), total, HexFormat.of().formatHex(aggregate.digest()));
    }

    private static byte[] hashFile(Path file) throws IOException {
        MessageDigest digest = digest();
        byte[] buffer = new byte[64 * 1024];
        try (var input = new BufferedInputStream(Files.newInputStream(file))) {
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) digest.update(buffer, 0, read);
            }
        }
        return digest.digest();
    }

    private static MessageDigest digest() throws IOException {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (Exception error) {
            throw new IOException("SHA-256 unavailable", error);
        }
    }
}
