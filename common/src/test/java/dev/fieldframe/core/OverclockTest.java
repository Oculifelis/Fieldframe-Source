package dev.fieldframe.core;

import dev.fieldframe.api.FieldframeRegistry;
import dev.fieldframe.api.face.LocalFace;
import dev.fieldframe.api.id.FfId;
import dev.fieldframe.core.chassis.ChassisCore;
import dev.fieldframe.core.chassis.SimulatedWorld;
import dev.fieldframe.core.modules.BuiltinModules;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static dev.fieldframe.core.TestSupport.IRON;
import static dev.fieldframe.core.TestSupport.IRON_ORE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Overclock Bay Upgrade: faster processors, more RF per operation, heat, overheating and cooling by cold blocks. */
class OverclockTest {
    static final FfId OVERCLOCK = FfId.ff("overclock_upgrade");
    SimulatedWorld w;

    @BeforeEach
    void setUp() {
        TestSupport.freshRegistry();
        w = TestSupport.world();
    }

    private ChassisCore heater(int overclocks) {
        ChassisCore c = TestSupport.chassis(w);
        assertTrue(c.install(LocalFace.TOP, FieldframeRegistry.module(BuiltinModules.HEATER), null).ok());
        for (int i = 0; i < overclocks; i++) {
            assertTrue(c.installUpgrade(i, FieldframeRegistry.upgrade(OVERCLOCK)).ok());
        }
        c.lane(0).insert(IRON_ORE, 64, false);
        return c;
    }

    /** Runs with the buffer kept topped up; returns the RF the machine used. */
    private long run(ChassisCore c, int ticks) {
        long used = 0;
        for (int i = 0; i < ticks; i++) {
            c.energy().receive(c.energy().capacity() - c.energy().stored(), false);
            long before = c.energy().stored();
            TestSupport.run(c, w, 1);
            used += before - c.energy().stored();
        }
        return used;
    }

    @Test
    void overclockSmeltsFasterForMoreRfPerItem() {
        ChassisCore plain = heater(0);
        long plainRf = run(plain, 400);
        ChassisCore fast = heater(1);
        long fastRf = run(fast, 400);
        long plainItems = plain.lane(1).countOf(IRON);
        long fastItems = fast.lane(1).countOf(IRON);
        assertTrue(fastItems > plainItems, "overclocked " + fastItems + " vs " + plainItems);
        assertTrue(fastRf / (double) fastItems > plainRf / (double) plainItems, "more RF per item");
        assertEquals(0.5, fast.overclock());
        assertTrue(fast.heat() > 0, "working overclocked builds heat");
        assertEquals(0, plain.heat());
    }

    @Test
    void overheatsInStillAirThenRecoversAndColdKeepsItRunning() {
        ChassisCore c = heater(1);
        run(c, 700);
        assertTrue(c.overheated(), "+4 a tick against 2 of cooling overheats within 700 ticks; heat " + c.heat());
        assertEquals(0, c.overclock(), "overheated machines run at normal speed");
        // Blue ice under the machine (180 K) takes 7 more a tick: it cools down and stays cool while it works.
        w.actions.temperatures.put(LocalFace.BOTTOM, 180);
        run(c, 200);
        assertFalse(c.overheated(), "recovered; heat " + c.heat());
        run(c, 600);
        assertFalse(c.overheated(), "cold enough to run overclocked; heat " + c.heat());
        assertEquals(0.5, c.overclock());
    }

    @Test
    void heatSurvivesSaveAndLoad() {
        ChassisCore c = heater(1);
        run(c, 300);
        int heat = c.heat();
        assertTrue(heat > 0);
        ChassisCore copy = TestSupport.chassis(w);
        copy.load(c.save());
        assertEquals(heat, copy.heat());
        assertEquals(0.5, copy.overclock(), "the upgrade comes back with its overclock");
    }
}
