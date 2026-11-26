package dev.fieldframe.frameworks;

import dev.fieldframe.neoforge.api.WorldFluids;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.material.Fluid;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.fluids.BaseFlowingFluid;
import net.neoforged.neoforge.fluids.FluidType;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

/**
 * Deuterium, the Starcore's fuel: made by the Centrifuge from water and moved in tanks; its bucket pours it into the
 * world like water.
 */
public final class FwFluids {
    private FwFluids() {
    }

    public static final DeferredRegister<FluidType> TYPES = DeferredRegister.create(NeoForgeRegistries.Keys.FLUID_TYPES, Frameworks.MOD_ID);
    public static final DeferredRegister<Fluid> FLUIDS = DeferredRegister.create(Registries.FLUID, Frameworks.MOD_ID);

    public static final DeferredHolder<FluidType, FluidType> DEUTERIUM_TYPE = TYPES.register("deuterium", () -> new FluidType(WorldFluids.sounds(
            FluidType.Properties.create().descriptionId("fluid.fieldframe_frameworks.deuterium").density(1100).temperature(290)
                    .canSwim(false).canDrown(false), false)));
    public static final DeferredHolder<Fluid, BaseFlowingFluid.Source> DEUTERIUM = FLUIDS.register("deuterium", () -> WorldFluids.source(props(), false));
    public static final DeferredHolder<Fluid, BaseFlowingFluid.Flowing> FLOWING_DEUTERIUM = FLUIDS.register("flowing_deuterium",
            () -> WorldFluids.flowing(props(), false));
    public static final DeferredBlock<LiquidBlock> DEUTERIUM_BLOCK = FwContent.BLOCKS.register("deuterium",
            () -> WorldFluids.liquidBlock(DEUTERIUM.get(), false, 0));
    public static final DeferredItem<Item> DEUTERIUM_BUCKET = FwContent.ITEMS.register("deuterium_bucket",
            () -> new BucketItem(DEUTERIUM.get(), new Item.Properties().craftRemainder(Items.BUCKET).stacksTo(1)));

    private static BaseFlowingFluid.Properties props() {
        return WorldFluids.flow(new BaseFlowingFluid.Properties(DEUTERIUM_TYPE, DEUTERIUM, FLOWING_DEUTERIUM)
                .bucket(DEUTERIUM_BUCKET).block(DEUTERIUM_BLOCK), false);
    }

    static void register(IEventBus bus) {
        TYPES.register(bus);
        FLUIDS.register(bus);
    }
}
