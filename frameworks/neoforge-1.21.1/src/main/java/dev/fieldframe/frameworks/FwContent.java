package dev.fieldframe.frameworks;

import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.List;

/** Blocks, items, block entities and the creative tab of Fieldframe Frameworks. */
public final class FwContent {
    private FwContent() {
    }

    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(Frameworks.MOD_ID);
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(Frameworks.MOD_ID);
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES = DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, Frameworks.MOD_ID);
    public static final DeferredRegister<CreativeModeTab> TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, Frameworks.MOD_ID);

    public static final DeferredBlock<FrameCasingBlock> CASING = BLOCKS.registerBlock("frame_casing", FrameCasingBlock::new,
            net.minecraft.world.level.block.state.BlockBehaviour.Properties.of().mapColor(MapColor.METAL).strength(3.5f, 6f).sound(SoundType.METAL).requiresCorrectToolForDrops());
    public static final DeferredBlock<FrameCoreBlock> CORE = BLOCKS.registerBlock("frame_core", FrameCoreBlock::new,
            net.minecraft.world.level.block.state.BlockBehaviour.Properties.of().mapColor(MapColor.METAL).strength(4f, 8f).sound(SoundType.METAL).requiresCorrectToolForDrops().lightLevel(s -> 7));
    public static final DeferredBlock<Block> CELL = component("frame_cell");
    public static final DeferredBlock<Block> TANK = component("frame_tank");
    public static final DeferredBlock<Block> VAULT = component("frame_vault");
    public static final DeferredBlock<Block> CRUCIBLE = BLOCKS.registerBlock("frame_crucible", Block::new,
            net.minecraft.world.level.block.state.BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_ORANGE).strength(3.5f, 6f).sound(SoundType.METAL).requiresCorrectToolForDrops().lightLevel(s -> 5));

    public static final DeferredBlock<Block> FIN = BLOCKS.registerBlock("cooling_fin", Block::new,
            net.minecraft.world.level.block.state.BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_LIGHT_BLUE).strength(3f, 6f).sound(SoundType.METAL).requiresCorrectToolForDrops().noOcclusion());
    public static final DeferredBlock<Block> ELECTRODE = BLOCKS.registerBlock("arc_electrode", Block::new,
            net.minecraft.world.level.block.state.BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_CYAN).strength(3.5f, 6f).sound(SoundType.METAL).requiresCorrectToolForDrops().lightLevel(s -> 3));

    public static final DeferredBlock<Block> COIL = BLOCKS.registerBlock("starcore_coil", Block::new,
            net.minecraft.world.level.block.state.BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_PURPLE).strength(4f, 8f).sound(SoundType.METAL).requiresCorrectToolForDrops().lightLevel(s -> 4));
    public static final DeferredBlock<GantryControllerBlock> GANTRY_CONTROLLER = BLOCKS.registerBlock("gantry_controller", GantryControllerBlock::new,
            net.minecraft.world.level.block.state.BlockBehaviour.Properties.of().mapColor(MapColor.METAL).strength(3.5f, 6f).sound(SoundType.METAL).requiresCorrectToolForDrops());
    public static final DeferredBlock<GantryRailBlock> GANTRY_RAIL = BLOCKS.registerBlock("gantry_rail", GantryRailBlock::new,
            net.minecraft.world.level.block.state.BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_YELLOW).strength(2.5f, 6f).sound(SoundType.METAL).requiresCorrectToolForDrops());
    public static final DeferredBlock<Block> GANTRY_HEAD = BLOCKS.registerBlock("gantry_head", Block::new,
            net.minecraft.world.level.block.state.BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_YELLOW).strength(-1f, 3_600_000f).sound(SoundType.METAL).noLootTable().lightLevel(s -> 9));

    public static final DeferredItem<BlockItem> CASING_ITEM = ITEMS.registerSimpleBlockItem("frame_casing", CASING);
    public static final DeferredItem<BlockItem> CORE_ITEM = ITEMS.registerSimpleBlockItem("frame_core", CORE);
    public static final DeferredItem<BlockItem> CELL_ITEM = ITEMS.registerSimpleBlockItem("frame_cell", CELL);
    public static final DeferredItem<BlockItem> TANK_ITEM = ITEMS.registerSimpleBlockItem("frame_tank", TANK);
    public static final DeferredItem<BlockItem> VAULT_ITEM = ITEMS.registerSimpleBlockItem("frame_vault", VAULT);
    public static final DeferredItem<BlockItem> CRUCIBLE_ITEM = ITEMS.registerSimpleBlockItem("frame_crucible", CRUCIBLE);
    public static final DeferredItem<BlockItem> FIN_ITEM = ITEMS.registerSimpleBlockItem("cooling_fin", FIN);
    public static final DeferredItem<BlockItem> ELECTRODE_ITEM = ITEMS.registerSimpleBlockItem("arc_electrode", ELECTRODE);
    public static final DeferredItem<BlockItem> COIL_ITEM = ITEMS.registerSimpleBlockItem("starcore_coil", COIL);
    public static final DeferredItem<BlockItem> GANTRY_CONTROLLER_ITEM = ITEMS.registerSimpleBlockItem("gantry_controller", GANTRY_CONTROLLER);
    public static final DeferredItem<BlockItem> GANTRY_RAIL_ITEM = ITEMS.registerSimpleBlockItem("gantry_rail", GANTRY_RAIL);

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<FrameCasingEntity>> CASING_BE = BLOCK_ENTITIES.register("frame_casing",
            () -> BlockEntityType.Builder.of(FrameCasingEntity::new, CASING.get()).build(null));
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<GantryControllerEntity>> GANTRY_BE = BLOCK_ENTITIES.register("gantry_controller",
            () -> BlockEntityType.Builder.of(GantryControllerEntity::new, GANTRY_CONTROLLER.get()).build(null));
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<FrameCoreEntity>> CORE_BE = BLOCK_ENTITIES.register("frame_core",
            () -> BlockEntityType.Builder.of(FrameCoreEntity::new, CORE.get()).build(null));

    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> TAB = TABS.register("main", () -> CreativeModeTab.builder()
            .title(Component.translatable("itemGroup.fieldframe_frameworks"))
            .icon(() -> new ItemStack(CORE_ITEM.get()))
            .displayItems((params, out) -> {
                List.of(CASING_ITEM, CORE_ITEM, CELL_ITEM, TANK_ITEM, VAULT_ITEM, CRUCIBLE_ITEM, FIN_ITEM, ELECTRODE_ITEM, COIL_ITEM, GANTRY_CONTROLLER_ITEM, GANTRY_RAIL_ITEM).forEach(i -> out.accept(i.get()));
                out.accept(FwFluids.DEUTERIUM_BUCKET.get());
                for (dev.fieldframe.api.id.FfId m : List.of(StarcoreModules.FIELD_MAGNET, StarcoreModules.PLASMA_INJECTOR, StarcoreModules.PLASMA_PROBE)) {
                    net.minecraft.world.item.Item item = dev.fieldframe.neoforge.api.FieldframeForgeApi.moduleItem(m);
                    if (item != null) {
                        out.accept(item);
                    }
                }
            })
            .build());

    private static DeferredBlock<Block> component(String name) {
        return BLOCKS.registerBlock(name, Block::new, net.minecraft.world.level.block.state.BlockBehaviour.Properties.of().mapColor(MapColor.METAL).strength(3.5f, 6f).sound(SoundType.METAL).requiresCorrectToolForDrops());
    }

    static void register(IEventBus bus) {
        BLOCKS.register(bus);
        ITEMS.register(bus);
        BLOCK_ENTITIES.register(bus);
        TABS.register(bus);
    }
}
