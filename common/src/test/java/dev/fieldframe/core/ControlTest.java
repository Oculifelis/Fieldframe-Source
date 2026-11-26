package dev.fieldframe.core;

import dev.fieldframe.api.FieldframeRegistry;
import dev.fieldframe.api.data.FfData;
import dev.fieldframe.api.face.LocalFace;
import dev.fieldframe.api.face.WorldDir;
import dev.fieldframe.core.chassis.ChassisCore;
import dev.fieldframe.core.chassis.SimulatedWorld;
import dev.fieldframe.core.control.ControlBus;
import dev.fieldframe.core.modules.BuiltinModules;
import dev.fieldframe.core.modules.LogicBehaviors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ControlTest {
    SimulatedWorld w;
    ChassisCore c;

    @BeforeEach
    void setUp() {
        TestSupport.freshRegistry();
        w = TestSupport.world();
        c = TestSupport.chassis(w);
    }

    @Test
    void everyLogicModeTruthTable() {
        String[] ops = {"and", "or", "xor", "nand", "nor", "xnor", "not", "buffer"};
        boolean[][] expected = {
                {false, false, false, true}, // and: 00 01 10 11
                {false, true, true, true},
                {false, true, true, false},
                {true, true, true, false},
                {true, false, false, false},
                {true, false, false, true},
                {true, true, false, false}, // not a
                {false, false, true, true}, // buffer a
        };
        for (int o = 0; o < ops.length; o++) {
            for (int i = 0; i < 4; i++) {
                boolean a = (i & 2) != 0, b = (i & 1) != 0;
                assertEquals(expected[o][i], LogicBehaviors.LogicPlate.eval(ops[o], a, b), ops[o] + " " + a + " " + b);
            }
        }
    }

    @Test
    void memoryPriorityResetOverSetOverToggle() {
        assertFalse(LogicBehaviors.MemoryPlate.next(true, true, true, true));
        assertTrue(LogicBehaviors.MemoryPlate.next(false, true, false, true));
        assertTrue(LogicBehaviors.MemoryPlate.next(false, false, false, true));
        assertFalse(LogicBehaviors.MemoryPlate.next(true, false, false, true));
        assertTrue(LogicBehaviors.MemoryPlate.next(true, false, false, false));
    }

    @Test
    void hysteresisBothModes() {
        boolean a = false;
        int[] levels = {50, 30, 25, 40, 74, 75, 60, 20};
        boolean[] onBelow = {false, false, true, true, true, false, false, true};
        for (int i = 0; i < levels.length; i++) {
            a = LogicBehaviors.ResourceSensor.hysteresis(a, levels[i], 25, 75, true);
            assertEquals(onBelow[i], a, "level " + levels[i]);
        }
        a = false;
        a = LogicBehaviors.ResourceSensor.hysteresis(a, 80, 25, 75, false);
        assertTrue(a);
        a = LogicBehaviors.ResourceSensor.hysteresis(a, 50, 25, 75, false);
        assertTrue(a);
        a = LogicBehaviors.ResourceSensor.hysteresis(a, 10, 25, 75, false);
        assertFalse(a);
    }

    @Test
    void busIsDrivenMaxWinsAndOneTickLatency() {
        ControlBus bus = new ControlBus();
        bus.drive(0, 5);
        bus.drive(0, 9);
        bus.drive(0, 99);
        assertEquals(0, bus.read(0));
        bus.beginTick();
        assertEquals(15, bus.read(0));
        bus.beginTick();
        assertEquals(0, bus.read(0), "undriven channel reads 0");
    }

    @Test
    void feedbackLoopOscillatesOnceePerTickWithoutRecursion() {
        // NOT gate reading its own output channel: must toggle every tick, never recurse.
        FfData cfg = FieldframeRegistry.module(BuiltinModules.LOGIC_PLATE).defaultConfig().putString("op", "not");
        c.install(LocalFace.FRONT, FieldframeRegistry.module(BuiltinModules.LOGIC_PLATE), cfg);
        c.setBinding(LocalFace.FRONT, "a", 2);
        int[] seen = new int[6];
        for (int i = 0; i < 6; i++) {
            w.time++;
            c.tick(w);
            seen[i] = c.control().read(2);
        }
        // read() reflects start-of-tick snapshot; it alternates.
        for (int i = 1; i < 6; i++) {
            assertTrue(seen[i] != seen[i - 1], "alternates each tick");
        }
    }

    @Test
    void signalTerminalRoundTripsWorldRedstone() {
        c.install(LocalFace.LEFT, FieldframeRegistry.module(BuiltinModules.SIGNAL_TERMINAL), null);
        FfData outCfg = FieldframeRegistry.module(BuiltinModules.SIGNAL_TERMINAL).defaultConfig().putString("mode", "output");
        c.install(LocalFace.RIGHT, FieldframeRegistry.module(BuiltinModules.SIGNAL_TERMINAL), outCfg);
        w.redstone.put(LocalFace.LEFT, 7);
        TestSupport.run(c, w, 3);
        assertEquals(15, c.redstoneOutput(WorldDir.EAST));
        assertEquals(0, c.redstoneOutput(WorldDir.WEST));
        w.redstone.put(LocalFace.LEFT, 0);
        TestSupport.run(c, w, 3);
        assertEquals(0, c.redstoneOutput(WorldDir.EAST));
    }

    @Test
    void sensorDrivesEngineWithHysteresisAndUnpoweredLogicWorks() {
        // Resource sensor on energy: on below 25%, off above 75%. Engine enabled by that channel.
        c.install(LocalFace.FRONT, FieldframeRegistry.module(BuiltinModules.RESOURCE_SENSOR), null); // out ch5 (index 4)
        c.install(LocalFace.BOTTOM, FieldframeRegistry.module(BuiltinModules.FUEL_ENGINE), null);
        c.setBinding(LocalFace.BOTTOM, "enable", 4);
        c.lane(2).insert(TestSupport.COAL, 4, false);
        TestSupport.run(c, w, 400);
        long stored = c.energy().stored();
        assertTrue(stored >= 12_000, "charged to the high threshold: " + stored);
        TestSupport.run(c, w, 100);
        assertTrue(c.energy().stored() <= 16_000);
        assertEquals("SIGNAL_DISABLED", c.module(LocalFace.BOTTOM).status().code());
    }

    @Test
    void timerAndCounterPersistAcrossReload() {
        FfData counter = FieldframeRegistry.module(BuiltinModules.TIMER_COUNTER).defaultConfig().putString("mode", "counter").putLong("target", 3);
        c.install(LocalFace.FRONT, FieldframeRegistry.module(BuiltinModules.TIMER_COUNTER), counter);
        c.setBinding(LocalFace.FRONT, "count", 0);
        c.install(LocalFace.LEFT, FieldframeRegistry.module(BuiltinModules.SIGNAL_TERMINAL), null); // world -> ch1
        for (int pulse = 0; pulse < 2; pulse++) {
            w.redstone.put(LocalFace.LEFT, 15);
            TestSupport.run(c, w, 3);
            w.redstone.put(LocalFace.LEFT, 0);
            TestSupport.run(c, w, 3);
        }
        ChassisCore again = TestSupport.chassis(w);
        assertTrue(again.load(c.save()).isEmpty());
        assertEquals(2, ((LogicBehaviors.TimerCounter) again.module(LocalFace.FRONT).behavior()).count());
        w.redstone.put(LocalFace.LEFT, 15);
        TestSupport.run(again, w, 4);
        assertEquals(15, again.control().read(3));
    }

    @Test
    void timerPulses() {
        FfData t = FieldframeRegistry.module(BuiltinModules.TIMER_COUNTER).defaultConfig().putLong("period", 10).putLong("pulse", 2);
        c.install(LocalFace.FRONT, FieldframeRegistry.module(BuiltinModules.TIMER_COUNTER), t);
        int on = 0;
        for (int i = 0; i < 100; i++) {
            w.time++;
            c.tick(w);
            if (c.control().read(3) > 0) {
                on++;
            }
        }
        // 10 pulses of 2 ticks are emitted; the pulse emitted on tick 100 becomes readable on tick 101
        // (one-tick bus latency), so 9 full pulses are observed within the window.
        assertEquals(18, on);
    }
}
