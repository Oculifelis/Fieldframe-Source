package dev.fieldframe.core;

import dev.fieldframe.api.FieldframeRegistry;
import dev.fieldframe.api.data.FfData;
import dev.fieldframe.api.face.LocalFace;
import dev.fieldframe.api.resource.FluidVariant;
import dev.fieldframe.api.resource.ItemVariant;
import dev.fieldframe.core.chassis.BoltGroup;
import dev.fieldframe.core.chassis.ChassisCore;
import dev.fieldframe.core.chassis.SimulatedWorld;
import dev.fieldframe.core.modules.BuiltinModules;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static dev.fieldframe.core.TestSupport.COAL;
import static dev.fieldframe.core.TestSupport.IRON;
import static dev.fieldframe.core.TestSupport.IRON_ORE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Bolted Chassis: shared stores, capacities that add up, conservation on bolt / unbolt / break, save and relink. */
class BoltGroupTest {
    static final FluidVariant WATER = FluidVariant.Simple.of("minecraft:water");
    static final FluidVariant LAVA = FluidVariant.Simple.of("minecraft:lava");
    SimulatedWorld w;
    ChassisCore a;
    ChassisCore b;

    @BeforeEach
    void setUp() {
        TestSupport.freshRegistry();
        w = TestSupport.world();
        a = TestSupport.chassis(w);
        b = TestSupport.chassis(w);
    }

    private void tickBoth(int ticks) {
        for (int i = 0; i < ticks; i++) {
            w.time++;
            a.tick(w);
            b.tick(w);
        }
    }

    private static long items(ChassisCore c, int lane) {
        return c.lane(lane).totalCount();
    }

    @Test
    void boltingSharesStoresAndAddsCapacitiesAndMovesContents() {
        a.lane(0).insert(IRON_ORE, 10, false);
        b.lane(0).insert(COAL, 5, false);
        b.tank(0).fill(WATER, 1_000, false);
        b.energy().receive(500, false);
        int slots = a.lane(0).slots();
        long tank = a.tank(0).capacity();
        long rf = a.energy().capacity();
        assertTrue(BoltGroup.bolt(a, b).ok());
        assertSame(a.lane(0), b.lane(0), "one Orange storage for the machine");
        assertSame(a.tank(0), b.tank(0));
        assertSame(a.energy(), b.energy());
        assertSame(a.control(), b.control());
        assertEquals(2 * slots, a.lane(0).slots(), "capacities add up");
        assertEquals(2 * tank, a.tank(0).capacity());
        assertEquals(2 * rf, a.energy().capacity());
        assertEquals(10, a.lane(0).countOf(IRON_ORE));
        assertEquals(5, a.lane(0).countOf(COAL), "the new part's items moved in");
        assertEquals(1_000, a.tank(0).amountOf(WATER));
        assertEquals(500, a.energy().stored());
        assertSame(a, b.group().lead());
    }

    @Test
    void differentFluidsRefuseAndChangeNothing() {
        a.tank(0).fill(LAVA, 1_000, false);
        b.tank(0).fill(WATER, 1_000, false);
        ChassisCore.Result r = BoltGroup.bolt(a, b);
        assertFalse(r.ok());
        assertEquals("TANK_MISMATCH", r.code());
        assertNull(a.group());
        assertEquals(1_000, b.tank(0).amountOf(WATER));
        assertEquals(1_000, a.tank(0).amountOf(LAVA));
    }

    @Test
    void modulesOnDifferentPartsWorkFromTheSameStorage() {
        assertTrue(BoltGroup.bolt(a, b).ok());
        assertTrue(a.install(LocalFace.TOP, FieldframeRegistry.module(BuiltinModules.HEATER), null).ok());
        assertTrue(b.install(LocalFace.BOTTOM, FieldframeRegistry.module(BuiltinModules.FUEL_ENGINE), null).ok());
        a.lane(0).insert(IRON_ORE, 2, false);
        b.lane(2).insert(COAL, 1, false);
        tickBoth(400);
        assertEquals(2, a.lane(1).countOf(IRON), "the heater on one part ran on the engine on the other");
    }

    @Test
    void controlChannelsAreSharedAndFlipOncePerTick() {
        assertTrue(BoltGroup.bolt(a, b).ok());
        w.time = 10;
        a.control().beginTick(10);
        a.control().drive(2, 9);
        b.control().beginTick(10);
        assertEquals(0, b.control().read(2), "same tick: still the old snapshot");
        b.control().beginTick(11);
        assertEquals(9, a.control().read(2), "next tick both parts see it");
    }

