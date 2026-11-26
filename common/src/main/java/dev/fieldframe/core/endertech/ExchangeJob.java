package dev.fieldframe.core.endertech;

import dev.fieldframe.api.face.WorldDir;

/**
 * One queued Exchanger use: rings of growing radius around the clicked block, one ring every
 * {@link EnderTechRules#EXCHANGER_TICKS_PER_RING} ticks, in the plane of the clicked face. Transient (like the
 * original): a job ends when its owner logs out, stops holding the Exchanger, or a swap reports STOP.
 */
public final class ExchangeJob {
    public enum Swap {
        /** Swapped (or harmlessly skipped); keep going. */
        DONE,
        /** This position does not qualify; keep going. */
        SKIP,
        /** Out of energy, replacement blocks or inventory space: end the job. */
        STOP
    }

    @FunctionalInterface
    public interface Swapper {
        /** Attempts one swap at origin + offset on ring {@code ring} (1-based). */
        Swap swap(int dx, int dy, int dz, int ring);
    }

    private final WorldDir normal;
    private final int radius;
    private int ring = 1;
    private int countdown = 0;
    private boolean finished;
    private int swapped;

    public ExchangeJob(WorldDir normal, int radius) {
        this.normal = normal;
        this.radius = EnderTechRules.clampRadius(radius);
    }

    /** Advances one tick; performs at most one ring of swaps. */
    public void tick(Swapper swapper) {
        if (finished) {
            return;
        }
        if (countdown > 0) {
            countdown--;
            return;
        }
        for (int[] o : EnderTechRules.ring(normal, ring)) {
            Swap s = swapper.swap(o[0], o[1], o[2], ring);
            if (s == Swap.DONE) {
                swapped++;
            } else if (s == Swap.STOP) {
                finished = true;
                return;
            }
        }
        ring++;
        if (ring > radius) {
            finished = true;
        } else {
            countdown = EnderTechRules.EXCHANGER_TICKS_PER_RING - 1;
        }
    }

    public boolean finished() {
        return finished;
    }

    public void cancel() {
        finished = true;
    }

    public int swapped() {
        return swapped;
    }

    public int currentRing() {
        return ring;
    }
}
