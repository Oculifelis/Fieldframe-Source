package dev.fieldframe.core.util;

import dev.fieldframe.api.resource.ItemFilter;
import dev.fieldframe.api.resource.ItemStore;
import dev.fieldframe.api.resource.ItemVariant;
import dev.fieldframe.api.resource.TagLookup;

/** A directional, filtered view over a lane, used for passive face exposure and access panels. */
public final class FilteredItemStore implements ItemStore {
    private final ItemStore inner;
    private final boolean allowInsert;
    private final boolean allowExtract;
    private final ItemFilter filter;
    private final TagLookup tags;

    public FilteredItemStore(ItemStore inner, boolean allowInsert, boolean allowExtract, ItemFilter filter, TagLookup tags) {
        this.inner = inner;
        this.allowInsert = allowInsert;
        this.allowExtract = allowExtract;
        this.filter = filter;
        this.tags = tags;
    }

    @Override
    public int slots() {
        return inner.slots();
    }

    @Override
    public ItemVariant variant(int slot) {
        return inner.variant(slot);
    }

    @Override
    public int count(int slot) {
        return inner.count(slot);
    }

    @Override
    public int insert(ItemVariant v, int amount, boolean simulate) {
        if (!allowInsert || v == null || !filter.test(v, tags)) {
            return 0;
        }
        return inner.insert(v, amount, simulate);
    }

    @Override
    public int insertSlot(int slot, ItemVariant v, int amount, boolean simulate) {
        if (!allowInsert || v == null || !filter.test(v, tags)) {
            return 0;
        }
        return inner.insertSlot(slot, v, amount, simulate);
    }

    @Override
    public int extractSlot(int slot, int amount, boolean simulate) {
        ItemVariant v = inner.variant(slot);
        if (!allowExtract || v == null || !filter.test(v, tags)) {
            return 0;
        }
        return inner.extractSlot(slot, amount, simulate);
    }
}
