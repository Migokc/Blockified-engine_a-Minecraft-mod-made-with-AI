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
    public static final String CAMERA_ROTATION_3D = "Camera Rotation 3D";
    public static final String ALT_IDLE_ANIMATION = "Alt Idle Animation";
    public static final String SCREEN_SHAKE = "Screen Shake";
    public static final String CHANGE_CHARACTER = "Change Character";
    public static final String CHANGE_SCROLL_SPEED = "Change Scroll Speed";
    public static final String SET_PROPERTY = "Set Property";
    public static final String PLAY_SOUND = "Play Sound";
    public static final String ADD_CHARACTER = "Add Character";
    public static final String REMOVE_CHARACTER = "Remove Character";
    public static final String TWEEN_CHARACTER = "Tween Character";

    public record Definition(String name, String defaultValue1, String defaultValue2,
                             String defaultValue3, String defaultValue4, String defaultValue5,
                             String defaultValue6) {}

    private static Definition definition(String name, String value1, String value2) {
        return new Definition(name, value1, value2, "", "", "", "");
    }

    private static final List<Definition> BUILTINS = List.of(
            definition(MINECRAFT_COMMAND, "", "player"),
            new Definition(CAMERA_ZOOM, "0", "", "smooth", "", "", ""),
            definition(CAMERA_FOCUS, "player", "smooth"),
            definition(CAMERA_BEHAVIOR, "1", "smooth"),
            definition(HEY, "", "0.6"),
            definition(SET_GF_SPEED, "1", ""),
            definition(ADD_CAMERA_ZOOM, "0.015", "0.03"),
            definition(PLAY_ANIMATION, "idle", "dad"),
            definition(CAMERA_FOLLOW_POS, "", ""),
            definition(CAMERA_ROTATION_3D, "", ""),
            definition(ALT_IDLE_ANIMATION, "dad", "-alt"),
            definition(SCREEN_SHAKE, "0.5,0.05", ""),
            definition(CHANGE_CHARACTER, "dad", ""),
            definition(CHANGE_SCROLL_SPEED, "1", "0"),
            definition(SET_PROPERTY, "", ""),
            definition(PLAY_SOUND, "", "1"),
            new Definition(ADD_CHARACTER, "extra", "", "0,0,0", "0", "idle,player", ""),
            definition(REMOVE_CHARACTER, "extra", ""),
            new Definition(TWEEN_CHARACTER, "extra", "0,0,0", "1", "smooth", "", "")
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

    public static boolean isCameraFollowPos(String name) {
        return is(name, CAMERA_FOLLOW_POS);
    }

    public static boolean isCameraRotation3d(String name) {
        return is(name, CAMERA_ROTATION_3D);
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
        if (definition == null) return "";
        if (definition.name.equals(CAMERA_FOCUS) && (value1 == null || value1.isBlank())) return "";
        if (definition.name.equals(CAMERA_BEHAVIOR) && (value1 == null || value1.isBlank())) return "";
        return definition.defaultValue2;
    }

    public static String defaultValue3(String name) {
        Definition definition = definition(name);
        return definition == null ? "" : definition.defaultValue3;
    }

    public static String defaultValue4(String name) {
        Definition definition = definition(name);
        return definition == null ? "" : definition.defaultValue4;
    }

    public static String defaultValue5(String name) {
        Definition definition = definition(name);
        return definition == null ? "" : definition.defaultValue5;
    }

    public static String defaultValue6(String name) {
        Definition definition = definition(name);
        return definition == null ? "" : definition.defaultValue6;
    }

    /** 1-based value slot containing a Blockified easing, or zero. */
    public static int easingValue(String name) {
        Definition definition = definition(name);
        if (definition == null) return 0;
        return switch (definition.name) {
            case CAMERA_FOCUS, CAMERA_BEHAVIOR -> 2;
            case CAMERA_ZOOM -> 3;
            case CAMERA_FOLLOW_POS, CAMERA_ROTATION_3D, TWEEN_CHARACTER -> 4;
            default -> 0;
        };
    }

    public static String help(String name) {
        Definition definition = definition(name);
        if (definition == null) return String.join("\n",
                "Custom Lua event.",
                "Value 1 and Value 2 are passed unchanged to onEvent(name, value1, value2).",
                "Their meaning is defined by the event's Lua script.");
        return switch (definition.name) {
            case MINECRAFT_COMMAND -> String.join("\n",
                    "Runs a Minecraft command when this event is reached.",
                    "Value 1: Command. A leading / is optional. The command editor provides vanilla parsing and suggestions.",
                    "Targets: <player>, <opponent>, and <speakers> select the performers assigned to this Funkin' Machine.",
                    "Position: <forward:n>, <backward:n>, <left:n>, <right:n>, <up:n>, and <down:n> produce camera-relative coordinates. Directions can be combined, such as <forward:2,left:1>.",
                    "Rotation: <camera_rotation> faces the stage camera; <character_rotation:degrees> adds a yaw offset to the normal performer direction.",
                    "Value 2: player runs the command as the local player with normal permissions. server runs it once from the Funkin' Machine after host/operator permission checks.");
            case CAMERA_ZOOM -> String.join("\n",
                    "Changes Blockified's persistent camera zoom over a duration. Unlike Add Camera Zoom, this does not immediately decay.",
                    "Value 1: Zoom amount from -1 to 0.9. Positive zooms in; negative zooms out; 0 restores normal zoom.",
                    "Value 2: Duration in seconds to reach the zoom. Leave empty for the default 0.5 seconds.",
                    "Value 3: Easing curve plus in/out/inOut direction. constant snaps immediately.");
            case CAMERA_FOCUS -> String.join("\n",
                    "Overrides Must Hit camera focus until released.",
                    "Value 1: player/BF, opponent/Dad, or GF/speakers. Leave empty to restore normal Must Hit section focus.",
                    "Value 2: Easing curve plus in/out/inOut direction. Ignored when Value 1 is empty.");
            case CAMERA_BEHAVIOR -> String.join("\n",
                    "Legacy and Minecraft only. Changes how section-focus camera movement behaves; FNF mode ignores it.",
                    "Value 1: Speed multiplier. 1 is normal; 2 is twice as fast; 0.5 is half speed.",
                    "Value 2: Easing curve plus in/out/inOut direction. constant snaps to the target. Leave both values empty to restore defaults.");
            // Meanings and examples mirror Psych Engine 1.0.4 ChartingState event help.
            case HEY -> String.join("\n",
                    "Plays the Hey animation.",
                    "Value 1: BF for Boyfriend only, GF for Girlfriend only, or anything else for both.",
                    "Value 2: Animation duration in seconds. Leave empty for 0.6 seconds.");
            case SET_GF_SPEED -> String.join("\n",
                    "Sets Girlfriend's head-bop interval.",
                    "Value 1: Integer interval. 1 is normal speed, 2 is every second beat, 4 is every fourth beat, and so on.");
            case ADD_CAMERA_ZOOM -> String.join("\n",
                    "Adds a temporary camera and HUD zoom impulse.",
                    "Value 1: Game camera zoom addition. Default is 0.015.",
                    "Value 2: HUD zoom addition. Default is 0.03. Leave values empty to use the defaults.");
            case PLAY_ANIMATION -> String.join("\n",
                    "Plays a character animation, then returns the character to its idle behavior when complete.",
                    "Value 1: Animation name.",
                    "Value 2: Character: Dad, BF, GF, or an Add Character tag. Blockified character.json custom animation names also work.");
            case CAMERA_FOLLOW_POS -> String.join("\n",
                    "Psych-compatible camera positioning with optional Blockified 3D controls.",
                    "Value 1: X. With only Values 1/2, this is Psych's 1280x720 camera coordinate.",
                    "Value 2: Y. Leave Values 1/2 empty to return to normal Psych camera following.",
                    "Value 3: Z in blocks. Supplying any extended value switches Minecraft's world camera to 3D offsets: X = camera-right, Y = up, Z = camera-forward.",
                    "Value 4: Easing. Empty/default uses normal smooth movement.",
                    "Value 5: default keeps normal/Lua character tracking and adds the 3D offset; override/true locks the world camera to the speakers and blocks normal/Lua focus movement.",
                    "Value 6: Reference frame for the 3D offset. machine (default) aligns X/Y/Z to the Funkin' Machine facing, so Camera Rotation 3D no longer skews the movement. camera aligns them to the current camera rotation instead.",
                    "Value 7: Move duration in seconds. Empty uses the default 0.5s; a larger value makes the camera glide to the position more slowly.");
            case CAMERA_ROTATION_3D -> String.join("\n",
                    "Rotates Minecraft's world camera in three axes.",
                    "Value 1: X rotation (pitch) in degrees.",
                    "Value 2: Y rotation (yaw) in degrees.",
                    "Value 3: Z rotation (roll) in degrees.",
                    "Value 4: Easing. Leave all rotation values empty to return to the normal camera rotation.");
            case ALT_IDLE_ANIMATION -> String.join("\n",
                    "Adds a suffix to a character's idle animation name; for example, -alt selects idle-alt.",
                    "Value 1: Character: Dad, BF, or GF.",
                    "Value 2: New suffix. Leave empty to disable the alternate idle.");
            case SCREEN_SHAKE -> String.join("\n",
                    "Shakes the game camera and/or HUD.",
                    "Value 1: Game shake as duration, intensity; for example 1, 0.05.",
                    "Value 2: HUD shake using the same duration, intensity format.");
            case CHANGE_CHARACTER -> String.join("\n",
                    "Changes one character during the song.",
                    "Value 1: Character slot: Dad, BF, or GF.",
                    "Value 2: New character ID. Outside the FNF scene this can select a Blockified BBS character mapping.");
            case CHANGE_SCROLL_SPEED -> String.join("\n",
                    "Changes the song's scroll-speed multiplier.",
                    "Value 1: Multiplier; 1 is the chart's default speed.",
                    "Value 2: Transition duration in seconds.");
            case SET_PROPERTY -> String.join("\n",
                    "Changes a runtime property.",
                    "Value 1: Variable/property path.",
                    "Value 2: New value.");
            case PLAY_SOUND -> String.join("\n",
                    "Plays a sound from the active mod's sounds folder.",
                    "Value 1: Sound filename without .ogg.",
                    "Value 2: Volume from 0 to 1. Default is 1.");
            case ADD_CHARACTER -> String.join("\n",
                    "Creates or replaces a named client-side BBS performer for this song.",
                    "Value 1: Unique tag used by Lua and Play Animation, such as extraDad.",
                    "Value 2: Character definition name from the active mod's animations folder.",
                    "Value 3: Stage-local X,Y,Z in blocks. X is camera-right, Y is up, Z is camera-forward.",
                    "Value 4: Extra yaw rotation in degrees; character.json rotation is also applied.",
                    "Value 5: Initial animation and optional definition side, such as idle,player or idle,opponent.",
                    "Lua can move it with setProperty('extraDad.x', value), tween it, or call characterPlayAnim.");
            case REMOVE_CHARACTER -> String.join("\n",
                    "Removes a named character created by Add Character or Lua.",
                    "Value 1: Character tag.");
            case TWEEN_CHARACTER -> String.join("\n",
                    "Smoothly moves an Add Character performer to a new position over time. "
                            + "Works in Legacy and Minecraft modes with no Lua required.",
                    "Value 1: Character tag, such as extraDad.",
                    "Value 2: Target stage-local X,Y,Z in blocks. X is camera-right, Y is up, Z is camera-forward. "
                            + "Leave a component empty to keep its current value.",
                    "Value 3: Duration in seconds. Default is 1. Use 0 to snap instantly.",
                    "Value 4: Easing curve plus in/out/inOut direction. constant snaps at the end.",
                    "Value 5: Optional target yaw rotation in degrees. Leave empty to keep the current rotation.");
            default -> "Built-in event";
        };
    }

    public static String documentationSource(String name) {
        Definition definition = definition(name);
        if (definition == null) return "Custom Lua event";
        return switch (definition.name) {
            case CAMERA_FOLLOW_POS -> "Blockified & Psych Hybrid event";
            case MINECRAFT_COMMAND, CAMERA_ZOOM, CAMERA_FOCUS, CAMERA_BEHAVIOR,
                    CAMERA_ROTATION_3D, ADD_CHARACTER, REMOVE_CHARACTER, TWEEN_CHARACTER -> "Blockified event";
            default -> "Psych Engine event";
        };
    }

    public static String value1Hint(String name) {
        Definition definition = definition(name);
        if (definition == null) return "custom value 1";
        return switch (definition.name) {
            case MINECRAFT_COMMAND -> "Minecraft command";
            case CAMERA_ZOOM -> "zoom amount; 0 resets";
            case CAMERA_FOCUS -> "player, opponent, GF, or empty";
            case CAMERA_BEHAVIOR -> "speed multiplier; empty resets";
            case HEY -> "BF, GF, or both";
            case SET_GF_SPEED -> "integer beat interval";
            case ADD_CAMERA_ZOOM -> "game zoom; default 0.015";
            case PLAY_ANIMATION -> "animation name";
            case CAMERA_FOLLOW_POS -> "camera X / 3D right";
            case CAMERA_ROTATION_3D -> "X rotation / pitch";
            case ALT_IDLE_ANIMATION -> "Dad, BF, or GF";
            case CHANGE_CHARACTER -> "Dad, BF, GF, or custom tag";
            case SCREEN_SHAKE -> "game: duration, intensity";
            case CHANGE_SCROLL_SPEED -> "speed multiplier";
            case SET_PROPERTY -> "property path";
            case PLAY_SOUND -> "sound filename";
            case ADD_CHARACTER, REMOVE_CHARACTER, TWEEN_CHARACTER -> "unique character tag";
            default -> "value 1";
        };
    }

    public static String value2Hint(String name) {
        Definition definition = definition(name);
        if (definition == null) return "custom value 2";
        return switch (definition.name) {
            case MINECRAFT_COMMAND -> "player or server";
            case CAMERA_FOCUS, CAMERA_BEHAVIOR -> "easing";
            case CAMERA_ZOOM -> "duration s; default 0.5";
            case HEY -> "duration; default 0.6";
            case ADD_CAMERA_ZOOM -> "HUD zoom; default 0.03";
            case PLAY_ANIMATION -> "Dad, BF, GF, or custom tag";
            case CAMERA_FOLLOW_POS -> "camera Y / 3D up";
            case CAMERA_ROTATION_3D -> "Y rotation / yaw";
            case ALT_IDLE_ANIMATION -> "animation suffix";
            case SCREEN_SHAKE -> "HUD: duration, intensity";
            case CHANGE_CHARACTER -> "new character ID";
            case CHANGE_SCROLL_SPEED -> "duration in seconds";
            case SET_PROPERTY -> "new value";
            case PLAY_SOUND -> "volume 0-1";
            case ADD_CHARACTER -> "character definition";
            case TWEEN_CHARACTER -> "target X,Y,Z";
            default -> "value 2";
        };
    }

    public static String value3Hint(String name) {
        Definition definition = definition(name);
        if (definition == null) return "custom value 3";
        return switch (definition.name) {
            case CAMERA_ZOOM -> "easing; default is smooth";
            case CAMERA_FOLLOW_POS -> "3D forward offset (blocks)";
            case CAMERA_ROTATION_3D -> "Z rotation / roll";
            case ADD_CHARACTER -> "stage X,Y,Z";
            case TWEEN_CHARACTER -> "duration in seconds";
            default -> "value 3";
        };
    }

    public static String value4Hint(String name) {
        if (is(name, ADD_CHARACTER)) return "extra yaw rotation";
        return easingValue(name) == 4 ? "easing; default is smooth" : "value 4";
    }

    public static String value5Hint(String name) {
        if (is(name, ADD_CHARACTER)) return "animation,player/opponent";
        if (is(name, TWEEN_CHARACTER)) return "target rotation (deg)";
        return isCameraFollowPos(name) ? "default or override" : "value 5";
    }

    public static String value6Hint(String name) {
        return isCameraFollowPos(name) ? "machine facing or camera" : "value 6";
    }

    public static String value7Hint(String name) {
        return isCameraFollowPos(name) ? "move duration (s); blank = 0.5" : "value 7";
    }
}
