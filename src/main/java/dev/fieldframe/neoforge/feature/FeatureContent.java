package dev.fieldframe.neoforge.feature;

import dev.fieldframe.core.endertech.EnderTechRules;
import dev.fieldframe.core.endertech.TankStructure;
import dev.fieldframe.core.flora.ArmorInfuserBehavior;
import dev.fieldframe.core.flora.Flora;
import dev.fieldframe.neoforge.Fieldframe;
import dev.fieldframe.neoforge.api.WorldFluids;
import dev.fieldframe.neoforge.feature.endertech.ExchangerItem;
import dev.fieldframe.neoforge.feature.endertech.ExchangerService;
import dev.fieldframe.neoforge.feature.endertech.PadBlock;
import dev.fieldframe.neoforge.feature.endertech.PadBlockEntity;
import dev.fieldframe.neoforge.feature.endertech.TankControllerEntity;
import dev.fieldframe.neoforge.feature.endertech.TankGlassBlock;
import dev.fieldframe.neoforge.feature.endertech.TankPartBlock;
import dev.fieldframe.neoforge.feature.endertech.TankPortEntity;
import dev.fieldframe.neoforge.feature.flora.ContainedBucketItem;
import dev.fieldframe.neoforge.feature.flora.FloraEvents;
import dev.fieldframe.neoforge.feature.flora.FluidArmorItem;
import dev.fieldframe.neoforge.registry.FfContent;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ArmorMaterial;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.fluids.BaseFlowingFluid;
import net.neoforged.neoforge.fluids.FluidType;
import net.neoforged.neoforge.fluids.capability.wrappers.FluidBucketWrapper;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Registrations of the EnderTech and FLORA feature sets (all in the {@code fieldframe} namespace): Ender Alloy,
 * seven effect fluids, Exchangers, Charge/Health Pads, Ender Tank parts and fluid armor.
 */
public final class FeatureContent {
    private FeatureContent() {
    }

    public static final DeferredRegister<FluidType> FLUID_TYPES = DeferredRegister.create(NeoForgeRegistries.Keys.FLUID_TYPES, Fieldframe.MOD_ID);
    public static final DeferredRegister<Fluid> FLUIDS = DeferredRegister.create(Registries.FLUID, Fieldframe.MOD_ID);
    public static final DeferredRegister<ArmorMaterial> ARMOR_MATERIALS = DeferredRegister.create(Registries.ARMOR_MATERIAL, Fieldframe.MOD_ID);
    private static final DeferredRegister.Items ITEMS = FfContent.ITEMS;
    private static final DeferredRegister.Blocks BLOCKS = FfContent.BLOCKS;
    private static final DeferredRegister<BlockEntityType<?>> BES = FfContent.BLOCK_ENTITIES;

    private static final List<Supplier<? extends Item>> TAB = new ArrayList<>();

    public static final Supplier<Item> ENDER_ALLOY = item("ender_alloy_ingot", () -> new Item(new Item.Properties()));

    // ------------------------------------------------------------------ effect fluids

    public record FluidSet(DeferredHolder<FluidType, FluidType> type, DeferredHolder<Fluid, BaseFlowingFluid.Source> source,
                           DeferredHolder<Fluid, BaseFlowingFluid.Flowing> flowing, Supplier<Item> bucket,
                           Supplier<? extends LiquidBlock> block) {
    }

    public static final Map<Flora.Fluid, FluidSet> FLUID_SETS = new EnumMap<>(Flora.Fluid.class);

    static {
        for (Flora.Fluid f : Flora.Fluid.values()) {
            boolean hot = WorldFluids.hot(f.temperature);
            DeferredHolder<FluidType, FluidType> type = FLUID_TYPES.register(f.path, () -> new FluidType(WorldFluids.sounds(FluidType.Properties.create()
                    .descriptionId("fluid.fieldframe." + f.path).temperature(f.temperature).lightLevel(f.light)
                    .canSwim(false).canDrown(false), hot)));
            DeferredHolder<Fluid, BaseFlowingFluid.Source> source = FLUIDS.register(f.path, () -> WorldFluids.source(props(f), hot));
            DeferredHolder<Fluid, BaseFlowingFluid.Flowing> flowing = FLUIDS.register("flowing_" + f.path, () -> WorldFluids.flowing(props(f), hot));
            Supplier<LiquidBlock> block = BLOCKS.register(f.path, () -> WorldFluids.liquidBlock(FLUID_SETS.get(f).source().get(), hot, f.light));
            Supplier<Item> bucket = item(f.path + "_bucket", () -> new ContainedBucketItem(() -> FLUID_SETS.get(f).source().get(),
                    new Item.Properties().craftRemainder(Items.BUCKET).stacksTo(1)));
            FLUID_SETS.put(f, new FluidSet(type, source, flowing, bucket, block));
        }
    }

    private static BaseFlowingFluid.Properties props(Flora.Fluid f) {
        FluidSet s = FLUID_SETS.get(f);
        return WorldFluids.flow(new BaseFlowingFluid.Properties(s.type(), s.source(), s.flowing()).bucket(s.bucket()).block(s.block()),
                WorldFluids.hot(f.temperature));
    }

    // ------------------------------------------------------------------ EnderTech

    public static final Map<EnderTechRules.Tier, Supplier<Item>> EXCHANGERS = new EnumMap<>(EnderTechRules.Tier.class);
    public static final List<Supplier<PadBlock>> PADS = new ArrayList<>();
    public static final Map<TankStructure.Part, Supplier<TankPartBlock>> TANK_PARTS = new EnumMap<>(TankStructure.Part.class);

