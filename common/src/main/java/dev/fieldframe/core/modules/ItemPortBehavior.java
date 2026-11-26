package dev.fieldframe.core.modules;

import dev.fieldframe.api.data.FfData;
import dev.fieldframe.api.module.ModuleBehavior;
import dev.fieldframe.api.module.ModuleContext;
import dev.fieldframe.api.resource.ItemFilter;
import dev.fieldframe.api.resource.ItemStore;
import dev.fieldframe.api.status.Statuses;
import dev.fieldframe.core.util.FilteredItemStore;
import dev.fieldframe.core.util.Transfers;

/**
 * Item Port. Modes: {@code passive_in}, {@code passive_out}, {@code passive_both} expose the bound lane on this
 * face (budget-limited); {@code import}/{@code export} actively move one batch per tick from/to the adjacent
 * inventory. Round-robin cursors persist so reloads do not starve slots.
 */
public final class ItemPortBehavior implements ModuleBehavior {
    private int cursor;
    private long movedTotal;

    @Override
    public void tick(ModuleContext ctx) {
        String mode = ctx.config().choice("mode");
        ItemStore lane = ctx.items("lane");
        if (lane == null) {
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
        ItemStore neighbor = ctx.world().neighborItems(ctx.face());
        if (neighbor == null) {
            ctx.setStatus(Statuses.NO_NEIGHBOR);
            ctx.sleep(40);
            return;
        }
        int batch = (int) Math.min(ctx.config().number("batch"), ctx.itemBudget().remaining());
        if (batch <= 0) {
            ctx.setStatus(Statuses.working("budget"));
            return;
        }
        ItemFilter filter = ctx.config().itemFilter("filter");
        Transfers.Moved moved;
        if (mode.equals("import")) {
            // Fuel for an engine on this chassis goes straight to that engine's storage (ModuleContext#itemsIn).
            moved = Transfers.moveItems(neighbor, ctx.itemsIn("lane"), batch, filter, ctx.world().tags(), cursor, ctx);
        } else {
            moved = Transfers.moveItems(lane, neighbor, batch, filter, ctx.world().tags(), cursor, ctx);
        }
        cursor = moved.nextCursor();
        ctx.itemBudget().spend(moved.amount());
        if (moved.amount() > 0) {
            movedTotal += moved.amount();
            ctx.setStatus(Statuses.working(moved.amount() + " items"));
            ctx.trace((mode.equals("import") ? "in " : "out ") + moved.amount() + " " + ctx.bindingLabel("lane"));
        } else if (mode.equals("import")) {
            ctx.setStatus(lane.isEmpty() || hasRoomFor(lane) ? Statuses.NEIGHBOR_EMPTY : Statuses.outputFull(ctx.bindingLabel("lane")));
            ctx.sleep(20);
        } else {
            ctx.setStatus(lane.isEmpty() ? Statuses.noInput(ctx.bindingLabel("lane")) : Statuses.NEIGHBOR_FULL);
            ctx.sleep(20);
        }
    }

    private static boolean hasRoomFor(ItemStore lane) {
        for (int s = 0; s < lane.slots(); s++) {
            if (lane.variant(s) == null) {
                return true;
            }
        }
        return false;
    }

    @Override
    public ItemStore exposeItems(ModuleContext ctx) {
        String mode = ctx.config().choice("mode");
        ItemStore lane = ctx.items("lane");
        if (lane == null || !mode.startsWith("passive") || !ctx.enabledBy("enable")) {
            return null;
        }
        boolean in = mode.equals("passive_in") || mode.equals("passive_both");
        boolean out = mode.equals("passive_out") || mode.equals("passive_both");
        return new FilteredItemStore(in ? ctx.itemsIn("lane") : lane, in, out, ctx.config().itemFilter("filter"), ctx.world() == null ? dev.fieldframe.api.resource.TagLookup.NONE : ctx.world().tags());
    }

    @Override
    public String detail() {
        return movedTotal + " moved";
    }

    @Override
    public FfData save() {
        return new FfData().putInt("cursor", cursor).putLong("moved", movedTotal);
    }

    @Override
    public void load(FfData d) {
        cursor = Math.max(0, d.getInt("cursor", 0));
        movedTotal = Math.max(0, d.getLong("moved", 0));
    }
}
