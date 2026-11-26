package dev.fieldframe.api.face;

/** Absolute world directions, mirrored from the platform's Direction enum by adapters (same ordinal order). */
public enum WorldDir {
    DOWN(0, -1, 0), UP(0, 1, 0), NORTH(0, 0, -1), SOUTH(0, 0, 1), WEST(-1, 0, 0), EAST(1, 0, 0);

    public final int dx, dy, dz;

    WorldDir(int dx, int dy, int dz) {
        this.dx = dx;
        this.dy = dy;
        this.dz = dz;
    }

    public WorldDir opposite() {
        return switch (this) {
            case DOWN -> UP;
            case UP -> DOWN;
            case NORTH -> SOUTH;
            case SOUTH -> NORTH;
            case WEST -> EAST;
            case EAST -> WEST;
        };
    }

    public boolean horizontal() {
        return dy == 0;
    }

    /** Rotates a horizontal direction 90 degrees clockwise when viewed from above; vertical directions are unchanged. */
    public WorldDir clockwise() {
        return switch (this) {
            case NORTH -> EAST;
            case EAST -> SOUTH;
            case SOUTH -> WEST;
            case WEST -> NORTH;
            default -> this;
        };
    }

    public WorldDir counterClockwise() {
        return clockwise().clockwise().clockwise();
    }
}
