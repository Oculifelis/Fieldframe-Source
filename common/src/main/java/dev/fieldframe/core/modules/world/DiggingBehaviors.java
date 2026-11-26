package dev.fieldframe.core.modules.world;

import dev.fieldframe.api.data.FfData;
import dev.fieldframe.api.module.ModuleContext;
import dev.fieldframe.api.resource.FluidStore;
import dev.fieldframe.api.resource.FluidVariant;
import dev.fieldframe.api.status.Statuses;
import dev.fieldframe.api.world.WorkGeometry;
import dev.fieldframe.api.world.WorkTarget;
import dev.fieldframe.api.world.WorldActions;
import dev.fieldframe.api.world.WorldActions.BlockInfo;
import dev.fieldframe.api.world.WorldActions.Outcome;

/** Block Breaker and Hydraulic Excavator. */
public final class DiggingBehaviors {
    private DiggingBehaviors() {
    }

    /**
     * Block Breaker: breaks the block directly in front of its face, one per interval, with the first fitting
     * tool from the tool lane (or by hand). Unbreakable, protected and block-entity blocks are left alone.
     */
    public static final class BlockBreaker extends WorldWorker {
        @Override
        protected void work(ModuleContext ctx, WorldActions a) {
            WorkTarget t = WorkTarget.adjacent(ctx.face());
            BlockInfo info = a.probe(t);
            if (report(ctx, info.access(), t)) {
                return;
            }
            if (info.air() || info.fluid() != null) {
                ctx.setStatus(Statuses.NOTHING_TO_DO);
                ctx.sleep(10);
                return;
            }
            if (info.unbreakable()) {
                report(ctx, Outcome.UNBREAKABLE, t);
                return;
            }
            if (info.hasBlockEntity()) {
                ctx.setStatus(Statuses.targetBlocked("block entity"));
                ctx.sleep(40);
                return;
            }
            long cost = Math.round(ctx.tunable("rf_per_block"));
            if (!hasEnergy(ctx, cost)) {
                return;
            }
            WorldActions.BreakResult r = breakWithTools(a, t, ctx.items("tool"));
            if (report(ctx, r.outcome(), t)) {
                return;
            }
            ctx.energy().consume(cost);
            pending.flush(ctx.items("output"));
            ctx.setStatus(Statuses.working(String.valueOf(info.block())));
            ctx.trace("broke " + info.block());
            interval(ctx);
        }
    }

    /**
     * Hydraulic Excavator. Works a bounded square area layer by layer out from its face with a persisted cursor.
     * Each block costs RF and working fluid (contained nozzle: the fluid is consumed as make-up loss, nothing is
     * placed in the world). Drops come from real loot with a real tool. Stops (does not skip) at protected or
     * unbreakable blocks; skips air, fluids and block entities. Never starts a block while output is pending.
     */
    public static final class Excavator extends WorldWorker {
        private int cursor;
        private String signature = "";
        private boolean done;
        private long lastMark;

        private static String signature(ModuleContext ctx) {
            return ctx.config().integer("radius") + "/" + ctx.config().integer("depth") + "/" + ctx.config().integer("job");
        }

        @Override
        public void onConfigChanged(ModuleContext ctx) {
            String sig = signature(ctx);
            if (!sig.equals(signature)) {
                signature = sig;
                cursor = 0;
                done = false;
            }
        }

        @Override
        public void onInstalled(ModuleContext ctx) {
            signature = signature(ctx);
        }

