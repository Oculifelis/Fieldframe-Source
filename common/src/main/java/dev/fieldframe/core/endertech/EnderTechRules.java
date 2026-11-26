package dev.fieldframe.core.endertech;

import dev.fieldframe.api.face.WorldDir;

import java.util.ArrayList;
import java.util.List;

/**
 * Loader-independent rules of the EnderTech feature set (Exchanger, Charge Pad, Health Pad, Ender Tank), taken from
 * EnderTech 0.3.2 by Sky Welch and reimplemented for Fieldframe. See docs/features/endertech.md for sources.
 */
public final class EnderTechRules {
    private EnderTechRules() {
    }

    /** Device tiers shared by the Exchanger and both pads. CREATIVE needs no energy. */
    public enum Tier {
        REDSTONE("redstone", 2_000_000, 2_000, 2_000, 40),
        RESONANT("resonant", 10_000_000, 20_000, 20_000, 20),
        CREATIVE("creative", Long.MAX_VALUE, 0, 10_000_000, 10);

        public final String key;
        public final long capacity;
        public final long receivePerTick;
        /** Charge Pad send rate per tick. */
        public final long padSendPerTick;
        /** Health Pad minimum ticks between half-heart heals. */
        public final int ticksPerHeal;

        Tier(String key, long capacity, long receive, long padSend, int ticksPerHeal) {
            this.key = key;
            this.capacity = capacity;
            this.receivePerTick = receive;
            this.padSendPerTick = padSend;
            this.ticksPerHeal = ticksPerHeal;
        }

        public boolean creative() {
            return this == CREATIVE;
        }

        public static Tier byKey(String key) {
            for (Tier t : values()) {
                if (t.key.equals(key)) {
                    return t;
                }
            }
            return REDSTONE;
        }
    }

    // ------------------------------------------------------------------ Exchanger

    public static final int EXCHANGER_DEFAULT_RADIUS = 3;
    public static final int EXCHANGER_MAX_RADIUS = 8;
    /** Ticks between rings (original Exchange.radiusTicksDefault). */
    public static final int EXCHANGER_TICKS_PER_RING = 10;
    public static final long EXCHANGE_BASE_COST = 2048;
    public static final long EXCHANGE_RING_COST = 128;
    public static final long EXCHANGE_HARDNESS_COST = 64;
    public static final long EXCHANGE_MIN_COST = 2048;
    public static final long EXCHANGE_MAX_COST = 16384;

    /** RF for one swap on ring {@code ring} (1-based) of a block with the given hardness. */
    public static long exchangeCost(int ring, float hardness) {
        int h = (int) Math.max(1, Math.min(50, hardness));
        long cost = EXCHANGE_BASE_COST + EXCHANGE_RING_COST * ring + EXCHANGE_HARDNESS_COST * h;
        return Math.max(EXCHANGE_MIN_COST, Math.min(EXCHANGE_MAX_COST, cost));
    }

    public static int clampRadius(int r) {
        return Math.max(1, Math.min(EXCHANGER_MAX_RADIUS, r));
    }

    /**
     * Offsets {dx,dy,dz} of the square ring at Chebyshev distance {@code ring - 1} around the origin, in the plane
     * perpendicular to {@code normal} (the clicked face). Ring 1 is the clicked block itself.
     */
    public static List<int[]> ring(WorldDir normal, int ring) {
        int d = ring - 1;
        List<int[]> out = new ArrayList<>();
        for (int a = -d; a <= d; a++) {
            for (int b = -d; b <= d; b++) {
                if (Math.max(Math.abs(a), Math.abs(b)) != d) {
                    continue;
                }
                out.add(switch (normal) {
                    case UP, DOWN -> new int[] {a, 0, b};
                    case NORTH, SOUTH -> new int[] {a, b, 0};
                    case EAST, WEST -> new int[] {0, a, b};
                });
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ pads

    /** Depth of the box in front of a pad that it affects (original getAABBInFront(2)). */
    public static final int PAD_RANGE = 2;
    /** RF per half heart (original HealthPad.ChargePerHalfHeart default). */
    public static final long HEAL_COST_PER_HALF_HEART = 192_000;

    /** Even split of a per-tick send budget between {@code entities} receivers (integer division, like the original). */
    public static long chargeShare(long sendable, int entities) {
        return entities <= 0 ? 0 : sendable / entities;
    }

    // ------------------------------------------------------------------ Ender Tank

    public static final int TANK_MIN = 3;
    public static final int TANK_MAX_XZ = 8;
    public static final int TANK_MAX_Y = 10;
    /** Buckets of storage per interior block (original Tank.StorageMultiplier default, source 0.3.2). */
    public static final long TANK_MULTIPLIER = 512;
    public static final long TANK_MAX_ENERGY = 10_000_000;
    public static final long TANK_ENERGY_INPUT_PER_TICK = 20_000;
    /** RF per mB moved through a valve. */
    public static final long TANK_RF_PER_MB = 1;

    public static long tankCapacity(int sx, int sy, int sz) {
        long interior = (long) (sx - 2) * (sy - 2) * (sz - 2);
        return Math.min(Long.MAX_VALUE / 2, interior * 1000L * TANK_MULTIPLIER);
    }
}
