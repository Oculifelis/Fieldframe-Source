package dev.fieldframe.core.util;

import java.util.concurrent.atomic.AtomicInteger;

/** Packet counters read by the two-process multiplayer smoke test (cheap atomic increments, always on). */
public final class MpProbe {
    private MpProbe() {
    }

    /** Validated chassis actions handled on the server. */
    public static final AtomicInteger ACTIONS = new AtomicInteger();
    /** Feature key packets handled on the server. */
    public static final AtomicInteger FEATURE_KEYS = new AtomicInteger();
    /** Inspector snapshots received on the client. */
    public static final AtomicInteger SNAPSHOTS = new AtomicInteger();
    /** Edits refused on the server because the player may not change that chassis. */
    public static final AtomicInteger REFUSED = new AtomicInteger();
    /** Chassis block updates sent to clients (configuration, lit faces, gauges); the perf test turns it into bytes/s. */
    public static final java.util.concurrent.atomic.AtomicLong CHASSIS_UPDATES = new java.util.concurrent.atomic.AtomicLong();
    /** Configuration revision from the latest snapshot the client received. */
    public static final java.util.concurrent.atomic.AtomicLong LAST_REVISION = new java.util.concurrent.atomic.AtomicLong(-1);
}
