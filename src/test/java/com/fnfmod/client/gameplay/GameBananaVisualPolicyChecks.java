package com.fnfmod.client.gameplay;

import com.fnfmod.gameplay.PlaybackMode;
import com.fnfmod.gameplay.PlaybackPolicy;
import com.fnfmod.song.GameBananaInstalledMod;
import com.fnfmod.song.ExternalDirectoryConfig;
import com.fnfmod.song.ModMinecraftVisualSettings;
import com.fnfmod.song.SongEntry;
import com.fnfmod.song.SongLibrary;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

/** Local-only checks for per-pack Minecraft-look art and font gating. */
public final class GameBananaVisualPolicyChecks {
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("blockified-gamebanana-policy-");
        try {
            require(PlaybackMode.MINECRAFT.next(false) == PlaybackMode.FNF
                            && PlaybackMode.FNF.next(false) == PlaybackMode.MINECRAFT,
                    "the look picker must cycle through only Minecraft and FNF");
            require(PlaybackMode.fromNetworkId((byte) 2) == PlaybackMode.MINECRAFT
                            && PlaybackMode.parse("legacy") == PlaybackMode.MINECRAFT,
                    "removed Legacy look must fall back safely");
            Path installedRoot = Files.createDirectories(root.resolve("installed"));
            ModMinecraftVisualSettings settings = new ModMinecraftVisualSettings(
                    root.resolve("config"), installedRoot);
            Path downloaded = Files.createDirectories(installedRoot.resolve("downloaded"));
            Files.writeString(downloaded.resolve("mod_url.txt"), "https://gamebanana.com/mods/123\n");
            GameBananaInstalledMod.mark(downloaded, "123");
            require(!settings.configuredEnabled(downloaded),
                    "GameBanana mod with URL must show X by default");
            SongEntry entry = new SongEntry();
            entry.modRoot = downloaded;
            entry.fullModLayout = true;
            entry.folder = Files.createDirectories(downloaded.resolve("songs/test"));
            require(GameBananaInstalledMod.isDownloaded(entry), "download marker was not detected");

            PlaybackPolicy minecraft = new PlaybackPolicy(PlaybackMode.MINECRAFT, true);
            PlaybackPolicy fnf = new PlaybackPolicy(PlaybackMode.FNF, true);
            require(GameBananaVisualPolicy.blocks(entry, minecraft, SongLibrary.ExternalContent.IMAGES, settings),
                    "download images should be blocked in Minecraft look by default");
            require(GameBananaVisualPolicy.blocks(entry, minecraft, SongLibrary.ExternalContent.FONTS, settings),
                    "download fonts should be blocked in Minecraft look by default");
            settings.setEnabled(downloaded, true);
            require(!GameBananaVisualPolicy.blocks(entry, minecraft, SongLibrary.ExternalContent.IMAGES, settings),
                    "Minecraft toggle did not allow images");
            settings.setEnabled(downloaded, false);
            require(!GameBananaVisualPolicy.blocks(entry, fnf, SongLibrary.ExternalContent.IMAGES, settings),
                    "FNF look must ignore the Minecraft toggle");
            require(!GameBananaVisualPolicy.blocks(entry, minecraft, SongLibrary.ExternalContent.ICONS, settings),
                    "Minecraft toggle should not hide icons");

            Path second = Files.createDirectories(installedRoot.resolve("second"));
            Files.writeString(second.resolve("mod_url.txt"), "https://gamebanana.com/mods/456\n");
            GameBananaInstalledMod.mark(second, "456");
            settings.setEnabled(second, true);
            require(!settings.enabled(downloaded),
                    "enabling one pack changed the other pack");
            require(settings.enabled(second),
                    "second pack setting was not saved");

            Path manual = Files.createDirectories(installedRoot.resolve("manual"));
            Files.writeString(manual.resolve("mod_url.txt"), "https://gamebanana.com/mods/123\n");
            entry.modRoot = manual;
            entry.folder = Files.createDirectories(manual.resolve("songs/test"));
            require(!GameBananaInstalledMod.isDownloaded(entry), "mod_url alone must not mark manual installs");
            require(GameBananaVisualPolicy.blocks(entry, minecraft, SongLibrary.ExternalContent.IMAGES, settings),
                    "manual installs with URL must default to off");
            Path withoutUrl = Files.createDirectories(installedRoot.resolve("manual-without-url"));
            entry.modRoot = withoutUrl;
            entry.folder = Files.createDirectories(withoutUrl.resolve("songs/test"));
            require(!GameBananaVisualPolicy.blocks(entry, minecraft, SongLibrary.ExternalContent.IMAGES, settings),
                    "manual installs without URL must default to on");
            settings.setEnabled(withoutUrl, false);
            require(GameBananaVisualPolicy.blocks(entry, minecraft, SongLibrary.ExternalContent.IMAGES, settings),
                    "per-mod toggle did not disable images");
            Path external = Files.createDirectories(root.resolve("external-mods/mod-without-url"));
            require(!settings.configuredEnabled(external),
                    "external dirs/mods must show X by default");
            entry.modRoot = external;
            entry.folder = Files.createDirectories(external.resolve("songs/test"));
            require(GameBananaVisualPolicy.blocks(entry, minecraft, SongLibrary.ExternalContent.IMAGES, settings),
                    "external mods without URL must default to off");
            settings.setEnabled(external, true);
            require(!GameBananaVisualPolicy.blocks(entry, minecraft, SongLibrary.ExternalContent.IMAGES, settings),
                    "an external mod could not be enabled individually");
            require(new ModMinecraftVisualSettings(root.resolve("config"), installedRoot).enabled(second),
                    "per-mod toggle was not persisted");
            ExternalDirectoryConfig filters = new ExternalDirectoryConfig(root.resolve("config"));
            filters.setContent("external-source", SongLibrary.ExternalContent.IMAGES, false);
            require(new ModMinecraftVisualSettings(root.resolve("config"), installedRoot).enabled(second),
                    "editing a content filter erased the Minecraft visual switch");
            settings.setEnabled(external, false);
            require(!filters.content("external-source").contains(SongLibrary.ExternalContent.IMAGES),
                    "editing a Minecraft visual switch erased a content filter");

            Path oldConfig = Files.createDirectories(root.resolve("old-config"));
            Path oldFile = oldConfig.resolve("mod_minecraft_visuals.json");
            com.google.gson.JsonObject oldValues = new com.google.gson.JsonObject();
            oldValues.addProperty(external.toAbsolutePath().normalize().toString(), true);
            Files.writeString(oldFile, oldValues.toString());
            ModMinecraftVisualSettings migrated = new ModMinecraftVisualSettings(oldConfig, installedRoot);
            require(migrated.enabled(external), "old visual choice was not migrated");
            require(!Files.exists(oldFile), "old active visual settings file was not archived");
            require(Boolean.TRUE.equals(new ExternalDirectoryConfig(oldConfig).minecraftVisualOverrides()
                            .get(external.toAbsolutePath().normalize().toString())),
                    "migrated choice was not saved in external_folder_filters.json");
            System.out.println("GameBanana visual policy checks passed.");
        } finally {
            try (var files = Files.walk(root)) {
                for (Path file : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(file);
            }
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
