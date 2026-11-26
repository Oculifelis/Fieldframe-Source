package dev.fieldframe.core.chassis;

import dev.fieldframe.api.module.BindingSpec;
import dev.fieldframe.api.resource.FluidVariant;
import dev.fieldframe.api.resource.ItemVariant;
import dev.fieldframe.core.storage.FluidReservoir;
import dev.fieldframe.core.storage.ItemLane;

import java.util.ArrayList;
import java.util.List;

/**
 * Bolted Chassis: two to {@value #MAX_PARTS} touching chassis bolted into one machine. The parts share one set of
 * Orange, Green and Purple storages and tanks, one RF buffer and the control channels, all owned by the lead part
 * (the first chassis); capacities are the sum of what each part brings (its spec, tier and service bays), and each
 * part keeps its own six sides, modules and per-tick allowances, so throughput adds up too.
 *
 * <p>Conservation: bolting moves the new part's contents into the shared stores (refused when a tank holds a
 * different fluid); unbolting gives the leaving part empty stores and refuses when the rest would not fit the smaller
 * machine; {@link #breakPart} (a part destroyed) shrinks the shared stores and returns the items that no longer fit
 * so the platform drops them. Only the lead saves the shared contents; other parts save empty stores and a pointer
 * to the lead, and the machine stays paused until every part is loaded and linked again.
 *
 * <p>Positions are the platform's business: each core keeps an opaque {@link ChassisCore#boltInfo()} the platform
 * fills with the other parts' offsets.
 */
public final class BoltGroup {
    public static final int MAX_PARTS = 8;

    private final ChassisCore lead;
    private final List<ChassisCore> parts = new ArrayList<>();
    /** Parts the machine has when complete (lead included); the machine pauses while fewer are linked. */
    private int expected;

    private BoltGroup(ChassisCore lead, int expected) {
        this.lead = lead;
        this.parts.add(lead);
        this.expected = Math.max(1, expected);
    }

    public ChassisCore lead() {
        return lead;
    }

    public List<ChassisCore> parts() {
        return List.copyOf(parts);
    }

    public int expected() {
        return expected;
    }

    /** True when every part is linked; the machine only runs then. */
    public boolean complete() {
        return parts.size() >= expected;
    }

    // ------------------------------------------------------------------ bolting

    /**
     * Bolts {@code joiner} (a standalone chassis) onto the machine {@code onto} belongs to, or starts a machine with
     * {@code onto} as its lead. Its contents move into the shared stores.
     */
    public static ChassisCore.Result bolt(ChassisCore onto, ChassisCore joiner) {
        if (onto == joiner) {
            return ChassisCore.Result.fail("BOLT_SELF", "");
        }
        if (joiner.group() != null || joiner.isAwaitingLink() || joiner.storesBorrowed()) {
            return ChassisCore.Result.fail("ALREADY_BOLTED", "");
        }
        if (onto.group() == null && onto.storesBorrowed()) {
            return ChassisCore.Result.fail("ALREADY_BOLTED", "");
        }
        if (onto.isAwaitingLink()) {
            return ChassisCore.Result.fail("PARTS_MISSING", "");
        }
        BoltGroup g = onto.group() != null ? onto.group() : new BoltGroup(onto, 1);
        if (!g.complete()) {
            return ChassisCore.Result.fail("PARTS_MISSING", "");
        }
        if (g.parts.size() >= MAX_PARTS) {
            return ChassisCore.Result.fail("MACHINE_TOO_BIG", String.valueOf(MAX_PARTS));
        }
        ChassisCore lead = g.lead;
        for (int i = 0; i < 3; i++) {
            FluidVariant mine = lead.tank(i).fluid();
            FluidVariant theirs = joiner.tank(i).fluid();
            if (mine != null && theirs != null && !mine.sameAs(theirs)) {
                return ChassisCore.Result.fail("TANK_MISMATCH", BindingSpec.tankName(i));
            }
        }
        boolean fresh = onto.group() == null;
        g.parts.add(joiner);
        g.expected++;
        ChassisCore.Result r = g.resizeShared();
        if (!r.ok()) {
            g.parts.remove(joiner);
            g.expected--;
            g.resizeShared();
            return r;
        }
        // Move the joiner's contents in (they always fit: the machine just grew by at least what it held).
        for (int i = 0; i < 3; i++) {
            ItemLane from = joiner.lane(i);
            for (int s = 0; s < from.slots(); s++) {
                ItemVariant v = from.variant(s);
                int n = from.count(s);
                if (v != null && n > 0) {
                    int moved = lead.lane(i).insert(v, n, false);
                    from.extractSlot(s, moved, false);
                }
            }
            FluidReservoir tf = joiner.tank(i);
            if (tf.fluid() != null && tf.amount() > 0) {
                long moved = lead.tank(i).fill(tf.fluid(), tf.amount(), false);
                tf.drain(tf.fluid(), moved, false);
            }
        }
        long rf = joiner.energy().stored();
        joiner.energy().extract(lead.energy().receive(rf, false), false);
        List<Object[]> leftovers = joiner.drainOwnStores();
        if (fresh) {
            lead.setGroup(g);
            g.wire();
        }
        joiner.shareStoresOf(lead);
        joiner.setGroup(g);
        joiner.pendingSpill().addAll(leftovers);
        g.changedAll();
        return ChassisCore.Result.success();
    }

