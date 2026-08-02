package com.fnfmod.item;

import com.fnfmod.machine.MachineHitboxService;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.context.UseOnContext;

/** Temporary placement token produced after a hitbox selection. */
public final class MachineAnchorItem extends Item {
    public MachineAnchorItem(Properties properties) { super(properties); }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        if (!context.getLevel().isClientSide && context.getPlayer() instanceof ServerPlayer player) {
            MachineHitboxService.placeAnchor(player, context);
        }
        return InteractionResult.sidedSuccess(context.getLevel().isClientSide);
    }
}
