package dev.fieldframe.core.modules;

import dev.fieldframe.api.data.FfData;
import dev.fieldframe.api.module.ModuleBehavior;
import dev.fieldframe.api.module.ModuleContext;
import dev.fieldframe.api.resource.FluidStore;
import dev.fieldframe.api.resource.FluidVariant;
import dev.fieldframe.api.resource.ItemStore;
import dev.fieldframe.api.resource.ItemVariant;
import dev.fieldframe.api.resource.TagLookup;
import dev.fieldframe.api.status.Statuses;
import dev.fieldframe.core.util.FilteredFluidStore;
import dev.fieldframe.core.util.FilteredItemStore;

/**
 * Combined Service Port (field stage): passive item, fluid and RF access through one face. Each resource can be
 * switched on separately and has its own per-tick limit on top of the shared chassis budgets, so one face can
 * never move more than {@code item_limit} items and {@code fluid_limit} mB per tick in total, in either direction.
 * RF follows the chassis RF budgets like a Power Terminal.
 */
public final class ServicePortBehavior implements ModuleBehavior {
    private long tick = Long.MIN_VALUE;
    private long itemsThisTick;
    private long fluidThisTick;

    @Override
    public void tick(ModuleContext ctx) {
        ctx.setStatus(ctx.enabledBy("enable") ? Statuses.PASSIVE : Statuses.signalDisabled(ctx.bindingLabel("enable")));
        ctx.sleep(100);
    }

    private void roll(ModuleContext ctx) {
        long now = ctx.gameTime();
        if (now != tick) {
            tick = now;
            itemsThisTick = 0;
            fluidThisTick = 0;
        }
    }

    @Override
    public ItemStore exposeItems(ModuleContext ctx) {
        ItemStore lane = ctx.items("lane");
        if (lane == null || !ctx.config().bool("items") || !ctx.enabledBy("enable")) {
            return null;
        }
        TagLookup tags = ctx.world() == null ? TagLookup.NONE : ctx.world().tags();
        // Inserts route fuel to the engine that burns it (ModuleContext#itemsIn); extraction is the bound storage.
        ItemStore filtered = new FilteredItemStore(ctx.itemsIn("lane"), true, true, ctx.config().itemFilter("filter"), tags);
        long limit = ctx.config().integer("item_limit");
        return new ItemStore() {
            private int allow(int amount) {
                roll(ctx);
                return (int) Math.max(0, Math.min(amount, limit - itemsThisTick));
            }

            @Override
            public int slots() {
                return filtered.slots();
            }

            @Override
            public ItemVariant variant(int slot) {
                return filtered.variant(slot);
            }

            @Override
            public int count(int slot) {
                return filtered.count(slot);
            }

            @Override
            public int insert(ItemVariant v, int amount, boolean simulate) {
                int n = filtered.insert(v, allow(amount), simulate);
                if (!simulate) {
                    itemsThisTick += n;
                }
                return n;
            }

            @Override
            public int insertSlot(int slot, ItemVariant v, int amount, boolean simulate) {
                int n = filtered.insertSlot(slot, v, allow(amount), simulate);
                if (!simulate) {
                    itemsThisTick += n;
                }
                return n;
            }

            @Override
            public int extractSlot(int slot, int amount, boolean simulate) {
                int n = filtered.extractSlot(slot, allow(amount), simulate);
                if (!simulate) {
                    itemsThisTick += n;
                }
                return n;
            }
        };
    }

    @Override
    public FluidStore exposeFluids(ModuleContext ctx) {
        FluidStore tank = ctx.fluids("tank");
        if (tank == null || !ctx.config().bool("fluids") || !ctx.enabledBy("enable")) {
            return null;
        }
        TagLookup tags = ctx.world() == null ? TagLookup.NONE : ctx.world().tags();
        FluidStore filtered = new FilteredFluidStore(tank, true, true, ctx.config().fluidFilter("fluid_filter"), tags);
        long limit = ctx.config().integer("fluid_limit");
        return new FluidStore() {
            private long allow(long amount) {
                roll(ctx);
                return Math.max(0, Math.min(amount, limit - fluidThisTick));
            }

            @Override
            public int tanks() {
                return filtered.tanks();
            }

            @Override
            public FluidVariant fluid(int t) {
                return filtered.fluid(t);
            }

            @Override
            public long amount(int t) {
                return filtered.amount(t);
            }

            @Override
            public long capacity(int t) {
                return filtered.capacity(t);
            }

            @Override
            public long fill(FluidVariant v, long amount, boolean simulate) {
                long n = filtered.fill(v, allow(amount), simulate);
                if (!simulate) {
                    fluidThisTick += n;
                }
                return n;
            }

            @Override
            public long drain(FluidVariant v, long amount, boolean simulate) {
                long n = filtered.drain(v, allow(amount), simulate);
                if (!simulate) {
                    fluidThisTick += n;
                }
                return n;
            }
        };
    }

    @Override
    public boolean exposeEnergyReceive(ModuleContext ctx) {
        return ctx.config().bool("energy") && ctx.enabledBy("enable");
    }

    @Override
    public boolean exposeEnergyExtract(ModuleContext ctx) {
        return ctx.config().bool("energy") && ctx.enabledBy("enable");
    }

    @Override
    public FfData save() {
        return new FfData();
    }
}
