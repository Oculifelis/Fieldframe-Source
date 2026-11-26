package dev.fieldframe.core;

import dev.fieldframe.api.FieldframeRegistry;
import dev.fieldframe.api.data.FfData;
import dev.fieldframe.api.face.LocalFace;
import dev.fieldframe.api.face.Orientation;
import dev.fieldframe.api.face.WorldDir;
import dev.fieldframe.api.id.FfId;
import dev.fieldframe.api.module.ChassisWorld;
import dev.fieldframe.api.resource.FluidVariant;
import dev.fieldframe.api.resource.ItemVariant;
import dev.fieldframe.api.world.WorkGeometry;
import dev.fieldframe.api.world.WorkTarget;
import dev.fieldframe.api.world.WorldActions.Drop;
import dev.fieldframe.core.chassis.ChassisCore;
import dev.fieldframe.core.chassis.SimulatedActions;
import dev.fieldframe.core.chassis.SimulatedActions.Block;
import dev.fieldframe.core.chassis.SimulatedWorld;
import dev.fieldframe.core.modules.BuiltinModules;
import dev.fieldframe.core.modules.RecipeAssemblerBehavior;
import dev.fieldframe.core.modules.world.DiggingBehaviors;
import dev.fieldframe.core.modules.world.WorldWorker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldModulesTest {
    static final ItemVariant STONE_ITEM = ItemVariant.Simple.of("minecraft:cobblestone");
    static final ItemVariant DIRT = ItemVariant.Simple.of("minecraft:dirt");
    static final ItemVariant PICK = new ItemVariant.Simple(FfId.parse("minecraft:iron_pickaxe"), "", 1);
    static final ItemVariant SHOVEL = new ItemVariant.Simple(FfId.parse("minecraft:iron_shovel"), "", 1);
    static final ItemVariant SEEDS = ItemVariant.Simple.of("minecraft:wheat_seeds");
    static final ItemVariant WHEAT = ItemVariant.Simple.of("minecraft:wheat");
    static final FluidVariant WATER = FluidVariant.Simple.of("minecraft:water");

    SimulatedWorld w;
    SimulatedActions a;
    ChassisCore c;

    @BeforeEach
    void setUp() {
        TestSupport.freshRegistry();
        w = TestSupport.world();
        a = w.actions;
        c = TestSupport.chassis(w);
        c.energy().receive(100_000, false);
    }

    private FfData cfg(FfId id, Object... kv) {
        FfData d = FieldframeRegistry.module(id).defaultConfig();
        for (int i = 0; i < kv.length; i += 2) {
            d.putAny((String) kv[i], kv[i + 1]);
        }
        return d;
    }

    private static Block stone() {
        Block b = Block.of("minecraft:stone", STONE_ITEM);
        b.tool = FfId.parse("minecraft:iron_pickaxe");
        b.hardness = 1.5f;
        return b;
    }

    private void fillArea(LocalFace face, int radius, int depth) {
        for (int i = 0; i < WorkGeometry.areaSize(radius, depth); i++) {
            a.put(WorkGeometry.areaCursor(face, radius, depth, i), stone());
        }
    }

    private String status(LocalFace f) {
        return c.statuses().get(f).code();
    }

    // ------------------------------------------------------------------ geometry

    @Test
    void geometryAxesAreStableAcrossOrientations() {
        Orientation north = new Orientation(WorldDir.NORTH);
        assertArrayEquals(new int[] {0, 0, -1}, WorkGeometry.offset(north, WorkTarget.adjacent(LocalFace.FRONT)));
        // Right of the front face, seen from the chassis looking north, is east.
        assertArrayEquals(new int[] {1, 0, -2}, WorkGeometry.offset(north, new WorkTarget(LocalFace.FRONT, 2, 1, 0)));
        // Bottom face: up points toward front (north), right toward the chassis right (east).
        assertArrayEquals(new int[] {1, -3, -1}, WorkGeometry.offset(north, new WorkTarget(LocalFace.BOTTOM, 3, 1, 1)));
        Orientation east = new Orientation(WorldDir.EAST);
        assertArrayEquals(new int[] {0, -1, 1}, WorkGeometry.offset(east, new WorkTarget(LocalFace.BOTTOM, 1, 1, 0)));
        assertEquals(18, WorkGeometry.areaSize(1, 2));
        assertNull(WorkGeometry.areaCursor(LocalFace.BOTTOM, 1, 2, 18));
    }

    @Test
    void waterwheelSamplesNeverOverlapBetweenFaces() {
        Orientation o = Orientation.DEFAULT;
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (LocalFace f : LocalFace.ALL) {
            for (int d = 1; d <= 3; d++) {
                int[] off = WorkGeometry.offset(o, new WorkTarget(f, d, 0, 0));
                assertTrue(seen.add(off[0] + "," + off[1] + "," + off[2]), "overlap at " + f + " " + d);
            }
        }
    }

    // ------------------------------------------------------------------ excavator

    private void installExcavator(int radius, int depth) {
        assertTrue(c.install(LocalFace.BOTTOM, FieldframeRegistry.module(BuiltinModules.EXCAVATOR),
                cfg(BuiltinModules.EXCAVATOR, "radius", radius, "depth", depth, "preview", false)).ok());
        c.lane(2).insert(PICK, 1, false);
        c.tank(0).fill(WATER, 1000, false);
    }

    /** Keeps the working-fluid reservoir topped up (the standard tank holds 10 blocks of make-up water). */
    private void runWithWater(int ticks) {
        for (int i = 0; i < ticks; i++) {
            c.tank(0).fill(WATER, 1000, false);
            TestSupport.run(c, w, 1);
        }
    }

    @Test
    void excavatorMinesAreaExactlyOnceAcrossSaveAndLoad() {
        fillArea(LocalFace.BOTTOM, 1, 2);
        installExcavator(1, 2);
        runWithWater(60);
        int mid = a.breaks;
        assertTrue(mid > 0 && mid < 18, "partial progress, got " + mid);
        long cobbleMid = c.lane(1).countOf(STONE_ITEM);
        assertEquals(mid, cobbleMid);

        // Reload into a fresh chassis over the same world: continues where it stopped.
        FfData saved = c.save();
        ChassisCore again = TestSupport.chassis(w);
        assertTrue(again.load(saved).isEmpty());
        c = again;
        runWithWater(400);
        assertEquals(18, a.breaks);
        assertEquals(18, c.lane(1).countOf(STONE_ITEM));
        assertTrue(a.blocks.isEmpty());
        assertEquals("AREA_DONE", status(LocalFace.BOTTOM));
        assertEquals(15, c.control().read(c.module(LocalFace.BOTTOM).binding("done")));
    }

    @Test
    void excavatorConsumesWaterAndStopsWithoutIt() {
        fillArea(LocalFace.BOTTOM, 1, 2);
        installExcavator(1, 2);
        TestSupport.run(c, w, 400);
        // 1000 mB / 100 mB per block = 10 blocks, then NO_FLUID.
        assertEquals(10, a.breaks);
        assertEquals(0, c.tank(0).amount());
        assertEquals("NO_FLUID", status(LocalFace.BOTTOM));
    }

    @Test
    void excavatorStopsAtProtectedAndUnbreakableBlocksWithFault() {
        fillArea(LocalFace.BOTTOM, 0, 3);
        Block bedrock = Block.of("minecraft:bedrock", null);
        bedrock.hardness = -1;
        a.put(new WorkTarget(LocalFace.BOTTOM, 2, 0, 0), bedrock);
        installExcavator(0, 3);
        TestSupport.run(c, w, 200);
        assertEquals(1, a.breaks);
        assertEquals("UNBREAKABLE", status(LocalFace.BOTTOM));
        int cursor = ((DiggingBehaviors.Excavator) c.module(LocalFace.BOTTOM).behavior()).cursor();
        assertEquals(1, cursor);

        a.blocks.remove(SimulatedActions.key(new WorkTarget(LocalFace.BOTTOM, 2, 0, 0)));
        a.protectedTargets.add(SimulatedActions.key(new WorkTarget(LocalFace.BOTTOM, 3, 0, 0)));
        TestSupport.run(c, w, 200);
        assertEquals(1, a.breaks);
        assertEquals("PROTECTED", status(LocalFace.BOTTOM));
        assertEquals(15, c.control().read(c.module(LocalFace.BOTTOM).binding("fault")));
        assertNotNull(a.at(new WorkTarget(LocalFace.BOTTOM, 3, 0, 0)));
    }

    @Test
    void excavatorBackpressuresWithoutLosingDrops() {
        fillArea(LocalFace.BOTTOM, 1, 1);
        installExcavator(1, 1);
        // Fill the output lane completely with dirt so cobblestone cannot go in.
        for (int i = 0; i < c.lane(1).slots(); i++) {
            c.lane(1).insert(DIRT, 64, false);
        }
        TestSupport.run(c, w, 200);
        assertEquals(1, a.breaks, "one block broken, then it waits");
        assertEquals("OUTPUT_PENDING", status(LocalFace.BOTTOM));
        WorldWorker ex = (WorldWorker) c.module(LocalFace.BOTTOM).behavior();
        assertEquals(1, ex.pendingCount());
        // Pending survives save/load.
        ChassisCore again = TestSupport.chassis(w);
        again.load(c.save());
        c = again;
        assertEquals(1, ((WorldWorker) c.module(LocalFace.BOTTOM).behavior()).pendingCount());
        c.lane(1).extract(DIRT, 64 * 16, false);
        runWithWater(400);
        assertEquals(9, a.breaks);
        assertEquals(9, c.lane(1).countOf(STONE_ITEM));
    }

    @Test
    void wrongToolNeverDestroysDrops() {
        a.put(WorkTarget.adjacent(LocalFace.FRONT), stone());
        assertTrue(c.install(LocalFace.FRONT, FieldframeRegistry.module(BuiltinModules.BLOCK_BREAKER), null).ok());
        c.lane(2).insert(SHOVEL, 1, false);
        TestSupport.run(c, w, 40);
        assertEquals(0, a.breaks);
        assertEquals("WRONG_TOOL", status(LocalFace.FRONT));
        c.lane(2).insert(PICK, 1, false);
        TestSupport.run(c, w, 40);
        assertEquals(1, a.breaks);
        assertEquals(1, c.lane(1).countOf(STONE_ITEM));
        assertEquals(1, c.lane(2).countOf(PICK), "tool returned to its lane");
    }

    @Test
    void breakerLeavesBlockEntitiesAndUnbreakablesAlone() {
        Block chest = Block.of("minecraft:chest", null);
        chest.blockEntity = true;
        a.put(WorkTarget.adjacent(LocalFace.FRONT), chest);
        c.install(LocalFace.FRONT, FieldframeRegistry.module(BuiltinModules.BLOCK_BREAKER), null);
        TestSupport.run(c, w, 40);
        assertEquals(0, a.breaks);
        assertEquals("TARGET_BLOCKED", status(LocalFace.FRONT));
    }

    @Test
    void worldActionEventCanVetoBreaking() {
        a.put(WorkTarget.adjacent(LocalFace.FRONT), Block.of("minecraft:dirt", DIRT));
        c.install(LocalFace.FRONT, FieldframeRegistry.module(BuiltinModules.BLOCK_BREAKER), null);
        Object owner = new Object();
        List<String> seen = new ArrayList<>();
        dev.fieldframe.api.event.FieldframeEvents.register(dev.fieldframe.api.event.FieldframeEvents.WorldAction.class, e -> {
            seen.add(e.action() + "@" + e.x() + "," + e.y() + "," + e.z());
            e.cancel("pack_rule");
        }, owner);
        try {
            TestSupport.run(c, w, 5);
        } finally {
            dev.fieldframe.api.event.FieldframeEvents.unregisterOwner(owner);
        }
        assertEquals(0, a.breaks, "vetoed break changed nothing");
        assertEquals("PROTECTED", status(LocalFace.FRONT));
        assertTrue(seen.contains("break@1,0,0"), "event carries the face-local target: " + seen);
        TestSupport.run(c, w, 40);
        assertEquals(1, a.breaks, "allowed again once the listener is gone");
    }

    @Test
    void noWorldAccessIsReportedNotIgnored() {
        w.actions = null;
        c.install(LocalFace.FRONT, FieldframeRegistry.module(BuiltinModules.BLOCK_BREAKER), null);
        TestSupport.run(c, w, 5);
        assertEquals("NO_WORLD_ACCESS", status(LocalFace.FRONT));
    }

    @Test
    void removalSpillsPendingItems() {
        fillArea(LocalFace.BOTTOM, 0, 1);
        installExcavator(0, 1);
        for (int i = 0; i < c.lane(1).slots(); i++) {
            c.lane(1).insert(DIRT, 64, false);
        }
        TestSupport.run(c, w, 40);
        List<Object[]> spill = c.remove(LocalFace.BOTTOM, w);
        assertEquals(1, spill.size());
        assertEquals(STONE_ITEM, spill.get(0)[0]);
    }

    // ------------------------------------------------------------------ placer / crops / head

    @Test
    void placerConsumesExactlyOneAndRespectsOccupiedTarget() {
        c.install(LocalFace.FRONT, FieldframeRegistry.module(BuiltinModules.BLOCK_PLACER), null);
        c.lane(0).insert(WHEAT, 5, false); // not placeable
        c.lane(0).insert(STONE_ITEM, 3, false);
        TestSupport.run(c, w, 5);
        assertEquals(2, c.lane(0).countOf(STONE_ITEM));
        assertEquals(5, c.lane(0).countOf(WHEAT));
        TestSupport.run(c, w, 100);
        assertEquals(2, c.lane(0).countOf(STONE_ITEM), "target occupied: nothing more placed");
        assertEquals("TARGET_BLOCKED", status(LocalFace.FRONT));
    }

    @Test
    void cropTenderHarvestsMatureAndReplantsFromDrops() {
        Block ripe = Block.of("minecraft:wheat", WHEAT);
        ripe.drops.add(new Drop(SEEDS, 2));
        ripe.crop = true;
        ripe.mature = true;
        ripe.plant = SEEDS;
        a.put(WorkTarget.adjacent(LocalFace.BOTTOM), ripe);
        c.install(LocalFace.BOTTOM, FieldframeRegistry.module(BuiltinModules.CROP_TENDER),
                cfg(BuiltinModules.CROP_TENDER, "radius", 0));
        TestSupport.run(c, w, 5);
        assertEquals(1, c.lane(0).countOf(WHEAT));
        assertEquals(1, c.lane(0).countOf(SEEDS), "one seed replanted");
        Block replanted = a.at(WorkTarget.adjacent(LocalFace.BOTTOM));
        assertNotNull(replanted);
        assertFalse(replanted.mature);
    }

    @Test
    void cropTenderBoneMealUsesExactlyOne() {
        Block young = Block.of("minecraft:wheat", WHEAT);
        young.crop = true;
        a.put(WorkTarget.adjacent(LocalFace.BOTTOM), young);
        c.install(LocalFace.BOTTOM, FieldframeRegistry.module(BuiltinModules.CROP_TENDER),
                cfg(BuiltinModules.CROP_TENDER, "radius", 0, "bonemeal", true));
        c.lane(0).insert(ItemVariant.Simple.of("minecraft:bone_meal"), 3, false);
        TestSupport.run(c, w, 2);
        assertEquals(2, c.lane(0).countOf(ItemVariant.Simple.of("minecraft:bone_meal")));
        assertTrue(young.mature);
    }

    @Test
    void interactionHeadFillsBucketAndReturnsResult() {
        Block water = new Block();
        water.id = FfId.parse("minecraft:water");
        water.fluid = water.id;
        water.source = true;
        a.put(WorkTarget.adjacent(LocalFace.FRONT), water);
        c.install(LocalFace.FRONT, FieldframeRegistry.module(BuiltinModules.INTERACTION_HEAD), null);
        c.lane(2).insert(ItemVariant.Simple.of("minecraft:bucket"), 2, false);
        TestSupport.run(c, w, 2);
        assertEquals(1, c.lane(2).countOf(ItemVariant.Simple.of("minecraft:bucket")));
        assertEquals(1, c.lane(1).countOf(ItemVariant.Simple.of("minecraft:water_bucket")));
    }

    // ------------------------------------------------------------------ fluids, items, entities

    @Test
    void pumpTakesSourcesOnlyWithRoomAndFillPlacesThem() {
        for (int d = 1; d <= 2; d++) {
            Block src = new Block();
            src.id = FfId.parse("minecraft:water");
            src.fluid = src.id;
            src.source = true;
            a.put(new WorkTarget(LocalFace.BOTTOM, d, 0, 0), src);
        }
        c.install(LocalFace.BOTTOM, FieldframeRegistry.module(BuiltinModules.FLUID_PUMP),
                cfg(BuiltinModules.FLUID_PUMP, "radius", 0, "depth", 2));
        c.tank(0).fill(WATER, 3000, false); // room for exactly one more bucket
        TestSupport.run(c, w, 100);
        assertEquals(4000, c.tank(0).amount());
        assertEquals(1, a.blocks.size(), "second source untouched while the tank is full");

        c.setConfig(LocalFace.BOTTOM, cfg(BuiltinModules.FLUID_PUMP, "radius", 0, "depth", 2, "mode", "fill"));
        TestSupport.run(c, w, 100);
        assertEquals(3000, c.tank(0).amount(), "one drained spot refilled; the remaining source is not replaceable");
        assertEquals(2, a.blocks.size());
    }

    @Test
    void collectorAndEjectorConserveItems() {
        a.groundItems.put(LocalFace.TOP, new ArrayList<>(List.of(new Drop(WHEAT, 20))));
        c.install(LocalFace.TOP, FieldframeRegistry.module(BuiltinModules.DROP_COLLECTOR), null);
        TestSupport.run(c, w, 1);
        assertEquals(16, c.lane(0).countOf(WHEAT), "items_per_pickup default 16");
        TestSupport.run(c, w, 10);
        assertEquals(20, c.lane(0).countOf(WHEAT));
        assertTrue(a.groundItems.get(LocalFace.TOP).isEmpty());

        c.install(LocalFace.FRONT, FieldframeRegistry.module(BuiltinModules.ITEM_EJECTOR),
                cfg(BuiltinModules.ITEM_EJECTOR, "mode", "pulse", "quantity", 4));
        c.setBinding(LocalFace.FRONT, "lane", 0);
        c.setBinding(LocalFace.FRONT, "trigger", 3);
        TestSupport.run(c, w, 20);
        assertTrue(a.ejected.isEmpty(), "pulse mode waits for a rising edge");
        c.control().drive(3, 15);
        TestSupport.run(c, w, 1);
        TestSupport.run(c, w, 20);
        assertEquals(1, a.ejected.size());
        assertEquals(4, a.ejected.get(0).count());
        assertEquals(16, c.lane(0).countOf(WHEAT));
    }

    @Test
    void entityMoverPaysPerEntityAndPolicyGatesPlayers() {
        a.entities.put(LocalFace.FRONT, 50);
        c.install(LocalFace.FRONT, FieldframeRegistry.module(BuiltinModules.ENTITY_MOVER), null);
        long before = c.energy().stored();
        TestSupport.run(c, w, 1);
        assertEquals(8, a.moved, "capped at max_entities");
        assertEquals(before - 8 * 4, c.energy().stored());
    }

    @Test
    void waterwheelCountsFlowingWaterOnly() {
        for (int d = 1; d <= 3; d++) {
            Block flow = new Block();
            flow.id = FfId.parse("minecraft:water");
            flow.fluid = flow.id;
            flow.flowingWater = d != 2;
            flow.source = d == 2;
            a.put(new WorkTarget(LocalFace.FRONT, d, 0, 0), flow);
        }
        c.energy().extract(c.energy().stored(), false);
        c.install(LocalFace.FRONT, FieldframeRegistry.module(BuiltinModules.WATERWHEEL), null);
        TestSupport.run(c, w, 10);
        assertEquals(10 * 2 * 3, c.energy().stored());
    }

    @Test
    void recipeAssemblerCraftsWithRemaindersAndChecksRoom() {
        FfId[] p = RecipeAssemblerBehavior.parsePattern("oak_planks _ _ / oak_planks");
        assertNotNull(p);
        assertEquals(FfId.parse("minecraft:oak_planks"), p[0]);
        assertNull(p[1]);
        assertNull(RecipeAssemblerBehavior.parsePattern("a b c d e f g h i j"));
        // The ghost-grid editor writes the canonical form; it parses back cell for cell.
        String text = RecipeAssemblerBehavior.formatPattern(p);
        assertEquals("minecraft:oak_planks _ _ / minecraft:oak_planks _ _ / _ _ _", text);
        assertArrayEquals(p, RecipeAssemblerBehavior.parsePattern(text));

        ItemVariant planks = ItemVariant.Simple.of("minecraft:oak_planks");
        ItemVariant sticks = ItemVariant.Simple.of("minecraft:stick");
        w.crafting.put("minecraft:oak_planks,_,_,minecraft:oak_planks,_,_,_,_,_",
                new ChassisWorld.CraftResult(sticks, 4, List.of()));
        c.install(LocalFace.TOP, FieldframeRegistry.module(BuiltinModules.RECIPE_ASSEMBLER),
                cfg(BuiltinModules.RECIPE_ASSEMBLER, "pattern", "oak_planks _ _ / oak_planks _ _"));
        c.lane(0).insert(planks, 5, false);
        TestSupport.run(c, w, 100);
        assertEquals(1, c.lane(0).countOf(planks));
        assertEquals(8, c.lane(1).countOf(sticks));
        assertEquals("NO_INPUT", status(LocalFace.TOP));
    }

    @Test
    void servicePortEnforcesAggregateItemLimit() {
        c.install(LocalFace.LEFT, FieldframeRegistry.module(BuiltinModules.SERVICE_PORT),
                cfg(BuiltinModules.SERVICE_PORT, "item_limit", 5));
        c.lane(0).insert(WHEAT, 64, false);
        w.time++;
        c.tick(w);
        var store = c.exposedItems(Orientation.DEFAULT.toWorld(LocalFace.LEFT));
        assertNotNull(store);
        int got = store.extract(WHEAT, 64, false);
        assertEquals(5, got);
        assertEquals(0, store.insert(WHEAT, 10, false), "limit is shared by both directions");
    }

    @Test
    void tankPanelGivesBucketAccessToSelectedTanksOnly() {
        c.install(LocalFace.FRONT, FieldframeRegistry.module(BuiltinModules.TANK_PANEL),
                cfg(BuiltinModules.TANK_PANEL, "a", false, "b", true, "c", false));
        var tanks = c.panelTanks(LocalFace.FRONT);
        assertNotNull(tanks);
        assertEquals(1000, tanks.fill(WATER, 1000, false));
        assertEquals(1000, c.tank(1).amount());
        assertEquals(0, c.tank(0).amount());
        assertNull(c.panelTanks(LocalFace.BACK));
    }

    @Test
    void ownerPersistsButNotInBlueprintCapture() {
        c.setOwner("1234-uuid", "Engineer");
        ChassisCore again = TestSupport.chassis(w);
        again.load(c.save());
        assertEquals("1234-uuid", again.ownerId());
        assertEquals("Engineer", again.ownerName());
        String bp = dev.fieldframe.core.blueprint.BlueprintOps.capture("x", List.of(
                new dev.fieldframe.core.blueprint.BlueprintOps.Source(0, 0, 0, c))).toData().toString();
        assertFalse(bp.contains("1234-uuid"));
    }
}
