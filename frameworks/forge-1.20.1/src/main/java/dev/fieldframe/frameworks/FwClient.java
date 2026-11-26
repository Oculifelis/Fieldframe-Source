package dev.fieldframe.frameworks;

import dev.fieldframe.forge.api.client.ModuleHostClientApi;
import net.minecraftforge.eventbus.api.IEventBus;

/** Client registration: casings and the gantry controller draw module faceplates like a chassis (fluids: FwFluids). */
public final class FwClient {
    private FwClient() {
    }

    static void init(IEventBus bus) {
        ModuleHostClientApi.composeModules(FwContent.CASING);
        ModuleHostClientApi.composeModules(FwContent.GANTRY_CONTROLLER);
    }
}
