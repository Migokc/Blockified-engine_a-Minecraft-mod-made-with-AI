package com.fnfmod.chart;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.List;

/**
 * Parses V-Slice (FNF 0.3.0+) chart + metadata json pairs.
 * The chart file contains all difficulties: {"notes": {"easy":[...], ...}, "scrollSpeed": {...}}.
 * Notes: {"t": timeMs, "d": 0-7, "l": lengthMs, "k": kind}; d 0-3 = player, 4-7 = opponent.
 */
public final class VSliceChartParser {

    private VSliceChartParser() {}

    public static List<String> listDifficulties(String chartJson) {
        List<String> out = new ArrayList<>();
        try {
            JsonObject root = JsonParser.parseString(chartJson).getAsJsonObject();
            if (root.has("notes") && root.get("notes").isJsonObject()) {
                for (var e : root.getAsJsonObject("notes").entrySet()) {
                    if (e.getValue().isJsonArray() && !e.getValue().getAsJsonArray().isEmpty()) {
                        out.add(e.getKey());
                    }
                }
            }
        } catch (Exception ignored) {}
        return out;
    }

    public static SongChart parse(String chartJson, String metadataJson, String difficulty) {
        JsonObject chartRoot = JsonParser.parseString(chartJson).getAsJsonObject();
        SongChart chart = new SongChart();

        // ---- metadata ----
        if (metadataJson != null) {
            try {
                JsonObject meta = JsonParser.parseString(metadataJson).getAsJsonObject();
                chart.title = LegacyChartParser.optString(meta, "songName", "Unknown");
                if (meta.has("timeChanges") && meta.get("timeChanges").isJsonArray()) {
                    for (JsonElement el : meta.getAsJsonArray("timeChanges")) {
                        if (!el.isJsonObject()) continue;
                        JsonObject tc = el.getAsJsonObject();
                        double t = LegacyChartParser.optDouble(tc, "t", 0);
                        double bpm = LegacyChartParser.optDouble(tc, "bpm", 120);
                        chart.bpmChanges.add(new SongChart.BpmChange(Math.max(0, t), bpm));
                    }
                }
                if (meta.has("playData") && meta.get("playData").isJsonObject()) {
                    JsonObject pd = meta.getAsJsonObject("playData");
                    if (pd.has("characters") && pd.get("characters").isJsonObject()) {
                        JsonObject ch = pd.getAsJsonObject("characters");
                        chart.player1 = LegacyChartParser.optString(ch, "player", "bf");
                        chart.player2 = LegacyChartParser.optString(ch, "opponent", "dad");
                        chart.player3 = LegacyChartParser.optString(ch, "girlfriend", "gf");
                    }
                }
                if (meta.has("offsets") && meta.get("offsets").isJsonObject()) {
                    chart.offsetMs = LegacyChartParser.optDouble(meta.getAsJsonObject("offsets"), "instrumental", 0);
                }
            } catch (Exception ignored) {}
        }
        if (!chart.bpmChanges.isEmpty()) {
            chart.startBpm = chart.bpmChanges.get(0).bpm;
        }
        if (chart.bpmChanges.isEmpty() || chart.bpmChanges.get(0).timeMs > 0) {
            chart.bpmChanges.add(0, new SongChart.BpmChange(0, chart.startBpm));
        }

        // ---- scroll speed ----
        if (chartRoot.has("scrollSpeed")) {
            JsonElement ss = chartRoot.get("scrollSpeed");
            if (ss.isJsonObject()) {
                JsonObject sso = ss.getAsJsonObject();
                if (sso.has(difficulty)) chart.speed = sso.get(difficulty).getAsDouble();
                else if (sso.has("default")) chart.speed = sso.get("default").getAsDouble();
            } else if (ss.isJsonPrimitive()) {
                chart.speed = ss.getAsDouble();
            }
        }

        // ---- notes ----
        JsonArray notes = null;
        if (chartRoot.has("notes")) {
            JsonElement n = chartRoot.get("notes");
            if (n.isJsonObject()) {
                JsonObject no = n.getAsJsonObject();
                if (no.has(difficulty)) notes = no.getAsJsonArray(difficulty);
                else if (no.size() > 0) notes = no.entrySet().iterator().next().getValue().getAsJsonArray();
            } else if (n.isJsonArray()) {
                notes = n.getAsJsonArray();
            }
        }
        if (notes != null) {
            for (JsonElement el : notes) {
                if (!el.isJsonObject()) continue;
                JsonObject n = el.getAsJsonObject();
                double t = LegacyChartParser.optDouble(n, "t", -1);
                int d = (int) LegacyChartParser.optDouble(n, "d", -1);
                if (t < 0 || d < 0 || d > 7) continue;
                double len = LegacyChartParser.optDouble(n, "l", 0);
                String kind = LegacyChartParser.optString(n, "k", "");
                if ("normal".equalsIgnoreCase(kind)) kind = "";
                // V-Slice: 0-3 player, 4-7 opponent
                SongChart.Note note = new SongChart.Note(t, d % 4, d < 4, Math.max(0, len), kind);
                chart.notes.add(note);
            }
        }
        chart.sortNotes();
        chart.events.addAll(LegacyChartParser.parseEvents(chartJson));
        chart.sortEvents();

        // Build synthetic 4-beat sections for the editor / legacy conversion.
        buildSections(chart);
        return chart;
    }

    private static void buildSections(SongChart chart) {
        double end = chart.lastNoteTimeMs();
        Conductor conductor = new Conductor(chart);
        double time = 0;
        int guard = 0;
        while (time <= end + 1 && guard++ < 4096) {
            SongChart.Section s = new SongChart.Section();
            double bpm = conductor.bpmAt(time);
            s.sectionBeats = 4;
            // mark bpm changes at section boundaries so editing keeps timing roughly right
            double prevBpm = time == 0 ? chart.startBpm : conductor.bpmAt(time - 1);
            if (time > 0 && bpm != prevBpm) {
                s.changeBPM = true;
                s.bpm = bpm;
            }
            // mustHit = does this section contain more player notes than opponent notes
            double secEnd = time + s.sectionBeats * (60000.0 / bpm);
            int player = 0, opp = 0;
            for (SongChart.Note n : chart.notes) {
                if (n.timeMs >= time && n.timeMs < secEnd) {
                    if (n.playerSide) player++; else opp++;
                }
            }
            s.mustHit = player >= opp;
            chart.sections.add(s);
            time = secEnd;
        }
        if (chart.sections.isEmpty()) chart.sections.add(new SongChart.Section());
    }
}
