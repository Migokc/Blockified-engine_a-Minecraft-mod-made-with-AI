package com.fnfmod.chart;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.List;

/**
 * Parses Codename Engine charts. Codename ships two chart shapes inside a
 * {@code songs/<song>/charts/<diff>.json} layout:
 *
 * <ul>
 *   <li><b>Modern</b> ({@code "codenameChart": true}) — a {@code strumLines}
 *       array, each strumline a full 4-lane set for one side
 *       ({@code type} 0 = opponent, 1 = player) holding
 *       {@code {id, time, sLen, type}} notes, plus a top-level {@code events}
 *       array (BPM changes, camera cues), {@code scrollSpeed} and
 *       {@code noteTypes}. BPM/needsVoices live in the sibling {@code meta.json}.</li>
 *   <li><b>Legacy-wrapped</b> — the classic {@code {"song": {...}}} shape, which
 *       {@link LegacyChartParser} already handles fully; we just delegate.</li>
 * </ul>
 */
public final class CodenameChartParser {

    private CodenameChartParser() {}

    public static boolean looksLikeCodename(JsonObject root) {
        if (root.has("codenameChart") && root.get("codenameChart").isJsonPrimitive()) {
            try {
                if (root.get("codenameChart").getAsBoolean()) return true;
            } catch (Exception ignored) {}
        }
        return root.has("strumLines") && root.get("strumLines").isJsonArray();
    }

    public static SongChart parse(String chartJson, String metaJson, String difficulty) {
        JsonObject root = JsonParser.parseString(chartJson).getAsJsonObject();

        // Legacy-wrapped Codename charts are complete on their own.
        if (!looksLikeCodename(root)) {
            return LegacyChartParser.parse(chartJson);
        }

        JsonObject meta = null;
        if (metaJson != null && !metaJson.isBlank()) {
            try {
                meta = JsonParser.parseString(metaJson).getAsJsonObject();
            } catch (Exception ignored) {}
        }

        SongChart chart = new SongChart();
        if (meta != null) {
            chart.title = LegacyChartParser.optString(meta, "displayName",
                    LegacyChartParser.optString(meta, "name", "Unknown"));
            chart.startBpm = LegacyChartParser.optDouble(meta, "bpm", 100);
            SongChart.TimeSignature startingMeter = new SongChart.TimeSignature(
                    roundedInt(meta, "beatsPerMeasure", 4),
                    denominatorFromStepsPerBeat(LegacyChartParser.optDouble(meta, "stepsPerBeat", 4)));
            chart.timeSignatureNumerator = startingMeter.numerator();
            chart.timeSignatureDenominator = startingMeter.denominator();
        }
        chart.speed = readScrollSpeed(root, difficulty);

        List<String> noteTypes = new ArrayList<>();
        if (root.has("noteTypes") && root.get("noteTypes").isJsonArray()) {
            for (JsonElement e : root.getAsJsonArray("noteTypes")) {
                noteTypes.add(e.isJsonPrimitive() ? e.getAsString() : "");
            }
        }

        boolean anyVocalLine = false;
        String player = null, opponent = null;

        if (root.has("strumLines") && root.get("strumLines").isJsonArray()) {
            for (JsonElement slEl : root.getAsJsonArray("strumLines")) {
                if (!slEl.isJsonObject()) continue;
                JsonObject sl = slEl.getAsJsonObject();
                // type 1 = the player's (BF) strumline, everything else is an opponent line
                boolean playerSide = LegacyChartParser.optDouble(sl, "type", 0) == 1;
                if (!LegacyChartParser.optString(sl, "vocalsSuffix", "").isEmpty()) anyVocalLine = true;

                if (sl.has("characters") && sl.get("characters").isJsonArray()) {
                    JsonArray chars = sl.getAsJsonArray("characters");
                    if (!chars.isEmpty() && chars.get(0).isJsonPrimitive()) {
                        String c = chars.get(0).getAsString();
                        if (playerSide && player == null) player = c;
                        else if (!playerSide && opponent == null) opponent = c;
                    }
                }

                if (!sl.has("notes") || !sl.get("notes").isJsonArray()) continue;
                for (JsonElement nEl : sl.getAsJsonArray("notes")) {
                    if (!nEl.isJsonObject()) continue;
                    JsonObject n = nEl.getAsJsonObject();
                    int lane = (int) LegacyChartParser.optDouble(n, "id", -1);
                    if (lane < 0 || lane > 3) continue; // this engine is 4-key only
                    double time = LegacyChartParser.optDouble(n, "time", -1);
                    if (time < 0) continue;
                    double sustain = Math.max(0, LegacyChartParser.optDouble(n, "sLen", 0));

                    // note "type" is 1-indexed into noteTypes (0 = a normal note)
                    int t = (int) LegacyChartParser.optDouble(n, "type", 0);
                    String type = (t >= 1 && t - 1 < noteTypes.size()) ? noteTypes.get(t - 1) : "";

                    SongChart.Note note = new SongChart.Note(time, lane, playerSide, sustain, type);
                    note.altAnim = type.toLowerCase(java.util.Locale.ROOT).contains("alt anim");
                    chart.notes.add(note);
                }
            }
        }

        if (player != null) chart.player1 = player;
        if (opponent != null) chart.player2 = opponent;
        // Codename's meta.needsVoices is often left false even when a song ships
        // vocals; trust the presence of per-strumline vocal suffixes too.
        boolean metaNeedsVoices = meta != null && LegacyChartParser.optBool(meta, "needsVoices", true);
        chart.needsVoices = metaNeedsVoices || anyVocalLine || meta == null;

        chart.sortNotes();
        chart.events.addAll(LegacyChartParser.parseEvents(chartJson));
        chart.sortEvents();
        buildBpmMap(chart, root);
        buildMeterMap(chart, root);
        synthesizeSections(chart);
        return chart;
    }

