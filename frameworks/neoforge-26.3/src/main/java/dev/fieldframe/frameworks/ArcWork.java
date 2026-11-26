package dev.fieldframe.frameworks;

import dev.fieldframe.api.id.FfId;
import dev.fieldframe.api.process.ProcessRecipe;
import dev.fieldframe.api.resource.ItemStore;
import dev.fieldframe.api.resource.ItemVariant;
import dev.fieldframe.api.resource.TagLookup;

import java.util.ArrayList;
import java.util.List;
import java.util.function.DoubleSupplier;
import java.util.function.Function;

/**
 * What one arc of the Arc Furnace does, loader-independent (unit tested): up to {@code budget} input items from
 * {@code in} (Orange storage) are worked into {@code out} (Green storage). Arc alloys go first, in their order, then
 * everything with a heating (smelting) result, slot by slot. It conserves: inputs are taken only once their outputs are
 * in {@code out}; outputs that do not fit leave their inputs where they are. A chance by-product is made only when it
 * fits (its inputs are already paid for by the main result).
 */
final class ArcWork {
    private ArcWork() {
    }

    /** Alloy batches and items smelted, input items taken and items made. */
    record Result(int batches, int smelted, int itemsIn, int itemsOut) {
        static final Result NONE = new Result(0, 0, 0, 0);

        boolean any() {
            return itemsIn > 0;
        }
    }

    static Result run(ItemStore in, ItemStore out, List<ArcAlloys.Alloy> alloys, List<ProcessRecipe> heating, TagLookup tags,
                      Function<FfId, ItemVariant> items, int budget, DoubleSupplier rng) {
        int batches = 0;
        int smelted = 0;
        int itemsIn = 0;
        int itemsOut = 0;
        for (ArcAlloys.Alloy a : alloys) {
            int cost = a.inputCount();
            if (budget < cost) {
                continue;
            }
            ItemVariant rv = items.apply(a.result().id());
            if (rv == null) {
                continue;
            }
            int per = a.result().count();
            int k = budget / cost;
            for (ArcAlloys.Part p : a.inputs()) {
                k = (int) Math.min(k, countMatching(in, p, tags) / p.count());
            }
            k = Math.min(k, out.insert(rv, per * k, true) / per);
            int[] take = null;
            while (k > 0 && (take = allocate(in, a, tags, k)) == null) {
                k--;
            }
            if (k <= 0) {
                continue;
            }
            int put = out.insert(rv, per * k, false);
            if (put < per * k) {
                int k2 = put / per;
                int extra = put - k2 * per;
                if (extra > 0) {
                    out.extract(rv, extra, false);
                }
                k = k2;
                if (k <= 0) {
                    continue;
                }
                take = allocate(in, a, tags, k);
            }
            for (int s = 0; s < take.length; s++) {
                if (take[s] > 0) {
                    in.extractSlot(s, take[s], false);
                }
            }
            budget -= k * cost;
            batches += k;
            itemsIn += k * cost;
            itemsOut += k * per;
        }
        for (int s = 0; s < in.slots() && budget > 0; s++) {
            ItemVariant v = in.variant(s);
            if (v == null) {
                continue;
            }
            ProcessRecipe r = smeltOf(v, heating, tags);
            if (r == null) {
                continue;
            }
            int n = r.itemInputs().get(0).count();
            int k = Math.min(in.count(s) / n, budget / n);
            List<ProcessRecipe.ItemResult> sure = new ArrayList<>();
            List<ProcessRecipe.ItemResult> chance = new ArrayList<>();
            for (ProcessRecipe.ItemResult res : r.itemResults()) {
                (res.chance() >= 1 ? sure : chance).add(res);
            }
            if (k <= 0 || sure.isEmpty()) {
                continue;
            }
            k = commit(out, sure, k);
            if (k <= 0) {
                continue;
            }
            in.extractSlot(s, k * n, false);
            for (ProcessRecipe.ItemResult res : sure) {
                itemsOut += res.count() * k;
            }
            for (ProcessRecipe.ItemResult res : chance) {
                int rolled = 0;
                for (int i = 0; i < k; i++) {
                    rolled += rng.getAsDouble() < res.chance() ? res.count() : 0;
                }
                if (rolled > 0) {
                    itemsOut += out.insert(res.item(), rolled, false);
                }
            }
            budget -= k * n;
            smelted += k * n;
            itemsIn += k * n;
        }
        return itemsIn == 0 ? Result.NONE : new Result(batches, smelted, itemsIn, itemsOut);
    }

    /** The first heating recipe with one consumed item input that takes {@code v} and makes items. */
    static ProcessRecipe smeltOf(ItemVariant v, List<ProcessRecipe> heating, TagLookup tags) {
        for (ProcessRecipe r : heating) {
            if (r.fluidInput() != null || r.fluidResult() != null || r.itemInputs().size() != 1 || r.itemResults().isEmpty()) {
                continue;
            }
            ProcessRecipe.ItemInput in = r.itemInputs().get(0);
            if (in.consumed() && in.matcher().test(v, tags)) {
                return r;
            }
        }
        return null;
    }

    private static long countMatching(ItemStore in, ArcAlloys.Part p, TagLookup tags) {
        long n = 0;
        for (int s = 0; s < in.slots(); s++) {
            if (p.matches(in.variant(s), tags)) {
                n += in.count(s);
            }
        }
        return n;
    }

    /** How many to take from each slot for {@code k} batches (a slot is never counted for two inputs), or null. */
    static int[] allocate(ItemStore in, ArcAlloys.Alloy a, TagLookup tags, int k) {
        int[] left = new int[in.slots()];
        for (int s = 0; s < left.length; s++) {
            left[s] = in.variant(s) == null ? 0 : in.count(s);
        }
        int[] take = new int[left.length];
        for (ArcAlloys.Part p : a.inputs()) {
            long need = (long) p.count() * k;
            for (int s = 0; s < left.length && need > 0; s++) {
                if (left[s] > 0 && p.matches(in.variant(s), tags)) {
                    int t = (int) Math.min(left[s], need);
                    left[s] -= t;
                    take[s] += t;
                    need -= t;
                }
            }
            if (need > 0) {
                return null;
            }
        }
        return take;
    }

    /** Puts {@code k} units of every result into {@code out}, fewer when they do not fit; returns the units placed. */
    private static int commit(ItemStore out, List<ProcessRecipe.ItemResult> results, int k) {
        while (k > 0) {
            int[] got = new int[results.size()];
            int bad = -1;
            for (int i = 0; i < results.size(); i++) {
                ProcessRecipe.ItemResult res = results.get(i);
                got[i] = out.insert(res.item(), res.count() * k, false);
                if (got[i] < res.count() * k) {
                    bad = i;
                    break;
                }
            }
            if (bad < 0) {
                return k;
            }
            for (int i = 0; i <= bad; i++) {
                if (got[i] > 0) {
                    out.extract(results.get(i).item(), got[i], false);
                }
            }
            k = Math.min(k - 1, got[bad] / results.get(bad).count());
        }
        return 0;
    }
}