    /**
     * Takes {@code part} out of its machine (a part that is not the lead) or, for the lead, splits the whole machine
     * up. The leaving parts get empty stores of their own; refused when what is stored would not fit the machine that
     * remains (it says which storage to empty).
     */
    public static ChassisCore.Result unbolt(ChassisCore part) {
        BoltGroup g = part.group();
        if (g == null) {
            return ChassisCore.Result.fail("NOT_BOLTED", "");
        }
        if (!g.complete()) {
            return ChassisCore.Result.fail("PARTS_MISSING", "");
        }
        if (part == g.lead) {
            ChassisCore.Caps own = g.lead.ownCapacities();
            ChassisCore.Result fits = ChassisCore.checkFits(g.lead.lanesArray(), g.lead.tanksArray(), g.lead.energy(), own);
            if (!fits.ok()) {
                return fits;
            }
            for (ChassisCore p : List.copyOf(g.parts)) {
                if (p != g.lead) {
                    g.release(p);
                }
            }
            g.dissolve();
            return ChassisCore.Result.success();
        }
        ChassisCore.Caps rest = g.capsWithout(part);
        ChassisCore.Result fits = ChassisCore.checkFits(g.lead.lanesArray(), g.lead.tanksArray(), g.lead.energy(), rest);
        if (!fits.ok()) {
            return fits;
        }
        g.release(part);
        g.resizeShared();
        if (g.parts.size() == 1) {
            g.dissolve();
        }
        g.changedAll();
        return ChassisCore.Result.success();
    }

    /**
     * A part is being destroyed. A non-lead part leaves and the shared stores shrink; items that no longer fit are
     * returned (to drop where the part was), fluid and RF beyond the new capacity are lost, as when a chassis breaks.
     * When the lead is destroyed every other part becomes a standalone chassis with empty stores and the lead keeps
     * everything (its block drops the items).
     */
    public static List<Object[]> breakPart(ChassisCore part) {
        BoltGroup g = part.group();
        List<Object[]> spill = new ArrayList<>();
        if (g == null) {
            return spill;
        }
        if (part == g.lead) {
            for (ChassisCore p : List.copyOf(g.parts)) {
                if (p != g.lead) {
                    g.release(p);
                }
            }
            g.dissolve();
            return spill;
        }
        g.release(part);
        ChassisCore.Caps caps = g.complete() ? g.sumCaps() : null;
        if (caps != null) {
            spill.addAll(ChassisCore.forceFit(g.lead.lanesArray(), g.lead.tanksArray(), g.lead.energy(), caps));
            g.resizeShared();
        }
        if (g.parts.size() == 1 && g.expected <= 1) {
            g.dissolve();
        }
        g.changedAll();
        return spill;
    }

    // ------------------------------------------------------------------ loading and unloading

    /**
     * After loading: links {@code part} (saved with empty stores and a pointer to {@code lead}) back into the machine
     * whose lead saved {@code expected} parts. The machine runs again once all parts are linked.
     */
    public static ChassisCore.Result relink(ChassisCore lead, ChassisCore part, int expected) {
        if (lead == part) {
            return ChassisCore.Result.fail("BOLT_SELF", "");
        }
        BoltGroup g = lead.group();
        if (g == null) {
            g = new BoltGroup(lead, expected);
            lead.setGroup(g);
            g.wire();
        } else if (g.lead != lead) {
            return ChassisCore.Result.fail("NOT_LEAD", "");
        }
        if (g.parts.contains(part)) {
            return ChassisCore.Result.success();
        }
        if (g.parts.size() >= g.expected) {
            return ChassisCore.Result.fail("MACHINE_TOO_BIG", String.valueOf(g.expected));
        }
        part.pendingSpill().addAll(part.drainOwnStores());
        part.shareStoresOf(lead);
        part.setGroup(g);
        g.parts.add(part);
        if (g.complete()) {
            for (ChassisCore p : g.parts) {
                p.setAwaitingLink(false);
            }
        }
        g.changedAll();
        return ChassisCore.Result.success();
    }

