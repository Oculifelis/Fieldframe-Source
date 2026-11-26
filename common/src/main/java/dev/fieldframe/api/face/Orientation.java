package dev.fieldframe.api.face;

/**
 * Maps chassis-local faces to world directions. A chassis faces one horizontal direction; TOP is always up.
 * LEFT and RIGHT are as seen by a player standing in front of the chassis looking at its FRONT face,
 * i.e. the chassis' own left/right when "looking out" through its front is mirrored. We define them from
 * the chassis' point of view: LEFT = front rotated counter-clockwise.
 */
public record Orientation(WorldDir front) {
    public static final Orientation DEFAULT = new Orientation(WorldDir.NORTH);

    public Orientation {
        if (front == null || !front.horizontal()) {
            throw new IllegalArgumentException("Chassis front must be horizontal, got " + front);
        }
    }

    public WorldDir toWorld(LocalFace face) {
        return switch (face) {
            case FRONT -> front;
            case BACK -> front.opposite();
            case LEFT -> front.counterClockwise();
            case RIGHT -> front.clockwise();
            case TOP -> WorldDir.UP;
            case BOTTOM -> WorldDir.DOWN;
        };
    }

    public LocalFace toLocal(WorldDir dir) {
        for (LocalFace f : LocalFace.ALL) {
            if (toWorld(f) == dir) {
                return f;
            }
        }
        throw new AssertionError(dir);
    }

    public Orientation rotateClockwise() {
        return new Orientation(front.clockwise());
    }

    /** Number of clockwise quarter turns from {@link #DEFAULT}. */
    public int quarterTurns() {
        int n = 0;
        WorldDir d = WorldDir.NORTH;
        while (d != front) {
            d = d.clockwise();
            n++;
        }
        return n;
    }

    public Orientation rotated(int clockwiseQuarterTurns) {
        Orientation o = this;
        for (int i = 0; i < Math.floorMod(clockwiseQuarterTurns, 4); i++) {
            o = o.rotateClockwise();
        }
        return o;
    }
}
