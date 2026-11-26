package dev.fieldframe.core.modules;

import dev.fieldframe.api.data.FfData;
import dev.fieldframe.api.event.FieldframeEvents;
import dev.fieldframe.api.id.FfId;
import dev.fieldframe.api.module.ModuleBehavior;
import dev.fieldframe.api.module.ModuleContext;
import dev.fieldframe.api.module.ModuleDefinition;
import dev.fieldframe.api.process.ProcessRecipe;
import dev.fieldframe.api.resource.FluidStore;
import dev.fieldframe.api.resource.FluidVariant;
import dev.fieldframe.api.resource.ItemStore;
import dev.fieldframe.api.resource.ItemVariant;
import dev.fieldframe.api.resource.TagLookup;
import dev.fieldframe.api.resource.VariantCodec;
import dev.fieldframe.api.resource.VariantCodecs;
import dev.fieldframe.api.status.Statuses;
import dev.fieldframe.core.storage.ItemLane;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

/**
 * The shared processing engine behind the Heater, Crusher, Press, Mixer and Washer, and any addon or script
 * process family. One operation at a time, with an explicit persisted lifecycle:
 *
 * <pre>
 *  (none) --preflight ok + OperationStarting not cancelled--> RESERVED/RUNNING --progress==duration--> OUTPUT_PENDING
 *  OUTPUT_PENDING --all products fit--> commit exactly once, OperationCompleted, (none)
 * </pre>
 *
 * Inputs are removed at reservation; probabilistic products are rolled at reservation and stored, so blocked
 * output or reloads never reroll. The operation keeps the recipe parameters captured at reservation, so a
 * recipe reload never changes an operation midway. On removal: RUNNING refunds inputs, OUTPUT_PENDING delivers
 * products; anything that cannot be placed in lanes is spilled (handed to the player / dropped), never voided.
 */
public final class ProcessorBehavior implements ModuleBehavior {
    public enum Stage {
        RUNNING, OUTPUT_PENDING
    }

    record Stack(ItemVariant item, int count) {
    }

    static final class Operation {
        long id;
        FfId recipe;
        Stage stage = Stage.RUNNING;
        int progress;
        int duration;
        long energyPerTick;
        final List<Stack> reserved = new ArrayList<>();
        FluidVariant reservedFluid;
        long reservedFluidAmount;
        final List<Stack> products = new ArrayList<>();
        FluidVariant productFluid;
        long productFluidAmount;
    }

    private final ModuleDefinition def;
    private Operation op;

    /** What the running operation makes, as an item or fluid id (screens show its name), else the recipe id. */
    static String producing(Operation o) {
        if (!o.products.isEmpty()) {
            return o.products.get(0).item().id().toString();
        }
        if (o.productFluid != null) {
            return o.productFluid.id().toString();
        }
        return o.recipe.toString();
    }
    private FfId lastRecipe;
    private long lastGeneration = -1;
    private int completed;

    public ProcessorBehavior(ModuleDefinition def) {
        this.def = def;
    }

    @Override
    public double progress() {
        return op == null ? 0 : Math.min(1.0, op.progress / (double) Math.max(1, op.duration));
    }

    @Override
    public String detail() {
        if (op == null) {
            return completed + " done";
        }
        return (int) (progress() * 100) + "% " + op.recipe.path() + (op.stage == Stage.OUTPUT_PENDING ? " (waiting for output)" : "");
    }

