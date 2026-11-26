package dev.fieldframe.frameworks;

import com.mojang.authlib.GameProfile;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import java.util.ArrayList;
import java.util.UUID;
import dev.fieldframe.api.id.FfId;
import dev.fieldframe.api.resource.ItemVariant;
import dev.fieldframe.neoforge.api.FieldframeForgeApi;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.IEventBus;

import java.util.List;

/**
 * In-world tests of the Grand Frame, through the public API and vanilla interaction only: a frame is built block by
 * block, modules are installed by a mock player using module items on the casings, and storage is read through the
 * core's {@code MachineHub}.
 */
@net.neoforged.neoforge.gametest.GameTestHolder(Frameworks.MOD_ID)
@net.neoforged.neoforge.gametest.PrefixGameTestTemplate(false)
public final class FwGameTests {
    private FwGameTests() {
    }

    static void register(IEventBus bus) {
        // 1.21.1: @GameTestHolder classes are found by annotation.
    }

    /** Frame corner (relative). A 5x5x5 frame spans O .. O+4; its core sits on the bottom front edge. */
    private static final BlockPos O = new BlockPos(1, 1, 1);
    private static final BlockPos CORE = O.offset(2, 0, 0);

    private static void build(GameTestHelper h, int size) {
        for (int x = 0; x < size; x++) {
            for (int y = 0; y < size; y++) {
                for (int z = 0; z < size; z++) {
                    boolean outside = x == 0 || y == 0 || z == 0 || x == size - 1 || y == size - 1 || z == size - 1;
                    if (outside) {
                        h.setBlock(O.offset(x, y, z), FwContent.CASING.get());
                    }
                }
            }
        }
        h.setBlock(CORE, FwContent.CORE.get());
    }

    private static FrameCoreEntity core(GameTestHelper h) {
        return (FrameCoreEntity) h.getLevel().getBlockEntity(h.absolutePos(CORE));
    }

    private static void install(GameTestHelper h, Player p, BlockPos at, Direction side, String module) {
        ItemStack stack = new ItemStack(FieldframeForgeApi.moduleItem(FfId.parse(module)));
        p.setItemInHand(InteractionHand.MAIN_HAND, stack);
        BlockPos abs = h.absolutePos(at);
        Vec3 hit = Vec3.atCenterOf(abs).add(side.getStepX() * 0.5, side.getStepY() * 0.5, side.getStepZ() * 0.5);
        h.getLevel().getBlockState(abs).useItemOn(stack, h.getLevel(), p, InteractionHand.MAIN_HAND, new BlockHitResult(hit, side, abs, false));
    }

    /** A 5x5x5 frame with a vault and a cell inside forms: 54 sockets, the vault's slots and the cell's RF. */
    @GameTest(template = "empty9", timeoutTicks = 100)
    public static void formsWithSockets(GameTestHelper h) {
        build(h, 5);
        h.setBlock(O.offset(2, 2, 2), FwContent.VAULT.get());
        h.setBlock(O.offset(1, 1, 1), FwContent.CELL.get());
        h.succeedWhen(() -> {
            FrameCoreEntity c = core(h);
            h.assertTrue(c != null && c.formed(), "formed: " + (c == null ? "no core" : c.lastCheck()));
            h.assertTrue(c.hub().linkedSockets() == 54, "54 sockets linked, not " + c.hub().linkedSockets());
            h.assertTrue(c.hub().slotsPerLane() == GrandFrame.baseSlots() + 3, "a vault adds 3 slots");
            h.assertTrue(c.hub().rfCapacity() == GrandFrame.baseRf() + 1_000_000, "a cell adds 1,000,000 RF");
        });
    }

