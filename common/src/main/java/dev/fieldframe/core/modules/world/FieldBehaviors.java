package dev.fieldframe.core.modules.world;

import dev.fieldframe.api.data.FfData;
import dev.fieldframe.api.module.ModuleContext;
import dev.fieldframe.api.resource.FluidStore;
import dev.fieldframe.api.resource.FluidVariant;
import dev.fieldframe.api.resource.ItemFilter;
import dev.fieldframe.api.resource.ItemStore;
import dev.fieldframe.api.resource.ItemVariant;
import dev.fieldframe.api.status.Statuses;
import dev.fieldframe.api.world.WorkGeometry;
import dev.fieldframe.api.world.WorkTarget;
import dev.fieldframe.api.world.WorldActions;
import dev.fieldframe.api.world.WorldActions.BlockInfo;
import dev.fieldframe.api.world.WorldActions.Outcome;
import dev.fieldframe.core.util.FilteredItemStore;

/** World Fluid Pump, Drop Collector, Item Ejector and Entity Mover. */
public final class FieldBehaviors {
    private FieldBehaviors() {
    }

    public static final int BUCKET = 1000;

    /**
     * World Fluid Pump. {@code pump}: removes source blocks from a finite area (radius 0..4, depth 1..8) into the
     * bound reservoir, 1000 mB each, only when the reservoir has room. {@code fill}: places source blocks from the
     * reservoir into air/replaceable spots, deepest layer first. Flowing fluid is never collected. Whether a
     * drained water source comes back is left to vanilla rules (two adjacent sources refill it): that is the
     * renewable-water rule, and the pump never creates fluid itself.
     */
    public static final class FluidPump extends WorldWorker {
        private int cursor;
        private int emptyScan;

        @Override
        protected String outputBinding() {
            return null;
        }

        @Override
        protected void work(ModuleContext ctx, WorldActions a) {
            FluidStore tank = ctx.fluids("tank");
            if (tank == null) {
                ctx.setStatus(Statuses.UNBOUND);
                return;
            }
            int radius = ctx.config().integer("radius");
            int depth = ctx.config().integer("depth");
            int size = WorkGeometry.areaSize(radius, depth);
            boolean fill = ctx.config().choice("mode").equals("fill");
            long cost = Math.round(ctx.tunable("rf_per_block"));
            if (!hasEnergy(ctx, cost)) {
                return;
            }
            FluidVariant stored = tank.drainableFluid();
            if (fill && (stored == null || tank.drain(stored, BUCKET, true) < BUCKET)) {
                ctx.setStatus(Statuses.noFluid(ctx.bindingLabel("tank")));
                ctx.sleep(40);
                return;
            }
            for (int probes = 0; probes < 8; probes++) {
                cursor = cursor % size;
                int index = fill ? size - 1 - cursor : cursor;
                WorkTarget t = WorkGeometry.areaCursor(ctx.face(), radius, depth, index);
                cursor++;
                BlockInfo info = a.probe(t);
                if (info.access() != Outcome.OK) {
                    continue;
                }
                if (!fill && info.fluidSource()) {
                    FluidVariant f = a.drainSource(t, true);
                    if (f == null) {
                        continue;
                    }
                    if (tank.fill(f, BUCKET, true) < BUCKET) {
                        cursor--;
                        ctx.setStatus(Statuses.outputFull(ctx.bindingLabel("tank")));
                        ctx.sleep(20);
                        return;
                    }
                    FluidVariant got = a.drainSource(t, false);
                    if (got != null) {
                        tank.fill(got, BUCKET, false);
                        done(ctx, cost, "pumped " + got.id());
                        return;
                    }
                } else if (fill && (info.air() || info.replaceable() && info.fluid() == null)) {
                    Outcome o = a.placeFluid(t, stored);
                    if (o.ok()) {
                        tank.drain(stored, BUCKET, false);
                        done(ctx, cost, "placed " + stored.id());
                        return;
                    }
                    if (o == Outcome.PROTECTED || o == Outcome.DISABLED_BY_POLICY) {
                        report(ctx, o, t);
                        return;
                    }
                }
                if (cursor >= size) {
                    emptyScan++;
                }
            }
            ctx.setStatus(Statuses.NOTHING_TO_DO);
            ctx.sleep(emptyScan > 0 ? 100 : 5);
            emptyScan = 0;
        }

        private void done(ModuleContext ctx, long cost, String what) {
            ctx.energy().consume(cost);
            actions++;
            emptyScan = 0;
            ctx.setStatus(Statuses.working(what));
            interval(ctx);
        }

        @Override
        protected void saveExtra(FfData d) {
            d.putInt("cursor", cursor);
        }

        @Override
        protected void loadExtra(FfData d) {
            cursor = Math.max(0, d.getInt("cursor", 0));
        }
    }

    /** Drop Collector: pulls dropped items from a small box in front of its face into a lane (budgeted). */
    public static final class DropCollector extends WorldWorker {
        @Override
        protected String outputBinding() {
            return "lane";
        }