    @Override
    public void tick(ModuleContext ctx) {
        ItemStore in = ctx.items("input");
        ItemStore out = ctx.items("output");
        if (in == null || out == null) {
            ctx.setStatus(Statuses.UNBOUND);
            return;
        }
        if (op == null) {
            if (!ctx.enabledBy("enable")) {
                ctx.setStatus(Statuses.signalDisabled(ctx.bindingLabel("enable")));
                return;
            }
            if (!tryStart(ctx, in, out)) {
                return;
            }
        }
        if (op.stage == Stage.RUNNING) {
            if (!ctx.enabledBy("enable")) {
                ctx.setStatus(Statuses.signalDisabled(ctx.bindingLabel("enable")));
                return;
            }
            if (!ctx.energy().consume(op.energyPerTick)) {
                ctx.setStatus(Statuses.noEnergy(op.energyPerTick));
                return;
            }
            op.progress++;
            ctx.emit("active", 15);
            ctx.setStatus(Statuses.working(producing(op)));
            if (op.progress >= op.duration) {
                op.stage = Stage.OUTPUT_PENDING;
            } else {
                return;
            }
        }
        // OUTPUT_PENDING: commit all products atomically or wait.
        FluidStore fluidOut = ctx.fluids("fluid_out");
        if (!fits(out, op.products) || (op.productFluid != null && (fluidOut == null
                || fluidOut.fill(op.productFluid, op.productFluidAmount, true) < op.productFluidAmount))) {
            ctx.setStatus(Statuses.outputFull(op.productFluid != null && fluidOut != null
                    && fluidOut.fill(op.productFluid, op.productFluidAmount, true) < op.productFluidAmount
                    ? ctx.bindingLabel("fluid_out") : ctx.bindingLabel("output")));
            ctx.sleep(20);
            return;
        }
        for (Stack s : op.products) {
            int placed = out.insert(s.item(), s.count(), false);
            if (placed < s.count()) {
                ctx.spill(s.item(), s.count() - placed); // cannot happen after a successful fit; defensive
            }
        }
        if (op.productFluid != null) {
            long placed = fluidOut.fill(op.productFluid, op.productFluidAmount, false);
            if (placed < op.productFluidAmount) {
                ctx.spillFluid(op.productFluid, op.productFluidAmount - placed);
            }
        }
        Operation done = op;
        op = null;
        completed++;
        ctx.emit("done", 15);
        ctx.trace("done " + done.recipe.path() + " #" + done.id);
        ctx.post(new FieldframeEvents.OperationCompleted(ctx.address(), def.id(), done.recipe, done.id));
        ctx.setStatus(Statuses.working("cycle"));
    }

    private boolean tryStart(ModuleContext ctx, ItemStore in, ItemStore out) {
        FfId family = def.processFamily();
        List<ProcessRecipe> recipes = ctx.world().recipes().recipes(family);
        long gen = ctx.world().recipes().generation();
        if (gen != lastGeneration) {
            lastGeneration = gen;
            lastRecipe = null;
        }
        TagLookup tags = ctx.world().tags();
        FluidStore fluidIn = ctx.fluids("fluid_in");
        ProcessRecipe match = null;
        boolean wrongFluid = false;
        String requiredFluid = "";
        // Try the cached recipe first, then scan in a stable order.
        for (int pass = 0; pass < 2 && match == null; pass++) {
            for (ProcessRecipe r : recipes) {
                if ((pass == 0) != r.id().equals(lastRecipe)) {
                    continue;
                }
                if (allocate(r, in, tags) == null) {
                    continue;
                }
                if (r.fluidInput() != null) {
                    FluidVariant present = fluidIn == null ? null : fluidIn.drainableFluid();
                    if (present == null || !r.fluidInput().matcher().test(present, tags)
                            || fluidIn.drain(present, r.fluidInput().amount(), true) < r.fluidInput().amount()) {
                        wrongFluid = present != null && !r.fluidInput().matcher().test(present, tags);
                        requiredFluid = r.fluidInput().display();
                        continue;
                    }
                }
                match = r;
                break;
            }
        }
        if (match == null) {
            if (in.isEmpty()) {
                ctx.setStatus(Statuses.noInput(ctx.bindingLabel("input")));
            } else if (wrongFluid) {
                ctx.setStatus(Statuses.wrongFluid(requiredFluid));
            } else if (!requiredFluid.isEmpty()) {
                ctx.setStatus(Statuses.noFluid(ctx.bindingLabel("fluid_in")));
            } else {
                ctx.setStatus(Statuses.invalidRecipe(ctx.bindingLabel("input")));
            }
            ctx.sleep(40);
            return false;
        }
        lastRecipe = match.id();
        // Preflight: reserve room for the maximum possible products.
        List<Stack> maxProducts = new ArrayList<>();
        for (ProcessRecipe.ItemResult r : match.itemResults()) {
            maxProducts.add(new Stack(r.item(), r.count()));
        }
        if (!fits(out, maxProducts)) {
            ctx.setStatus(Statuses.outputFull(ctx.bindingLabel("output")));
            ctx.sleep(20);
            return false;
        }
        if (match.fluidResult() != null) {
            FluidStore fo = ctx.fluids("fluid_out");
            if (fo == null || fo.fill(match.fluidResult().fluid(), match.fluidResult().amount(), true) < match.fluidResult().amount()) {
                ctx.setStatus(Statuses.outputFull(ctx.bindingLabel("fluid_out")));
                ctx.sleep(20);
                return false;
            }
        }
        FieldframeEvents.OperationStarting ev = new FieldframeEvents.OperationStarting(ctx.address(), def.id(), match.id(), family);
        if (!ctx.post(ev)) {
            ctx.setStatus(Statuses.cancelledByRule(ev.cancelReason()));
            ctx.sleep(40);
            return false;
        }
        // Reserve: remove inputs now.
        int[][] alloc = allocate(match, in, tags);
        if (alloc == null) {
            ctx.setStatus(Statuses.invalidRecipe(ctx.bindingLabel("input")));
            return false;
        }
        Operation o = new Operation();
        o.id = ctx.nextSeed() & Long.MAX_VALUE;
        o.recipe = match.id();
        double oc = ctx.overclock();
        double tierSpeed = ctx.chassisSpeed() * (1 + oc);
        double speed = (def.tunables().containsKey("speed") ? ctx.tunable("speed") : 1.0) * tierSpeed;
        // A faster tier spends the same RF per operation in fewer ticks; overclocking costs half as much again per step.
        double energyMul = (def.tunables().containsKey("energy_multiplier") ? ctx.tunable("energy_multiplier") : 1.0) * tierSpeed * (1 + oc / 2);
        o.duration = Math.max(1, (int) Math.round(match.durationTicks() / speed));
        o.energyPerTick = Math.max(0, Math.round(match.energyPerTick() * energyMul));
        for (int i = 0; i < match.itemInputs().size(); i++) {
            ProcessRecipe.ItemInput input = match.itemInputs().get(i);
            if (!input.consumed()) {
                continue;
            }
            for (int s = 0; s < in.slots(); s++) {
                int take = alloc[i][s];
                if (take > 0) {
                    ItemVariant v = in.variant(s);
                    int got = in.extractSlot(s, take, false);
                    if (got > 0) {
                        o.reserved.add(new Stack(v, got));
                    }
                }
            }
        }
        if (match.fluidInput() != null && fluidIn != null) {
            FluidVariant fv = fluidIn.drainableFluid();
            o.reservedFluid = fv;
            o.reservedFluidAmount = fluidIn.drain(fv, match.fluidInput().amount(), false);
        }
        SplittableRandom rnd = new SplittableRandom(ctx.nextSeed());
        for (ProcessRecipe.ItemResult r : match.itemResults()) {
            if (r.chance() >= 1.0 || rnd.nextDouble() < r.chance()) {
                o.products.add(new Stack(r.item(), r.count()));
            }
        }
        if (match.fluidResult() != null) {
            o.productFluid = match.fluidResult().fluid();
            o.productFluidAmount = match.fluidResult().amount();
        }
        op = o;
        ctx.trace("start " + match.id().path() + " #" + o.id);
        return true;
    }

