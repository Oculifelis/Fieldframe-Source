package dev.fieldframe.core.modules;

import dev.fieldframe.api.module.ModuleBehavior;
import dev.fieldframe.api.module.ModuleContext;
import dev.fieldframe.api.status.Statuses;

/**
 * Solar Collector: generates {@code rf_per_tick} in daylight with open sky above its face, reduced by
 * {@code rain_factor} in rain. Sky checks are cached and refreshed every {@code check_interval} ticks
 * (and on wake), so idle collectors cost almost nothing.
 */
public final class SolarCollectorBehavior implements ModuleBehavior {
    private long nextCheck;
    private boolean sky;
    private boolean day;
    private boolean rain;

    @Override
    public void tick(ModuleContext ctx) {
        long now = ctx.gameTime();
        if (now >= nextCheck) {
            sky = ctx.world().canSeeSky(ctx.face());
            day = ctx.world().isDaytime();
            rain = ctx.world().isRaining();
            nextCheck = now + Math.round(ctx.tunable("check_interval"));
        }
        if (!sky) {
            ctx.setStatus(Statuses.NO_SKY);
            ctx.sleep((int) Math.max(1, nextCheck - now));
            return;
        }
        if (!day) {
            ctx.setStatus(Statuses.NIGHT);
            ctx.sleep((int) Math.max(1, nextCheck - now));
            return;
        }
        double rate = ctx.tunable("rf_per_tick") * (rain ? ctx.tunable("rain_factor") : 1.0);
        long got = ctx.energy().generate(Math.round(rate));
        ctx.setStatus(got > 0 ? Statuses.working(got + " RF/t") : Statuses.BUFFER_FULL);
    }
}