        @Override
        protected void work(ModuleContext ctx, WorldActions a) {
            ItemStore lane = ctx.items("lane");
            if (lane == null) {
                ctx.setStatus(Statuses.UNBOUND);
                return;
            }
            int max = (int) Math.min(Math.round(ctx.tunable("items_per_pickup")), ctx.itemBudget().remaining());
            if (max <= 0) {
                ctx.setStatus(Statuses.working("budget"));
                return;
            }
            ItemStore into = new FilteredItemStore(lane, true, false, ctx.config().itemFilter("filter"), ctx.world().tags());
            int got = a.collectItems(ctx.face(), ctx.config().integer("range"), into, max);
            ctx.itemBudget().spend(got);
            if (got > 0) {
                actions += got;
                ctx.setStatus(Statuses.working(got + " items"));
            } else {
                ctx.setStatus(Statuses.NOTHING_TO_DO);
            }
            interval(ctx);
        }
    }

    /**
     * Item Ejector: drops up to {@code quantity} items from its lane as an item entity in front of its face, then
     * waits {@code delay} ticks. In {@code pulse} mode it ejects once per rising edge of the trigger signal.
     */
    public static final class ItemEjector extends WorldWorker {
        private boolean lastTrigger;
        private int cursor;

        @Override
        protected String outputBinding() {
            return null;
        }

        @Override
        protected void work(ModuleContext ctx, WorldActions a) {
            ItemStore lane = ctx.items("lane");
            if (lane == null) {
                ctx.setStatus(Statuses.UNBOUND);
                return;
            }
            if (ctx.config().choice("mode").equals("pulse")) {
                boolean trig = ctx.signal("trigger") > 0;
                boolean rising = trig && !lastTrigger;
                lastTrigger = trig;
                if (!rising) {
                    ctx.setStatus(Statuses.idle());
                    return;
                }
            }
            ItemFilter filter = ctx.config().itemFilter("filter");
            int quantity = (int) Math.min(ctx.config().integer("quantity"), ctx.itemBudget().remaining());
            for (int s = 0; s < lane.slots() && quantity > 0; s++) {
                int slot = (cursor + s) % lane.slots();
                ItemVariant v = lane.variant(slot);
                if (v == null || lane.count(slot) <= 0 || !filter.test(v, ctx.world().tags())) {
                    continue;
                }
                int n = lane.extractSlot(slot, Math.min(quantity, v.maxStackSize()), true);
                if (n <= 0) {
                    continue;
                }
                Outcome o = a.dropItem(ctx.face(), v, n);
                if (o.ok()) {
                    lane.extractSlot(slot, n, false);
                    ctx.itemBudget().spend(n);
                    actions += n;
                    cursor = (slot + 1) % lane.slots();
                    ctx.setStatus(Statuses.working(n + " " + v.id()));
                    cooldown(ctx, Math.max(1, ctx.config().integer("delay")));
                } else {
                    report(ctx, o, WorkTarget.adjacent(ctx.face()));
                }
                return;
            }
            ctx.setStatus(Statuses.noInput(ctx.bindingLabel("lane")));
            ctx.sleep(20);
        }

        @Override
        protected void saveExtra(FfData d) {
            d.putBool("trigger", lastTrigger).putInt("cursor", cursor);
        }

        @Override
        protected void loadExtra(FfData d) {
            lastTrigger = d.getBool("trigger", false);
            cursor = Math.max(0, d.getInt("cursor", 0));
        }
    }

    /**
     * Entity Mover: pushes or pulls living entities in a box in front of its face. Players are only affected when
     * both the module option and the server policy ({@code allow_players} tunable) allow it.
     */
    public static final class EntityMover extends WorldWorker {
        @Override
        protected String outputBinding() {
            return null;
        }

        @Override
        protected void work(ModuleContext ctx, WorldActions a) {
            long perEntity = Math.round(ctx.tunable("rf_per_entity"));
            int maxEntities = (int) Math.round(ctx.tunable("max_entities"));
            if (!hasEnergy(ctx, perEntity)) {
                return;
            }
            maxEntities = (int) Math.min(maxEntities, perEntity <= 0 ? maxEntities : ctx.energy().stored() / perEntity);
            boolean players = ctx.config().bool("players") && ctx.tunable("allow_players") >= 1;
            double strength = Math.min(ctx.config().integer("strength") / 10.0, ctx.tunable("max_strength"));
            int moved = a.moveEntities(ctx.face(), ctx.config().integer("range"), strength,
                    ctx.config().choice("mode").equals("pull"), players, maxEntities);
            if (moved > 0) {
                ctx.energy().consume(moved * perEntity);
                actions += moved;
                ctx.setStatus(Statuses.working(moved + " entities"));
            } else {
                ctx.setStatus(Statuses.NOTHING_TO_DO);
            }
            interval(ctx);
        }
    }
}
