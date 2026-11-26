package dev.fieldframe.api.energy;

import java.util.Locale;

/**
 * RF helpers. Fieldframe counts electrical energy in RF, mapped 1:1 onto the platform's native energy
 * unit (Forge/NeoForge FE). Internally amounts are {@code long}; conversions to platform {@code int}
 * APIs saturate rather than truncate.
 */
public final class Energy {
    private Energy() {
    }

    /** JavaScript's largest exactly-representable integer; script-facing values are validated against it. */
    public static final long JS_SAFE_MAX = 9_007_199_254_740_991L;

    public static int clampToInt(long v) {
        if (v > Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        if (v < Integer.MIN_VALUE) {
            return Integer.MIN_VALUE;
        }
        return (int) v;
    }

    public static long saturatingAdd(long a, long b) {
        long r = a + b;
        if (((a ^ r) & (b ^ r)) < 0) {
            return a < 0 ? Long.MIN_VALUE : Long.MAX_VALUE;
        }
        return r;
    }

    public static long saturatingMul(long a, long b) {
        long hi = Math.multiplyHigh(a, b);
        long lo = a * b;
        if ((hi == 0 && lo >= 0) || (hi == -1 && lo < 0)) {
            return lo;
        }
        return (a < 0) == (b < 0) ? Long.MAX_VALUE : Long.MIN_VALUE;
    }

    /** Validates a number coming from a script or data file and converts it to an exact non-negative long. */
    public static long requireSafeNonNegative(double v, String what) {
        if (!Double.isFinite(v) || v < 0 || v > JS_SAFE_MAX || v != Math.floor(v)) {
            throw new IllegalArgumentException(what + " must be a whole number between 0 and " + JS_SAFE_MAX + ", got " + v);
        }
        return (long) v;
    }

    public static String format(long rf) {
        if (rf < 10_000) {
            return rf + " RF";
        }
        if (rf < 10_000_000) {
            return String.format(Locale.ROOT, "%.1fk RF", rf / 1000.0);
        }
        return String.format(Locale.ROOT, "%.2fM RF", rf / 1_000_000.0);
    }

    public static String formatRate(long rfPerTick) {
        return rfPerTick + " RF/t";
    }
}
