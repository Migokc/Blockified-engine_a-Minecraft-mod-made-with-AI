package com.fnfmod.client.lua;

import com.fnfmod.client.math.Easing;

/** Lua-package compatibility facade over the engine's shared easing implementation. */
final class PsychEasing {
    private PsychEasing() {}

    static double apply(String rawName, double progress) {
        return Easing.apply(rawName, progress);
    }
}
