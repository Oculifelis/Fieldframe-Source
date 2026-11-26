package dev.fieldframe.api.module;

import dev.fieldframe.api.data.FfData;
import dev.fieldframe.api.resource.FluidStore;
import dev.fieldframe.api.resource.ItemStore;

/**
 * The mutable runtime half of an installed module. One instance per installed module; created by the
 * definition's {@link ModuleBehaviorFactory}. Called only on the logical server thread.
 *
 * <p>Contract:
 * <ul>
 *   <li>{@link #tick} must do bounded work and set a status through the context.</li>
 *   <li>Resource changes go through the context's stores, which conserve and respect shared budgets.</li>
 *   <li>{@link #save}/{@link #load} use explicit {@link FfData}; unknown keys must be ignored.</li>
 *   <li>{@link #onRemoved} must settle in-flight operations: refund to lanes or {@link ModuleContext#spill}.</li>
 * </ul>
 */
public interface ModuleBehavior {
    void tick(ModuleContext ctx);

    default void onInstalled(ModuleContext ctx) {
    }

    default void onConfigChanged(ModuleContext ctx) {
    }

    default void onRemoved(ModuleContext ctx) {
    }

    default FfData save() {
        return new FfData();
    }

    default void load(FfData data) {
    }

    /** Short live detail for the inspector, e.g. "42% (crushing iron ore)". Optional. */
    default String detail() {
        return "";
    }

    /** Progress 0..1 for display and Resource Sensor "progress" mode; negative when not applicable. */
    default double progress() {
        return -1;
    }

    // ---- Passive exposure on this module's face. Only consulted when the definition declares the matching
    // Exposure. The chassis wraps the returned store with the shared per-tick budget. ----

    /**
     * A player used this face with an empty hand (server side, owner or not). Return true to take the use: the machine
     * screen does not open and the platform charges the player a little hunger. Act on it in the next tick. Default false.
     */
    default boolean onPlayerUse(boolean sneaking) {
        return false;
    }

    /** Store reachable by an adjacent block through this face, or null for no passive item access. */
    /**
     * Whether this module burns {@code item} as fuel from its {@code fuel} storage. Ports use it to send fuel that
     * comes in through them to that storage (see {@link ModuleContext#itemsIn}). Engines override it; default false.
     */
    default boolean burns(dev.fieldframe.api.resource.ItemVariant item, ChassisWorld world) {
        return false;
    }

    default ItemStore exposeItems(ModuleContext ctx) {
        return null;
    }

    default FluidStore exposeFluids(ModuleContext ctx) {
        return null;
    }

    /** Whether the face accepts / provides RF. Both false means no energy access. */
    default boolean exposeEnergyReceive(ModuleContext ctx) {
        return false;
    }

    default boolean exposeEnergyExtract(ModuleContext ctx) {
        return false;
    }

    /** Redstone power 0..15 emitted on this face (Exposure.REDSTONE_OUT). */
    default int redstoneOutput() {
        return 0;
    }

    /** Item lanes (0..2) a player may access through this face's panel (Exposure.PLAYER_ACCESS). */
    default int[] playerAccessibleLanes(ModuleContext ctx) {
        return new int[0];
    }

    /** Fluid lanes (0..2) a player may fill or empty through this face's panel. */
    default int[] playerAccessibleTanks(ModuleContext ctx) {
        return new int[0];
    }
}
