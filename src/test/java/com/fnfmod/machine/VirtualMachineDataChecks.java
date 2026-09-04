package com.fnfmod.machine;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;

/** Persistent tag lookup checks, independent of Minecraft's renderer/server startup. */
public final class VirtualMachineDataChecks {
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        VirtualMachineData data = new VirtualMachineData();
        BlockPos stage = new BlockPos(12345, 72, -5678);
        BlockPos other = new BlockPos(-400, -60, 250);
        data.put(stage, "  Earrings_Stage  ");
        check(data.find("EARRINGS_STAGE").equals(java.util.List.of(stage)), "case-insensitive lookup");
        data.put(other, "earrings_stage");
        check(data.find("earrings_stage").size() == 2, "duplicates stay detectable");
        data.put(other, "other_stage");
        check(data.find("earrings_stage").size() == 1, "rename removes old association");
        CompoundTag saved = data.save(new CompoundTag(), null);
        VirtualMachineData restored = VirtualMachineData.load(saved, null);
        check(restored.find("earrings_stage").equals(java.util.List.of(stage)), "saved unloaded-stage lookup");
        check(restored.find("other_stage").equals(java.util.List.of(other)), "negative coordinates survive save");
        restored.remove(stage);
        check(restored.find("earrings_stage").isEmpty(), "broken anchor removed");
        restored.put(other, "../bad");
        check(restored.find("other_stage").isEmpty(), "invalid/blank tag clears association");
        check(VirtualMachineData.normalizeTag("x".repeat(65)).isEmpty(), "tag length bounded");
        check(VirtualMachineData.normalizeTag("x".repeat(64)).length() == 64, "64-character tag supported");
        System.out.println("Virtual machine tag/persistence checks passed.");
    }
}
