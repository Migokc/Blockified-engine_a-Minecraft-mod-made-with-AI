package com.fnfmod.gameplay;

import java.util.Locale;

/** Visual and resource-loading profile selected before a song starts. */
public enum PlaybackMode {
    FNF((byte) 0, "FNF", "Psych-style camera and FNF presentation"),
    MINECRAFT((byte) 1, "Minecraft", "Rich assets only from installed fnfmod/mods packs"),
    LEGACY((byte) 2, "Legacy", "The original Blockified Engine behavior");

    private final byte networkId;
    private final String displayName;
    private final String description;

    PlaybackMode(byte networkId, String displayName, String description) {
        this.networkId = networkId;
        this.displayName = displayName;
        this.description = description;
    }

    public byte networkId() {
        return networkId;
    }

    public String displayName() {
        return displayName;
    }

    public String description() {
        return description;
    }

    public PlaybackMode next(boolean backwards) {
        PlaybackMode[] modes = values();
        int direction = backwards ? -1 : 1;
        return modes[Math.floorMod(ordinal() + direction, modes.length)];
    }

    public static PlaybackMode fromNetworkId(byte id) {
        for (PlaybackMode mode : values()) {
            if (mode.networkId == id) return mode;
        }
        return LEGACY;
    }

    public static PlaybackMode parse(String value) {
        if (value == null) return LEGACY;
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return LEGACY;
        }
    }
}
