package com.fnfmod.gameplay;

import com.fnfmod.song.SongEntry;
import com.fnfmod.song.SongLibrary;

import java.nio.file.Path;

/**
 * Resolved policy for one song run. The server resolves whether Minecraft mode
 * may use the song's assets and sends that decision to every participant.
 */
public record PlaybackPolicy(PlaybackMode mode, boolean songAssets) {

    public static PlaybackPolicy resolve(PlaybackMode mode, SongEntry entry) {
        PlaybackMode resolved = mode == null ? PlaybackMode.LEGACY : mode;
        boolean assets = entry != null && entry.fullModLayout
                && (resolved != PlaybackMode.MINECRAFT || isInstalledMod(entry));
        return new PlaybackPolicy(resolved, assets);
    }

    public boolean allows(SongEntry entry, SongLibrary.ExternalContent content) {
        if (entry != null && !entry.allows(content)) return false;
        if (songAssets) return true;
        return content == SongLibrary.ExternalContent.LUA
                || content == SongLibrary.ExternalContent.EVENTS
                || content == SongLibrary.ExternalContent.CHARTS
                || content == SongLibrary.ExternalContent.AUDIO;
    }

    public boolean usesPsychCamera() {
        return mode == PlaybackMode.FNF;
    }

    public boolean forcesFnfHud() {
        return mode == PlaybackMode.FNF;
    }

    private static boolean isInstalledMod(SongEntry entry) {
        if (entry == null) return false;
        Path mods = SongLibrary.modsDir().toAbsolutePath().normalize();
        return inside(entry.modRoot, mods);
    }

    private static boolean inside(Path path, Path root) {
        return path != null && path.toAbsolutePath().normalize().startsWith(root);
    }
}
