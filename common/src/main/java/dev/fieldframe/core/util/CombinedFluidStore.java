package dev.fieldframe.core.util;

import dev.fieldframe.api.resource.FluidStore;
import dev.fieldframe.api.resource.FluidVariant;

import java.util.List;

/** Several fluid stores seen as one: fills go to the first store that accepts, drains take from each in turn. */
public final class CombinedFluidStore implements FluidStore {
    private final List<FluidStore> parts;

    public CombinedFluidStore(List<FluidStore> parts) {
        this.parts = List.copyOf(parts);
    }

    private int[] locate(int tank) {
        int base = 0;
        for (int i = 0; i < parts.size(); i++) {
            int n = parts.get(i).tanks();
            if (tank < base + n) {
                return new int[] {i, tank - base};
            }
            base += n;
        }
        throw new IndexOutOfBoundsException(tank);
    }

    @Override
    public int tanks() {
        int n = 0;
        for (FluidStore p : parts) {
            n += p.tanks();
        }
        return n;
    }

    @Override
    public FluidVariant fluid(int tank) {
        int[] l = locate(tank);
        return parts.get(l[0]).fluid(l[1]);
    }

    @Override
    public long amount(int tank) {
        int[] l = locate(tank);
        return parts.get(l[0]).amount(l[1]);
    }

    @Override
    public long capacity(int tank) {
        int[] l = locate(tank);
        return parts.get(l[0]).capacity(l[1]);
    }

    @Override
    public long fill(FluidVariant v, long amount, boolean simulate) {
        // Prefer a store that already holds this fluid, so buckets top up instead of splitting.
        for (FluidStore p : parts) {
            if (p.amountOf(v) > 0) {
                long in = Transfers.clamp(p.fill(v, amount, simulate), amount);
                if (in > 0) {
                    return in;
                }
            }
        }
        for (FluidStore p : parts) {
            long in = Transfers.clamp(p.fill(v, amount, simulate), amount);
            if (in > 0) {
                return in;
            }
        }
        return 0;
    }

    @Override
    public long drain(FluidVariant v, long amount, boolean simulate) {
        for (FluidStore p : parts) {
            long out = Transfers.clamp(p.drain(v, amount, simulate), amount);
            if (out > 0) {
                return out;
            }
        }
        return 0;
    }
}
