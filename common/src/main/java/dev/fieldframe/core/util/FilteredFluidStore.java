package dev.fieldframe.core.util;

import dev.fieldframe.api.resource.FluidFilter;
import dev.fieldframe.api.resource.FluidStore;
import dev.fieldframe.api.resource.FluidVariant;
import dev.fieldframe.api.resource.TagLookup;

/** Directional, filtered view over a reservoir. */
public final class FilteredFluidStore implements FluidStore {
    private final FluidStore inner;
    private final boolean allowFill;
    private final boolean allowDrain;
    private final FluidFilter filter;
    private final TagLookup tags;

    public FilteredFluidStore(FluidStore inner, boolean allowFill, boolean allowDrain, FluidFilter filter, TagLookup tags) {
        this.inner = inner;
        this.allowFill = allowFill;
        this.allowDrain = allowDrain;
        this.filter = filter;
        this.tags = tags;
    }

    @Override
    public int tanks() {
        return inner.tanks();
    }

    @Override
    public FluidVariant fluid(int tank) {
        return inner.fluid(tank);
    }

    @Override
    public long amount(int tank) {
        return inner.amount(tank);
    }

    @Override
    public long capacity(int tank) {
        return inner.capacity(tank);
    }

    @Override
    public long fill(FluidVariant v, long amount, boolean simulate) {
        if (!allowFill || v == null || !filter.test(v, tags)) {
            return 0;
        }
        return inner.fill(v, amount, simulate);
    }

    @Override
    public long drain(FluidVariant v, long amount, boolean simulate) {
        if (!allowDrain) {
            return 0;
        }
        FluidVariant present = inner.drainableFluid();
        if (present == null || !filter.test(present, tags) || (v != null && !v.sameAs(present))) {
            return 0;
        }
        return inner.drain(present, amount, simulate);
    }
}
