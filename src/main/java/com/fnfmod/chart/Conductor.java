package com.fnfmod.chart;

import java.util.List;

/** Converts between song time and beats/steps given a BPM change map. */
public class Conductor {
    private final List<SongChart.BpmChange> changes;

    public Conductor(SongChart chart) {
        this.changes = chart.bpmChanges;
    }

    public double bpmAt(double timeMs) {
        double bpm = changes.isEmpty() ? 120 : changes.get(0).bpm;
        for (SongChart.BpmChange c : changes) {
            if (c.timeMs <= timeMs) bpm = c.bpm;
            else break;
        }
        return bpm;
    }

    public double beatAt(double timeMs) {
        double beat = 0;
        double lastTime = 0;
        double bpm = changes.isEmpty() ? 120 : changes.get(0).bpm;
        for (SongChart.BpmChange c : changes) {
            if (c.timeMs <= 0) { bpm = c.bpm; continue; }
            if (c.timeMs >= timeMs) break;
            beat += (c.timeMs - lastTime) / (60000.0 / bpm);
            lastTime = c.timeMs;
            bpm = c.bpm;
        }
        beat += (timeMs - lastTime) / (60000.0 / bpm);
        return beat;
    }

    /** Inverse of {@link #beatAt}: song time (ms) of a given beat. */
    public double timeOfBeat(double beat) {
        double time = 0;
        double b = 0;
        double bpm = changes.isEmpty() ? 120 : changes.get(0).bpm;
        for (SongChart.BpmChange c : changes) {
            if (c.timeMs <= 0) {
                bpm = c.bpm;
                continue;
            }
            double beatsToChange = (c.timeMs - time) / (60000.0 / bpm);
            if (b + beatsToChange >= beat) break;
            b += beatsToChange;
            time = c.timeMs;
            bpm = c.bpm;
        }
        return time + (beat - b) * (60000.0 / bpm);
    }
}
