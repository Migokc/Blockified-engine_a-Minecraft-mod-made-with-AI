package com.fnfmod.client.gameplay;

import com.fnfmod.gameplay.PlaybackMode;
import com.fnfmod.gameplay.PlaybackPolicy;
import com.fnfmod.song.ModMinecraftVisualSettings;
import com.fnfmod.song.SongEntry;
import com.fnfmod.song.SongLibrary;
import com.fnfmod.world.ModContentScope;

import java.nio.file.Path;

/** Per-mod Minecraft-look gate for images and fonts; normal pack permissions still apply. */
public final class GameBananaVisualPolicy {
    private GameBananaVisualPolicy() {}

    public static boolean blocks(SongEntry entry, PlaybackPolicy policy,
                                 SongLibrary.ExternalContent content) {
        if (entry == null || policy == null || policy.mode() != PlaybackMode.MINECRAFT) return false;
        if (content != SongLibrary.ExternalContent.IMAGES
                && content != SongLibrary.ExternalContent.FONTS) return false;
        return blocks(entry, policy, content, ModMinecraftVisualSettings.live());
    }

    static boolean blocks(SongEntry entry, PlaybackPolicy policy,
                          SongLibrary.ExternalContent content, ModMinecraftVisualSettings settings) {
        if (entry == null || policy == null || policy.mode() != PlaybackMode.MINECRAFT) return false;
        if (content != SongLibrary.ExternalContent.IMAGES
                && content != SongLibrary.ExternalContent.FONTS) return false;
        if (ModContentScope.isModWorld()) return false;
        Path pack = entry.modRoot;
        if (pack == null || !entry.fullModLayout) return false;
        return !settings.enabled(pack);
    }
}