    /** scrollSpeed is either a flat number or a per-difficulty object. */
    private static double readScrollSpeed(JsonObject root, String difficulty) {
        if (!root.has("scrollSpeed")) return 1.0;
        JsonElement sp = root.get("scrollSpeed");
        if (sp.isJsonPrimitive() && sp.getAsJsonPrimitive().isNumber()) {
            return sp.getAsDouble();
        }
        if (sp.isJsonObject()) {
            JsonObject o = sp.getAsJsonObject();
            if (difficulty != null && o.has(difficulty) && o.get(difficulty).isJsonPrimitive()) {
                return o.get(difficulty).getAsDouble();
            }
            for (var e : o.entrySet()) {
                if (e.getValue().isJsonPrimitive() && e.getValue().getAsJsonPrimitive().isNumber()) {
                    return e.getValue().getAsDouble();
                }
            }
        }
        return 1.0;
    }

    /** Seeds the BPM map with the meta BPM and layers on every "BPM Change" event. */
    private static void buildBpmMap(SongChart chart, JsonObject root) {
        chart.bpmChanges.add(new SongChart.BpmChange(0, chart.startBpm));
        if (!root.has("events") || !root.get("events").isJsonArray()) return;

        record Change(double time, double bpm) {}
        List<Change> changes = new ArrayList<>();
        for (JsonElement eEl : root.getAsJsonArray("events")) {
            if (!eEl.isJsonObject()) continue;
            JsonObject e = eEl.getAsJsonObject();
            if (!"BPM Change".equals(LegacyChartParser.optString(e, "name", ""))) continue;
            double time = LegacyChartParser.optDouble(e, "time", -1);
            if (time < 0 || !e.has("params") || !e.get("params").isJsonArray()) continue;
            JsonArray params = e.getAsJsonArray("params");
            if (params.isEmpty() || !params.get(0).isJsonPrimitive()) continue;
            changes.add(new Change(time, params.get(0).getAsDouble()));
        }
        changes.sort(java.util.Comparator.comparingDouble(Change::time));
        for (Change c : changes) {
            if (c.time() <= 0) {
                chart.bpmChanges.get(0).bpm = c.bpm();
            } else {
                chart.bpmChanges.add(new SongChart.BpmChange(c.time(), c.bpm()));
            }
        }
    }

