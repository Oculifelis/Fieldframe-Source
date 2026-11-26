package dev.fieldframe.api.status;

import static dev.fieldframe.api.status.Status.Severity.BLOCKED;
import static dev.fieldframe.api.status.Status.Severity.DISABLED;
import static dev.fieldframe.api.status.Status.Severity.FAULT;
import static dev.fieldframe.api.status.Status.Severity.IDLE;
import static dev.fieldframe.api.status.Status.Severity.WORKING;

/** Built-in status codes. Addons may define their own codes with the same {@link Status} shape. */
public final class Statuses {
    private Statuses() {
    }

    private static final Status IDLE_STATUS = Status.of("IDLE", IDLE);

    public static Status idle() {
        return IDLE_STATUS;
    }

    public static Status working(String what) {
        return Status.of("WORKING", WORKING, what);
    }

    public static Status noInput(String lane) {
        return Status.of("NO_INPUT", IDLE, lane);
    }

    public static Status outputFull(String lane) {
        return Status.of("OUTPUT_FULL", BLOCKED, lane);
    }

    public static Status invalidRecipe(String lane) {
        return Status.of("INVALID_RECIPE", BLOCKED, lane);
    }

    public static Status wrongFluid(String required) {
        return Status.of("WRONG_FLUID", BLOCKED, required);
    }

    public static Status noFluid(String lane) {
        return Status.of("NO_FLUID", BLOCKED, lane);
    }

    public static Status noEnergy(long needed) {
        return Status.of("NO_ENERGY", BLOCKED, needed + " RF");
    }

    public static Status signalDisabled(String channel) {
        return Status.of("SIGNAL_DISABLED", DISABLED, channel);
    }

    public static final Status NO_TOOL = Status.of("NO_TOOL", BLOCKED);
    public static final Status NO_FUEL = Status.of("NO_FUEL", IDLE);
    public static final Status BUFFER_FULL = Status.of("BUFFER_FULL", IDLE);
    public static final Status NO_SKY = Status.of("NO_SKY", IDLE);
    public static final Status NIGHT = Status.of("NIGHT", IDLE);
    public static final Status NEIGHBOR_UNLOADED = Status.of("NEIGHBOR_UNLOADED", BLOCKED);
    public static final Status NO_NEIGHBOR = Status.of("NO_NEIGHBOR", IDLE);
    public static final Status NEIGHBOR_FULL = Status.of("NEIGHBOR_FULL", BLOCKED);
    public static final Status NEIGHBOR_EMPTY = Status.of("NEIGHBOR_EMPTY", IDLE);
    public static final Status UNBOUND = Status.of("UNBOUND", FAULT);
    public static final Status PASSIVE = Status.of("PASSIVE", IDLE);
    public static final Status DISABLED_BY_CONFIG = Status.of("DISABLED_BY_CONFIG", DISABLED);
    public static final Status PAUSED = Status.of("PAUSED", DISABLED);

    // ---- World interaction ----
    public static final Status NO_WORLD_ACCESS = Status.of("NO_WORLD_ACCESS", FAULT);
    public static final Status POLICY_DISABLED = Status.of("POLICY_DISABLED", DISABLED);
    public static final Status AREA_DONE = Status.of("AREA_DONE", IDLE);
    public static final Status NOTHING_TO_DO = Status.of("NOTHING_TO_DO", IDLE);
    public static final Status WRONG_TOOL = Status.of("WRONG_TOOL", BLOCKED);

    public static Status protectedTarget(String where) {
        return Status.of("PROTECTED", BLOCKED, where);
    }

    public static Status unbreakable(String where) {
        return Status.of("UNBREAKABLE", BLOCKED, where);
    }

    public static Status targetBlocked(String where) {
        return Status.of("TARGET_BLOCKED", BLOCKED, where);
    }

    public static Status outputPending(int items) {
        return Status.of("OUTPUT_PENDING", BLOCKED, Integer.toString(items));
    }

    public static Status cancelledByRule(String reason) {
        return Status.of("CANCELLED_BY_RULE", BLOCKED, reason);
    }

    public static Status missingDefinition(String id) {
        return Status.of("MISSING_DEFINITION", FAULT, id);
    }

    public static Status scriptError(String where) {
        return Status.of("SCRIPT_ERROR", FAULT, where);
    }

    public static Status fault(String detail) {
        return Status.of("FAULT", FAULT, detail);
    }
}