    /** A heater in one wall smelts with power from a fuel engine in another, from the frame's one storage. */
    @GameTest(template = "empty9", timeoutTicks = 600)
    public static void socketsShareStorage(GameTestHelper h) {
        build(h, 5);
        Player p = h.makeMockPlayer(GameType.SURVIVAL);
        h.runAfterDelay(5, () -> {
            install(h, p, O.offset(2, 4, 2), Direction.UP, "fieldframe:heater");
            install(h, p, O.offset(2, 2, 4), Direction.SOUTH, "fieldframe:fuel_engine");
            FrameCoreEntity c = core(h);
            ItemVariant ore = FieldframeForgeApi.variantOf(new ItemStack(Items.RAW_IRON));
            ItemVariant coal = FieldframeForgeApi.variantOf(new ItemStack(Items.COAL));
            c.hub().items(0).insert(ore, 2, false);
            c.hub().items(2).insert(coal, 1, false);
        });
        h.succeedWhen(() -> {
            FrameCoreEntity c = core(h);
            ItemVariant ingot = FieldframeForgeApi.variantOf(new ItemStack(Items.IRON_INGOT));
            h.assertTrue(c.hub().items(1).countOf(ingot) == 2, "two ingots in the frame's Green storage");
        });
    }

    /** Breaking a wall casing takes the frame apart (sockets dormant, contents kept); putting it back re-forms it. */
    @GameTest(template = "empty9", timeoutTicks = 300)
    public static void breaksAndReforms(GameTestHelper h) {
        build(h, 5);
        BlockPos wall = O.offset(4, 2, 2);
        h.runAfterDelay(5, () -> {
            core(h).hub().items(1).insert(FieldframeForgeApi.variantOf(new ItemStack(Items.DIAMOND)), 5, false);
            h.destroyBlock(wall);
            core(h).requestCheck();
        });
        h.runAfterDelay(15, () -> {
            h.assertTrue(!core(h).formed(), "came apart");
            h.assertTrue(core(h).hub().linkedSockets() == 0, "no sockets linked");
            h.assertTrue(core(h).hub().itemCount(1) == 5, "the diamonds stayed in the core");
            h.setBlock(wall, FwContent.CASING.get());
            core(h).requestCheck();
        });
        h.runAfterDelay(25, () -> h.succeedWhen(() -> {
            h.assertTrue(core(h).formed(), "formed again");
            h.assertTrue(core(h).hub().linkedSockets() == 54, "all sockets back");
        }));
    }

    /** Removing a vault shrinks the storage; the stacks that no longer fit drop at the core. */
    @GameTest(template = "empty9", timeoutTicks = 300)
    public static void componentRemovedSpills(GameTestHelper h) {
        build(h, 5);
        BlockPos vault = O.offset(2, 2, 2);
        h.setBlock(vault, FwContent.VAULT.get());
        h.runAfterDelay(5, () -> {
            FrameCoreEntity c = core(h);
            h.assertTrue(c.hub().slotsPerLane() == 7, "seven slots with the vault");
            for (int i = 0; i < 7; i++) {
                c.hub().items(0).insert(FieldframeForgeApi.variantOf(new ItemStack(i % 2 == 0 ? Items.COBBLESTONE : Items.DIRT)), 64, false);
            }
            h.destroyBlock(vault);
            c.requestCheck();
        });
        h.runAfterDelay(15, () -> h.succeedWhen(() -> {
            FrameCoreEntity c = core(h);
            h.assertTrue(c.hub().slotsPerLane() == 4, "back to four slots");
            h.assertTrue(c.hub().itemCount(0) == 4 * 64, "four stacks kept");
            h.assertItemEntityPresent(Items.COBBLESTONE, CORE.above(), 3);
        }));
    }

    /** A 5 by 5 ring of rails at height 4 with the controller in its north side; dirt fills the area inside below. */
    private static final BlockPos RING = new BlockPos(1, 4, 1);
    private static final BlockPos CONTROLLER = RING.offset(2, 0, 0);

