package dev.fieldframe.core.endertech;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;

/**
 * Ender Tank multiblock validation. Starting from the controller, connected tank parts are collected (bounded flood
 * fill), their bounding box is checked against the size limits, and every position in the box is checked:
 * edges must be frames, faces may hold frames, glass, valves, energy inputs or the controller, and the interior must
 * be empty. Exactly one controller, at least one valve and one energy input are required.
 */
public final class TankStructure {
    private TankStructure() {
    }

    public enum Part {
        FRAME, GLASS, VALVE, ENERGY_INPUT, CONTROLLER, AIR, OTHER;

        boolean tankPart() {
            return this != AIR && this != OTHER;
        }
    }

    /** Reads the part kind at absolute coordinates; never loads chunks (unloaded = OTHER). */
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

        public long capacity() {
            return valid ? EnderTechRules.tankCapacity(sizeX(), sizeY(), sizeZ()) : 0;
        }

        static Result fail(String problem, int... args) {
            return new Result(false, 0, 0, 0, 0, 0, 0, problem, args);
        }
    }

    public static Result validate(int cx, int cy, int cz, Lookup world) {
        if (world.at(cx, cy, cz) != Part.CONTROLLER) {
            return Result.fail("no_controller");
        }
        int limit = EnderTechRules.TANK_MAX_XZ * EnderTechRules.TANK_MAX_Y * EnderTechRules.TANK_MAX_XZ;
        Set<Long> seen = new HashSet<>();
        Deque<int[]> queue = new ArrayDeque<>();
        queue.add(new int[] {cx, cy, cz});
        seen.add(key(cx, cy, cz));
        int minX = cx, minY = cy, minZ = cz, maxX = cx, maxY = cy, maxZ = cz;
        while (!queue.isEmpty()) {
            int[] p = queue.poll();
            minX = Math.min(minX, p[0]);
            minY = Math.min(minY, p[1]);
            minZ = Math.min(minZ, p[2]);
            maxX = Math.max(maxX, p[0]);
            maxY = Math.max(maxY, p[1]);
            maxZ = Math.max(maxZ, p[2]);
            if (maxX - minX + 1 > EnderTechRules.TANK_MAX_XZ || maxZ - minZ + 1 > EnderTechRules.TANK_MAX_XZ
                    || maxY - minY + 1 > EnderTechRules.TANK_MAX_Y || seen.size() > limit) {
                return Result.fail("too_large", EnderTechRules.TANK_MAX_XZ, EnderTechRules.TANK_MAX_Y, EnderTechRules.TANK_MAX_XZ);
            }
            int[][] next = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
            for (int[] d : next) {
                int x = p[0] + d[0], y = p[1] + d[1], z = p[2] + d[2];
                if (seen.contains(key(x, y, z))) {
                    continue;
                }
                if (world.at(x, y, z).tankPart()) {
                    seen.add(key(x, y, z));
                    queue.add(new int[] {x, y, z});
                }
            }
        }
        int sx = maxX - minX + 1, sy = maxY - minY + 1, sz = maxZ - minZ + 1;
        if (sx < EnderTechRules.TANK_MIN || sy < EnderTechRules.TANK_MIN || sz < EnderTechRules.TANK_MIN) {
            return Result.fail("too_small", EnderTechRules.TANK_MIN, EnderTechRules.TANK_MIN, EnderTechRules.TANK_MIN);
        }
        int controllers = 0, valves = 0, inputs = 0;
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    int boundary = (x == minX || x == maxX ? 1 : 0) + (y == minY || y == maxY ? 1 : 0) + (z == minZ || z == maxZ ? 1 : 0);
                    Part part = world.at(x, y, z);
                    if (boundary == 0) {
                        if (part != Part.AIR) {
                            return Result.fail("interior", x, y, z);
                        }
                        continue;
                    }
                    if (boundary >= 2 && part != Part.FRAME) {
                        return Result.fail("frame", x, y, z);
                    }
                    if (!part.tankPart()) {
                        return Result.fail("face", x, y, z);
                    }
                    if (part == Part.CONTROLLER) {
                        controllers++;
                    } else if (part == Part.VALVE) {
                        valves++;
                    } else if (part == Part.ENERGY_INPUT) {
                        inputs++;
                    }
                }
            }
        }
        if (controllers != 1) {
            return Result.fail("controllers", controllers);
        }
        if (valves < 1) {
            return Result.fail("no_valve");
        }
        if (inputs < 1) {
            return Result.fail("no_energy_input");
        }
        return new Result(true, minX, minY, minZ, maxX, maxY, maxZ, null, new int[0]);
    }

    private static long key(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
    }
}
