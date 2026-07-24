package com.fnfmod.gameplay;

/**
 * Psych Engine 1.0.4 rating names and full-combo classification.
 *
 * <p>Kept outside the gameplay screen so the chart editor, results screen, and
 * Lua runtime can all report the same values. Thresholds and clear types match
 * {@code PlayState.ratingStuff} and {@code fullComboFunction} exactly.
 */
public final class PsychRating {

    /** Psych's tier table: the highest percent each name covers, lowest first. */
    private static final String[] NAMES = {
            "You Suck!", "Shit", "Bad", "Bruh", "Meh", "Nice", "Good", "Great", "Sick!"
    };
    private static final double[] LIMITS = {0.2, 0.4, 0.5, 0.6, 0.69, 0.7, 0.8, 0.9, 1.0};

    /** Shown only at an exact 100%, matching Psych's last-entry special case. */
    private static final String PERFECT = "Perfect!!";

    /** Psych reports this until the first note is judged. */
    public static final String UNKNOWN = "?";

    private PsychRating() {}

    /**
     * Psych's rating percent: accumulated hit weight over judged notes, clamped
     * to 0-1. Returns 0 before anything is judged, like {@code ratingPercent}.
     */
    public static double percent(double totalNotesHit, int totalPlayed) {
        if (totalPlayed <= 0) return 0;
        return Math.min(1, Math.max(0, totalNotesHit / totalPlayed));
    }

    /** Psych's {@code ratingName} for a 0-1 percent, or {@code ?} before any note. */
    public static String name(double percent, int totalPlayed) {
        if (totalPlayed <= 0) return UNKNOWN;
        if (percent >= 1) return PERFECT;
        for (int i = 0; i < NAMES.length; i++) {
            if (percent < LIMITS[i]) return NAMES[i];
        }
        return NAMES[NAMES.length - 1];
    }

    /**
     * Psych's {@code ratingFC} clear type. A miss-free run is graded by its worst
     * judgement; otherwise fewer than ten misses is an SDCB and the rest is a Clear.
     */
    public static String fullCombo(int misses, int sicks, int goods, int bads, int shits) {
        if (misses <= 0) {
            if (bads > 0 || shits > 0) return "FC";
            if (goods > 0) return "GFC";
            if (sicks > 0) return "SFC";
            return "";
        }
        return misses < 10 ? "SDCB" : "Clear";
    }
}
