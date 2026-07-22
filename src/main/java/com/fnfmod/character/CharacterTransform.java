package com.fnfmod.character;

import com.fnfmod.FnfMod;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

import java.nio.file.Files;
import java.nio.file.Path;

/** Server-safe rotation loaded from an animation set's character.json. */
public record CharacterTransform(Vec3 positionOffset, float rotationOffset) {
    public static final CharacterTransform DEFAULT = new CharacterTransform(Vec3.ZERO, 0f);

    public static CharacterTransform load(String setName, Direction facing) {
        return load(setName, null, facing, false);
    }

    public static CharacterTransform load(String setName, Path songFolder, Direction facing) {
        return load(setName, songFolder, facing, false);
    }

    public static CharacterTransform load(String setName, Path songFolder, Direction facing,
                                          boolean opponent) {
        return load(setName, songFolder, facing, opponent, null);
    }

    public static CharacterTransform load(String setName, Path songFolder, Direction facing,
                                          boolean opponent, String defaultCharacter) {
        Path config = CharacterDefinitionPaths.characterJson(
                setName, songFolder, opponent, defaultCharacter);
        if (config == null) return DEFAULT;

        try {
            JsonObject json = JsonParser.parseString(Files.readString(config)).getAsJsonObject();
            float rotation = readRotation(json.get("rotation"));
            return new CharacterTransform(Vec3.ZERO, rotation);
        } catch (Exception e) {
            FnfMod.LOGGER.warn("Bad character transform in {}: {}", config, e.toString());
            return DEFAULT;
        }
    }

    private static float readRotation(JsonElement element) {
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            return 0f;
        }
        float value = element.getAsFloat();
        return Float.isFinite(value) ? value : 0f;
    }
}
