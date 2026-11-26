package dev.fieldframe.core.storage;

/**
 * A shared per-tick allowance. Every access path to a resource (passive faces, active ports, scripts)
 * draws from the same budget, so six ports cannot each spend the nominal chassis limit.
 * Simulation queries never spend. The budget resets at the start of each chassis tick.
 */
public final class RateBudget {
    private long perTick;
    private long used;
    private long lastResetTick = Long.MIN_VALUE;

    public RateBudget(long perTick) {
        this.perTick = Math.max(0, perTick);
    }

    public long perTick() {
        return perTick;
    }

    /** Rate changes take effect immediately; already-spent allowance this tick is kept. */
    public void setPerTick(long perTick) {
        this.perTick = Math.max(0, perTick);
    }

    public void resetFor(long gameTick) {
        if (gameTick != lastResetTick) {
            lastResetTick = gameTick;
            used = 0;
        }
    }

    public long[] snapshot() {
        return new long[]{perTick, used, lastResetTick};
    }

    public void restore(long[] s) {
        perTick = s[0];
        used = s[1];
        lastResetTick = s[2];
    }

    public long remaining() {
        return Math.max(0, perTick - used);
    }

    public long used() {
        return used;
    }

    /** Clamps a request to the remaining allowance. */
    public long allow(long requested) {
        return Math.max(0, Math.min(requested, remaining()));
    }

    /** Records actual movement; callers spend only what really moved. */
    public void spend(long actual) {
        if (actual > 0) {
            used += Math.min(actual, remaining());
        }
    }
}