    /**
     * Greedy allocation of lane slots to recipe inputs; returns per-input per-slot counts or null if unsatisfiable.
     * Catalysts are allocated too (they must be present) but are never extracted.
     */
    static int[][] allocate(ProcessRecipe r, ItemStore in, TagLookup tags) {
        int slots = in.slots();
        int[] left = new int[slots];
        for (int s = 0; s < slots; s++) {
            left[s] = in.count(s);
        }
        int[][] alloc = new int[r.itemInputs().size()][slots];
        for (int i = 0; i < r.itemInputs().size(); i++) {
            ProcessRecipe.ItemInput input = r.itemInputs().get(i);
            int need = input.count();
            for (int s = 0; s < slots && need > 0; s++) {
                ItemVariant v = in.variant(s);
                if (v != null && left[s] > 0 && input.matcher().test(v, tags)) {
                    int take = Math.min(need, left[s]);
                    alloc[i][s] = take;
                    left[s] -= take;
                    need -= take;
                }
            }
            if (need > 0) {
                return null;
            }
        }
        return alloc;
    }

    /** Whether all stacks fit together (simulated on a scratch copy so stacks cannot double-claim a slot). */
    static boolean fits(ItemStore lane, List<Stack> stacks) {
        if (stacks.isEmpty()) {
            return true;
        }
        ItemLane scratch = new ItemLane("scratch", Math.max(1, Math.min(ItemLane.MAX_SLOTS, lane.slots())));
        for (int s = 0; s < scratch.slots(); s++) {
            if (lane.variant(s) != null) {
                scratch.setSlot(s, lane.variant(s), Math.min(lane.count(s), lane.variant(s).maxStackSize()));
            }
        }
        for (Stack st : stacks) {
            if (scratch.insert(st.item(), st.count(), false) < st.count()) {
                return false;
            }
        }
        return true;
    }

