package com.fnfmod.gameplay;

import com.fnfmod.song.SongEntry;
import com.fnfmod.song.SongLibrary;

import java.nio.file.Path;

/**
 * Resolved policy for one song run. The server resolves whether Minecraft mode
 * may use the song's assets and sends that decision to every participant.
 */
public record PlaybackPolicy(PlaybackMode mode, boolean songAssets, boolean luaAllowed) {

    public PlaybackPolicy(PlaybackMode mode, boolean songAssets) {
        this(mode, songAssets, true);
    }

    public static PlaybackPolicy resolve(PlaybackMode mode, SongEntry entry) {
        PlaybackMode resolved = mode == null ? PlaybackMode.MINECRAFT : mode;
        boolean assets = entry != null && entry.fullModLayout
                && (resolved != PlaybackMode.MINECRAFT
                || isInstalledModSong(entry));
        return new PlaybackPolicy(resolved, assets, true);
    }

    public boolean allows(SongEntry entry, SongLibrary.ExternalContent content) {
        if (entry != null && !entry.allows(content)) return false;
        if (content == SongLibrary.ExternalContent.LUA && !luaAllowed) return false;
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

    /**
     * Minecraft presentation accepts rich song resources when the song belongs to
     * config/fnfmod/mods/&lt;mod&gt;. A chart-only override saved under
     * config/fnfmod/songs also qualifies when its original_directory.txt names an
     * installed mod, so an edited chart keeps that mod's characters and animations.
     * An origin outside config/fnfmod/mods stays lightweight.
     */
    private static boolean isInstalledModSong(SongEntry entry) {
        if (entry == null) return false;
        Path mods = SongLibrary.modsDir().toAbsolutePath().normalize();
        Path songs = SongLibrary.songsDir().toAbsolutePath().normalize();
        if (inside(entry.chartOriginRoot, mods)) return true;
        return inside(entry.modRoot, mods)
                && inside(entry.folder, entry.modRoot)
                && !inside(entry.folder, songs);
    }

    private static boolean inside(Path path, Path root) {
        return path != null && path.toAbsolutePath().normalize().startsWith(root);
    }
}
