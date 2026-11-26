package dev.fieldframe.api.module;

import dev.fieldframe.api.event.FieldframeEvent;
import dev.fieldframe.api.face.LocalFace;
import dev.fieldframe.api.face.WorldDir;
import dev.fieldframe.api.resource.FluidStore;
import dev.fieldframe.api.resource.FluidVariant;
import dev.fieldframe.api.resource.ItemStore;
import dev.fieldframe.api.resource.ItemVariant;
import dev.fieldframe.api.status.Status;

/**
 * Everything an installed module may see and do during a callback. A context is only valid for the duration
 * of the callback that received it; do not store it.
 *
 * <p>Lanes are exposed as conserving {@link ItemStore}/{@link FluidStore} views limited to this module's own
 * bindings. External transfers must draw from the shared chassis budgets via {@link #itemBudget()} etc.
 */
public interface ModuleContext {
    ModuleDefinition definition();

    LocalFace face();

    WorldDir worldDirection();

    ConfigView config();

    /** Effective tunable value after datapack/script overrides, clamped to the definition's bounds. */
    double tunable(String key);

    /**
     * Speed factor of the chassis tier (1 at the base tier). Processors divide their time by it and engines multiply
     * their RF/t by it, so the RF per operation or per fuel stays the same.
     */
    default double chassisSpeed() {
        return 1.0;
    }

    /**
     * Overclock of the chassis from its upgrades (0 = none, at most 2), or 0 while it is overheated. Processors run
     * {@code 1 + overclock} times as fast for {@code 1 + overclock / 2} times the RF per operation, and heat the chassis.
     */
    default double overclock() {
        return 0;
    }

    /** Level of the chassis tier: 0 Basic, 1 Hardened, 2 Reinforced, 3 Signalum, 4 Resonant (addon tiers above). */
    default int chassisTier() {
        return 0;
    }

    /** Bound item lane, or null when the binding is unset. */
    ItemStore items(String bindingKey);

    FluidStore fluids(String bindingKey);

    /**
     * The bound storage as an entry point for items arriving from outside the chassis (ports). Fuel that an engine on
     * this chassis burns from another storage goes to that storage instead, unless a machine taking from this storage
     * can use the item (has a recipe for it) or an engine burns it from here; when the fuel storage is full the item is
     * not taken. Everything else behaves exactly like {@link #items}.
     */
    default ItemStore itemsIn(String bindingKey) {
        return items(bindingKey);
    }

    /** Display label of a binding's current target, e.g. "Green storage" or "signal 2". */
    String bindingLabel(String bindingKey);

    /** Raw bound index (lane 0..2 / channel 0..7) or -1. */
    int bindingIndex(String bindingKey);

    Energy energy();

    /** Reads a bound input signal from the start-of-tick snapshot; -1 when unbound. */
    int signal(String bindingKey);

    /** True when the binding is unset or its snapshot value is above zero. */
    default boolean enabledBy(String bindingKey) {
        int v = signal(bindingKey);
        return v < 0 || v > 0;
    }

    /** Requests a value on a bound output channel; committed at the end of the tick. */
    void emit(String bindingKey, int value);

    /** Reads any channel 0..7 from the snapshot. */
    int channel(int index);

    /**
     * Drives any channel 0..7 (committed at the end of the tick, highest driver wins). Prefer signal-out bindings,
     * which appear in the patch diagram; this is for programmable modules such as the Controller Plate.
     */
    void driveChannel(int index, int value);

    /** Read-only totals of the whole chassis (all lanes, reservoirs and the RF buffer). */
    ChassisView chassis();

    /** Another module on the same chassis: its side, definition and whether it worked within the last two seconds. */
    record Peer(dev.fieldframe.api.face.LocalFace face, ModuleDefinition definition, boolean working) {
    }

    interface ChassisView {
        /**
         * Every installed module on this chassis, in side order, this one included. Lets modules that work as a set
         * (Hot and Cold Junction, Steam Turbine and Condenser) find each other without any link between them.
         */
        default java.util.List<Peer> peers() {
            return java.util.List.of();
        }

        long itemCount(int lane);

        long itemCapacity(int lane);

        long fluidAmount(int tank);

        long fluidCapacity(int tank);
    }

    void setStatus(Status status);

    /** Skips ticks until woken by a relevant lane change or the timeout elapses. */
    void sleep(int ticks);

    long gameTime();

    /** A fresh pseudo-random seed for capturing probabilistic results at reservation time. */
    long nextSeed();

    ChassisWorld world();

    Budget itemBudget();

    Budget fluidBudget();

    /**
     * The chassis RF buffer as seen through a power terminal: limited by the shared directional RF budgets.
     * Used for active transfer to neighbours; internal consumption uses {@link #energy()}.
     */
    dev.fieldframe.api.resource.EnergyStore terminalEnergy(boolean allowReceive, boolean allowExtract);

    /** Hands resources that cannot be returned to lanes to the platform (player inventory or dropped). */
    void spill(ItemVariant v, int count);

    void spillFluid(FluidVariant v, long amount);

    /** Appends to the chassis' bounded diagnostic trace. */
    void trace(String message);

    /** Posts an event to Java and script listeners. Returns false when a cancellable event was cancelled. */
    boolean post(FieldframeEvent event);

    /** Opaque stable address of this module instance (chassis address + face + install generation). */
    String address();

    interface Energy {
        long stored();

        long capacity();

        /** All-or-nothing consumption; returns false (and consumes nothing) if not enough is stored. */
        boolean consume(long amount);

        /** Adds created RF; returns the amount accepted. */
        long generate(long amount);
    }

    interface Budget {
        long remaining();

        void spend(long actual);
    }
}
