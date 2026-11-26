package dev.fieldframe.api.resource;

import dev.fieldframe.api.id.FfId;

/** A fluid identity without an amount; equality uses id + component key, like {@link ItemVariant}. */
public interface FluidVariant {
    FfId id();

    String componentKey();

    default boolean sameAs(FluidVariant other) {
        return other != null && id().equals(other.id()) && componentKey().equals(other.componentKey());
    }

    record Simple(FfId id, String componentKey) implements FluidVariant {
        public Simple {
            componentKey = componentKey == null ? "" : componentKey;
        }

        public static Simple of(String id) {
            return new Simple(FfId.parse(id), "");
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof FluidVariant v && sameAs(v);
        }

        @Override
        public int hashCode() {
            return id.hashCode() * 31 + componentKey.hashCode();
        }
    }
}
