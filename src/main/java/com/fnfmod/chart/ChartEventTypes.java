package com.fnfmod.chart;

import java.util.List;

/**
 * Canonical built-in chart events shared by gameplay and the chart editor.
 * Add new engine-owned event definitions here so authoring and playback cannot
 * silently disagree about names or defaults.
 */
public final class ChartEventTypes {
    public static final String MINECRAFT_COMMAND = "Minecraft Command";
    public static final String CAMERA_ZOOM = "Camera Zoom";
    public static final String CAMERA_FOCUS = "Camera Focus";
    public static final String CAMERA_BEHAVIOR = "Camera Behavior";
    public static final String HEY = "Hey!";
    public static final String SET_GF_SPEED = "Set GF Speed";
    public static final String ADD_CAMERA_ZOOM = "Add Camera Zoom";
    public static final String PLAY_ANIMATION = "Play Animation";
    public static final String CAMERA_FOLLOW_POS = "Camera Follow Pos";
    public static final String ALT_IDLE_ANIMATION = "Alt Idle Animation";
    public static final String SCREEN_SHAKE = "Screen Shake";
    public static final String CHANGE_CHARACTER = "Change Character";
    public static final String CHANGE_SCROLL_SPEED = "Change Scroll Speed";
    public static final String SET_PROPERTY = "Set Property";
    public static final String PLAY_SOUND = "Play Sound";

    public record Definition(String name, String defaultValue1, String defaultValue2) {}

    private static final List<Definition> BUILTINS = List.of(
            new Definition(MINECRAFT_COMMAND, "", "player"),
            new Definition(CAMERA_ZOOM, "0", "smooth"),
            new Definition(CAMERA_FOCUS, "player", "smooth"),
            new Definition(CAMERA_BEHAVIOR, "1", "smooth"),
            new Definition(HEY, "", "0.6"),
            new Definition(SET_GF_SPEED, "1", ""),
            new Definition(ADD_CAMERA_ZOOM, "0.015", "0.03"),
            new Definition(PLAY_ANIMATION, "idle", "dad"),
            new Definition(CAMERA_FOLLOW_POS, "", ""),
            new Definition(ALT_IDLE_ANIMATION, "dad", "-alt"),
            new Definition(SCREEN_SHAKE, "0.5,0.05", ""),
            new Definition(CHANGE_CHARACTER, "dad", ""),
            new Definition(CHANGE_SCROLL_SPEED, "1", "0"),
            new Definition(SET_PROPERTY, "", ""),
            new Definition(PLAY_SOUND, "", "1")
    );

    private ChartEventTypes() {}

    public static List<Definition> builtins() {
        return BUILTINS;
    }

    public static List<String> builtinNames() {
        return BUILTINS.stream().map(Definition::name).toList();
    }

    public static Definition definition(String name) {
        if (name == null) return null;
        for (Definition definition : BUILTINS) {
            if (definition.name.equalsIgnoreCase(name)) return definition;
        }
        // Compatibility with charts made before the canonical command name.
        if (name.equalsIgnoreCase("Run Minecraft Command")) return BUILTINS.get(0);
        return null;
    }

    public static boolean isMinecraftCommand(String name) {
        Definition definition = definition(name);
        return definition != null && definition.name.equals(MINECRAFT_COMMAND);
    }

    public static boolean isCameraZoom(String name) {
        Definition definition = definition(name);
        return definition != null && definition.name.equals(CAMERA_ZOOM);
    }

    public static boolean isCameraFocus(String name) {
        Definition definition = definition(name);
        return definition != null && definition.name.equals(CAMERA_FOCUS);
    }

    public static boolean isCameraBehavior(String name) {
        Definition definition = definition(name);
        return definition != null && definition.name.equals(CAMERA_BEHAVIOR);
    }

    public static boolean is(String actual, String canonical) {
        Definition definition = definition(actual);
        return definition != null && definition.name.equals(canonical);
    }

    public static String defaultValue1(String name) {
        Definition definition = definition(name);
        return definition == null ? "" : definition.defaultValue1;
    }

    public static String defaultValue2(String name, String value1) {
        Definition definition = definition(name);
        if (definition == null) return "player";
        if (definition.name.equals(CAMERA_FOCUS) && (value1 == null || value1.isBlank())) return "";
        if (definition.name.equals(CAMERA_BEHAVIOR) && (value1 == null || value1.isBlank())) return "";
        return definition.defaultValue2;
    }

    public static String help(String name) {
        Definition definition = definition(name);
        if (definition == null) return "Custom event: Value 1 and Value 2 go to Lua";
        return switch (definition.name) {
            case MINECRAFT_COMMAND -> "Value 1: command; Value 2: player/server";
            case CAMERA_ZOOM -> "Persistent amount + easing (500ms)";
            case CAMERA_FOCUS -> "Target + easing; empty restores Must Hit";
            case CAMERA_BEHAVIOR -> "Legacy/Minecraft: speed + easing; both empty reset";
            case HEY -> "Value 1: bf/gf/both; Value 2: duration";
            case SET_GF_SPEED -> "Value 1: GF dance interval";
            case ADD_CAMERA_ZOOM -> "Value 1: game amount; Value 2: HUD amount";
            case PLAY_ANIMATION -> "Value 1: animation; Value 2: character";
            case CAMERA_FOLLOW_POS -> "Value 1: X; Value 2: Y; both empty release";
            case ALT_IDLE_ANIMATION -> "Value 1: character; Value 2: suffix";
            case SCREEN_SHAKE -> "Values: duration,intensity for game and HUD";
            case CHANGE_CHARACTER -> "Value 1: character slot; Value 2: new ID";
            case CHANGE_SCROLL_SPEED -> "Value 1: multiplier; Value 2: duration";
            case SET_PROPERTY -> "Value 1: property; Value 2: new value";
            case PLAY_SOUND -> "Value 1: sound; Value 2: volume";
            default -> "Built-in event";
        };
    }
}
