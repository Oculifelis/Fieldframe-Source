package dev.fieldframe.api.resource;

/** A conserving RF store. Amounts are RF (1 RF = 1 native FE). */
public interface EnergyStore {
    long stored();

    long capacity();

    long receive(long amount, boolean simulate);

    long extract(long amount, boolean simulate);

    default boolean canReceive() {
        return true;
    }

    default boolean canExtract() {
        return true;
    }
}
