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

    public record Definition(String name, String defaultValue1, String defaultValue2) {}

    private static final List<Definition> BUILTINS = List.of(
            new Definition(MINECRAFT_COMMAND, "", "player"),
            new Definition(CAMERA_ZOOM, "0", "smooth"),
            new Definition(CAMERA_FOCUS, "player", "smooth")
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

    public static String defaultValue1(String name) {
        Definition definition = definition(name);
        return definition == null ? "" : definition.defaultValue1;
    }

    public static String defaultValue2(String name, String value1) {
        Definition definition = definition(name);
        if (definition == null) return "player";
        if (definition.name.equals(CAMERA_FOCUS) && (value1 == null || value1.isBlank())) return "";
        return definition.defaultValue2;
    }
}
