package dev.fieldframe.core.storage;

import dev.fieldframe.api.data.FfData;
import dev.fieldframe.api.energy.Energy;
import dev.fieldframe.api.resource.EnergyStore;

/** The chassis' single authoritative RF buffer. Overflow-safe; simulation never changes state. */
public final class EnergyBuffer implements EnergyStore {
    private long stored;
    private long capacity;
    private Runnable onChange = () -> { };

    public EnergyBuffer(long capacity) {
        if (capacity < 0) {
            throw new IllegalArgumentException("capacity");
        }
        this.capacity = capacity;
    }

    public void setOnChange(Runnable r) {
        this.onChange = r == null ? () -> { } : r;
    }

    @Override
    public long stored() {
        return stored;
    }

    @Override
    public long capacity() {
        return capacity;
    }

    @Override
    public long receive(long amount, boolean simulate) {
        if (amount <= 0) {
            return 0;
        }
        long moved = Math.min(amount, capacity - stored);
        if (moved <= 0) {
            return 0;
        }
        if (!simulate) {
            stored = Energy.saturatingAdd(stored, moved);
            onChange.run();
        }
        return moved;
    }

    @Override
    public long extract(long amount, boolean simulate) {
        if (amount <= 0) {
            return 0;
        }
        long moved = Math.min(amount, stored);
        if (!simulate && moved > 0) {
            stored -= moved;
            onChange.run();
        }
        return moved;
    }

    /** All-or-nothing consumption used by processors. */
    public boolean consumeExactly(long amount) {
        if (amount < 0 || amount > stored) {
            return false;
        }
        if (amount > 0) {
            stored -= amount;
            onChange.run();
        }
        return true;
    }

    public long[] snapshot() {
        return new long[]{stored, capacity};
    }

    public void restore(long[] s) {
        stored = s[0];
        capacity = s[1];
        onChange.run();
    }

    public boolean setCapacity(long newCap) {
        if (newCap < stored || newCap < 0) {
            return false;
        }
        capacity = newCap;
        onChange.run();
        return true;
    }

    public FfData save() {
        return new FfData().putLong("stored", stored).putLong("capacity", capacity);
    }

    public void load(FfData d) {
        capacity = Math.max(0, d.getLong("capacity", capacity));
        stored = Math.max(0, Math.min(capacity, d.getLong("stored", 0)));
    }
}
