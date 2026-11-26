package dev.fieldframe.core.storage;

import dev.fieldframe.api.data.FfData;
import dev.fieldframe.api.resource.FluidStore;
import dev.fieldframe.api.resource.FluidVariant;
import dev.fieldframe.api.resource.VariantCodec;

/** One named fluid reservoir (A/B/C). Holds exactly one fluid identity at a time. */
public final class FluidReservoir implements FluidStore {
    private final String name;
    private FluidVariant fluid;
    private long amount;
    private long capacity;
    private Runnable onChange = () -> { };

    public FluidReservoir(String name, long capacity) {
        this.name = name;
        this.capacity = capacity;
    }

    public String name() {
        return name;
    }

    public void setOnChange(Runnable r) {
        this.onChange = r == null ? () -> { } : r;
    }

    public FluidVariant fluid() {
        return fluid;
    }

    public long amount() {
        return amount;
    }

    public long capacity() {
        return capacity;
    }

    @Override
    public int tanks() {
        return 1;
    }

    @Override
    public FluidVariant fluid(int tank) {
        return tank == 0 ? fluid : null;
    }

    @Override
    public long amount(int tank) {
        return tank == 0 ? amount : 0;
    }

    @Override
    public long capacity(int tank) {
        return tank == 0 ? capacity : 0;
    }

    @Override
    public long fill(FluidVariant v, long req, boolean simulate) {
        if (v == null || req <= 0) {
            return 0;
        }
        if (fluid != null && !fluid.sameAs(v)) {
            return 0;
        }
        long moved = Math.min(req, capacity - amount);
        if (moved <= 0) {
            return 0;
        }
        if (!simulate) {
            fluid = v;
            amount += moved;
            onChange.run();
        }
        return moved;
    }

    @Override
    public long drain(FluidVariant v, long req, boolean simulate) {
        if (fluid == null || req <= 0 || (v != null && !fluid.sameAs(v))) {
            return 0;
        }
        long moved = Math.min(req, amount);
        if (!simulate && moved > 0) {
            amount -= moved;
            if (amount == 0) {
                fluid = null;
            }
            onChange.run();
        }
        return moved;
    }

    public Object snapshot() {
        return new Object[]{fluid, amount, capacity};
    }

    public void restore(Object snapshot) {
        Object[] s = (Object[]) snapshot;
        fluid = (FluidVariant) s[0];
        amount = (Long) s[1];
        capacity = (Long) s[2];
        onChange.run();
    }

    /** Refuses a capacity reduction that would discard stored fluid. */
    public boolean setCapacity(long newCap) {
        if (newCap < amount || newCap < 0) {
            return false;
        }
        capacity = newCap;
        onChange.run();
        return true;
    }

    public FfData save(VariantCodec codec) {
        FfData d = new FfData().putLong("capacity", capacity);
        if (fluid != null) {
            d.put("fluid", codec.encodeFluid(fluid)).putLong("amount", amount);
        }
        return d;
    }

    public void load(FfData d, VariantCodec codec) {
        capacity = Math.max(0, d.getLong("capacity", capacity));
        FfData f = d.getDataOrNull("fluid");
        fluid = f == null ? null : codec.decodeFluid(f);
        amount = fluid == null ? 0 : Math.max(0, Math.min(capacity, d.getLong("amount", 0)));
        if (amount == 0) {
            fluid = null;
        }
    }
}
