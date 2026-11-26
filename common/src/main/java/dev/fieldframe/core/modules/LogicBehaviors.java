package dev.fieldframe.core.modules;

import dev.fieldframe.api.data.FfData;
import dev.fieldframe.api.module.ModuleBehavior;
import dev.fieldframe.api.module.ModuleContext;
import dev.fieldframe.api.resource.FluidStore;
import dev.fieldframe.api.resource.ItemStore;
import dev.fieldframe.api.status.Status;

/**
 * Redstone, sensing and control behaviors. All run in the CONTROL phase, read the start-of-tick channel
 * snapshot and drive next-tick values, so no combination of them can recurse within a tick. None of them
 * consume RF: passive logic must work even while the machine is unpowered (e.g. to enable its own generator).
 */
public final class LogicBehaviors {
    private LogicBehaviors() {
    }

    static Status on(int v) {
        return v > 0 ? Status.of("SIGNAL_ON", Status.Severity.WORKING, String.valueOf(v)) : Status.of("SIGNAL_OFF", Status.Severity.IDLE);
    }

    /** Signal Terminal: {@code input} copies world redstone on its face to a channel; {@code output} the reverse. */
    public static final class SignalTerminal implements ModuleBehavior {
        private int out;

        @Override
        public void tick(ModuleContext ctx) {
            if (ctx.config().choice("mode").equals("input")) {
                int v = ctx.world().redstoneInput(ctx.face());
                if (!ctx.config().bool("analog")) {
                    v = v > 0 ? 15 : 0;
                }
                if (ctx.config().bool("invert")) {
                    v = 15 - v;
                }
                ctx.emit("channel_out", v);
                out = 0;
                ctx.setStatus(on(v));
            } else {
                int v = Math.max(0, ctx.signal("channel_in"));
                if (!ctx.config().bool("analog")) {
                    v = v > 0 ? 15 : 0;
                }
                if (ctx.config().bool("invert")) {
                    v = 15 - v;
                }
                out = v;
                ctx.setStatus(on(v));
            }
        }

        @Override
        public int redstoneOutput() {
            return out;
        }

        @Override
        public FfData save() {
            return new FfData().putInt("out", out);
        }

        @Override
        public void load(FfData d) {
            out = d.getIntClamped("out", 0, 0, 15);
        }
    }

    /** Logic Plate: Boolean gate over channels a/b (b ignored by NOT and BUFFER). */
    public static final class LogicPlate implements ModuleBehavior {
        public static boolean eval(String op, boolean a, boolean b) {
            return switch (op) {
                case "and" -> a && b;
                case "or" -> a || b;
                case "xor" -> a ^ b;
                case "nand" -> !(a && b);
                case "nor" -> !(a || b);
                case "xnor" -> a == b;
                case "not" -> !a;
                case "buffer" -> a;
                default -> throw new IllegalArgumentException("unknown logic op " + op);
            };
        }

        @Override
        public void tick(ModuleContext ctx) {
            boolean a = ctx.signal("a") > 0;
            boolean b = ctx.signal("b") > 0;
            boolean r = eval(ctx.config().choice("op"), a, b);
            int v = r ? 15 : 0;
            ctx.emit("out", v);
            ctx.setStatus(on(v));
        }
    }

    /**
     * Memory Plate (SR latch with toggle). Simultaneous inputs: reset wins over set, set wins over toggle.
     * Toggle acts on rising edges only. State persists across save/load.
     */
    public static final class MemoryPlate implements ModuleBehavior {
        private boolean state;
        private boolean lastToggle;

        public static boolean next(boolean state, boolean set, boolean reset, boolean toggleRise) {
            if (reset) {
                return false;
            }
            if (set) {
                return true;
            }
            return toggleRise != state;
        }

        @Override
        public void tick(ModuleContext ctx) {
            boolean t = ctx.signal("toggle") > 0;
            state = next(state, ctx.signal("set") > 0, ctx.signal("reset") > 0, t && !lastToggle);
            lastToggle = t;
            ctx.emit("out", state ? 15 : 0);
            ctx.setStatus(on(state ? 15 : 0));
        }

