package dev.fieldframe.frameworks;

import dev.fieldframe.neoforge.api.client.ModuleHostClientApi;

/** Client registration: frame casings draw module faceplates, rings and lit overlays like a chassis. */
public final class FwClient {
    private FwClient() {
    }

    static void init(net.neoforged.bus.api.IEventBus bus) {
        ModuleHostClientApi.composeModules(FwContent.CASING);
        ModuleHostClientApi.composeModules(FwContent.GANTRY_CONTROLLER);
        bus.addListener(FwClient::fluidModels);
    }

    /** Deuterium draws with its own still and flowing textures in tanks and screens. */
    private static void fluidModels(net.neoforged.neoforge.client.extensions.common.RegisterClientExtensionsEvent e) {
        e.registerFluidType(new net.neoforged.neoforge.client.extensions.common.IClientFluidTypeExtensions() {
            @Override
            public net.minecraft.resources.ResourceLocation getStillTexture() {
                return net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(Frameworks.MOD_ID, "block/fluid/deuterium_still");
            }

            @Override
            public net.minecraft.resources.ResourceLocation getStillTexture(net.minecraft.world.level.material.FluidState state,
                    net.minecraft.world.level.BlockAndTintGetter getter, net.minecraft.core.BlockPos pos) {
                return dev.fieldframe.neoforge.api.client.FluidTextures.still(getStillTexture(), pos);
            }

            @Override
            public net.minecraft.resources.ResourceLocation getFlowingTexture() {
                return net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(Frameworks.MOD_ID, "block/fluid/deuterium_flow");
            }
        }, FwFluids.DEUTERIUM_TYPE.get());
    }
}
