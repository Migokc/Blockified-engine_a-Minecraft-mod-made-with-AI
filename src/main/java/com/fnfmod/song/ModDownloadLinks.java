package com.fnfmod.song;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** Deliberately narrow allow-list for untrusted mod_url.txt values sent by servers. */
public final class ModDownloadLinks {
    public enum Host { GAMEBANANA, GOOGLE_DRIVE, MEDIAFIRE, MEGA }

    public record Link(String url, Host host, String gameBananaId) {}

    private ModDownloadLinks() {}

    public static Link parse(String raw) {
        if (raw == null || raw.length() > 2048 || raw.chars().anyMatch(c -> c < 32)) return null;
        try {
            URI uri = URI.create(raw.strip());
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                    || uri.getRawUserInfo() != null || uri.getPort() != -1 && uri.getPort() != 443) return null;
            String host = uri.getHost().toLowerCase(Locale.ROOT);
            String path = uri.getPath() == null ? "" : uri.getPath();
            if (host.equals("gamebanana.com") || host.equals("www.gamebanana.com")) {
                String id = GameBananaModLinks.modId(uri.toString());
                return id.isEmpty() ? null : new Link("https://gamebanana.com/mods/" + id,
                        Host.GAMEBANANA, id);
            }
            if ((host.equals("drive.google.com") || host.equals("www.drive.google.com"))
                    && (path.startsWith("/file/d/") || path.equals("/open") || path.equals("/uc"))) {
                return new Link(uri.toString(), Host.GOOGLE_DRIVE, "");
            }
            if ((host.equals("mediafire.com") || host.equals("www.mediafire.com"))
                    && (path.startsWith("/file/") || path.startsWith("/download/")
                    || path.equals("/"))) {
                return new Link(uri.toString(), Host.MEDIAFIRE, "");
            }
            if ((host.equals("mega.nz") || host.equals("www.mega.nz")
                    || host.equals("mega.co.nz") || host.equals("www.mega.co.nz"))
                    && (path.startsWith("/file/") || path.equals("/")
                    && uri.getFragment() != null && uri.getFragment().startsWith("!"))) {
                return new Link(uri.toString(), Host.MEGA, "");
            }
        } catch (IllegalArgumentException ignored) {}
        return null;
    }

    public static String firstSupportedUrl(Path pack) {
        if (pack == null) return "";
        Path file = pack.resolve("mod_url.txt");
        try {
            if (!Files.isRegularFile(file) || Files.size(file) > 4096) return "";
            for (String line : Files.readAllLines(file)) {
                Link link = parse(line);
                if (link != null) return link.url();
            }
        } catch (Exception ignored) {}
        return "";
    }

    public static Set<String> installedUrls() {
        Set<String> urls = new HashSet<>();
        scan(SongLibrary.modsDir(), urls);
        for (String configured : SongLibrary.getExternalFolders()) {
            try { scan(Path.of(configured), urls); }
            catch (RuntimeException ignored) {}
        }
        return urls;
    }

    private static void scan(Path source, Set<String> urls) {
        for (ModPackInfo pack : SongLibrary.discoverModPacks(source)) {
            String url = firstSupportedUrl(pack.root());
            if (!url.isEmpty()) urls.add(url);
        }
    }
}