        @Override
        public FfData save() {
            return new FfData().putBool("state", state).putBool("lastToggle", lastToggle);
        }

        @Override
        public void load(FfData d) {
            state = d.getBool("state", false);
            lastToggle = d.getBool("lastToggle", false);
        }
    }

    /**
     * Timer/Counter Plate. Timer: every {@code period} ticks emit a pulse of {@code pulse} ticks while enabled.
     * Counter: counts rising edges on {@code count}; emits while count &gt;= {@code target}; {@code reset} clears.
     */
    public static final class TimerCounter implements ModuleBehavior {
        private long elapsed;
        private int pulseLeft;
        private long count;
        private boolean lastCount;

        @Override
        public void tick(ModuleContext ctx) {
            if (ctx.signal("reset") > 0) {
                elapsed = 0;
                count = 0;
                pulseLeft = 0;
            }
            if (ctx.config().choice("mode").equals("timer")) {
                if (ctx.enabledBy("enable")) {
                    elapsed++;
                    long period = ctx.config().number("period");
                    if (elapsed >= period) {
                        elapsed = 0;
                        pulseLeft = (int) ctx.config().number("pulse");
                    }
                }
                int v = pulseLeft > 0 ? 15 : 0;
                if (pulseLeft > 0) {
                    pulseLeft--;
                }
                ctx.emit("out", v);
                ctx.setStatus(Status.of(v > 0 ? "SIGNAL_ON" : "TIMING", v > 0 ? Status.Severity.WORKING : Status.Severity.IDLE,
                        elapsed + "/" + ctx.config().number("period")));
            } else {
                boolean c = ctx.signal("count") > 0;
                if (c && !lastCount && count < Integer.MAX_VALUE) {
                    count++;
                }
                lastCount = c;
                boolean reached = count >= ctx.config().number("target");
                ctx.emit("out", reached ? 15 : 0);
                ctx.setStatus(Status.of(reached ? "SIGNAL_ON" : "COUNTING", reached ? Status.Severity.WORKING : Status.Severity.IDLE,
                        count + "/" + ctx.config().number("target")));
            }
        }

        public long count() {
            return count;
        }

        @Override
        public FfData save() {
            return new FfData().putLong("elapsed", elapsed).putInt("pulseLeft", pulseLeft).putLong("count", count).putBool("lastCount", lastCount);
        }

        @Override
        public void load(FfData d) {
            elapsed = Math.max(0, d.getLong("elapsed", 0));
            pulseLeft = d.getIntClamped("pulseLeft", 0, 0, 1200);
            count = Math.max(0, d.getLong("count", 0));
            lastCount = d.getBool("lastCount", false);
        }
    }

    /**
     * Resource Sensor with hysteresis. Measures a percentage 0..100 of a lane, reservoir or the RF buffer.
     * {@code on_below}: turns on at value &lt;= low, off at value &gt;= high. {@code on_above}: on at &gt;= high, off at &lt;= low.
     * {@code analog} emits the level scaled to 0..15 instead.
     */
    public static final class ResourceSensor implements ModuleBehavior {
        private boolean active;
        private int lastPercent;

        public static boolean hysteresis(boolean active, int value, int low, int high, boolean onBelow) {
            if (onBelow) {
                if (value <= low) {
                    return true;
                }
                if (value >= high) {
                    return false;
                }
            } else {
                if (value >= high) {
                    return true;
                }
                if (value <= low) {
                    return false;
                }
            }
            return active;
        }

