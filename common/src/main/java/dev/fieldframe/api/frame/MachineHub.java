package dev.fieldframe.api.frame;

import dev.fieldframe.api.data.FfData;
import dev.fieldframe.api.resource.FluidVariant;
import dev.fieldframe.api.resource.ItemVariant;

import java.util.List;

/**
 * Shared storage for a multiblock machine: one Orange, Green and Purple storage, three tanks and an RF buffer that
 * every linked socket's modules work from (the platform's socket block entity links to a hub). The hub owns the
 * contents and saves them; sockets never do. Capacities are set by the frame (its components), and a shrink that no
 * longer fits returns the items to drop; fluid and RF beyond the new size are lost, as when a chassis breaks.
 */
public interface MachineHub {
    /** Items that did not fit somewhere and must be dropped (or given back) by the caller. */
    record Spill(ItemVariant item, int count) {
    }

    /** Sets the shared capacities: slots per storage (1..27), mB per tank, RF buffer. */
    List<Spill> resize(int slotsPerLane, long mbPerTank, long rfCapacity);

    int slotsPerLane();

    long mbPerTank();

    long rfCapacity();

    long rfStored();

    long itemCount(int lane);

    FluidVariant fluid(int tank);

    long fluidAmount(int tank);

    /** Storage 0 Orange, 1 Green, 2 Purple (conserving views; changes wake every linked socket). */
    dev.fieldframe.api.resource.ItemStore items(int lane);

    /** Tank 0 Orange, 1 Green, 2 Purple. */
    dev.fieldframe.api.resource.FluidStore tank(int tank);

    dev.fieldframe.api.resource.EnergyStore energy();

    /** Number of sockets linked right now. */
    int linkedSockets();

    /** Takes every item out (to drop when the machine is destroyed); fluid and RF are discarded. */
    List<Spill> drainAll();

    /** True once after the contents or capacities changed (the owner should mark itself for saving). */
    boolean consumeDirty();

    FfData save();

    /** Loads saved contents; returns problems (skipped entries), empty when all went well. */
    List<String> load(FfData d);
}
