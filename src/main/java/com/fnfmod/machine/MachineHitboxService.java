package com.fnfmod.machine;

import com.fnfmod.FnfMod;
import com.fnfmod.block.MachineAnchorBlock;
import com.fnfmod.block.MachineAnchorBlockEntity;
import com.fnfmod.entity.MachineHitboxEntity;
import com.fnfmod.net.FnfPayloads;
import com.fnfmod.session.SessionManager;
import com.fnfmod.world.ModContentScope;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.item.ItemTossEvent;
import net.neoforged.neoforge.event.entity.player.PlayerContainerEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Host-only two-point hitbox builder and persistent virtual-machine placement. */
@EventBusSubscriber(modid = FnfMod.MODID)
public final class MachineHitboxService {

    public static final byte FULL_BLOCKS = 0;
    public static final byte PRECISE = 1;
    private static final double MAX_AXIS_SIZE = 64.0;
    private static final double MIN_PRECISE_SIZE = 1.0 / 16.0;

    private record Draft(byte mode, String profileId, ResourceKey<Level> dimension,
                         Vec3 firstPoint, BlockPos firstCell) {}
    private record Ready(byte mode, String profileId, ResourceKey<Level> dimension, AABB bounds) {}

    private static final Map<UUID, Draft> drafts = new HashMap<>();
    private static final Map<UUID, Ready> ready = new HashMap<>();
    private static final Set<UUID> removing = new HashSet<>();

    private MachineHitboxService() {}

    public static void openBuilder(ServerPlayer player) {
        var server = player.getServer();
        if (server == null || server.isDedicatedServer()
                || (!server.isSingleplayerOwner(player.getGameProfile()) && !player.hasPermissions(2))) {
            player.displayClientMessage(Component.literal(
                    "Funkin' Designer is available only to the singleplayer/LAN host or an operator."), true);
            return;
        }
        String suggested = MachineEditorService.copiedProfile(player)
                .filter(id -> MachineLibrary.find(id).isPresent())
                .orElse(MachineDefinition.DEFAULT_ID);
        PacketDistributor.sendToPlayer(player, new FnfPayloads.OpenHitboxBuilderS2C(
                suggested, ModContentScope.isModWorld()));
    }

    public static void handleBuilder(ServerPlayer player, FnfPayloads.HitboxBuilderC2S payload) {
        if (!canEdit(player)) return;
        if (payload.action() == 1) {
            cancel(player, true);
            return;
        }
        byte mode = payload.mode() == PRECISE ? PRECISE : FULL_BLOCKS;
        MachineDefinition profile = MachineLibrary.find(payload.profileId()).orElse(null);
        if (profile == null) {
            player.displayClientMessage(Component.literal("Selected machine profile is unavailable."), true);
            return;
        }
        clearTemporaryItems(player);
        ready.remove(player.getUUID());
        drafts.put(player.getUUID(), new Draft(mode, profile.id(), player.level().dimension(), null, null));
        sendPreview(player, (byte) 1, mode, null);
        player.displayClientMessage(Component.literal(mode == FULL_BLOCKS
                ? "Hitbox selection started (full blocks). Right-click first corner."
                : "Hitbox selection started (precise). Right-click first corner."), true);
    }

    public static boolean hasDraft(ServerPlayer player) {
        return drafts.containsKey(player.getUUID());
    }

