package com.fnfmod.character;

import com.fnfmod.FnfMod;
import com.fnfmod.chart.CommandEventPlaceholders;
import com.fnfmod.song.SongLibrary;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

import java.nio.file.Files;
import java.nio.file.Path;

/** Server-safe stage transform loaded from an animation set's character.json. */
public record CharacterTransform(Vec3 positionOffset, float rotationOffset) {
    public static final CharacterTransform DEFAULT = new CharacterTransform(Vec3.ZERO, 0f);

    public static CharacterTransform load(String setName, Direction facing) {
        Path root = SongLibrary.animationsDir().toAbsolutePath().normalize();
        String selected = setName == null || setName.isBlank() ? "default" : setName;
        Path directory = "default".equals(selected) ? root : root.resolve(selected).normalize();
        if (!directory.startsWith(root) || !Files.isDirectory(directory)) directory = root;
        Path config = directory.resolve("character.json");
        if (!Files.isRegularFile(config)) return DEFAULT;

        try {
            JsonObject json = JsonParser.parseString(Files.readString(config)).getAsJsonObject();
            Vec3 pos = readPosition(json.get("pos"), facing);
            float rotation = readRotation(json.get("rotation"));
            return new CharacterTransform(pos, rotation);
        } catch (Exception e) {
            FnfMod.LOGGER.warn("Bad character transform in {}: {}", config, e.toString());
            return DEFAULT;
        }
    }

    private static Vec3 readPosition(JsonElement element, Direction facing) {
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            return Vec3.ZERO;
        }
        Vec3 offset = CommandEventPlaceholders.positionOffset(element.getAsString(), facing);
        return offset == null ? Vec3.ZERO : offset;
    }

    private static float readRotation(JsonElement element) {
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            return 0f;
        }
        float value = element.getAsFloat();
        return Float.isFinite(value) ? value : 0f;
    }
}