    static {
        for (EnderTechRules.Tier t : EnderTechRules.Tier.values()) {
            EXCHANGERS.put(t, item("exchanger_" + t.key, () -> new ExchangerItem(t,
                    new Item.Properties().rarity(t.creative() ? Rarity.EPIC : t == EnderTechRules.Tier.RESONANT ? Rarity.RARE : Rarity.UNCOMMON))));
        }
        for (boolean health : new boolean[] {false, true}) {
            for (EnderTechRules.Tier t : EnderTechRules.Tier.values()) {
                String name = (health ? "health_pad_" : "charge_pad_") + t.key;
                Supplier<PadBlock> b = BLOCKS.register(name, () -> new PadBlock(health, t, BlockBehaviour.Properties.of()
                        .mapColor(MapColor.COLOR_CYAN).strength(t.creative() ? -1 : 4f, t.creative() ? 3_600_000 : 8f)
                        .sound(SoundType.METAL).requiresCorrectToolForDrops().noOcclusion()));
                PADS.add(b);
                item(name, () -> new BlockItem(b.get(), new Item.Properties()));
            }
        }
        for (TankStructure.Part p : new TankStructure.Part[] {TankStructure.Part.FRAME, TankStructure.Part.GLASS, TankStructure.Part.VALVE,
                TankStructure.Part.ENERGY_INPUT, TankStructure.Part.CONTROLLER}) {
            String name = "ender_tank_" + p.name().toLowerCase(java.util.Locale.ROOT);
            Supplier<TankPartBlock> b = BLOCKS.register(name, () -> {
                BlockBehaviour.Properties props = BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_CYAN).strength(5f, 12f)
                        .sound(p == TankStructure.Part.GLASS ? SoundType.GLASS : SoundType.METAL).requiresCorrectToolForDrops();
                return p == TankStructure.Part.GLASS ? new TankGlassBlock(props.noOcclusion()) : new TankPartBlock(p, props);
            });
            TANK_PARTS.put(p, b);
            item(name, () -> new BlockItem(b.get(), new Item.Properties()));
        }
    }

    @SuppressWarnings("ConstantConditions")
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<PadBlockEntity>> PAD_BE = BES.register("pad",
            () -> BlockEntityType.Builder.of(PadBlockEntity::new, PADS.stream().map(Supplier::get).toArray(Block[]::new)).build(null));
    @SuppressWarnings("ConstantConditions")
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<TankControllerEntity>> TANK_CONTROLLER_BE = BES.register("ender_tank_controller",
            () -> BlockEntityType.Builder.of(TankControllerEntity::new, TANK_PARTS.get(TankStructure.Part.CONTROLLER).get()).build(null));
    @SuppressWarnings("ConstantConditions")
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<TankPortEntity>> TANK_PORT_BE = BES.register("ender_tank_port",
            () -> BlockEntityType.Builder.of(TankPortEntity::new, TANK_PARTS.get(TankStructure.Part.VALVE).get(),
                    TANK_PARTS.get(TankStructure.Part.ENERGY_INPUT).get()).build(null));

    // ------------------------------------------------------------------ FLORA armor

    public static final Map<String, Supplier<Item>> ARMOR = new LinkedHashMap<>();

    static {
        for (Flora.Tier t : Flora.Tier.values()) {
            DeferredHolder<ArmorMaterial, ArmorMaterial> material = ARMOR_MATERIALS.register(t.key + "_fluid", () -> FluidArmorItem.material(t));
            for (Flora.Piece piece : Flora.Piece.values()) {
                ArmorItem.Type type = ArmorItem.Type.values()[piece.ordinal()];
                String path = ArmorInfuserBehavior.itemPath(t, piece);
                ARMOR.put(path, item(path, () -> new FluidArmorItem(t, material, type, new Item.Properties())));
            }
        }
    }

    private static <T extends Item> Supplier<T> item(String name, Supplier<T> s) {
        Supplier<T> r = ITEMS.register(name, s);
        TAB.add(r);
        return r;
    }

    public static void addToTab(CreativeModeTab.Output out) {
        for (Supplier<? extends Item> r : TAB) {
            out.accept(r.get());
        }
    }

    private static void capabilities(RegisterCapabilitiesEvent e) {
        e.registerBlockEntity(Capabilities.EnergyStorage.BLOCK, PAD_BE.get(), PadBlockEntity::energyCap);
        e.registerBlockEntity(Capabilities.FluidHandler.BLOCK, TANK_PORT_BE.get(), TankPortEntity::fluidCap);
        e.registerBlockEntity(Capabilities.EnergyStorage.BLOCK, TANK_PORT_BE.get(), TankPortEntity::energyCap);
        for (Supplier<Item> ex : EXCHANGERS.values()) {
            e.registerItem(Capabilities.EnergyStorage.ITEM, (stack, ctx) -> {
                ExchangerItem item = (ExchangerItem) stack.getItem();
                return ItemEnergy.storage(stack, item.capacity(), item.tier().receivePerTick);
            }, ex.get());
        }
        for (FluidSet s : FLUID_SETS.values()) {
            e.registerItem(Capabilities.FluidHandler.ITEM, (stack, ctx) -> new FluidBucketWrapper(stack), s.bucket().get());
        }
    }

    public static void register(IEventBus modBus) {
        FLUID_TYPES.register(modBus);
        FLUIDS.register(modBus);
        ARMOR_MATERIALS.register(modBus);
        modBus.addListener(FeatureContent::capabilities);
        FloraEvents.register();
        dev.fieldframe.neoforge.chassis.BlastEvents.register();
        NeoForge.EVENT_BUS.addListener((ServerTickEvent.Post e) -> ExchangerService.tick(e.getServer()));
    }
}
