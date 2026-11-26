package dev.fieldframe.api.event;

import dev.fieldframe.api.id.FfId;

/**
 * Base class of runtime events. Events carry ids and addresses, never live mutable machine objects.
 * Only events whose {@link #cancellable()} is true may be cancelled; cancellation must happen during
 * dispatch. Observational events (completion etc.) are fired only after a successful commit.
 */
public abstract class FieldframeEvent {
    private final String address;
    private final FfId moduleId;
    private boolean cancelled;
    private String cancelReason = "";

    protected FieldframeEvent(String address, FfId moduleId) {
        this.address = address;
        this.moduleId = moduleId;
    }

    public String address() {
        return address;
    }

    public FfId moduleId() {
        return moduleId;
    }

    public boolean cancellable() {
        return false;
    }

    public final void cancel(String reason) {
        if (!cancellable()) {
            throw new IllegalStateException(getClass().getSimpleName() + " is observational and cannot be cancelled");
        }
        cancelled = true;
        cancelReason = reason == null ? "" : reason.length() > 64 ? reason.substring(0, 64) : reason;
    }

    public final boolean isCancelled() {
        return cancelled;
    }

    public final String cancelReason() {
        return cancelReason;
    }
}
