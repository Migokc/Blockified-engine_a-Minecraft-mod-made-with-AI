package com.fnfmod.song;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** HTTPS-only public-file downloads. A share link never grants access to other hosts. */
final class ExternalModDownloader {
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15)).followRedirects(HttpClient.Redirect.NEVER).build();
    private static final Pattern DRIVE_ID = Pattern.compile("(?:/file/d/|[?&]id=)([A-Za-z0-9_-]+)");
    private static final Pattern MEDIAFIRE_DOWNLOAD = Pattern.compile(
            "https://download[^\\\"'<>\\s]*\\.mediafire\\.com/[^\\\"'<>\\s]+",
            Pattern.CASE_INSENSITIVE);

    private ExternalModDownloader() {}

    static void download(ModDownloadLinks.Link link, Path target, GameBananaInstaller.Progress progress)
            throws IOException, InterruptedException {
        switch (link.host()) {
            case GOOGLE_DRIVE -> {
                Matcher match = DRIVE_ID.matcher(link.url());
                if (!match.find()) throw new IOException("Google Drive link has no file ID");
                String id = match.group(1);
                URI direct = URI.create("https://drive.usercontent.google.com/download?id=" + id
                        + "&export=download&confirm=t");
                transfer(direct, target, progress, ModDownloadLinks.Host.GOOGLE_DRIVE, null, -1);
            }
            case MEDIAFIRE -> {
                String page = smallPage(URI.create(link.url()), ModDownloadLinks.Host.MEDIAFIRE);
                Matcher match = MEDIAFIRE_DOWNLOAD.matcher(page.replace("&amp;", "&"));
                if (!match.find()) throw new IOException("MediaFire did not provide a public download link");
                transfer(URI.create(match.group()), target, progress, ModDownloadLinks.Host.MEDIAFIRE,
                        null, -1);
            }
            case MEGA -> downloadMega(link.url(), target, progress);
            default -> throw new IOException("Unsupported mod download host");
        }
    }

    private static String smallPage(URI start, ModDownloadLinks.Host host)
            throws IOException, InterruptedException {
        URI uri = start;
        for (int redirects = 0; redirects <= 5; redirects++) {
            if (!trusted(uri, host)) throw new IOException("Page redirected to an untrusted host");
            HttpResponse<InputStream> response = HTTP.send(request(uri), HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream input = response.body()) {
                if (response.statusCode() >= 300 && response.statusCode() < 400) {
                    String location = response.headers().firstValue("Location").orElse("");
                    if (location.isBlank()) throw new IOException("Page redirect has no location");
                    uri = uri.resolve(location);
                    continue;
                }
                if (response.statusCode() != 200)
                    throw new IOException("Page returned HTTP " + response.statusCode());
                byte[] data = input.readNBytes(4_000_001);
                if (data.length > 4_000_000) throw new IOException("Share page is too large");
                return new String(data, StandardCharsets.UTF_8);
            }
        }
        throw new IOException("Too many page redirects");
    }

    private static void downloadMega(String url, Path target, GameBananaInstaller.Progress progress)
            throws IOException, InterruptedException {
        URI shared = URI.create(url);
        String path = shared.getPath();
        String id = path != null && path.startsWith("/file/")
                ? path.substring("/file/".length()).split("/", 2)[0] : "";
        String keyPart = shared.getFragment();
        if (id.isEmpty() && keyPart != null && keyPart.startsWith("!")) {
            String[] legacy = keyPart.substring(1).split("!", 2);
            if (legacy.length == 2) { id = legacy[0]; keyPart = legacy[1]; }
        }
        if (!id.matches("[A-Za-z0-9_-]{6,24}") || keyPart == null)
            throw new IOException("MEGA needs a public file link with its decryption key");
        byte[] key;
        try { key = Base64.getUrlDecoder().decode(keyPart); }
        catch (IllegalArgumentException error) { throw new IOException("Invalid MEGA key", error); }
        if (key.length != 32) throw new IOException("Invalid MEGA key length");
        String api = "https://g.api.mega.co.nz/cs?id=" + System.nanoTime();
        HttpRequest request = HttpRequest.newBuilder(URI.create(api)).timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("[{\"a\":\"g\",\"g\":1,\"p\":\"" + id + "\"}]"))
                .build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) throw new IOException("MEGA returned HTTP " + response.statusCode());
        try {
            JsonObject data = JsonParser.parseString(response.body()).getAsJsonArray().get(0).getAsJsonObject();
            long size = data.get("s").getAsLong();
            if (size < 0 || size > GameBananaInstaller.MAX_DOWNLOAD)
                throw new IOException("MEGA file exceeds 1.5 GB limit");
            byte[] aesKey = Arrays.copyOf(key, 16);
            for (int i = 0; i < 16; i++) aesKey[i] ^= key[i + 16];
            byte[] iv = new byte[16];
            System.arraycopy(key, 16, iv, 0, 8);
            Cipher cipher = Cipher.getInstance("AES/CTR/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(aesKey, "AES"), new IvParameterSpec(iv));
            transfer(URI.create(data.get("g").getAsString()), target, progress,
                    ModDownloadLinks.Host.MEGA, cipher, size);
        } catch (IOException error) {
            throw error;
        } catch (Exception error) {
            throw new IOException("Could not read or decrypt the MEGA file", error);
        }
    }

    private static void transfer(URI start, Path target, GameBananaInstaller.Progress progress,
                                 ModDownloadLinks.Host host, Cipher decrypt, long expected)
            throws IOException, InterruptedException {
        URI uri = start;
        for (int redirects = 0; redirects <= 5; redirects++) {
            if (!trusted(uri, host)) throw new IOException("Download redirected to an untrusted host");
            HttpResponse<InputStream> response = HTTP.send(request(uri), HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream raw = response.body()) {
                if (response.statusCode() >= 300 && response.statusCode() < 400) {
                    String location = response.headers().firstValue("Location").orElse("");
                    if (location.isBlank()) throw new IOException("Download redirect has no location");
                    uri = uri.resolve(location);
                    continue;
                }
                if (response.statusCode() != 200)
                    throw new IOException("Download returned HTTP " + response.statusCode());
                String contentType = response.headers().firstValue("Content-Type").orElse("");
                if (contentType.toLowerCase(java.util.Locale.ROOT).contains("text/html"))
                    throw new IOException("Host returned a web page, not a downloadable archive");
                long length = response.headers().firstValueAsLong("Content-Length").orElse(expected);
                if (length > GameBananaInstaller.MAX_DOWNLOAD)
                    throw new IOException("Download exceeds 1.5 GB limit");
                try (InputStream input = decrypt == null ? raw : new CipherInputStream(raw, decrypt);
                     var output = Files.newOutputStream(target)) {
                    byte[] buffer = new byte[64 * 1024];
                    long count = 0;
                    int read;
                    while ((read = input.read(buffer)) != -1) {
                        progress.update("Downloading", count, length);
                        count += read;
                        if (count > GameBananaInstaller.MAX_DOWNLOAD)
                            throw new IOException("Download exceeds 1.5 GB limit");
                        output.write(buffer, 0, read);
                    }
                    if (expected >= 0 && count != expected)
                        throw new IOException("Download was incomplete");
                    progress.update("Downloading", count, count);
                }
                return;
            }
        }
        throw new IOException("Too many download redirects");
    }

    private static HttpRequest request(URI uri) {
        return HttpRequest.newBuilder(uri).timeout(Duration.ofMinutes(15))
                .header("User-Agent", "Mozilla/5.0 BlockifiedEngine/2.5")
                .GET().build();
    }

    private static boolean trusted(URI uri, ModDownloadLinks.Host source) {
        String host = uri.getHost();
        if (!"https".equalsIgnoreCase(uri.getScheme()) || host == null
                || uri.getRawUserInfo() != null || uri.getPort() != -1 && uri.getPort() != 443) return false;
        host = host.toLowerCase(Locale.ROOT);
        return switch (source) {
            case GOOGLE_DRIVE -> host.equals("drive.google.com")
                    || host.equals("drive.usercontent.google.com")
                    || host.endsWith(".googleusercontent.com");
            case MEDIAFIRE -> host.equals("mediafire.com") || host.endsWith(".mediafire.com");
            case MEGA -> host.equals("mega.nz") || host.endsWith(".mega.nz")
                    || host.equals("mega.co.nz") || host.endsWith(".mega.co.nz");
            default -> false;
        };
    }
}
