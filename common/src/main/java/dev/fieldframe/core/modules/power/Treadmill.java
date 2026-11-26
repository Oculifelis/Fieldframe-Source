package dev.fieldframe.core.modules.power;

import dev.fieldframe.api.data.FfData;
import dev.fieldframe.api.id.FfId;
import dev.fieldframe.api.module.ModuleBehavior;
import dev.fieldframe.api.module.ModuleContext;
import dev.fieldframe.api.resource.ItemStore;
import dev.fieldframe.api.resource.ItemVariant;
import dev.fieldframe.api.status.Status;
import dev.fieldframe.api.status.Statuses;
import dev.fieldframe.api.world.WorldActions;
import dev.fieldframe.core.material.TreadmillMobs;

/**
 * Treadmill: an adult animal in the block in front of the side walks in place (the platform keeps it there) and makes
 * RF from the {@link TreadmillMobs} table, fixed or scaled by its speed (horses). It eats its food from Purple storage,
 * one item every {@code feed_ticks}; unfed it slows to a stop over {@code coast_ticks}.
 */
public final class Treadmill implements ModuleBehavior {
    public static final FfId ID = FfId.ff("treadmill");

    private long nextLook;
    private WorldActions.WalkingAnimal animal;
    private int fedLeft;
    private int coastLeft;
    private long lastRf;

    @Override
    public void tick(ModuleContext ctx) {
        long now = ctx.gameTime();
        WorldActions a = ctx.world().actions();
        if (a == null) {
            ctx.setStatus(Statuses.NO_WORLD_ACCESS);
            ctx.sleep(40);
            return;
        }
        if (now >= nextLook) {
            nextLook = now + Math.max(1, Math.round(ctx.tunable("hold_interval")));
            animal = a.animalOnTreadmill(ctx.face());
        }
        lastRf = 0;
        if (animal == null) {
            ctx.setStatus(Status.of("TREADMILL_EMPTY", Status.Severity.IDLE));
            return;
        }
        if (animal.baby()) {
            ctx.setStatus(Status.of("TREADMILL_BABY", Status.Severity.IDLE));
            return;
        }
        TreadmillMobs.Entry e = TreadmillMobs.of(animal.type());
        if (e == null) {
            ctx.setStatus(Status.of("TREADMILL_UNKNOWN", Status.Severity.IDLE, animal.type().toString()));
            return;
        }
        int coast = (int) Math.max(1, Math.round(ctx.tunable("coast_ticks")));
        if (fedLeft <= 0 && eat(ctx, e)) {
            fedLeft = (int) Math.max(1, Math.round(ctx.tunable("feed_ticks")));
            coastLeft = coast;
        }
        double share;
        if (fedLeft > 0) {
            fedLeft--;
            share = 1.0;
        } else if (coastLeft > 0) {
            coastLeft--;
            share = coastLeft / (double) coast;
        } else {
            ctx.setStatus(Status.of("TREADMILL_HUNGRY", Status.Severity.IDLE, ctx.bindingLabel("food")));
            return;
        }
        long rf = Math.round(e.rf(animal.speed()) * share);
        if (rf <= 0) {
            ctx.setStatus(Status.of("TREADMILL_HUNGRY", Status.Severity.IDLE, ctx.bindingLabel("food")));
            return;
        }
        lastRf = ctx.energy().generate(rf);
        if (lastRf <= 0) {
            ctx.setStatus(Statuses.BUFFER_FULL);
        } else if (share < 1) {
            ctx.setStatus(Status.of("TREADMILL_SLOWING", Status.Severity.WORKING, String.valueOf(lastRf), ctx.bindingLabel("food")));
        } else {
            ctx.setStatus(Status.of("TREADMILL_RUNNING", Status.Severity.WORKING, String.valueOf(lastRf)));
        }
    }

    private static boolean eat(ModuleContext ctx, TreadmillMobs.Entry e) {
        ItemStore food = ctx.items("food");
        if (food == null) {
            return false;
        }
        for (int s = 0; s < food.slots(); s++) {
            ItemVariant v = food.variant(s);
            if (e.eats(v, ctx.world().tags()) && food.extractSlot(s, 1, false) == 1) {
                return true;
            }
        }
        return false;
    }

    /** RF made last tick (tests and displays). */
    public long lastRf() {
        return lastRf;
    }

    @Override
    public String detail() {
        return lastRf + " RF/t";
    }

    @Override
    public FfData save() {
        return new FfData().putInt("fed", fedLeft).putInt("coast", coastLeft);
    }

    @Override
    public void load(FfData d) {
        fedLeft = d.getIntClamped("fed", 0, 0, 1_000_000);
        coastLeft = d.getIntClamped("coast", 0, 0, 1_000_000);
    }
}
