package dev.fieldframe.neoforge.client;

import dev.fieldframe.api.data.FfData;
import dev.fieldframe.api.face.LocalFace;
import dev.fieldframe.api.id.FfId;
import dev.fieldframe.core.endertech.EnderTechRules;
import dev.fieldframe.core.modules.BuiltinModules;
import dev.fieldframe.core.util.MpProbe;
import dev.fieldframe.neoforge.Fieldframe;
import dev.fieldframe.neoforge.chassis.ChassisBlockEntity;
import dev.fieldframe.neoforge.client.manual.ManualScreen;
import dev.fieldframe.neoforge.feature.endertech.ExchangerItem;
import dev.fieldframe.neoforge.network.FfNetwork;
import dev.fieldframe.neoforge.registry.FfContent;
import dev.fieldframe.neoforge.test.MultiplayerSmokeServer;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;

import java.util.ArrayList;
import java.util.List;

/**
 * Client half of the two-process multiplayer smoke test ({@code -Dfieldframe.mpSmoke=client}). The first client
 * (owner) checks registry/world/block-entity sync, opens the inspector over the network, presses the Exchanger radius
 * key, edits the Controller Plate program and builds a chassis from a Blueprint Sheet. A second client (any other
 * username) tries to edit the owner's chassis and expects to be refused. Each logs its checks and quits.
 */
public final class MultiplayerSmokeClient {
    private MultiplayerSmokeClient() {
    }

    private static int ticksInWorld;
    private static BlockPos spawn;
    private static BlockPos chassis;
    private static boolean owner;
    private static final List<String> FAILURES = new ArrayList<>();
    private static boolean done;
    private static boolean connecting;
    private static int ticksWaiting;
    private static final int JOIN_TIMEOUT = 1200;
    private static final String FW = "fieldframe_frameworks";
    private static final boolean FRAMEWORKS = net.neoforged.fml.ModList.get().isLoaded(FW);

    public static void init() {
        if (!"client".equals(System.getProperty("fieldframe.mpSmoke"))) {
            return;
        }
        NeoForge.EVENT_BUS.addListener(MultiplayerSmokeClient::tick);
    }

    private static void check(boolean ok, String what) {
        Fieldframe.LOG.info("FIELDFRAME_MP client check {} {}", ok ? "ok  " : "FAIL", what);
        if (!ok) {
            FAILURES.add(what);
        }
    }

    private static void tick(ClientTickEvent.Post e) {
        Minecraft mc = Minecraft.getInstance();
        if (!done && !connecting && mc.player == null && mc.getOverlay() == null
                && (mc.screen instanceof net.minecraft.client.gui.screens.TitleScreen
                    || mc.screen instanceof net.minecraft.client.gui.screens.AccessibilityOnboardingScreen)) {
            // 26.1 does not honour --quickPlayMultiplayer from the dev launch here, so the smoke client connects itself.
            connecting = true;
            String address = "localhost:" + System.getProperty("fieldframe.mpSmokePort", "25572");
            Fieldframe.LOG.info("FIELDFRAME_MP client connecting to {}", address);
            net.minecraft.client.gui.screens.ConnectScreen.startConnecting(new net.minecraft.client.gui.screens.TitleScreen(), mc,
                    net.minecraft.client.multiplayer.resolver.ServerAddress.parseString(address),
                    new net.minecraft.client.multiplayer.ServerData("Fieldframe smoke", address, net.minecraft.client.multiplayer.ServerData.Type.OTHER),
                    false, null);
            return;
        }
        if (!done && connecting && mc.player == null && ++ticksWaiting > JOIN_TIMEOUT) {
            // Refused (mod mismatch, server error) or stuck: fail now instead of idling until the script's timeout.
            check(false, "joined the server within " + JOIN_TIMEOUT / 20 + " s (screen: "
                    + (mc.screen == null ? "none" : mc.screen.getClass().getSimpleName()) + ")");
            finish(mc);
            return;
        }
        if (done || mc.player == null || mc.level == null) {
            return;
        }
        ticksInWorld++;
        if (ticksInWorld == 1) {
            spawn = mc.player.blockPosition();
            chassis = spawn.east(2);
            owner = mc.player.getGameProfile().name().equals("Dev");
            Fieldframe.LOG.info("FIELDFRAME_MP client joined as {} ({})", mc.player.getGameProfile().name(), owner ? "owner" : "guest");
        }
        if (ticksInWorld == 50) {
            // Again once the server's position packet has surely arrived (under load tick 1 can still see the client's
            // placeholder position).
            spawn = mc.player.blockPosition();
            chassis = spawn.east(2);
        }
        if (owner) {
            ownerScript(mc);
        } else {
            guestScript(mc);
        }
    }

