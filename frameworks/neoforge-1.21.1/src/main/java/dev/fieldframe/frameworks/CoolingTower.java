package dev.fieldframe.frameworks;

import dev.fieldframe.api.frame.MachineHub;
import dev.fieldframe.api.resource.FluidStore;
import dev.fieldframe.api.resource.FluidVariant;
import dev.fieldframe.neoforge.api.FieldframeForgeApi;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.material.Fluids;

/**
 * Cooling Tower: a Grand Frame with Cooling Fins inside turns the steam in its tanks back into water in its Orange
 * tank, {@code cooling_tower.mb_per_fin} mB a tick per fin, all of it (a Condenser returns at most 90%). With a Boiler
 * and a Steam Turbine on the frame's walls that closes the steam loop at frame scale. It never makes water from nothing:
 * every mB of water was a mB of steam.
 */
public final class CoolingTower {
    private CoolingTower() {
    }

    /** Tuning {@code cooling_tower.mb_per_fin}. */
    public static long mbPerFin() {
        return FwTuning.getLong("cooling_tower.mb_per_fin");
    }

    /** One tick; returns the mB condensed. */
    static long tick(MachineHub hub, int fins) {
        FluidVariant steam = FieldframeForgeApi.variantOf(BuiltInRegistries.FLUID.get(ResourceLocation.fromNamespaceAndPath("fieldframe", "steam")));
        FluidVariant water = FieldframeForgeApi.variantOf(Fluids.WATER);
        long budget = mbPerFin() * fins;
        FluidStore orange = hub.tank(0);
        long done = 0;
        for (int t = 0; t < 3 && budget > 0; t++) {
            FluidStore tank = hub.tank(t);
            long can = Math.min(budget, tank.drain(steam, budget, true));
            if (can <= 0) {
                continue;
            }
            long moved = Math.min(can, orange.fill(water, can, true));
            if (moved <= 0) {
                continue;
            }
            long drained = tank.drain(steam, moved, false);
            orange.fill(water, drained, false);
            budget -= drained;
            done += drained;
        }
        return done;
    }
}
