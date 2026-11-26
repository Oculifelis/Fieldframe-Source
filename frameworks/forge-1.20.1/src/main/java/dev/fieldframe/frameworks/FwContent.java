package dev.fieldframe.frameworks;

import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

import java.util.List;

/** Blocks, items, block entities and the creative tab (Forge 1.20.1; derived from the 26.1.2 FwContent). */
public final class FwContent {
    private FwContent() {
    }

    public static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(ForgeRegistries.BLOCKS, Frameworks.MOD_ID);
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, Frameworks.MOD_ID);
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES = DeferredRegister.create(ForgeRegistries.BLOCK_ENTITY_TYPES, Frameworks.MOD_ID);
    public static final DeferredRegister<CreativeModeTab> TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, Frameworks.MOD_ID);

    public static final RegistryObject<FrameCasingBlock> CASING = BLOCKS.register("frame_casing", () -> new FrameCasingBlock(BlockBehaviour.Properties.of().mapColor(MapColor.METAL).strength(3.5f, 6f).sound(SoundType.METAL).requiresCorrectToolForDrops()));
    public static final RegistryObject<FrameCoreBlock> CORE = BLOCKS.register("frame_core", () -> new FrameCoreBlock(BlockBehaviour.Properties.of().mapColor(MapColor.METAL).strength(4f, 8f).sound(SoundType.METAL).requiresCorrectToolForDrops().lightLevel(s -> 7)));
    public static final RegistryObject<Block> CRUCIBLE = BLOCKS.register("frame_crucible", () -> new Block(BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_ORANGE).strength(3.5f, 6f).sound(SoundType.METAL).requiresCorrectToolForDrops().lightLevel(s -> 5)));
    public static final RegistryObject<Block> COIL = BLOCKS.register("starcore_coil", () -> new Block(BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_PURPLE).strength(4f, 8f).sound(SoundType.METAL).requiresCorrectToolForDrops().lightLevel(s -> 4)));
    public static final RegistryObject<GantryControllerBlock> GANTRY_CONTROLLER = BLOCKS.register("gantry_controller", () -> new GantryControllerBlock(BlockBehaviour.Properties.of().mapColor(MapColor.METAL).strength(3.5f, 6f).sound(SoundType.METAL).requiresCorrectToolForDrops()));
    public static final RegistryObject<GantryRailBlock> GANTRY_RAIL = BLOCKS.register("gantry_rail", () -> new GantryRailBlock(BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_YELLOW).strength(2.5f, 6f).sound(SoundType.METAL).requiresCorrectToolForDrops()));
    public static final RegistryObject<Block> GANTRY_HEAD = BLOCKS.register("gantry_head", () -> new Block(BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_YELLOW).strength(-1f, 3_600_000f).sound(SoundType.METAL).noLootTable().lightLevel(s -> 9)));
    public static final RegistryObject<Block> CELL = component("frame_cell");
    public static final RegistryObject<Block> TANK = component("frame_tank");
    public static final RegistryObject<Block> VAULT = component("frame_vault");

    public static final RegistryObject<Block> FIN = BLOCKS.register("cooling_fin", () -> new Block(BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_LIGHT_BLUE).strength(3f, 6f).sound(SoundType.METAL).requiresCorrectToolForDrops().noOcclusion()));
    public static final RegistryObject<Block> ELECTRODE = BLOCKS.register("arc_electrode", () -> new Block(BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_CYAN).strength(3.5f, 6f).sound(SoundType.METAL).requiresCorrectToolForDrops().lightLevel(s -> 3)));
    public static final RegistryObject<BlockItem> CASING_ITEM = ITEMS.register("frame_casing", () -> new BlockItem(CASING.get(), new Item.Properties()));
    public static final RegistryObject<BlockItem> CORE_ITEM = ITEMS.register("frame_core", () -> new BlockItem(CORE.get(), new Item.Properties()));
    public static final RegistryObject<BlockItem> CELL_ITEM = ITEMS.register("frame_cell", () -> new BlockItem(CELL.get(), new Item.Properties()));
    public static final RegistryObject<BlockItem> TANK_ITEM = ITEMS.register("frame_tank", () -> new BlockItem(TANK.get(), new Item.Properties()));
    public static final RegistryObject<BlockItem> VAULT_ITEM = ITEMS.register("frame_vault", () -> new BlockItem(VAULT.get(), new Item.Properties()));
    public static final RegistryObject<BlockItem> CRUCIBLE_ITEM = ITEMS.register("frame_crucible", () -> new BlockItem(CRUCIBLE.get(), new Item.Properties()));
    public static final RegistryObject<BlockItem> FIN_ITEM = ITEMS.register("cooling_fin", () -> new BlockItem(FIN.get(), new Item.Properties()));
    public static final RegistryObject<BlockItem> ELECTRODE_ITEM = ITEMS.register("arc_electrode", () -> new BlockItem(ELECTRODE.get(), new Item.Properties()));
    public static final RegistryObject<BlockItem> COIL_ITEM = ITEMS.register("starcore_coil", () -> new BlockItem(COIL.get(), new Item.Properties()));
    public static final RegistryObject<BlockItem> GANTRY_CONTROLLER_ITEM = ITEMS.register("gantry_controller", () -> new BlockItem(GANTRY_CONTROLLER.get(), new Item.Properties()));
    public static final RegistryObject<BlockItem> GANTRY_RAIL_ITEM = ITEMS.register("gantry_rail", () -> new BlockItem(GANTRY_RAIL.get(), new Item.Properties()));

    public static final RegistryObject<BlockEntityType<FrameCasingEntity>> CASING_BE = BLOCK_ENTITIES.register("frame_casing",
            () -> BlockEntityType.Builder.of(FrameCasingEntity::new, CASING.get()).build(null));
    public static final RegistryObject<BlockEntityType<GantryControllerEntity>> GANTRY_BE = BLOCK_ENTITIES.register("gantry_controller",
            () -> BlockEntityType.Builder.of(GantryControllerEntity::new, GANTRY_CONTROLLER.get()).build(null));
    public static final RegistryObject<BlockEntityType<FrameCoreEntity>> CORE_BE = BLOCK_ENTITIES.register("frame_core",
            () -> BlockEntityType.Builder.of(FrameCoreEntity::new, CORE.get()).build(null));

    public static final RegistryObject<CreativeModeTab> TAB = TABS.register("main", () -> CreativeModeTab.builder()
            .title(Component.translatable("itemGroup.fieldframe_frameworks"))
            .icon(() -> new ItemStack(CORE_ITEM.get()))
            .displayItems((params, out) -> {
                List.of(CASING_ITEM, CORE_ITEM, CELL_ITEM, TANK_ITEM, VAULT_ITEM, CRUCIBLE_ITEM, FIN_ITEM, ELECTRODE_ITEM, COIL_ITEM, GANTRY_CONTROLLER_ITEM, GANTRY_RAIL_ITEM).forEach(i -> out.accept(i.get()));
                out.accept(FwFluids.DEUTERIUM_BUCKET.get());
                for (dev.fieldframe.api.id.FfId m : List.of(StarcoreModules.FIELD_MAGNET, StarcoreModules.PLASMA_INJECTOR, StarcoreModules.PLASMA_PROBE)) {
                    net.minecraft.world.item.Item item = dev.fieldframe.forge.api.FieldframeForgeApi.moduleItem(m);
                    if (item != null) {
                        out.accept(item);
                    }
                }
            })
            .build());

    private static RegistryObject<Block> component(String name) {
        return BLOCKS.register(name, () -> new Block(BlockBehaviour.Properties.of().mapColor(MapColor.METAL).strength(3.5f, 6f).sound(SoundType.METAL).requiresCorrectToolForDrops()));
    }

    static void register(IEventBus bus) {
        BLOCKS.register(bus);
        ITEMS.register(bus);
        BLOCK_ENTITIES.register(bus);
        TABS.register(bus);
    }
}
