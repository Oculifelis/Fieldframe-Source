package dev.fieldframe.core;

import dev.fieldframe.api.FieldframeRegistry;
import dev.fieldframe.api.face.LocalFace;
import dev.fieldframe.api.face.WorldDir;
import dev.fieldframe.api.id.FfId;
import dev.fieldframe.api.resource.EnergyStore;
import dev.fieldframe.api.resource.FluidVariant;
import dev.fieldframe.api.resource.ItemVariant;
import dev.fieldframe.core.chassis.ChassisCore;
import dev.fieldframe.core.chassis.SimulatedWorld;
import dev.fieldframe.core.endertech.EnderTechRules;
import dev.fieldframe.core.endertech.EnderTechRules.Tier;
import dev.fieldframe.core.endertech.ExchangeJob;
import dev.fieldframe.core.endertech.PadLogic;
import dev.fieldframe.core.endertech.TankCore;
import dev.fieldframe.core.endertech.TankStructure;
import dev.fieldframe.core.endertech.TankStructure.Part;
import dev.fieldframe.core.flora.ArmorInfuserBehavior;
import dev.fieldframe.core.flora.Flora;
import dev.fieldframe.core.flora.FloraEffects;
import dev.fieldframe.core.modules.BuiltinModules;
import dev.fieldframe.core.storage.EnergyBuffer;
import dev.fieldframe.api.resource.VariantCodec;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EnderTechFloraTest {

    // ------------------------------------------------------------------ Exchanger

    @Test
    void exchangeCostMatchesOriginalFormulaAndClamps() {
        // 2048 + 128*1 + 64*1 = 2240 for ring 1, hardness below 1 counts as 1
        assertEquals(2240, EnderTechRules.exchangeCost(1, 0.5f));
        // stone hardness 1.5 -> (int) 1
        assertEquals(2048 + 128 * 3 + 64, EnderTechRules.exchangeCost(3, 1.5f));
        // obsidian 50 on ring 8: 2048 + 1024 + 3200 = 6272
        assertEquals(6272, EnderTechRules.exchangeCost(8, 50f));
        assertEquals(EnderTechRules.EXCHANGE_MAX_COST, EnderTechRules.exchangeCost(100, 50f));
    }

    @Test
    void ringsCoverTheFacePlaneExactlyOnce() {
        Set<String> seen = new HashSet<>();
        int total = 0;
        for (int r = 1; r <= 3; r++) {
            for (int[] o : EnderTechRules.ring(WorldDir.UP, r)) {
                assertEquals(0, o[1], "plane of an UP face is horizontal");
                assertTrue(seen.add(o[0] + "," + o[2]));
                total++;
            }
        }
        assertEquals(25, total); // 5x5 square
        for (int[] o : EnderTechRules.ring(WorldDir.EAST, 2)) {
            assertEquals(0, o[0]);
        }
    }

    @Test
    void exchangeJobAdvancesOneRingPerIntervalAndStops() {
        ExchangeJob job = new ExchangeJob(WorldDir.UP, 3);
        List<Integer> rings = new ArrayList<>();
        int ticks = 0;
        while (!job.finished() && ticks < 100) {
            job.tick((dx, dy, dz, ring) -> {
                rings.add(ring);
                return ExchangeJob.Swap.DONE;
            });
            ticks++;
        }
        assertEquals(1 + 8 + 16, job.swapped());
        assertEquals(1 + 2 * EnderTechRules.EXCHANGER_TICKS_PER_RING, ticks);
        ExchangeJob stopping = new ExchangeJob(WorldDir.UP, 8);
        stopping.tick((dx, dy, dz, r) -> ExchangeJob.Swap.STOP);
        assertTrue(stopping.finished());
        assertEquals(EnderTechRules.EXCHANGER_MAX_RADIUS, EnderTechRules.clampRadius(99));
    }

    // ------------------------------------------------------------------ pads

    @Test
    void chargePadSplitsEvenlyAndConservesEnergy() {
        EnergyBuffer pad = new EnergyBuffer(Tier.RESONANT.capacity);
        pad.receive(100_000, false);
        EnergyBuffer a = new EnergyBuffer(1_000_000);
        EnergyBuffer b = new EnergyBuffer(3_000); // nearly full item takes less
        b.receive(0, false);
        long sent = PadLogic.charge(Tier.RESONANT, pad, List.of(new PadLogic.Receiver(List.of(a)), new PadLogic.Receiver(List.of(b))));
        assertEquals(10_000 + 3_000, sent);
        assertEquals(10_000, a.stored());
        assertEquals(3_000, b.stored());
        assertEquals(100_000 - sent, pad.stored());
    }

    @Test
    void healthPadPaysPerHalfHeartAndRespectsInterval() {
        EnergyBuffer pad = new EnergyBuffer(Tier.REDSTONE.capacity);
        pad.receive(Tier.REDSTONE.capacity, false);
        int[] healed = {0};
        PadLogic.Patient p = new PadLogic.Patient() {
            public boolean hurt() {
                return true;
            }

            public void healHalfHeart() {
                healed[0]++;
            }
        };
        int counter = 0;
        for (int t = 0; t < 200; t++) {
            counter = PadLogic.heal(Tier.REDSTONE, pad, List.of(p), counter, () -> 0);
        }
        // interval 40: a heal at most every 41 ticks -> 4 heals in 200 ticks
        assertEquals(4, healed[0]);
        assertEquals(Tier.REDSTONE.capacity - 4 * EnderTechRules.HEAL_COST_PER_HALF_HEART, pad.stored());
    }

    // ------------------------------------------------------------------ Ender Tank

    private static Map<String, Part> box(int sx, int sy, int sz) {
        Map<String, Part> m = new HashMap<>();
        for (int x = 0; x < sx; x++) {
            for (int y = 0; y < sy; y++) {
                for (int z = 0; z < sz; z++) {
                    int b = (x == 0 || x == sx - 1 ? 1 : 0) + (y == 0 || y == sy - 1 ? 1 : 0) + (z == 0 || z == sz - 1 ? 1 : 0);
                    m.put(x + "," + y + "," + z, b == 0 ? Part.AIR : b >= 2 ? Part.FRAME : Part.GLASS);
                }
            }
        }
        return m;
    }

    private static TankStructure.Result check(Map<String, Part> m, int cx, int cy, int cz) {
        return TankStructure.validate(cx, cy, cz, (x, y, z) -> m.getOrDefault(x + "," + y + "," + z, Part.AIR));
    }

    @Test
    void tankStructureRules() {
        Map<String, Part> m = box(3, 4, 5);
        m.put("1,0,2", Part.CONTROLLER);
        assertEquals("no_valve", check(m, 1, 0, 2).problem());
        m.put("1,3,2", Part.VALVE);
        assertEquals("no_energy_input", check(m, 1, 0, 2).problem());
        m.put("0,1,2", Part.ENERGY_INPUT);
        TankStructure.Result ok = check(m, 1, 0, 2);
        assertTrue(ok.valid(), ok.problem());
        // interior 1 x 2 x 3 = 6 blocks x 512 buckets
        assertEquals(6L * 1000 * 512, ok.capacity());
        m.put("1,1,2", Part.FRAME);
        assertEquals("interior", check(m, 1, 0, 2).problem());
        m.put("1,1,2", Part.AIR);
        m.put("0,0,2", Part.GLASS); // an edge must be frame
        assertEquals("frame", check(m, 1, 0, 2).problem());
        Map<String, Part> big = box(9, 3, 3);
        big.put("1,0,1", Part.CONTROLLER);
        assertEquals("too_large", check(big, 1, 0, 1).problem());
    }

    @Test
    void tankValveChargesOneRfPerMbAndRefusesWhenUnformed() {
        TankCore t = new TankCore();
        FluidVariant water = FluidVariant.Simple.of("minecraft:water");
        assertEquals(0, t.valve().fill(water, 1000, false), "unformed tank accepts nothing");
        Map<String, Part> m = box(3, 3, 3);
        m.put("1,0,1", Part.CONTROLLER);
        m.put("1,2,1", Part.VALVE);
        m.put("0,1,1", Part.ENERGY_INPUT);
        t.applyStructure(check(m, 1, 0, 1));
        assertTrue(t.formed());
        assertEquals(20_000, t.energyInput(() -> 1).receive(50_000, false), "input capped per tick");
        assertEquals(0, t.energyInput(() -> 1).receive(50_000, false), "same tick: cap shared");
        assertEquals(20_000, t.valve().fill(water, 64_000, false), "fill limited by RF");
        assertEquals(0, t.energy().stored());
        t.energyInput(() -> 2).receive(5_000, false);
        assertEquals(5_000, t.valve().drain(water, 10_000, false));
        TankCore again = new TankCore();
        again.load(t.save(VariantCodec.SIMPLE), VariantCodec.SIMPLE);
        assertEquals(15_000, again.fluid().amount());
        assertTrue(again.formed());
    }

    // ------------------------------------------------------------------ FLORA

    @Test
    void chargeSharesCapacityAndMatrixFollowsFormula() {
        Flora.Charge c = new Flora.Charge();
        assertEquals(200, c.fill(Flora.Fluid.ENDER, 200, Flora.Tier.COPPER.capacity, false));
        assertEquals(50, c.fill(Flora.Fluid.GLOWSTONE, 500, Flora.Tier.COPPER.capacity, false));
        assertEquals(0, c.fill(Flora.Fluid.COAL, 1, Flora.Tier.COPPER.capacity, false));
        Flora.Charge reloaded = Flora.Charge.load(c.save(), Flora.Tier.COPPER.capacity);
        assertEquals(200, reloaded.amount(Flora.Fluid.ENDER));
        Flora.Charge four = new Flora.Charge();
        four.fill(Flora.Fluid.ENDER, 4000, Flora.Tier.GOLD.capacity, false);
        float[][] m = Flora.matrix(List.of(four));
        assertEquals(40f, Flora.m(m, Flora.Fluid.ENDER, Flora.Fluid.ENDER), 1e-4);
        assertEquals(0f, Flora.m(m, Flora.Fluid.ENDER, Flora.Fluid.COAL), 1e-6);
        assertEquals(28, Flora.EFFECTS.size());
        assertEquals(40, FloraEffects.blinkRange(m));
    }

    @Test
    void effectDecisions() {
        Flora.Charge c = new Flora.Charge();
        c.fill(Flora.Fluid.BLAZING, 10_000, Flora.Tier.ENDER_ALLOY.capacity, false);
        c.fill(Flora.Fluid.ARCANE, 10_000, Flora.Tier.ENDER_ALLOY.capacity, false);
        float[][] m = Flora.matrix(List.of(c));
        List<FloraEffects.Action> a = FloraEffects.tick(m, 1, false, new Random(1));
        assertTrue(a.contains(new FloraEffects.ApplyPotion(FloraEffects.Potion.SPEED, 2, 2)), "Pyro-Mana speed level log10(100)=2: " + a);
        assertTrue(a.contains(new FloraEffects.MaxHealthOffset(0)), "no redstone -> max-health modifier cleared");
        // Pyro-Pyro raises fall distance; no Ender, so no reduction.
        assertEquals(10 + 10f, FloraEffects.onWearerFall(m, 10), 1e-3);
        Flora.Charge cold = new Flora.Charge();
        cold.fill(Flora.Fluid.GELID, 1000, 2500, false);
        cold.fill(Flora.Fluid.ENDER, 1000, 2500, false);
        FloraEffects.HurtResult h = FloraEffects.onWearerHurt(Flora.matrix(List.of(cold)), 18, 20, false, false, 0);
        assertTrue(h.cancel());
        assertEquals(30, h.teleportSelfRange());
    }

    @Test
    void scriptLookupsAreReadOnlySummaries() {
        TestSupport.freshRegistry();
        java.util.List<java.util.Map<String, Object>> ups = dev.fieldframe.core.script.ScriptLookups.upgrades();
        assertEquals(5, ups.size()); // storage, tank, cell, throughput, overclock
        assertEquals("fieldframe:storage_upgrade", ups.get(0).get("id"));
        String json = "{\"format\":1,\"name\":\"Pair\",\"chassis\":[{\"x\":0,\"y\":0,\"z\":0,\"front\":\"north\",\"faces\":"
                + "{\"top\":{\"module\":\"fieldframe:heater\",\"config\":{},\"bindings\":{}}},\"upgrades\":[]},"
                + "{\"x\":1,\"y\":0,\"z\":0,\"front\":\"north\",\"faces\":{\"top\":{\"module\":\"kubejs:missing\",\"config\":{},\"bindings\":{}}},\"upgrades\":[]}]}";
        java.util.Map<String, Object> bp = dev.fieldframe.core.script.ScriptLookups.blueprint(json, "fieldframe:chassis");
        assertEquals(true, bp.get("valid"));
        assertEquals(2, bp.get("chassisCount"));
        assertEquals(2, ((java.util.Map<?, ?>) bp.get("billOfMaterials")).get("fieldframe:chassis"));
        assertFalse(((java.util.List<?>) bp.get("problems")).isEmpty(), "unknown module reported");
        assertEquals(false, dev.fieldframe.core.script.ScriptLookups.blueprint("{\"format\":1,\"bogus\":1}", "fieldframe:chassis").get("valid"));
    }

    @Test
    void infuserFillsArmorInPlaceAndOnlyWithEffectFluids() {
        TestSupport.freshRegistry();
        SimulatedWorld w = TestSupport.world();
        ChassisCore c = TestSupport.chassis(w);
        c.energy().receive(10_000, false);
        assertTrue(c.install(LocalFace.TOP, FieldframeRegistry.module(BuiltinModules.ARMOR_INFUSER), null).ok());
        ItemVariant helmet = new ItemVariant.Simple(FfId.ff(ArmorInfuserBehavior.itemPath(Flora.Tier.COPPER, Flora.Piece.HELMET)), "", 1);
        c.lane(0).insert(helmet, 1, false);
        c.tank(0).fill(FluidVariant.Simple.of("minecraft:water"), 1000, false);
        TestSupport.run(c, w, 5);
        assertEquals(1000, c.tank(0).amount(), "water is not an effect fluid");
        assertEquals("WRONG_FLUID", c.statuses().get(LocalFace.TOP).code());
        c.tank(0).drain(FluidVariant.Simple.of("minecraft:water"), 1000, false);
        c.tank(0).fill(new FluidVariant.Simple(Flora.Fluid.ENDER.id(), ""), 1000, false);
        TestSupport.run(c, w, 10);
        assertEquals(750, c.tank(0).amount(), "only the 250 mB that fit were drained");
        ItemVariant filled = c.lane(0).variant(0);
        assertNotNull(filled);
        Flora.Charge charge = Flora.Charge.load(w.itemData(filled, Flora.Charge.DATA_KEY), Flora.Tier.COPPER.capacity);
        assertEquals(250, charge.amount(Flora.Fluid.ENDER));
        assertEquals(10_000 - 250 * 2, c.energy().stored());
        assertFalse(c.lane(0).isEmpty());
    }
}
