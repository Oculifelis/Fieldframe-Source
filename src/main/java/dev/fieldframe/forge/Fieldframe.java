package dev.fieldframe.forge;

import com.mojang.logging.LogUtils;
import dev.fieldframe.api.event.FieldframeEvents;
import dev.fieldframe.api.resource.VariantCodecs;
import dev.fieldframe.core.modules.BuiltinModules;
import dev.fieldframe.forge.adapter.McVariantCodec;
import dev.fieldframe.forge.data.TuningReloadListener;
import dev.fieldframe.forge.network.FfNetwork;
import dev.fieldframe.forge.recipe.RecipeBridge;
import dev.fieldframe.forge.registry.FfContent;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.AddReloadListenerEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;

/** Fieldframe for Minecraft 1.20.1 / Forge. */
@Mod(Fieldframe.MOD_ID)
public final class Fieldframe {
    public static final String MOD_ID = "fieldframe";
    public static final Logger LOG = LogUtils.getLogger();

    private static final List<Runnable> STARTUP_EXTENSIONS = new ArrayList<>();
    private static boolean startupRan;

    public Fieldframe() {
        IEventBus bus = FMLJavaModLoadingContext.get().getModEventBus();
        VariantCodecs.install(McVariantCodec.INSTANCE);
        FieldframeEvents.setErrorSink(LOG::warn);
        BuiltinModules.registerAll();
        BuiltinModules.registerSinceFreeze();
        FfContent.register(bus);
        dev.fieldframe.forge.feature.FeatureContent.register(bus);
        dev.fieldframe.forge.feature.MaterialContent.register(bus);
        net.minecraftforge.fml.ModLoadingContext.get().registerConfig(net.minecraftforge.fml.config.ModConfig.Type.COMMON, FfConfig.SPEC);
        bus.addListener(this::commonSetup);
        MinecraftForge.EVENT_BUS.addListener(this::addReloadListeners);
        MinecraftForge.EVENT_BUS.addListener(this::serverTick);
        MinecraftForge.EVENT_BUS.addListener((net.minecraftforge.event.OnDatapackSyncEvent e) -> {
            if (e.getPlayer() != null) {
                FfNetwork.FuelSync.send(e.getPlayer());
            } else {
                e.getPlayerList().getPlayers().forEach(FfNetwork.FuelSync::send);
            }
        });
        if (ModList.get().isLoaded("kubejs")) {
            // Isolated: the KubeJS bridge class is only touched when KubeJS is present.
            addStartupExtension(() -> dev.fieldframe.forge.compat.kubejs.FieldframeKubeJS.postStartupRegistration());
        }
        dev.fieldframe.forge.test.MultiplayerSmokeServer.init();
        if (ModList.get().isLoaded("curios")) {
            // Isolated: the Field Kit's Curios belt slot.
            bus.addListener((FMLCommonSetupEvent e) -> e.enqueueWork(dev.fieldframe.forge.compat.curios.KitCurio::register));
        }
        // Fundamentum is required; these items register in every run.
        dev.fieldframe.forge.compat.fundamentum.FieldWhips.init(bus);
        dev.fieldframe.forge.compat.fundamentum.FieldHooks.init(bus);
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> {
            dev.fieldframe.forge.compat.fundamentum.GravitonHookClient.init();
            dev.fieldframe.forge.compat.fundamentum.WhipChargeClient.register();
        });

        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> dev.fieldframe.forge.client.FfClient.init(bus));
    }

    /** Called before the module registry freezes (item registration). */
    public static synchronized void addStartupExtension(Runnable r) {
        if (startupRan) {
            throw new IllegalStateException("Fieldframe startup registration has already completed");
        }
        STARTUP_EXTENSIONS.add(r);
    }

    public static synchronized void runStartupExtensions() {
        if (startupRan) {
            return;
        }
        startupRan = true;
        for (Runnable r : STARTUP_EXTENSIONS) {
            try {
                r.run();
            } catch (RuntimeException e) {
                LOG.error("Fieldframe startup extension failed; its modules are not registered", e);
            }
        }
    }

    private void commonSetup(FMLCommonSetupEvent e) {
        e.enqueueWork(FfNetwork::register);
    }

    private void addReloadListeners(AddReloadListenerEvent e) {
        e.addListener(new TuningReloadListener());
        e.addListener(new dev.fieldframe.forge.data.FuelReloadListener());
        e.addListener(new dev.fieldframe.forge.data.AuraReloadListener());
        e.addListener(new dev.fieldframe.forge.data.TemperatureReloadListener());
        e.addListener(new dev.fieldframe.forge.data.ElectrodeReloadListener());
        e.addListener(new dev.fieldframe.forge.data.TreadmillReloadListener());
        e.addListener(new dev.fieldframe.forge.data.FrameComponentReloadListener());
        e.addListener(new dev.fieldframe.forge.data.RecipeFilterReloadListener());
        e.addListener(new dev.fieldframe.forge.data.FluidArmorReloadListener());
        e.addListener(new dev.fieldframe.forge.data.FluidArmorPairsReloadListener());
        RecipeBridge.invalidate();
    }

    private void serverTick(TickEvent.ServerTickEvent e) {
        if (e.phase == TickEvent.Phase.END) {
            FfNetwork.tickInspectors(e.getServer());
            dev.fieldframe.forge.chassis.FieldKitRuntime.sweep(e.getServer());
        }
    }
}
