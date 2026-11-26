package dev.fieldframe.neoforge.client;

import dev.fieldframe.api.FieldframeRegistry;
import dev.fieldframe.api.chassis.ChassisSpec;
import dev.fieldframe.api.face.LocalFace;
import dev.fieldframe.api.id.FfId;
import dev.fieldframe.api.manual.ManualRegistry;
import dev.fieldframe.api.resource.VariantCodec;
import dev.fieldframe.core.chassis.ChassisCore;
import dev.fieldframe.core.chassis.SimulatedWorld;
import dev.fieldframe.core.modules.BuiltinModules;
import dev.fieldframe.neoforge.Fieldframe;
import dev.fieldframe.neoforge.client.manual.ManualScreen;
import dev.fieldframe.neoforge.network.InspectorData;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.client.event.ClientTickEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Dev-only automated client smoke test ({@code -Dfieldframe.clientSmoke=true}, see build.gradle). From the title
 * screen it opens every manual entry and a configurator built from a simulated chassis, lets each render for a few
 * frames, saves screenshots of a representative subset to {@code run/screenshots/fieldframe-smoke-*.png},
 * logs a summary and closes the game. Not active in normal play.
 */
public final class ClientSmokeTest {
    private ClientSmokeTest() {
    }

    private record Step(String name, Supplier<Screen> screen, boolean capture) {
    }

    private static final Set<String> CAPTURE = Set.of("fieldframe:basics/welcome", "fieldframe:basics/first_machine",
            "fieldframe:control/channels", "fieldframe:processing/chain", "fieldframe:modules/heater", "kubejs:pack_guide/smasher",
            "fieldframe:reference/status_codes", "fieldframe_example:kiln");

    private static List<Step> steps;
    private static int index = -1;
    private static int wait;
    private static int captured;
    private static boolean started;

    public static void init() {
        if (!Boolean.getBoolean("fieldframe.clientSmoke")) {
            return;
        }
        NeoForge.EVENT_BUS.addListener(ClientSmokeTest::tick);
    }

    private static void tick(ClientTickEvent.Post e) {
        Minecraft mc = Minecraft.getInstance();
        if (!started) {
            if (mc.gui.overlay() != null || mc.gui.screen() == null) {
                return;
            }
            started = true;
            steps = new ArrayList<>();
            for (FfId id : ManualRegistry.current().entries().keySet()) {
                steps.add(new Step(id.toString(), () -> ManualScreen.open(id), CAPTURE.contains(id.toString())));
            }
            steps.add(new Step("configurator", () -> new ConfiguratorScreen(BlockPos.ZERO, LocalFace.TOP.ordinal(), simulatedSnapshot()), true));
            steps.add(new Step("configurator_advanced", () -> new ConfiguratorScreen(BlockPos.ZERO, LocalFace.BOTTOM.ordinal(), simulatedSnapshot()).showAdvanced(), true));
            steps.add(new Step("configurator_empty_side", () -> new ConfiguratorScreen(BlockPos.ZERO, LocalFace.BACK.ordinal(), simulatedSnapshot()), true));
            steps.add(new Step("pattern_editor", () -> new PatternScreen(new ConfiguratorScreen(BlockPos.ZERO, LocalFace.TOP.ordinal(), simulatedSnapshot()),
                    "pattern", "minecraft:oak_planks _ _ / minecraft:oak_planks _ _ / minecraft:stick"), true));
            steps.add(new Step("filter_editor", () -> new FilterScreen(new ConfiguratorScreen(BlockPos.ZERO, LocalFace.TOP.ordinal(), simulatedSnapshot()),
                    "filter", dev.fieldframe.api.resource.ItemFilter.allowIds("minecraft:coal", "minecraft:iron_ingot").toData()), true));
            Fieldframe.LOG.info("Fieldframe client smoke: {} screens queued", steps.size());
        }
        if (wait-- > 0) {
            return;
        }
        if (index >= 0 && steps.get(index).capture()) {
            String file = "fieldframe-smoke-" + steps.get(index).name().replace(':', '_').replace('/', '_') + ".png";
            Screenshot.grab(mc.gameDirectory, file, mc.gameRenderer.mainRenderTarget(), 1, msg -> { });
            captured++;
        }
        index++;
        if (index >= steps.size()) {
            Fieldframe.LOG.info("Fieldframe client smoke: rendered {} screens, captured {} screenshots, {} page render errors",
                    steps.size(), captured, ManualScreen.RENDER_ERRORS);
            mc.gui.setScreen(null);
            mc.stop();
            return;
        }
        try {
            mc.gui.setScreen(steps.get(index).screen().get());
        } catch (RuntimeException ex) {
            ManualScreen.RENDER_ERRORS++;
            Fieldframe.LOG.error("Fieldframe client smoke: failed to open {}", steps.get(index).name(), ex);
        }
        wait = steps.get(index).capture() ? 8 : 2;
    }

    /** A realistic configurator snapshot from a simulated chassis (no world, no network). */
    static dev.fieldframe.api.data.FfData simulatedSnapshot() {
        SimulatedWorld w = new SimulatedWorld();
        ChassisCore c = new ChassisCore(ChassisSpec.STANDARD, VariantCodec.SIMPLE, () -> w.time);
        c.setSimulation(true);
        c.install(LocalFace.TOP, FieldframeRegistry.module(BuiltinModules.HEATER), null);
        c.install(LocalFace.BOTTOM, FieldframeRegistry.module(BuiltinModules.FUEL_ENGINE), null);
        c.install(LocalFace.FRONT, FieldframeRegistry.module(BuiltinModules.INVENTORY_PANEL), null);
        c.install(LocalFace.LEFT, FieldframeRegistry.module(BuiltinModules.ITEM_PORT), null);
        c.install(LocalFace.RIGHT, FieldframeRegistry.module(BuiltinModules.RESOURCE_SENSOR), null);
        // Smart defaults: ore in Orange, ingots come out in Green, coal for the engine in Purple.
        w.addRecipe(new dev.fieldframe.api.process.ProcessRecipe(dev.fieldframe.api.id.FfId.ff("smelt_iron"), dev.fieldframe.api.process.ProcessFamilies.HEATING,
                java.util.List.of(new dev.fieldframe.api.process.ProcessRecipe.ItemInput(dev.fieldframe.api.resource.ItemFilter.allowIds("minecraft:raw_iron"), 1, true, "raw iron")),
                null, java.util.List.of(new dev.fieldframe.api.process.ProcessRecipe.ItemResult(dev.fieldframe.api.resource.ItemVariant.Simple.of("minecraft:iron_ingot"), 1, 1.0)),
                null, 100, 20));
        w.fuels.put(dev.fieldframe.api.id.FfId.parse("minecraft:coal"), 1600);
        c.lane(1).insert(dev.fieldframe.api.resource.ItemVariant.Simple.of("minecraft:iron_ingot"), 12, false);
        c.lane(0).insert(dev.fieldframe.api.resource.ItemVariant.Simple.of("minecraft:raw_iron"), 5, false);
        c.lane(2).insert(dev.fieldframe.api.resource.ItemVariant.Simple.of("minecraft:coal"), 7, false);
        c.energy().receive(12_400, false);
        for (int i = 0; i < 45; i++) {
            w.time++;
            c.tick(w);
        }
        return InspectorData.build(c);
    }
}