    public static void selectPoint(ServerPlayer player, UseOnContext context) {
        Draft draft = drafts.get(player.getUUID());
        if (draft == null || !draft.dimension().equals(player.level().dimension())) {
            cancel(player, false);
            return;
        }
        Direction face = context.getClickedFace();
        BlockPos cell = context.getClickedPos().relative(face);
        Vec3 normal = new Vec3(face.getStepX(), face.getStepY(), face.getStepZ());
        Vec3 point = draft.mode() == FULL_BLOCKS
                ? Vec3.atLowerCornerOf(cell)
                : context.getClickLocation().add(normal.scale(0.001));

        if (draft.firstPoint() == null) {
            drafts.put(player.getUUID(), new Draft(draft.mode(), draft.profileId(), draft.dimension(), point, cell));
            sendPreview(player, (byte) 2, draft.mode(), new AABB(point, point));
            player.displayClientMessage(Component.literal(
                    "First corner selected. Right-click opposite corner."), true);
            return;
        }

        AABB bounds = draft.mode() == FULL_BLOCKS
                ? fullBounds(draft.firstCell(), cell)
                : preciseBounds(draft.firstPoint(), point);
        if (bounds.getXsize() > MAX_AXIS_SIZE || bounds.getYsize() > MAX_AXIS_SIZE
                || bounds.getZsize() > MAX_AXIS_SIZE) {
            player.displayClientMessage(Component.literal(
                    "Hitbox dimensions cannot exceed 64 blocks per axis."), true);
            return;
        }
        drafts.remove(player.getUUID());
        ready.put(player.getUUID(), new Ready(draft.mode(), draft.profileId(), draft.dimension(), bounds));
        sendPreview(player, (byte) 3, draft.mode(), bounds);
        clearTemporaryItems(player);
        ItemStack token = new ItemStack(FnfMod.MACHINE_ANCHOR_ITEM.get());
        if (!player.getInventory().add(token)) {
            ready.remove(player.getUUID());
            sendPreview(player, (byte) 0, draft.mode(), null);
            player.displayClientMessage(Component.literal(
                    "Inventory is full. Hitbox selection cancelled; free one slot and retry."), true);
            return;
        }
        player.displayClientMessage(Component.literal(
                "Entity hitbox selected: " + size(bounds.getXsize()) + " x " + size(bounds.getYsize())
                        + " x " + size(bounds.getZsize())
                        + ". Place temporary Machine Anchor at stage origin."), true);
    }

    public static void placeAnchor(ServerPlayer player, UseOnContext context) {
        Ready selection = ready.get(player.getUUID());
        if (selection == null || !selection.dimension().equals(player.level().dimension()) || !canEdit(player)) {
            player.displayClientMessage(Component.literal("No valid hitbox selection. Start again with Funkin' Designer."), true);
            clearTemporaryItems(player);
            return;
        }
        ServerLevel level = player.serverLevel();
        BlockPos anchorPos = new BlockPlaceContext(context).getClickedPos();
        if (insideCells(selection.bounds(), anchorPos)) {
            player.displayClientMessage(Component.literal(
                    "Place anchor outside selected hitbox; it represents stage location/facing."), true);
            return;
        }
        if (level.isOutsideBuildHeight(anchorPos) || !level.hasChunkAt(anchorPos)
                || !level.getBlockState(anchorPos).canBeReplaced()) {
            player.displayClientMessage(Component.literal("Anchor location is blocked or unloaded."), true);
            return;
        }

        UUID group = UUID.randomUUID();
        Direction facing = player.getDirection().getOpposite();
        MachineHitboxEntity hitbox = null;
        try {
            level.setBlock(anchorPos, FnfMod.MACHINE_ANCHOR.get().defaultBlockState()
                    .setValue(MachineAnchorBlock.FACING, facing), 3);
            if (!(level.getBlockEntity(anchorPos) instanceof MachineAnchorBlockEntity anchor)) {
                throw new IllegalStateException("anchor block entity missing");
            }
            anchor.configure(group, selection.profileId(), selection.bounds());
            hitbox = new MachineHitboxEntity(FnfMod.MACHINE_HITBOX_ENTITY.get(), level);
            hitbox.configure(anchorPos, group, selection.bounds());
            if (!level.addFreshEntity(hitbox)) throw new IllegalStateException("hitbox entity spawn rejected");
        } catch (Exception e) {
            if (hitbox != null) hitbox.discard();
            level.removeBlock(anchorPos, false);
            FnfMod.LOGGER.error("Could not place virtual machine hitbox", e);
            player.displayClientMessage(Component.literal("Could not place hitbox; selection kept for retry."), true);
            return;
        }

        ready.remove(player.getUUID());
        sendPreview(player, (byte) 0, selection.mode(), null);
        context.getItemInHand().shrink(1);
        clearTemporaryItems(player);
        player.displayClientMessage(Component.literal(
                "Virtual machine created. Hold Funkin' Designer to see/remove anchor and hitbox."), true);
    }

    public static void requestRemovalAt(ServerPlayer player, BlockPos pos) {
        if (!canEdit(player)) return;
        ServerLevel level = player.serverLevel();
        if (!(level.getBlockEntity(pos) instanceof MachineAnchorBlockEntity anchor)) return;
        PacketDistributor.sendToPlayer(player,
                new FnfPayloads.ConfirmHitboxRemovalS2C(pos, anchor.groupId()));
    }

