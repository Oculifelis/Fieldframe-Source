package dev.fieldframe.core.modules.power;

import dev.fieldframe.api.face.LocalFace;
import dev.fieldframe.api.id.FfId;
import dev.fieldframe.api.module.ModuleBehavior;
import dev.fieldframe.api.module.ModuleContext;
import dev.fieldframe.api.status.Status;
import dev.fieldframe.api.status.Statuses;
import dev.fieldframe.api.world.WorkTarget;
import dev.fieldframe.api.world.WorldActions;
import dev.fieldframe.api.world.WorldPos;
import dev.fieldframe.core.world.SharedSources;

/**
 * Geothermal Probe: taps the ground heat under the machine (bottom face).
 * <ul>
 *   <li>Depth: full below {@code full_depth_y}, half up to {@code half_depth_y}, nothing above; the Nether is hot
 *       everywhere ({@code nether_factor} scales the output, 1 by default).</li>
 *   <li>Heat: a quarter of the output from the ground itself; the rest grows with the hot blocks (at least
 *       {@value #HOT_K} K in the temperature table, such as lava and magma) in the {@value #BOX}-wide,
 *       {@value #DEPTH}-deep box below, full at {@value #HOT_FOR_FULL} of them.</li>
 *   <li>Probes within {@code share_radius} blocks (about a 3 by 3 chunk area) split the area's heat
 *       ({@link SharedSources}).</li>
 * </ul>
 */
public final class GeothermalProbe implements ModuleBehavior {
    public static final FfId ID = FfId.ff("geothermal_probe");
    public static final int BOX = 5;
    public static final int DEPTH = 4;
    public static final int HOT_K = 1000;
    public static final int HOT_FOR_FULL = 8;

    private long nextCheck;
    private long rate;
    private Status status = Statuses.idle();

    @Override
    public void tick(ModuleContext ctx) {
        long now = ctx.gameTime();
        if (now >= nextCheck) {
            long interval = Math.max(20, Math.round(ctx.tunable("check_interval")));
            nextCheck = now + interval;
            measure(ctx, now, interval);
        }
        if (rate <= 0) {
            ctx.setStatus(status);
            ctx.sleep((int) Math.max(1, nextCheck - now));
            return;
        }
        long got = ctx.energy().generate(rate);
        ctx.setStatus(got > 0 ? status : Statuses.BUFFER_FULL);
    }

    /** Depth share: 1 at or below {@code full}, 0.5 up to {@code half}, 0 above. */
    public static double depthFactor(int y, double full, double half) {
        return y <= full ? 1.0 : y <= half ? 0.5 : 0.0;
    }

    private void measure(ModuleContext ctx, long now, long interval) {
        rate = 0;
        WorldPos pos = ctx.world().position();
        WorldActions a = ctx.world().actions();
        if (pos == null || a == null) {
            status = Statuses.NO_WORLD_ACCESS;
            return;
        }
        boolean nether = pos.dimension().equals("minecraft:the_nether");
        double depth = nether ? 1.0 : depthFactor(pos.y(), ctx.tunable("full_depth_y"), ctx.tunable("half_depth_y"));
        if (depth <= 0) {
            status = Status.of("TOO_HIGH_FOR_HEAT", Status.Severity.IDLE, String.valueOf(Math.round(ctx.tunable("half_depth_y"))));
            return;
        }
        int hot = 0;
        int half = BOX / 2;
        for (int d = 1; d <= DEPTH; d++) {
            for (int r = -half; r <= half; r++) {
                for (int u = -half; u <= half; u++) {
                    if (a.temperature(new WorkTarget(LocalFace.BOTTOM, d, r, u)) >= HOT_K) {
                        hot++;
                    }
                }
            }
        }
        double heat = 0.25 + 0.75 * Math.min(1.0, hot / (double) HOT_FOR_FULL);
        int sharing = SharedSources.claim("ground_heat", pos, (int) Math.round(ctx.tunable("share_radius")), now, interval * 2);
        double rf = ctx.tunable("max_rf_per_tick") * depth * heat * (nether ? ctx.tunable("nether_factor") : 1.0) / Math.max(1, sharing);
        rate = Math.max(0, Math.round(rf));
        int pct = (int) Math.round(heat * 100);
        status = rate <= 0 ? Status.of("TOO_HIGH_FOR_HEAT", Status.Severity.IDLE, String.valueOf(Math.round(ctx.tunable("half_depth_y"))))
                : Status.of(sharing > 1 ? "GROUND_HEAT_SHARED" : "GROUND_HEAT_RUNNING", Status.Severity.WORKING, String.valueOf(rate),
                String.valueOf(pct), String.valueOf(sharing));
    }

    /** RF a tick from the last measurement. */
    public long rate() {
        return rate;
    }

    @Override
    public String detail() {
        return rate + " RF/t";
    }
}
