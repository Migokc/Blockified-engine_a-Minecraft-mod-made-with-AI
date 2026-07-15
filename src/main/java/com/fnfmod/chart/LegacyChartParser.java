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
        chart.player3 = optString(song, "gfVersion", optString(song, "player3", "gf"));
        chart.stage = optString(song, "stage", "stage");
        chart.noteTexture = optString(song, "arrowSkin", optString(song, "noteTexture", ""));
        chart.noteSplashTexture = optString(song, "splashSkin",
                optString(song, "noteSplashTexture", ""));

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
                    Double timeValue = number(n.get(0));
                    Integer dataValue = integer(n.get(1));
                    if (timeValue == null || dataValue == null) continue;
                    double time = timeValue;
                    int data = dataValue;
                    // Psych event notes use data == -1 inside sectionNotes (old format); skip.
                    if (data < 0 || data > 7) continue;

                    Double sustainValue = n.size() > 2 ? number(n.get(2)) : Double.valueOf(0.0);
                    // Some event exporters use a note-shaped row whose third item
                    // is the event name. It is not a malformed sustain note.
                    if (sustainValue == null) continue;
                    double sustain = Math.max(0, sustainValue);

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
                    // Psych section alt animations affect opponent-side notes only.
                    note.altAnim = (section.altAnim && !playerSide) || "Alt Animation".equals(type);
                    note.animSuffix = note.altAnim ? "-alt" : "";
                    // gfSection redirects notes belonging to the section's focused side.
                    note.gfNote = section.gfSection && playerSide == section.mustHit;
                    chart.notes.add(note);
                }
            }

            sectionStart += section.sectionBeats * (60000.0 / bpm);
        }

        if (chart.bpmChanges.isEmpty() || chart.bpmChanges.get(0).timeMs > 0) {
            chart.bpmChanges.add(0, new SongChart.BpmChange(0, chart.startBpm));
        }
        chart.events.addAll(parseEvents(json));
        chart.sortEvents();
        chart.sortNotes();
        return chart;
    }

    /** Extracts Psych events without attempting to interpret them as gameplay notes. */
    public static java.util.List<SongChart.Event> parseEvents(String json) {
        java.util.List<SongChart.Event> out = new java.util.ArrayList<>();
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            JsonObject song = root.has("song") && root.get("song").isJsonObject()
                    ? root.getAsJsonObject("song") : root;

            if (song.has("events")) parseEventContainer(song.get("events"), out);
            if (root != song && root.has("events")) parseEventContainer(root.get("events"), out);

            JsonArray sections = song.has("notes") && song.get("notes").isJsonArray()
                    ? song.getAsJsonArray("notes") : new JsonArray();
            for (JsonElement sectionEl : sections) {
                if (!sectionEl.isJsonObject()) continue;
                JsonObject section = sectionEl.getAsJsonObject();
                if (!section.has("sectionNotes") || !section.get("sectionNotes").isJsonArray()) continue;
                for (JsonElement rowEl : section.getAsJsonArray("sectionNotes")) {
                    if (!rowEl.isJsonArray()) continue;
                    JsonArray row = rowEl.getAsJsonArray();
                    if (row.size() < 2) continue;
                    Double time = number(row.get(0));
                    Integer data = integer(row.get(1));
                    if (time == null) continue;
                    if (data != null && data < 0) {
                        if (row.size() > 2) parseEventPayload(time, row.get(2), out);
                        if (row.size() > 2 && row.get(2).isJsonPrimitive()) addDirectEvent(time, row, 2, out);
                    } else if (data != null && row.size() > 2 && number(row.get(2)) == null) {
                        // Nonstandard but common: [time, lane, "Event Name", value1, value2].
                        addDirectEvent(time, row, 2, out);
                    } else if (data == null && row.get(1).isJsonPrimitive()) {
                        addDirectEvent(time, row, 1, out);
                    }
                }
            }
        } catch (Exception ignored) {}
        out.sort(java.util.Comparator.comparingDouble(e -> e.timeMs));
        return out;
    }

    private static void parseEventContainer(JsonElement element, java.util.List<SongChart.Event> out) {
        if (element == null || element.isJsonNull()) return;
        if (element.isJsonObject()) {
            JsonObject object = element.getAsJsonObject();
            // V-Slice/per-difficulty containers and object-form Codename events.
            if (object.has("time") || object.has("t") || object.has("strumTime")) {
                double time = optDouble(object, "time", optDouble(object, "t", optDouble(object, "strumTime", -1)));
                String name = optString(object, "name", optString(object, "event", optString(object, "type", "")));
                String value1 = optString(object, "value1", "");
                String value2 = optString(object, "value2", "");
                boolean beforeSong = "load".equalsIgnoreCase(optString(object, "trigger", ""));
                if (object.has("params") && object.get("params").isJsonArray()) {
                    JsonArray params = object.getAsJsonArray("params");
                    if (!params.isEmpty()) value1 = text(params.get(0));
                    if (params.size() > 1) value2 = text(params.get(1));
                }
                addEvent(out, time, name, value1, value2, beforeSong);
            } else {
                for (var child : object.entrySet()) parseEventContainer(child.getValue(), out);
            }
            return;
        }
        if (!element.isJsonArray()) return;
        JsonArray array = element.getAsJsonArray();
        for (JsonElement rowEl : array) {
            if (rowEl.isJsonObject()) {
                parseEventContainer(rowEl, out);
                continue;
            }
            if (!rowEl.isJsonArray()) continue;
            JsonArray row = rowEl.getAsJsonArray();
            if (row.isEmpty()) continue;
            Double time = number(row.get(0));
            if (time == null) continue;
            if (row.size() > 1) {
                JsonElement payload = row.get(1);
                if (payload.isJsonArray()) parseEventPayload(time, payload, out);
                else if (payload.isJsonPrimitive()) addDirectEvent(time, row, 1, out);
            }
        }
    }

    private static void parseEventPayload(double time, JsonElement payload, java.util.List<SongChart.Event> out) {
        if (!payload.isJsonArray()) return;
        JsonArray array = payload.getAsJsonArray();
        if (!array.isEmpty() && array.get(0).isJsonArray()) {
            for (JsonElement event : array) parseEventPayload(time, event, out);
            return;
        }
        if (!array.isEmpty()) addDirectEvent(time, array, 0, out);
    }

    private static void addDirectEvent(double time, JsonArray row, int nameIndex,
                                       java.util.List<SongChart.Event> out) {
        String name = row.size() > nameIndex ? text(row.get(nameIndex)) : "";
        String value1 = row.size() > nameIndex + 1 ? text(row.get(nameIndex + 1)) : "";
        String value2 = row.size() > nameIndex + 2 ? text(row.get(nameIndex + 2)) : "";
        boolean beforeSong = row.size() > nameIndex + 3
                && "load".equalsIgnoreCase(text(row.get(nameIndex + 3)));
        addEvent(out, time, name, value1, value2, beforeSong);
    }

    private static void addEvent(java.util.List<SongChart.Event> out, double time,
                                 String name, String value1, String value2) {
        addEvent(out, time, name, value1, value2, false);
    }

    private static void addEvent(java.util.List<SongChart.Event> out, double time,
                                 String name, String value1, String value2, boolean beforeSong) {
        if (time < 0 || name == null || name.isBlank()) return;
        out.add(new SongChart.Event(time, name, value1, value2, beforeSong));
    }

    private static Double number(JsonElement element) {
        try {
            if (element == null || !element.isJsonPrimitive()) return null;
            var primitive = element.getAsJsonPrimitive();
            if (primitive.isNumber()) return primitive.getAsDouble();
            if (primitive.isString()) return Double.parseDouble(primitive.getAsString().trim());
            return null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static Integer integer(JsonElement element) {
        Double value = number(element);
        return value == null ? null : value.intValue();
    }

    private static String text(JsonElement element) {
        try {
            return element != null && element.isJsonPrimitive() ? element.getAsString() : "";
        } catch (Exception ignored) {
            return "";
        }
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
