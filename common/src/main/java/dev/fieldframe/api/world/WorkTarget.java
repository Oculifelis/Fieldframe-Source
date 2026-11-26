package dev.fieldframe.api.world;

import dev.fieldframe.api.face.LocalFace;

/**
 * A block position addressed relative to one chassis face: {@code depth} blocks out along the face normal
 * (1 = the adjacent block) and {@code right}/{@code up} across the face plane. See {@link WorkGeometry} for the
 * exact axes. Bounds are enforced here so no module can address an unbounded region.
 */
public record WorkTarget(LocalFace face, int depth, int right, int up) {
    public static final int MAX_DEPTH = 64;
    public static final int MAX_LATERAL = 8;

    public WorkTarget {
        if (face == null) {
            throw new IllegalArgumentException("face");
        }
        if (depth < 1 || depth > MAX_DEPTH) {
            throw new IllegalArgumentException("depth out of range: " + depth);
        }
        if (Math.abs(right) > MAX_LATERAL || Math.abs(up) > MAX_LATERAL) {
            throw new IllegalArgumentException("lateral offset out of range");
        }
    }

    public static WorkTarget adjacent(LocalFace face) {
        return new WorkTarget(face, 1, 0, 0);
    }

    @Override
    public String toString() {
        return face.key() + "+" + depth + "(" + right + "," + up + ")";
    }
}
