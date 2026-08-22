package com.fnfmod.client;

import com.fnfmod.gameplay.PlaybackMode;
import com.fnfmod.net.FnfPayloads;
import com.fnfmod.song.WeekDefinition;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;

/** Client-side playlist state for a solo Story Mode week. */
public final class ClientStorySession {
    private static WeekDefinition week;
    private static List<FnfPayloads.SongInfo> songs = List.of();
    private static String difficulty = "normal";
    private static PlaybackMode playbackMode = PlaybackMode.MINECRAFT;
    private static int index;

    private ClientStorySession() {}

    public static boolean active() { return week != null; }
    public static String weekId() { return week == null ? "" : week.id(); }
    public static String weekName() { return week == null ? "" : week.displayName(); }
    public static int songIndex() { return active() ? index : -1; }
    public static int songCount() { return songs.size(); }

    public static boolean start(BlockPos pos, WeekDefinition selected, String selectedDifficulty,
                                PlaybackMode selectedPlaybackMode,
                                List<FnfPayloads.SongInfo> available) {
        clear();
        if (pos == null || selected == null) return false;
        List<FnfPayloads.SongInfo> playlist = new ArrayList<>();
        for (WeekDefinition.Song song : selected.songs()) {
            FnfPayloads.SongInfo match = find(available, song.id());
            // Never silently skip an unresolved entry. Doing so could make a
            // week begin at its last song when only that title matched exactly.
            if (match == null) return false;
            playlist.add(match);
        }
        if (playlist.isEmpty()) return false;
        week = selected;
        songs = List.copyOf(playlist);
        difficulty = selectedDifficulty == null || selectedDifficulty.isBlank() ? "normal" : selectedDifficulty;
        playbackMode = selectedPlaybackMode == null ? PlaybackMode.MINECRAFT : selectedPlaybackMode;
        index = 0;
        return play(pos, songs.get(0));
    }

    /** Called after a successfully completed song; true means another song was launched. */
    public static boolean advance(BlockPos pos) {
        if (!active() || index + 1 >= songs.size()) return false;
        index++;
        return play(pos, songs.get(index));
    }

    public static void clear() {
        week = null;
        songs = List.of();
        difficulty = "normal";
        playbackMode = PlaybackMode.MINECRAFT;
        index = 0;
    }

    private static boolean play(BlockPos pos, FnfPayloads.SongInfo song) {
        String chosen = song.difficulties().stream().filter(value -> value.equalsIgnoreCase(difficulty))
                .findFirst().orElseGet(() -> song.difficulties().stream()
                        .filter(value -> value.equalsIgnoreCase("normal")).findFirst()
                        .orElse(song.difficulties().isEmpty() ? "normal" : song.difficulties().get(0)));
        byte playSide = (byte) (ClientOptions.get().playAs % 3);
        ClientSession.pendingPlaySide = playSide;
        ClientSession.pendingPlaybackMode = playbackMode;
        ClientSession.pendingSongExitTarget = FnfPayloads.LeaveC2S.RETURN_SELECTOR;
        PacketDistributor.sendToServer(new FnfPayloads.MachineDirectPlayC2S(
                pos, song.id(), chosen, false, playSide, playbackMode.networkId()));
        Minecraft.getInstance().setScreen(new com.fnfmod.client.gui.WaitingScreen(Component.literal(
                "Story Mode " + (index + 1) + "/" + songs.size() + " — Loading " + song.name() + "...")));
        return true;
    }

    private static FnfPayloads.SongInfo find(List<FnfPayloads.SongInfo> values, String id) {
        if (values == null) return null;
        String wanted = normalizeSongId(id);
        return values.stream().filter(song -> normalizeSongId(song.id()).equals(wanted)
                || normalizeSongId(song.name()).equals(wanted)).findFirst().orElse(null);
    }

    private static String normalizeSongId(String value) {
        return value == null ? "" : value.trim().toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
    }
}