    private static void ring(GameTestHelper h, boolean closed) {
        for (int x = 0; x < 5; x++) {
            for (int z = 0; z < 5; z++) {
                boolean edge = x == 0 || z == 0 || x == 4 || z == 4;
                if (!edge || (!closed && x == 4 && z == 2)) {
                    continue;
                }
                h.setBlock(RING.offset(x, 0, z), x == 2 && z == 0 ? FwContent.GANTRY_CONTROLLER.get() : FwContent.GANTRY_RAIL.get());
            }
        }
    }

    private static GantryControllerEntity gantry(GameTestHelper h) {
        return (GantryControllerEntity) h.getLevel().getBlockEntity(h.absolutePos(CONTROLLER));
    }

    /** Gantry: a Block Breaker on the controller's top makes it a quarry; the head clears the dirt into Green storage. */
    @GameTest(template = "empty9", timeoutTicks = 1200)
    public static void gantryQuarries(GameTestHelper h) {
        ring(h, true);
        for (int x = 1; x <= 3; x++) {
            for (int z = 1; z <= 3; z++) {
                for (int y = 2; y <= 3; y++) {
                    h.setBlock(RING.offset(x, y - RING.getY(), z), net.minecraft.world.level.block.Blocks.DIRT);
                }
            }
        }
        Player p = h.makeMockPlayer(GameType.SURVIVAL);
        h.runAfterDelay(5, () -> {
            install(h, p, CONTROLLER, Direction.UP, "fieldframe:block_breaker");
            gantry(h).hub().energy().receive(100_000, false);
        });
        h.succeedWhen(() -> {
            GantryControllerEntity g = gantry(h);
            h.assertTrue(g.formed() && g.head() != null, "formed with a head: " + g.status());
            h.assertTrue(g.program() == GantryControllerEntity.Program.QUARRY, "quarry program, not " + g.program());
            ItemVariant dirt = FieldframeForgeApi.variantOf(new ItemStack(Items.DIRT));
            h.assertTrue(g.hub().items(1).countOf(dirt) == 18, "18 dirt in Green storage, not " + g.hub().items(1).countOf(dirt) + " (" + g.status() + ")");
            for (int x = 1; x <= 3; x++) {
                for (int z = 1; z <= 3; z++) {
                    for (int y = 2; y <= 3; y++) {
                        h.assertBlockPresent(net.minecraft.world.level.block.Blocks.AIR, RING.offset(x, y - RING.getY(), z));
                    }
                }
            }
        });
    }

    /** A ring with a gap does not form, places no head, and says why. */
    @GameTest(template = "empty9", timeoutTicks = 100)
    public static void gantryOpenRing(GameTestHelper h) {
        ring(h, false);
        h.runAfterDelay(50, () -> h.succeedWhen(() -> {
            GantryControllerEntity g = gantry(h);
            h.assertTrue(!g.formed() && g.head() == null, "not formed");
            h.assertTrue(g.status() == GantryStatus.RING_PROBLEM, "status ring_problem, not " + g.status());
            // Save/load round trip: the one status value comes back as it was saved.
            var tag = g.saveWithFullMetadata(h.getLevel().registryAccess());
            GantryControllerEntity fresh = new GantryControllerEntity(g.getBlockPos(), g.getBlockState());
            fresh.loadWithComponents(tag, h.getLevel().registryAccess());
            h.assertTrue(fresh.status() == g.status() && fresh.formed() == g.formed() && fresh.done() == g.done(),
                    "round trip kept " + g.status() + ", got " + fresh.status());
        }));
    }

