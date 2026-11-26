package dev.fieldframe.core.controller;

import dev.fieldframe.api.data.FfData;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;

/**
 * Executes a {@link ControllerProgram} once per evaluation with these exact semantics:
 * <ol>
 *   <li><b>Snapshot reads.</b> All conditions read one {@link Inputs} snapshot plus the registers, state and previous
 *       channel snapshot as they were at the start of the evaluation. Writes made by earlier rules in the same
 *       evaluation are not visible to later rules.</li>
 *   <li><b>All matching rules fire</b>, in program order. A rule with {@code in S:} only matches while the state is S.</li>
 *   <li><b>Conflicts.</b> Register and state writes: the first matching rule (lowest line) that writes a target wins;
 *       later conflicting writes are ignored and counted. Channel writes: highest value wins (same as the chassis bus).</li>
 *   <li><b>Commit</b> happens once, after all rules are evaluated. Channel outputs therefore become visible to the
 *       chassis on the next tick, so feedback through the controller always crosses a tick boundary.</li>
 *   <li><b>Edges.</b> {@code rise ch N} is true when the channel is above 0 now and was 0 in the previous evaluation's
 *       snapshot; {@code fall} the reverse. The first evaluation after load treats the previous snapshot as saved.</li>
 *   <li><b>Registers</b> are 32-bit and saturate at the int range instead of wrapping.</li>
 *   <li><b>Budget.</b> At most {@link #OPERATION_BUDGET} condition atoms + actions per evaluation; exceeding it
 *       aborts the evaluation without committing anything and reports a fault.</li>
 * </ol>
 * The VM has no access to the world, files, network, commands or Java: its only effects are channel values,
 * registers and its state, which the Controller Plate behavior applies through the chassis.
 */
public final class ControllerVM {
    public static final int OPERATION_BUDGET = 256;
    public static final int TRACE = 8;

    /** Read-only inputs for one evaluation. Percent sources return -1 when capacity is 0. */
    public interface Inputs {
        long items(int lane);

        long itemCapacity(int lane);

        long fluid(int lane);

        long fluidCapacity(int lane);

        long power();

        long powerCapacity();

        int channel(int index);
    }

    public record Result(int[] channelWrites, int firedRules, int ignoredConflicts, String fault) {
        public boolean ok() {
            return fault == null;
        }
    }

    private ControllerProgram program;
    private final long[] registers = new long[ControllerProgram.MAX_REGISTERS];
    private int state;
    private int[] previousChannels = new int[8];
    private final Deque<String> trace = new ArrayDeque<>();

    public ControllerVM(ControllerProgram program) {
        this.program = program;
    }

    public ControllerProgram program() {
        return program;
    }

    /** Replaces the program; registers keep values by name, state resets if it no longer exists. */
    public void replaceProgram(ControllerProgram p) {
        long[] old = registers.clone();
        List<String> oldNames = program.registers();
        Arrays.fill(registers, 0);
        for (int i = 0; i < p.registers().size(); i++) {
            int o = oldNames.indexOf(p.registers().get(i));
            if (o >= 0) {
                registers[i] = old[o];
            }
        }
        String oldState = state < program.states().size() ? program.states().get(state) : "";
        int ns = p.states().indexOf(oldState);
        state = ns < 0 ? 0 : ns;
        program = p;
    }

    public long register(String name) {
        int i = program.registers().indexOf(name);
        return i < 0 ? 0 : registers[i];
    }

    public String stateName() {
        return program.states().get(Math.min(state, program.states().size() - 1));
    }

    public List<String> trace() {
        return List.copyOf(trace);
    }

    private static final class Budget {
        int left = OPERATION_BUDGET;

        boolean spend() {
            return --left >= 0;
        }
    }

    public Result evaluate(Inputs in) {
        Budget budget = new Budget();
        int[] now = new int[8];
        for (int i = 0; i < 8; i++) {
            now[i] = Math.max(0, Math.min(15, in.channel(i)));
        }
        int[] channelWrites = new int[8];
        Arrays.fill(channelWrites, -1);
        long[] newRegs = registers.clone();
        boolean[] regWritten = new boolean[registers.length];
        int newState = state;
        boolean stateWritten = false;
        int fired = 0;
        int conflicts = 0;
        List<String> firedLines = new ArrayList<>();
        for (ControllerProgram.Rule r : program.rules()) {
            if (r.onlyInState() >= 0 && r.onlyInState() != state) {
                continue;
            }
            Boolean m = eval(r.cond(), in, now, budget);
            if (m == null) {
                return new Result(new int[0], fired, conflicts, "BUDGET: more than " + OPERATION_BUDGET + " operations at line " + r.line());
            }
            if (!m) {
                continue;
            }
            fired++;
            firedLines.add("L" + r.line());
            for (ControllerProgram.Action a : r.actions()) {
                if (!budget.spend()) {
                    return new Result(new int[0], fired, conflicts, "BUDGET: more than " + OPERATION_BUDGET + " operations at line " + r.line());
                }
                switch (a.kind()) {
                    case SET_CHANNEL, PULSE -> channelWrites[a.channel()] = (int) Math.max(channelWrites[a.channel()], a.value());
                    case GOTO -> {
                        if (stateWritten) {
                            conflicts++;
                        } else {
                            stateWritten = true;
                            newState = a.state();
                        }
                    }
                    default -> {
                        int i = a.register();
                        if (regWritten[i]) {
                            conflicts++;
                            continue;
                        }
                        regWritten[i] = true;
                        long cur = registers[i];
                        newRegs[i] = switch (a.kind()) {
                            case INC -> saturate(cur + 1);
                            case DEC -> saturate(cur - 1);
                            case RESET -> 0;
                            default -> saturate(a.value());
                        };
                    }
                }
            }
        }
        // Commit.
        System.arraycopy(newRegs, 0, registers, 0, registers.length);
        if (newState != state) {
            addTrace("state " + program.states().get(state) + " -> " + program.states().get(newState));
        }
        state = newState;
        previousChannels = now;
        if (!firedLines.isEmpty()) {
            addTrace("fired " + String.join(",", firedLines));
        }
        return new Result(channelWrites, fired, conflicts, null);
    }

