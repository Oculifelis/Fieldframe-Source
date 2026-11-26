package dev.fieldframe.api.world;

import dev.fieldframe.api.face.LocalFace;
import dev.fieldframe.api.face.Orientation;
import dev.fieldframe.api.face.WorldDir;

/**
 * Converts {@link WorkTarget}s to world offsets. Axes, seen from the chassis looking out through the face:
 * <ul>
 *   <li>Side faces (FRONT/BACK/LEFT/RIGHT): {@code up} is world up; {@code right} is the viewer's right.</li>
 *   <li>TOP/BOTTOM: {@code up} points toward the chassis FRONT; {@code right} is the chassis RIGHT face.</li>
 * </ul>
 * Every platform uses this class, so a work area is identical on all editions.
 */
public final class WorkGeometry {
    private WorkGeometry() {
    }

    /** Offset {dx, dy, dz} from the chassis position. */
    public static int[] offset(Orientation o, WorkTarget t) {
        WorldDir n = o.toWorld(t.face());
        WorldDir r;
        WorldDir u;
        if (n.horizontal()) {
            r = n.clockwise();
            u = WorldDir.UP;
        } else {
            r = o.toWorld(LocalFace.RIGHT);
            u = o.toWorld(LocalFace.FRONT);
        }
        return new int[] {
                n.dx * t.depth() + r.dx * t.right() + u.dx * t.up(),
                n.dy * t.depth() + r.dy * t.right() + u.dy * t.up(),
                n.dz * t.depth() + r.dz * t.right() + u.dz * t.up()
        };
    }

    /**
     * Visits a square work area of side {@code 2*radius+1} at each depth, layer by layer, row by row.
     * {@code index} is a persisted cursor; returns null once the area is exhausted.
     */
    public static WorkTarget areaCursor(LocalFace face, int radius, int maxDepth, int index) {
        if (index < 0 || radius < 0 || radius > WorkTarget.MAX_LATERAL || maxDepth < 1) {
            return null;
        }
        int side = 2 * radius + 1;
        int perLayer = side * side;
        int layer = index / perLayer;
        if (layer >= Math.min(maxDepth, WorkTarget.MAX_DEPTH)) {
            return null;
        }
        int i = index % perLayer;
        return new WorkTarget(face, layer + 1, i % side - radius, i / side - radius);
    }

    public static int areaSize(int radius, int maxDepth) {
        int side = 2 * radius + 1;
        return side * side * Math.min(maxDepth, WorkTarget.MAX_DEPTH);
    }
}
