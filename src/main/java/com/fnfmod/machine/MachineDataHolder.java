package com.fnfmod.machine;

import net.minecraft.nbt.CompoundTag;

/** Common persistent profile/data surface for real and virtual machines. */
public interface MachineDataHolder {
    String profileId();
    default String machineTag() { return ""; }
    CompoundTag machineData();
    void setProfileId(String profileId);
    default void setMachineTag(String tag) { }
    void setMachineData(CompoundTag data);
}
