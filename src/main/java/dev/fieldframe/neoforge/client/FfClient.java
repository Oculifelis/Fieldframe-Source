package dev.fieldframe.neoforge.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.fieldframe.api.data.FfData;
import dev.fieldframe.api.face.LocalFace;
import dev.fieldframe.api.id.FfId;
import dev.fieldframe.neoforge.chassis.ChassisBlock;
import dev.fieldframe.neoforge.chassis.ChassisBlockEntity;
import dev.fieldframe.neoforge.client.manual.BuiltinPages;
import dev.fieldframe.neoforge.client.manual.ManualLoader;
import dev.fieldframe.neoforge.client.manual.ManualScreen;
import dev.fieldframe.neoforge.registry.FfContent;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.neoforged.neoforge.client.event.AddClientReloadListenersEvent;
import net.neoforged.neoforge.client.event.RecipesReceivedEvent;
import net.minecraft.resources.Identifier;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import net.neoforged.bus.api.IEventBus;
import org.lwjgl.glfw.GLFW;

import java.util.Map;

/** Client bootstrap. Only loaded on the physical client. */
public final class FfClient {
    private FfClient() {
    }

    public static final KeyMapping.Category CATEGORY = new KeyMapping.Category(Identifier.fromNamespaceAndPath("fieldframe", "main"));
    public static final KeyMapping MANUAL_KEY = new KeyMapping("key.fieldframe.manual", KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_U, CATEGORY);

    public static final KeyMapping ARMOR_KEY = new KeyMapping("key.fieldframe.armor_action", KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_I, CATEGORY);
    public static final KeyMapping FIREBALL_KEY = new KeyMapping("key.fieldframe.armor_fireball", KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_K, CATEGORY);
    public static final KeyMapping KIT_KEY = new KeyMapping("key.fieldframe.field_kit", KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_J, CATEGORY);
    public static final KeyMapping RADIUS_UP = new KeyMapping("key.fieldframe.exchanger_radius_up", KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_EQUAL, CATEGORY);
    public static final KeyMapping RADIUS_DOWN = new KeyMapping("key.fieldframe.exchanger_radius_down", KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_MINUS, CATEGORY);

