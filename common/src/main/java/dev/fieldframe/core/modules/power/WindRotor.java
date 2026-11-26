package dev.fieldframe.core.modules.power;

import dev.fieldframe.api.id.FfId;
import dev.fieldframe.api.module.ChassisWorld;
import dev.fieldframe.api.module.ModuleBehavior;
import dev.fieldframe.api.module.ModuleContext;
import dev.fieldframe.api.status.Status;
import dev.fieldframe.api.status.Statuses;
import dev.fieldframe.api.world.WorkTarget;
import dev.fieldframe.api.world.WorldActions;
import dev.fieldframe.api.world.WorldPos;
import dev.fieldframe.core.world.SharedSources;

/**
 * Wind Rotor: a side-mounted rotor whose output depends on height, open air in front and the weather.
 * <ul>
 *   <li>Height: nothing at or below {@code min_height}, rising linearly to full at {@code full_height}.</li>
 *   <li>Open air: the {@value #BOX_DEPTH}-deep, {@value #BOX_SIZE}-wide box in front of the face; every solid block
 *       there takes its share.</li>
 *   <li>Weather: {@code clear_factor}, {@code rain_factor}, {@code thunder_factor}; dimension factors for the
 *       Nether and the End.</li>
 *   <li>Rotors within {@code share_radius} blocks of each other split the wind ({@link SharedSources}), so a crowded
 *       hill is no better than a few spaced-out towers.</li>
 * </ul>
 */
public final class WindRotor implements ModuleBehavior {
    public static final FfId ID = FfId.ff("wind_rotor");
    public static final int BOX_DEPTH = 4;
    public static final int BOX_SIZE = 5;

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

    private void measure(ModuleContext ctx, long now, long interval) {
        rate = 0;
        ChassisWorld w = ctx.world();
        WorldPos pos = w.position();
        WorldActions a = w.actions();
        if (pos == null || a == null) {
            status = Statuses.NO_WORLD_ACCESS;
            return;
        }
        double height = heightFactor(pos.y(), ctx.tunable("min_height"), ctx.tunable("full_height"));
        if (height <= 0) {
            status = Status.of("TOO_LOW_FOR_WIND", Status.Severity.IDLE, String.valueOf(Math.round(ctx.tunable("min_height"))));
            return;
        }
        int air = 0;
        int total = 0;
        int half = BOX_SIZE / 2;
        for (int d = 1; d <= BOX_DEPTH; d++) {
            for (int r = -half; r <= half; r++) {
                for (int u = -half; u <= half; u++) {
                    WorldActions.BlockInfo info = a.probe(new WorkTarget(ctx.face(), d, r, u));
                    total++;
                    if (info.access().ok() && (info.air() || info.replaceable() && info.fluid() == null)) {
                        air++;
                    }
                }
            }
        }
        double open = total == 0 ? 0 : air / (double) total;
        double weather = w.isThundering() ? ctx.tunable("thunder_factor") : w.isRaining() ? ctx.tunable("rain_factor") : ctx.tunable("clear_factor");
        double dim = pos.dimension().equals("minecraft:the_nether") ? ctx.tunable("nether_factor")
                : pos.dimension().equals("minecraft:the_end") ? ctx.tunable("end_factor") : 1.0;
        int sharing = SharedSources.claim("wind", pos, (int) Math.round(ctx.tunable("share_radius")), now, interval * 2);
        double rf = ctx.tunable("max_rf_per_tick") * height * open * weather * dim / Math.max(1, sharing);
        rate = Math.max(0, Math.round(rf));
        if (open <= 0) {
            status = Status.of("WIND_BLOCKED", Status.Severity.IDLE);
        } else if (rate <= 0) {
            status = Status.of("NO_WIND", Status.Severity.IDLE);
        } else {
            status = Status.of(sharing > 1 ? "WIND_SHARED" : "WIND_RUNNING", Status.Severity.WORKING, String.valueOf(rate),
                    String.valueOf(Math.round(open * 100)), String.valueOf(sharing));
        }
    }

    /** 0 at or below {@code min}, 1 at or above {@code full}, linear between. */
    public static double heightFactor(int y, double min, double full) {
        if (y <= min) {
            return 0;
        }
        if (y >= full || full <= min) {
            return 1;
        }
        return (y - min) / (full - min);
    }

    @Override
    public String detail() {
        return rate + " RF/t";
    }
}
