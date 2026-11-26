package dev.fieldframe.core;

import dev.fieldframe.api.FieldframeRegistry;
import dev.fieldframe.api.data.FfData;
import dev.fieldframe.api.event.FieldframeEvents;
import dev.fieldframe.api.face.LocalFace;
import dev.fieldframe.api.face.WorldDir;
import dev.fieldframe.api.id.FfId;
import dev.fieldframe.api.resource.EnergyStore;
import dev.fieldframe.api.resource.ItemStore;
import dev.fieldframe.core.chassis.ChassisCore;
import dev.fieldframe.core.chassis.SimulatedWorld;
import dev.fieldframe.core.modules.BuiltinModules;
import dev.fieldframe.core.modules.ProcessorBehavior;
import dev.fieldframe.core.storage.EnergyBuffer;
import dev.fieldframe.core.storage.ItemLane;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static dev.fieldframe.core.TestSupport.COAL;
import static dev.fieldframe.core.TestSupport.FLINT;
import static dev.fieldframe.core.TestSupport.GRAVEL;
import static dev.fieldframe.core.TestSupport.IRON;
import static dev.fieldframe.core.TestSupport.IRON_ORE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChassisTest {
    SimulatedWorld w;
    ChassisCore c;

    @BeforeEach
    void setUp() {
        TestSupport.freshRegistry();
        w = TestSupport.world();
        c = TestSupport.chassis(w);
    }

    private FfData cfg(String id, Object... kv) {
        FfData d = FieldframeRegistry.module(FfId.parse(id)).defaultConfig();
        for (int i = 0; i < kv.length; i += 2) {
            d.putAny((String) kv[i], kv[i + 1]);
        }
        return d;
    }

    /** Scenario 1/2: first machine with chest import, fuel engine, heater, chest export. */
    @Test
    void firstMachineAdjacentAutomation() {
        ItemLane inChest = new ItemLane("chest", 27);
        ItemLane outChest = new ItemLane("chest", 27);
        inChest.insert(IRON_ORE, 8, false);
        w.items.put(LocalFace.LEFT, inChest);
        w.items.put(LocalFace.RIGHT, outChest);
        assertTrue(c.install(LocalFace.LEFT, FieldframeRegistry.module(BuiltinModules.ITEM_PORT), cfg("item_port", "mode", "import")).ok());
        assertTrue(c.install(LocalFace.RIGHT, FieldframeRegistry.module(BuiltinModules.ITEM_PORT), cfg("item_port", "mode", "export")).ok());
        c.setBinding(LocalFace.RIGHT, "lane", 1);
        assertTrue(c.install(LocalFace.TOP, FieldframeRegistry.module(BuiltinModules.HEATER), null).ok());
        assertTrue(c.install(LocalFace.BOTTOM, FieldframeRegistry.module(BuiltinModules.FUEL_ENGINE), null).ok());
        // Smart defaults: the heater takes from Orange and puts into Green, the engine burns fuel from Purple.
        assertEquals(0, c.module(LocalFace.TOP).binding("input"));
        assertEquals(1, c.module(LocalFace.TOP).binding("output"));
        assertEquals(2, c.module(LocalFace.BOTTOM).binding("fuel"), "fuel comes from Purple storage by default");
        c.lane(2).insert(COAL, 2, false);

        TestSupport.run(c, w, 1200);
        assertEquals(8, outChest.countOf(IRON));
        assertEquals(0, inChest.countOf(IRON_ORE));
        assertEquals(0, c.lane(0).totalCount());
        assertEquals(0, c.lane(1).totalCount());
        // 8 smelts x 100 ticks x 20 RF = 16,000 RF consumed out of 32,000 created, minus buffer cap effects.
        assertTrue(c.energy().stored() > 0);
    }

    @Test
    void outputFullBlocksWithoutRerollOrLoss() {
        c.install(LocalFace.TOP, FieldframeRegistry.module(BuiltinModules.CRUSHER), null);
        c.energy().receive(16_000, false);
        // Fill output lane B with junk so products cannot fit.
        for (int s = 0; s < c.lane(1).slots(); s++) {
            c.lane(1).insert(TestSupport.COBBLE, 64, false);
        }
        c.lane(0).insert(GRAVEL, 4, false);
        TestSupport.run(c, w, 100);
        // Preflight refuses to start because the maximum product would not fit.
        assertEquals("OUTPUT_FULL", c.module(LocalFace.TOP).status().code());
        assertEquals(4, c.lane(0).countOf(GRAVEL));
        c.lane(1).extract(TestSupport.COBBLE, 64, false);
        TestSupport.run(c, w, 200);
        assertEquals(0, c.lane(0).countOf(GRAVEL));
        long flint = c.lane(1).countOf(FLINT);
        assertTrue(flint >= 0 && flint <= 4);
    }

    @Test
    void reservedOperationSurvivesSaveLoadExactlyOnce() {
        c.install(LocalFace.TOP, FieldframeRegistry.module(BuiltinModules.HEATER), null);
        c.energy().receive(16_000, false);
        c.lane(0).insert(IRON_ORE, 1, false);
        TestSupport.run(c, w, 50);
        assertEquals(0, c.lane(0).totalCount(), "input reserved");
        FfData saved = c.save();

        ChassisCore again = TestSupport.chassis(w);
        assertTrue(again.load(saved).isEmpty());
        TestSupport.run(again, w, 60);
        assertEquals(1, again.lane(1).countOf(IRON));
        TestSupport.run(again, w, 200);
        assertEquals(1, again.lane(1).countOf(IRON), "no duplicate commit");
    }

    @Test
    void removalRefundsReservedInputs() {
        c.install(LocalFace.TOP, FieldframeRegistry.module(BuiltinModules.HEATER), null);
        c.energy().receive(16_000, false);
        c.lane(0).insert(IRON_ORE, 3, false);
        TestSupport.run(c, w, 10);
        assertEquals(2, c.lane(0).countOf(IRON_ORE));
        List<Object[]> spill = c.remove(LocalFace.TOP, w);
        assertTrue(spill.isEmpty());
        assertEquals(3, c.lane(0).countOf(IRON_ORE));
        assertEquals(0, c.lane(1).countOf(IRON));
    }

    @Test
    void removalWithFullLanesSpillsInsteadOfVoiding() {
        c.install(LocalFace.TOP, FieldframeRegistry.module(BuiltinModules.HEATER), null);
        c.energy().receive(16_000, false);
        c.lane(0).insert(IRON_ORE, 1, false);
        TestSupport.run(c, w, 5);
        for (int lane = 0; lane < 2; lane++) {
            for (int s = 0; s < c.lane(lane).slots(); s++) {
                c.lane(lane).insert(TestSupport.COBBLE, 64, false);
            }
        }
        List<Object[]> spill = c.remove(LocalFace.TOP, w);
        assertEquals(1, spill.size());
        assertEquals(IRON_ORE, spill.get(0)[0]);
        assertEquals(1, spill.get(0)[1]);
    }

    @Test
    void noEnergyIsDistinctAndDoesNotProgress() {
        c.install(LocalFace.TOP, FieldframeRegistry.module(BuiltinModules.HEATER), null);
        c.lane(0).insert(IRON_ORE, 1, false);
        TestSupport.run(c, w, 5);
        assertEquals("NO_ENERGY", c.module(LocalFace.TOP).status().code());
        assertEquals(0.0, c.module(LocalFace.TOP).behavior().progress());
    }

    @Test
    void redstoneEnableGatesProcessing() {
        c.install(LocalFace.TOP, FieldframeRegistry.module(BuiltinModules.HEATER), null);
        c.setBinding(LocalFace.TOP, "enable", 1);
        c.energy().receive(16_000, false);
        c.lane(0).insert(IRON_ORE, 1, false);
        TestSupport.run(c, w, 5);
        assertEquals("SIGNAL_DISABLED", c.module(LocalFace.TOP).status().code());
        assertEquals(List.of("signal 2"), c.module(LocalFace.TOP).status().args());
    }

    @Test
    void passiveFacesShareOneItemBudget() {
        for (LocalFace f : new LocalFace[]{LocalFace.LEFT, LocalFace.RIGHT, LocalFace.BACK}) {
            c.install(f, FieldframeRegistry.module(BuiltinModules.ITEM_PORT), null);
        }
        w.time = 10;
        int total = 0;
        for (WorldDir d : new WorldDir[]{WorldDir.WEST, WorldDir.EAST, WorldDir.SOUTH}) {
            ItemStore s = c.exposedItems(d);
            assertNotNull(s);
            total += s.insert(IRON_ORE, 64, false);
        }
        assertEquals(c.spec().itemsPerTick(), total);
        w.time = 11;
        assertEquals(c.spec().itemsPerTick(), c.exposedItems(WorldDir.WEST).insert(IRON_ORE, 64, false));
    }

    @Test
    void unconfiguredAndUnspecifiedSidesExposeNothing() {
        c.install(LocalFace.TOP, FieldframeRegistry.module(BuiltinModules.HEATER), null);
        for (WorldDir d : WorldDir.values()) {
            assertNull(c.exposedItems(d));
            assertNull(c.exposedFluids(d));
            assertNull(c.exposedEnergy(d));
        }
        c.install(LocalFace.FRONT, FieldframeRegistry.module(BuiltinModules.ITEM_PORT), null);
        assertNull(c.exposedItems(null));
        assertNull(c.exposedEnergy(WorldDir.NORTH), "item port is not an energy port");
    }

    @Test
    void terminalsShareDirectionalEnergyBudgetAndSimulationIsFree() {
        c.install(LocalFace.LEFT, FieldframeRegistry.module(BuiltinModules.POWER_TERMINAL), null);
        c.install(LocalFace.RIGHT, FieldframeRegistry.module(BuiltinModules.POWER_TERMINAL), null);
        w.time = 5;
        EnergyStore a = c.exposedEnergy(WorldDir.WEST);
        EnergyStore b = c.exposedEnergy(WorldDir.EAST);
        assertEquals(512, a.receive(10_000, true));
        assertEquals(512, a.receive(10_000, true), "simulate does not spend budget");
        assertEquals(400, a.receive(400, false));
        assertEquals(112, b.receive(10_000, false));
        assertEquals(0, a.extract(10, false), "receive-mode terminal cannot be drained");
        assertEquals(512, c.energy().stored());
    }

    @Test
    void pushTerminalFeedsExternalHandler() {
        c.install(LocalFace.BACK, FieldframeRegistry.module(BuiltinModules.POWER_TERMINAL), cfg("power_terminal", "mode", "push"));
        EnergyBuffer external = new EnergyBuffer(100_000);
        w.energy.put(LocalFace.BACK, external);
        c.energy().receive(16_000, false);
        TestSupport.run(c, w, 10);
        assertEquals(2560, external.stored());
        assertEquals(16_000 - 2560, c.energy().stored());
    }

    @Test
    void missingDefinitionIsPreservedVerbatim() {
        c.install(LocalFace.TOP, FieldframeRegistry.module(BuiltinModules.HEATER), null);
        c.lane(0).insert(IRON_ORE, 5, false);
        FfData saved = c.save();
        ChassisCore other = TestSupport.chassis(w);
        other.setDefinitionLookup(id -> id.equals(BuiltinModules.HEATER) ? null : FieldframeRegistry.module(id));
        List<String> problems = other.load(saved);
        assertEquals(1, problems.size());
        assertTrue(other.module(LocalFace.TOP).isMissing());
        assertEquals("MISSING_DEFINITION", other.module(LocalFace.TOP).status().code());
        assertEquals(5, other.lane(0).countOf(IRON_ORE));
        TestSupport.run(other, w, 20);
        // Re-save and re-load with the definition back: the module returns intact.
        ChassisCore restored = TestSupport.chassis(w);
        assertTrue(restored.load(other.save()).isEmpty());
        assertFalse(restored.module(LocalFace.TOP).isMissing());
    }

    @Test
    void upgradeRemovalRefusesTruncation() {
        assertTrue(c.installUpgrade(0, FieldframeRegistry.upgrade(FfId.ff("storage_upgrade"))).ok());
        assertEquals(8, c.lane(0).slots());
        for (int i = 0; i < 6; i++) {
            c.lane(0).insert(i % 2 == 0 ? IRON_ORE : COAL, 64, false);
        }
        ChassisCore.Result r = c.removeUpgrade(0);
        assertFalse(r.ok());
        assertEquals("WOULD_TRUNCATE_ITEMS", r.code());
        assertEquals(8, c.lane(0).slots());
        assertEquals(384, c.lane(0).totalCount());
    }

    @Test
    void forgedConfigurationIsSanitized() {
        FfData evil = new FfData().putString("mode", "teleport").putLong("batch", 1_000_000).putString("extra", "x");
        c.install(LocalFace.LEFT, FieldframeRegistry.module(BuiltinModules.ITEM_PORT), evil);
        FfData stored = c.module(LocalFace.LEFT).config();
        assertEquals("passive_both", stored.getString("mode", ""));
        assertEquals(64, stored.getLong("batch", 0));
        assertFalse(stored.has("extra"));
        assertFalse(c.setBinding(LocalFace.LEFT, "lane", 7).ok());
        assertFalse(c.setBinding(LocalFace.LEFT, "nonexistent", 0).ok());
        assertFalse(c.setBinding(LocalFace.FRONT, "lane", 0).ok());
    }

    @Test
    void faceRestrictionsAreEnforced() {
        assertFalse(c.install(LocalFace.FRONT, FieldframeRegistry.module(BuiltinModules.SOLAR), null).ok());
        assertTrue(c.install(LocalFace.TOP, FieldframeRegistry.module(BuiltinModules.SOLAR), null).ok());
        assertFalse(c.install(LocalFace.TOP, FieldframeRegistry.module(BuiltinModules.HEATER), null).ok());
    }

    @Test
    void solarRequiresSkyAndDay() {
        c.install(LocalFace.TOP, FieldframeRegistry.module(BuiltinModules.SOLAR), null);
        TestSupport.run(c, w, 10);
        assertEquals("NO_SKY", c.module(LocalFace.TOP).status().code());
        w.sky.add(LocalFace.TOP);
        TestSupport.run(c, w, 200);
        assertTrue(c.energy().stored() > 0);
        w.day = false;
        long before = c.energy().stored();
        TestSupport.run(c, w, 200);
        assertTrue(c.energy().stored() - before <= 8 * 100);
    }

    @Test
    void scriptStyleCancellationKeepsResources() {
        Object owner = new Object();
        FieldframeEvents.register(FieldframeEvents.OperationStarting.class, e -> {
            if (e.recipeId().equals(FfId.ff("smelt_iron"))) {
                e.cancel("pack_rule");
            }
        }, owner);
        try {
            c.install(LocalFace.TOP, FieldframeRegistry.module(BuiltinModules.HEATER), null);
            c.energy().receive(1000, false);
            c.lane(0).insert(IRON_ORE, 2, false);
            TestSupport.run(c, w, 50);
            assertEquals("CANCELLED_BY_RULE", c.module(LocalFace.TOP).status().code());
            assertEquals(2, c.lane(0).countOf(IRON_ORE));
            assertEquals(1000, c.energy().stored());
        } finally {
            assertEquals(1, FieldframeEvents.unregisterOwner(owner));
        }
    }

    @Test
    void listenerExceptionCancelsPreEventSafely() {
        Object owner = new Object();
        FieldframeEvents.setErrorSink(s -> { });
        FieldframeEvents.register(FieldframeEvents.OperationStarting.class, e -> {
            throw new IllegalStateException("script bug");
        }, owner);
        try {
            c.install(LocalFace.TOP, FieldframeRegistry.module(BuiltinModules.HEATER), null);
            c.energy().receive(1000, false);
            c.lane(0).insert(IRON_ORE, 1, false);
            TestSupport.run(c, w, 20);
            assertEquals(1, c.lane(0).countOf(IRON_ORE));
        } finally {
            FieldframeEvents.unregisterOwner(owner);
        }
    }

    @Test
    void tuningOverlayIsValidatedAtomically() {
        List<String> errors = FieldframeRegistry.applyData(List.of(
                new FieldframeRegistry.Override(BuiltinModules.FUEL_ENGINE, "rf_per_tick", 80, "test"),
                new FieldframeRegistry.Override(BuiltinModules.FUEL_ENGINE, "rf_per_tick", 1e9, "test")), Set.of());
        assertEquals(1, errors.size());
        assertEquals(40.0, FieldframeRegistry.tunable(FieldframeRegistry.module(BuiltinModules.FUEL_ENGINE), "rf_per_tick"), "batch rejected as a whole");
        assertTrue(FieldframeRegistry.applyData(List.of(
                new FieldframeRegistry.Override(BuiltinModules.FUEL_ENGINE, "rf_per_tick", 80, "test")), Set.of()).isEmpty());
        assertEquals(80.0, FieldframeRegistry.tunable(FieldframeRegistry.module(BuiltinModules.FUEL_ENGINE), "rf_per_tick"));
    }

    @Test
    void disabledModuleStaysInstalledButIdle() {
        c.install(LocalFace.TOP, FieldframeRegistry.module(BuiltinModules.HEATER), null);
        FieldframeRegistry.applyData(List.of(), Set.of(BuiltinModules.HEATER));
        c.energy().receive(1000, false);
        c.lane(0).insert(IRON_ORE, 1, false);
        TestSupport.run(c, w, 20);
        assertEquals("MODULE_DISABLED", c.module(LocalFace.TOP).status().code());
        assertEquals(1, c.lane(0).countOf(IRON_ORE));
        assertNotNull(c.module(LocalFace.TOP));
        FieldframeRegistry.applyData(List.of(), Set.of());
    }

    @Test
    void neighborUnloadedIsReported() {
        c.install(LocalFace.LEFT, FieldframeRegistry.module(BuiltinModules.ITEM_PORT), cfg("item_port", "mode", "import"));
        w.unloaded.add(LocalFace.LEFT);
        TestSupport.run(c, w, 3);
        assertEquals("NEIGHBOR_UNLOADED", c.module(LocalFace.LEFT).status().code());
    }

    @Test
    void misbehavingNeighborCannotMintItems() {
        // A handler that claims to accept/extract more than requested.
        ItemLane real = new ItemLane("chest", 27);
        real.insert(IRON_ORE, 64, false);
        ItemStore liar = new ItemStore() {
            public int slots() { return real.slots(); }
            public dev.fieldframe.api.resource.ItemVariant variant(int s) { return real.variant(s); }
            public int count(int s) { return real.count(s); }
            public int insert(dev.fieldframe.api.resource.ItemVariant v, int a, boolean sim) { real.insert(v, a, sim); return a * 10; }
            public int extractSlot(int s, int a, boolean sim) { return real.extractSlot(s, a, sim) * 10; }
        };
        w.items.put(LocalFace.LEFT, liar);
        c.install(LocalFace.LEFT, FieldframeRegistry.module(BuiltinModules.ITEM_PORT), cfg("item_port", "mode", "import", "batch", 8L));
        TestSupport.run(c, w, 4);
        long total = real.countOf(IRON_ORE) + c.lane(0).countOf(IRON_ORE);
        assertEquals(64, total);
    }

    @Test
    void transactionSnapshotRollsBackContentsAndBudget() {
        c.install(LocalFace.LEFT, FieldframeRegistry.module(BuiltinModules.ITEM_PORT), null);
        c.install(LocalFace.RIGHT, FieldframeRegistry.module(BuiltinModules.POWER_TERMINAL), null);
        w.time = 3;
        Object snap = c.snapshotResources();
        assertEquals(10, c.exposedItems(WorldDir.WEST).insert(IRON_ORE, 10, false));
        assertEquals(100, c.exposedEnergy(WorldDir.EAST).receive(100, false));
        c.restoreResources(snap);
        assertEquals(0, c.lane(0).totalCount());
        assertEquals(0, c.energy().stored());
        assertEquals(c.spec().itemsPerTick(), c.exposedItems(WorldDir.WEST).insert(IRON_ORE, 64, true), "budget restored too");
    }

    @Test
    void processorStageIsVisible() {
        c.install(LocalFace.TOP, FieldframeRegistry.module(BuiltinModules.HEATER), null);
        c.energy().receive(16_000, false);
        c.lane(0).insert(IRON_ORE, 1, false);
        TestSupport.run(c, w, 2);
        assertEquals(ProcessorBehavior.Stage.RUNNING, ((ProcessorBehavior) c.module(LocalFace.TOP).behavior()).stage());
    }
}
