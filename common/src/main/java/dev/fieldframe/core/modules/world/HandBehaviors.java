package dev.fieldframe.core.modules.world;

import dev.fieldframe.api.data.FfData;
import dev.fieldframe.api.id.FfId;
import dev.fieldframe.api.module.ModuleContext;
import dev.fieldframe.api.resource.ItemFilter;
import dev.fieldframe.api.resource.ItemStore;
import dev.fieldframe.api.resource.ItemVariant;
import dev.fieldframe.api.status.Statuses;
import dev.fieldframe.api.world.WorkGeometry;
import dev.fieldframe.api.world.WorkTarget;
import dev.fieldframe.api.world.WorldActions;
import dev.fieldframe.api.world.WorldActions.BlockInfo;
import dev.fieldframe.api.world.WorldActions.Outcome;

/** Block Placer, Interaction Head and Crop Tender: actions that hand an item to the world. */
public final class HandBehaviors {
    private HandBehaviors() {
    }

    /**
     * Block Placer: places one item from its lane into the block in front, if that space is replaceable.
     * Items that do not place (not blocks, or placement rules refuse) are skipped with a round-robin cursor.
     */
    public static final class BlockPlacer extends WorldWorker {
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
            WorkTarget t = WorkTarget.adjacent(ctx.face());
            BlockInfo info = a.probe(t);
            if (report(ctx, info.access(), t)) {
                return;
            }
            if (!info.air() && !info.replaceable()) {
                ctx.setStatus(Statuses.targetBlocked(String.valueOf(info.block())));
                ctx.sleep(20);
                return;
            }
            ItemFilter filter = ctx.config().itemFilter("filter");
            long cost = Math.round(ctx.tunable("rf_per_block"));
            if (!hasEnergy(ctx, cost)) {
                return;
            }
            Outcome last = Outcome.NOTHING_TO_DO;
            // Try at most four candidate slots per tick.
            for (int tried = 0, s = 0; s < lane.slots() && tried < 4; s++) {
                int slot = (cursor + s) % lane.slots();
                ItemVariant v = lane.variant(slot);
                if (v == null || lane.count(slot) <= 0 || !filter.test(v, ctx.world().tags())) {
                    continue;
                }
                tried++;
                Outcome o = a.place(t, v);
                if (o.ok()) {
                    lane.extractSlot(slot, 1, false);
                    ctx.energy().consume(cost);
                    actions++;
                    cursor = slot;
                    ctx.setStatus(Statuses.working(v.id().toString()));
                    interval(ctx);
                    return;
                }
                last = o;
                if (o != Outcome.NOT_PLACEABLE) {
                    break;
                }
            }
            cursor = (cursor + 1) % Math.max(1, lane.slots());
            if (last == Outcome.NOTHING_TO_DO) {
                ctx.setStatus(Statuses.noInput(ctx.bindingLabel("lane")));
                ctx.sleep(20);
            } else {
                report(ctx, last, t);
            }
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

    /**
     * Interaction Head: a right-click on the block in front with the stack from the held lane, as the chassis
     * actor. Tools take durability; containers (buckets, bottles) hand back their results, which go to the output
     * lane. Mode {@code empty_hand} uses no item (buttons, levers, berry bushes).
     */
    public static final class InteractionHead extends WorldWorker {
        private int cursor;

        @Override
        protected void work(ModuleContext ctx, WorldActions a) {
            WorkTarget t = WorkTarget.adjacent(ctx.face());
            long cost = Math.round(ctx.tunable("rf_per_use"));
            if (!hasEnergy(ctx, cost)) {
                return;
            }
            boolean emptyHand = ctx.config().choice("mode").equals("empty_hand");
            ItemStore held = ctx.items("held");
            if (emptyHand) {
                WorldActions.UseResult r = a.use(t, null, 0);
                if (report(ctx, r.outcome(), t)) {
                    return;
                }
                finish(ctx, r, cost, "hand");
                return;
            }
            if (held == null) {
                ctx.setStatus(Statuses.UNBOUND);
                return;
            }
            ItemFilter filter = ctx.config().itemFilter("filter");
            for (int s = 0; s < held.slots(); s++) {
                int slot = (cursor + s) % held.slots();
                ItemVariant v = held.variant(slot);
                int n = held.count(slot);
                if (v == null || n <= 0 || !filter.test(v, ctx.world().tags())) {
                    continue;
                }
                cursor = slot;
                WorldActions.UseResult r = a.use(t, v, n);
                if (report(ctx, r.outcome(), t)) {
                    return;
                }
                // Replace the stack with what the actor holds afterwards (same tick, so the slot is unchanged).
                // A different item (bucket -> water bucket) is a product and goes to the output lane instead,
                // so the head never feeds its own result back into the world.
                held.extractSlot(slot, n, false);
                if (r.heldAfter() != null && r.heldAfterCount() > 0) {
                    int back = r.heldAfter().id().equals(v.id())
                            ? held.insertSlot(slot, r.heldAfter(), r.heldAfterCount(), false) : 0;
                    pending.add(r.heldAfter(), r.heldAfterCount() - back);
                }
                finish(ctx, r, cost, v.id().toString());
                return;
            }
            ctx.setStatus(Statuses.noInput(ctx.bindingLabel("held")));
            ctx.sleep(20);
        }

