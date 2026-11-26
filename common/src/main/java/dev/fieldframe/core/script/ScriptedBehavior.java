package dev.fieldframe.core.script;

import dev.fieldframe.api.data.FfData;
import dev.fieldframe.api.module.ModuleBehavior;
import dev.fieldframe.api.module.ModuleContext;
import dev.fieldframe.api.status.Statuses;

import java.util.function.Consumer;

/**
 * Behavior for script-defined modules. Callbacks are trusted pack-author code (server/startup scripts), never
 * player input. A callback exception becomes a SCRIPT_ERROR status with the module paused for 5 seconds;
 * resources stay coherent because scripts can only use conserving operations. Errors are reported through a
 * rate-limited sink so a broken script cannot flood the log. Note: a JS infinite loop cannot be interrupted;
 * callbacks are documented as cooperative.
 */
public final class ScriptedBehavior implements ModuleBehavior {
    /** Script callback shapes; Rhino adapts JS functions to these single-method interfaces. */
    @FunctionalInterface
    public interface Callback {
        void call(ScriptModuleContext ctx);
    }

    private static volatile Consumer<String> errorSink = System.err::println;
    private static long lastErrorTime;
    private static int suppressed;

    public static void setErrorSink(Consumer<String> sink) {
        errorSink = sink == null ? s -> { } : sink;
    }

    private final Callback onTick;
    private final Callback onInstalled;
    private final Callback onRemoved;
    private Callback onPlayerUse;
    private int pendingUses;
    private final String moduleId;
    private FfData state = new FfData();
    private String lastError = "";

    public ScriptedBehavior(String moduleId, Callback onTick, Callback onInstalled, Callback onRemoved) {
        this.moduleId = moduleId;
        this.onTick = onTick;
        this.onInstalled = onInstalled;
        this.onRemoved = onRemoved;
    }

    /** Takes empty-hand uses of the face; {@code cb} runs once per use at the start of the next tick. */
    public ScriptedBehavior withPlayerUse(Callback cb) {
        this.onPlayerUse = cb;
        return this;
    }

    @Override
    public boolean onPlayerUse(boolean sneaking) {
        if (onPlayerUse == null) {
            return false;
        }
        pendingUses = Math.min(pendingUses + 1, 4);
        return true;
    }

    private void run(Callback cb, ModuleContext ctx, String phase) {
        if (cb == null) {
            return;
        }
        try {
            cb.call(new ScriptModuleContext(ctx, state));
            lastError = "";
        } catch (Throwable t) {
            if (t instanceof VirtualMachineError vme) {
                throw vme;
            }
            lastError = phase + ": " + t.getMessage();
            ctx.setStatus(Statuses.scriptError(phase));
            ctx.sleep(100);
            report("Fieldframe script module " + moduleId + " failed in " + phase + " at " + ctx.address() + ": " + t);
        }
    }

    private static synchronized void report(String msg) {
        long now = System.currentTimeMillis();
        if (now - lastErrorTime < 5000) {
            suppressed++;
            return;
        }
        lastErrorTime = now;
        errorSink.accept(msg + (suppressed > 0 ? " (" + suppressed + " similar errors suppressed)" : ""));
        suppressed = 0;
    }

    @Override
    public void tick(ModuleContext ctx) {
        for (; pendingUses > 0; pendingUses--) {
            run(onPlayerUse, ctx, "player_use");
        }
        if (onTick == null) {
            ctx.setStatus(Statuses.idle());
            ctx.sleep(200);
            return;
        }
        run(onTick, ctx, "tick");
    }

    @Override
    public void onInstalled(ModuleContext ctx) {
        run(onInstalled, ctx, "installed");
    }

    @Override
    public void onRemoved(ModuleContext ctx) {
        run(onRemoved, ctx, "removed");
    }

    @Override
    public String detail() {
        return lastError.isEmpty() ? "" : "error: " + lastError;
    }

    @Override
    public FfData save() {
        return new FfData().put("script", state.copy());
    }

    @Override
    public void load(FfData d) {
        FfData s = d.getDataOrNull("script");
        state = s == null ? new FfData() : s.copy();
        // Bound state from disk as strictly as live writes.
        if (state.size() > ScriptModuleContext.MAX_STATE_KEYS || state.depth() > 2) {
            state = new FfData();
        }
    }
}
