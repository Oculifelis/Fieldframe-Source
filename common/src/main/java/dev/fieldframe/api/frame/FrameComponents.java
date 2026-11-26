package dev.fieldframe.api.frame;

import dev.fieldframe.api.id.FfId;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Blocks a frame accepts inside its walls, and what each adds to the frame's shared storage. Addons register their
 * own (Java), and packs can change them through data ({@code data/<ns>/fieldframe/frame_components/*.json}) or
 * KubeJS; the frame adds every component inside it to its base capacity.
 */
public final class FrameComponents {
    private FrameComponents() {
    }

    /**
     * What one block adds: item slots to each of the three storages, mB to each of the three tanks, RF to the buffer,
     * and a processing speed bonus (added up, then applied to the frame's sockets). {@code role} is a free tag a
     * frame type can look for, e.g. {@code crucible} for the Foundry.
     */
    public record Component(int slotsPerLane, long mbPerTank, long rfCapacity, double speed, String role) {
        public Component {
            if (slotsPerLane < 0 || mbPerTank < 0 || rfCapacity < 0 || speed < 0) {
                throw new IllegalArgumentException("negative component values");
            }
            role = role == null ? "" : role;
        }
    }

    private static final Map<FfId, Component> REGISTERED = new LinkedHashMap<>();
    private static volatile Map<FfId, java.util.Optional<Component>> overrides = Map.of();

    /** Registers (or replaces) the component of a block id. */
    public static synchronized void register(FfId block, Component c) {
        REGISTERED.put(block, c);
    }

    /** The effective component of a block, or null when the block is not a frame component. */
    public static Component get(FfId block) {
        java.util.Optional<Component> o = overrides.get(block);
        if (o != null) {
            return o.orElse(null);
        }
        synchronized (FrameComponents.class) {
            return REGISTERED.get(block);
        }
    }

    public static synchronized Map<FfId, Component> registered() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(REGISTERED));
    }

    /**
     * Changes made after the datapack files on every data reload (Java addons; KubeJS {@code FieldframeEvents.frameComponents}).
     * {@code block} is a block id.
     */
    public interface Sink {
        void component(String block, int slotsPerLane, long mbPerTank, long rfCapacity, double speed, String role);

        void remove(String block);
    }

    private static final java.util.List<java.util.function.Consumer<Sink>> CONTRIBUTORS = new java.util.concurrent.CopyOnWriteArrayList<>();

    /** Adds a contributor that runs after the datapack files on every data reload. */
    public static void addContributor(java.util.function.Consumer<Sink> c) {
        CONTRIBUTORS.add(c);
    }

    public static java.util.List<java.util.function.Consumer<Sink>> contributors() {
        return java.util.List.copyOf(CONTRIBUTORS);
    }

    /** The data and script changes in effect (a null value: that block is no longer a component). */
    public static Map<FfId, Component> overrides() {
        Map<FfId, Component> m = new LinkedHashMap<>();
        overrides.forEach((k, v) -> m.put(k, v.orElse(null)));
        return m;
    }

    /** Every effective component: registered ones with the data and script changes applied. */
    public static Map<FfId, Component> effective() {
        Map<FfId, Component> m = new LinkedHashMap<>(registered());
        overrides.forEach((k, v) -> {
            if (v.isPresent()) {
                m.put(k, v.get());
            } else {
                m.remove(k);
            }
        });
        return Collections.unmodifiableMap(m);
    }

    /** Fieldframe internal: data and script changes; a null value removes that block's component. */
    public static void setOverrides(Map<FfId, Component> o) {
        Map<FfId, java.util.Optional<Component>> m = new LinkedHashMap<>();
        if (o != null) {
            o.forEach((k, v) -> m.put(k, java.util.Optional.ofNullable(v)));
        }
        overrides = Collections.unmodifiableMap(m);
    }
}
