package com.fnfmod.item;

import com.fnfmod.block.FunkinMachineBlock;
import com.fnfmod.machine.MachineEditorService;
import com.fnfmod.machine.MachineHitboxService;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

/** Tool for editing, copying, and applying Funkin' Machine profiles. */
public final class FunkinDesignerItem extends Item {

    public FunkinDesignerItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        var block = level.getBlockState(context.getClickedPos()).getBlock();
        if (!level.isClientSide && context.getPlayer() instanceof ServerPlayer player) {
            if (block instanceof com.fnfmod.block.ChunkLoaderPointBlock) {
                com.fnfmod.world.ChunkLoaderPointService.openEditor(player, context.getClickedPos());
            } else if (block instanceof com.fnfmod.block.MachineAnchorBlock) {
                MachineHitboxService.requestRemovalAt(player, context.getClickedPos());
            } else if (block instanceof FunkinMachineBlock) {
                if (player.isShiftKeyDown()) MachineEditorService.copyOrApply(player, context.getClickedPos());
                else MachineEditorService.open(player, context.getClickedPos());
            } else if (MachineHitboxService.hasDraft(player)) {
                MachineHitboxService.selectPoint(player, context);
            } else {
                player.displayClientMessage(net.minecraft.network.chat.Component.literal(
                        "Right-click air with Funkin' Designer to start a hitbox selection."), true);
            }
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        if (!level.isClientSide && player instanceof ServerPlayer serverPlayer && player.isShiftKeyDown()) {
            MachineEditorService.clearCopied(serverPlayer);
            MachineHitboxService.cancel(serverPlayer, false);
            return InteractionResultHolder.success(player.getItemInHand(hand));
        }
        if (!level.isClientSide && player instanceof ServerPlayer serverPlayer) {
            MachineHitboxService.openBuilder(serverPlayer);
        }
        return InteractionResultHolder.success(player.getItemInHand(hand));
    }
}
