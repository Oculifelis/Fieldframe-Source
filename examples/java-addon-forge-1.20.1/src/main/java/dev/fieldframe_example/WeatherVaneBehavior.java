package dev.fieldframe_example;

import dev.fieldframe.api.data.FfData;
import dev.fieldframe.api.module.ModuleBehavior;
import dev.fieldframe.api.module.ModuleContext;
import dev.fieldframe.api.status.Status;

/**
 * Weather Vane: a sensor written against the public behavior contract. It reads the world only through
 * {@link dev.fieldframe.api.module.ChassisWorld} (so it also runs in the manual's test bench) and drives a channel.
 */
public final class WeatherVaneBehavior implements ModuleBehavior {
    private boolean lastOn;

    @Override
    public void tick(ModuleContext ctx) {
        boolean rain = ctx.world().isRaining();
        boolean on = switch (ctx.config().choice("trigger")) {
            case "clear" -> !rain;
            case "thunder" -> rain && ctx.world().skyDarkening() >= 8;
            default -> rain;
        };
        lastOn = on;
        ctx.emit("out", on ? 15 : 0);
        ctx.setStatus(Status.of(on ? "SIGNAL_ON" : "SIGNAL_OFF", on ? Status.Severity.WORKING : Status.Severity.IDLE, ctx.config().choice("trigger")));
        ctx.sleep(20);
    }

    @Override
    public String detail() {
        return lastOn ? "triggered" : "calm";
    }

    @Override
    public FfData save() {
        return new FfData().putBool("on", lastOn);
    }

    @Override
    public void load(FfData d) {
        lastOn = d.getBool("on", false);
    }
}