    public static void requestRemoval(ServerPlayer player, MachineHitboxEntity entity) {
        if (!canEdit(player) || entity == null) return;
        BlockPos anchorPos = entity.anchorPos();
        if (!(player.level().getBlockEntity(anchorPos) instanceof MachineAnchorBlockEntity anchor)) {
            entity.discard();
            return;
        }
        if (entity.groupId().isEmpty() || !entity.groupId().get().equals(anchor.groupId())) return;
        PacketDistributor.sendToPlayer(player,
                new FnfPayloads.ConfirmHitboxRemovalS2C(anchorPos, anchor.groupId()));
    }

    public static void confirmRemoval(ServerPlayer player, FnfPayloads.ConfirmHitboxRemovalC2S payload) {
        if (!canEdit(player) || payload == null || !canReachAnchor(player, payload.anchorPos())) return;
        if (!(player.level().getBlockEntity(payload.anchorPos()) instanceof MachineAnchorBlockEntity anchor)
                || !payload.groupId().equals(anchor.groupId())) {
            player.displayClientMessage(Component.literal("Virtual machine changed or no longer exists."), true);
            return;
        }
        removeAnchor(player, player.serverLevel(), payload.anchorPos(), anchor);
    }

    private static void removeAnchor(ServerPlayer player, ServerLevel level, BlockPos anchorPos,
                                     MachineAnchorBlockEntity anchor) {
        UUID id = anchor.groupId();
        if (!removing.add(id)) return;
        try {
            SessionManager.onMachineRemoved(level, anchorPos);
            AABB search = anchor.selectionBounds().inflate(1.0);
            for (MachineHitboxEntity hitbox : level.getEntitiesOfClass(MachineHitboxEntity.class, search,
                    entity -> entity.groupId().isPresent() && id.equals(entity.groupId().get()))) {
                hitbox.discard();
            }
            if (level.getBlockEntity(anchorPos) instanceof MachineAnchorBlockEntity current
                    && id.equals(current.groupId())) {
                level.removeBlock(anchorPos, false);
            }
            player.displayClientMessage(Component.literal(
                    "Virtual machine removed. Make a new hitbox selection to recreate it."), true);
        } finally {
            removing.remove(id);
        }
    }

    public static void cancel(ServerPlayer player, boolean message) {
        drafts.remove(player.getUUID());
        ready.remove(player.getUUID());
        clearTemporaryItems(player);
        sendPreview(player, (byte) 0, FULL_BLOCKS, null);
        if (message) player.displayClientMessage(Component.literal("Hitbox selection cancelled."), true);
    }

    public static void clearPlayer(ServerPlayer player) {
        drafts.remove(player.getUUID());
        ready.remove(player.getUUID());
        clearTemporaryItems(player);
    }

    /** Allows menu packets to reference an anchor farther away than normal block reach. */
    public static boolean canReachAnchor(ServerPlayer player, BlockPos anchorPos) {
        if (player.distanceToSqr(anchorPos.getX() + 0.5, anchorPos.getY() + 0.5,
                anchorPos.getZ() + 0.5) <= 64) return true;
        return !player.level().getEntitiesOfClass(MachineHitboxEntity.class,
                player.getBoundingBox().inflate(8.0), entity -> entity.anchorPos().equals(anchorPos)).isEmpty();
    }

    public static boolean canEdit(ServerPlayer player) {
        var server = player.getServer();
        return server != null && !server.isDedicatedServer() && ModContentScope.isModWorld()
                && (server.isSingleplayerOwner(player.getGameProfile()) || player.hasPermissions(2));
    }

