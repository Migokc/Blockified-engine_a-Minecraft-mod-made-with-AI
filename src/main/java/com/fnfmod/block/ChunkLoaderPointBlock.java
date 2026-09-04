package com.fnfmod.block;

import com.fnfmod.FnfMod;
import com.fnfmod.world.ChunkLoaderPointService;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/** Invisible persistent chunk-loader point, targetable only with its authoring tools. */
public final class ChunkLoaderPointBlock extends BaseEntityBlock {

    public static final MapCodec<ChunkLoaderPointBlock> CODEC = simpleCodec(ChunkLoaderPointBlock::new);

    public ChunkLoaderPointBlock(Properties properties) { super(properties); }

    @Override protected MapCodec<? extends BaseEntityBlock> codec() { return CODEC; }
    @Override protected RenderShape getRenderShape(BlockState state) { return RenderShape.INVISIBLE; }
    @Override public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new ChunkLoaderPointBlockEntity(pos, state);
    }

    private static boolean holdingTool(Player player) {
        return player.getMainHandItem().is(FnfMod.FUNKIN_DESIGNER.get())
                || player.getOffhandItem().is(FnfMod.FUNKIN_DESIGNER.get());
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return context.isHoldingItem(FnfMod.FUNKIN_DESIGNER.get()) ? Shapes.block() : Shapes.empty();
    }

    @Override protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos,
                                                       CollisionContext context) { return Shapes.empty(); }

    /** Liquids must flow around the invisible point instead of replacing it. */
    @Override
    protected boolean canBeReplaced(BlockState state, Fluid fluid) {
        return false;
    }

    @Override
    protected float getDestroyProgress(BlockState state, Player player, BlockGetter level, BlockPos pos) {
        return holdingTool(player) ? 1.0f : 0.0f;
    }

    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
                                               Player player, net.minecraft.world.InteractionHand hand,
                                               BlockHitResult hit) {
        if (!stack.is(FnfMod.FUNKIN_DESIGNER.get())) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        if (!level.isClientSide && player instanceof ServerPlayer serverPlayer) {
            ChunkLoaderPointService.openEditor(serverPlayer, pos);
        }
        return ItemInteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                                Player player, BlockHitResult hit) {
        return InteractionResult.PASS;
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState next, boolean moved) {
        if (!state.is(next.getBlock()) && level instanceof ServerLevel serverLevel
                && level.getBlockEntity(pos) instanceof ChunkLoaderPointBlockEntity point) {
            ChunkLoaderPointService.onRemoved(serverLevel, pos, point.radius(), point.enabled());
        }
        super.onRemove(state, level, pos, next, moved);
    }
}
