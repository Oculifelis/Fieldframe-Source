package dev.fieldframe.core;

import dev.fieldframe.api.FieldframeRegistry;
import dev.fieldframe.api.face.LocalFace;
import dev.fieldframe.api.id.FfId;
import dev.fieldframe.api.process.MachineData;
import dev.fieldframe.api.resource.FluidVariant;
import dev.fieldframe.api.resource.ItemVariant;
import dev.fieldframe.api.world.WorkTarget;
import dev.fieldframe.api.world.WorldPos;
import dev.fieldframe.core.chassis.ChassisCore;
import dev.fieldframe.core.chassis.SimulatedActions;
import dev.fieldframe.core.chassis.SimulatedWorld;
import dev.fieldframe.core.material.Electrodes;
import dev.fieldframe.core.modules.power.BurstPower;
import dev.fieldframe.core.modules.power.GalvanicCell;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Storm Coil, Blast Damper (and its chamber) and the Galvanic Cell. */
class BurstAndCellTest {
    static final ItemVariant ZINC = ItemVariant.Simple.of("metallurgy:zinc_ingot");
    static final ItemVariant COPPER = ItemVariant.Simple.of("minecraft:copper_ingot");
    static final ItemVariant TNT = ItemVariant.Simple.of("minecraft:tnt");
    static final FluidVariant WATER = FluidVariant.Simple.of("minecraft:water");
    SimulatedWorld w;
    ChassisCore c;

    @BeforeEach
    void setUp() {
        TestSupport.freshRegistry();
        BurstPower.registry().clear();
        w = TestSupport.world();
        c = TestSupport.chassis(w);
        w.blockTags.put(BurstPower.LIGHTNING_RODS, Set.of(FfId.parse("minecraft:lightning_rod")));
        w.itemTags.put(BurstPower.EXPLOSIVES, Set.of(FfId.parse("minecraft:tnt")));
        w.itemTags.put(FfId.parse("c:ingots/zinc"), Set.of(FfId.parse("metallurgy:zinc_ingot")));
    }

    @AfterEach
    void tearDown() {
        MachineData.resetContributorsForTests();
        Electrodes.replace(List.of());
    }

    private void install(LocalFace f, FfId id) {
        assertTrue(c.install(f, FieldframeRegistry.module(id), null).ok(), id + " on " + f);
    }

    private long drained(int ticks) {
        long total = 0;
        for (int i = 0; i < ticks; i++) {
            w.time++;
            c.tick(w);
            total += c.energy().extract(c.energy().stored(), false);
        }
        return total;
    }

    // ------------------------------------------------------------------ storm coil

    @Test
    void stormCoilCatchesLightningOnARodAndReleasesItSlowly() {
        install(LocalFace.TOP, BurstPower.STORM_COIL);
        w.actions.put(WorkTarget.adjacent(LocalFace.TOP), SimulatedActions.Block.of("minecraft:lightning_rod", null));
        TestSupport.run(c, w, 2);
        assertEquals("WAITING_FOR_LIGHTNING", c.module(LocalFace.TOP).status().code());
        w.redstone.put(LocalFace.TOP, 15);
        assertEquals(512 * 10, drained(10), "releases 512 RF/t");
        w.redstone.put(LocalFace.TOP, 0);
        assertEquals("RELEASING_CHARGE", c.module(LocalFace.TOP).status().code());
        assertEquals(200_000 - 512 * 10, drained(1_000), "one strike is 200,000 RF in all");
        assertEquals("WAITING_FOR_LIGHTNING", c.module(LocalFace.TOP).status().code());
    }

    @Test
    void stormCoilIgnoresRedstoneWithoutARod() {
        install(LocalFace.TOP, BurstPower.STORM_COIL);
        w.redstone.put(LocalFace.TOP, 15);
        assertEquals(0, drained(50), "a lever is not lightning");
        assertEquals("NEEDS_LIGHTNING_ROD", c.module(LocalFace.TOP).status().code());
    }

    @Test
    void stormCoilCapacitorOverflowIsLost() {
        install(LocalFace.TOP, BurstPower.STORM_COIL);
        w.actions.put(WorkTarget.adjacent(LocalFace.TOP), SimulatedActions.Block.of("minecraft:lightning_rod", null));
        c.energy().receive(c.energy().capacity(), false);
        for (int strike = 0; strike < 3; strike++) {
            w.redstone.put(LocalFace.TOP, 15);
            TestSupport.run(c, w, 2);
            w.redstone.put(LocalFace.TOP, 0);
            TestSupport.run(c, w, 2);
        }
        assertEquals("BUFFER_FULL", c.module(LocalFace.TOP).status().code());
        assertEquals(400_000 + 16_000, drained(1_000), "three strikes, but the capacitor holds 400,000 (plus the full buffer)");
    }

