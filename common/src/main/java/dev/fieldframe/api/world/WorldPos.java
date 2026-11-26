package dev.fieldframe.api.world;

/** A block position with its dimension id (e.g. {@code minecraft:overworld}), as reported by a chassis' world. */
public record WorldPos(String dimension, int x, int y, int z) {
    public WorldPos {
        if (dimension == null || dimension.isEmpty()) {
            throw new IllegalArgumentException("dimension");
        }
    }
}
