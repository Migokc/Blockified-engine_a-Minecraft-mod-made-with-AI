package com.fnfmod.chart;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

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
    private static final Pattern POSITION_MACRO = Pattern.compile(
            "<(left|right|forward|backward|up|down)(?::(-?\\d+(?:\\.\\d+)?))?>",
            Pattern.CASE_INSENSITIVE);

    private CommandEventPlaceholders() {}

    public static String tag(BlockPos machinePos, String role) {
        return "fnfmod_" + role + "_" + Long.toUnsignedString(machinePos.asLong(), 36);
    }

    public static String expand(String command, BlockPos machinePos) {
        return expand(command, machinePos, Direction.NORTH);
    }

    /** Expands selectors and camera-relative XYZ/rotation macros. */
    public static String expand(String command, BlockPos machinePos, Direction machineFacing) {
        if (command == null) return "";
        Direction facing = machineFacing == null ? Direction.NORTH : machineFacing;
        String expanded = command
                .replace(PLAYER, selector(machinePos, "player"))
                .replace(OPPONENT, selector(machinePos, "opponent"))
                .replace(SPEAKERS, selector(machinePos, "speakers"))
                .replace(CAMERA_ROTATION, trim(facing.getOpposite().toYRot()) + " 0");

        Matcher matcher = POSITION_MACRO.matcher(expanded);
        StringBuffer out = new StringBuffer();
        while (matcher.find()) {
            String kind = matcher.group(1).toLowerCase(Locale.ROOT);
            double distance = matcher.group(2) == null ? 1.0 : Double.parseDouble(matcher.group(2));
            String replacement = position(kind, distance, facing);
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private static String position(String kind, double distance, Direction facing) {
        if (kind.equals("up")) return "~ " + relative(distance) + " ~";
        if (kind.equals("down")) return "~ " + relative(-distance) + " ~";

        Direction direction = switch (kind) {
            case "left" -> facing.getClockWise();
            case "right" -> facing.getCounterClockWise();
            case "forward" -> facing.getOpposite();
            case "backward" -> facing;
            default -> facing.getOpposite();
        };
        return relative(direction.getStepX() * distance) + " ~ "
                + relative(direction.getStepZ() * distance);
    }

    private static String relative(double value) {
        return Math.abs(value) < 1.0e-9 ? "~" : "~" + trim(value);
    }

    private static String trim(double value) {
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }

    private static String selector(BlockPos machinePos, String role) {
        return "@e[tag=" + tag(machinePos, role) + ",limit=1]";
    }
}