    private static long saturate(long v) {
        return Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, v));
    }

    private void addTrace(String s) {
        trace.addLast(s);
        while (trace.size() > TRACE) {
            trace.removeFirst();
        }
    }

    /** Returns null when the budget is exhausted. */
    private Boolean eval(ControllerProgram.Cond c, Inputs in, int[] now, Budget b) {
        if (!b.spend()) {
            return null;
        }
        if (c instanceof ControllerProgram.Always) {
            return true;
        }
        if (c instanceof ControllerProgram.Not n) {
            Boolean v = eval(n.inner(), in, now, b);
            return v == null ? null : !v;
        }
        if (c instanceof ControllerProgram.Edge e) {
            boolean high = now[e.channel()] > 0;
            boolean was = previousChannels[e.channel()] > 0;
            return e.rising() ? high && !was : !high && was;
        }
        if (c instanceof ControllerProgram.InState s) {
            return state == s.state();
        }
        if (c instanceof ControllerProgram.And a) {
            for (ControllerProgram.Cond p : a.parts()) {
                Boolean v = eval(p, in, now, b);
                if (v == null) {
                    return null;
                }
                if (!v) {
                    return false;
                }
            }
            return true;
        }
        if (c instanceof ControllerProgram.Or o) {
            for (ControllerProgram.Cond p : o.parts()) {
                Boolean v = eval(p, in, now, b);
                if (v == null) {
                    return null;
                }
                if (v) {
                    return true;
                }
            }
            return false;
        }
        ControllerProgram.Compare cmp = (ControllerProgram.Compare) c;
        long value;
        long cap;
        switch (cmp.source()) {
            case ITEM -> {
                value = in.items(cmp.index());
                cap = in.itemCapacity(cmp.index());
            }
            case FLUID -> {
                value = in.fluid(cmp.index());
                cap = in.fluidCapacity(cmp.index());
            }
            case POWER -> {
                value = in.power();
                cap = in.powerCapacity();
            }
            case CHANNEL -> {
                value = now[cmp.index()];
                cap = 15;
            }
            default -> {
                value = registers[cmp.index()];
                cap = 0;
            }
        }
        if (cmp.percent()) {
            if (cap <= 0) {
                return false;
            }
            value = value * 100 / cap;
        }
        long r = cmp.value();
        return switch (cmp.cmp()) {
            case LT -> value < r;
            case LE -> value <= r;
            case GT -> value > r;
            case GE -> value >= r;
            case EQ -> value == r;
            case NE -> value != r;
        };
    }

    // ------------------------------------------------------------------ persistence

    public FfData save() {
        FfData d = new FfData().putInt("state", state).putString("state_name", stateName());
        List<Object> regs = new ArrayList<>();
        for (int i = 0; i < program.registers().size(); i++) {
            regs.add(new FfData().putString("name", program.registers().get(i)).putLong("value", registers[i]));
        }
        d.putList("registers", regs);
        List<Object> prev = new ArrayList<>();
        for (int v : previousChannels) {
            prev.add((long) v);
        }
        d.putList("previous", prev);
        return d;
    }

    /**
     * The saved state, validated the same way {@link #replaceProgram} does: by name first (the program may have been
     * edited since), else state 0 if that name no longer exists; old saves without a name fall back to the clamped
     * index.
     */
    static int loadState(List<String> states, String name, int index) {
        if (name != null) {
            int i = states.indexOf(name);
            return i < 0 ? 0 : i;
        }
        return Math.max(0, Math.min(states.size() - 1, index));
    }

    public void load(FfData d) {
        Arrays.fill(registers, 0);
        for (Object o : d.getList("registers")) {
            if (o instanceof FfData r) {
                int i = program.registers().indexOf(r.getString("name", ""));
                if (i >= 0) {
                    registers[i] = saturate(r.getLong("value", 0));
                }
            }
        }
        state = loadState(program.states(), d.getString("state_name", null), d.getInt("state", 0));
        List<Object> prev = d.getList("previous");
        for (int i = 0; i < 8; i++) {
            previousChannels[i] = i < prev.size() && prev.get(i) instanceof Long l ? (int) Math.max(0, Math.min(15, l)) : 0;
        }
    }
}
