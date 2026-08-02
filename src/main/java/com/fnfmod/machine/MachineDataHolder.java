package com.fnfmod.machine;

import net.minecraft.nbt.CompoundTag;

/** Common persistent profile/data surface for real and virtual machines. */
public interface MachineDataHolder {
    String profileId();
    CompoundTag machineData();
    void setProfileId(String profileId);
    void setMachineData(CompoundTag data);
}
