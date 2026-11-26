package dev.fieldframe.frameworks;

import java.util.Locale;

/**
 * What a gantry controller is doing. One value replaces the old formed / done / status-string trio: whether the
 * ring is formed is derived from it ({@link #formed()}), and each value names its lang key
 * ({@code fieldframe_frameworks.gantry.status.<key>}). Saved by {@link #key()}, never by ordinal.
 */
public enum GantryStatus {
    /** Not checked yet (fresh or just loaded with an unknown saved value). */
    UNCHECKED,
    RING_PROBLEM,
    IDLE,
    NEEDS_TOOL_MODULE,
    HEAD_MISSING,
    HEAD_BLOCKED,
    NO_POWER,
    MOVING,
    QUARRYING,
    FARMING,
    PUMPING,
    OUTPUT_FULL,
    DONE;

    /** Lang-key suffix and save id. */
    public String key() {
        return this == UNCHECKED ? "idle" : name().toLowerCase(Locale.ROOT);
    }

    public boolean formed() {
        return this != UNCHECKED && this != RING_PROBLEM;
    }

    /** Reads a saved key; unknown or missing values become UNCHECKED (the ring is re-checked anyway). */
    public static GantryStatus fromKey(String key) {
        if (key != null && !key.isEmpty()) {
            for (GantryStatus s : values()) {
                if (s != UNCHECKED && s.key().equals(key)) {
                    return s;
                }
            }
        }
        return UNCHECKED;
    }

    /** Lowest layer a by-layer program works; the run is done once {@code layer} goes below it. */
    public static boolean layersDone(int layer, int bottom) {
        return layer != Integer.MIN_VALUE && layer < bottom;
    }

    /**
     * The one transition rule. A broken ring always wins; a finished run stays DONE through re-checks; after the
     * ring forms, a stale unformed value becomes IDLE (or DONE).
     */
    public static GantryStatus next(GantryStatus current, GantryStatus wanted, boolean done) {
        if (wanted == RING_PROBLEM || wanted == UNCHECKED) {
            return wanted;
        }
        if (done && wanted != NEEDS_TOOL_MODULE) {
            return DONE;
        }
        return wanted;
    }
}
