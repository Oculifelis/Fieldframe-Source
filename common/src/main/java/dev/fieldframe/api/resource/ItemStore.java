package dev.fieldframe.api.resource;

/**
 * A conserving item store: every operation reports the amount that actually moved and never creates or
 * destroys items. Simulation never changes state. Used for chassis lanes (internal) and wrapped platform
 * inventories (external), so the same transfer code works for both.
 */
public interface ItemStore {
    int slots();

    /** Variant in the slot, or null when empty. */
    ItemVariant variant(int slot);

    int count(int slot);

    /** Inserts up to {@code amount} of {@code v}; returns how many were (or would be) accepted. */
    int insert(ItemVariant v, int amount, boolean simulate);

    /**
     * Inserts into one specific slot (for slot-addressed platform handlers). Default: slot-agnostic insert,
     * which stores must override if they can honour the slot.
     */
    default int insertSlot(int slot, ItemVariant v, int amount, boolean simulate) {
        return insert(v, amount, simulate);
    }

    /** Extracts up to {@code amount} from a slot; returns how many were (or would be) extracted. */
    int extractSlot(int slot, int amount, boolean simulate);

    /** Extracts up to {@code amount} of a specific variant across slots. */
    default int extract(ItemVariant v, int amount, boolean simulate) {
        int done = 0;
        for (int s = 0; s < slots() && done < amount; s++) {
            ItemVariant in = variant(s);
            if (in != null && in.sameAs(v)) {
                int left = amount - done;
                int avail = simulate ? Math.min(count(s), left) : extractSlot(s, left, false);
                done += avail;
            }
        }
        return done;
    }

    default long countOf(ItemVariant v) {
        long n = 0;
        for (int s = 0; s < slots(); s++) {
            ItemVariant in = variant(s);
            if (in != null && in.sameAs(v)) {
                n += count(s);
            }
        }
        return n;
    }

    default long countMatching(ItemFilter f, TagLookup tags) {
        long n = 0;
        for (int s = 0; s < slots(); s++) {
            ItemVariant in = variant(s);
            if (in != null && f.test(in, tags)) {
                n += count(s);
            }
        }
        return n;
    }

    default long totalCount() {
        long n = 0;
        for (int s = 0; s < slots(); s++) {
            n += count(s);
        }
        return n;
    }

    default boolean isEmpty() {
        return totalCount() == 0;
    }
}
