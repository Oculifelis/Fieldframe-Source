package dev.fieldframe.api.module;

/**
 * Fixed processing phases of one chassis tick, executed in this order. Within a phase modules run in
 * round-robin order starting from a persisted rotating cursor, so no face is permanently favoured.
 * Control signals written during a tick become readable at the start of the next tick.
 */
public enum TickPhase {
    /** Generators add RF to the buffer. */
    GENERATE,
    /** Ports pull from neighbours into lanes. */
    IMPORT,
    /** Processors and world actuators work. */
    PROCESS,
    /** Ports push from lanes to neighbours. */
    EXPORT,
    /** Logic, sensors, controllers and indicators evaluate the snapshot and write next-tick signals. */
    CONTROL
}
