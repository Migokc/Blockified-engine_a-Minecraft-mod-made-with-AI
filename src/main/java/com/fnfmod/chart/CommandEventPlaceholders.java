package com.fnfmod.chart;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Mod-only command placeholders used by chart events. */
public final class CommandEventPlaceholders {
    public static final String PLAYER = "<player>";
    public static final String OPPONENT = "<opponent>";
    public static final String SPEAKERS = "<speakers>";
    public static final String CAMERA_ROTATION = "<camera_rotation>";
    public static final String CHARACTER_ROTATION = "<character_rotation:0>";
    private static final Pattern ANGLE_MACRO = Pattern.compile("<([^<>]+)>");
    private static final Pattern POSITION_PART = Pattern.compile(
            "(left|right|forward|backward|up|down)(?::(-?\\d+(?:\\.\\d+)?))?",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern CHARACTER_ROTATION_BODY = Pattern.compile(
            "character_rotation(?::(-?\\d+(?:\\.\\d+)?))?", Pattern.CASE_INSENSITIVE);

    private CommandEventPlaceholders() {}

    public static String tag(BlockPos machinePos, String role) {
        return "fnfmod_" + role + "_" + Long.toUnsignedString(machinePos.asLong(), 36);
    }

    public static String expand(String command, BlockPos machinePos) {
        return expand(command, machinePos, Direction.NORTH);
    }

    /**
     * Replaces FNF syntax with equal-length valid Brigadier syntax. The editor
     * can therefore use Minecraft's real parser without changing cursor or
     * suggestion ranges, while still displaying the readable FNF macros.
     */
    public static String forAutocomplete(String command) {
        if (command == null || command.isEmpty()) return command == null ? "" : command;
        String parsed = command
                .replace(PLAYER, dummySelector(PLAYER.length()))
                .replace(OPPONENT, dummySelector(OPPONENT.length()))
                .replace(SPEAKERS, dummySelector(SPEAKERS.length()))
                .replace(CAMERA_ROTATION, dummyRotation(CAMERA_ROTATION.length()));

        Matcher matcher = ANGLE_MACRO.matcher(parsed);
        StringBuffer out = new StringBuffer();
        while (matcher.find()) {
            String replacement;
            if (characterRotation(matcher.group(1), Direction.NORTH) != null) {
                replacement = dummyRotation(matcher.group().length());
            } else if (matcher.group().length() >= 5
                    && combinedPosition(matcher.group(1), Direction.NORTH) != null) {
                replacement = dummyPosition(matcher.group().length());
            } else {
                continue;
            }
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    /** Expands selectors and camera-relative XYZ/rotation macros. */
    public static String expand(String command, BlockPos machinePos, Direction machineFacing) {
        return expand(command, machinePos, machineFacing, false);
    }

    /**
     * Expands selectors and camera-relative macros.
     *
     * <p>The {@code player}/{@code opponent}/{@code speakers} selectors normally
     * target entities the server tagged when the song session started. An editor
     * playtest has no session and no tags, so with {@code selfSelectors} they all
     * resolve to {@code @s}: the one performer present is the person testing, who
     * runs the command, so every role points at them. Without this a command like
     * {@code tp <player> ...} fails with "No entity was found" and the chart's
     * opening teleport never moves the player onto the scene.
     */
    public static String expand(String command, BlockPos machinePos, Direction machineFacing,
                                boolean selfSelectors) {
        if (command == null) return "";
        Direction facing = machineFacing == null ? Direction.NORTH : machineFacing;
        String player = selfSelectors ? "@s" : selector(machinePos, "player");
        String opponent = selfSelectors ? "@s" : selector(machinePos, "opponent");
        String speakers = selfSelectors ? "@s" : selector(machinePos, "speakers");
        String expanded = command
                .replace(PLAYER, player)
                .replace(OPPONENT, opponent)
                .replace(SPEAKERS, speakers)
                .replace(CAMERA_ROTATION, trim(facing.getOpposite().toYRot()) + " 0");

        Matcher matcher = ANGLE_MACRO.matcher(expanded);
        StringBuffer out = new StringBuffer();
        while (matcher.find()) {
            String replacement = characterRotation(matcher.group(1), facing);
            if (replacement == null) replacement = combinedPosition(matcher.group(1), facing);
            if (replacement == null) replacement = matcher.group();
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private static String combinedPosition(String body, Direction facing) {
        Vec3 offset = positionOffset(body, facing);
        if (offset == null) return null;
        return relative(offset.x) + " " + relative(offset.y) + " " + relative(offset.z);
    }

    /** Character yaw relative to the stage's normal performer rotation, plus pitch 0. */
    private static String characterRotation(String body, Direction facing) {
        Matcher matcher = CHARACTER_ROTATION_BODY.matcher(body.trim());
        if (!matcher.matches()) return null;
        try {
            double offset = matcher.group(1) == null ? 0.0 : Double.parseDouble(matcher.group(1));
            double rotation = facing.toYRot() + offset;
            return Double.isFinite(rotation) ? trim(rotation) + " 0" : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    /**
     * Parses the same camera-relative position expression used by command
     * events. Angle brackets are optional for callers reading character.json.
     */
    public static Vec3 positionOffset(String expression, Direction machineFacing) {
        if (expression == null) return null;
        String body = expression.trim();
        if (body.startsWith("<") && body.endsWith(">") && body.length() >= 2) {
            body = body.substring(1, body.length() - 1);
        }
        Direction facing = machineFacing == null ? Direction.NORTH : machineFacing;
        double x = 0, y = 0, z = 0;
        String[] parts = body.split(",");
        if (parts.length == 0) return null;
        for (String rawPart : parts) {
            Matcher part = POSITION_PART.matcher(rawPart.trim());
            if (!part.matches()) return null;
            String kind = part.group(1).toLowerCase(Locale.ROOT);
            double distance = part.group(2) == null ? 1.0 : Double.parseDouble(part.group(2));
            if (kind.equals("up")) {
                y += distance;
            } else if (kind.equals("down")) {
                y -= distance;
            } else {
                Direction direction = switch (kind) {
                    case "left" -> facing.getClockWise();
                    case "right" -> facing.getCounterClockWise();
                    case "forward" -> facing.getOpposite();
                    case "backward" -> facing;
                    default -> facing.getOpposite();
                };
                x += direction.getStepX() * distance;
                z += direction.getStepZ() * distance;
            }
        }
        return new Vec3(x, y, z);
    }

    private static String relative(double value) {
        return Math.abs(value) < 1.0e-9 ? "~" : "~" + trim(value);
    }

    private static String dummySelector(int length) {
        return "@e[x=" + "0".repeat(Math.max(1, length - 6)) + "]";
    }

    private static String dummyPosition(int length) {
        if (length < 5) return "~ ~ ~";
        return "~" + "0".repeat(length - 5) + " ~ ~";
    }

    private static String dummyRotation(int length) {
        return "0".repeat(Math.max(1, length - 2)) + " 0";
    }

    private static String trim(double value) {
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }

    private static String selector(BlockPos machinePos, String role) {
        return "@e[tag=" + tag(machinePos, role) + ",limit=1]";
    }
}
