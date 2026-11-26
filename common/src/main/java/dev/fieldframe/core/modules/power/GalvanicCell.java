package dev.fieldframe.core.modules.power;

import dev.fieldframe.api.data.FfData;
import dev.fieldframe.api.id.FfId;
import dev.fieldframe.api.module.ModuleBehavior;
import dev.fieldframe.api.module.ModuleContext;
import dev.fieldframe.api.resource.FluidStore;
import dev.fieldframe.api.resource.FluidVariant;
import dev.fieldframe.api.resource.ItemStore;
import dev.fieldframe.api.resource.ItemVariant;
import dev.fieldframe.api.status.Status;
import dev.fieldframe.api.status.Statuses;
import dev.fieldframe.core.material.Electrodes;

import java.util.Locale;

/**
 * Galvanic Cell: a battery made of two metals. The cathode (Purple storage) stays; anodes (Orange storage) are used
 * up one at a time, each lasting {@code anode_ticks}. Output is {@code rf_per_volt} x the difference between their
 * electrode potentials ({@link Electrodes}), capped at {@code max_rf_per_tick}; zinc against copper is 1.10 V, 27 RF/t.
 * The electrolyte (water, or a fluid in {@code #fieldframe:electrolytes}) comes from the Orange tank,
 * {@code electrolyte_per_tick} mB a tick. The anode only needs to be less noble than the cathode; the anode chosen is
 * the first such item in its storage.
 */
public final class GalvanicCell implements ModuleBehavior {
    public static final FfId ID = FfId.ff("galvanic_cell");
    public static final FfId ELECTROLYTES = FfId.ff("electrolytes");
    public static final FfId WATER = FfId.of("minecraft", "water");
    /** Smallest potential difference the cell works with. */
    public static final double MIN_DIFFERENCE = 0.05;

    private int anodeLeft;
    private int anodeTotal;
    private double anodeVolts;

    @Override
    public double progress() {
        return anodeTotal <= 0 ? 0 : anodeLeft / (double) anodeTotal;
    }

    @Override
    public void tick(ModuleContext ctx) {
        ItemStore anodes = ctx.items("anode");
        ItemStore cathodes = ctx.items("cathode");
        FluidStore tank = ctx.fluids("electrolyte");
        if (anodes == null || cathodes == null || tank == null) {
            ctx.setStatus(Statuses.UNBOUND);
            return;
        }
        Double cathode = null;
        for (int s = 0; s < cathodes.slots() && cathode == null; s++) {
            cathode = Electrodes.volts(cathodes.variant(s), ctx.world().tags());
        }
        if (cathode == null) {
            ctx.setStatus(Status.of("NO_CATHODE", Status.Severity.IDLE, ctx.bindingLabel("cathode")));
            ctx.sleep(40);
            return;
        }
        FluidVariant electrolyte = tank.drainableFluid();
        long use = Math.max(1, Math.round(ctx.tunable("electrolyte_per_tick")));
        if (electrolyte == null || !(electrolyte.id().equals(WATER) || ctx.world().tags().fluidInTag(electrolyte, ELECTROLYTES))
                || tank.drain(electrolyte, use, true) < use) {
            ctx.setStatus(Status.of("NO_ELECTROLYTE", Status.Severity.IDLE, ctx.bindingLabel("electrolyte")));
            ctx.sleep(40);
            return;
        }
        if (anodeLeft <= 0 || anodeVolts > cathode - MIN_DIFFERENCE) {
            if (!nextAnode(ctx, anodes, cathode)) {
                return;
            }
        }
        double volts = cathode - anodeVolts;
        long rf = Math.min(Math.round(ctx.tunable("max_rf_per_tick")), Math.round(volts * ctx.tunable("rf_per_volt")));
        if (ctx.energy().capacity() - ctx.energy().stored() < rf) {
            ctx.setStatus(Statuses.BUFFER_FULL);
            ctx.sleep(10);
            return;
        }
        ctx.energy().generate(rf);
        tank.drain(electrolyte, use, false);
        anodeLeft--;
        ctx.setStatus(Status.of("CELL_RUNNING", Status.Severity.WORKING, String.valueOf(rf), String.format(Locale.ROOT, "%.2f", volts)));
    }

    private boolean nextAnode(ModuleContext ctx, ItemStore anodes, double cathode) {
        for (int s = 0; s < anodes.slots(); s++) {
            ItemVariant v = anodes.variant(s);
            Double a = Electrodes.volts(v, ctx.world().tags());
            if (a == null || a > cathode - MIN_DIFFERENCE) {
                continue;
            }
            if (anodes.extractSlot(s, 1, false) != 1) {
                continue;
            }
            anodeVolts = a;
            anodeTotal = Math.max(1, (int) Math.round(ctx.tunable("anode_ticks")));
            anodeLeft = anodeTotal;
            ctx.trace("anode " + v.id().path() + " (" + a + " V)");
            return true;
        }
        anodeLeft = 0;
        ctx.setStatus(Status.of("NO_ANODE", Status.Severity.IDLE, ctx.bindingLabel("anode"), String.format(Locale.ROOT, "%.2f", cathode)));
        ctx.sleep(40);
        return false;
    }

    @Override
    public String detail() {
        return anodeLeft > 0 ? anodeLeft + "t left on this anode" : "no anode";
    }

    @Override
    public FfData save() {
        return new FfData().putInt("anodeLeft", anodeLeft).putInt("anodeTotal", anodeTotal).putDouble("anodeVolts", anodeVolts);
    }

    @Override
    public void load(FfData d) {
        anodeTotal = d.getIntClamped("anodeTotal", 0, 0, 10_000_000);
        anodeLeft = d.getIntClamped("anodeLeft", 0, 0, anodeTotal);
        anodeVolts = d.getDouble("anodeVolts", 0);
    }
}
