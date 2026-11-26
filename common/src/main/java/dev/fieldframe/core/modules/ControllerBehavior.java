package dev.fieldframe.core.modules;

import dev.fieldframe.api.data.FfData;
import dev.fieldframe.api.module.ModuleBehavior;
import dev.fieldframe.api.module.ModuleContext;
import dev.fieldframe.api.status.Status;
import dev.fieldframe.core.controller.ControllerProgram;
import dev.fieldframe.core.controller.ControllerVM;

/**
 * Controller Plate: runs a bounded rule program (see {@link ControllerProgram}) once per tick in the CONTROL phase.
 * Configuration: {@code program} (text), {@code paused}, {@code step} (incrementing it while paused runs exactly one
 * evaluation). Pausing affects only this module. Parse errors and budget overruns are reported as FAULT statuses with
 * the offending line; nothing is committed in that case.
 */
public final class ControllerBehavior implements ModuleBehavior {
    private ControllerVM vm = new ControllerVM(ControllerProgram.empty());
    private String loadedSource;
    private String parseError = "";
    private long lastStep;
    private FfData pendingState;
    private int lastFired;

    private void ensureProgram(ModuleContext ctx) {
        String src = ctx.config().text("program");
        if (src.equals(loadedSource)) {
            return;
        }
        loadedSource = src;
        try {
            ControllerProgram p = ControllerProgram.parse(src);
            vm.replaceProgram(p);
            parseError = "";
            if (pendingState != null) {
                vm.load(pendingState);
                pendingState = null;
            }
        } catch (ControllerProgram.ParseException e) {
            parseError = e.getMessage();
        }
    }

    @Override
    public void tick(ModuleContext ctx) {
        ensureProgram(ctx);
        if (!parseError.isEmpty()) {
            ctx.setStatus(Status.of("PARSE_ERROR", Status.Severity.FAULT, parseError.length() > 32 ? parseError.substring(0, 32) : parseError));
            return;
        }
        boolean paused = ctx.config().bool("paused");
        long step = ctx.config().number("step");
        if (paused && step == lastStep) {
            ctx.setStatus(Status.of("PAUSED", Status.Severity.DISABLED, vm.stateName()));
            return;
        }
        lastStep = step;
        ModuleContext.ChassisView view = ctx.chassis();
        ControllerVM.Result r = vm.evaluate(new ControllerVM.Inputs() {
            public long items(int lane) {
                return view.itemCount(lane);
            }

            public long itemCapacity(int lane) {
                return view.itemCapacity(lane);
            }

            public long fluid(int lane) {
                return view.fluidAmount(lane);
            }

            public long fluidCapacity(int lane) {
                return view.fluidCapacity(lane);
            }

            public long power() {
                return ctx.energy().stored();
            }

            public long powerCapacity() {
                return ctx.energy().capacity();
            }

            public int channel(int index) {
                return ctx.channel(index);
            }
        });
        if (!r.ok()) {
            ctx.setStatus(Status.of("FAULT", Status.Severity.FAULT, r.fault().length() > 32 ? r.fault().substring(0, 32) : r.fault()));
            ctx.trace(r.fault());
            return;
        }
        for (int i = 0; i < r.channelWrites().length; i++) {
            if (r.channelWrites()[i] >= 0) {
                ctx.driveChannel(i, r.channelWrites()[i]);
            }
        }
        lastFired = r.firedRules();
        ctx.setStatus(Status.of(paused ? "STEPPED" : "RUNNING", Status.Severity.WORKING, vm.stateName(), r.firedRules() + " rules"));
    }

    @Override
    public String detail() {
        StringBuilder sb = new StringBuilder("state ").append(vm.stateName());
        for (String reg : vm.program().registers()) {
            sb.append(' ').append(reg).append('=').append(vm.register(reg));
        }
        return sb.toString();
    }

    public ControllerVM vm() {
        return vm;
    }

    public String parseError() {
        return parseError;
    }

    @Override
    public FfData save() {
        FfData d = loadedSource == null && pendingState != null ? pendingState.copy() : vm.save();
        d.putLong("lastStep", lastStep);
        return d;
    }

    @Override
    public void load(FfData d) {
        // The program text lives in config; apply saved registers once the program is parsed on the first tick.
        pendingState = d.copy();
        lastStep = d.getLong("lastStep", 0);
        loadedSource = null;
        vm = new ControllerVM(ControllerProgram.empty());
    }
}