        private void finish(ModuleContext ctx, WorldActions.UseResult r, long cost, String what) {
            pending.addAll(r.extra());
            pending.flush(ctx.items("output"));
            ctx.energy().consume(cost);
            actions++;
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

    /**
     * Crop Tender: visits one position of a small square (radius 0..2) at depth 1 per interval. Mature crops are
     * harvested by hand (real loot) and replanted from the lane; immature crops optionally get one bone meal.
     */
    public static final class CropTender extends WorldWorker {
        static final FfId BONE_MEAL = FfId.parse("minecraft:bone_meal");
        static final FfId COMPOST = FfId.ff("compost");
        private int cursor;

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
            int radius = ctx.config().integer("radius");
            int size = WorkGeometry.areaSize(radius, 1);
            cursor = cursor % size;
            WorkTarget t = target(ctx.face(), radius, cursor);
            cursor = (cursor + 1) % size;
            BlockInfo info = a.probe(t);
            if (info.access() == Outcome.UNLOADED || info.access() == Outcome.PROTECTED) {
                report(ctx, info.access(), t);
                return;
            }
            if (!info.crop()) {
                ctx.setStatus(Statuses.NOTHING_TO_DO);
                interval(ctx);
                return;
            }
            long cost = Math.round(ctx.tunable("rf_per_action"));
            if (!hasEnergy(ctx, cost)) {
                return;
            }
            if (info.mature()) {
                WorldActions.BreakResult r = a.breakBlock(t, null, true);
                if (report(ctx, r.outcome(), t)) {
                    return;
                }
                pending.addAll(r.drops());
                actions++;
                ctx.energy().consume(cost);
                if (ctx.config().bool("replant") && info.plantItem() != null) {
                    replant(ctx, a, lane, t, info.plantItem());
                }
                pending.flush(lane);
                ctx.setStatus(Statuses.working("harvest " + info.block()));
            } else if (ctx.config().bool("bonemeal")) {
                // Compost from a Bio-Digester works like bone meal.
                ItemVariant meal = findById(lane, BONE_MEAL);
                ItemVariant spend = meal;
                if (meal == null) {
                    spend = findById(lane, COMPOST);
                    meal = spend == null ? null : ctx.world().itemVariant(BONE_MEAL);
                }
                if (meal == null) {
                    ctx.setStatus(Statuses.NOTHING_TO_DO);
                } else {
                    WorldActions.UseResult r = a.use(t, meal, 1);
                    if (r.outcome().ok() && (r.heldAfter() == null || r.heldAfterCount() == 0)) {
                        lane.extract(spend, 1, false);
                        ctx.energy().consume(cost);
                        actions++;
                        ctx.setStatus(Statuses.working("bone meal"));
                    } else {
                        ctx.setStatus(Statuses.NOTHING_TO_DO);
                    }
                }
            } else {
                ctx.setStatus(Statuses.NOTHING_TO_DO);
            }
            interval(ctx);
        }

        /**
         * The tended patch is always horizontal: on a side face it is the {@code (2r+1)^2} field in front at the
         * chassis' own height (crops need light, so a tender usually sits at the edge of the field); on TOP/BOTTOM
         * it is the layer directly above/below.
         */
        static WorkTarget target(dev.fieldframe.api.face.LocalFace face, int radius, int index) {
            if (face == dev.fieldframe.api.face.LocalFace.TOP || face == dev.fieldframe.api.face.LocalFace.BOTTOM) {
                return WorkGeometry.areaCursor(face, radius, 1, index);
            }
            int side = 2 * radius + 1;
            return new WorkTarget(face, 1 + index / side, index % side - radius, 0);
        }

        private void replant(ModuleContext ctx, WorldActions a, ItemStore lane, WorkTarget t, ItemVariant seed) {
            boolean fromPending = pending.take(seed, 1) == 1;
            boolean fromLane = !fromPending && lane.extract(seed, 1, true) == 1;
            if (!fromPending && !fromLane) {
                return;
            }
            Outcome o = a.place(t, seed);
            if (o.ok()) {
                if (fromLane) {
                    lane.extract(seed, 1, false);
                }
            } else {
                ctx.trace("replant " + seed.id() + ": " + o);
                if (fromPending) {
                    pending.add(seed, 1);
                }
            }
        }

        private static ItemVariant findById(ItemStore lane, FfId id) {
            for (int s = 0; s < lane.slots(); s++) {
                ItemVariant v = lane.variant(s);
                if (v != null && lane.count(s) > 0 && v.id().equals(id)) {
                    return v;
                }
            }
            return null;
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
}
