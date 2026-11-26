package dev.fieldframe.forge.client;

import dev.fieldframe.forge.Fieldframe;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;

/**
 * Dev-only single-player Ponder check ({@code -Dfieldframe.ponderSmoke=true}, with -PwithPonder): creates a fresh flat
 * world (Ponder needs one for its config, and on 26.1.2 it cannot join a dedicated server), opens the chassis's
 * data-driven scene, saves two screenshots ({@code run/screenshots/fieldframe-ponder-*.png}) and quits.
 */
public final class PonderSmokeClient {
    private PonderSmokeClient() {
    }

    private static boolean creating;
    private static boolean done;
    private static int ticks;
    private static int waiting;

    public static void init() {
        if (!Boolean.getBoolean("fieldframe.ponderSmoke")) {
            return;
        }
        MinecraftForge.EVENT_BUS.addListener(PonderSmokeClient::tick);
    }

    private static void tick(TickEvent.ClientTickEvent e) {
        if (e.phase != TickEvent.Phase.END) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (done) {
            return;
        }
        if (!creating && mc.player == null && mc.getOverlay() == null && (mc.screen instanceof TitleScreen
                || mc.screen instanceof net.minecraft.client.gui.screens.AccessibilityOnboardingScreen)) {
            creating = true;
            String name = "ff_ponder_smoke_" + System.currentTimeMillis();
            Fieldframe.LOG.info("FIELDFRAME_PONDER creating world {}", name);
            mc.createWorldOpenFlows().createFreshLevel(name,
                    new LevelSettings(name, GameType.CREATIVE, false, Difficulty.PEACEFUL, true, new net.minecraft.world.level.GameRules(),
                            WorldDataConfiguration.DEFAULT),
                    WorldOptions.defaultWithRandomSeed(), ra -> ra.registryOrThrow(net.minecraft.core.registries.Registries.WORLD_PRESET)
                            .getHolderOrThrow(WorldPresets.FLAT).value().createWorldDimensions());
            return;
        }
        if (mc.player == null || mc.level == null) {
            if (++waiting > 6000) {
                result(mc, false, (creating ? "world did not open" : "no title screen") + " within 5 minutes (screen: "
                        + (mc.screen == null ? "none" : mc.screen.getClass().getName()) + ")");
            } else if (waiting % 600 == 0) {
                Fieldframe.LOG.info("FIELDFRAME_PONDER waiting ({}), screen {}", creating ? "world" : "title",
                        mc.screen == null ? "none" : mc.screen.getClass().getName());
            }
            return;
        }
        switch (++ticks) {
            case 100 -> {
                boolean ponder = ModList.get().isLoaded("ponder");
                var screen = ponder ? dev.fieldframe.forge.compat.ponder.FieldframePonder.open(new ResourceLocation("fieldframe:chassis")) : null;
                if (screen == null) {
                    result(mc, false, ponder ? "no Ponder scene for the chassis" : "Ponder is not loaded (-PwithPonder)");
                    return;
                }
                mc.setScreen(screen);
                Fieldframe.LOG.info("FIELDFRAME_PONDER scene opened");
            }
            // Timeline of first_machine.json: modules go on from about tick 110 (engine) to 280 (panel), lit at ~380.
            case 300 -> ViewerShots.shot(mc, "fieldframe-ponder-first-machine.png");
            case 520 -> ViewerShots.shot(mc, "fieldframe-ponder-first-machine-end.png");
            case 540 -> result(mc, true, "scene played");
            default -> {
            }
        }
    }

    private static void result(Minecraft mc, boolean ok, String what) {
        done = true;
        Fieldframe.LOG.info("FIELDFRAME_PONDER result={} {}", ok ? "PASS" : "FAIL", what);
        mc.setScreen(null);
        if (mc.level != null) {
            mc.level.disconnect();
            mc.clearLevel();
        }
        mc.stop();
    }
}