    private static void clearTemporaryItems(ServerPlayer player) {
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            if (player.getInventory().getItem(i).is(FnfMod.MACHINE_ANCHOR_ITEM.get())) {
                player.getInventory().setItem(i, ItemStack.EMPTY);
            }
        }
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        for (ServerPlayer player : event.getServer().getPlayerList().getPlayers()) {
            sanitizeTemporaryItem(player, player.containerMenu);
        }
    }

    @SubscribeEvent
    public static void onContainerClose(PlayerContainerEvent.Close event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            sanitizeTemporaryItem(player, event.getContainer());
        }
    }

    @SubscribeEvent
    public static void onItemToss(ItemTossEvent event) {
        if (!event.getEntity().getItem().is(FnfMod.MACHINE_ANCHOR_ITEM.get())) return;
        event.getEntity().discard();
        if (event.getPlayer() instanceof ServerPlayer player) cancel(player, false);
    }

    @SubscribeEvent
    public static void onEntityJoin(EntityJoinLevelEvent event) {
        if (!event.getLevel().isClientSide && event.getEntity() instanceof net.minecraft.world.entity.item.ItemEntity item
                && item.getItem().is(FnfMod.MACHINE_ANCHOR_ITEM.get())) {
            item.discard();
        }
    }

    @SubscribeEvent
    public static void onAnchorBreak(BlockEvent.BreakEvent event) {
        if (!(event.getPlayer() instanceof ServerPlayer player)
                || !(event.getState().getBlock() instanceof MachineAnchorBlock)) return;
        event.setCanceled(true);
        requestRemovalAt(player, event.getPos());
    }

    private static void sanitizeTemporaryItem(ServerPlayer player,
                                              net.minecraft.world.inventory.AbstractContainerMenu menu) {
        boolean hasSelection = ready.containsKey(player.getUUID());
        boolean external = false;
        if (menu != null) {
            for (var slot : menu.slots) {
                if (slot.hasItem() && slot.getItem().is(FnfMod.MACHINE_ANCHOR_ITEM.get())
                        && slot.container != player.getInventory()) {
                    slot.set(ItemStack.EMPTY);
                    external = true;
                }
            }
        }
        int inventoryTokens = 0;
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            if (player.getInventory().getItem(i).is(FnfMod.MACHINE_ANCHOR_ITEM.get())) inventoryTokens++;
        }
        if (!hasSelection || external || inventoryTokens != 1) {
            clearTemporaryItems(player);
            if (hasSelection && (external || inventoryTokens != 1)) {
                ready.remove(player.getUUID());
                sendPreview(player, (byte) 0, FULL_BLOCKS, null);
                player.displayClientMessage(Component.literal(
                        "Temporary Machine Anchor left your inventory. Hitbox selection cancelled."), true);
            }
        }
    }

    private static AABB fullBounds(BlockPos a, BlockPos b) {
        return new AABB(Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()), Math.min(a.getZ(), b.getZ()),
                Math.max(a.getX(), b.getX()) + 1, Math.max(a.getY(), b.getY()) + 1, Math.max(a.getZ(), b.getZ()) + 1);
    }

    private static AABB preciseBounds(Vec3 a, Vec3 b) {
        double minX = Math.min(a.x, b.x), minY = Math.min(a.y, b.y), minZ = Math.min(a.z, b.z);
        double maxX = Math.max(a.x, b.x), maxY = Math.max(a.y, b.y), maxZ = Math.max(a.z, b.z);
        if (maxX - minX < MIN_PRECISE_SIZE) maxX = minX + MIN_PRECISE_SIZE;
        if (maxY - minY < MIN_PRECISE_SIZE) maxY = minY + MIN_PRECISE_SIZE;
        if (maxZ - minZ < MIN_PRECISE_SIZE) maxZ = minZ + MIN_PRECISE_SIZE;
        return new AABB(minX, minY, minZ, maxX, maxY, maxZ);
    }

    private static boolean insideCells(AABB bounds, BlockPos pos) {
        return pos.getX() >= minCell(bounds.minX) && pos.getX() <= maxCell(bounds.maxX)
                && pos.getY() >= minCell(bounds.minY) && pos.getY() <= maxCell(bounds.maxY)
                && pos.getZ() >= minCell(bounds.minZ) && pos.getZ() <= maxCell(bounds.maxZ);
    }

    private static int minCell(double value) { return (int) Math.floor(value); }
    private static int maxCell(double value) { return (int) Math.floor(Math.nextDown(value)); }

    private static String size(double value) {
        return String.format(java.util.Locale.ROOT, "%.2f", value);
    }

    private static void sendPreview(ServerPlayer player, byte stage, byte mode, AABB bounds) {
        double minX = bounds == null ? 0 : bounds.minX;
        double minY = bounds == null ? 0 : bounds.minY;
        double minZ = bounds == null ? 0 : bounds.minZ;
        double maxX = bounds == null ? 0 : bounds.maxX;
        double maxY = bounds == null ? 0 : bounds.maxY;
        double maxZ = bounds == null ? 0 : bounds.maxZ;
        PacketDistributor.sendToPlayer(player, new FnfPayloads.HitboxSelectionStateS2C(
                stage, mode, minX, minY, minZ, maxX, maxY, maxZ));
    }
}
