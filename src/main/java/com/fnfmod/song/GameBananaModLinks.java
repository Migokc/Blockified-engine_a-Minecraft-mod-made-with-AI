package com.fnfmod.song;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Installed GameBanana identities are independent of each player's local directory layout. */
public final class GameBananaModLinks {
    private static final Pattern MOD_URL = Pattern.compile(
            "(?i)https?://(?:www\\.)?gamebanana\\.com/mods/(\\d+)(?:[/?#].*)?");

    private GameBananaModLinks() {}

    public static String modId(String url) {
        if (url == null) return "";
        Matcher match = MOD_URL.matcher(url.strip());
        return match.matches() ? match.group(1) : "";
    }

    public static String songModUrl(SongEntry entry) {
        if (entry == null || entry.modRoot == null) return "";
        return ModDownloadLinks.firstSupportedUrl(entry.modRoot);
    }

    public static Set<String> installedIds() {
        Set<String> ids = new HashSet<>();
        scanSource(SongLibrary.modsDir(), ids);
        for (String configured : SongLibrary.getExternalFolders()) {
            try { scanSource(Path.of(configured), ids); }
            catch (RuntimeException ignored) {}
        }
        return ids;
    }

    private static void scanSource(Path source, Set<String> ids) {
        for (ModPackInfo pack : SongLibrary.discoverModPacks(source)) {
            Path link = pack.root().resolve("mod_url.txt");
            if (!Files.isRegularFile(link)) continue;
            try {
                String id = modId(Files.readString(link).strip());
                if (!id.isEmpty()) ids.add(id);
            } catch (Exception ignored) {}
        }
    }
}