    public static void init(IEventBus modBus) {
        modBus.addListener(FfClient::fluidExtensions);
        modBus.addListener(FfClient::menuScreens);
        modBus.addListener(FfClient::reloadListeners);
        modBus.addListener(FfClient::modifyModels);
        modBus.addListener(FfClient::keys);
        NeoForge.EVENT_BUS.addListener(FfClient::clientTick);
        // HIGHEST: JEI reads the same map when it starts, so ours must be set first.
        NeoForge.EVENT_BUS.addListener(net.neoforged.bus.api.EventPriority.HIGHEST, (RecipesReceivedEvent e) -> dev.fieldframe.neoforge.recipe.RecipeBridge.setClientRecipes(e.getRecipeMap()));
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent.LoggingIn e) ->
                dev.fieldframe.neoforge.adapter.RegistryHolder.set(e.getPlayer().registryAccess()));
        BuiltinPages.register();
        // Ponder is optional: its classes are touched only when it is loaded.
        if (net.neoforged.fml.ModList.get().isLoaded("ponder")) {
            dev.fieldframe.neoforge.compat.ponder.FieldframePonder.init(modBus);
        }
        ClientSmokeTest.init();
        // Test clients never grab the mouse; DevNoGrab is dev-only (not in the release jar).
        if ("client".equals(System.getProperty("fieldframe.mpSmoke")) || Boolean.getBoolean("fieldframe.clientSmoke")
                || Boolean.getBoolean("fieldframe.fluidSmoke") || Boolean.getBoolean("fieldframe.ponderSmoke")) {
            try {
                Class.forName("dev.fieldframe.neoforge.client.DevNoGrab").getMethod("init").invoke(null);
            } catch (ReflectiveOperationException ignored) {
            }
        }
        MultiplayerSmokeClient.init();
        PonderSmokeClient.init();
        FluidSmokeClient.init();
    }

    private static void menuScreens(RegisterMenuScreensEvent e) {
        e.register(FfContent.LANE_MENU.get(), LaneAccessScreen::new);
        e.register(FfContent.WORKSHOP_MENU.get(), WorkshopScreen::new);
    }

    private static void reloadListeners(AddClientReloadListenersEvent e) {
        e.addListener(Identifier.fromNamespaceAndPath("fieldframe", "manual"), new ManualLoader());
    }

    private static void keys(RegisterKeyMappingsEvent e) {
        e.registerCategory(CATEGORY);
        e.register(MANUAL_KEY);
        e.register(ARMOR_KEY);
        e.register(FIREBALL_KEY);
        e.register(KIT_KEY);
        e.register(RADIUS_UP);
        e.register(RADIUS_DOWN);
    }

    private static void modifyModels(ModelEvent.ModifyBakingResult e) {
        var models = e.getBakingResult().blockStateModels();
        java.util.List<net.minecraft.world.level.block.Block> blocks = new java.util.ArrayList<>();
        blocks.add(FfContent.CHASSIS.get());
        dev.fieldframe.neoforge.api.client.ModuleHostClientApi.blocks().forEach(s -> blocks.add(s.get()));
        for (var block : blocks) {
            for (var state : block.getStateDefinition().getPossibleStates()) {
                var base = models.get(state);
                if (base != null) {
                    models.put(state, new ChassisBakedModel(base, e.getTextureGetter()));
                }
            }
        }
    }


    /** Effect fluids use their own still/flow textures (26.1 fluid models); still ones repeat every 4 blocks. */
    private static void fluidExtensions(net.neoforged.neoforge.client.event.RegisterFluidModelsEvent e) {
        for (var entry : dev.fieldframe.neoforge.feature.FeatureContent.FLUID_SETS.entrySet()) {
            var model = dev.fieldframe.neoforge.api.client.FluidTextures.model(
                    net.minecraft.resources.Identifier.fromNamespaceAndPath("fieldframe", "block/fluid/" + entry.getKey().path + "_still"),
                    net.minecraft.resources.Identifier.fromNamespaceAndPath("fieldframe", "block/fluid/" + entry.getKey().path + "_flow"));
            e.register(model, entry.getValue().source(), entry.getValue().flowing());
        }
        for (var entry : dev.fieldframe.neoforge.feature.MaterialContent.FLUID_SETS.entrySet()) {
            var model = dev.fieldframe.neoforge.api.client.FluidTextures.model(
                    net.minecraft.resources.Identifier.fromNamespaceAndPath("fieldframe", "block/fluid/" + entry.getKey() + "_still"),
                    net.minecraft.resources.Identifier.fromNamespaceAndPath("fieldframe", "block/fluid/" + entry.getKey() + "_flow"));
            e.register(model, entry.getValue().source(), entry.getValue().flowing());
        }
    }

    private static void clientTick(ClientTickEvent.Post e) {
        boolean sneak = Minecraft.getInstance().player != null && Minecraft.getInstance().player.isShiftKeyDown();
        while (ARMOR_KEY.consumeClick()) {
            dev.fieldframe.neoforge.network.FfNetwork.FeatureKey.send(0);
        }
        while (FIREBALL_KEY.consumeClick()) {
            dev.fieldframe.neoforge.network.FfNetwork.FeatureKey.send(1);
        }
        while (KIT_KEY.consumeClick()) {
            dev.fieldframe.neoforge.network.FfNetwork.FeatureKey.send(20);
        }
        while (RADIUS_UP.consumeClick()) {
            dev.fieldframe.neoforge.network.FfNetwork.FeatureKey.send(sneak ? 12 : 10);
        }
        while (RADIUS_DOWN.consumeClick()) {
            dev.fieldframe.neoforge.network.FfNetwork.FeatureKey.send(sneak ? 13 : 11);
        }
        while (MANUAL_KEY.consumeClick()) {
            Minecraft mc = Minecraft.getInstance();
            FfId target = null;
            HitResult hit = mc.hitResult;
            if (mc.level != null && hit instanceof BlockHitResult bh && hit.getType() == HitResult.Type.BLOCK
                    && mc.level.getBlockEntity(bh.getBlockPos()) instanceof ChassisBlockEntity be) {
                LocalFace face = ChassisBlock.localFace(be, bh.getDirection());
                FfId mod = be.clientModule(face);
                if (mod != null && dev.fieldframe.api.FieldframeRegistry.module(mod) != null) {
                    target = dev.fieldframe.api.FieldframeRegistry.module(mod).manualEntry();
                } else {
                    target = FfId.ff("basics/chassis");
                }
            }
            openManual(target);
        }
    }

    public static void openManual(FfId entry) {
        Minecraft.getInstance().gui.setScreen(ManualScreen.open(entry));
    }

    public static void onSnapshot(BlockPos pos, int face, boolean open, FfData data) {
        dev.fieldframe.core.util.MpProbe.SNAPSHOTS.incrementAndGet();
        dev.fieldframe.core.util.MpProbe.LAST_REVISION.set(data.getLong("revision", -1));
        Minecraft mc = Minecraft.getInstance();
        if (mc.gui.screen() instanceof WorkshopScreen ws && ws.pos().equals(pos)) {
            ws.update(face, data);
        } else if (mc.gui.screen() instanceof ConfiguratorScreen cs && cs.pos().equals(pos)) {
            cs.update(face, data);
        } else if (!open) {
            // Arrives just before the Workshop screen opens; it picks this up.
            WorkshopScreen.pending(pos, face, data);
        } else {
            mc.gui.setScreen(new ConfiguratorScreen(pos, face, data));
        }
    }
}
