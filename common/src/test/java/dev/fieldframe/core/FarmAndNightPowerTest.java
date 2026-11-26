package dev.fieldframe.core;

import dev.fieldframe.api.FieldframeRegistry;
import dev.fieldframe.api.face.LocalFace;
import dev.fieldframe.api.id.FfId;
import dev.fieldframe.api.process.MachineData;
import dev.fieldframe.api.process.ProcessFamilies;
import dev.fieldframe.api.process.ProcessRecipe;
import dev.fieldframe.api.resource.FluidVariant;
import dev.fieldframe.api.resource.ItemFilter;
import dev.fieldframe.api.resource.ItemVariant;
import dev.fieldframe.api.world.WorkTarget;
import dev.fieldframe.api.world.WorldActions;
import dev.fieldframe.api.world.WorldPos;
import dev.fieldframe.core.chassis.ChassisCore;
import dev.fieldframe.core.chassis.SimulatedActions;
import dev.fieldframe.core.chassis.SimulatedWorld;
import dev.fieldframe.core.material.TreadmillMobs;
import dev.fieldframe.core.modules.BuiltinModules;
import dev.fieldframe.core.modules.power.GeothermalProbe;
import dev.fieldframe.core.modules.power.LunarPrism;
import dev.fieldframe.core.modules.power.Treadmill;
import dev.fieldframe.core.world.SharedSources;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Lunar Prism, Geothermal Probe, Treadmill and Bio-Digester. */
class FarmAndNightPowerTest {
    SimulatedWorld w;
    ChassisCore c;

    @BeforeEach
    void setUp() {
        TestSupport.freshRegistry();
        SharedSources.clear();
        w = TestSupport.world();
        c = TestSupport.chassis(w);
    }

    @AfterEach
    void tearDown() {
        MachineData.resetContributorsForTests();
        TreadmillMobs.replace(List.of());
    }

    private void install(LocalFace f, FfId id) {
        assertTrue(c.install(f, FieldframeRegistry.module(id), null).ok(), id + " on " + f);
    }

    /** RF made over {@code ticks}, starting from an empty buffer (a full one would stop the generators). */
    private long rfAfter(int ticks) {
        c.energy().extract(c.energy().stored(), false);
        long before = c.energy().stored();
        TestSupport.run(c, w, ticks);
        return c.energy().stored() - before;
    }

    private String status(LocalFace f) {
        return c.module(f).status().code();
    }

    // ------------------------------------------------------------------ Lunar Prism

    @Test
    void lunarPrismFollowsTheMoonAtNight() {
        install(LocalFace.TOP, LunarPrism.ID);
        w.sky.add(LocalFace.TOP);
        TestSupport.run(c, w, 2);
        assertEquals("LUNAR_DAY", status(LocalFace.TOP), "nothing by day");
        w.day = false;
        w.moon = 0;
        TestSupport.run(c, w, 101);
        assertEquals(16 * 50, rfAfter(50), "full moon: 16 RF/t");
        w.moon = 2;
        TestSupport.run(c, w, 100);
        assertEquals(8 * 50, rfAfter(50), "quarter moon: 8 RF/t");
        w.rain = true;
        TestSupport.run(c, w, 100);
        assertEquals(4 * 50, rfAfter(50), "rain halves it");
        w.rain = false;
        w.moon = 4;
        TestSupport.run(c, w, 100);
        assertEquals("LUNAR_NEW_MOON", status(LocalFace.TOP));
        assertEquals(0, rfAfter(50));
    }

    @Test
    void lunarPrismNeedsTheSky() {
        install(LocalFace.TOP, LunarPrism.ID);
        w.day = false;
        TestSupport.run(c, w, 2);
        assertEquals("NO_SKY", status(LocalFace.TOP));
        assertEquals(0.5, LunarPrism.phaseFactor(6));
        assertEquals(0.0, LunarPrism.phaseFactor(-1));
    }

