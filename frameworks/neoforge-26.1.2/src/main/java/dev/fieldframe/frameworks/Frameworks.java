package dev.fieldframe.frameworks;

import com.mojang.logging.LogUtils;
import dev.fieldframe.neoforge.api.FieldframeForgeApi;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.transfer.fluid.BucketResourceHandler;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import org.slf4j.Logger;

/**
 * Fieldframe Frameworks: multiblock frames built only on Fieldframe's public API. The Grand Frame is a hollow box of
 * Frame Casings with a Frame Core in its shell; every casing in a wall (not on an edge or corner) becomes a module
 * socket, and blocks inside the box (cells, tanks, vaults) set the size of the machine's shared storage.
 */
@Mod(Frameworks.MOD_ID)
public final class Frameworks {
    public static final String MOD_ID = "fieldframe_frameworks";
    public static final Logger LOG = LogUtils.getLogger();

    public Frameworks(IEventBus bus, net.neoforged.fml.ModContainer container) {
        FwContent.register(bus);
        FwFluids.register(bus);
        container.registerConfig(net.neoforged.fml.config.ModConfig.Type.SERVER, FwConfig.SPEC);
        FieldframeForgeApi.onRegisterModules(StarcoreModules::register);
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.server.ServerStoppedEvent e) -> Starcores.clear());
        GrandFrame.registerComponents();
        bus.addListener(Frameworks::capabilities);
        bus.addListener(FwNetwork::register);
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(FwNetwork::onDatapackSync);
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.AddServerReloadListenersEvent e) ->
                e.addListener(net.minecraft.resources.Identifier.fromNamespaceAndPath(MOD_ID, "alloys"), new FoundryAlloys.Listener()));
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.AddServerReloadListenersEvent e) ->
                e.addListener(net.minecraft.resources.Identifier.fromNamespaceAndPath(MOD_ID, "arc_alloys"), new ArcAlloysListener()));
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.AddServerReloadListenersEvent e) ->
                e.addListener(net.minecraft.resources.Identifier.fromNamespaceAndPath(MOD_ID, "tuning"), new FwTuningListener()));
        if (FMLEnvironment.getDist() == Dist.CLIENT) {
            FwClient.init(bus);
        }
        FwGameTests.register(bus);
    }

    private static void capabilities(RegisterCapabilitiesEvent e) {
        FieldframeForgeApi.registerSocketCapabilities(e, FwContent.CASING_BE.get());
        FieldframeForgeApi.registerSocketCapabilities(e, FwContent.GANTRY_BE.get());
        e.registerItem(Capabilities.Fluid.ITEM, (stack, access) -> new BucketResourceHandler(access), FwFluids.DEUTERIUM_BUCKET.get());
    }
}
