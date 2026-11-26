package dev.fieldframe.api.resource;

/** A conserving single-or-multi tank fluid store, amounts in millibuckets. */
public interface FluidStore {
    int tanks();

    FluidVariant fluid(int tank);

    long amount(int tank);

    long capacity(int tank);

    /** Returns the amount accepted. Never mixes fluid identities in one tank. */
    long fill(FluidVariant v, long amount, boolean simulate);

    /** Drains up to {@code amount} of {@code v} (or of anything when {@code v} is null). Returns amount drained. */
    long drain(FluidVariant v, long amount, boolean simulate);

    /** The variant a call to {@code drain(null, ...)} would drain, or null. */
    default FluidVariant drainableFluid() {
        for (int t = 0; t < tanks(); t++) {
            if (fluid(t) != null && amount(t) > 0) {
                return fluid(t);
            }
        }
        return null;
    }

    default long amountOf(FluidVariant v) {
        long n = 0;
        for (int t = 0; t < tanks(); t++) {
            if (fluid(t) != null && fluid(t).sameAs(v)) {
                n += amount(t);
            }
        }
        return n;
    }
}