    /**
     * A part's block entity is unloading: the machine pauses until it is back. When the lead unloads, the other parts
     * go back to empty stores of their own and wait for it (their contents live in the lead).
     */
    public static void unload(ChassisCore part) {
        BoltGroup g = part.group();
        if (g == null) {
            return;
        }
        if (part == g.lead) {
            for (ChassisCore p : List.copyOf(g.parts)) {
                if (p != g.lead) {
                    g.parts.remove(p);
                    p.setGroup(null);
                    p.ownStores();
                    p.setAwaitingLink(true);
                }
            }
            g.lead.setGroup(null);
            g.lead.setAwaitingLink(true);
            return;
        }
        g.parts.remove(part);
        part.setGroup(null);
        part.ownStores();
        part.setAwaitingLink(true);
        for (ChassisCore p : g.parts) {
            p.setAwaitingLink(true);
        }
    }

    /** Forgets a part that will never come back (its block is gone): the machine shrinks; see {@link #breakPart}. */
    public static List<Object[]> forgetMissing(ChassisCore lead, int expected) {
        BoltGroup g = lead.group();
        if (g == null || g.lead != lead) {
            return new ArrayList<>();
        }
        g.expected = Math.max(g.parts.size(), Math.min(g.expected, expected));
        List<Object[]> spill = new ArrayList<>();
        if (g.complete()) {
            spill.addAll(ChassisCore.forceFit(lead.lanesArray(), lead.tanksArray(), lead.energy(), g.sumCaps()));
            g.resizeShared();
            for (ChassisCore p : g.parts) {
                p.setAwaitingLink(false);
            }
            if (g.parts.size() == 1) {
                g.dissolve();
            }
        }
        g.changedAll();
        return spill;
    }

    // ------------------------------------------------------------------ internals

    private void release(ChassisCore p) {
        parts.remove(p);
        expected = Math.max(1, expected - 1);
        p.setGroup(null);
        p.ownStores();
        p.setAwaitingLink(false);
        p.changed();
    }

    private void dissolve() {
        lead.setGroup(null);
        lead.rewireOwnStores();
        lead.setAwaitingLink(false);
        expected = 1;
        lead.reapplyCapacities();
        lead.changed();
    }

    /** Shared stores report changes to every part, so sleeping modules on any side wake up. */
    private void wire() {
        Runnable r = this::contentsChanged;
        for (int i = 0; i < 3; i++) {
            lead.lane(i).setOnChange(r);
            lead.tank(i).setOnChange(r);
        }
        lead.energy().setOnChange(lead::markDirtyOnly);
    }

    private void contentsChanged() {
        for (ChassisCore p : parts) {
            p.onSharedContentsChanged();
        }
    }

    private void changedAll() {
        for (ChassisCore p : parts) {
            p.changed();
        }
    }

    ChassisCore.Caps sumCaps() {
        int slots = 0;
        long fluid = 0;
        long energy = 0;
        for (ChassisCore p : parts) {
            ChassisCore.Caps c = p.ownCapacities();
            slots += c.slots();
            fluid += c.fluid();
            energy += c.energy();
        }
        return new ChassisCore.Caps(Math.min(slots, ItemLane.MAX_SLOTS), fluid, energy);
    }

    private ChassisCore.Caps capsWithout(ChassisCore leaving) {
        ChassisCore.Caps all = sumCaps();
        ChassisCore.Caps l = leaving.ownCapacities();
        return new ChassisCore.Caps(Math.max(1, Math.min(ItemLane.MAX_SLOTS, all.slots() - l.slots())), all.fluid() - l.fluid(), all.energy() - l.energy());
    }

    /** Resizes the shared stores to the sum of every part's capacities; refuses a shrink that would truncate. */
    ChassisCore.Result resizeShared() {
        if (!complete()) {
            return ChassisCore.Result.fail("PARTS_MISSING", "");
        }
        return ChassisCore.resizeStores(lead.lanesArray(), lead.tanksArray(), lead.energy(), sumCaps());
    }
}