    @Test
    void unboltRefusesWhenTheRestWouldNotFitAndConservesWhenItDoes() {
        assertTrue(BoltGroup.bolt(a, b).ok());
        int all = a.lane(0).slots();
        for (int i = 0; i < all; i++) {
            a.lane(0).insert(ItemVariant.Simple.of("minecraft:dirt_" + i), 64, false);
        }
        ChassisCore.Result r = BoltGroup.unbolt(b);
        assertEquals("WOULD_TRUNCATE_ITEMS", r.code());
        assertSame(a.lane(0), b.lane(0), "still bolted");
        for (int i = 0; i < all / 2; i++) {
            a.lane(0).extractSlot(i, 64, false);
        }
        long before = items(a, 0);
        assertTrue(BoltGroup.unbolt(b).ok());
        assertNull(a.group());
        assertNull(b.group());
        assertEquals(before, items(a, 0), "nothing lost");
        assertEquals(0, items(b, 0), "the leaving part starts empty");
        assertEquals(all / 2, a.lane(0).slots(), "back to its own size");
        assertFalse(a.lane(0) == b.lane(0));
    }

    @Test
    void breakingAPartDropsWhatNoLongerFits() {
        assertTrue(BoltGroup.bolt(a, b).ok());
        int all = a.lane(0).slots();
        for (int i = 0; i < all; i++) {
            a.lane(0).insert(ItemVariant.Simple.of("minecraft:dirt_" + i), 64, false);
        }
        List<Object[]> spill = BoltGroup.breakPart(b);
        long dropped = 0;
        for (Object[] s : spill) {
            dropped += (Integer) s[1];
        }
        assertEquals(64L * all, items(a, 0) + dropped, "every item either stays or drops");
        assertEquals(64L * all / 2, dropped);
        assertNull(a.group());
    }

    @Test
    void savedMachineWaitsForItsPartsAndRelinks() {
        assertTrue(BoltGroup.bolt(a, b).ok());
        a.setBoltInfo(new FfData().putList("parts", new ArrayList<>(List.of("1,0,0"))));
        b.setBoltInfo(new FfData().putString("lead", "-1,0,0"));
        a.lane(1).insert(IRON, 40, false);
        a.tank(2).fill(WATER, 6_000, false);
        FfData da = a.save();
        FfData db = b.save();
        ChassisCore a2 = TestSupport.chassis(w);
        ChassisCore b2 = TestSupport.chassis(w);
        assertTrue(a2.load(da).isEmpty());
        assertTrue(b2.load(db).isEmpty());
        assertTrue(a2.isAwaitingLink() && b2.isAwaitingLink(), "both wait for each other");
        assertEquals(0, items(b2, 1), "the part saved no copy of the shared contents");
        assertEquals(40, a2.lane(1).countOf(IRON));
        assertEquals(6_000, a2.tank(2).amountOf(WATER), "the lead kept the machine-sized tank");
        assertTrue(a2.install(LocalFace.TOP, FieldframeRegistry.module(BuiltinModules.HEATER), null).ok());
        a2.lane(0).insert(IRON_ORE, 1, false);
        a2.energy().receive(10_000, false);
        for (int i = 0; i < 300; i++) {
            w.time++;
            a2.tick(w);
        }
        assertEquals(1, a2.lane(0).countOf(IRON_ORE), "paused while a part is missing");
        assertTrue(BoltGroup.relink(a2, b2, 2).ok());
        assertFalse(a2.isAwaitingLink());
        assertSame(a2.lane(1), b2.lane(1));
        for (int i = 0; i < 300; i++) {
            w.time++;
            a2.tick(w);
            b2.tick(w);
        }
        assertEquals(0, a2.lane(0).countOf(IRON_ORE), "running again");
        BoltGroup.unload(b2);
        assertTrue(a2.isAwaitingLink(), "a part unloaded: paused");
    }

    @Test
    void atMostEightParts() {
        List<ChassisCore> parts = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            ChassisCore c = TestSupport.chassis(w);
            parts.add(c);
            if (i > 0) {
                assertTrue(BoltGroup.bolt(parts.get(0), c).ok(), "part " + i);
            }
        }
        ChassisCore.Result r = BoltGroup.bolt(parts.get(3), TestSupport.chassis(w));
        assertEquals("MACHINE_TOO_BIG", r.code());
        assertEquals(8, parts.get(0).group().parts().size());
    }

    @Test
    void leadUnboltSplitsTheWholeMachine() {
        ChassisCore c = TestSupport.chassis(w);
        assertTrue(BoltGroup.bolt(a, b).ok());
        assertTrue(BoltGroup.bolt(b, c).ok(), "bolting onto any part joins the lead's machine");
        assertSame(a, c.group().lead());
        a.lane(0).insert(IRON, 10, false);
        assertTrue(BoltGroup.unbolt(a).ok());
        assertNull(a.group());
        assertNull(b.group());
        assertNull(c.group());
        assertEquals(10, a.lane(0).countOf(IRON), "the lead keeps the contents");
    }
}