    @Override
    public void onRemoved(ModuleContext ctx) {
        if (op == null) {
            return;
        }
        ItemStore in = ctx.items("input");
        ItemStore out = ctx.items("output");
        if (op.stage == Stage.RUNNING) {
            for (Stack s : op.reserved) {
                settle(ctx, in, out, s);
            }
            if (op.reservedFluid != null && op.reservedFluidAmount > 0) {
                FluidStore fi = ctx.fluids("fluid_in");
                long back = fi == null ? 0 : fi.fill(op.reservedFluid, op.reservedFluidAmount, false);
                if (back < op.reservedFluidAmount) {
                    ctx.spillFluid(op.reservedFluid, op.reservedFluidAmount - back);
                }
            }
            ctx.trace("refunded #" + op.id);
        } else {
            for (Stack s : op.products) {
                settle(ctx, out, in, s);
            }
            if (op.productFluid != null) {
                FluidStore fo = ctx.fluids("fluid_out");
                long placed = fo == null ? 0 : fo.fill(op.productFluid, op.productFluidAmount, false);
                if (placed < op.productFluidAmount) {
                    ctx.spillFluid(op.productFluid, op.productFluidAmount - placed);
                }
            }
            ctx.trace("delivered #" + op.id + " on removal");
        }
        ctx.post(new FieldframeEvents.OperationFailed(ctx.address(), def.id(), "module_removed"));
        op = null;
    }

    private static void settle(ModuleContext ctx, ItemStore first, ItemStore second, Stack s) {
        int left = s.count();
        if (first != null) {
            left -= first.insert(s.item(), left, false);
        }
        if (left > 0 && second != null) {
            left -= second.insert(s.item(), left, false);
        }
        if (left > 0) {
            ctx.spill(s.item(), left);
        }
    }

    @Override
    public FfData save() {
        FfData d = new FfData().putInt("completed", completed);
        if (op != null) {
            VariantCodec c = VariantCodecs.active();
            FfData o = new FfData().putLong("id", op.id).putString("recipe", op.recipe.toString())
                    .putString("stage", op.stage.name()).putInt("progress", op.progress).putInt("duration", op.duration)
                    .putLong("energyPerTick", op.energyPerTick);
            o.putList("reserved", encode(c, op.reserved));
            o.putList("products", encode(c, op.products));
            if (op.reservedFluid != null) {
                o.put("rfluid", c.encodeFluid(op.reservedFluid)).putLong("rfluidAmount", op.reservedFluidAmount);
            }
            if (op.productFluid != null) {
                o.put("pfluid", c.encodeFluid(op.productFluid)).putLong("pfluidAmount", op.productFluidAmount);
            }
            d.put("op", o);
        }
        return d;
    }

    private static List<Object> encode(VariantCodec c, List<Stack> stacks) {
        List<Object> l = new ArrayList<>();
        for (Stack s : stacks) {
            l.add(c.encodeItem(s.item()).copy().putInt("count", s.count()));
        }
        return l;
    }

    @Override
    public void load(FfData d) {
        completed = Math.max(0, d.getInt("completed", 0));
        FfData o = d.getDataOrNull("op");
        op = null;
        if (o == null) {
            return;
        }
        VariantCodec c = VariantCodecs.active();
        Operation x = new Operation();
        x.id = o.getLong("id", 0);
        x.recipe = FfId.tryParse(o.getString("recipe", ""));
        if (x.recipe == null) {
            x.recipe = FfId.ff("unknown");
        }
        try {
            x.stage = Stage.valueOf(o.getString("stage", "RUNNING"));
        } catch (IllegalArgumentException e) {
            x.stage = Stage.RUNNING;
        }
        x.duration = o.getIntClamped("duration", 1, 1, 72_000);
        x.progress = o.getIntClamped("progress", 0, 0, x.duration);
        x.energyPerTick = Math.max(0, o.getLong("energyPerTick", 0));
        decode(c, o.getList("reserved"), x.reserved);
        decode(c, o.getList("products"), x.products);
        FfData rf = o.getDataOrNull("rfluid");
        if (rf != null) {
            x.reservedFluid = c.decodeFluid(rf);
            x.reservedFluidAmount = Math.max(0, o.getLong("rfluidAmount", 0));
        }
        FfData pf = o.getDataOrNull("pfluid");
        if (pf != null) {
            x.productFluid = c.decodeFluid(pf);
            x.productFluidAmount = Math.max(1, o.getLong("pfluidAmount", 1));
        }
        op = x;
    }

    private static void decode(VariantCodec c, List<Object> in, List<Stack> out) {
        for (Object e : in) {
            if (e instanceof FfData sd) {
                ItemVariant v = c.decodeItem(sd);
                int n = sd.getIntClamped("count", 0, 0, 64 * 64);
                if (v != null && n > 0) {
                    out.add(new Stack(v, n));
                }
            }
        }
    }

    /** Test/diagnostic accessor. */
    public Stage stage() {
        return op == null ? null : op.stage;
    }
}
