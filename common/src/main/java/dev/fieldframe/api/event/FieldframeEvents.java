package dev.fieldframe.api.event;

import dev.fieldframe.api.face.LocalFace;
import dev.fieldframe.api.id.FfId;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Global runtime event bus for Java addons and the KubeJS bridge. Listeners are registered with an
 * {@code owner} token so a reloading script engine can remove exactly its own listeners
 * ({@link #unregisterOwner}) and repeated reloads never duplicate them.
 *
 * <p>Threading: posted only on the logical server thread. Listener exceptions are caught, counted and
 * reported via the error sink; an exception in a cancellable pre-event cancels the operation (fail-safe).
 */
public final class FieldframeEvents {
    private FieldframeEvents() {
    }

    private record Listener(Class<?> type, Consumer<Object> handler, Object owner) {
    }

    private static final List<Listener> LISTENERS = new CopyOnWriteArrayList<>();
    private static volatile Consumer<String> errorSink = System.err::println;
    private static int depth;

    public static final int MAX_DEPTH = 4;

    @SuppressWarnings("unchecked")
    public static <T extends FieldframeEvent> void register(Class<T> type, Consumer<? super T> handler, Object owner) {
        LISTENERS.add(new Listener(type, o -> handler.accept((T) o), owner));
    }

    public static int unregisterOwner(Object owner) {
        List<Listener> rm = new ArrayList<>();
        for (Listener l : LISTENERS) {
            if (l.owner() == owner || (l.owner() != null && l.owner().equals(owner))) {
                rm.add(l);
            }
        }
        LISTENERS.removeAll(rm);
        return rm.size();
    }

    public static int listenerCount() {
        return LISTENERS.size();
    }

    public static void setErrorSink(Consumer<String> sink) {
        errorSink = sink == null ? s -> { } : sink;
    }

    /** Dispatches; returns false when the event was cancelled. Nested dispatch beyond MAX_DEPTH is refused. */
    public static boolean post(FieldframeEvent event) {
        if (depth >= MAX_DEPTH) {
            errorSink.accept("Fieldframe event recursion limit reached for " + event.getClass().getSimpleName() + "; event dropped");
            if (event.cancellable()) {
                event.cancel("recursion_limit");
            }
            return !event.isCancelled();
        }
        depth++;
        try {
            for (Listener l : LISTENERS) {
                if (l.type().isInstance(event)) {
                    try {
                        l.handler().accept(event);
                    } catch (RuntimeException ex) {
                        errorSink.accept("Fieldframe listener failed on " + event.getClass().getSimpleName() + " (" + event.moduleId() + "): " + ex);
                        if (event.cancellable() && !event.isCancelled()) {
                            event.cancel("listener_error");
                        }
                    }
                }
            }
        } finally {
            depth--;
        }
        return !event.isCancelled();
    }

    // ---- Built-in event types ----

    public static final class ModuleInstalled extends FieldframeEvent {
        private final LocalFace face;

        public ModuleInstalled(String address, FfId moduleId, LocalFace face) {
            super(address, moduleId);
            this.face = face;
        }

        public LocalFace face() {
            return face;
        }
    }

    public static final class ModuleRemoved extends FieldframeEvent {
        private final LocalFace face;

        public ModuleRemoved(String address, FfId moduleId, LocalFace face) {
            super(address, moduleId);
            this.face = face;
        }

        public LocalFace face() {
            return face;
        }
    }

    public static final class ModuleConfigured extends FieldframeEvent {
        public ModuleConfigured(String address, FfId moduleId) {
            super(address, moduleId);
        }
    }

    /** Cancellable pre-event fired after preflight succeeded and before any input is reserved. */
    public static final class OperationStarting extends FieldframeEvent {
        private final FfId recipeId;
        private final FfId family;

        public OperationStarting(String address, FfId moduleId, FfId recipeId, FfId family) {
            super(address, moduleId);
            this.recipeId = recipeId;
            this.family = family;
        }

        public FfId recipeId() {
            return recipeId;
        }

        public FfId family() {
            return family;
        }

        @Override
        public boolean cancellable() {
            return true;
        }
    }

    /** Fired once, after outputs were committed. */
    public static final class OperationCompleted extends FieldframeEvent {
        private final FfId recipeId;
        private final long operationId;

        public OperationCompleted(String address, FfId moduleId, FfId recipeId, long operationId) {
            super(address, moduleId);
            this.recipeId = recipeId;
            this.operationId = operationId;
        }

        public FfId recipeId() {
            return recipeId;
        }

        public long operationId() {
            return operationId;
        }
    }

    public static final class OperationFailed extends FieldframeEvent {
        private final String reason;

        public OperationFailed(String address, FfId moduleId, String reason) {
            super(address, moduleId);
            this.reason = reason;
        }

        public String reason() {
            return reason;
        }
    }

    /** Cancellable pre-event for world-mutating module actions (break, place, interact, excavate). */
    public static final class WorldAction extends FieldframeEvent {
        private final String action;
        private final int x, y, z;

        public WorldAction(String address, FfId moduleId, String action, int x, int y, int z) {
            super(address, moduleId);
            this.action = action;
            this.x = x;
            this.y = y;
            this.z = z;
        }

        public String action() {
            return action;
        }

        public int x() {
            return x;
        }

        public int y() {
            return y;
        }

        public int z() {
            return z;
        }

        @Override
        public boolean cancellable() {
            return true;
        }
    }
}
