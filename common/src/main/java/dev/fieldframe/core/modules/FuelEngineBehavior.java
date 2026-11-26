package dev.fieldframe.core.modules;

import dev.fieldframe.api.data.FfData;
import dev.fieldframe.api.module.ModuleBehavior;
import dev.fieldframe.api.module.ModuleContext;
import dev.fieldframe.api.resource.ItemStore;
import dev.fieldframe.api.resource.ItemVariant;
import dev.fieldframe.api.status.Statuses;

/**
 * Fuel Engine: burns one furnace fuel item at a time from its bound lane into the RF buffer.
 * Engine burn duration = vanilla burn ticks / {@code burn_divisor}; output {@code rf_per_tick} while burning.
 * Default: coal (1600 burn ticks) = 400 engine ticks x 40 RF/t = 16,000 RF. It pauses (keeping the
 * remaining burn) rather than wasting fuel when the buffer is full.
 */
public final class FuelEngineBehavior implements ModuleBehavior {
    @Override
    public boolean burns(dev.fieldframe.api.resource.ItemVariant item, dev.fieldframe.api.module.ChassisWorld world) {
        return world.fuelBurnTicks(item) > 0;
    }

    private int burnLeft;
    private int burnTotal;

    @Override
    public double progress() {
        return burnTotal <= 0 ? 0 : burnLeft / (double) burnTotal;
    }

    @Override
    public void tick(ModuleContext ctx) {
        long rate = Math.round(ctx.tunable("rf_per_tick"));
        if (!ctx.enabledBy("enable")) {
            ctx.setStatus(Statuses.signalDisabled(ctx.bindingLabel("enable")));
            return;
        }
        if (ctx.energy().capacity() - ctx.energy().stored() < rate) {
            ctx.setStatus(Statuses.BUFFER_FULL);
            ctx.sleep(10);
            return;
        }
        if (burnLeft <= 0 && !refuel(ctx)) {
            return;
        }
        long got = ctx.energy().generate(rate);
        burnLeft--;
        ctx.emit("running", 15);
        ctx.setStatus(Statuses.working(got + " RF/t"));
    }

    private boolean refuel(ModuleContext ctx) {
        ItemStore fuel = ctx.items("fuel");
        if (fuel == null) {
            ctx.setStatus(Statuses.UNBOUND);
            return false;
        }
        double divisor = ctx.tunable("burn_divisor");
        for (int s = 0; s < fuel.slots(); s++) {
            ItemVariant v = fuel.variant(s);
            if (v == null) {
                continue;
            }
            int burn = ctx.world().fuelBurnTicks(v);
            if (burn <= 0) {
                continue;
            }
            ItemVariant remainder = ctx.world().fuelRemainder(v);
            // Bucket-style fuels need room for their container before they are consumed.
            if (remainder != null && fuel.count(s) > 1 && fuel.insert(remainder, 1, true) < 1) {
                continue;
            }
            if (fuel.extractSlot(s, 1, false) != 1) {
                continue;
            }
            if (remainder != null && fuel.insert(remainder, 1, false) < 1) {
                ctx.spill(remainder, 1);
            }
            burnTotal = Math.max(1, (int) Math.round(burn / divisor));
            if (remainder != null) {
                // Bucket fuels (lava) burn no longer than a bucket is worth in the Fluid Engine.
                burnTotal = Math.min(burnTotal, (int) Math.round(ctx.tunable("bucket_max_ticks")));
            }
            burnLeft = burnTotal;
            ctx.trace("burn " + v.id().path() + " for " + burnTotal + "t");
            return true;
        }
        ctx.setStatus(fuel.isEmpty() ? Statuses.NO_FUEL : Statuses.invalidRecipe(ctx.bindingLabel("fuel")));
        ctx.sleep(40);
        return false;
    }

    @Override
    public String detail() {
        return burnLeft > 0 ? burnLeft + "t left" : "cold";
    }

    @Override
    public FfData save() {
        return new FfData().putInt("burnLeft", burnLeft).putInt("burnTotal", burnTotal);
    }

    @Override
    public void load(FfData d) {
        burnTotal = d.getIntClamped("burnTotal", 0, 0, 1_000_000);
        burnLeft = d.getIntClamped("burnLeft", 0, 0, burnTotal);
    }
}