    /**
     * Imports Codename's native meter model. The initial meter lives in
     * meta.json as beatsPerMeasure/stepsPerBeat; later changes are chart events
     * whose third parameter chooses whether parameter two is a denominator or
     * Codename's internal steps-per-beat value.
     */
    private static void buildMeterMap(SongChart chart, JsonObject root) {
        if (!root.has("events") || !root.get("events").isJsonArray()) return;

        record Change(double time, int numerator, int denominator) {}
        List<Change> changes = new ArrayList<>();
        for (JsonElement eventElement : root.getAsJsonArray("events")) {
            if (!eventElement.isJsonObject()) continue;
            JsonObject event = eventElement.getAsJsonObject();
            if (!"Time Signature Change".equals(
                    LegacyChartParser.optString(event, "name", ""))) continue;
            double time = LegacyChartParser.optDouble(event, "time", -1);
            if (time < 0 || !event.has("params") || !event.get("params").isJsonArray()) continue;
            JsonArray params = event.getAsJsonArray("params");
            if (params.size() < 2) continue;

            Double rawNumerator = number(params.get(0));
            Double rawDenominator = number(params.get(1));
            if (rawNumerator == null || rawDenominator == null) continue;
            boolean denominatorIsSteps = params.size() > 2 && bool(params.get(2), false);
            int denominator = denominatorIsSteps
                    ? denominatorFromStepsPerBeat(rawDenominator)
                    : (int) Math.round(rawDenominator);
            SongChart.TimeSignature meter = new SongChart.TimeSignature(
                    (int) Math.round(rawNumerator), denominator);
            changes.add(new Change(time, meter.numerator(), meter.denominator()));
        }

        changes.sort(java.util.Comparator.comparingDouble(Change::time));
        int previousNumerator = chart.timeSignatureNumerator;
        int previousDenominator = chart.timeSignatureDenominator;
        for (Change change : changes) {
            if (change.time() <= 0.001) {
                chart.timeSignatureNumerator = change.numerator();
                chart.timeSignatureDenominator = change.denominator();
            }
            if (change.numerator() == previousNumerator
                    && change.denominator() == previousDenominator) continue;

            SongChart.MeterChange meterChange = new SongChart.MeterChange(
                    change.time(), change.numerator(), change.denominator());
            int last = chart.meterChanges.size() - 1;
            if (last >= 0 && Math.abs(chart.meterChanges.get(last).timeMs() - change.time()) < 0.001) {
                chart.meterChanges.set(last, meterChange);
            } else {
                chart.meterChanges.add(meterChange);
            }
            previousNumerator = change.numerator();
            previousDenominator = change.denominator();
        }
    }

    private static int roundedInt(JsonObject object, String key, int fallback) {
        return (int) Math.round(LegacyChartParser.optDouble(object, key, fallback));
    }

    private static int denominatorFromStepsPerBeat(double stepsPerBeat) {
        if (!Double.isFinite(stepsPerBeat) || stepsPerBeat <= 0) return 4;
        return (int) Math.round(16.0 / stepsPerBeat);
    }

    private static Double number(JsonElement element) {
        try {
            if (element == null || !element.isJsonPrimitive()) return null;
            var primitive = element.getAsJsonPrimitive();
            if (primitive.isNumber()) return primitive.getAsDouble();
            if (primitive.isString()) return Double.parseDouble(primitive.getAsString().trim());
        } catch (Exception ignored) {}
        return null;
    }

    private static boolean bool(JsonElement element, boolean fallback) {
        try {
            if (element == null || !element.isJsonPrimitive()) return fallback;
            var primitive = element.getAsJsonPrimitive();
            if (primitive.isBoolean()) return primitive.getAsBoolean();
            if (primitive.isNumber()) return primitive.getAsDouble() != 0;
            if (primitive.isString()) {
                String value = primitive.getAsString().trim();
                if ("true".equalsIgnoreCase(value) || "1".equals(value)) return true;
                if ("false".equalsIgnoreCase(value) || "0".equals(value)) return false;
            }
        } catch (Exception ignored) {}
        return fallback;
    }

    /**
     * Modern Codename charts have no per-section data, so build 4-beat measure
     * sections and point the camera at whichever side sings most in each — a
     * format-agnostic stand-in for the mustHitSection focus legacy charts carry.
     */
    private static void synthesizeSections(SongChart chart) {
        double end = chart.lastNoteTimeMs();
        if (end <= 0 || chart.notes.isEmpty()) {
            chart.sections.add(new SongChart.Section());
            return;
        }
        Conductor cond = new Conductor(chart);
        double t = 0;
        int idx = 0;
        boolean prevFocusPlayer = true;
        int guard = 0;
        while (t <= end + 1 && guard++ < 8192) {
            double measureMs = 4 * (60000.0 / cond.bpmAt(t));
            if (measureMs <= 0) break;
            int p = 0, o = 0;
            while (idx < chart.notes.size() && chart.notes.get(idx).timeMs < t + measureMs) {
                if (chart.notes.get(idx).playerSide) p++; else o++;
                idx++;
            }
            SongChart.Section s = new SongChart.Section();
            s.sectionBeats = 4;
            s.mustHit = (p == 0 && o == 0) ? prevFocusPlayer : p >= o;
            prevFocusPlayer = s.mustHit;
            chart.sections.add(s);
            t += measureMs;
        }
    }
}
