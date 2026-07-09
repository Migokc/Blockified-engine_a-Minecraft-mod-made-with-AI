package com.fnfmod.chart;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Parses legacy FNF ("week 7" era) and Psych Engine charts. Both share the
 * {"song": {...}} wrapper; Psych adds note types (4th sectionNotes element),
 * gfSection, sectionBeats, events (ignored here), etc.
 */
public final class LegacyChartParser {

    /** Psych numeric note types (index into this list when the 4th element is a number). */
    private static final String[] PSYCH_NOTE_TYPES = {
            "", "Alt Animation", "Hey!", "Hurt Note", "GF Sing", "No Animation"
    };

    private LegacyChartParser() {}

    public static boolean looksLikeLegacy(JsonObject root) {
        if (root.has("song") && root.get("song").isJsonObject()) return true;
        // unwrapped charts (Psych 1.0 "psych_v1" and some exports): song data sits at the root
        return root.has("notes") && root.get("notes").isJsonArray() && root.has("bpm");
    }

    public static SongChart parse(String json) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        JsonObject song = root.has("song") && root.get("song").isJsonObject()
                ? root.getAsJsonObject("song") : root;

        SongChart chart = new SongChart();
        chart.title = optString(song, "song", "Unknown");
        chart.startBpm = optDouble(song, "bpm", 120);
        chart.speed = optDouble(song, "speed", 1.0);
        chart.offsetMs = optDouble(song, "offset", 0);
        chart.needsVoices = optBool(song, "needsVoices", true);
        chart.player1 = optString(song, "player1", "bf");
        chart.player2 = optString(song, "player2", "dad");

        // Psych Engine 1.0+ ("psych_v1", "psych_v1_convert") stores lanes absolutely:
        // 0-3 = player, 4-7 = opponent. Older charts are mustHitSection-relative.
        boolean absoluteLanes = optString(song, "format", "").startsWith("psych_v1");

        double bpm = chart.startBpm;
        double sectionStart = 0;

        JsonArray sections = song.has("notes") && song.get("notes").isJsonArray()
                ? song.getAsJsonArray("notes") : new JsonArray();

        for (JsonElement secEl : sections) {
            if (!secEl.isJsonObject()) continue;
            JsonObject sec = secEl.getAsJsonObject();

            SongChart.Section section = new SongChart.Section();
            section.mustHit = optBool(sec, "mustHitSection", true);
            section.altAnim = optBool(sec, "altAnim", false);
            section.gfSection = optBool(sec, "gfSection", false);
            section.changeBPM = optBool(sec, "changeBPM", false);
            section.bpm = optDouble(sec, "bpm", 0);
            if (sec.has("sectionBeats")) {
                section.sectionBeats = optDouble(sec, "sectionBeats", 4);
            } else {
                section.sectionBeats = optDouble(sec, "lengthInSteps", 16) / 4.0;
            }
            if (section.sectionBeats <= 0) section.sectionBeats = 4;
            section.camEase = optString(sec, "fnfmodCamEase", "smooth");
            chart.sections.add(section);

            if (section.changeBPM && section.bpm > 0) {
                bpm = section.bpm;
                chart.bpmChanges.add(new SongChart.BpmChange(sectionStart, bpm));
            }

            if (sec.has("sectionNotes") && sec.get("sectionNotes").isJsonArray()) {
                for (JsonElement noteEl : sec.getAsJsonArray("sectionNotes")) {
                    if (!noteEl.isJsonArray()) continue;
                    JsonArray n = noteEl.getAsJsonArray();
                    if (n.size() < 2) continue;
                    double time = n.get(0).getAsDouble();
                    int data;
                    try {
                        data = n.get(1).getAsInt();
                    } catch (Exception e) {
                        continue;
                    }
                    // Psych event notes use data == -1 inside sectionNotes (old format); skip.
                    if (data < 0 || data > 7) continue;

                    double sustain = n.size() > 2 && n.get(2).isJsonPrimitive()
                            ? Math.max(0, n.get(2).getAsDouble()) : 0;

                    String type = "";
                    if (n.size() > 3 && n.get(3).isJsonPrimitive()) {
                        var p = n.get(3).getAsJsonPrimitive();
                        if (p.isString()) {
                            type = p.getAsString();
                        } else if (p.isNumber()) {
                            int idx = p.getAsInt();
                            if (idx >= 0 && idx < PSYCH_NOTE_TYPES.length) type = PSYCH_NOTE_TYPES[idx];
                        } else if (p.isBoolean() && p.getAsBoolean()) {
                            type = "Alt Animation";
                        }
                    }

                    int lane = data % 4;
                    boolean playerSide = absoluteLanes ? data < 4 : (data < 4) == section.mustHit;
                    SongChart.Note note = new SongChart.Note(time, lane, playerSide, sustain, type);
                    note.altAnim = section.altAnim || "Alt Animation".equals(type);
                    chart.notes.add(note);
                }
            }

            sectionStart += section.sectionBeats * (60000.0 / bpm);
        }

        if (chart.bpmChanges.isEmpty() || chart.bpmChanges.get(0).timeMs > 0) {
            chart.bpmChanges.add(0, new SongChart.BpmChange(0, chart.startBpm));
        }
        chart.sortNotes();
        return chart;
    }

    public static String optString(JsonObject o, String key, String def) {
        try {
            return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : def;
        } catch (Exception e) {
            return def;
        }
    }

    public static double optDouble(JsonObject o, String key, double def) {
        try {
            return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsDouble() : def;
        } catch (Exception e) {
            return def;
        }
    }

    public static boolean optBool(JsonObject o, String key, boolean def) {
        try {
            if (!o.has(key) || !o.get(key).isJsonPrimitive()) return def;
            var p = o.get(key).getAsJsonPrimitive();
            if (p.isBoolean()) return p.getAsBoolean();
            if (p.isNumber()) return p.getAsInt() != 0;
            return Boolean.parseBoolean(p.getAsString());
        } catch (Exception e) {
            return def;
        }
    }
}
