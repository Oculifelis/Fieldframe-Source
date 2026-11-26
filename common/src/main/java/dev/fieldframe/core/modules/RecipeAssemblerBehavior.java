package dev.fieldframe.core.modules;

import dev.fieldframe.api.data.FfData;
import dev.fieldframe.api.id.FfId;
import dev.fieldframe.api.module.ChassisWorld;
import dev.fieldframe.api.module.ModuleBehavior;
import dev.fieldframe.api.module.ModuleContext;
import dev.fieldframe.api.resource.ItemStore;
import dev.fieldframe.api.resource.ItemVariant;
import dev.fieldframe.api.status.Statuses;
import dev.fieldframe.core.modules.world.PendingItems;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Recipe Assembler: crafts with a ghost pattern of up to 9 item ids (no items are stored in the pattern).
 * Ingredients come from the input lane; the real stacks (with their components) are handed to the platform
 * crafting lookup, so component-sensitive results and remainders (buckets, tools) are correct. A craft only starts
 * when the result fits; remainders that do not fit wait as pending output, and nothing new starts meanwhile.
 */
public final class RecipeAssemblerBehavior implements ModuleBehavior {
    public static final String EMPTY = "_";
    private final PendingItems pending = new PendingItems();
    private long crafted;

    /** Parses "a b c / d e f / g h i" style text (separators: space, comma, slash); "_" is an empty cell. */
    public static FfId[] parsePattern(String text) {
        String[] parts = text.trim().split("[\\s,/]+");
        if (text.isBlank() || parts.length > 9) {
            return null;
        }
        FfId[] out = new FfId[9];
        for (int i = 0; i < parts.length; i++) {
            if (parts[i].equals(EMPTY)) {
                continue;
            }
            out[i] = FfId.tryParse(parts[i].contains(":") ? parts[i] : "minecraft:" + parts[i]);
            if (out[i] == null) {
                return null;
            }
        }
        return out;
    }

    /** Inverse of {@link #parsePattern}: three rows "a b c / d e f / g h i", "_" for empty cells (ghost-grid editor). */
    public static String formatPattern(FfId[] cells) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < 9; i++) {
            if (i > 0) {
                b.append(i % 3 == 0 ? " / " : " ");
            }
            FfId id = cells != null && i < cells.length ? cells[i] : null;
            b.append(id == null ? EMPTY : id.toString());
        }
        return b.toString();
    }

    @Override
    public void tick(ModuleContext ctx) {
        ItemStore in = ctx.items("input");
        ItemStore out = ctx.items("output");
        if (in == null || out == null) {
            ctx.setStatus(Statuses.UNBOUND);
            return;
        }
        if (!pending.flush(out)) {
            ctx.setStatus(Statuses.outputPending(pending.total()));
            ctx.sleep(10);
            return;
        }
        if (!ctx.enabledBy("enable")) {
            ctx.setStatus(Statuses.signalDisabled(ctx.bindingLabel("enable")));
            return;
        }
        FfId[] pattern = parsePattern(ctx.config().text("pattern"));
        if (pattern == null) {
            ctx.setStatus(Statuses.fault("pattern"));
            ctx.sleep(40);
            return;
        }
        // Choose one concrete variant per pattern id, and check the lane holds enough of each.
        Map<FfId, ItemVariant> chosen = new HashMap<>();
        Map<FfId, Integer> need = new HashMap<>();
        for (FfId id : pattern) {
            if (id != null) {
                need.merge(id, 1, Integer::sum);
            }
        }
        if (need.isEmpty()) {
            ctx.setStatus(Statuses.fault("pattern"));
            ctx.sleep(40);
            return;
        }
        for (Map.Entry<FfId, Integer> e : need.entrySet()) {
            ItemVariant v = find(in, e.getKey(), e.getValue());
            if (v == null) {
                ctx.setStatus(Statuses.noInput(ctx.bindingLabel("input")));
                ctx.sleep(20);
                return;
            }
            chosen.put(e.getKey(), v);
        }
        List<ItemVariant> grid = new ArrayList<>(9);
        for (FfId id : pattern) {
            grid.add(id == null ? null : chosen.get(id));
        }
        ChassisWorld.CraftResult r = ctx.world().craft(grid);
        if (r == null || r.result() == null || r.count() <= 0) {
            ctx.setStatus(Statuses.invalidRecipe(ctx.bindingLabel("input")));
            ctx.sleep(40);
            return;
        }
        if (out.insert(r.result(), r.count(), true) < r.count()) {
            ctx.setStatus(Statuses.outputFull(ctx.bindingLabel("output")));
            ctx.sleep(20);
            return;
        }
        long cost = Math.round(ctx.tunable("rf_per_craft"));
        if (cost > 0 && !ctx.energy().consume(cost)) {
            ctx.setStatus(Statuses.noEnergy(cost));
            ctx.sleep(20);
            return;
        }
        for (Map.Entry<FfId, Integer> e : need.entrySet()) {
            in.extract(chosen.get(e.getKey()), e.getValue(), false);
        }
        int placed = out.insert(r.result(), r.count(), false);
        pending.add(r.result(), r.count() - placed);
        for (ItemVariant rem : r.remainders()) {
            pending.add(rem, 1);
        }
        pending.flush(out);
        crafted++;
        ctx.setStatus(Statuses.working(r.count() + " " + r.result().id()));
        ctx.sleep((int) Math.max(1, Math.round(ctx.tunable("interval"))));
    }

    private static ItemVariant find(ItemStore lane, FfId id, int count) {
        for (int s = 0; s < lane.slots(); s++) {
            ItemVariant v = lane.variant(s);
            if (v != null && v.id().equals(id) && lane.countOf(v) >= count) {
                return v;
            }
        }
        return null;
    }

    @Override
    public void onRemoved(ModuleContext ctx) {
        pending.spillAll(ctx);
    }

    @Override
    public String detail() {
        return crafted + " crafted";
    }

    @Override
    public FfData save() {
        FfData d = new FfData().putLong("crafted", crafted);
        d.putList("pending", pending.save());
        return d;
    }

    @Override
    public void load(FfData d) {
        crafted = Math.max(0, d.getLong("crafted", 0));
        pending.load(d.getList("pending"));
    }
}
