package com.fnfmod.song;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry;
import org.apache.commons.compress.archivers.sevenz.SevenZFile;
import com.github.junrar.Archive;
import com.github.junrar.rarfile.FileHeader;

/** Explicit, bounded archive installation into a new Blockified mod folder. */
public final class GameBananaInstaller {
    public static final long MAX_DOWNLOAD = 1_500_000_000L; // 1.5 GB
    private static final long MAX_EXPANDED = 4L * 1024 * 1024 * 1024;
    private static final int MAX_ENTRIES = 20_000;
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(12)).followRedirects(HttpClient.Redirect.NEVER).build();
    private static final Set<String> CONTENT_DIRS = Set.of("data", "songs", "images", "characters",
            "animations", "custom_events", "custom_notetypes", "machines", "worlds", "stages");
    private static final Set<String> BLOCKED_EXTENSIONS = Set.of(".exe", ".dll", ".jar", ".bat",
            ".cmd", ".ps1", ".vbs", ".msi", ".com", ".scr", ".sh", ".so", ".dylib");

    public record FileOption(String id, String name, long size, String checksum) {
        public boolean installable() {
            return id.matches("\\d+") && supportedArchiveName(name)
                    && size <= MAX_DOWNLOAD;
        }
    }

    public interface Progress {
        void update(String phase, long done, long total) throws IOException;
        default void checkActive() throws IOException { update("Working", 0, -1); }
    }

    private static final Progress NO_PROGRESS = (phase, done, total) -> {};

    public static boolean supportedArchiveName(String name) {
        if (name == null) return false;
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".zip") || lower.endsWith(".7z") || lower.endsWith(".rar");
    }

    private GameBananaInstaller() {}

    public static List<FileOption> files(String modId) throws IOException, InterruptedException {
        if (modId == null || !modId.matches("\\d+")) throw new IOException("Invalid mod ID");
        URI uri = URI.create("https://gamebanana.com/apiv11/Mod/" + modId + "?_csvProperties=_aFiles");
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(20))
                .header("Accept", "application/json").GET().build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) throw new IOException("GameBanana returned HTTP " + response.statusCode());
        try {
            JsonObject data = JsonParser.parseString(response.body()).getAsJsonObject();
            List<FileOption> result = new ArrayList<>();
            if (data.has("_aFiles") && data.get("_aFiles").isJsonArray()) {
                for (JsonElement element : data.getAsJsonArray("_aFiles")) {
                    if (!element.isJsonObject()) continue;
                    JsonObject file = element.getAsJsonObject();
                    String id = string(file, "_idRow");
                    String name = string(file, "_sFile");
                    long size = file.has("_nFilesize") ? file.get("_nFilesize").getAsLong() : -1;
                    if (id.matches("\\d+") && !name.isBlank()) {
                        result.add(new FileOption(id, name, size, string(file, "_sMd5Checksum")));
                    }
                }
            }
            return List.copyOf(result);
        } catch (RuntimeException error) {
            throw new IOException("Could not read GameBanana file list", error);
        }
    }

    public static Path install(String modId, String modName, FileOption file)
            throws IOException, InterruptedException {
        return install(modId, modName, file, NO_PROGRESS);
    }

    public static Path install(String modId, String modName, FileOption file, Progress progress)
            throws IOException, InterruptedException {
        if (modId == null || !modId.matches("\\d+") || file == null || !file.installable()) {
            throw new IOException("Only ZIP, 7z or RAR files up to 1.5 GB can be installed here");
        }
        if (GameBananaModLinks.installedIds().contains(modId)) {
            throw new IOException("This GameBanana mod is already installed");
        }
        Path mods = SongLibrary.modsDir().toAbsolutePath().normalize();
        Files.createDirectories(mods);
        Path destination = mods.resolve(folderName(modName, modId)).normalize();
        if (!destination.startsWith(mods) || Files.exists(destination)) {
            throw new IOException("A mod folder with this name already exists");
        }
        Path temporary = Files.createTempDirectory("blockified-gamebanana-");
        boolean moved = false;
        try {
            Path archive = temporary.resolve("download.archive");
            download(file, archive, progress);
            Path staged = temporary.resolve("pack");
            progress.update("Extracting", 0, -1);
            extract(archive, staged, progress);
            Files.writeString(staged.resolve("mod_url.txt"),
                    "https://gamebanana.com/mods/" + modId + System.lineSeparator());
            GameBananaInstalledMod.mark(staged, modId);
            progress.checkActive();
            Files.move(staged, destination);
            moved = true;
            progress.checkActive();
            return destination;
        } catch (IOException | InterruptedException error) {
            if (moved) deleteTemporary(destination);
            throw error;
        } finally {
            deleteTemporary(temporary);
        }
    }

    public static Path installExternal(ModDownloadLinks.Link link, String modName, Progress progress)
            throws IOException, InterruptedException {
        if (link == null || link.host() == ModDownloadLinks.Host.GAMEBANANA)
            throw new IOException("Invalid external mod link");
        if (ModDownloadLinks.installedUrls().contains(link.url()))
            throw new IOException("This mod link is already installed");
        Path mods = SongLibrary.modsDir().toAbsolutePath().normalize();
        Files.createDirectories(mods);
        Path destination = mods.resolve(folderName(modName, "dl",
                Integer.toHexString(link.url().hashCode()))).normalize();
        if (!destination.startsWith(mods) || Files.exists(destination))
            throw new IOException("A mod folder with this name already exists");
        Path temporary = Files.createTempDirectory("blockified-mod-download-");
        boolean moved = false;
        try {
            Path archive = temporary.resolve("download.archive");
            ExternalModDownloader.download(link, archive, progress);
            progress.update("Extracting", 0, -1);
            Path staged = temporary.resolve("pack");
            extract(archive, staged, progress);
            Files.writeString(staged.resolve("mod_url.txt"), link.url() + System.lineSeparator());
            progress.checkActive();
            Files.move(staged, destination);
            moved = true;
            progress.checkActive();
            return destination;
        } catch (IOException | InterruptedException error) {
            if (moved) deleteTemporary(destination);
            throw error;
        } finally {
            deleteTemporary(temporary);
        }
    }

    private static void download(FileOption file, Path target, Progress progress)
            throws IOException, InterruptedException {
        URI uri = URI.create("https://gamebanana.com/dl/" + file.id());
        for (int redirects = 0; redirects <= 5; redirects++) {
            if (!trustedUrl(uri)) throw new IOException("Download redirected outside GameBanana");
            HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofMinutes(5)).GET().build();
            HttpResponse<InputStream> response = HTTP.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream input = response.body()) {
                if (response.statusCode() >= 300 && response.statusCode() < 400) {
                    String location = response.headers().firstValue("Location").orElse("");
                    if (location.isBlank()) throw new IOException("Download redirect has no location");
                    uri = uri.resolve(location);
                    continue;
                }
                if (response.statusCode() != 200) {
                    throw new IOException("GameBanana download returned HTTP " + response.statusCode());
                }
                long length = response.headers().firstValueAsLong("Content-Length").orElse(-1);
                if (length > MAX_DOWNLOAD) throw new IOException("Download exceeds 1.5 GB limit");
                MessageDigest digest = md5();
                long total = 0;
                try (var output = Files.newOutputStream(target)) {
                    byte[] buffer = new byte[64 * 1024];
                    int read;
                    while ((read = input.read(buffer)) != -1) {
                        progress.update("Downloading", total, length >= 0 ? length : file.size());
                        total += read;
                        if (total > MAX_DOWNLOAD) throw new IOException("Download exceeds 1.5 GB limit");
                        output.write(buffer, 0, read);
                        digest.update(buffer, 0, read);
                    }
                }
                progress.update("Downloading", total, total);
                if (!file.checksum().isBlank()
                        && !MessageDigest.isEqual(file.checksum().toLowerCase(Locale.ROOT)
                                .getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                                java.util.HexFormat.of().formatHex(digest.digest())
                                        .getBytes(java.nio.charset.StandardCharsets.US_ASCII))) {
                    throw new IOException("Downloaded file checksum does not match GameBanana metadata");
                }
                return;
            }
        }
        throw new IOException("Too many GameBanana download redirects");
    }

    private static boolean trustedUrl(URI uri) {
        String host = uri.getHost();
        return "https".equalsIgnoreCase(uri.getScheme()) && host != null
                && (host.equalsIgnoreCase("gamebanana.com")
                || host.toLowerCase(Locale.ROOT).endsWith(".gamebanana.com"));
    }

    static void extract(Path archive, Path destination) throws IOException {
        extract(archive, destination, NO_PROGRESS);
    }

    static void extract(Path archive, Path destination, Progress progress) throws IOException {
        byte[] signature;
        try (InputStream input = Files.newInputStream(archive)) { signature = input.readNBytes(8); }
        if (signature.length >= 4 && signature[0] == 'P' && signature[1] == 'K') {
            extractZip(archive, destination, progress);
        } else if (signature.length >= 6 && signature[0] == '7' && signature[1] == 'z'
                && (signature[2] & 0xff) == 0xbc && (signature[3] & 0xff) == 0xaf
                && signature[4] == 0x27 && signature[5] == 0x1c) {
            extractSevenZ(archive, destination, progress);
        } else if (signature.length >= 7 && signature[0] == 'R' && signature[1] == 'a'
                && signature[2] == 'r' && signature[3] == '!' && signature[4] == 0x1a
                && signature[5] == 0x07) {
            extractRar(archive, destination, progress);
        } else throw new IOException("Download is not a ZIP, 7z or RAR archive");
    }

    private static void extractZip(Path archive, Path destination, Progress progress) throws IOException {
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            List<ZipEntry> entries = new ArrayList<>();
            Set<Path> roots = new HashSet<>();
            long declaredTotal = 0;
            boolean unknownTotal = false;
            Enumeration<? extends ZipEntry> enumeration = zip.entries();
            while (enumeration.hasMoreElements()) {
                if (entries.size() >= MAX_ENTRIES) throw new IOException("Archive has too many files");
                ZipEntry entry = enumeration.nextElement();
                Path path = safePath(entry.getName());
                entries.add(entry);
                if (!entry.isDirectory()) {
                    if (entry.getSize() < 0) unknownTotal = true;
                    else {
                        declaredTotal += entry.getSize();
                        if (declaredTotal > MAX_EXPANDED)
                            throw new IOException("Archive expands too large");
                    }
                }
                if (path.getName(0).toString().equalsIgnoreCase("__MACOSX")) continue;
                for (int i = 0; i < path.getNameCount(); i++) {
                    String part = path.getName(i).toString().toLowerCase(Locale.ROOT);
                    if (CONTENT_DIRS.contains(part) || part.equals("pack.json")
                            || part.equals("_polymod_meta.json")) {
                        roots.add(i == 0 ? Path.of("") : path.subpath(0, i));
                        break;
                    }
                }
            }
            if (roots.isEmpty()) throw new IOException("ZIP has no recognizable FNF mod layout");
            Path prefix = roots.stream().filter(candidate -> roots.stream().allMatch(root ->
                    candidate.toString().isEmpty() || root.startsWith(candidate)))
                    .min(Comparator.comparingInt(root -> root.toString().length()))
                    .orElseThrow(() -> new IOException("ZIP contains multiple mod roots"));
            Files.createDirectories(destination);
            long expanded = 0;
            long progressTotal = unknownTotal ? -1 : declaredTotal;
            int files = 0;
            Set<String> written = new HashSet<>();
            for (ZipEntry entry : entries) {
                Path path = safePath(entry.getName());
                if (path.getName(0).toString().equalsIgnoreCase("__MACOSX")) continue;
                if (!path.startsWith(prefix) || path.equals(prefix)) continue;
                Path relative = prefix.toString().isEmpty() ? path : prefix.relativize(path);
                if (relative.toString().isEmpty()) continue;
                String lower = relative.getFileName().toString().toLowerCase(Locale.ROOT);
                if (BLOCKED_EXTENSIONS.stream().anyMatch(lower::endsWith)) {
                    throw new IOException("Archive contains executable file: " + lower);
                }
                Path output = destination.resolve(relative).normalize();
                if (!output.startsWith(destination)) throw new IOException("Unsafe archive path");
                if (entry.isDirectory()) {
                    Files.createDirectories(output);
                    continue;
                }
                if (!written.add(relative.toString().toLowerCase(Locale.ROOT))) {
                    throw new IOException("ZIP contains duplicate file names");
                }
                if (++files > MAX_ENTRIES) throw new IOException("Archive has too many files");
                if (entry.getSize() > MAX_EXPANDED - expanded) throw new IOException("Archive expands too large");
                Files.createDirectories(output.getParent());
                try (InputStream input = zip.getInputStream(entry);
                     var stream = Files.newOutputStream(output)) {
                    byte[] buffer = new byte[64 * 1024];
                    int read;
                    while ((read = input.read(buffer)) != -1) {
                        progress.update("Extracting", expanded, progressTotal);
                        expanded += read;
                        if (expanded > MAX_EXPANDED) throw new IOException("Archive expands too large");
                        stream.write(buffer, 0, read);
                    }
                }
            }
            if (files == 0) throw new IOException("ZIP contains no mod files");
            progress.update("Extracting", expanded, expanded);
        }
    }

    private static void extractSevenZ(Path archive, Path destination, Progress progress) throws IOException {
        List<Path> paths = new ArrayList<>();
        long declaredTotal = 0;
        try (SevenZFile reader = new SevenZFile(archive.toFile())) {
            SevenZArchiveEntry entry;
            while ((entry = reader.getNextEntry()) != null) {
                if (paths.size() >= MAX_ENTRIES) throw new IOException("Archive has too many files");
                paths.add(safePath(entry.getName()));
                if (!entry.isDirectory()) {
                    if (entry.getSize() > MAX_EXPANDED - declaredTotal)
                        throw new IOException("Archive expands too large");
                    declaredTotal += Math.max(0, entry.getSize());
                }
            }
        }
        ExtractionState state = new ExtractionState(destination, modPrefix(paths), progress, declaredTotal);
        try (SevenZFile reader = new SevenZFile(archive.toFile())) {
            SevenZArchiveEntry entry;
            while ((entry = reader.getNextEntry()) != null) {
                Path output = state.outputFor(entry.getName(), entry.isDirectory(), entry.getSize());
                if (output == null || entry.isDirectory()) continue;
                try (var stream = Files.newOutputStream(output)) {
                    byte[] buffer = new byte[64 * 1024];
                    int read;
                    while ((read = reader.read(buffer)) != -1) {
                        state.add(read);
                        stream.write(buffer, 0, read);
                    }
                }
            }
        }
        state.requireFiles();
        progress.update("Extracting", state.expanded, state.expanded);
    }

    private static void extractRar(Path archive, Path destination, Progress progress) throws IOException {
        try (Archive reader = new Archive(archive.toFile())) {
            List<FileHeader> headers = reader.getFileHeaders();
            if (headers.size() > MAX_ENTRIES) throw new IOException("Archive has too many files");
            List<Path> paths = new ArrayList<>();
            long declaredTotal = 0;
            for (FileHeader header : headers) {
                paths.add(safePath(header.getFileName()));
                if (!header.isDirectory()) {
                    if (header.getFullUnpackSize() > MAX_EXPANDED - declaredTotal)
                        throw new IOException("Archive expands too large");
                    declaredTotal += Math.max(0, header.getFullUnpackSize());
                }
            }
            ExtractionState state = new ExtractionState(destination, modPrefix(paths), progress, declaredTotal);
            for (FileHeader header : headers) {
                Path output = state.outputFor(header.getFileName(), header.isDirectory(),
                        header.getFullUnpackSize());
                if (output == null || header.isDirectory()) continue;
                try (var stream = new BoundedOutputStream(Files.newOutputStream(output), state)) {
                    reader.extractFile(header, stream);
                } catch (Exception error) {
                    if (error instanceof IOException io) throw io;
                    throw new IOException("Could not extract RAR entry " + header.getFileName(), error);
                }
            }
            state.requireFiles();
            progress.update("Extracting", state.expanded, state.expanded);
        } catch (IOException error) {
            throw error;
        } catch (Exception error) {
            throw new IOException("Could not read RAR archive", error);
        }
    }

    private static Path modPrefix(List<Path> paths) throws IOException {
        Set<Path> roots = new HashSet<>();
        for (Path path : paths) {
            if (path.getName(0).toString().equalsIgnoreCase("__MACOSX")) continue;
            for (int i = 0; i < path.getNameCount(); i++) {
                String part = path.getName(i).toString().toLowerCase(Locale.ROOT);
                if (CONTENT_DIRS.contains(part) || part.equals("pack.json")
                        || part.equals("_polymod_meta.json")) {
                    roots.add(i == 0 ? Path.of("") : path.subpath(0, i));
                    break;
                }
            }
        }
        if (roots.isEmpty()) throw new IOException("Archive has no recognizable FNF mod layout");
        return roots.stream().filter(candidate -> roots.stream().allMatch(root ->
                        candidate.toString().isEmpty() || root.startsWith(candidate)))
                .min(Comparator.comparingInt(root -> root.toString().length()))
                .orElseThrow(() -> new IOException("Archive contains multiple mod roots"));
    }

    private static final class ExtractionState {
        private final Path destination, prefix;
        private final Progress progress;
        private final long declaredTotal;
        private final Set<String> written = new HashSet<>();
        private long expanded;
        private int files;

        private ExtractionState(Path destination, Path prefix, Progress progress,
                                long declaredTotal) throws IOException {
            this.destination = destination;
            this.prefix = prefix;
            this.progress = progress;
            this.declaredTotal = declaredTotal > 0 ? declaredTotal : -1;
            Files.createDirectories(destination);
        }

        private Path outputFor(String name, boolean directory, long declaredSize) throws IOException {
            progress.checkActive();
            Path path = safePath(name);
            if (path.getName(0).toString().equalsIgnoreCase("__MACOSX")
                    || !path.startsWith(prefix) || path.equals(prefix)) return null;
            Path relative = prefix.toString().isEmpty() ? path : prefix.relativize(path);
            if (relative.toString().isEmpty()) return null;
            String lower = relative.getFileName().toString().toLowerCase(Locale.ROOT);
            if (BLOCKED_EXTENSIONS.stream().anyMatch(lower::endsWith))
                throw new IOException("Archive contains executable file: " + lower);
            Path output = destination.resolve(relative).normalize();
            if (!output.startsWith(destination)) throw new IOException("Unsafe archive path");
            if (directory) {
                Files.createDirectories(output);
                return output;
            }
            if (++files > MAX_ENTRIES) throw new IOException("Archive has too many files");
            if (!written.add(relative.toString().toLowerCase(Locale.ROOT)))
                throw new IOException("Archive contains duplicate file names");
            if (declaredSize > MAX_EXPANDED - expanded)
                throw new IOException("Archive expands too large");
            Files.createDirectories(output.getParent());
            return output;
        }

        private void add(int bytes) throws IOException {
            expanded += bytes;
            if (expanded > MAX_EXPANDED) throw new IOException("Archive expands too large");
            progress.update("Extracting", expanded, declaredTotal);
        }

        private void requireFiles() throws IOException {
            if (files == 0) throw new IOException("Archive contains no mod files");
        }
    }

    private static final class BoundedOutputStream extends java.io.FilterOutputStream {
        private final ExtractionState state;
        private BoundedOutputStream(java.io.OutputStream out, ExtractionState state) {
            super(out);
            this.state = state;
        }
        @Override public void write(int value) throws IOException {
            state.add(1);
            out.write(value);
        }
        @Override public void write(byte[] buffer, int offset, int length) throws IOException {
            state.add(length);
            out.write(buffer, offset, length);
        }
    }

    private static Path safePath(String raw) throws IOException {
        String name = raw.replace('\\', '/');
        if (name.isBlank() || name.startsWith("/") || name.indexOf(':') >= 0) {
            throw new IOException("Unsafe archive path");
        }
        Path path = Path.of(name).normalize();
        if (path.toString().isBlank() || path.isAbsolute() || path.startsWith("..")) {
            throw new IOException("Unsafe archive path");
        }
        return path;
    }

    private static String folderName(String name, String id) {
        return folderName(name, "gb", id);
    }

    private static String folderName(String name, String source, String id) {
        String slug = name == null ? "" : name.replaceAll("[^a-zA-Z0-9._ -]", "-")
                .replaceAll("[. ]+$", "").strip();
        if (slug.isBlank()) slug = "mod";
        if (slug.length() > 60) slug = slug.substring(0, 60).strip();
        return slug + "-" + source + id;
    }

    private static String string(JsonObject object, String key) {
        try { return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsString() : ""; }
        catch (RuntimeException ignored) { return ""; }
    }

    private static MessageDigest md5() throws IOException {
        try { return MessageDigest.getInstance("MD5"); }
        catch (java.security.NoSuchAlgorithmException error) { throw new IOException(error); }
    }

    private static void deleteTemporary(Path path) {
        if (path == null || !Files.isDirectory(path)) return;
        try (var files = Files.walk(path)) {
            for (Path file : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(file);
        } catch (IOException ignored) {}
    }
}
