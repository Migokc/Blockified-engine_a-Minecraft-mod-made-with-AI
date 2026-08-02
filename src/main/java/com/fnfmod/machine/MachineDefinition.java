package com.fnfmod.machine;

import com.google.gson.JsonObject;

import java.nio.file.Path;
import java.util.Map;

/** Immutable machine profile loaded from an active mod pack. */
public record MachineDefinition(
        String id,
        String displayName,
        Path root,
        Path menuScript,
        Map<String, Path> textures,
        JsonObject behavior,
        boolean builtIn
) {
    public static final String DEFAULT_ID = "fnfmod:default";

    public Path texture(String face) {
        Path exact = textures.get(face);
        if (exact != null) return exact;
        if (!"top".equals(face) && !"bottom".equals(face)) {
            Path side = textures.get("side");
            if (side != null) return side;
        }
        return textures.get("all");
    }
}