    /**
     * A 5 by 5 by 5 Starcore: six Frame Cells and four containment coils inside, eight Field Magnets on the top wall, a
     * Plasma Injector, a Plasma Probe and (if {@code program} is given) a Controller Plate on the south wall; full RF,
     * deuterium in the Orange tank and Ender Alloy dust in Purple storage.
     */
    private static void starcore(GameTestHelper h, String program) {
        build(h, 5);
        int cells = 0;
        for (int x = 1; x <= 3; x++) {
            for (int z = 1; z <= 3; z++) {
                if (cells < 6) {
                    h.setBlock(O.offset(x, 1, z), FwContent.CELL.get());
                    cells++;
                }
            }
        }
        h.setBlock(O.offset(1, 2, 1), FwContent.COIL.get());
        h.setBlock(O.offset(3, 2, 1), FwContent.COIL.get());
        h.setBlock(O.offset(1, 2, 3), FwContent.COIL.get());
        h.setBlock(O.offset(3, 2, 3), FwContent.COIL.get());
        Player p = h.makeMockPlayer(GameType.SURVIVAL);
        h.runAfterDelay(5, () -> {
            FrameCoreEntity c = core(h);
            h.assertTrue(c.isStarcore() && c.plasma().coils() == 4, "a Starcore with four coils: " + c.lastCheck());
            int magnets = 0;
            for (int x = 1; x <= 3; x++) {
                for (int z = 1; z <= 3; z++) {
                    if (magnets < 8) {
                        install(h, p, O.offset(x, 4, z), Direction.UP, StarcoreModules.FIELD_MAGNET.toString());
                        magnets++;
                    }
                }
            }
            install(h, p, O.offset(2, 2, 4), Direction.SOUTH, StarcoreModules.PLASMA_INJECTOR.toString());
            install(h, p, O.offset(1, 2, 4), Direction.SOUTH, StarcoreModules.PLASMA_PROBE.toString());
            if (program != null) {
                install(h, p, O.offset(3, 2, 4), Direction.SOUTH, "fieldframe:controller_plate");
                h.assertTrue(FieldframeForgeApi.setConfig(h.getLevel(), h.absolutePos(O.offset(3, 2, 4)), dev.fieldframe.api.face.LocalFace.FRONT,
                        new dev.fieldframe.api.data.FfData().putString("program", program)), "program set");
                h.assertTrue(FieldframeForgeApi.setBinding(h.getLevel(), h.absolutePos(O.offset(2, 2, 4)), dev.fieldframe.api.face.LocalFace.FRONT,
                        "rate", 2), "injector rate on signal 3");
            }
            c.hub().energy().receive(c.hub().rfCapacity(), false);
            c.hub().tank(0).fill(FieldframeForgeApi.variantOf(FwFluids.DEUTERIUM.get()), 4_000, false);
            ResourceLocation dust = ResourceLocation.fromNamespaceAndPath("fieldframe", "ender_alloy_dust");
            c.hub().items(2).insert(FieldframeForgeApi.variantOf(new ItemStack(net.minecraft.core.registries.BuiltInRegistries.ITEM.get(dust))), 8, false);
        });
    }

    /** A controller program that cuts the fuel when the pressure nears the field holds a Starcore lit for 1,000 ticks. */
    @GameTest(template = "empty9", timeoutTicks = 1600)
    public static void starcoreHeld(GameTestHelper h) {
        starcore(h, "# pressure on signal 1, injector rate on signal 3\n"
                + "when ch 1 > 11 then set ch 3 = 0\n"
                + "when ch 1 < 8 then set ch 3 = 15\n");
        int[] lit = {0};
        long[] rf = {0};
        h.onEachTick(() -> {
            FrameCoreEntity c = core(h);
            if (c != null && c.plasma().lit()) {
                lit[0]++;
                rf[0] += c.plasma().lastPower();
            }
        });
        h.succeedWhen(() -> {
            Plasma pl = core(h).plasma();
            h.assertTrue(pl.escapes() == 0, "no escapes");
            h.assertTrue(lit[0] >= 1_000, "lit for 1,000 ticks (" + lit[0] + " so far, " + Math.round(pl.temperature()) + " MK, pressure "
                    + pl.pressureLevel() + "/15)");
            h.assertTrue(rf[0] / lit[0] > 10_000, "more than 10,000 RF a tick on average, not " + rf[0] / Math.max(1, lit[0]));
        });
    }

