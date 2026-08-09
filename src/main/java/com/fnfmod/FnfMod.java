package com.fnfmod;

import com.fnfmod.block.FunkinMachineBlock;
import com.fnfmod.block.MachineAnchorBlock;
import com.fnfmod.block.ChunkLoaderPointBlock;
import com.fnfmod.entity.MachineHitboxEntity;
import com.fnfmod.world.ChunkLoaderPointService;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import com.fnfmod.item.FunkinDesignerItem;
import com.fnfmod.item.MachineAnchorItem;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod(FnfMod.MODID)
public class FnfMod {
    public static final String MODID = "fnfmod";
    public static final Logger LOGGER = LoggerFactory.getLogger("fnfmod");

    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(MODID);
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MODID);
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, MODID);
    public static final DeferredRegister<EntityType<?>> ENTITY_TYPES =
            DeferredRegister.create(Registries.ENTITY_TYPE, MODID);

    public static final DeferredBlock<Block> FUNKIN_MACHINE = BLOCKS.register("funkin_machine",
            () -> new FunkinMachineBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_PURPLE)
                    .strength(2.0f)
                    .sound(SoundType.WOOD)));

    public static final DeferredBlock<Block> MACHINE_ANCHOR = BLOCKS.register("machine_anchor",
            () -> new MachineAnchorBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.NONE).noCollission().noOcclusion().strength(-1.0f, 3_600_000f)));

    public static final DeferredBlock<Block> CHUNK_LOADER_POINT = BLOCKS.register("chunk_loader_point",
            () -> new ChunkLoaderPointBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.NONE).noCollission().noOcclusion().strength(-1.0f, 3_600_000f)));

    public static final DeferredItem<BlockItem> FUNKIN_MACHINE_ITEM =
            ITEMS.registerSimpleBlockItem("funkin_machine", FUNKIN_MACHINE);

    public static final DeferredItem<FunkinDesignerItem> FUNKIN_DESIGNER =
            ITEMS.register("funkin_designer", () -> new FunkinDesignerItem(
                    new Item.Properties().stacksTo(1)));

    /** Not in creative inventory: issued only after a valid Designer selection. */
    public static final DeferredItem<MachineAnchorItem> MACHINE_ANCHOR_ITEM =
            ITEMS.register("machine_anchor", () -> new MachineAnchorItem(new Item.Properties().stacksTo(1)));

    public static final DeferredItem<BlockItem> CHUNK_LOADER_POINT_ITEM =
            ITEMS.registerSimpleBlockItem("chunk_loader_point", CHUNK_LOADER_POINT);

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<com.fnfmod.block.FunkinMachineBlockEntity>>
            FUNKIN_MACHINE_BLOCK_ENTITY = BLOCK_ENTITIES.register("funkin_machine", () ->
                    BlockEntityType.Builder.of(com.fnfmod.block.FunkinMachineBlockEntity::new,
                            FUNKIN_MACHINE.get()).build(null));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<com.fnfmod.block.MachineAnchorBlockEntity>>
            MACHINE_ANCHOR_BLOCK_ENTITY = BLOCK_ENTITIES.register("machine_anchor", () ->
                    BlockEntityType.Builder.of(com.fnfmod.block.MachineAnchorBlockEntity::new,
                            MACHINE_ANCHOR.get()).build(null));
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<com.fnfmod.block.ChunkLoaderPointBlockEntity>>
            CHUNK_LOADER_POINT_BLOCK_ENTITY = BLOCK_ENTITIES.register("chunk_loader_point", () ->
                    BlockEntityType.Builder.of(com.fnfmod.block.ChunkLoaderPointBlockEntity::new,
                            CHUNK_LOADER_POINT.get()).build(null));
    public static final DeferredHolder<EntityType<?>, EntityType<MachineHitboxEntity>> MACHINE_HITBOX_ENTITY =
            ENTITY_TYPES.register("machine_hitbox", () -> EntityType.Builder
                    .<MachineHitboxEntity>of(MachineHitboxEntity::new, MobCategory.MISC)
                    .sized(1f, 1f).clientTrackingRange(96).updateInterval(20)
                    .build("machine_hitbox"));

    public FnfMod(IEventBus modEventBus, ModContainer modContainer) {
        BLOCKS.register(modEventBus);
        ITEMS.register(modEventBus);
        BLOCK_ENTITIES.register(modEventBus);
        ENTITY_TYPES.register(modEventBus);
        modEventBus.addListener(this::addCreative);
        modEventBus.addListener(ChunkLoaderPointService::registerTicketController);
    }

    private void addCreative(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.FUNCTIONAL_BLOCKS) {
            event.accept(FUNKIN_MACHINE_ITEM.get());
            event.accept(FUNKIN_DESIGNER.get());
            event.accept(CHUNK_LOADER_POINT_ITEM.get());
        }
    }

    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MODID, path);
    }
}
