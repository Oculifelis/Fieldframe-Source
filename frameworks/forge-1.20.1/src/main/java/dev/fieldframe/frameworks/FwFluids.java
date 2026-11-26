package dev.fieldframe.frameworks;

import dev.fieldframe.forge.api.WorldFluids;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.material.Fluid;
import net.minecraftforge.client.extensions.common.IClientFluidTypeExtensions;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fluids.FluidType;
import net.minecraftforge.fluids.ForgeFlowingFluid;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

import java.util.function.Consumer;

/**
 * Deuterium, the Starcore's fuel: made by the Centrifuge from water and moved in tanks; its bucket pours it into the
 * world like water.
 */
public final class FwFluids {
    private FwFluids() {
    }

    public static final DeferredRegister<FluidType> TYPES = DeferredRegister.create(ForgeRegistries.Keys.FLUID_TYPES, Frameworks.MOD_ID);
    public static final DeferredRegister<Fluid> FLUIDS = DeferredRegister.create(ForgeRegistries.FLUIDS, Frameworks.MOD_ID);

    public static final RegistryObject<FluidType> DEUTERIUM_TYPE = TYPES.register("deuterium", () -> new FluidType(WorldFluids.sounds(FluidType.Properties.create()
            .descriptionId("fluid.fieldframe_frameworks.deuterium").density(1100).temperature(290).canSwim(false).canDrown(false), false)) {
        @Override
        public void initializeClient(Consumer<IClientFluidTypeExtensions> consumer) {
            consumer.accept(new IClientFluidTypeExtensions() {
                @Override
                public ResourceLocation getStillTexture() {
                    return new ResourceLocation(Frameworks.MOD_ID, "block/fluid/deuterium_still");
                }

                @Override
                public ResourceLocation getStillTexture(net.minecraft.world.level.material.FluidState state,
                        net.minecraft.world.level.BlockAndTintGetter getter, net.minecraft.core.BlockPos pos) {
                    return dev.fieldframe.forge.api.client.FluidTextures.still(getStillTexture(), pos);
                }

                @Override
                public ResourceLocation getFlowingTexture() {
                    return new ResourceLocation(Frameworks.MOD_ID, "block/fluid/deuterium_flow");
                }
            });
        }
    });
    public static final RegistryObject<ForgeFlowingFluid.Source> DEUTERIUM = FLUIDS.register("deuterium", () -> WorldFluids.source(props(), false));
    public static final RegistryObject<ForgeFlowingFluid.Flowing> FLOWING_DEUTERIUM = FLUIDS.register("flowing_deuterium",
            () -> WorldFluids.flowing(props(), false));
    public static final RegistryObject<LiquidBlock> DEUTERIUM_BLOCK = FwContent.BLOCKS.register("deuterium",
            () -> WorldFluids.liquidBlock(DEUTERIUM, false, 0));
    public static final RegistryObject<Item> DEUTERIUM_BUCKET = FwContent.ITEMS.register("deuterium_bucket",
            () -> new BucketItem(DEUTERIUM, new Item.Properties().craftRemainder(Items.BUCKET).stacksTo(1)));

    private static ForgeFlowingFluid.Properties props() {
        return WorldFluids.flow(new ForgeFlowingFluid.Properties(DEUTERIUM_TYPE, DEUTERIUM, FLOWING_DEUTERIUM)
                .bucket(DEUTERIUM_BUCKET).block(DEUTERIUM_BLOCK), false);
    }

    static void register(IEventBus bus) {
        TYPES.register(bus);
        FLUIDS.register(bus);
    }
}
