package dev.fieldframe.core.modules;

import dev.fieldframe.api.module.ModuleBehavior;
import dev.fieldframe.api.module.ModuleContext;
import dev.fieldframe.api.status.Statuses;

/**
 * Inventory / Tank Access Panel. Grants players access to the selected lanes only; the chassis GUI never
 * offers lanes that no installed panel permits. It occupies a face, which is the cost of player access.
 */
public final class AccessPanelBehavior implements ModuleBehavior {
    private final boolean tanks;

    public AccessPanelBehavior(boolean tanks) {
        this.tanks = tanks;
    }

    @Override
    public void tick(ModuleContext ctx) {
        ctx.setStatus(Statuses.PASSIVE);
        ctx.sleep(400);
    }

    private static int[] selected(ModuleContext ctx) {
        int n = 0;
        int[] tmp = new int[3];
        if (ctx.config().bool("a")) {
            tmp[n++] = 0;
        }
        if (ctx.config().bool("b")) {
            tmp[n++] = 1;
        }
        if (ctx.config().bool("c")) {
            tmp[n++] = 2;
        }
        return java.util.Arrays.copyOf(tmp, n);
    }

    @Override
    public int[] playerAccessibleLanes(ModuleContext ctx) {
        return tanks ? new int[0] : selected(ctx);
    }

    @Override
    public int[] playerAccessibleTanks(ModuleContext ctx) {
        return tanks ? selected(ctx) : new int[0];
    }
}
