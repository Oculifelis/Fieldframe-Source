package dev.fieldframe.neoforge;

import com.mojang.logging.LogUtils;
import dev.fieldframe.api.event.FieldframeEvents;
import dev.fieldframe.api.resource.VariantCodecs;
import dev.fieldframe.core.modules.BuiltinModules;
import dev.fieldframe.neoforge.adapter.McVariantCodec;
import dev.fieldframe.neoforge.adapter.RegistryHolder;
import dev.fieldframe.neoforge.chassis.ChassisBlockEntity;
import dev.fieldframe.neoforge.data.TuningReloadListener;
import dev.fieldframe.neoforge.network.FfNetwork;
import dev.fieldframe.neoforge.recipe.RecipeBridge;
import dev.fieldframe.neoforge.registry.FfContent;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.AddReloadListenerEvent;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;

/** Fieldframe for Minecraft 1.21.1 / NeoForge. */
@Mod(Fieldframe.MOD_ID)
public final class Fieldframe {
    public static final String MOD_ID = "fieldframe";
    public static final Logger LOG = LogUtils.getLogger();

    private static final List<Runnable> STARTUP_EXTENSIONS = new ArrayList<>();
    private static boolean startupRan;

    public Fieldframe(IEventBus bus, ModContainer container) {
        VariantCodecs.install(McVariantCodec.INSTANCE);
        FieldframeEvents.setErrorSink(LOG::warn);
        BuiltinModules.registerAll();
        BuiltinModules.registerSinceFreeze();
        FfContent.register(bus);
        dev.fieldframe.neoforge.test.MultiplayerSmokeServer.init();
        dev.fieldframe.neoforge.feature.FeatureContent.register(bus);
        dev.fieldframe.neoforge.feature.MaterialContent.register(bus);
        container.registerConfig(net.neoforged.fml.config.ModConfig.Type.COMMON, FfConfig.SPEC);
        bus.addListener(this::registerCapabilities);
        bus.addListener(this::registerPayloads);
        NeoForge.EVENT_BUS.addListener(this::addReloadListeners);
        NeoForge.EVENT_BUS.addListener(this::serverTick);
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.OnDatapackSyncEvent e) -> {
            // Build the bridge now so the Aura Chamber's mirrored Metallurgy recipes are in the table synced below.
            RecipeBridge.forLevel(e.getPlayerList().getServer().overworld());
            e.getRelevantPlayers().forEach(FfNetwork.FuelSync::send);
        });
        NeoForge.EVENT_BUS.addListener((ServerAboutToStartEvent e) -> RegistryHolder.set(e.getServer().registryAccess()));
        // Read Metallurgy's recipes once the server is up, so the Aura Chamber has its chamber recipes before anyone joins.
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.server.ServerStartedEvent e) -> RecipeBridge.forLevel(e.getServer().overworld()));
        if (ModList.get().isLoaded("kubejs")) {
            // Isolated: the KubeJS bridge class is only touched when KubeJS is present.
            addStartupExtension(() -> dev.fieldframe.neoforge.compat.kubejs.FieldframeKubeJS.postStartupRegistration());
        }
        if (ModList.get().isLoaded("curios")) {
            // Isolated: the Field Kit's Curios belt slot.
            bus.addListener((net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent e) -> e.enqueueWork(dev.fieldframe.neoforge.compat.curios.KitCurio::register));
        }
        // Fundamentum is required; these items register in every run.
        dev.fieldframe.neoforge.compat.fundamentum.FieldWhips.init(bus);
        dev.fieldframe.neoforge.compat.fundamentum.FieldHooks.init(bus);
        // These gear checks run with every GameTest run.
        bus.addListener((net.neoforged.neoforge.event.RegisterGameTestsEvent e) ->
                e.register(dev.fieldframe.neoforge.compat.fundamentum.WhipGameTests.class));
        if (FMLEnvironment.dist == Dist.CLIENT) {
            dev.fieldframe.neoforge.compat.fundamentum.GravitonHookClient.init();
            dev.fieldframe.neoforge.compat.fundamentum.WhipChargeClient.register();
        }

        if (FMLEnvironment.dist == Dist.CLIENT) {
            dev.fieldframe.neoforge.client.FfClient.init(bus);
        }
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

    /**
     * Side-specific capabilities only: a null side resolves to nothing, and each face's provider asks the core
     * whether that face currently exposes the kind. Invalidated whenever the chassis configuration changes.
     */
    private void registerCapabilities(RegisterCapabilitiesEvent e) {
        e.registerBlockEntity(Capabilities.ItemHandler.BLOCK, FfContent.CHASSIS_BE.get(), ChassisBlockEntity::itemCap);
        e.registerBlockEntity(Capabilities.FluidHandler.BLOCK, FfContent.CHASSIS_BE.get(), ChassisBlockEntity::fluidCap);
        e.registerBlockEntity(Capabilities.EnergyStorage.BLOCK, FfContent.CHASSIS_BE.get(), ChassisBlockEntity::energyCap);
        // Field Kit: takes RF like any RF item (an Item Charger fills it); it waits on the item until the kit next ticks.
        e.registerItem(Capabilities.EnergyStorage.ITEM, (stack, ctx) -> dev.fieldframe.neoforge.feature.ItemEnergy.storage(stack,
                dev.fieldframe.neoforge.item.FieldKitItem.room(stack), dev.fieldframe.api.chassis.FieldKits.SPEC.energyInPerTick()),
                FfContent.FIELD_KIT.get());
    }

    private void registerPayloads(RegisterPayloadHandlersEvent e) {
        FfNetwork.register(e);
    }

    private void addReloadListeners(AddReloadListenerEvent e) {
        e.addListener(new TuningReloadListener());
        e.addListener(new dev.fieldframe.neoforge.data.FuelReloadListener());
        e.addListener(new dev.fieldframe.neoforge.data.AuraReloadListener());
        e.addListener(new dev.fieldframe.neoforge.data.TemperatureReloadListener());
        e.addListener(new dev.fieldframe.neoforge.data.ElectrodeReloadListener());
        e.addListener(new dev.fieldframe.neoforge.data.TreadmillReloadListener());
        e.addListener(new dev.fieldframe.neoforge.data.FrameComponentReloadListener());
        e.addListener(new dev.fieldframe.neoforge.data.RecipeFilterReloadListener());
        e.addListener(new dev.fieldframe.neoforge.data.FluidArmorReloadListener());
        e.addListener(new dev.fieldframe.neoforge.data.FluidArmorPairsReloadListener());
        RecipeBridge.invalidate();
    }

    private void serverTick(ServerTickEvent.Post e) {
        FfNetwork.tickInspectors(e.getServer());
        dev.fieldframe.neoforge.chassis.FieldKitRuntime.sweep(e.getServer());
    }
}
