package dev.fieldframe.core.modules.power;

import dev.fieldframe.api.data.FfData;
import dev.fieldframe.api.id.FfId;
import dev.fieldframe.api.module.ModuleBehavior;
import dev.fieldframe.api.module.ModuleContext;
import dev.fieldframe.api.status.Status;
import dev.fieldframe.api.status.Statuses;

/**
 * Hand Crank: the first RF before any fuel. Each empty-hand use of the face turns the crank for {@code ticks_per_turn}
 * ticks (queued up to {@code max_queue}), making {@code rf_per_tick} while it turns. Holding use down keeps it turning
 * at the full rate and no faster; the platform charges the player hunger for every turn.
 */
public final class HandCrank implements ModuleBehavior {
    public static final FfId ID = FfId.ff("hand_crank");

    private int pendingTurns;
    private int turning;

    @Override
    public boolean onPlayerUse(boolean sneaking) {
        pendingTurns = Math.min(pendingTurns + 1, 8);
        return true;
    }

    @Override
    public void tick(ModuleContext ctx) {
        if (pendingTurns > 0) {
            int cap = (int) Math.round(ctx.tunable("max_queue"));
            turning = Math.min(cap, turning + pendingTurns * (int) Math.round(ctx.tunable("ticks_per_turn")));
            pendingTurns = 0;
        }
        if (turning <= 0) {
            ctx.setStatus(Status.of("CRANK_STILL", Status.Severity.IDLE, ""));
            return;
        }
        turning--;
        long got = ctx.energy().generate(Math.round(ctx.tunable("rf_per_tick")));
        ctx.emit("running", 15);
        ctx.setStatus(got > 0 ? Statuses.working(got + " RF/t") : Statuses.BUFFER_FULL);
    }

    @Override
    public double progress() {
        return turning > 0 ? 1 : 0;
    }

    @Override
    public String detail() {
        return turning > 0 ? "turning, " + turning + "t" : "still";
    }

    @Override
    public FfData save() {
        return new FfData().putInt("turning", turning);
    }

    @Override
    public void load(FfData d) {
        turning = Math.max(0, d.getInt("turning", 0));
    }
}
