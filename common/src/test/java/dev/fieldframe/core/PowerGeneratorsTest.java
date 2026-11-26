package dev.fieldframe.core;

import dev.fieldframe.api.FieldframeRegistry;
import dev.fieldframe.api.face.LocalFace;
import dev.fieldframe.api.id.FfId;
import dev.fieldframe.api.process.MachineData;
import dev.fieldframe.api.resource.FluidVariant;
import dev.fieldframe.api.world.WorkTarget;
import dev.fieldframe.api.world.WorldPos;
import dev.fieldframe.core.chassis.ChassisCore;
import dev.fieldframe.core.chassis.SimulatedActions;
import dev.fieldframe.core.chassis.SimulatedWorld;
import dev.fieldframe.core.material.Temperatures;
import dev.fieldframe.core.modules.BuiltinModules;
import dev.fieldframe.core.modules.power.HeatPower;
import dev.fieldframe.core.modules.power.WindRotor;
import dev.fieldframe.core.world.SharedSources;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static dev.fieldframe.core.TestSupport.COAL;
import static dev.fieldframe.core.TestSupport.IRON_ORE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Temperature table, Thermocouple, Steam Loop and Wind Rotor. */
class PowerGeneratorsTest {
    static final FluidVariant WATER = FluidVariant.Simple.of("minecraft:water");
    static final FluidVariant STEAM = FluidVariant.Simple.of("fieldframe:steam");
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
        Temperatures.replace(List.of());
    }

    private void install(LocalFace f, FfId id) {
        assertTrue(c.install(f, FieldframeRegistry.module(id), null).ok(), id + " on " + f);
    }

    /** Runs with something drawing all RF away each tick (a full buffer would stop the generators); returns the total. */
    private long rfDrained(int ticks) {
        long total = 0;
        for (int i = 0; i < ticks; i++) {
            w.time++;
            c.tick(w);
            total += c.energy().extract(c.energy().stored(), false);
        }
        return total;
    }

    private long rfAfter(int ticks) {
        long before = c.energy().stored();
        TestSupport.run(c, w, ticks);
        return c.energy().stored() - before;
    }

    // ------------------------------------------------------------------ temperature table

    @Test
    void temperatureTableLayersAndResolves() {
        String json = """
                {"temperatures": [
                  {"block": "minecraft:magma_block", "kelvin": 1000},
                  {"block": "minecraft:furnace", "lit": true, "kelvin": 700},
                  {"block": "#minecraft:ice_like", "kelvin": 250},
                  {"fluid": "minecraft:water", "kelvin": 285}
                ]}""";
        MachineData.addTemperatureContributor(t -> {
            t.block("minecraft:blue_ice", 180);
            t.remove("minecraft:magma_block");
        });
        assertTrue(Temperatures.load(Map.of("fieldframe:default", json, "pack:bad", "{\"block\": \"x:y\", \"kelvin\": 0}")).size() == 1,
                "the bad file is reported");
        w.blockTags.put(FfId.parse("minecraft:ice_like"), Set.of(FfId.parse("minecraft:packed_ice")));
        int amb = 290;
        assertEquals(amb, Temperatures.resolve(FfId.parse("minecraft:magma_block"), false, null, -1, amb, w.tags()), "removed by the contributor");
        assertEquals(700, Temperatures.resolve(FfId.parse("minecraft:furnace"), true, null, -1, amb, w.tags()), "lit furnace");
        assertEquals(amb, Temperatures.resolve(FfId.parse("minecraft:furnace"), false, null, -1, amb, w.tags()), "unlit furnace is ambient");
        assertEquals(250, Temperatures.resolve(FfId.parse("minecraft:packed_ice"), false, null, -1, amb, w.tags()), "by tag");
        assertEquals(180, Temperatures.resolve(FfId.parse("minecraft:blue_ice"), false, null, -1, amb, w.tags()), "added by the contributor");
        assertEquals(285, Temperatures.resolve(FfId.parse("minecraft:water"), false, WATER, 300, amb, w.tags()), "the table beats the fluid's own");
        assertEquals(1300, Temperatures.resolve(FfId.parse("minecraft:lava"), false, FluidVariant.Simple.of("minecraft:lava"), 1300, amb, w.tags()),
                "a fluid not in the table uses its own temperature");
        assertEquals(273, Temperatures.ambient(0f));
        assertEquals(289, Temperatures.ambient(0.8f));
    }

    // ------------------------------------------------------------------ thermocouple

    @Test
    void thermocoupleMakesPowerFromLavaAgainstBlueIce() {
        install(LocalFace.LEFT, HeatPower.HOT_JUNCTION);
        install(LocalFace.RIGHT, HeatPower.COLD_JUNCTION);
        w.actions.temperatures.put(LocalFace.LEFT, 1300);
        w.actions.temperatures.put(LocalFace.RIGHT, 180);
        TestSupport.run(c, w, 1);
        assertEquals(22 * 100, rfAfter(100), "0.02 RF/t per K x 1120 K = 22 RF/t");
        assertEquals("THERMO_RUNNING", c.module(LocalFace.LEFT).status().code());
        w.actions.temperatures.put(LocalFace.RIGHT, 285);
        TestSupport.run(c, w, 41);
        assertEquals(20 * 100, rfAfter(100), "water instead of blue ice: 1015 K, 20 RF/t");
    }

    @Test
    void thermocoupleNeedsAColdJunctionAndADifference() {
        install(LocalFace.LEFT, HeatPower.HOT_JUNCTION);
        w.actions.temperatures.put(LocalFace.LEFT, 1300);
        TestSupport.run(c, w, 5);
        assertEquals("NEEDS_COLD_JUNCTION", c.module(LocalFace.LEFT).status().code());
        install(LocalFace.RIGHT, HeatPower.COLD_JUNCTION);
        w.actions.temperatures.put(LocalFace.LEFT, 290);
        TestSupport.run(c, w, 60);
        assertEquals("NO_TEMPERATURE_DIFFERENCE", c.module(LocalFace.LEFT).status().code());
        assertEquals(0, rfAfter(100));
    }

    @Test
    void thermoelementMetalsRaiseTheJunctionGrade() {
        dev.fieldframe.core.material.Electrodes.replace(List.of(
                new dev.fieldframe.core.material.Electrodes.Entry("minecraft:copper_ingot", 0.34),
                new dev.fieldframe.core.material.Electrodes.Entry("minecraft:iron_ingot", -0.44),
                new dev.fieldframe.core.material.Electrodes.Entry("minecraft:gold_ingot", 1.50),
                new dev.fieldframe.core.material.Electrodes.Entry("fieldframe:zinc_test", -0.76)));
        try {
            install(LocalFace.LEFT, HeatPower.HOT_JUNCTION);
            install(LocalFace.RIGHT, HeatPower.COLD_JUNCTION);
            w.actions.temperatures.put(LocalFace.LEFT, 1300);
            w.actions.temperatures.put(LocalFace.RIGHT, 180);
            TestSupport.run(c, w, 2);
            long plain = rfDrained(40);
            c.lane(2).insert(dev.fieldframe.api.resource.ItemVariant.Simple.of("minecraft:gold_ingot"), 1, false);
            c.lane(2).insert(dev.fieldframe.api.resource.ItemVariant.Simple.of("fieldframe:zinc_test"), 1, false);
            TestSupport.run(c, w, 41);
            long graded = rfDrained(40);
            assertTrue(graded > plain * 19 / 10, "zinc and gold nearly double it: " + plain + " -> " + graded);
            assertEquals(2, c.lane(2).totalCount(), "thermoelements are never used up");
            assertEquals(1.0, HeatPower.grade(null, null));
        } finally {
            dev.fieldframe.core.material.Electrodes.replace(List.of());
        }
    }

    @Test
    void workingHeaterGivesCappedWasteHeat() {
        install(LocalFace.LEFT, HeatPower.HOT_JUNCTION);
        install(LocalFace.RIGHT, HeatPower.COLD_JUNCTION);
        install(LocalFace.TOP, BuiltinModules.HEATER);
        w.actions.temperatures.put(LocalFace.RIGHT, 180);
        c.energy().receive(16_000, false);
        c.lane(0).insert(IRON_ORE, 64, false);
        TestSupport.run(c, w, 60);
        assertEquals("THERMO_WASTE_HEAT", c.module(LocalFace.LEFT).status().code());
        assertEquals(5, c.module(LocalFace.LEFT).energyGeneratedLastTick(), "capped at a quarter of the Heater's 20 RF/t");
        assertEquals(20, c.module(LocalFace.TOP).energyUsedLastTick());
    }

    // ------------------------------------------------------------------ steam loop

    @Test
    void boilerTurnsWaterIntoSteamWithCoalAndNeverWastesFuel() {
        install(LocalFace.BOTTOM, HeatPower.BOILER);
        c.lane(2).insert(COAL, 2, false);
        TestSupport.run(c, w, 20);
        assertEquals(2, c.lane(2).countOf(COAL), "no water: the coal stays");
        c.tank(0).fill(WATER, 4_000, false);
        TestSupport.run(c, w, 100);
        assertEquals(1, c.lane(2).countOf(COAL), "one coal burning");
        assertEquals(20 * 100, c.tank(1).amountOf(STEAM), "20 mB of steam a tick");
        assertEquals(4_000 - 2_000, c.tank(0).amountOf(WATER));
    }

    @Test
    void handCrankTurnsOnlyWhileUsedAndNeverFasterThanHeld() {
        install(LocalFace.TOP, dev.fieldframe.core.modules.power.HandCrank.ID);
        c.energy().extract(c.energy().stored(), false);
        TestSupport.run(c, w, 5);
        assertEquals(0, c.energy().stored(), "a still crank makes nothing");
        assertEquals("CRANK_STILL", c.module(LocalFace.TOP).status().code());
        assertTrue(c.playerUse(LocalFace.TOP, false));
        TestSupport.run(c, w, 15);
        assertEquals(10 * 20, c.energy().stored(), "one turn: 10 ticks at 20 RF/t");
        // Spam-clicking queues at most 20 ticks of turning.
        c.energy().extract(c.energy().stored(), false);
        for (int i = 0; i < 8; i++) {
            c.playerUse(LocalFace.TOP, false);
        }
        TestSupport.run(c, w, 40);
        assertEquals(20 * 20, c.energy().stored());
        assertTrue(!c.playerUse(LocalFace.BOTTOM, false), "an empty face takes no use");
    }

    @Test
    void boilerRunsOnLavaWithoutFuel() {
        install(LocalFace.BOTTOM, HeatPower.BOILER);
        w.actions.temperatures.put(LocalFace.BOTTOM, 1300);
        c.tank(0).fill(WATER, 1_000, false);
        TestSupport.run(c, w, 200);
        assertEquals(1_000, c.tank(1).amountOf(STEAM), "heat boils at a quarter of the fuel rate");
        assertEquals("BOILING_ON_HEAT", c.module(LocalFace.BOTTOM).status().code());
    }

    @Test
    void steamLoopSpinsUpAndCondenserReturnsMostOfTheWater() {
        install(LocalFace.BOTTOM, HeatPower.BOILER);
        install(LocalFace.LEFT, HeatPower.STEAM_TURBINE);
        install(LocalFace.RIGHT, HeatPower.CONDENSER);
        w.actions.temperatures.put(LocalFace.RIGHT, 285);
        c.tank(0).fill(WATER, 4_000, false);
        c.lane(2).insert(COAL, 8, false);
        long early = rfDrained(60);
        rfDrained(400);
        long full = rfDrained(100);
        assertTrue(early < 60 * 80 / 2, "spinning up: well below full output at first (" + early + ")");
        assertEquals(80 * 100, full, 400, "full speed: 20 mB/t x 4 RF/mB = 80 RF/t");
        long water = c.tank(0).amountOf(WATER);
        assertTrue(water > 4_000 - 560 * 20 * 0.2, "most water came back (" + water + " mB left)");
        assertEquals("CONDENSING_COLD", c.module(LocalFace.RIGHT).status().code());
    }

    @Test
    void turbineCoastsDownWithoutSteam() {
        install(LocalFace.LEFT, HeatPower.STEAM_TURBINE);
        c.tank(1).fill(STEAM, 4_000, false);
        TestSupport.run(c, w, 100);
        assertEquals(0, c.tank(1).amountOf(STEAM), "40 mB/t used up the tank");
        double spinning = c.module(LocalFace.LEFT).behavior().progress();
        assertEquals(100 / 300.0, spinning, 0.02, "a third of the way to full speed");
        TestSupport.run(c, w, 60);
        double coasting = c.module(LocalFace.LEFT).behavior().progress();
        assertTrue(coasting < spinning && coasting > 0, "slowing down, not stopped (" + coasting + ")");
        assertEquals("TURBINE_COASTING", c.module(LocalFace.LEFT).status().code());
    }

    // ------------------------------------------------------------------ wind

    @Test
    void windRotorDependsOnHeightOpenAirAndWeather() {
        install(LocalFace.LEFT, WindRotor.ID);
        w.pos = new WorldPos("minecraft:overworld", 0, 64, 0);
        TestSupport.run(c, w, 5);
        assertEquals("TOO_LOW_FOR_WIND", c.module(LocalFace.LEFT).status().code());
        w.pos = new WorldPos("minecraft:overworld", 0, 192, 0);
        TestSupport.run(c, w, 101);
        assertEquals(19 * 50, rfAfter(50), "clear sky at full height: 32 x 0.6");
        w.thunder = true;
        TestSupport.run(c, w, 100);
        assertEquals(45 * 50, rfAfter(50), "storm: 32 x 1.4 = 44.8");
    }

    @Test
    void blockedAirAndCrowdedRotorsMakeLess() {
        install(LocalFace.LEFT, WindRotor.ID);
        w.pos = new WorldPos("minecraft:overworld", 0, 192, 0);
        for (int r = -2; r <= 2; r++) {
            for (int u = -2; u <= 2; u++) {
                w.actions.put(new WorkTarget(LocalFace.LEFT, 1, r, u), SimulatedActions.Block.of("minecraft:stone", null));
            }
        }
        TestSupport.run(c, w, 101);
        assertEquals(14 * 50, rfAfter(50), "a quarter of the box is stone: 19.2 x 0.75");
        // A second rotor two blocks away splits the wind.
        SimulatedWorld w2 = TestSupport.world();
        w2.pos = new WorldPos("minecraft:overworld", 2, 192, 0);
        w2.time = w.time;
        ChassisCore c2 = TestSupport.chassis(w2);
        assertTrue(c2.install(LocalFace.LEFT, FieldframeRegistry.module(WindRotor.ID), null).ok());
        for (int i = 0; i < 101; i++) {
            w.time++;
            c.tick(w);
            w2.time++;
            c2.tick(w2);
        }
        assertEquals("WIND_SHARED", c2.module(LocalFace.LEFT).status().code());
        assertEquals("WIND_SHARED", c.module(LocalFace.LEFT).status().code());
    }
}
