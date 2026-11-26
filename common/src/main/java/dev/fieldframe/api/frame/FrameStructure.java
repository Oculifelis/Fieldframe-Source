package dev.fieldframe.api.frame;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;

/**
 * Validation of a hollow box multiblock (Grand Frame and the frames built on it). Starting from the core, connected
 * shell blocks are collected (bounded flood fill), their bounding box is checked against the size limits, and every
 * position in the box is checked: the outside (walls, edges, corners) must be shell or the core, the inside may only
 * hold interior blocks or air, and there must be exactly one core. Never loads anything: the lookup decides what an
 * unloaded position is (normally {@link Part#OTHER}).
 */
public final class FrameStructure {
    private FrameStructure() {
    }

    public enum Part {
        /** Casing (or anything the frame accepts in its walls). */
        SHELL,
        CORE,
        /** A block the frame accepts inside (a frame component). */
        INTERIOR,
        AIR,
        OTHER;

        boolean shell() {
            return this == SHELL || this == CORE;
        }
    }

    @FunctionalInterface
    public interface Lookup {
        Part at(int x, int y, int z);
    }

    /** Outcome; {@code problem} is a translation key suffix with up to three integer args, null when valid. */
    public record Result(boolean valid, int minX, int minY, int minZ, int maxX, int maxY, int maxZ, String problem, int[] args) {
        public int sizeX() {
            return maxX - minX + 1;
        }

        public int sizeY() {
            return maxY - minY + 1;
        }

        public int sizeZ() {
            return maxZ - minZ + 1;
        }

        public boolean contains(int x, int y, int z) {
            return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
        }

        /** Number of outside planes the position lies on: 0 inside, 1 a wall, 2 an edge, 3 a corner. */
        public int planes(int x, int y, int z) {
            int n = 0;
            n += x == minX || x == maxX ? 1 : 0;
            n += y == minY || y == maxY ? 1 : 0;
            n += z == minZ || z == maxZ ? 1 : 0;
            return n;
        }

        /** True for a wall position that is not on an edge or corner: these are the module sockets. */
        public boolean socket(int x, int y, int z) {
            return contains(x, y, z) && planes(x, y, z) == 1;
        }

        /** Outward direction of a wall position as a unit step {dx, dy, dz}; null when not a socket. */
        public int[] outward(int x, int y, int z) {
            if (!socket(x, y, z)) {
                return null;
            }
            if (x == minX) {
                return new int[]{-1, 0, 0};
            }
            if (x == maxX) {
                return new int[]{1, 0, 0};
            }
            if (y == minY) {
                return new int[]{0, -1, 0};
            }
            if (y == maxY) {
                return new int[]{0, 1, 0};
            }
            return z == minZ ? new int[]{0, 0, -1} : new int[]{0, 0, 1};
        }

        /** Sockets of the whole frame: 2 x (sx-2)(sy-2) + 2 x (sx-2)(sz-2) + 2 x (sy-2)(sz-2). */
        public int sockets() {
            int a = sizeX() - 2;
            int b = sizeY() - 2;
            int c = sizeZ() - 2;
            return 2 * (a * b + a * c + b * c);
        }

        public int interiorVolume() {
            return (sizeX() - 2) * (sizeY() - 2) * (sizeZ() - 2);
        }

        static Result fail(String problem, int... args) {
            return new Result(false, 0, 0, 0, 0, 0, 0, problem, args);
        }
    }

    /**
     * Validates the frame whose core is at {@code (cx, cy, cz)}. Each side of the box must be between
     * {@code minSize} and {@code maxSize} blocks (inclusive).
     */
    public static Result validate(int cx, int cy, int cz, Lookup lookup, int minSize, int maxSize) {
        if (lookup.at(cx, cy, cz) != Part.CORE) {
            return Result.fail("no_core");
        }
        Set<Long> seen = new HashSet<>();
        Deque<int[]> queue = new ArrayDeque<>();
        queue.add(new int[]{cx, cy, cz});
        seen.add(key(cx, cy, cz));
        int minX = cx, minY = cy, minZ = cz, maxX = cx, maxY = cy, maxZ = cz;
        int limit = 6 * maxSize * maxSize + 8;
        while (!queue.isEmpty()) {
            int[] p = queue.poll();
            minX = Math.min(minX, p[0]);
            minY = Math.min(minY, p[1]);
            minZ = Math.min(minZ, p[2]);
            maxX = Math.max(maxX, p[0]);
            maxY = Math.max(maxY, p[1]);
            maxZ = Math.max(maxZ, p[2]);
            if (maxX - minX >= maxSize || maxY - minY >= maxSize || maxZ - minZ >= maxSize || seen.size() > limit) {
                return Result.fail("too_big", maxSize);
            }
            int[][] steps = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
            for (int[] d : steps) {
                int x = p[0] + d[0];
                int y = p[1] + d[1];
                int z = p[2] + d[2];
                if (seen.contains(key(x, y, z)) || !lookup.at(x, y, z).shell()) {
                    continue;
                }
                seen.add(key(x, y, z));
                queue.add(new int[]{x, y, z});
            }
        }
        int sx = maxX - minX + 1;
        int sy = maxY - minY + 1;
        int sz = maxZ - minZ + 1;
        if (sx < minSize || sy < minSize || sz < minSize) {
            return Result.fail("too_small", minSize, sx, Math.min(sy, sz));
        }
        int cores = 0;
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    Part part = lookup.at(x, y, z);
                    boolean outside = x == minX || x == maxX || y == minY || y == maxY || z == minZ || z == maxZ;
                    if (outside) {
                        if (!part.shell()) {
                            return Result.fail("shell_missing", x, y, z);
                        }
                        if (part == Part.CORE) {
                            cores++;
                        }
                    } else if (part != Part.AIR && part != Part.INTERIOR) {
                        return Result.fail(part == Part.CORE || part == Part.SHELL ? "casing_inside" : "interior_blocked", x, y, z);
                    }
                }
            }
        }
        if (cores != 1) {
            return Result.fail("cores", cores);
        }
        return new Result(true, minX, minY, minZ, maxX, maxY, maxZ, null, new int[0]);
    }

    private static long key(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
    }
}
