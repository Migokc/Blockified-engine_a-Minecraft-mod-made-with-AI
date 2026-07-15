package com.fnfmod.song;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Creates an empty Psych-compatible pack with Blockified-specific extension folders. */
public final class ModTemplateService {
    private static final List<String> ROOT_FOLDERS = List.of(
            "animations",          // Blockified playerAnimator character definitions
            "characters",
            "custom_events",
            "custom_notetypes",
            "data",
            "fonts",
            "images",
            "images/icons",
            "music",
            "scripts",
            "shaders",
            "songs",
            "sounds",
            "stages",
            "videos",
            "weeks"
    );

    private ModTemplateService() {}

    public static void create(Path modRoot, String songId) throws IOException {
        Files.createDirectories(modRoot);
        for (String folder : ROOT_FOLDERS) Files.createDirectories(modRoot.resolve(folder));
        Files.createDirectories(modRoot.resolve("data").resolve(songId));
        Files.createDirectories(modRoot.resolve("songs").resolve(songId));
    }
}
