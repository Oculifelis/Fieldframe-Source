package dev.fieldframe.core.util;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Optional timing of chassis server ticks for in-game performance measurements (performance GameTests). Off by
 * default; when off it costs one volatile read per chassis tick.
 */
public final class PerfProbe {
    private PerfProbe() {
    }

    public static volatile boolean enabled;
    public static final AtomicLong NANOS = new AtomicLong();
    public static final AtomicLong TICKS = new AtomicLong();

    /** When non-empty, only these objects (chassis block entities) are timed. */
    public static final java.util.Set<Object> TRACKED = java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());

    public static void reset() {
        NANOS.set(0);
        TICKS.set(0);
    }

    public static boolean timed(Object chassis) {
        return enabled && (TRACKED.isEmpty() || TRACKED.contains(chassis));
    }

    public static void record(long nanos) {
        NANOS.addAndGet(nanos);
        TICKS.incrementAndGet();
    }
}