    /** Without control (the injector always full) the plasma heats up until the pressure passes the field and escapes. */
    @GameTest(template = "empty9", timeoutTicks = 1200)
    public static void starcoreEscapes(GameTestHelper h) {
        starcore(h, null);
        boolean[] wasLit = {false};
        h.onEachTick(() -> {
            FrameCoreEntity c = core(h);
            wasLit[0] |= c != null && c.plasma().lit();
        });
        h.succeedWhen(() -> {
            Plasma pl = core(h).plasma();
            h.assertTrue(wasLit[0], "it ignited");
            h.assertTrue(pl.escapes() >= 1 && !pl.lit(), "the plasma escaped and the core went out (" + Math.round(pl.temperature()) + " MK)");
            h.assertTrue(pl.wear() >= 1, "the coils wore");
        });
    }

    /** Foundry: three copper ingots and a tin ingot melt, combine into bronze in the pool, and bronze pours out. */
    /** The alloys JEI shows on a client come from the server through FwNetwork: the round trip keeps every alloy. */
    @GameTest(template = "empty9", timeoutTicks = 20)
    public static void alloysSync(GameTestHelper h) {
        List<FoundryAlloys.Alloy> before = FoundryAlloys.all();
        h.assertTrue(!before.isEmpty(), "alloy data loaded");
        String json = FoundryAlloys.toJson();
        FoundryAlloys.acceptSync("{not json");
        h.assertTrue(FoundryAlloys.all().equals(before), "bad input keeps the alloys");
        FoundryAlloys.acceptSync(json);
        h.assertTrue(FoundryAlloys.all().equals(before), "the round trip keeps every alloy: " + FoundryAlloys.all().size() + " of " + before.size());
        h.succeed();
    }

    @GameTest(template = "empty9", timeoutTicks = 1500)
    public static void foundryMeltsAndAlloysBronze(GameTestHelper h) {
        build(h, 5);
        h.setBlock(O.offset(2, 2, 2), FwContent.CRUCIBLE.get());
        h.setBlock(O.offset(1, 1, 1), FwContent.CELL.get());
        h.runAfterDelay(5, () -> {
            FrameCoreEntity c = core(h);
            h.assertTrue(c.formed() && c.foundry().crucibles() == 1, "a foundry with one crucible: " + c.lastCheck());
            c.hub().energy().receive(800_000, false);
            c.hub().items(0).insert(FieldframeForgeApi.variantOf(new ItemStack(Items.COPPER_INGOT)), 3, false);
            ResourceLocation tin = ResourceLocation.fromNamespaceAndPath("metallurgy", "tin_ingot");
            c.hub().items(0).insert(FieldframeForgeApi.variantOf(new ItemStack(net.minecraft.core.registries.BuiltInRegistries.ITEM.get(tin))), 1, false);
        });
        h.succeedWhen(() -> {
            FrameCoreEntity c = core(h);
            var bronze = FieldframeForgeApi.variantOf(net.minecraft.core.registries.BuiltInRegistries.FLUID.get(ResourceLocation.fromNamespaceAndPath("metallurgy", "bronze")));
            h.assertTrue(c.hub().tank(0).amountOf(bronze) == 576, "576 mB of bronze in the Orange tank; pool " + c.foundry().pool()
                    + " at " + Math.round(c.foundry().temperature()) + " K, tank " + c.hub().fluidAmount(0));
            h.assertTrue(c.hub().itemCount(0) == 0, "everything melted");
        });
    }

