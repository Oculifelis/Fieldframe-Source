package dev.fieldframe.core.modules;

import dev.fieldframe.api.data.FfData;
import dev.fieldframe.api.module.ModuleBehavior;
import dev.fieldframe.api.module.ModuleContext;
import dev.fieldframe.api.resource.FluidFilter;
import dev.fieldframe.api.resource.FluidStore;
import dev.fieldframe.api.resource.TagLookup;
import dev.fieldframe.api.status.Statuses;
import dev.fieldframe.core.util.FilteredFluidStore;
import dev.fieldframe.core.util.Transfers;

/** Fluid Port: same modes as the Item Port, for one bound reservoir. Fluid identity is always preserved. */
public final class FluidPortBehavior implements ModuleBehavior {
    private long movedTotal;

    @Override
    public void tick(ModuleContext ctx) {
        String mode = ctx.config().choice("mode");
        FluidStore tank = ctx.fluids("tank");
        if (tank == null) {
            ctx.setStatus(Statuses.UNBOUND);
            return;
        }
        if (mode.startsWith("passive")) {
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
        FluidStore neighbor = ctx.world().neighborFluids(ctx.face());
        if (neighbor == null) {
            ctx.setStatus(Statuses.NO_NEIGHBOR);
            ctx.sleep(40);
            return;
        }
        long rate = Math.min(ctx.config().number("rate"), ctx.fluidBudget().remaining());
        FluidFilter filter = ctx.config().fluidFilter("filter");
        long moved = mode.equals("import")
                ? Transfers.moveFluid(neighbor, tank, rate, filter, ctx.world().tags(), ctx)
                : Transfers.moveFluid(tank, neighbor, rate, filter, ctx.world().tags(), ctx);
        ctx.fluidBudget().spend(moved);
        if (moved > 0) {
            movedTotal += moved;
            ctx.setStatus(Statuses.working(moved + " mB"));
        } else if (mode.equals("import")) {
            FluidStore t = tank;
            boolean full = t.amount(0) >= t.capacity(0);
            ctx.setStatus(full ? Statuses.outputFull(ctx.bindingLabel("tank")) : Statuses.NEIGHBOR_EMPTY);
            ctx.sleep(20);
        } else {
            ctx.setStatus(tank.amount(0) == 0 ? Statuses.noFluid(ctx.bindingLabel("tank")) : Statuses.NEIGHBOR_FULL);
            ctx.sleep(20);
        }
    }

    @Override
    public FluidStore exposeFluids(ModuleContext ctx) {
        String mode = ctx.config().choice("mode");
        FluidStore tank = ctx.fluids("tank");
        if (tank == null || !mode.startsWith("passive") || !ctx.enabledBy("enable")) {
            return null;
        }
        boolean in = mode.equals("passive_in") || mode.equals("passive_both");
        boolean out = mode.equals("passive_out") || mode.equals("passive_both");
        return new FilteredFluidStore(tank, in, out, ctx.config().fluidFilter("filter"), ctx.world() == null ? TagLookup.NONE : ctx.world().tags());
    }

    @Override
    public FfData save() {
        return new FfData().putLong("moved", movedTotal);
    }

    @Override
    public void load(FfData d) {
        movedTotal = Math.max(0, d.getLong("moved", 0));
    }
}
