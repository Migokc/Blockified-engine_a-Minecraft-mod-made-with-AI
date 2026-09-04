package com.fnfmod.client.gui.editor;

import com.fnfmod.FnfMod;
import com.fnfmod.client.ClientOptions;
import com.fnfmod.song.SongLibrary;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Per-song chart editor preferences: the Chart tab's editor-only options.
 *
 * <p>These describe how one chart is being worked on — its charting offset, snap, metronome,
 * loop region and stem mix — so every song keeps its own set instead of sharing the user
 * options, where a value tuned for one song silently applied to every other one. All songs live
 * in one file, {@code config/fnfmod/chart-editor.json}, keyed by song name. Playback rate and
 * Tap BPM stay transient, since they are momentary tools rather than settings.</p>
 */
public final class ChartEditorPrefs {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public double chartingOffsetMs;
    public int snapIndex = 3;
    public boolean vortex;
    public boolean hitsoundPlayer;
    public boolean hitsoundOpponent;
    public boolean metronome;
    public double metronomeVolume = 0.75;
    public boolean loopEnabled;
    public double loopStartMs = -1;
    public double loopEndMs = -1;
    public int preRollBeats = 1;
    public int instMix, playerMix, opponentMix;

    /** Independent copy, used by the editor's undo history. */
    public ChartEditorPrefs copy() {
        ChartEditorPrefs copy = new ChartEditorPrefs();
        copy.chartingOffsetMs = chartingOffsetMs;
        copy.snapIndex = snapIndex;
        copy.vortex = vortex;
        copy.hitsoundPlayer = hitsoundPlayer;
        copy.hitsoundOpponent = hitsoundOpponent;
        copy.metronome = metronome;
        copy.metronomeVolume = metronomeVolume;
        copy.loopEnabled = loopEnabled;
        copy.loopStartMs = loopStartMs;
        copy.loopEndMs = loopEndMs;
        copy.preRollBeats = preRollBeats;
        copy.instMix = instMix;
        copy.playerMix = playerMix;
        copy.opponentMix = opponentMix;
        return copy;
    }

    /** The single file holding every song's editor options, keyed by song. */
    public static Path file() {
        return SongLibrary.root().resolve("chart-editor.json");
    }

    /** Key a song is filed under. Kept stable and lowercase so casing changes still match. */
    private static String key(String songId) {
        String trimmed = songId == null ? "" : songId.trim();
        return trimmed.isEmpty() ? "untitled" : trimmed.toLowerCase(Locale.ROOT);
    }

    /** Every song's saved options, or an empty object when nothing has been stored yet. */
    private static JsonObject readAll() {
        Path file = file();
        if (!Files.isRegularFile(file)) return new JsonObject();
        try {
            JsonElement parsed = JsonParser.parseString(Files.readString(file));
            return parsed.isJsonObject() ? parsed.getAsJsonObject() : new JsonObject();
        } catch (Exception error) {
            FnfMod.LOGGER.warn("Bad chart editor prefs {}: {}", file, error.toString());
            return new JsonObject();
        }
    }

    /**
     * Loads one song's editor preferences. A song opened for the first time inherits the values
     * that used to live in the user options, so existing setups carry over once and then
     * become per-song.
     */
    public static ChartEditorPrefs load(String songId) {
        JsonObject all = readAll();
        JsonElement entry = all.get(key(songId));
        if (entry != null && entry.isJsonObject()) {
            try {
                ChartEditorPrefs loaded = GSON.fromJson(entry, ChartEditorPrefs.class);
                if (loaded != null) return loaded;
            } catch (Exception error) {
                FnfMod.LOGGER.warn("Bad chart editor prefs for {}: {}", songId, error.toString());
            }
        }
        return fromUserOptions();
    }

    private static ChartEditorPrefs fromUserOptions() {
        ClientOptions options = ClientOptions.get();
        ChartEditorPrefs prefs = new ChartEditorPrefs();
        prefs.chartingOffsetMs = options.editorChartingOffsetMs;
        prefs.hitsoundPlayer = options.editorHitsoundPlayer;
        prefs.hitsoundOpponent = options.editorHitsoundOpponent;
        prefs.metronome = options.editorMetronome;
        prefs.metronomeVolume = options.editorMetronomeVolume;
        return prefs;
    }

    /**
     * Writes this song's preferences into the shared file, leaving every other song's entry
     * untouched. Failures are logged; editing continues either way.
     */
    public void save(String songId) {
        Path file = file();
        try {
            JsonObject all = readAll();
            all.add(key(songId), GSON.toJsonTree(this));
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(all));
        } catch (Exception error) {
            FnfMod.LOGGER.warn("Could not save chart editor prefs {}: {}", file, error.toString());
        }
    }
}
