package dev.fieldframe.forge.feature;

import dev.fieldframe.core.endertech.EnderTechRules;
import dev.fieldframe.core.endertech.TankStructure;
import dev.fieldframe.core.flora.ArmorInfuserBehavior;
import dev.fieldframe.core.flora.Flora;
import dev.fieldframe.forge.Fieldframe;
import dev.fieldframe.forge.api.WorldFluids;
import dev.fieldframe.forge.feature.endertech.ExchangerItem;
import dev.fieldframe.forge.feature.endertech.ExchangerService;
import dev.fieldframe.forge.feature.endertech.PadBlock;
import dev.fieldframe.forge.feature.endertech.PadBlockEntity;
import dev.fieldframe.forge.feature.endertech.TankControllerEntity;
import dev.fieldframe.forge.feature.endertech.TankGlassBlock;
import dev.fieldframe.forge.feature.endertech.TankPartBlock;
import dev.fieldframe.forge.feature.endertech.TankPortEntity;
import dev.fieldframe.forge.feature.flora.FloraEvents;
import dev.fieldframe.forge.feature.flora.FluidArmorItem;
import dev.fieldframe.forge.registry.FfContent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.BucketItem;
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
import net.minecraftforge.client.extensions.common.IClientFluidTypeExtensions;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fluids.FluidType;
import net.minecraftforge.fluids.ForgeFlowingFluid;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Registrations of the EnderTech and FLORA feature sets (all in the {@code fieldframe} namespace): Ender Alloy,
 * seven effect fluids, Exchangers, Charge/Health Pads, Ender Tank parts and fluid armor.
 */
public final class FeatureContent {
    private FeatureContent() {
    }

    public static final DeferredRegister<FluidType> FLUID_TYPES = DeferredRegister.create(ForgeRegistries.Keys.FLUID_TYPES, Fieldframe.MOD_ID);
    public static final DeferredRegister<Fluid> FLUIDS = DeferredRegister.create(ForgeRegistries.FLUIDS, Fieldframe.MOD_ID);
    private static final DeferredRegister<Item> ITEMS = FfContent.ITEMS;
    private static final DeferredRegister<Block> BLOCKS = FfContent.BLOCKS;
    private static final DeferredRegister<BlockEntityType<?>> BES = FfContent.BLOCK_ENTITIES;

    /** Display order for the creative tab. */
    private static final List<RegistryObject<? extends Item>> TAB = new ArrayList<>();

    public static final RegistryObject<Item> ENDER_ALLOY = item("ender_alloy_ingot", () -> new Item(new Item.Properties()));

    // ------------------------------------------------------------------ effect fluids

    public record FluidSet(RegistryObject<FluidType> type, RegistryObject<ForgeFlowingFluid.Source> source,
                           RegistryObject<ForgeFlowingFluid.Flowing> flowing, RegistryObject<Item> bucket,
                           RegistryObject<LiquidBlock> block) {
    }

    public static final Map<Flora.Fluid, FluidSet> FLUID_SETS = new EnumMap<>(Flora.Fluid.class);

    static {
        for (Flora.Fluid f : Flora.Fluid.values()) {
            boolean hot = WorldFluids.hot(f.temperature);
            RegistryObject<FluidType> type = FLUID_TYPES.register(f.path, () -> new FluidType(WorldFluids.sounds(FluidType.Properties.create()
                    .descriptionId("fluid.fieldframe." + f.path).temperature(f.temperature).lightLevel(f.light)
                    .canSwim(false).canDrown(false), hot)) {
                @Override
                public void initializeClient(Consumer<IClientFluidTypeExtensions> consumer) {
                    consumer.accept(new IClientFluidTypeExtensions() {
                        @Override
                        public ResourceLocation getStillTexture() {
                            return new ResourceLocation("fieldframe", "block/fluid/" + f.path + "_still");
                        }

                        @Override
                        public ResourceLocation getStillTexture(net.minecraft.world.level.material.FluidState state,
                                net.minecraft.world.level.BlockAndTintGetter getter, net.minecraft.core.BlockPos pos) {
                            return dev.fieldframe.forge.api.client.FluidTextures.still(getStillTexture(), pos);
                        }

                        @Override
                        public ResourceLocation getFlowingTexture() {
                            return new ResourceLocation("fieldframe", "block/fluid/" + f.path + "_flow");
                        }

                        @Override
                        public int getTintColor() {
                            return 0xFFFFFFFF; // own textures; f.color stays the gauge/particle colour
                        }
                    });
                }
            });
            RegistryObject<ForgeFlowingFluid.Source> source = FLUIDS.register(f.path, () -> WorldFluids.source(props(f), hot));
            RegistryObject<ForgeFlowingFluid.Flowing> flowing = FLUIDS.register("flowing_" + f.path, () -> WorldFluids.flowing(props(f), hot));
            RegistryObject<LiquidBlock> block = BLOCKS.register(f.path, () -> WorldFluids.liquidBlock(() -> FLUID_SETS.get(f).source().get(), hot, f.light));
            RegistryObject<Item> bucket = item(f.path + "_bucket", () -> new dev.fieldframe.forge.feature.flora.ContainedBucketItem(() -> FLUID_SETS.get(f).source().get(),
                    new Item.Properties().craftRemainder(Items.BUCKET).stacksTo(1)));
            FLUID_SETS.put(f, new FluidSet(type, source, flowing, bucket, block));
        }
    }