        static int percent(ModuleContext ctx) {
            String src = ctx.config().choice("source");
            switch (src) {
                case "items" -> {
                    ItemStore s = ctx.items("lane");
                    if (s == null) {
                        return 0;
                    }
                    long cap = 0;
                    long have = 0;
                    for (int i = 0; i < s.slots(); i++) {
                        cap += s.variant(i) == null ? 64 : s.variant(i).maxStackSize();
                        have += s.count(i);
                    }
                    return cap == 0 ? 0 : (int) (have * 100 / cap);
                }
                case "fluid" -> {
                    FluidStore f = ctx.fluids("tank");
                    return f == null || f.capacity(0) == 0 ? 0 : (int) (f.amount(0) * 100 / f.capacity(0));
                }
                default -> {
                    long cap = ctx.energy().capacity();
                    return cap == 0 ? 0 : (int) (ctx.energy().stored() * 100 / cap);
                }
            }
        }

        @Override
        public void tick(ModuleContext ctx) {
            int p = percent(ctx);
            lastPercent = p;
            if (ctx.config().bool("analog")) {
                int v = (int) Math.round(p * 15 / 100.0);
                ctx.emit("out", v);
                ctx.setStatus(Status.of("LEVEL", Status.Severity.WORKING, p + "%"));
                return;
            }
            active = hysteresis(active, p, (int) ctx.config().number("low"), (int) ctx.config().number("high"),
                    ctx.config().choice("mode").equals("on_below"));
            ctx.emit("out", active ? 15 : 0);
            ctx.setStatus(Status.of(active ? "SIGNAL_ON" : "SIGNAL_OFF", active ? Status.Severity.WORKING : Status.Severity.IDLE, p + "%"));
        }

        @Override
        public String detail() {
            return lastPercent + "%";
        }

        @Override
        public FfData save() {
            return new FfData().putBool("active", active);
        }

        @Override
        public void load(FfData d) {
            active = d.getBool("active", false);
        }
    }

    /** Environment Sensor: a documented, bounded set of local observations. Sky check is cached. */
    public static final class EnvironmentSensor implements ModuleBehavior {
        private long nextSky;
        private boolean sky;

        @Override
        public void tick(ModuleContext ctx) {
            String what = ctx.config().choice("observe");
            if (what.startsWith("entities_")) {
                observeEntities(ctx, what.substring("entities_".length()));
                return;
            }
            boolean v = switch (what) {
                case "day" -> ctx.world().isDaytime();
                case "rain" -> ctx.world().isRaining();
                default -> {
                    if (ctx.gameTime() >= nextSky) {
                        sky = ctx.world().canSeeSky(ctx.face());
                        nextSky = ctx.gameTime() + 40;
                    }
                    yield sky;
                }
            };
            if (ctx.config().bool("invert")) {
                v = !v;
            }
            ctx.emit("out", v ? 15 : 0);
            ctx.setStatus(on(v ? 15 : 0));
        }

        /** Entity count in a small box in front of the face, refreshed every 10 ticks (analog, capped at 15). */
        private int entities;
        private long nextEntities;

        private void observeEntities(ModuleContext ctx, String kind) {
            var a = ctx.world().actions();
            if (a == null) {
                ctx.setStatus(dev.fieldframe.api.status.Statuses.NO_WORLD_ACCESS);
                return;
            }
            if (ctx.gameTime() >= nextEntities) {
                entities = Math.min(15, a.countEntities(ctx.face(), ctx.config().integer("range"), kind));
                nextEntities = ctx.gameTime() + 10;
            }
            int out = ctx.config().bool("invert") ? (entities == 0 ? 15 : 0) : entities;
            ctx.emit("out", out);
            ctx.setStatus(on(out));
        }
    }

    /** Indicator Panel: shows a channel value as lamp + number + symbolic fault indication on its face. */
    public static final class IndicatorPanel implements ModuleBehavior {
        private int shown;

        @Override
        public void tick(ModuleContext ctx) {
            shown = Math.max(0, ctx.signal("in"));
            ctx.setStatus(on(shown));
        }

        @Override
        public String detail() {
            return String.valueOf(shown);
        }

        @Override
        public double progress() {
            return shown / 15.0;
        }

        @Override
        public FfData save() {
            return new FfData().putInt("shown", shown);
        }

        @Override
        public void load(FfData d) {
            shown = d.getIntClamped("shown", 0, 0, 15);
        }
    }
}
