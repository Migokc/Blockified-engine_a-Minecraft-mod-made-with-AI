package com.fnfmod.chart;

import net.minecraft.core.BlockPos;

/** Mod-only command placeholders used by chart events. */
public final class CommandEventPlaceholders {
    public static final String PLAYER = "<player>";
    public static final String OPPONENT = "<opponent>";
    public static final String SPEAKERS = "<speakers>";

    private CommandEventPlaceholders() {}

    public static String tag(BlockPos machinePos, String role) {
        return "fnfmod_" + role + "_" + Long.toUnsignedString(machinePos.asLong(), 36);
    }

    public static String expand(String command, BlockPos machinePos) {
        if (command == null) return "";
        return command
                .replace(PLAYER, selector(machinePos, "player"))
                .replace(OPPONENT, selector(machinePos, "opponent"))
                .replace(SPEAKERS, selector(machinePos, "speakers"));
    }

    private static String selector(BlockPos machinePos, String role) {
        return "@e[tag=" + tag(machinePos, role) + ",limit=1]";
    }
}