    private static ForgeFlowingFluid.Properties props(Flora.Fluid f) {
        FluidSet s = FLUID_SETS.get(f);
        return WorldFluids.flow(new ForgeFlowingFluid.Properties(s.type(), s.source(), s.flowing()).bucket(s.bucket()).block(s.block()),
                WorldFluids.hot(f.temperature));
    }

    // ------------------------------------------------------------------ EnderTech

    public static final Map<EnderTechRules.Tier, RegistryObject<Item>> EXCHANGERS = new EnumMap<>(EnderTechRules.Tier.class);
    public static final List<RegistryObject<PadBlock>> PADS = new ArrayList<>();
    public static final Map<TankStructure.Part, RegistryObject<TankPartBlock>> TANK_PARTS = new EnumMap<>(TankStructure.Part.class);

    static {
        for (EnderTechRules.Tier t : EnderTechRules.Tier.values()) {
            EXCHANGERS.put(t, item("exchanger_" + t.key, () -> new ExchangerItem(t,
                    new Item.Properties().rarity(t.creative() ? Rarity.EPIC : t == EnderTechRules.Tier.RESONANT ? Rarity.RARE : Rarity.UNCOMMON))));
        }
        for (boolean health : new boolean[] {false, true}) {
            for (EnderTechRules.Tier t : EnderTechRules.Tier.values()) {
                String name = (health ? "health_pad_" : "charge_pad_") + t.key;
                RegistryObject<PadBlock> b = BLOCKS.register(name, () -> new PadBlock(health, t, BlockBehaviour.Properties.of()
                        .mapColor(MapColor.COLOR_CYAN).strength(t.creative() ? -1 : 4f, t.creative() ? 3_600_000 : 8f)
                        .sound(SoundType.METAL).requiresCorrectToolForDrops().noOcclusion()));
                PADS.add(b);
                item(name, () -> new BlockItem(b.get(), new Item.Properties()));
            }
        }
        for (TankStructure.Part p : new TankStructure.Part[] {TankStructure.Part.FRAME, TankStructure.Part.GLASS, TankStructure.Part.VALVE,
                TankStructure.Part.ENERGY_INPUT, TankStructure.Part.CONTROLLER}) {
            String name = "ender_tank_" + p.name().toLowerCase(java.util.Locale.ROOT);
            BlockBehaviour.Properties props = BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_CYAN).strength(5f, 12f)
                    .sound(p == TankStructure.Part.GLASS ? SoundType.GLASS : SoundType.METAL).requiresCorrectToolForDrops();
            if (p == TankStructure.Part.GLASS) {
                props = props.noOcclusion();
            }
            BlockBehaviour.Properties fp = props;
            RegistryObject<TankPartBlock> b = BLOCKS.register(name, () -> p == TankStructure.Part.GLASS ? new TankGlassBlock(fp) : new TankPartBlock(p, fp));
            TANK_PARTS.put(p, b);
            item(name, () -> new BlockItem(b.get(), new Item.Properties()));
        }
    }

    @SuppressWarnings("ConstantConditions")
    public static final RegistryObject<BlockEntityType<PadBlockEntity>> PAD_BE = BES.register("pad",
            () -> BlockEntityType.Builder.of(PadBlockEntity::new, PADS.stream().map(RegistryObject::get).toArray(Block[]::new)).build(null));
    @SuppressWarnings("ConstantConditions")
    public static final RegistryObject<BlockEntityType<TankControllerEntity>> TANK_CONTROLLER_BE = BES.register("ender_tank_controller",
            () -> BlockEntityType.Builder.of(TankControllerEntity::new, TANK_PARTS.get(TankStructure.Part.CONTROLLER).get()).build(null));
    @SuppressWarnings("ConstantConditions")
    public static final RegistryObject<BlockEntityType<TankPortEntity>> TANK_PORT_BE = BES.register("ender_tank_port",
            () -> BlockEntityType.Builder.of(TankPortEntity::new, TANK_PARTS.get(TankStructure.Part.VALVE).get(),
                    TANK_PARTS.get(TankStructure.Part.ENERGY_INPUT).get()).build(null));

    // ------------------------------------------------------------------ FLORA armor

    public static final Map<String, RegistryObject<Item>> ARMOR = new LinkedHashMap<>();

    static {
        for (Flora.Tier t : Flora.Tier.values()) {
            for (Flora.Piece piece : Flora.Piece.values()) {
                ArmorItem.Type type = ArmorItem.Type.values()[piece.ordinal()];
                String path = ArmorInfuserBehavior.itemPath(t, piece);
                ARMOR.put(path, item(path, () -> new FluidArmorItem(t, type, new Item.Properties())));
            }
        }
    }

    private static <T extends Item> RegistryObject<T> item(String name, java.util.function.Supplier<T> s) {
        RegistryObject<T> r = ITEMS.register(name, s);
        TAB.add(r);
        return r;
    }

    public static void addToTab(CreativeModeTab.Output out) {
        for (RegistryObject<? extends Item> r : TAB) {
            out.accept(r.get());
        }
    }

    public static void register(IEventBus modBus) {
        FLUID_TYPES.register(modBus);
        FLUIDS.register(modBus);
        FloraEvents.register();
        dev.fieldframe.forge.chassis.BlastEvents.register();
        MinecraftForge.EVENT_BUS.addListener((TickEvent.ServerTickEvent e) -> {
            if (e.phase == TickEvent.Phase.END) {
                ExchangerService.tick(e.getServer());
            }
        });
    }
}
