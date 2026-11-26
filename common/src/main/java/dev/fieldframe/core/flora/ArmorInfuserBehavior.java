package dev.fieldframe.core.flora;

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

/**
 * Armor Infuser module (FLORA's Fluidic Armor Infuser as a Fieldframe module). Each tick it moves up to
 * {@code mb_per_tick} of an effect fluid from the bound reservoir into the first fluid-armor piece in the bound lane
 * that has room, paying {@code rf_per_mb}. Only effect fluids are accepted (FLORA Reborn rule): the seven FLORA fluids
 * and every fluid with a fluid armor effect ({@link FluidArmorEffects}). Only what fits is drained (FLORA Reborn
 * fill-amount fix).
 */
public final class ArmorInfuserBehavior implements ModuleBehavior {
    private long infused;

    /** Fluid armor item ids are {@code fieldframe:<tier>_fluid_<piece>}. */
    public static Flora.Tier tierOf(FfId id) {
        if (id == null || !id.namespace().equals("fieldframe")) {
            return null;
        }
        for (Flora.Tier t : Flora.Tier.values()) {
            for (Flora.Piece p : Flora.Piece.values()) {
                if (id.path().equals(itemPath(t, p))) {
                    return t;
                }
            }
        }
        return null;
    }

    public static String itemPath(Flora.Tier t, Flora.Piece p) {
        return t.key + "_fluid_" + p.name().toLowerCase(java.util.Locale.ROOT);
    }

    @Override
    public void tick(ModuleContext ctx) {
        ItemStore lane = ctx.items("armor");
        FluidStore tank = ctx.fluids("fluid");
        if (lane == null || tank == null) {
            ctx.setStatus(Statuses.UNBOUND);
            return;
        }
        if (!ctx.enabledBy("enable")) {
            ctx.setStatus(Statuses.signalDisabled(ctx.bindingLabel("enable")));
            return;
        }
        FluidVariant fv = tank.drainableFluid();
        dev.fieldframe.api.id.FfId fluid = fv == null || !FluidArmorEffects.accepts(fv.id(), ctx.world().tags()) ? null : fv.id();
        if (fluid == null) {
            ctx.setStatus(fv == null ? Statuses.noFluid(ctx.bindingLabel("fluid")) : Statuses.wrongFluid("effect fluid"));
            ctx.sleep(40);
            return;
        }
        for (int s = 0; s < lane.slots(); s++) {
            ItemVariant v = lane.variant(s);
            Flora.Tier tier = v == null ? null : tierOf(v.id());
            if (tier == null || lane.count(s) != 1) {
                continue;
            }
            Flora.Charge charge = Flora.Charge.load(ctx.world().itemData(v, Flora.Charge.DATA_KEY), tier.capacity);
            long room = charge.fill(fluid, Long.MAX_VALUE, tier.capacity, true);
            if (room <= 0) {
                continue;
            }
            long rfPerMb = Math.max(0, Math.round(ctx.tunable("rf_per_mb")));
            long byEnergy = rfPerMb == 0 ? Long.MAX_VALUE : ctx.energy().stored() / rfPerMb;
            long amount = Math.min(Math.min(room, Math.round(ctx.tunable("mb_per_tick"))), byEnergy);
            amount = Math.min(amount, tank.drain(fv, amount, true));
            if (amount <= 0) {
                ctx.setStatus(byEnergy <= 0 ? Statuses.noEnergy(rfPerMb) : Statuses.noFluid(ctx.bindingLabel("fluid")));
                ctx.sleep(20);
                return;
            }
            charge.fill(fluid, amount, tier.capacity, false);
            ItemVariant updated = ctx.world().withItemData(v, Flora.Charge.DATA_KEY, charge.save());
            if (updated == null) {
                ctx.setStatus(Statuses.NO_WORLD_ACCESS);
                ctx.sleep(100);
                return;
            }
            // Replace the piece in its slot (same tick, nothing else touches the slot), then pay.
            if (lane.extractSlot(s, 1, false) != 1) {
                continue;
            }
            if (lane.insertSlot(s, updated, 1, false) != 1) {
                ctx.spill(updated, 1);
            }
            tank.drain(fv, amount, false);
            ctx.energy().consume(amount * rfPerMb);
            infused += amount;
            ctx.setStatus(Statuses.working(amount + " mB " + fluid.path()));
            return;
        }
        ctx.setStatus(Status.of("NOTHING_TO_DO", Status.Severity.IDLE));
        ctx.sleep(20);
    }

    @Override
    public String detail() {
        return infused + " mB infused";
    }

    @Override
    public FfData save() {
        return new FfData().putLong("infused", infused);
    }

    @Override
    public void load(FfData d) {
        infused = Math.max(0, d.getLong("infused", 0));
    }
}
