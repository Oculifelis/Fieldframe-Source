package dev.fieldframe.core.modules.world;

import dev.fieldframe.api.data.FfData;
import dev.fieldframe.api.id.FfId;
import dev.fieldframe.api.module.ModuleBehavior;
import dev.fieldframe.api.module.ModuleContext;
import dev.fieldframe.api.resource.FluidStore;
import dev.fieldframe.api.resource.FluidVariant;
import dev.fieldframe.api.status.Statuses;
import dev.fieldframe.api.world.WorkTarget;
import dev.fieldframe.api.world.WorldActions;

/** Waterwheel/Turbine Interface and Motion Harvester. */
public final class GeneratorBehaviors {
    private GeneratorBehaviors() {
    }

    /** Fluids a turbine may burn (data tag {@code fieldframe:turbine_fluids}). */
    public static final FfId TURBINE_FLUIDS = FfId.ff("turbine_fluids");
    /** Number of blocks sampled along the face normal by a waterwheel. */
    public static final int WHEEL_SAMPLES = 3;

    /**
     * Two clearly separate modes:
     * <ul>
     *   <li>{@code wheel} (renewable, environmental): every {@code check_interval} ticks samples the
     *       {@value #WHEEL_SAMPLES} blocks in a straight line out from its face and counts flowing (not source)
     *       water. Each face samples its own axis, so two wheels on one chassis never count the same block.</li>
     *   <li>{@code turbine} (stored fluid): burns {@code mb_per_tick} of a fluid from the bound reservoir if the
     *       fluid is in {@code #fieldframe:turbine_fluids}; consumed fluid is gone.</li>
     * </ul>
     */
    public static final class Waterwheel implements ModuleBehavior {
        private long nextCheck;
        private int flowing;

        @Override
        public void tick(ModuleContext ctx) {
            if (ctx.config().choice("mode").equals("turbine")) {
                turbine(ctx);
                return;
            }
            WorldActions a = ctx.world().actions();
            if (a == null) {
                ctx.setStatus(Statuses.NO_WORLD_ACCESS);
                ctx.sleep(100);
                return;
            }
            long now = ctx.gameTime();
            if (now >= nextCheck) {
                flowing = 0;
                for (int d = 1; d <= WHEEL_SAMPLES; d++) {
                    WorldActions.BlockInfo info = a.probe(new WorkTarget(ctx.face(), d, 0, 0));
                    if (info.access().ok() && info.flowingWater()) {
                        flowing++;
                    }
                }
                nextCheck = now + Math.max(20, Math.round(ctx.tunable("check_interval")));
            }
            if (flowing == 0) {
                ctx.setStatus(Statuses.NOTHING_TO_DO);
                ctx.sleep((int) Math.max(1, nextCheck - now));
                return;
            }
            long got = ctx.energy().generate(Math.round(flowing * ctx.tunable("rf_per_flow")));
            ctx.setStatus(got > 0 ? Statuses.working(got + " RF/t, " + flowing + " flow") : Statuses.BUFFER_FULL);
        }

        private void turbine(ModuleContext ctx) {
            FluidStore tank = ctx.fluids("fluid");
            if (tank == null) {
                ctx.setStatus(Statuses.UNBOUND);
                return;
            }
            FluidVariant f = tank.drainableFluid();
            if (f == null || !ctx.world().tags().fluidInTag(f, TURBINE_FLUIDS)) {
                ctx.setStatus(Statuses.noFluid(ctx.bindingLabel("fluid")));
                ctx.sleep(20);
                return;
            }
            long mb = Math.round(ctx.tunable("mb_per_tick"));
            long rf = Math.round(mb * ctx.tunable("rf_per_mb"));
            if (ctx.energy().capacity() - ctx.energy().stored() < rf) {
                ctx.setStatus(Statuses.BUFFER_FULL);
                ctx.sleep(10);
                return;
            }
            long drained = tank.drain(f, mb, false);
            long got = ctx.energy().generate(Math.round(drained * ctx.tunable("rf_per_mb")));
            ctx.setStatus(Statuses.working(got + " RF/t from " + f.id()));
        }

        @Override
        public String detail() {
            return flowing + " flowing";
        }

        @Override
        public FfData save() {
            return new FfData().putInt("flowing", flowing);
        }

        @Override
        public void load(FfData d) {
            flowing = d.getIntClamped("flowing", 0, 0, WHEEL_SAMPLES);
        }
    }

    /**
     * Motion Harvester: turns the movement of nearby living entities into a little RF. Samples at most
     * {@code max_entities} every 10 ticks; output is capped at {@code max_rf_per_tick}. Servers can switch it off
     * with {@code enabled = 0}.
     */
    public static final class MotionHarvester implements ModuleBehavior {
        private long nextSample;
        private long rate;

        @Override
        public void tick(ModuleContext ctx) {
            if (ctx.tunable("enabled") < 1) {
                ctx.setStatus(Statuses.POLICY_DISABLED);
                ctx.sleep(200);
                return;
            }
            WorldActions a = ctx.world().actions();
            if (a == null) {
                ctx.setStatus(Statuses.NO_WORLD_ACCESS);
                ctx.sleep(100);
                return;
            }
            long now = ctx.gameTime();
            if (now >= nextSample) {
                WorldActions.EntitySample s = a.sampleMotion(ctx.face(), 2, (int) Math.round(ctx.tunable("max_entities")));
                rate = Math.min(Math.round(ctx.tunable("max_rf_per_tick")),
                        Math.round(s.totalHorizontalSpeed() * ctx.tunable("rf_per_speed")));
                nextSample = now + 10;
            }
            if (rate <= 0) {
                ctx.setStatus(Statuses.NOTHING_TO_DO);
                ctx.sleep((int) Math.max(1, nextSample - now));
                return;
            }
            long got = ctx.energy().generate(rate);
            ctx.setStatus(got > 0 ? Statuses.working(got + " RF/t") : Statuses.BUFFER_FULL);
        }

        @Override
        public String detail() {
            return rate + " RF/t";
        }
    }
}
