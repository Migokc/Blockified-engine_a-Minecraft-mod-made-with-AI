package com.fnfmod.song;

import java.nio.file.Path;
import java.util.List;

/** Parsed Psych Engine week metadata plus its resolved local presentation assets. */
public record WeekDefinition(
        String id,
        String storyName,
        String weekName,
        List<Song> songs,
        List<String> difficulties,
        List<String> characters,
        String background,
        String weekBefore,
        boolean startUnlocked,
        boolean hiddenUntilUnlocked,
        boolean hideStoryMode,
        boolean hideFreeplay,
        Path root,
        Path jsonFile,
        Path imageFile
) {
    public record Song(String id, String icon, int color) {}

    public String displayName() {
        if (storyName != null && !storyName.isBlank()) return storyName;
        if (weekName != null && !weekName.isBlank()) return weekName;
        return id;
    }
}