        @Override
        protected void work(ModuleContext ctx, WorldActions a) {
            if (!signature.equals(signature(ctx))) {
                onConfigChanged(ctx);
            }
            int radius = ctx.config().integer("radius");
            int depth = ctx.config().integer("depth");
            if (ctx.config().bool("preview") && ctx.gameTime() - lastMark >= 20) {
                lastMark = ctx.gameTime();
                preview(a, ctx, radius, depth);
            }
            ctx.emit("done", done ? 15 : 0);
            // Bounded skip budget: at most 16 air/fluid/block-entity positions per tick.
            for (int scanned = 0; scanned < 16; scanned++) {
                WorkTarget t = WorkGeometry.areaCursor(ctx.face(), radius, depth, cursor);
                if (t == null) {
                    done = true;
                    ctx.emit("done", 15);
                    ctx.emit("fault", 0);
                    ctx.setStatus(Statuses.AREA_DONE);
                    ctx.sleep(100);
                    return;
                }
                BlockInfo info = a.probe(t);
                if (info.access() != Outcome.OK) {
                    fault(ctx, info.access(), t);
                    return;
                }
                if (info.air() || info.fluid() != null || info.hasBlockEntity()) {
                    cursor++;
                    continue;
                }
                if (info.unbreakable()) {
                    fault(ctx, Outcome.UNBREAKABLE, t);
                    return;
                }
                long cost = Math.round(ctx.tunable("rf_per_block"));
                long water = Math.round(ctx.tunable("fluid_per_block"));
                if (!hasEnergy(ctx, cost) || !hasFluid(ctx, water)) {
                    return;
                }
                WorldActions.BreakResult r = breakWithTools(a, t, ctx.items("tool"));
                if (r.outcome() != Outcome.OK) {
                    fault(ctx, r.outcome(), t);
                    return;
                }
                // Committed: the block is gone, so the cursor and costs advance in this same tick.
                cursor++;
                ctx.energy().consume(cost);
                drainFluid(ctx, water);
                pending.flush(ctx.items("output"));
                ctx.emit("fault", 0);
                ctx.setStatus(Statuses.working(t + " " + info.block()));
                interval(ctx);
                return;
            }
            ctx.setStatus(Statuses.working("scanning " + cursor + "/" + WorkGeometry.areaSize(radius, depth)));
        }

        private void fault(ModuleContext ctx, Outcome o, WorkTarget t) {
            ctx.emit("fault", 15);
            report(ctx, o, t);
        }

        private static boolean hasFluid(ModuleContext ctx, long amount) {
            if (amount <= 0) {
                return true;
            }
            FluidStore tank = ctx.fluids("fluid");
            FluidVariant f = tank == null ? null : tank.drainableFluid();
            if (f == null || !ctx.world().tags().fluidInTag(f, WORKING_FLUIDS) && !f.id().toString().equals("minecraft:water")) {
                ctx.setStatus(Statuses.noFluid(ctx.bindingLabel("fluid")));
                ctx.sleep(20);
                return false;
            }
            if (tank.drain(f, amount, true) < amount) {
                ctx.setStatus(Statuses.noFluid(ctx.bindingLabel("fluid")));
                ctx.sleep(20);
                return false;
            }
            return true;
        }

        private static void drainFluid(ModuleContext ctx, long amount) {
            if (amount <= 0) {
                return;
            }
            FluidStore tank = ctx.fluids("fluid");
            tank.drain(tank.drainableFluid(), amount, false);
        }

        private static void preview(WorldActions a, ModuleContext ctx, int r, int depth) {
            int d = Math.min(depth, WorkTarget.MAX_DEPTH);
            for (int depthStep : new int[] {1, d}) {
                a.mark(new WorkTarget(ctx.face(), depthStep, -r, -r));
                a.mark(new WorkTarget(ctx.face(), depthStep, r, -r));
                a.mark(new WorkTarget(ctx.face(), depthStep, -r, r));
                a.mark(new WorkTarget(ctx.face(), depthStep, r, r));
            }
        }

        @Override
        public String detail() {
            return (done ? "done" : "block " + cursor) + ", " + super.detail();
        }

        @Override
        protected void saveExtra(FfData d) {
            d.putInt("cursor", cursor).putString("sig", signature).putBool("done", done);
        }

        @Override
        protected void loadExtra(FfData d) {
            cursor = Math.max(0, d.getInt("cursor", 0));
            signature = d.getString("sig", "");
            done = d.getBool("done", false);
        }

        /** Test accessor. */
        public int cursor() {
            return cursor;
        }
    }

    /** Fluids accepted as excavator working fluid besides water (data tag). */
    public static final dev.fieldframe.api.id.FfId WORKING_FLUIDS = dev.fieldframe.api.id.FfId.ff("excavator_fluids");
}
