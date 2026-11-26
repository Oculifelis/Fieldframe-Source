package dev.fieldframe.bench;

import dev.fieldframe.api.FieldframeRegistry;
import dev.fieldframe.api.data.FfData;
import dev.fieldframe.api.face.LocalFace;
import dev.fieldframe.core.TestSupport;
import dev.fieldframe.core.chassis.ChassisCore;
import dev.fieldframe.core.chassis.SimulatedWorld;
import dev.fieldframe.core.modules.BuiltinModules;
import dev.fieldframe.core.storage.ItemLane;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * Domain-only throughput measurement (no Minecraft): ticks many chassis in simulated worlds.
 * Excluded from the normal test run; run with {@code ./gradlew benchmark}. Results go to stdout
 * and are copied by hand into docs/performance.md.
 */
@Tag("benchmark")
class DomainBenchmark {
    private static final int CHASSIS = 512;
    private static final int WARMUP = 400;
    private static final int TICKS = 2000;

    private record Unit(SimulatedWorld w, ChassisCore c, ItemLane in) {
    }

    private static FfData cfg(String mode) {
        FfData d = FieldframeRegistry.module(BuiltinModules.ITEM_PORT).defaultConfig();
        d.putAny("mode", mode);
        return d;
    }

    private static List<Unit> busyLines() {
        List<Unit> units = new ArrayList<>();
        for (int i = 0; i < CHASSIS; i++) {
            SimulatedWorld w = TestSupport.world();
            ChassisCore c = TestSupport.chassis(w);
            ItemLane in = new ItemLane("chest", 27);
            w.items.put(LocalFace.LEFT, in);
            w.items.put(LocalFace.RIGHT, new ItemLane("chest", 27));
            c.install(LocalFace.LEFT, FieldframeRegistry.module(BuiltinModules.ITEM_PORT), cfg("import"));
            c.install(LocalFace.RIGHT, FieldframeRegistry.module(BuiltinModules.ITEM_PORT), cfg("export"));
            c.setBinding(LocalFace.RIGHT, "lane", 1);
            c.install(LocalFace.TOP, FieldframeRegistry.module(BuiltinModules.HEATER), null);
            c.install(LocalFace.BOTTOM, FieldframeRegistry.module(BuiltinModules.FUEL_ENGINE), null);
            c.setBinding(LocalFace.BOTTOM, "fuel", 2);
            units.add(new Unit(w, c, in));
        }
        return units;
    }

    private static List<Unit> idleLines() {
        List<Unit> units = busyLines();
        // Nothing to import and no fuel: every module should settle into idle/sleep.
        return units;
    }

    private static double measure(List<Unit> units, boolean feed) {
        for (int t = 0; t < WARMUP + TICKS; t++) {
            if (t == WARMUP) {
                System.gc();
            }
            long start = t == WARMUP ? System.nanoTime() : 0;
            if (t == WARMUP) {
                startNs = start;
            }
            for (Unit u : units) {
                if (feed && t % 100 == 0) {
                    u.in.insert(TestSupport.IRON_ORE, 4, false);
                    u.c.lane(2).insert(TestSupport.COAL, 1, false);
                }
                u.w.time++;
                u.c.tick(u.w);
            }
        }
        long elapsed = System.nanoTime() - startNs;
        return elapsed / (double) (TICKS * (long) units.size());
    }

    private static long startNs;

    @Test
    void chassisTickCost() {
        TestSupport.freshRegistry();
        double busy = measure(busyLines(), true);
        TestSupport.freshRegistry();
        double idle = measure(idleLines(), false);
        System.out.printf("FIELDFRAME_BENCH chassis=%d ticks=%d busy_ns_per_chassis_tick=%.0f idle_ns_per_chassis_tick=%.0f%n",
                CHASSIS, TICKS, busy, idle);
        System.out.printf("FIELDFRAME_BENCH busy_ms_per_server_tick_for_%d=%.3f idle=%.3f%n",
                CHASSIS, busy * CHASSIS / 1e6, idle * CHASSIS / 1e6);
    }
}
