package dev.fieldframe.frameworks;

import com.mojang.logging.LogUtils;
import dev.fieldframe.forge.api.FieldframeForgeApi;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;
import org.slf4j.Logger;

/**
 * Fieldframe Frameworks (Forge 1.20.1; derived from the 26.1.2 edition by tools/port_frameworks.py): multiblock frames
 * built only on Fieldframe's public API.
 */
@Mod(Frameworks.MOD_ID)
public final class Frameworks {
    public static final String MOD_ID = "fieldframe_frameworks";
    public static final Logger LOG = LogUtils.getLogger();

    public Frameworks() {
        IEventBus bus = FMLJavaModLoadingContext.get().getModEventBus();
        FwContent.register(bus);
        FwFluids.register(bus);
        ModLoadingContext.get().registerConfig(ModConfig.Type.SERVER, FwConfig.SPEC);
        FieldframeForgeApi.onRegisterModules(StarcoreModules::register);
        MinecraftForge.EVENT_BUS.addListener((net.minecraftforge.event.server.ServerStoppedEvent e) -> Starcores.clear());
        GrandFrame.registerComponents();
        FwNetwork.register();
        MinecraftForge.EVENT_BUS.addListener(FwNetwork::onDatapackSync);
        MinecraftForge.EVENT_BUS.addListener((net.minecraftforge.event.AddReloadListenerEvent e) -> {
            e.addListener(new FoundryAlloys.Listener());
            e.addListener(new ArcAlloysListener());
            e.addListener(new FwTuningListener());
        });
        if (FMLEnvironment.dist == Dist.CLIENT) {
            FwClient.init(bus);
        }
        FwGameTests.register(bus);
    }
}
