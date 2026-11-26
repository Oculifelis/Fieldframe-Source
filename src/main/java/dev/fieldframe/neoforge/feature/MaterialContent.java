package dev.fieldframe.neoforge.feature;

import dev.fieldframe.core.material.Materials;
import dev.fieldframe.neoforge.Fieldframe;
import dev.fieldframe.neoforge.api.WorldFluids;
import dev.fieldframe.neoforge.feature.flora.ContainedBucketItem;
import dev.fieldframe.neoforge.registry.FfContent;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.fluids.BaseFlowingFluid;
import net.neoforged.neoforge.fluids.FluidType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.transfer.fluid.BucketResourceHandler;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Registers everything in {@code fieldframe_tables/materials.json}: Fieldframe's own alloys (ingot, nugget, dust,
 * block), extra items and fluids with buckets. Common metals, ores and dusts come from Metallurgy.
 */
public final class MaterialContent {
    private MaterialContent() {
    }

    private static final DeferredRegister.Items ITEMS = FfContent.ITEMS;
    private static final DeferredRegister.Blocks BLOCKS = FfContent.BLOCKS;

    private static final List<Supplier<? extends Item>> TAB = new ArrayList<>();
    public static final Map<String, Supplier<? extends Item>> ITEMS_BY_PATH = new LinkedHashMap<>();

    public record FluidSet(DeferredHolder<FluidType, FluidType> type, DeferredHolder<Fluid, BaseFlowingFluid.Source> source,
                           DeferredHolder<Fluid, BaseFlowingFluid.Flowing> flowing, Supplier<Item> bucket,
                           Supplier<? extends LiquidBlock> block, boolean hot) {
    }

    public static final Map<String, FluidSet> FLUID_SETS = new LinkedHashMap<>();

    static {
        Materials.Table t = Materials.get();
        for (Materials.Metal m : t.metals()) {
            for (Materials.Form f : m.forms()) {
                String path = f.path(m.name());
                if (f.isBlock()) {
                    var b = BLOCKS.registerBlock(path, Block::new, p -> p.mapColor(MapColor.METAL).strength(5f, 6f)
                            .sound(SoundType.METAL).requiresCorrectToolForDrops());
                    add(path, ITEMS.registerSimpleBlockItem(path, b));
                } else {
                    add(path, ITEMS.registerSimpleItem(path));
                }
            }
        }
        // Plates and gears for every metal in the parts table (Metallurgy metals included), made in the Press with dies.
        for (Materials.Part part : t.parts()) {
            for (String form : t.partForms()) {
                String path = part.path(form);
                add(path, ITEMS.registerSimpleItem(path));
            }
        }
        for (Materials.Item i : t.items()) {
            add(i.name(), ITEMS.registerSimpleItem(i.name()));
        }
        for (Materials.Fluid f : t.fluids()) {
            String name = f.name();
            boolean hot = WorldFluids.hot(f.temperature());
            DeferredHolder<FluidType, FluidType> type = FeatureContent.FLUID_TYPES.register(name, () -> new FluidType(WorldFluids.sounds(FluidType.Properties.create()
                    .descriptionId("fluid.fieldframe." + name).temperature(f.temperature()).density(f.density()).lightLevel(f.light())
                    .canSwim(false).canDrown(false), hot)));
            DeferredHolder<Fluid, BaseFlowingFluid.Source> source = FeatureContent.FLUIDS.register(name, () -> WorldFluids.source(props(name), hot));
            DeferredHolder<Fluid, BaseFlowingFluid.Flowing> flowing = FeatureContent.FLUIDS.register("flowing_" + name, () -> WorldFluids.flowing(props(name), hot));
            Supplier<Item> bucket = ITEMS.registerItem(name + "_bucket",
                    p -> new ContainedBucketItem(() -> FLUID_SETS.get(name).source().get(), p.craftRemainder(Items.BUCKET).stacksTo(1)));
            add(name + "_bucket", bucket);
            Supplier<LiquidBlock> block = BLOCKS.registerBlock(name, p -> new LiquidBlock(FLUID_SETS.get(name).source().get(), p),
                    p -> WorldFluids.block(p, hot, f.light()));
            FLUID_SETS.put(name, new FluidSet(type, source, flowing, bucket, block, hot));
        }
    }

    private static void add(String path, Supplier<? extends Item> item) {
        TAB.add(item);
        ITEMS_BY_PATH.put(path, item);
    }

    private static BaseFlowingFluid.Properties props(String name) {
        FluidSet s = FLUID_SETS.get(name);
        return WorldFluids.flow(new BaseFlowingFluid.Properties(s.type(), s.source(), s.flowing()).bucket(s.bucket()).block(s.block()), s.hot());
    }

    public static void addToTab(CreativeModeTab.Output out) {
        for (Supplier<? extends Item> r : TAB) {
            out.accept(r.get());
        }
    }

    private static void capabilities(RegisterCapabilitiesEvent e) {
        for (FluidSet s : FLUID_SETS.values()) {
            e.registerItem(Capabilities.Fluid.ITEM, (stack, access) -> new BucketResourceHandler(access), s.bucket().get());
        }
    }

    public static void register(IEventBus modBus) {
        modBus.addListener(MaterialContent::capabilities);
    }
}
