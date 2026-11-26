package dev.fieldframe.api.chassis;

import dev.fieldframe.api.id.FfId;

/**
 * Base capacities of a chassis type. Numbers are tunable defaults (docs/balance.md), not parity targets.
 * Upgrades in service bays add to these; they never add faces or functions.
 */
public record ChassisSpec(
        FfId id,
        int slotsPerLane,
        long fluidCapacity,
        long energyCapacity,
        int serviceBays,
        long itemsPerTick,
        long fluidPerTick,
        long energyInPerTick,
        long energyOutPerTick) {

    public static final int ITEM_LANES = 3;
    public static final int FLUID_LANES = 3;
    public static final int CHANNELS = 8;

    public static final ChassisSpec STANDARD = new ChassisSpec(FfId.ff("standard"), 4, 4_000, 16_000, 3, 16, 1_000, 512, 512);

    public ChassisSpec {
        if (slotsPerLane < 1 || slotsPerLane > 27 || serviceBays < 0 || serviceBays > 6 || fluidCapacity < 0 || energyCapacity < 0
                || itemsPerTick < 0 || fluidPerTick < 0 || energyInPerTick < 0 || energyOutPerTick < 0) {
            throw new IllegalArgumentException("Invalid chassis spec " + id);
        }
    }
}