    // ------------------------------------------------------------------ Geothermal Probe

    private void hotBelow(SimulatedActions a, int count, int kelvin) {
        int n = 0;
        for (int r = -2; r <= 2 && n < count; r++) {
            for (int u = -2; u <= 2 && n < count; u++) {
                a.targetTemperatures.put(SimulatedActions.key(new WorkTarget(LocalFace.BOTTOM, 1, r, u)), kelvin);
                n++;
            }
        }
    }

    @Test
    void geothermalDependsOnDepthAndHeatBelow() {
        install(LocalFace.BOTTOM, GeothermalProbe.ID);
        w.pos = new WorldPos("minecraft:overworld", 0, 80, 0);
        TestSupport.run(c, w, 2);
        assertEquals("TOO_HIGH_FOR_HEAT", status(LocalFace.BOTTOM));
        w.pos = new WorldPos("minecraft:overworld", 0, -20, 0);
        TestSupport.run(c, w, 101);
        assertEquals(15 * 50, rfAfter(50), "plain deep ground: a quarter of 60");
        hotBelow(w.actions, 8, 1300);
        TestSupport.run(c, w, 100);
        assertEquals(60 * 50, rfAfter(50), "eight lava blocks below: full 60");
        w.pos = new WorldPos("minecraft:overworld", 0, 20, 0);
        TestSupport.run(c, w, 100);
        assertEquals(30 * 50, rfAfter(50), "between y 0 and 40: half");
        w.pos = new WorldPos("minecraft:the_nether", 0, 100, 0);
        TestSupport.run(c, w, 100);
        assertEquals(60 * 50, rfAfter(50), "the Nether counts as deep at any height (nether_factor 1, balance pass)");
    }

    @Test
    void geothermalProbesShareAnArea() {
        install(LocalFace.BOTTOM, GeothermalProbe.ID);
        w.pos = new WorldPos("minecraft:overworld", 0, -20, 0);
        SimulatedWorld w2 = TestSupport.world();
        w2.pos = new WorldPos("minecraft:overworld", 20, -20, 0);
        w2.time = w.time;
        ChassisCore c2 = TestSupport.chassis(w2);
        assertTrue(c2.install(LocalFace.BOTTOM, FieldframeRegistry.module(GeothermalProbe.ID), null).ok());
        for (int i = 0; i < 101; i++) {
            w.time++;
            c.tick(w);
            w2.time++;
            c2.tick(w2);
        }
        assertEquals("GROUND_HEAT_SHARED", c.module(LocalFace.BOTTOM).status().code());
        assertEquals("GROUND_HEAT_SHARED", c2.module(LocalFace.BOTTOM).status().code());
    }

    // ------------------------------------------------------------------ Treadmill

    private static final ItemVariant WHEAT = ItemVariant.Simple.of("minecraft:wheat");

    private void cowTable() {
        TreadmillMobs.load(Map.of("fieldframe:fieldframe/treadmill/vanilla.json", """
                {"mobs": [
                  {"entity": "minecraft:cow", "rf_per_tick": 6, "food": "minecraft:wheat"},
                  {"entity": "minecraft:horse", "rf_per_speed": 40, "food": "minecraft:wheat"}
                ]}"""), List.of());
    }

    @Test
    void treadmillRunsWhileFedThenSlowsAndStops() {
        cowTable();
        install(LocalFace.LEFT, Treadmill.ID);
        TestSupport.run(c, w, 2);
        assertEquals("TREADMILL_EMPTY", status(LocalFace.LEFT));
        w.actions.animals.put(LocalFace.LEFT, new WorldActions.WalkingAnimal(FfId.parse("minecraft:cow"), false, 0.2));
        TestSupport.run(c, w, 10);
        assertEquals("TREADMILL_HUNGRY", status(LocalFace.LEFT), "nothing to eat");
        c.lane(2).insert(WHEAT, 1, false);
        TestSupport.run(c, w, 10);
        assertEquals(6 * 100, rfAfter(100), "a fed cow: 6 RF/t");
        assertEquals(0, c.lane(2).countOf(WHEAT), "it ate the wheat");
        TestSupport.run(c, w, 1_200);
        assertEquals("TREADMILL_SLOWING", status(LocalFace.LEFT));
        long slowing = rfAfter(100);
        assertTrue(slowing > 0 && slowing < 6 * 100, "slowing down: " + slowing);
        TestSupport.run(c, w, 2_400);
        assertEquals("TREADMILL_HUNGRY", status(LocalFace.LEFT));
        assertEquals(0, rfAfter(50), "stopped");
    }

