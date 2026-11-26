package dev.fieldframe.core.util;

import dev.fieldframe.api.module.ModuleContext;
import dev.fieldframe.api.resource.EnergyStore;
import dev.fieldframe.api.resource.FluidStore;
import dev.fieldframe.api.resource.FluidVariant;
import dev.fieldframe.api.resource.ItemFilter;
import dev.fieldframe.api.resource.ItemStore;
import dev.fieldframe.api.resource.ItemVariant;
import dev.fieldframe.api.resource.TagLookup;

/**
 * Conservative bounded transfers between stores that may be third-party and non-transactional.
 * Pattern: simulate destination, really extract from source (using the actual amount), really insert,
 * and return any remainder to the source; anything still unplaced is spilled, never discarded.
 * Reported amounts are clamped to [0, requested] so a misbehaving handler cannot mint resources.
 */
public final class Transfers {
    private Transfers() {
    }

    public static int clamp(int reported, int requested) {
        return Math.max(0, Math.min(reported, requested));
    }

    public static long clamp(long reported, long requested) {
        return Math.max(0, Math.min(reported, requested));
    }

    /** Result of a move: how many moved and the next round-robin slot to start from. */
    public record Moved(int amount, int nextCursor) {
    }

    /**
     * Moves up to {@code max} items of a single variant from {@code from} to {@code to}, scanning source slots
     * round-robin from {@code cursor}. Only one stack type moves per call (bounded work).
     */
    public static Moved moveItems(ItemStore from, ItemStore to, int max, ItemFilter filter, TagLookup tags, int cursor, ModuleContext spillCtx) {
        int slots = from.slots();
        if (slots <= 0 || max <= 0) {
            return new Moved(0, cursor);
        }
        int scan = Math.min(slots, 54);
        for (int i = 0; i < scan; i++) {
            int slot = Math.floorMod(cursor + i, slots);
            ItemVariant v = from.variant(slot);
            int have = from.count(slot);
            if (v == null || have <= 0 || !filter.test(v, tags)) {
                continue;
            }
            int want = Math.min(max, have);
            int room = clamp(to.insert(v, want, true), want);
            if (room <= 0) {
                continue;
            }
            int canTake = clamp(from.extractSlot(slot, room, true), room);
            if (canTake <= 0) {
                continue;
            }
            int taken = clamp(from.extractSlot(slot, canTake, false), canTake);
            if (taken <= 0) {
                continue;
            }
            int placed = clamp(to.insert(v, taken, false), taken);
            int rest = taken - placed;
            if (rest > 0) {
                int back = clamp(from.insert(v, rest, false), rest);
                rest -= back;
                if (rest > 0 && spillCtx != null) {
                    spillCtx.spill(v, rest);
                }
            }
            return new Moved(placed, Math.floorMod(slot + 1, slots));
        }
        return new Moved(0, cursor);
    }

    /** Moves fluid from {@code from} to {@code to}, preserving identity. Returns mB moved. */
    public static long moveFluid(FluidStore from, FluidStore to, long max, dev.fieldframe.api.resource.FluidFilter filter, TagLookup tags, ModuleContext spillCtx) {
        if (max <= 0) {
            return 0;
        }
        for (int t = 0; t < from.tanks(); t++) {
            FluidVariant v = from.fluid(t);
            if (v == null || from.amount(t) <= 0 || !filter.test(v, tags)) {
                continue;
            }
            long want = Math.min(max, from.amount(t));
            long room = clamp(to.fill(v, want, true), want);
            if (room <= 0) {
                continue;
            }
            long canTake = clamp(from.drain(v, room, true), room);
            if (canTake <= 0) {
                continue;
            }
            long taken = clamp(from.drain(v, canTake, false), canTake);
            long placed = clamp(to.fill(v, taken, false), taken);
            long rest = taken - placed;
            if (rest > 0) {
                rest -= clamp(from.fill(v, rest, false), rest);
                if (rest > 0 && spillCtx != null) {
                    spillCtx.spillFluid(v, rest);
                }
            }
            return placed;
        }
        return 0;
    }

    /** RF move with the same conservative pattern. Energy that cannot be returned is reported (and counted lost). */
    public static long moveEnergy(EnergyStore from, EnergyStore to, long max) {
        if (max <= 0) {
            return 0;
        }
        long room = clamp(to.receive(max, true), max);
        if (room <= 0) {
            return 0;
        }
        long avail = clamp(from.extract(room, true), room);
        if (avail <= 0) {
            return 0;
        }
        long taken = clamp(from.extract(avail, false), avail);
        long placed = clamp(to.receive(taken, false), taken);
        if (placed < taken) {
            from.receive(taken - placed, false);
        }
        return placed;
    }
}
