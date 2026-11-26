package com.fnfmod.song;

import com.fnfmod.FnfMod;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Separates Blockified-downloaded packs from manually installed packs with the same mod_url.txt. */
public final class GameBananaInstalledMod {
    public static final String MARKER = ".blockified-gamebanana-download";
    private static final String MINECRAFT_ART = ".blockified-gamebanana-minecraft-art";
    private static final Pattern LEGACY_FOLDER = Pattern.compile("(?i).*-gb(\\d+)");

    private GameBananaInstalledMod() {}

    public static void mark(Path pack, String modId) throws IOException {
        if (modId == null || !modId.matches("\\d+")) throw new IOException("Invalid GameBanana mod ID");
        Files.writeString(pack.resolve(MARKER), modId + System.lineSeparator());
    }

    public static boolean isDownloaded(SongEntry entry) {
        return downloadedPack(entry) != null;
    }

    public static boolean isDownloaded(Path source) {
        return downloadedPack(source) != null;
    }

    /** The owning download, including for chart-only overrides with an original directory. */
    public static Path downloadedPack(SongEntry entry) {
        if (entry == null) return null;
        Path origin = downloadedPack(entry.chartOriginRoot);
        if (origin != null) return origin;
        Path mod = downloadedPack(entry.modRoot);
        if (mod != null) return mod;
        Path character = downloadedPack(entry.characterRoot);
        return character != null ? character : downloadedPack(entry.folder);
    }

    public static Path downloadedPack(Path source) {
        Path current = source == null ? null : source.toAbsolutePath().normalize();
        for (int depth = 0; current != null && depth < 8; depth++, current = current.getParent()) {
            if (isDownloadedPack(current)) return current;
        }
        return null;
    }

    /** Off by default for each download; does not change its normal Images/Fonts permissions. */
    public static boolean minecraftArtEnabled(Path pack) {
        if (pack == null || !isDownloadedPack(pack)) return false;
        try {
            return Files.isRegularFile(pack.resolve(MINECRAFT_ART))
                    && "true".equalsIgnoreCase(Files.readString(pack.resolve(MINECRAFT_ART)).strip());
        } catch (IOException ignored) {
            return false;
        }
    }

    public static void setMinecraftArtEnabled(Path pack, boolean enabled) throws IOException {
        if (pack == null || !isDownloadedPack(pack)) {
            throw new IOException("Select a GameBanana-downloaded mod first");
        }
        Files.writeString(pack.resolve(MINECRAFT_ART), enabled + System.lineSeparator());
    }

    private static boolean isDownloadedPack(Path pack) {
        Path link = pack.resolve("mod_url.txt");
        if (!Files.isRegularFile(link)) return false;
        try {
            String id = GameBananaModLinks.modId(Files.readString(link).strip());
            if (id.isEmpty()) return false;
            Path marker = pack.resolve(MARKER);
            if (Files.isRegularFile(marker)) return id.equals(Files.readString(marker).strip());
            Matcher legacy = LEGACY_FOLDER.matcher(pack.getFileName().toString());
            return legacy.matches() && id.equals(legacy.group(1));
        } catch (Exception ignored) {
            return false;
        }
    }

    /** Previous browser builds disabled these permissions globally; restore them once for known downloads. */
    public static void migrateLegacyDownloads() {
        Path mods = SongLibrary.modsDir().toAbsolutePath().normalize();
        if (!Files.isDirectory(mods)) return;
        try (var folders = Files.list(mods)) {
            for (Path pack : folders.filter(Files::isDirectory).toList()) {
                try {
                    if (Files.exists(pack.resolve(MARKER)) || !isDownloadedPack(pack)) continue;
                    String source = mods.toString();
                    EnumSet<SongLibrary.ExternalContent> selected = SongLibrary.getExternalPackContent(source, pack);
                    EnumSet<SongLibrary.ExternalContent> inherited = SongLibrary.getExternalFolderContent(source);
                    if (!selected.contains(SongLibrary.ExternalContent.IMAGES)
                            && !selected.contains(SongLibrary.ExternalContent.FONTS)) {
                        if (inherited.contains(SongLibrary.ExternalContent.IMAGES))
                            selected.add(SongLibrary.ExternalContent.IMAGES);
                        if (inherited.contains(SongLibrary.ExternalContent.FONTS))
                            selected.add(SongLibrary.ExternalContent.FONTS);
                        new ExternalDirectoryConfig(SongLibrary.root()).setContent(
                                SongLibrary.packFilterKey(source, pack), selected);
                        if (!SongLibrary.getExternalPackContent(source, pack).equals(selected)) {
                            throw new IOException("Could not restore the downloaded pack's permissions");
                        }
                    }
                    String id = GameBananaModLinks.modId(Files.readString(pack.resolve("mod_url.txt")).strip());
                    mark(pack, id);
                } catch (Exception error) {
                    FnfMod.LOGGER.warn("Could not migrate GameBanana download {}: {}", pack, error.toString());
                }
            }
        } catch (Exception error) {
            FnfMod.LOGGER.warn("Could not inspect GameBanana downloads: {}", error.toString());
        }
    }
}