    @Test
    void treadmillHorsesScaleWithSpeedAndBabiesDoNotCount() {
        cowTable();
        install(LocalFace.LEFT, Treadmill.ID);
        c.lane(2).insert(WHEAT, 4, false);
        w.actions.animals.put(LocalFace.LEFT, new WorldActions.WalkingAnimal(FfId.parse("minecraft:horse"), false, 0.3));
        TestSupport.run(c, w, 10);
        assertEquals(12 * 50, rfAfter(50), "40 x 0.3");
        w.actions.animals.put(LocalFace.LEFT, new WorldActions.WalkingAnimal(FfId.parse("minecraft:horse"), true, 0.3));
        TestSupport.run(c, w, 10);
        assertEquals("TREADMILL_BABY", status(LocalFace.LEFT));
        w.actions.animals.put(LocalFace.LEFT, new WorldActions.WalkingAnimal(FfId.parse("minecraft:zombie"), false, 0.2));
        TestSupport.run(c, w, 10);
        assertEquals("TREADMILL_UNKNOWN", status(LocalFace.LEFT));
    }

    @Test
    void treadmillTableRejectsBadEntriesAndTakesContributors() {
        List<String> errors = TreadmillMobs.load(Map.of("a:fieldframe/treadmill/bad.json", "{\"entity\": \"minecraft:cow\", \"food\": \"minecraft:wheat\"}"),
                List.of(s -> s.mob("minecraft:llama", 6, 0, "#c:crops/wheat")));
        assertEquals(1, errors.size(), "no RF given: " + errors);
        assertEquals(6, TreadmillMobs.of(FfId.parse("minecraft:llama")).rf(0.2));
    }

    // ------------------------------------------------------------------ Bio-Digester

    @Test
    void bioDigesterTurnsSeedsAndWaterIntoBiogasAndCompost() {
        FluidVariant water = FluidVariant.Simple.of("minecraft:water");
        FluidVariant biogas = FluidVariant.Simple.of("fieldframe:biogas");
        ItemVariant seeds = ItemVariant.Simple.of("minecraft:wheat_seeds");
        ItemVariant compost = ItemVariant.Simple.of("fieldframe:compost");
        w.addRecipe(new ProcessRecipe(FfId.ff("digestion/seeds"), ProcessFamilies.DIGESTION,
                List.of(new ProcessRecipe.ItemInput(ItemFilter.allowIds("minecraft:wheat_seeds"), 4, true, "seeds")),
                new ProcessRecipe.FluidInput(dev.fieldframe.api.resource.FluidFilter.allowIds("minecraft:water"), 250, "water"), List.of(new ProcessRecipe.ItemResult(compost, 1, 1.0)),
                new ProcessRecipe.FluidResult(biogas, 100), 100, 10));
        install(LocalFace.LEFT, BuiltinModules.BIO_DIGESTER);
        c.lane(0).insert(seeds, 8, false);
        c.tank(0).fill(water, 1_000, false);
        c.energy().receive(10_000, false);
        TestSupport.run(c, w, 250);
        assertEquals(200, c.tank(1).amountOf(biogas), "biogas in the Green tank");
        assertEquals(2, c.lane(1).countOf(compost), "compost in Green storage");
        assertEquals(500, c.tank(0).amountOf(water));
    }
}
