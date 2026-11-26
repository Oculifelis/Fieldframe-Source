package dev.fieldframe.core.script;

import dev.fieldframe.api.data.FfData;
import dev.fieldframe.api.energy.Energy;
import dev.fieldframe.api.module.ModuleContext;
import dev.fieldframe.api.resource.FluidStore;
import dev.fieldframe.api.resource.ItemStore;
import dev.fieldframe.api.resource.ItemVariant;
import dev.fieldframe.api.status.Status;
import dev.fieldframe.core.util.Transfers;

import java.util.Locale;

/**
 * The script-facing view of an installed module during a callback. Every method takes and returns plain values
 * (strings, numbers, booleans) and validates them; scripts never receive mutable lanes, platform objects or the
 * raw context. Resource changes are conserving lane-to-lane moves or energy accounting through the chassis.
 * Valid only for the duration of one callback.
 */
public final class ScriptModuleContext {
    public static final int MAX_STATE_KEYS = 32;
    public static final int MAX_STATE_STRING = 256;

    private final ModuleContext ctx;
    private final FfData state;

    public ScriptModuleContext(ModuleContext ctx, FfData state) {
        this.ctx = ctx;
        this.state = state;
    }

    // ---- identity ----

    public String getFace() {
        return ctx.face().key();
    }

    public String getModuleId() {
        return ctx.definition().id().toString();
    }

    public long getGameTime() {
        return ctx.gameTime();
    }

    // ---- control channels ----

    /** Bound input signal 0..15, or -1 if unbound. */
    public int signal(String binding) {
        return ctx.signal(binding);
    }

    public boolean enabled(String binding) {
        return ctx.enabledBy(binding);
    }

    public void emit(String binding, double value) {
        ctx.emit(binding, (int) Math.max(0, Math.min(15, Double.isFinite(value) ? value : 0)));
    }

    public int channel(int index) {
        return ctx.channel(index);
    }

    // ---- configuration ----

    public boolean configBool(String key) {
        return ctx.config().bool(key);
    }

    public long configInt(String key) {
        return ctx.config().number(key);
    }

    public String configString(String key) {
        return ctx.config().text(key).isEmpty() ? ctx.config().choice(key) : ctx.config().text(key);
    }

    public double tunable(String key) {
        return ctx.tunable(key);
    }

    // ---- lanes (read + conserving moves) ----

    public long itemCount(String binding) {
        ItemStore s = ctx.items(binding);
        return s == null ? 0 : s.totalCount();
    }

    public long itemCountOf(String binding, String itemId) {
        ItemStore s = ctx.items(binding);
        if (s == null) {
            return 0;
        }
        long n = 0;
        for (int i = 0; i < s.slots(); i++) {
            ItemVariant v = s.variant(i);
            if (v != null && v.id().toString().equals(itemId)) {
                n += s.count(i);
            }
        }
        return n;
    }

    /** Moves up to {@code max} items (one stack type) between two bound item lanes. Returns the amount moved. */
    public int moveItems(String fromBinding, String toBinding, double max) {
        ItemStore from = ctx.items(fromBinding);
        ItemStore to = ctx.items(toBinding);
        if (from == null || to == null || from == to) {
            return 0;
        }
        int m = (int) Math.max(0, Math.min(64, Double.isFinite(max) ? max : 0));
        return Transfers.moveItems(from, to, m, dev.fieldframe.api.resource.ItemFilter.ANY, ctx.world().tags(), 0, ctx).amount();
    }

    public long fluidAmount(String binding) {
        FluidStore f = ctx.fluids(binding);
        return f == null ? 0 : f.amount(0);
    }

    public String fluidId(String binding) {
        FluidStore f = ctx.fluids(binding);
        return f == null || f.fluid(0) == null ? "" : f.fluid(0).id().toString();
    }

    // ---- energy ----

    public long energyStored() {
        return ctx.energy().stored();
    }

    public long energyCapacity() {
        return ctx.energy().capacity();
    }

    /** All-or-nothing; returns false and consumes nothing if the buffer holds less. */
    public boolean consumeEnergy(double amount) {
        return ctx.energy().consume(Energy.requireSafeNonNegative(amount, "consumeEnergy"));
    }

    /**
     * Adds RF to the buffer; returns what was accepted. Scripted generators are subject to the same buffer
     * limits as built-in ones; the per-call amount is capped by the module's {@code max_generation} tunable if
     * declared, else 10,000 RF.
     */
    public long generateEnergy(double amount) {
        long req = Energy.requireSafeNonNegative(amount, "generateEnergy");
        long cap = ctx.definition().tunables().containsKey("max_generation") ? (long) ctx.tunable("max_generation") : 10_000;
        return ctx.energy().generate(Math.min(req, cap));
    }

    // ---- status ----

    public void setStatus(String code, String severity, String... args) {
        Status.Severity sev;
        try {
            sev = Status.Severity.valueOf(severity.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            sev = Status.Severity.IDLE;
        }
        String c = code == null ? "IDLE" : code.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9_]", "_");
        if (c.length() > 48) {
            c = c.substring(0, 48);
        }
        String[] a = args == null ? new String[0] : args;
        if (a.length > 4) {
            a = java.util.Arrays.copyOf(a, 4);
        }
        for (int i = 0; i < a.length; i++) {
            a[i] = a[i] == null ? "" : a[i].length() > 32 ? a[i].substring(0, 32) : a[i];
        }
        ctx.setStatus(Status.of(c.isEmpty() ? "IDLE" : c, sev, a));
    }

    public void working(String what) {
        setStatus("WORKING", "WORKING", what);
    }

    public void idle() {
        setStatus("IDLE", "IDLE");
    }

    public void sleep(double ticks) {
        ctx.sleep((int) Math.max(1, Math.min(1200, Double.isFinite(ticks) ? ticks : 1)));
    }

    public void trace(String message) {
        ctx.trace(message == null ? "" : message.length() > 80 ? message.substring(0, 80) : message);
    }

    // ---- bounded persistent state (saved with the module) ----

    public double getNumber(String key) {
        return state.getDouble(checkKey(key), 0);
    }

    public String getString(String key) {
        return state.getString(checkKey(key), "");
    }

    public boolean getBool(String key) {
        return state.getBool(checkKey(key), false);
    }

    public void setNumber(String key, double v) {
        if (!Double.isFinite(v)) {
            throw new IllegalArgumentException("state value for '" + key + "' must be a finite number");
        }
        put(key);
        state.putDouble(key, v);
    }

    public void setString(String key, String v) {
        put(key);
        state.putString(key, v == null ? "" : v.length() > MAX_STATE_STRING ? v.substring(0, MAX_STATE_STRING) : v);
    }

    public void setBool(String key, boolean v) {
        put(key);
        state.putBool(key, v);
    }

    private void put(String key) {
        checkKey(key);
        if (!state.has(key) && state.size() >= MAX_STATE_KEYS) {
            throw new IllegalStateException("script state is limited to " + MAX_STATE_KEYS + " keys per module");
        }
    }

    private static String checkKey(String key) {
        if (key == null || !key.matches("[A-Za-z][A-Za-z0-9_]{0,31}")) {
            throw new IllegalArgumentException("invalid state key '" + key + "'");
        }
        return key;
    }
}
