package dev.fieldframe.api.chassis;

import dev.fieldframe.api.id.FfId;

/**
 * A service-bay upgrade. Only capacity, throughput, control-memory and overclock effects are allowed; service bays can
 * never host processors, actuators, generators or extra face functions. {@code overclock} (0..1) speeds up the
 * chassis' processors by that fraction, for more RF per operation and heat (see docs/design.md, Overclocking).
 */
public record UpgradeDefinition(
        FfId id,
        int extraSlotsPerLane,
        long extraFluidCapacity,
        long extraEnergyCapacity,
        long extraItemsPerTick,
        long extraFluidPerTick,
        long extraEnergyPerTick,
        int extraControllerRules,
        double overclock) {

    public UpgradeDefinition {
        if (extraSlotsPerLane < 0 || extraSlotsPerLane > 9 || extraFluidCapacity < 0 || extraEnergyCapacity < 0
                || extraItemsPerTick < 0 || extraFluidPerTick < 0 || extraEnergyPerTick < 0
                || extraControllerRules < 0 || extraControllerRules > 32 || !(overclock >= 0 && overclock <= 1)) {
            throw new IllegalArgumentException("Invalid upgrade " + id);
        }
    }

    /** An upgrade without overclock (the shape before 2026-10-31). */
    public UpgradeDefinition(FfId id, int extraSlotsPerLane, long extraFluidCapacity, long extraEnergyCapacity,
            long extraItemsPerTick, long extraFluidPerTick, long extraEnergyPerTick, int extraControllerRules) {
        this(id, extraSlotsPerLane, extraFluidCapacity, extraEnergyCapacity, extraItemsPerTick, extraFluidPerTick,
                extraEnergyPerTick, extraControllerRules, 0);
    }

    public String translationKey() {
        return "upgrade." + id.namespace() + "." + id.path();
    }
}
