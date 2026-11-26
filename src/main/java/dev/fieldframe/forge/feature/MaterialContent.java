package dev.fieldframe.forge.feature;

import dev.fieldframe.core.material.Materials;
import dev.fieldframe.forge.Fieldframe;
import dev.fieldframe.forge.api.WorldFluids;
import dev.fieldframe.forge.feature.flora.ContainedBucketItem;
import dev.fieldframe.forge.registry.FfContent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.MapColor;
import net.minecraftforge.client.extensions.common.IClientFluidTypeExtensions;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fluids.FluidType;
import net.minecraftforge.fluids.ForgeFlowingFluid;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Registers everything in {@code fieldframe_tables/materials.json}: Fieldframe's own alloys (ingot, nugget, dust,
 * block), extra items and fluids with buckets. Common metals, ores and dusts come from Metallurgy.
 */
public final class MaterialContent {
    private MaterialContent() {
    }

    private static final DeferredRegister<Item> ITEMS = FfContent.ITEMS;
    private static final DeferredRegister<Block> BLOCKS = FfContent.BLOCKS;

    private static final List<RegistryObject<? extends Item>> TAB = new ArrayList<>();
    public static final Map<String, RegistryObject<? extends Item>> ITEMS_BY_PATH = new LinkedHashMap<>();

    public record FluidSet(RegistryObject<FluidType> type, RegistryObject<ForgeFlowingFluid.Source> source,
                           RegistryObject<ForgeFlowingFluid.Flowing> flowing, RegistryObject<Item> bucket,
                           RegistryObject<LiquidBlock> block, boolean hot) {
    }

    public static final Map<String, FluidSet> FLUID_SETS = new LinkedHashMap<>();

    static {
        Materials.Table t = Materials.get();
        for (Materials.Metal m : t.metals()) {
            for (Materials.Form f : m.forms()) {
                String path = f.path(m.name());
                if (f.isBlock()) {
                    RegistryObject<Block> b = BLOCKS.register(path, () -> new Block(BlockBehaviour.Properties.of().mapColor(MapColor.METAL)
                            .strength(5f, 6f).sound(SoundType.METAL).requiresCorrectToolForDrops()));
                    add(path, ITEMS.register(path, () -> new BlockItem(b.get(), new Item.Properties())));
                } else {
                    add(path, ITEMS.register(path, () -> new Item(new Item.Properties())));
                }
            }
        }
        // Plates and gears for every metal in the parts table (Metallurgy metals included), made in the Press with dies.
        for (Materials.Part part : t.parts()) {
            for (String form : t.partForms()) {
                String path = part.path(form);
                add(path, ITEMS.register(path, () -> new Item(new Item.Properties())));
            }
        }
        for (Materials.Item i : t.items()) {
            int burn = i.burnTicks();
            add(i.name(), ITEMS.register(i.name(), () -> burn <= 0 ? new Item(new Item.Properties()) : new Item(new Item.Properties()) {
                @Override
                public int getBurnTime(ItemStack stack, RecipeType<?> type) {
                    return burn;
                }
            }));
        }
        for (Materials.Fluid f : t.fluids()) {
            String name = f.name();
            boolean hot = WorldFluids.hot(f.temperature());
            RegistryObject<FluidType> type = FeatureContent.FLUID_TYPES.register(name, () -> new FluidType(WorldFluids.sounds(FluidType.Properties.create()
                    .descriptionId("fluid.fieldframe." + name).temperature(f.temperature()).density(f.density()).lightLevel(f.light())
                    .canSwim(false).canDrown(false), hot)) {
                @Override
                public void initializeClient(Consumer<IClientFluidTypeExtensions> consumer) {
                    consumer.accept(new IClientFluidTypeExtensions() {
                        @Override
                        public ResourceLocation getStillTexture() {
                            return new ResourceLocation("fieldframe", "block/fluid/" + name + "_still");
                        }

                        @Override
                        public ResourceLocation getStillTexture(net.minecraft.world.level.material.FluidState state,
                                net.minecraft.world.level.BlockAndTintGetter getter, net.minecraft.core.BlockPos pos) {
                            return dev.fieldframe.forge.api.client.FluidTextures.still(getStillTexture(), pos);
                        }

                        @Override
                        public ResourceLocation getFlowingTexture() {
                            return new ResourceLocation("fieldframe", "block/fluid/" + name + "_flow");
                        }
                    });
                }
            });
            RegistryObject<ForgeFlowingFluid.Source> source = FeatureContent.FLUIDS.register(name, () -> WorldFluids.source(props(name), hot));
            RegistryObject<ForgeFlowingFluid.Flowing> flowing = FeatureContent.FLUIDS.register("flowing_" + name, () -> WorldFluids.flowing(props(name), hot));
            RegistryObject<LiquidBlock> block = BLOCKS.register(name, () -> WorldFluids.liquidBlock(() -> FLUID_SETS.get(name).source().get(), hot, f.light()));
            RegistryObject<Item> bucket = ITEMS.register(name + "_bucket",
                    () -> new ContainedBucketItem(() -> FLUID_SETS.get(name).source().get(), new Item.Properties().craftRemainder(Items.BUCKET).stacksTo(1)));
            add(name + "_bucket", bucket);
            FLUID_SETS.put(name, new FluidSet(type, source, flowing, bucket, block, hot));
        }
    }

    private static void add(String path, RegistryObject<? extends Item> item) {
        TAB.add(item);
        ITEMS_BY_PATH.put(path, item);
    }

    private static ForgeFlowingFluid.Properties props(String name) {
        FluidSet s = FLUID_SETS.get(name);
        return WorldFluids.flow(new ForgeFlowingFluid.Properties(s.type(), s.source(), s.flowing()).bucket(s.bucket()).block(s.block()), s.hot());
    }

    public static void addToTab(CreativeModeTab.Output out) {
        for (RegistryObject<? extends Item> r : TAB) {
            out.accept(r.get());
        }
    }

    public static void register(IEventBus modBus) {
    }
}