    // ------------------------------------------------------------------ blast damper

    @Test
    void blastDamperIsFoundForNearbyExplosionsAndStoresTheirEnergy() {
        install(LocalFace.FRONT, BurstPower.BLAST_DAMPER);
        w.pos = new WorldPos("minecraft:overworld", 10, 64, 10);
        TestSupport.run(c, w, 2);
        WorldPos found = BurstPower.registry().nearest("minecraft:overworld", 13.5, 64.5, 10.5, w.time, 40);
        assertNotNull(found, "within 6 blocks");
        assertNull(BurstPower.registry().nearest("minecraft:overworld", 30, 64, 10, w.time, 40), "too far");
        assertNull(BurstPower.registry().nearest("minecraft:the_nether", 13.5, 64.5, 10.5, w.time, 40), "other dimension");
        ((BurstPower.BlastDamper) c.module(LocalFace.FRONT).behavior()).queue(3f);
        assertEquals(31_500, drained(200), "a creeper: 3,500 x 3 x 3");
        TestSupport.run(c, w, 100);
        assertNull(BurstPower.registry().nearest("minecraft:overworld", 13.5, 64.5, 10.5, w.time + 100, 40), "claims expire");
    }

    @Test
    void blastChamberNeedsAReinforcedChassis() {
        install(LocalFace.FRONT, BurstPower.BLAST_DAMPER);
        c.lane(2).insert(TNT, 2, false);
        TestSupport.run(c, w, 5);
        assertEquals("CHASSIS_TOO_WEAK", c.module(LocalFace.FRONT).status().code());
        assertEquals(2, c.lane(2).countOf(TNT));
        assertTrue(c.upgradeTier(FieldframeRegistry.tierAt(1)).ok());
        assertTrue(c.upgradeTier(FieldframeRegistry.tierAt(2)).ok());
        long rf = drained(150);
        assertTrue(c.lane(2).countOf(TNT) <= 1, "detonated TNT");
        assertTrue(rf > 0, "released energy");
        assertEquals(56_000 * 2, rf + drained(1_000), "two TNT: 3,500 x 4 x 4 each");
    }

    // ------------------------------------------------------------------ galvanic cell

    private void electrodes() {
        Electrodes.load(Map.of("fieldframe:default",
                "{\"electrodes\": [{\"item\": \"#c:ingots/zinc\", \"volts\": -0.76}, {\"item\": \"minecraft:copper_ingot\", \"volts\": 0.34}]}"),
                List.of());
    }

    @Test
    void galvanicCellRunsOnZincAgainstCopper() {
        electrodes();
        install(LocalFace.LEFT, GalvanicCell.ID);
        c.lane(0).insert(ZINC, 2, false);
        c.lane(2).insert(COPPER, 1, false);
        c.tank(0).fill(WATER, 4_000, false);
        assertEquals(28 * 100, drained(100), "1.10 V x 25 = 27.5, rounded");
        assertEquals("CELL_RUNNING", c.module(LocalFace.LEFT).status().code());
        assertEquals(1, c.lane(0).countOf(ZINC), "one anode in use");
        assertEquals(1, c.lane(2).countOf(COPPER), "the cathode stays");
        assertEquals(3_900, c.tank(0).amountOf(WATER), "1 mB of electrolyte a tick");
        drained(1_200);
        assertEquals(0, c.lane(0).countOf(ZINC), "the second anode after 1,200 ticks");
    }

    @Test
    void galvanicCellNeedsTwoDifferentMetalsAndElectrolyte() {
        electrodes();
        install(LocalFace.LEFT, GalvanicCell.ID);
        c.lane(0).insert(COPPER, 2, false);
        c.lane(2).insert(COPPER, 1, false);
        TestSupport.run(c, w, 2);
        assertEquals("NO_ELECTROLYTE", c.module(LocalFace.LEFT).status().code());
        c.tank(0).fill(WATER, 1_000, false);
        TestSupport.run(c, w, 50);
        assertEquals("NO_ANODE", c.module(LocalFace.LEFT).status().code(), "copper against copper makes nothing");
        assertEquals(0, drained(10));
        assertEquals(2, c.lane(0).countOf(COPPER), "nothing used up");
    }
}
