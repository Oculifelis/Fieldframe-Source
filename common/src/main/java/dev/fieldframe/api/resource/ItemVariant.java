package dev.fieldframe.api.resource;

import dev.fieldframe.api.id.FfId;

/**
 * An item identity without a count. Two variants are the same resource when {@link #id()} and
 * {@link #componentKey()} are equal. The component key is a canonical string of the item's
 * data components / NBT produced by the platform adapter; an empty key means "no extra data".
 *
 * <p>This single definition of identity is shared by lane storage, filters, recipe matching
 * and blueprint comparison (see docs/design.md, "Matching policy").
 */
public interface ItemVariant {
    FfId id();

    String componentKey();

    int maxStackSize();

    default boolean sameAs(ItemVariant other) {
        return other != null && id().equals(other.id()) && componentKey().equals(other.componentKey());
    }

    /** A simple variant used by tests, simulations and the manual test bench. */
    record Simple(FfId id, String componentKey, int maxStackSize) implements ItemVariant {
        public Simple {
            if (maxStackSize < 1 || maxStackSize > 99) {
                throw new IllegalArgumentException("maxStackSize");
            }
            componentKey = componentKey == null ? "" : componentKey;
        }

        public static Simple of(String id) {
            return new Simple(FfId.parse(id), "", 64);
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof ItemVariant v && sameAs(v);
        }

        @Override
        public int hashCode() {
            return id.hashCode() * 31 + componentKey.hashCode();
        }
    }
}