    /** Cooling Tower: two fins turn 2,000 mB of steam in the Green tank into exactly 2,000 mB of water in the Orange tank. */
    @GameTest(template = "empty9", timeoutTicks = 200)
    public static void coolingTowerCondensesSteam(GameTestHelper h) {
        build(h, 5);
        h.setBlock(O.offset(2, 2, 2), FwContent.FIN.get());
        h.setBlock(O.offset(2, 1, 2), FwContent.FIN.get());
        var steam = FieldframeForgeApi.variantOf(net.minecraft.core.registries.BuiltInRegistries.FLUID.get(ResourceLocation.fromNamespaceAndPath("fieldframe", "steam")));
        var water = FieldframeForgeApi.variantOf(net.minecraft.world.level.material.Fluids.WATER);
        h.runAfterDelay(5, () -> {
            FrameCoreEntity c = core(h);
            h.assertTrue(c.formed() && c.coolingFins() == 2, "a cooling tower with two fins: " + c.lastCheck());
            h.assertTrue(c.hub().tank(1).fill(steam, 2_000, false) == 2_000, "steam in the Green tank");
        });
        h.succeedWhen(() -> {
            FrameCoreEntity c = core(h);
            h.assertTrue(c.hub().tank(1).amountOf(steam) == 0, "steam left: " + c.hub().tank(1).amountOf(steam));
            h.assertTrue(c.hub().tank(0).amountOf(water) == 2_000, "water back in the Orange tank: " + c.hub().tank(0).amountOf(water));
        });
    }

    /**
     * Arc Furnace: one electrode and a Frame Cell; with 1,000,000 RF in the buffer one arc turns two iron ingots and a
     * coal into two steel ingots (an arc alloy) and smelts four raw iron, all into Green storage, for one arc's RF.
     */
    @GameTest(template = "empty9", timeoutTicks = 200)
    public static void arcFurnaceSmeltsAndAlloys(GameTestHelper h) {
        build(h, 5);
        h.setBlock(O.offset(2, 2, 2), FwContent.ELECTRODE.get());
        h.setBlock(O.offset(1, 1, 1), FwContent.CELL.get());
        ResourceLocation steelId = ResourceLocation.fromNamespaceAndPath("metallurgy", "steel_ingot");
        ItemVariant steel = FieldframeForgeApi.variantOf(new ItemStack(net.minecraft.core.registries.BuiltInRegistries.ITEM.get(steelId)));
        ItemVariant ingot = FieldframeForgeApi.variantOf(new ItemStack(Items.IRON_INGOT));
        h.runAfterDelay(5, () -> {
            FrameCoreEntity c = core(h);
            h.assertTrue(c.formed() && c.arcElectrodes() == 1, "an arc furnace with one electrode: " + c.lastCheck());
            h.assertTrue(ArcAlloys.all().stream().anyMatch(a -> a.result().id().equals(FfId.of("metallurgy", "steel_ingot"))), "the steel arc alloy loaded");
            c.hub().energy().receive(1_000_000, false);
            c.hub().items(0).insert(FieldframeForgeApi.variantOf(new ItemStack(Items.RAW_IRON)), 4, false);
            c.hub().items(0).insert(ingot, 2, false);
            c.hub().items(0).insert(FieldframeForgeApi.variantOf(new ItemStack(Items.COAL)), 1, false);
        });
        h.runAfterDelay(10, () -> h.succeedWhen(() -> {
            FrameCoreEntity c = core(h);
            h.assertTrue(c.hub().items(1).countOf(steel) == 2, "two steel ingots in Green storage, not " + c.hub().items(1).countOf(steel));
            h.assertTrue(c.hub().items(1).countOf(ingot) == 4, "four smelted iron ingots in Green storage, not " + c.hub().items(1).countOf(ingot));
            h.assertTrue(c.hub().itemCount(0) == 0, "Orange storage empty, not " + c.hub().itemCount(0));
            h.assertTrue(c.hub().rfStored() == 1_000_000 - ArcFurnace.arcRf(), "one arc's RF taken: " + c.hub().rfStored());
            h.assertTrue(c.arcFurnace().arcs() == 1, "one arc, not " + c.arcFurnace().arcs());
        }));
    }

