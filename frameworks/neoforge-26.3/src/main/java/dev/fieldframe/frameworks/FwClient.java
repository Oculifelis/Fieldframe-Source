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

    /** Deuterium draws with its own still and flowing textures in tanks and screens; the still one repeats every 4 blocks. */
    private static void fluidModels(net.neoforged.neoforge.client.event.RegisterFluidModelsEvent e) {
        var model = dev.fieldframe.neoforge.api.client.FluidTextures.model(
                net.minecraft.resources.Identifier.fromNamespaceAndPath(Frameworks.MOD_ID, "block/fluid/deuterium_still"),
                net.minecraft.resources.Identifier.fromNamespaceAndPath(Frameworks.MOD_ID, "block/fluid/deuterium_flow"));
        e.register(model, FwFluids.DEUTERIUM.get(), FwFluids.FLOWING_DEUTERIUM.get());
    }
}
