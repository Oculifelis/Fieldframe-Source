package dev.fieldframe.forge.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.fieldframe.api.data.FfData;
import dev.fieldframe.api.face.LocalFace;
import dev.fieldframe.api.id.FfId;
import dev.fieldframe.forge.chassis.ChassisBlock;
import dev.fieldframe.forge.chassis.ChassisBlockEntity;
import dev.fieldframe.forge.client.manual.BuiltinPages;
import dev.fieldframe.forge.client.manual.ManualLoader;
import dev.fieldframe.forge.client.manual.ManualScreen;
import dev.fieldframe.forge.registry.FfContent;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraftforge.client.event.ModelEvent;
import net.minecraftforge.client.event.RegisterClientReloadListenersEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.settings.KeyConflictContext;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import org.lwjgl.glfw.GLFW;

import java.util.Map;

/** Client bootstrap. Only loaded on the physical client. */
public final class FfClient {
    private FfClient() {
    }

    public static final KeyMapping MANUAL_KEY = new KeyMapping("key.fieldframe.manual", KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_U, "key.categories.fieldframe");

    public static final KeyMapping ARMOR_KEY = new KeyMapping("key.fieldframe.armor_action", KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_I, "key.categories.fieldframe");
    public static final KeyMapping FIREBALL_KEY = new KeyMapping("key.fieldframe.armor_fireball", KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_K, "key.categories.fieldframe");
    public static final KeyMapping KIT_KEY = new KeyMapping("key.fieldframe.field_kit", KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_J, "key.categories.fieldframe");
    public static final KeyMapping RADIUS_UP = new KeyMapping("key.fieldframe.exchanger_radius_up", KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_EQUAL, "key.categories.fieldframe");
    public static final KeyMapping RADIUS_DOWN = new KeyMapping("key.fieldframe.exchanger_radius_down", KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_MINUS, "key.categories.fieldframe");

    public static void init(IEventBus modBus) {
        modBus.addListener(FfClient::setup);
        modBus.addListener(FfClient::reloadListeners);
        modBus.addListener(FfClient::modifyModels);
        modBus.addListener(FfClient::keys);
        MinecraftForge.EVENT_BUS.addListener(FfClient::clientTick);
        BuiltinPages.register();
        // Ponder is optional: its classes are touched only when it is loaded.
        if (net.minecraftforge.fml.ModList.get().isLoaded("ponder")) {
            dev.fieldframe.forge.compat.ponder.FieldframePonder.init(modBus);
        }
        ClientSmokeTest.init();
        // Test clients never grab the mouse; DevNoGrab is dev-only (not in the release jar).
        if ("client".equals(System.getProperty("fieldframe.mpSmoke")) || Boolean.getBoolean("fieldframe.clientSmoke")
                || Boolean.getBoolean("fieldframe.fluidSmoke") || Boolean.getBoolean("fieldframe.ponderSmoke")) {
            try {
                Class.forName("dev.fieldframe.forge.client.DevNoGrab").getMethod("init").invoke(null);
            } catch (ReflectiveOperationException ignored) {
            }
        }
        MultiplayerSmokeClient.init();
        PonderSmokeClient.init();
        FluidSmokeClient.init();
    }

    private static void setup(FMLClientSetupEvent e) {
        e.enqueueWork(() -> MenuScreens.register(FfContent.LANE_MENU.get(), LaneAccessScreen::new));
        e.enqueueWork(() -> MenuScreens.register(FfContent.WORKSHOP_MENU.get(), WorkshopScreen::new));
    }

    private static void reloadListeners(RegisterClientReloadListenersEvent e) {
        e.registerReloadListener(new ManualLoader());
    }

    private static void keys(RegisterKeyMappingsEvent e) {
        e.register(MANUAL_KEY);
        e.register(ARMOR_KEY);
        e.register(FIREBALL_KEY);
        e.register(KIT_KEY);
        e.register(RADIUS_UP);
        e.register(RADIUS_DOWN);
    }

    private static void modifyModels(ModelEvent.ModifyBakingResult e) {
        Map<net.minecraft.resources.ResourceLocation, BakedModel> models = e.getModels();
        // The chassis and every block registered through ModuleHostClientApi (module sockets of multiblocks).
        java.util.Set<net.minecraft.resources.ResourceLocation> hosts = new java.util.HashSet<>();
        hosts.add(new net.minecraft.resources.ResourceLocation("fieldframe", "chassis"));
        dev.fieldframe.forge.api.client.ModuleHostClientApi.blocks().forEach(
                s -> hosts.add(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(s.get())));
        for (var key : models.keySet()) {
            if (key instanceof ModelResourceLocation mrl && hosts.contains(new net.minecraft.resources.ResourceLocation(mrl.getNamespace(), mrl.getPath()))
                    && !mrl.getVariant().equals("inventory")) {
                models.put(key, new ChassisBakedModel(models.get(key)));
            }
        }
    }

    private static void clientTick(TickEvent.ClientTickEvent e) {
        if (e.phase != TickEvent.Phase.END) {
            return;
        }
        boolean sneak = Minecraft.getInstance().player != null && Minecraft.getInstance().player.isShiftKeyDown();
        while (ARMOR_KEY.consumeClick()) {
            dev.fieldframe.forge.network.FfNetwork.CHANNEL.sendToServer(new dev.fieldframe.forge.network.FfNetwork.FeatureKey(0));
        }
        while (FIREBALL_KEY.consumeClick()) {
            dev.fieldframe.forge.network.FfNetwork.CHANNEL.sendToServer(new dev.fieldframe.forge.network.FfNetwork.FeatureKey(1));
        }
        while (KIT_KEY.consumeClick()) {
            dev.fieldframe.forge.network.FfNetwork.CHANNEL.sendToServer(new dev.fieldframe.forge.network.FfNetwork.FeatureKey(20));
        }
        while (RADIUS_UP.consumeClick()) {
            dev.fieldframe.forge.network.FfNetwork.CHANNEL.sendToServer(new dev.fieldframe.forge.network.FfNetwork.FeatureKey(sneak ? 12 : 10));
        }
        while (RADIUS_DOWN.consumeClick()) {
            dev.fieldframe.forge.network.FfNetwork.CHANNEL.sendToServer(new dev.fieldframe.forge.network.FfNetwork.FeatureKey(sneak ? 13 : 11));
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
        Minecraft.getInstance().setScreen(ManualScreen.open(entry));
    }

    public static void onSnapshot(BlockPos pos, int face, boolean open, FfData data) {
        dev.fieldframe.core.util.MpProbe.SNAPSHOTS.incrementAndGet();
        dev.fieldframe.core.util.MpProbe.LAST_REVISION.set(data.getLong("revision", -1));
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof WorkshopScreen ws && ws.pos().equals(pos)) {
            ws.update(face, data);
        } else if (mc.screen instanceof ConfiguratorScreen cs && cs.pos().equals(pos)) {
            cs.update(face, data);
        } else if (!open) {
            // Arrives just before the Workshop screen opens; it picks this up.
            WorkshopScreen.pending(pos, face, data);
        } else {
            mc.setScreen(new ConfiguratorScreen(pos, face, data));
        }
    }
}