    private static void ownerScript(Minecraft mc) {
        if (ticksInWorld > 1270 && ticksInWorld < 1456 && mc.screen instanceof WorkshopScreen ws) {
            int r = ticksInWorld - 1271;
            switch (r) {
                case 6 -> ws.testToggle("info");
                case 30 -> ws.testToggle("energy");
                case 54 -> ws.testToggle("chassis");
                case 78 -> ws.testToggle("redstone");
                case 102 -> ws.testToggle("settings");
                case 126 -> ws.testSelectFace(LocalFace.RIGHT.ordinal());
                case 140 -> ws.testToggle("routing");
                case 164 -> ws.testToggle("energy");
                default -> { }
            }
            WorkshopScreen.testClock += 20;
            ViewerShots.shot(mc, String.format("ws-f%03d.png", r));
        }
        switch (ticksInWorld) {
            case 60 -> {
                check(BuiltInRegistries.ITEM.getValue(Identifier.fromNamespaceAndPath("fieldframe", "exchanger_redstone")) != Items.AIR, "item registry synced");
                check(BuiltInRegistries.FLUID.containsKey(Identifier.fromNamespaceAndPath("fieldframe", "liquid_redstone")), "fluid registry synced");
                check(mc.level.getBlockState(chassis).is(FfContent.CHASSIS.get()), "chassis block synced at " + chassis);
                check(mc.level.getBlockEntity(chassis) instanceof ChassisBlockEntity be
                        && BuiltinModules.ITEM_PORT.equals(be.clientModule(LocalFace.TOP)), "chassis module synced to client");
                check(mc.player.getMainHandItem().getItem() instanceof ExchangerItem, "server-given Exchanger in hand");
                FfNetwork.Action.send(chassis, 0, "select_face", LocalFace.TOP.ordinal(), "", 0, null);
                FfNetwork.FeatureKey.send(10);
                FfNetwork.FeatureKey.send(10);
            }
            case 120 -> {
                check(MpProbe.SNAPSHOTS.get() >= 1, "inspector snapshot received (" + MpProbe.SNAPSHOTS.get() + ")");
                check(mc.screen instanceof ConfiguratorScreen, "configurator opened from the server snapshot");
                check(ExchangerItem.radius(mc.player.getMainHandItem()) == EnderTechRules.EXCHANGER_DEFAULT_RADIUS + 2,
                        "exchanger radius change synced back (" + ExchangerItem.radius(mc.player.getMainHandItem()) + ")");
                mc.setScreen(null);
                // Controller edit through the same validated packet the program editor uses.
                FfNetwork.Action.send(chassis, MpProbe.LAST_REVISION.get(), "set_config", LocalFace.BOTTOM.ordinal(), "", 0,
                        new FfData().putString("program", MultiplayerSmokeServer.PROGRAM));
            }
            case 150 -> {
                // Staged blueprint build by really using the sheet on the ground (server-side item logic).
                mc.player.getInventory().setSelectedSlot(1);
                BlockPos ground = MultiplayerSmokeServer.buildPos(spawn).below();
                mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND,
                        new BlockHitResult(Vec3.atCenterOf(ground).add(0, 0.5, 0), Direction.UP, ground, false));
            }
            case 200 -> {
                BlockPos built = MultiplayerSmokeServer.buildPos(spawn);
                check(mc.level.getBlockState(built).is(FfContent.CHASSIS.get()), "blueprint-built chassis visible at " + built);
                mc.setScreen(ManualScreen.open(FfId.ff("ender/tank")));
            }
            case 240 -> {
                check(mc.screen instanceof ManualScreen, "manual opens while connected");
                mc.setScreen(null);
                int fuels = dev.fieldframe.api.process.MachineData.fuels().size();
                int auras = dev.fieldframe.api.process.MachineData.auras().size();
                check(fuels > 0 && auras > 0, "fuels and Aura Chamber data synced to the client (" + fuels + " fuels, " + auras + " auras)");
                // Raise the owner's chassis one tier with the server-given kit (server-side item logic).
                mc.player.getInventory().setSelectedSlot(2);
                mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND,
                        new BlockHitResult(Vec3.atCenterOf(chassis).add(0, 0.5, 0), Direction.UP, chassis, false));
            }
            case 280 -> {
                check(mc.player.getInventory().getItem(2).isEmpty(), "server accepted the Hardened Upgrade Kit");
                if (!net.neoforged.fml.ModList.get().isLoaded("jei")) {
                    ticksInWorld = 1229; // no JEI/Jade in this run: skip to the Workshop screen checks
                }
            }
            case 290 -> check(ViewerShots.showJei(), "JEI opens the Alloy Smelter category");
            case 330 -> ViewerShots.shot(mc, "fieldframe-jei-alloying.png");
            case 340 -> {
                mc.setScreen(null);
                check(ViewerShots.showAuras(), "JEI opens the Aura Chamber category");
            }
            case 380 -> ViewerShots.shot(mc, "fieldframe-jei-aura.png");
            case 390 -> {
                mc.setScreen(null);
                // The data tables reach this client only through the sync packet (the server is a separate process).
                int treadmill = dev.fieldframe.core.material.TreadmillMobs.entries().size();
                int electrodes = dev.fieldframe.core.material.Electrodes.entries().size();
                int temperatures = dev.fieldframe.core.material.Temperatures.entries().size();
                check(treadmill > 0 && electrodes > 0 && temperatures > 0, "Treadmill, electrode and temperature tables synced ("
                        + treadmill + ", " + electrodes + ", " + temperatures + ")");
                check(ViewerShots.showTable("temperatures"), "JEI opens the Temperatures category");
            }
            case 430 -> ViewerShots.shot(mc, "fieldframe-jei-temperatures.png");
            case 440 -> {
                mc.setScreen(null);
                check(ViewerShots.showTable("treadmill"), "JEI opens the Treadmill Animals category");
            }
            case 480 -> ViewerShots.shot(mc, "fieldframe-jei-treadmill.png");
            case 490 -> {
                mc.setScreen(null);
                check(ViewerShots.showTable("electrodes"), "JEI opens the Galvanic Electrodes category");
            }
            case 530 -> ViewerShots.shot(mc, "fieldframe-jei-electrodes.png");
            // With -PwithFrameworks: the addon's pages, filled from the server's synced data (alloys, tuning numbers).
            case 540 -> {
                mc.setScreen(null);
                if (FRAMEWORKS) {
                    int alloys = ViewerShots.showPage(Identifier.fromNamespaceAndPath(FW, "foundry_alloys"));
                    check(alloys > 0, "JEI opens the Frameworks Foundry Alloys page (" + alloys + " alloys synced)");
                }
            }
            case 580 -> {
                if (FRAMEWORKS) {
                    ViewerShots.shot(mc, "fieldframe-jei-fw-alloys.png");
                }
            }
            case 590 -> {
                mc.setScreen(null);
                if (FRAMEWORKS) {
                    var core = BuiltInRegistries.ITEM.getOptional(Identifier.fromNamespaceAndPath(FW, "frame_core"));
                    check(core.isPresent() && ViewerShots.showInfo(core.get()), "JEI opens the Frame Core's build guide (information page)");
                }
            }
            case 630 -> {
                if (FRAMEWORKS) {
                    ViewerShots.shot(mc, "fieldframe-jei-fw-frame-core.png");
                }
            }
            case 640 -> {
                mc.setScreen(null);
                ViewerShots.lookAt(mc, chassis);
            }
            case 680 -> ViewerShots.shot(mc, "fieldframe-jade-chassis.png");
            // Item icons in screens: the title-screen client smoke has no world, so it cannot show them; this client can.
            case 690 -> mc.setScreen(new FilterScreen(new ConfiguratorScreen(BlockPos.ZERO, LocalFace.TOP.ordinal(), ClientSmokeTest.simulatedSnapshot()),
                    "filter", dev.fieldframe.api.resource.ItemFilter.allowIds("minecraft:coal", "minecraft:iron_ingot").toData()));
            case 720 -> ViewerShots.shot(mc, "fieldframe-mp-filter-editor.png");
            case 730 -> mc.setScreen(ManualScreen.open(FfId.parse("fieldframe:modules/heater")));
            case 760 -> ViewerShots.shot(mc, "fieldframe-mp-manual-heater.png");
            // With -PwithPonder: the data-driven "Your first machine" scene (Ponder needs a world for its config).
            case 770 -> {
                mc.setScreen(null);
                if (net.neoforged.fml.ModList.get().isLoaded("ponder")) {
                    var ponder = dev.fieldframe.neoforge.compat.ponder.FieldframePonder.open(Identifier.parse("fieldframe:chassis"));
                    check(ponder != null, "Ponder opens the chassis's scene from data");
                    mc.setScreen(ponder);
                }
            }
            case 900 -> {
                if (net.neoforged.fml.ModList.get().isLoaded("ponder")) {
                    ViewerShots.shot(mc, "fieldframe-ponder-first-machine.png");
                }
            }
            case 1100 -> {
                if (net.neoforged.fml.ModList.get().isLoaded("ponder")) {
                    ViewerShots.shot(mc, "fieldframe-ponder-first-machine-end.png");
                }
            }
            // Fluid armor: every fluid with an armor effect reaches this client through the table sync.
            case 1110 -> {
                mc.setScreen(null);
                int armor = dev.fieldframe.core.flora.FluidArmorEffects.all().size();
                int rows = ViewerShots.showFluidArmor();
                check(armor > 0 && rows >= 7 + 2 + 10 + 40, "JEI opens the Fluid Armor page (" + rows + " fluids, " + armor + " effects synced)");
            }
            case 1150 -> ViewerShots.shot(mc, "fieldframe-jei-fluid-armor.png");
            case 1155 -> {
                mc.setScreen(null);
                check(ViewerShots.showFluidArmor(java.util.Set.of("minecraft:lava", "metallurgy:quicksilver")),
                        "JEI shows the lava and quicksilver fluid armor rows");
            }
            case 1185 -> ViewerShots.shot(mc, "fieldframe-jei-fluid-armor-lava.png");
            case 1190 -> mc.setScreen(ManualScreen.open(FfId.parse("fieldframe:flora/fluid_effects")));
            case 1220 -> ViewerShots.shot(mc, "fieldframe-mp-manual-fluid-effects.png");
            case 1230 -> {
                mc.setScreen(null);
                WorkshopScreen.testClock = 0;
                FfNetwork.Action.send(chassis, MpProbe.LAST_REVISION.get(), "open", LocalFace.LEFT.ordinal(), "", 0, null);
            }
            case 1270 -> {
                check(mc.screen instanceof WorkshopScreen ws && ws.shownFace() == LocalFace.LEFT.ordinal(),
                        "Workshop machine screen opened on the left side (" + mc.screen + ")");
                check(mc.player.containerMenu instanceof dev.fieldframe.neoforge.menu.WorkshopMenu, "Workshop container open on the client");
                ViewerShots.shot(mc, "fieldframe-ws-heater.png");
            }
            case 1460 -> {
                check(mc.screen instanceof WorkshopScreen ws && ws.shownFace() == LocalFace.RIGHT.ordinal() && ws.testIsOpen("routing"),
                        "Workshop face tab and Routing tab work");
                if (mc.screen instanceof WorkshopScreen ws) {
                    ws.testFullEditor();
                }
            }
            case 1500 -> {
                check(mc.screen instanceof ConfiguratorScreen, "Full editor opens from the Workshop (" + mc.screen + ")");
                check(!(mc.player.containerMenu instanceof dev.fieldframe.neoforge.menu.WorkshopMenu), "Workshop container closed on hand-off");
                mc.setScreen(null);
                WorkshopScreen.testClock = -1;
            }
            case 1510 -> finish(mc);
            default -> {
            }
        }
    }

    private static void guestScript(Minecraft mc) {
        switch (ticksInWorld) {
            case 60 -> {
                check(mc.level.getBlockEntity(chassis) instanceof ChassisBlockEntity, "guest sees the owner's chassis");
                FfNetwork.Action.send(chassis, 0, "select_face", LocalFace.BOTTOM.ordinal(), "", 0, null);
            }
            case 100 -> {
                mc.setScreen(null);
                check(MpProbe.SNAPSHOTS.get() >= 1, "guest may inspect (snapshot received)");
                FfNetwork.Action.send(chassis, MpProbe.LAST_REVISION.get(), "set_config", LocalFace.BOTTOM.ordinal(), "", 0,
                        new FfData().putString("program", "when power > 0% then set ch 1 = 15\n"));
                mc.player.getInventory().setSelectedSlot(0);
                mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND,
                        new BlockHitResult(Vec3.atCenterOf(chassis).add(0, 0, -0.5), Direction.NORTH, chassis, false));
            }
            case 160 -> {
                check(mc.level.getBlockEntity(chassis) instanceof ChassisBlockEntity be && be.clientModule(LocalFace.FRONT) == null,
                        "guest could not install a module");
                check(mc.player.getInventory().getItem(0).getItem() == FfContent.moduleItem(BuiltinModules.ITEM_PORT),
                        "guest kept the module item");
                finish(mc);
            }
            default -> {
            }
        }
    }

    private static void finish(Minecraft mc) {
        Fieldframe.LOG.info("FIELDFRAME_MP client result={} role={} failures={}", FAILURES.isEmpty() ? "PASS" : "FAIL", owner ? "owner" : "guest", FAILURES);
        done = true;
        mc.setScreen(null);
        if (mc.level != null) {
            mc.level.disconnect(net.minecraft.network.chat.Component.literal("Fieldframe multiplayer smoke done"));
        }
        mc.disconnectFromWorld(net.minecraft.network.chat.Component.literal("Fieldframe multiplayer smoke done"));
        mc.stop();
    }
}
