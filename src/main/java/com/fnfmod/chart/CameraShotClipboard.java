package com.fnfmod.chart;

import java.util.ArrayList;
import java.util.List;

/**
 * Cross-screen clipboard for camera events copied out of an editor playtest.
 * The playtest's free camera writes a Camera Follow Pos + Camera Rotation 3D
 * pair here; the chart editor reads it back so Ctrl+V pastes both at once.
 * Times are relative to the pair's start, so the editor can re-anchor them.
 */
public final class CameraShotClipboard {

    private static final List<SongChart.Event> EVENTS = new ArrayList<>();

    private CameraShotClipboard() {}

    public static synchronized void set(List<SongChart.Event> events) {
        EVENTS.clear();
        if (events != null) {
            for (SongChart.Event event : events) EVENTS.add(event.copy());
        }
    }

    public static synchronized boolean isEmpty() {
        return EVENTS.isEmpty();
    }

    /** A defensive copy of the stored events, or an empty list. */
    public static synchronized List<SongChart.Event> get() {
        List<SongChart.Event> out = new ArrayList<>(EVENTS.size());
        for (SongChart.Event event : EVENTS) out.add(event.copy());
        return out;
    }

    public static synchronized void clear() {
        EVENTS.clear();
    }
}
