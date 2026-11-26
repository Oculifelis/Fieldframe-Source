package dev.fieldframe.core.modules;

import dev.fieldframe.api.module.ModuleBehavior;
import dev.fieldframe.api.module.ModuleContext;
import dev.fieldframe.api.resource.EnergyStore;
import dev.fieldframe.api.status.Statuses;
import dev.fieldframe.core.util.Transfers;

/**
 * Power Terminal: exposes the chassis RF buffer on its face through the platform's native energy capability.
 * Modes: {@code receive}, {@code provide} (passive extract), {@code push} (actively sends to the neighbour).
 * All terminals share the chassis' directional RF budgets.
 */
public final class PowerTerminalBehavior implements ModuleBehavior {
    @Override
    public void tick(ModuleContext ctx) {
        String mode = ctx.config().choice("mode");
        if (!mode.equals("push")) {
            ctx.setStatus(Statuses.PASSIVE);
            ctx.sleep(200);
            return;
        }
        if (!ctx.enabledBy("enable")) {
            ctx.setStatus(Statuses.signalDisabled(ctx.bindingLabel("enable")));
            return;
        }
        if (!ctx.world().isNeighborLoaded(ctx.face())) {
            ctx.setStatus(Statuses.NEIGHBOR_UNLOADED);
            ctx.sleep(40);
            return;
        }
        EnergyStore neighbor = ctx.world().neighborEnergy(ctx.face());
        if (neighbor == null || !neighbor.canReceive()) {
            ctx.setStatus(Statuses.NO_NEIGHBOR);
            ctx.sleep(40);
            return;
        }
        EnergyStore buffer = ctx.terminalEnergy(false, true);
        long moved = Transfers.moveEnergy(buffer, neighbor, (long) ctx.tunable("push_rf_per_tick"));
        if (moved > 0) {
            ctx.setStatus(Statuses.working(moved + " RF/t"));
        } else {
            ctx.setStatus(buffer.stored() == 0 ? Statuses.noEnergy(1) : Statuses.NEIGHBOR_FULL);
            ctx.sleep(10);
        }
    }

    @Override
    public boolean exposeEnergyReceive(ModuleContext ctx) {
        return ctx.config().choice("mode").equals("receive") && ctx.enabledBy("enable");
    }

    @Override
    public boolean exposeEnergyExtract(ModuleContext ctx) {
        return ctx.config().choice("mode").equals("provide") && ctx.enabledBy("enable");
    }
}
