package dev.fieldframe.core.modules.world;

import dev.fieldframe.api.data.FfData;
import dev.fieldframe.api.module.ModuleBehavior;
import dev.fieldframe.api.module.ModuleContext;
import dev.fieldframe.api.resource.ItemStore;
import dev.fieldframe.api.resource.ItemVariant;
import dev.fieldframe.api.status.Statuses;
import dev.fieldframe.api.world.WorkTarget;
import dev.fieldframe.api.world.WorldActions;
import dev.fieldframe.api.world.WorldActions.Outcome;

import java.util.ArrayList;
import java.util.List;

/**
 * Shared skeleton of every world-interaction module: platform access check, pending-output flush before any new
 * work, enable signal, per-action energy, and persistence of pending items. Subclasses implement {@link #work}.
 *
 * <p>Ordering rule that makes world work exactly-once: a subclass changes the world, then in the same tick
 * records the result (drops into {@link #pending}, cursor advance). Both are saved together with the chassis.
 */
public abstract class WorldWorker implements ModuleBehavior {
    protected final PendingItems pending = new PendingItems();
    protected long actions;
    /** Hard rate limit: no world action before this game time (sleep alone can be cut short by wake-ups). */
    private long readyAt;

    /** Binding key of the item lane that receives results, or null if this module produces no items. */
    protected String outputBinding() {
        return "output";
    }

    protected abstract void work(ModuleContext ctx, WorldActions a);

    @Override
    public final void tick(ModuleContext ctx) {
        WorldActions a = ctx.world() == null ? null : ctx.world().actions();
        if (a == null) {
            ctx.setStatus(Statuses.NO_WORLD_ACCESS);
            ctx.sleep(100);
            return;
        }
        if (!pending.isEmpty()) {
            ItemStore out = outputBinding() == null ? null : ctx.items(outputBinding());
            if (!pending.flush(out)) {
                ctx.setStatus(Statuses.outputPending(pending.total()));
                ctx.sleep(10);
                return;
            }
        }
        if (!ctx.enabledBy("enable")) {
            ctx.setStatus(Statuses.signalDisabled(ctx.bindingLabel("enable")));
            return;
        }
        long now = ctx.gameTime();
        if (now < readyAt) {
            ctx.sleep((int) Math.min(1200, readyAt - now));
            return;
        }
        work(ctx, new GuardedActions(a, ctx));
    }

    /** True when the RF buffer holds {@code cost}; sets NO_ENERGY otherwise. Consume only after success. */
    protected static boolean hasEnergy(ModuleContext ctx, long cost) {
        if (cost <= 0 || ctx.energy().stored() >= cost) {
            return true;
        }
        ctx.setStatus(Statuses.noEnergy(cost));
        ctx.sleep(20);
        return false;
    }

    /** Starts the cooldown after a world action (or a probe that found nothing). */
    protected void interval(ModuleContext ctx) {
        cooldown(ctx, (int) Math.max(1, Math.round(ctx.tunable("interval"))));
    }

    protected void cooldown(ModuleContext ctx, int ticks) {
        readyAt = ctx.gameTime() + ticks;
        ctx.sleep(ticks);
    }

    /** Maps a failed outcome to a player-facing status. Returns true if it was a failure. */
    protected static boolean report(ModuleContext ctx, Outcome o, WorkTarget t) {
        String where = t == null ? "" : t.toString();
        switch (o) {
            case OK:
                return false;
            case UNLOADED:
                ctx.setStatus(Statuses.NEIGHBOR_UNLOADED);
                break;
            case PROTECTED:
                ctx.setStatus(Statuses.protectedTarget(where));
                break;
            case UNBREAKABLE:
                ctx.setStatus(Statuses.unbreakable(where));
                break;
            case WRONG_TOOL:
                ctx.setStatus(Statuses.WRONG_TOOL);
                break;
            case DISABLED_BY_POLICY:
                ctx.setStatus(Statuses.POLICY_DISABLED);
                break;
            case AIR:
            case NOTHING_TO_DO:
                ctx.setStatus(Statuses.NOTHING_TO_DO);
                break;
            default:
                ctx.setStatus(Statuses.targetBlocked(where));
        }
        ctx.sleep(o == Outcome.DISABLED_BY_POLICY ? 200 : 20);
        return true;
    }

    /** Slots holding a single unstackable item (tools). */
    protected static List<Integer> toolSlots(ItemStore lane) {
        List<Integer> slots = new ArrayList<>();
        if (lane == null) {
            return slots;
        }
        for (int s = 0; s < lane.slots(); s++) {
            ItemVariant v = lane.variant(s);
            if (v != null && v.maxStackSize() == 1 && lane.count(s) == 1) {
                slots.add(s);
            }
        }
        return slots;
    }

    /**
     * Breaks a block with the first tool in {@code tools} that can harvest it, or by hand when the lane holds no
     * tools. Never removes a block whose drops would be lost to a wrong tool. Drops go to {@link #pending}; the
     * damaged tool returns to its slot (or pending if the slot was taken meanwhile).
     */
    protected WorldActions.BreakResult breakWithTools(WorldActions a, WorkTarget t, ItemStore tools) {
        List<Integer> slots = toolSlots(tools);
        WorldActions.BreakResult last = null;
        if (slots.isEmpty()) {
            last = a.breakBlock(t, null, true);
        } else {
            for (int slot : slots) {
                ItemVariant tool = tools.variant(slot);
                WorldActions.BreakResult r = a.breakBlock(t, tool, true);
                last = r;
                if (r.outcome() == Outcome.WRONG_TOOL) {
                    continue;
                }
                if (r.outcome().ok()) {
                    tools.extractSlot(slot, 1, false);
                    if (r.toolAfter() != null && tools.insertSlot(slot, r.toolAfter(), 1, false) < 1) {
                        pending.add(r.toolAfter(), 1);
                    }
                }
                break;
            }
        }
        if (last.outcome().ok()) {
            pending.addAll(last.drops());
            actions++;
        }
        return last;
    }

    @Override
    public void onRemoved(ModuleContext ctx) {
        pending.spillAll(ctx);
    }

    @Override
    public String detail() {
        return actions + " actions" + (pending.isEmpty() ? "" : ", " + pending.total() + " pending");
    }

    protected void saveExtra(FfData d) {
    }

    protected void loadExtra(FfData d) {
    }

    @Override
    public final FfData save() {
        FfData d = new FfData().putLong("actions", actions).putLong("readyAt", readyAt);
        d.putList("pending", pending.save());
        saveExtra(d);
        return d;
    }

    @Override
    public final void load(FfData d) {
        actions = Math.max(0, d.getLong("actions", 0));
        readyAt = Math.max(0, d.getLong("readyAt", 0));
        pending.load(d.getList("pending"));
        loadExtra(d);
    }

    /** Test/diagnostic accessor. */
    public int pendingCount() {
        return pending.total();
    }
}
