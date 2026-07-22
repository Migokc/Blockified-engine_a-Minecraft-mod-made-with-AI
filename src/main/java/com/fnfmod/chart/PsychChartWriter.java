package com.fnfmod.chart;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Serializes a normalized SongChart back to Psych Engine / legacy-compatible JSON. */
public final class PsychChartWriter {

    private PsychChartWriter() {}

    public static String write(SongChart chart) {
        chart.sortNotes();
        chart.ensureSectionsCoverNotes();

        JsonObject song = new JsonObject();
        song.addProperty("song", chart.title);
        song.addProperty("bpm", chart.startBpm);
        song.addProperty("speed", chart.speed);
        song.addProperty("offset", chart.offsetMs);
        song.addProperty("needsVoices", chart.needsVoices);
        song.addProperty("player1", chart.player1);
        song.addProperty("player2", chart.player2);
        song.addProperty("gfVersion", chart.player3);
        song.addProperty("stage", chart.stage);
        if (chart.noteTexture != null && !chart.noteTexture.isBlank()) {
            song.addProperty("arrowSkin", chart.noteTexture.trim());
        }
        if (chart.noteSplashTexture != null && !chart.noteSplashTexture.isBlank()) {
            song.addProperty("splashSkin", chart.noteSplashTexture.trim());
        }
        song.addProperty("validScore", true);
        chart.sortEvents();
        song.add("events", eventArray(chart));

        JsonArray sectionsArr = new JsonArray();
        double bpm = chart.startBpm;
        double time = 0;

        // bucket notes by section
        List<List<SongChart.Note>> buckets = new ArrayList<>();
        for (int i = 0; i < chart.sections.size(); i++) buckets.add(new ArrayList<>());
        double[] sectionStarts = new double[chart.sections.size() + 1];
        {
            double t = 0;
            double b = chart.startBpm;
            for (int i = 0; i < chart.sections.size(); i++) {
                SongChart.Section s = chart.sections.get(i);
                if (s.changeBPM && s.bpm > 0) b = s.bpm;
                sectionStarts[i] = t;
                t += s.sectionBeats * (60000.0 / b);
            }
            sectionStarts[chart.sections.size()] = t;
        }
        for (SongChart.Note n : chart.notes) {
            int idx = chart.sections.size() - 1;
            for (int i = 0; i < chart.sections.size(); i++) {
                if (n.timeMs >= sectionStarts[i] && n.timeMs < sectionStarts[i + 1]) {
                    idx = i;
                    break;
                }
            }
            if (idx >= 0 && idx < buckets.size()) buckets.get(idx).add(n);
        }

        for (int i = 0; i < chart.sections.size(); i++) {
            SongChart.Section s = chart.sections.get(i);
            JsonObject sec = new JsonObject();
            JsonArray sectionNotes = new JsonArray();
            for (SongChart.Note n : buckets.get(i)) {
                JsonArray arr = new JsonArray();
                arr.add(new JsonPrimitive(n.timeMs));
                int d = n.playerSide == s.mustHit ? n.lane : n.lane + 4;
                arr.add(new JsonPrimitive(d));
                arr.add(new JsonPrimitive(n.sustainMs));
                if (n.noteType != null && !n.noteType.isEmpty()) {
                    arr.add(new JsonPrimitive(n.noteType));
                }
                sectionNotes.add(arr);
            }
            sec.add("sectionNotes", sectionNotes);
            sec.addProperty("lengthInSteps", (int) Math.round(s.sectionBeats * 4));
            sec.addProperty("sectionBeats", s.sectionBeats);
            sec.addProperty("mustHitSection", s.mustHit);
            sec.addProperty("gfSection", s.gfSection);
            sec.addProperty("altAnim", s.altAnim);
            sec.addProperty("changeBPM", s.changeBPM);
            if (s.changeBPM && s.bpm > 0) bpm = s.bpm;
            sec.addProperty("bpm", bpm);
            sec.addProperty("typeOfSection", 0);
            sectionsArr.add(sec);
            time += s.sectionBeats * (60000.0 / bpm);
        }
        song.add("notes", sectionsArr);

        JsonObject root = new JsonObject();
        root.add("song", song);
        Gson gson = new GsonBuilder().create();
        return gson.toJson(root);
    }

    public static String writeEvents(SongChart chart) {
        JsonObject root = new JsonObject();
        root.add("events", eventArray(chart));
        return new GsonBuilder().create().toJson(root);
    }

    private static JsonArray eventArray(SongChart chart) {
        JsonArray events = new JsonArray();
        List<SongChart.Event> ordered = new ArrayList<>(chart.events);
        ordered.sort(Comparator.comparingDouble(event -> event.timeMs));
        double pointTime = Double.NaN;
        JsonArray payloads = null;
        for (SongChart.Event event : ordered) {
            if (payloads == null || Math.abs(event.timeMs - pointTime) >= 0.001) {
                pointTime = event.timeMs;
                JsonArray row = new JsonArray();
                row.add(pointTime);
                payloads = new JsonArray();
                row.add(payloads);
                events.add(row);
            }
            JsonArray payload = new JsonArray();
            payload.add(event.name);
            payload.add(event.value1);
            payload.add(event.value2);
            if (!event.value3.isBlank() || !event.value4.isBlank() || !event.value5.isBlank()
                    || !event.value6.isBlank()) {
                payload.add(event.value3);
                payload.add(event.value4);
                payload.add(event.value5);
                if (!event.value6.isBlank()) payload.add(event.value6);
            }
            if (event.beforeSong) payload.add("load");
            payloads.add(payload);
        }
        return events;
    }
}