    /** Deuterium's bucket pours a source and scoops it back up, and the poured source spreads like water. */
    @GameTest(template = "empty9", timeoutTicks = 100)
    public static void deuteriumBucketPlacesAndFlows(GameTestHelper h) {
        List<String> bad = new java.util.ArrayList<>();
        pourAndScoop(h, new BlockPos(4, 1, 5), "deuterium", FwFluids.DEUTERIUM_BUCKET.get(),
                FwFluids.DEUTERIUM.get(), 0, bad);
        h.assertTrue(bad.isEmpty(), "deuterium bucket: " + bad);
        trough(h, 1);
        BlockPos s = new BlockPos(2, 2, 1);
        h.setBlock(s, FwFluids.DEUTERIUM.get().defaultFluidState().createLegacyBlock());
        h.succeedWhen(() -> h.assertTrue(h.getBlockState(s.east()).getFluidState().getType().isSame(FwFluids.DEUTERIUM.get()),
                "deuterium spreads"));
    }

    /** Uses the held stack like a right click and returns what ends up in the hand. */
    private static ItemStack use(ServerLevel level, Player p, ItemStack stack) {
        p.setItemInHand(InteractionHand.MAIN_HAND, stack);
        return stack.use(level, p, InteractionHand.MAIN_HAND).getObject();
    }

    /** A player standing on {@code floor} looking straight down at it. */
    private static FakePlayer lookingDown(GameTestHelper h, BlockPos floor) {
        FakePlayer fp = FakePlayerFactory.get(h.getLevel(), new GameProfile(UUID.randomUUID(), "[ff-fluid-bucket]"));
        BlockPos abs = h.absolutePos(floor.above());
        fp.setPos(abs.getX() + 0.5, abs.getY(), abs.getZ() + 0.5);
        fp.setYRot(0f);
        fp.setXRot(90f);
        return fp;
    }

    /** Pours {@code bucket} onto {@code floor}, checks the source, light and empty bucket, then scoops it back up. */
    private static void pourAndScoop(GameTestHelper h, BlockPos floor, String name, Item bucket, Fluid source, int light, List<String> bad) {
        BlockPos at = floor.above();
        h.setBlock(floor, Blocks.STONE);
        h.setBlock(at, Blocks.AIR);
        FakePlayer fp = lookingDown(h, floor);
        ItemStack after = use(h.getLevel(), fp, new ItemStack(bucket));
        FluidState fs = h.getBlockState(at).getFluidState();
        if (!fs.isSource() || !fs.getType().isSame(source)) {
            bad.add(name + ": bucket placed " + h.getBlockState(at));
            return;
        }
        if (!after.is(Items.BUCKET)) {
            bad.add(name + ": bucket left " + after);
        }
        if (h.getBlockState(at).getLightEmission() != light) {
            bad.add(name + ": light " + h.getBlockState(at).getLightEmission() + " != " + light);
        }
        ItemStack filled = use(h.getLevel(), fp, new ItemStack(Items.BUCKET));
        if (!filled.is(bucket)) {
            bad.add(name + ": empty bucket picked up " + filled);
        }
        if (!h.getBlockState(at).getFluidState().isEmpty()) {
            bad.add(name + ": fluid still there after pickup");
        }
    }

    /** A one-wide stone trough along x at row {@code z}, floor at y=1. */
    private static void trough(GameTestHelper h, int z) {
        for (int x = 0; x <= 6; x++) {
            h.setBlock(new BlockPos(x, 1, z), Blocks.STONE);
            h.setBlock(new BlockPos(x, 2, z - 1), Blocks.STONE);
            h.setBlock(new BlockPos(x, 3, z - 1), Blocks.STONE);
            h.setBlock(new BlockPos(x, 2, z + 1), Blocks.STONE);
            h.setBlock(new BlockPos(x, 3, z + 1), Blocks.STONE);
        }
        h.setBlock(new BlockPos(0, 2, z), Blocks.STONE);
        h.setBlock(new BlockPos(6, 2, z), Blocks.STONE);
    }
}
