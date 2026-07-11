package com.fnfmod.client;

import com.fnfmod.FnfMod;
import com.fnfmod.song.SongLibrary;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/** Per-song, difficulty, and play-mode best scores. Client-side / personal. */
public final class ScoreStore {

    private static final String[] MODE_KEYS = {"player", "opponent", "both"};
    private static final String[] MODE_NAMES = {"Player", "Opponent", "Both"};

    public static final class Record {
        public int score;
        public int maxCombo;
        public int sick;
        public int good;
        public int bad;
        public int shit;
        public int missed;
        public int totalNotes;

        public int hitNotes() {
            return sick + good + bad + shit;
        }

        public float accuracy() {
            int judged = hitNotes() + missed;
            if (judged == 0) return 1f;
            return (sick * 1f + good * 0.67f + bad * 0.34f) / judged;
        }
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static Map<String, Record> records;

    private ScoreStore() {}

    /** For manual edits to scores.json. */
    public static synchronized void reload() {
        records = null;
        map();
    }

    private static Path file() {
        return SongLibrary.root().resolve("scores.json");
    }

    private static int normalizeMode(int playMode) {
        return Math.max(0, Math.min(MODE_KEYS.length - 1, playMode));
    }

    private static String key(String songId, String difficulty, int playMode) {
        return songId + "|" + difficulty + "|" + MODE_KEYS[normalizeMode(playMode)];
    }

    private static String legacyKey(String songId, String difficulty) {
        return songId + "|" + difficulty;
    }

    public static String modeName(int playMode) {
        return MODE_NAMES[normalizeMode(playMode)];
    }

    private static synchronized Map<String, Record> map() {
        if (records == null) {
            records = new HashMap<>();
            try {
                Path f = file();
                if (Files.isRegularFile(f)) {
                    Map<String, Record> loaded = GSON.fromJson(Files.readString(f),
                            new TypeToken<HashMap<String, Record>>() {}.getType());
                    if (loaded != null) records = loaded;
                }
            } catch (Exception e) {
                FnfMod.LOGGER.warn("Could not read scores.json: {}", e.toString());
            }
        }
        return records;
    }

    public static Record get(String songId, String difficulty, int playMode) {
        Record record = map().get(key(songId, difficulty, playMode));
        // Scores saved before play-mode separation belonged to normal Player mode.
        if (record == null && normalizeMode(playMode) == 0) {
            record = map().get(legacyKey(songId, difficulty));
        }
        return record;
    }

    /** Saves the record if it beats this song+difficulty+mode's stored score. */
    public static void submit(String songId, String difficulty, int playMode, Record r) {
        Record existing = get(songId, difficulty, playMode);
        if (existing != null && existing.score >= r.score) return;
        map().put(key(songId, difficulty, playMode), r);
        try {
            Files.createDirectories(file().getParent());
            Files.writeString(file(), GSON.toJson(map()));
        } catch (Exception e) {
            FnfMod.LOGGER.warn("Could not save scores.json: {}", e.toString());
        }
    }
}
