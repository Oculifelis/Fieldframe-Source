package dev.fieldframe.core.modules.power;

import dev.fieldframe.api.id.FfId;
import dev.fieldframe.api.module.ModuleBehavior;
import dev.fieldframe.api.module.ModuleContext;
import dev.fieldframe.api.status.Status;
import dev.fieldframe.api.status.Statuses;

/**
 * Lunar Prism: the Solar Collector's night twin for the top face. At night under open sky it makes
 * {@code rf_per_tick} scaled by the moon phase (full 1, gibbous 3/4, quarter 1/2, crescent 1/4, new 0), times
 * {@code rain_factor} in rain. Sky and moon are checked every {@code check_interval} ticks.
 */
public final class LunarPrism implements ModuleBehavior {
    public static final FfId ID = FfId.ff("lunar_prism");
    /** Share of the output per moon phase, in the game's order (0 full moon, 4 new moon). */
    private static final double[] PHASE = {1.0, 0.75, 0.5, 0.25, 0.0, 0.25, 0.5, 0.75};
    private static final String[] PHASE_NAMES = {"full", "waning gibbous", "third quarter", "waning crescent", "new", "waxing crescent",
            "first quarter", "waxing gibbous"};

    private long nextCheck;
    private long rate;
    private Status status = Statuses.idle();

    /** Share of the full output for a moon phase (0..7), or 0 when unknown. */
    public static double phaseFactor(int phase) {
        return phase < 0 || phase >= PHASE.length ? 0 : PHASE[phase];
    }

    @Override
    public void tick(ModuleContext ctx) {
        long now = ctx.gameTime();
        if (now >= nextCheck) {
            nextCheck = now + Math.max(20, Math.round(ctx.tunable("check_interval")));
            measure(ctx);
        }
        if (rate <= 0) {
            ctx.setStatus(status);
            ctx.sleep((int) Math.max(1, nextCheck - now));
            return;
        }
        long got = ctx.energy().generate(rate);
        ctx.setStatus(got > 0 ? status : Statuses.BUFFER_FULL);
    }

    private void measure(ModuleContext ctx) {
        rate = 0;
        if (!ctx.world().canSeeSky(ctx.face())) {
            status = Statuses.NO_SKY;
            return;
        }
        if (ctx.world().isDaytime()) {
            status = Status.of("LUNAR_DAY", Status.Severity.IDLE);
            return;
        }
        int phase = ctx.world().moonPhase();
        double f = phaseFactor(phase);
        if (f <= 0) {
            status = Status.of("LUNAR_NEW_MOON", Status.Severity.IDLE);
            return;
        }
        double rf = ctx.tunable("rf_per_tick") * f * (ctx.world().isRaining() ? ctx.tunable("rain_factor") : 1.0);
        rate = Math.max(0, Math.round(rf));
        status = rate > 0 ? Status.of("LUNAR_RUNNING", Status.Severity.WORKING, String.valueOf(rate), PHASE_NAMES[phase])
                : Status.of("LUNAR_NEW_MOON", Status.Severity.IDLE);
    }

    @Override
    public String detail() {
        return rate + " RF/t";
    }
}
