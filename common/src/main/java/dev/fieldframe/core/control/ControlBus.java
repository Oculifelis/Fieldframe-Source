package dev.fieldframe.core.control;

import dev.fieldframe.api.chassis.ChassisSpec;
import dev.fieldframe.api.data.FfData;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The chassis' eight internal control channels (values 0..15), double-buffered.
 *
 * <p>Semantics ("driven bus"): during tick N every module reads the snapshot taken at the start of tick N.
 * Writes during tick N go to the pending buffer; when several modules drive the same channel the highest value
 * wins (like several redstone sources on one line). At the start of tick N+1 the pending buffer becomes the
 * snapshot and is cleared, so an undriven channel reads 0. Feedback therefore always crosses a tick boundary
 * and can never recurse within a tick.
 */
public final class ControlBus {
    private int[] snapshot = new int[ChassisSpec.CHANNELS];
    private int[] pending = new int[ChassisSpec.CHANNELS];

    private long lastBegin = Long.MIN_VALUE;

    /**
     * Starts game tick {@code now} once, however many chassis share this bus (Bolted Chassis): the first call in a
     * tick flips the buffers, later calls in the same tick do nothing.
     */
    public void beginTick(long now) {
        if (now == lastBegin) {
            return;
        }
        lastBegin = now;
        beginTick();
    }

    public void beginTick() {
        int[] t = snapshot;
        snapshot = pending;
        pending = t;
        Arrays.fill(pending, 0);
    }

    public int read(int channel) {
        return channel >= 0 && channel < snapshot.length ? snapshot[channel] : 0;
    }

    public void drive(int channel, int value) {
        if (channel < 0 || channel >= pending.length) {
            return;
        }
        int v = Math.max(0, Math.min(15, value));
        if (v > pending[channel]) {
            pending[channel] = v;
        }
    }

    public int[] snapshotCopy() {
        return snapshot.clone();
    }

    public FfData save() {
        List<Object> s = new ArrayList<>();
        List<Object> p = new ArrayList<>();
        for (int i = 0; i < snapshot.length; i++) {
            s.add((long) snapshot[i]);
            p.add((long) pending[i]);
        }
        return new FfData().putList("snapshot", s).putList("pending", p);
    }

    public void load(FfData d) {
        readInto(d.getList("snapshot"), snapshot);
        readInto(d.getList("pending"), pending);
    }

    private static void readInto(List<Object> list, int[] out) {
        Arrays.fill(out, 0);
        for (int i = 0; i < Math.min(list.size(), out.length); i++) {
            if (list.get(i) instanceof Long l) {
                out[i] = (int) Math.max(0, Math.min(15, l));
            }
        }
    }
}
