package dev.fieldframe.core.world;

import dev.fieldframe.api.world.WorldPos;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Shared world sources (wind, ground heat): generators of one kind near each other split what the world offers. Each
 * generator claims its position when it measures; {@link #claim} returns how many fresh claims of that kind lie within
 * {@code radius} blocks (Chebyshev distance, same dimension), itself included, so the caller divides its output by it.
 * Claims older than {@code ttl} ticks are ignored and pruned, so removed or unloaded generators stop counting.
 * Purely in memory: after a restart the counts settle within one measuring interval.
 */
public final class SharedSources {
    private SharedSources() {
    }

    private static final Map<String, Map<Long, Long>> CLAIMS = new HashMap<>();

    public static synchronized int claim(String kind, WorldPos pos, int radius, long now, long ttl) {
        Map<Long, Long> m = CLAIMS.computeIfAbsent(kind + "@" + pos.dimension(), k -> new HashMap<>());
        m.put(pack(pos.x(), pos.y(), pos.z()), now);
        int n = 0;
        for (Iterator<Map.Entry<Long, Long>> it = m.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Long, Long> e = it.next();
            if (Math.abs(now - e.getValue()) > ttl) {
                it.remove();
                continue;
            }
            long p = e.getKey();
            if (Math.abs(unpackX(p) - pos.x()) <= radius && Math.abs(unpackY(p) - pos.y()) <= radius && Math.abs(unpackZ(p) - pos.z()) <= radius) {
                n++;
            }
        }
        return Math.max(1, n);
    }

    /** Tests only. */
    public static synchronized void clear() {
        CLAIMS.clear();
    }

    private static long pack(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
    }

    private static int unpackX(long p) {
        return (int) (p >> 38) << 6 >> 6;
    }

    private static int unpackZ(long p) {
        return (int) ((p >> 12) & 0x3FFFFFF) << 6 >> 6;
    }

    private static int unpackY(long p) {
        return (int) (p & 0xFFF) << 20 >> 20;
    }
}
