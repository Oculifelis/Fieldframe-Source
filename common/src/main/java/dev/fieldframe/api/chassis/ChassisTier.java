package dev.fieldframe.api.chassis;

import dev.fieldframe.api.id.FfId;

/**
 * A chassis tier (Thermal Expansion's machine tiers): Basic, Hardened, Reinforced, Signalum, Resonant. An upgrade kit
 * raises a chassis by exactly one level and is given back when the chassis is broken. A tier speeds up every
 * processor and engine on the chassis ({@code speed}: same RF per operation, spent faster) and adds service bays,
 * lane stacks, reservoir and RF capacity and power-terminal throughput on top of the chassis spec and its upgrades.
 * Level 0 is the base tier every chassis starts at; it has no kit. Register more tiers at startup with
 * {@code FieldframeRegistry.registerTier}; each tier above 0 gets a kit item {@link #kitId()}.
 */
public record ChassisTier(
        FfId id,
        int level,
        double speed,
        int extraServiceBays,
        int extraSlotsPerLane,
        long extraFluidCapacity,
        long extraEnergyCapacity,
        long extraEnergyPerTick) {

    public ChassisTier {
        if (level < 0 || level > 15 || !(speed >= 0.1 && speed <= 64) || extraServiceBays < 0 || extraServiceBays > 6
                || extraSlotsPerLane < 0 || extraSlotsPerLane > 18 || extraFluidCapacity < 0 || extraEnergyCapacity < 0
                || extraEnergyPerTick < 0) {
            throw new IllegalArgumentException("Invalid chassis tier " + id);
        }
    }

    public String translationKey() {
        return "tier." + id.namespace() + "." + id.path();
    }

    /** The kit item that raises a chassis to this tier. */
    public FfId kitId() {
        return FfId.of(id.namespace(), id.path() + "_upgrade_kit");
    }
}
