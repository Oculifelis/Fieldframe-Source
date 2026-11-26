package dev.fieldframe.core.modules;

import dev.fieldframe.api.data.FfData;
import dev.fieldframe.api.id.FfId;
import dev.fieldframe.api.module.ChassisWorld;
import dev.fieldframe.api.module.ModuleBehavior;
import dev.fieldframe.api.module.ModuleContext;
import dev.fieldframe.api.resource.FluidStore;
import dev.fieldframe.api.resource.FluidVariant;
import dev.fieldframe.api.resource.ItemFilter;
import dev.fieldframe.api.resource.ItemStore;
import dev.fieldframe.api.resource.ItemVariant;
import dev.fieldframe.api.status.Statuses;
import dev.fieldframe.core.material.Fuels;

/**
 * Thermal-style machines that are not plain recipe processors (see docs/thermal_parity.md): data-fuelled engines,
 * item charger, brewer, enchanter, stone extruder and nullifier. All amounts conserve: items leave a lane only
 * when their product has a place, and nothing is created from nothing except what the module's purpose is.
 */
public final class MachineBehaviors {
    private MachineBehaviors() {
    }

    /** Fluids the enchanter accepts as experience (data tag; the shipped tag holds fieldframe:liquid_experience). */
    public static final FfId EXPERIENCE_FLUIDS = FfId.ff("experience_fluids");

    // ------------------------------------------------------------------ engines

    /** Shared engine core: holds an RF credit from the last fuel unit and pays it out at {@code rf_per_tick}. */
    abstract static class CreditEngine implements ModuleBehavior {
        protected long credit;
        protected long creditTotal;

        /** Adds fuel credit; returns false (and sets a status) when there is nothing to burn. */
        protected abstract boolean refuel(ModuleContext ctx);

        @Override
        public final void tick(ModuleContext ctx) {
            // Higher chassis tiers burn the same fuel faster: more RF/t, the same RF per unit.
            long rate = Math.max(1, Math.round(ctx.tunable("rf_per_tick") * ctx.chassisSpeed()));
            if (!ctx.enabledBy("enable")) {
                ctx.setStatus(Statuses.signalDisabled(ctx.bindingLabel("enable")));
                return;
            }
            if (ctx.energy().capacity() - ctx.energy().stored() < rate) {
                ctx.setStatus(Statuses.BUFFER_FULL);
                ctx.sleep(10);
                return;
            }
            if (credit <= 0 && !refuel(ctx)) {
                return;
            }
            long got = ctx.energy().generate(Math.min(rate, credit));
            credit -= got;
            ctx.emit("running", 15);
            ctx.setStatus(Statuses.working(got + " RF/t"));
        }

        @Override
        public double progress() {
            return creditTotal <= 0 ? 0 : credit / (double) creditTotal;
        }

        @Override
        public String detail() {
            return credit > 0 ? credit + " RF left" : "cold";
        }

        @Override
        public FfData save() {
            return new FfData().putLong("credit", credit).putLong("creditTotal", creditTotal);
        }

        @Override
        public void load(FfData d) {
            creditTotal = Math.max(0, d.getLong("creditTotal", 0));
            credit = Math.max(0, Math.min(creditTotal, d.getLong("credit", 0)));
        }
    }

    /** Fluid Engine (magmatic/compression/reactant style): burns fluids listed for it in the fuels data. */
    public static final class FluidEngine extends CreditEngine {
        @Override
        protected boolean refuel(ModuleContext ctx) {
            FluidStore tank = ctx.fluids("fuel");
            if (tank == null) {
                ctx.setStatus(Statuses.UNBOUND);
                return false;
            }
            int batch = (int) Math.max(1, Math.round(ctx.tunable("mb_per_batch")));
            for (int t = 0; t < tank.tanks(); t++) {
                FluidVariant f = tank.fluid(t);
                if (f == null || tank.amount(t) < batch) {
                    continue;
                }
                long perMb = Fuels.fluidValue(ctx.definition().id(), f, ctx.world().tags());
                if (perMb <= 0 || tank.drain(f, batch, false) != batch) {
                    continue;
                }
                creditTotal = credit = perMb * batch;
                ctx.trace("burn " + batch + " mB " + f.id().path());
                return true;
            }
            ctx.setStatus(tank.drainableFluid() == null ? Statuses.NO_FUEL : Statuses.wrongFluid("fuel"));
            ctx.sleep(40);
            return false;
        }
    }

    /**
     * Item engines. {@code DATA}: RF per item from the fuels data (gem, redstone engines). {@code FOOD}: hunger points
     * x {@code rf_per_point}. {@code ARCANE}: enchantment levels x {@code rf_per_level}; the item is consumed.
     */
    public static final class ItemEngine extends CreditEngine {
        public enum Mode { DATA, FOOD, ARCANE }

        private final Mode mode;
        private final FfId engine;

        public ItemEngine(Mode mode) {
            this(mode, null);
        }

        public ItemEngine(Mode mode, FfId engine) {
            this.mode = mode;
            this.engine = engine;
        }

        @Override
        public boolean burns(ItemVariant item, ChassisWorld world) {
            return switch (mode) {
                case DATA -> engine != null && Fuels.itemValue(engine, item, world.tags()) > 0;
                case FOOD -> world.foodValue(item) > 0;
                case ARCANE -> world.enchantmentLevels(item) > 0;
            };
        }

        long value(ModuleContext ctx, ItemVariant v) {
            ChassisWorld w = ctx.world();
            return switch (mode) {
                case DATA -> Fuels.itemValue(ctx.definition().id(), v, w.tags());
                case FOOD -> Math.round(w.foodValue(v) * ctx.tunable("rf_per_point"));
                case ARCANE -> Math.round(w.enchantmentLevels(v) * ctx.tunable("rf_per_level"));
            };
        }

        @Override
        protected boolean refuel(ModuleContext ctx) {
            ItemStore fuel = ctx.items("fuel");
            if (fuel == null) {
                ctx.setStatus(Statuses.UNBOUND);
                return false;
            }
            for (int s = 0; s < fuel.slots(); s++) {
                ItemVariant v = fuel.variant(s);
                if (v == null) {
                    continue;
                }
                long rf = value(ctx, v);
                if (rf <= 0 || fuel.extractSlot(s, 1, false) != 1) {
                    continue;
                }
                creditTotal = credit = rf;
                ctx.trace("burn " + v.id().path() + " for " + rf + " RF");
                return true;
            }
            ctx.setStatus(fuel.isEmpty() ? Statuses.NO_FUEL : Statuses.invalidRecipe(ctx.bindingLabel("fuel")));
            ctx.sleep(40);
            return false;
        }
    }

    // ------------------------------------------------------------------ charger

    /** Item Charger: charges RF items from the input lane; full items move to the output lane. */
    public static final class Charger implements ModuleBehavior {
        private long charged;

        @Override
        public void tick(ModuleContext ctx) {
            ItemStore in = ctx.items("input");
            ItemStore out = ctx.items("output");
            if (in == null || out == null) {
                ctx.setStatus(Statuses.UNBOUND);
                return;
            }
            if (!ctx.enabledBy("enable")) {
                ctx.setStatus(Statuses.signalDisabled(ctx.bindingLabel("enable")));
                return;
            }
            long rate = Math.min(ctx.energy().stored(), Math.max(1, Math.round(ctx.tunable("rf_per_tick"))));
            if (rate <= 0) {
                ctx.setStatus(Statuses.noEnergy(1));
                ctx.sleep(20);
                return;
            }
            for (int s = 0; s < in.slots(); s++) {
                ItemVariant v = in.variant(s);
                if (v == null) {
                    continue;
                }
                ChassisWorld.ChargeResult r = ctx.world().charge(v, rate);
                if (r == null) {
                    continue;
                }
                boolean done = r.full() || r.accepted() == 0;
                ItemStore dest = done ? out : in;
                if (dest == out && out.insert(r.result(), 1, true) < 1) {
                    ctx.setStatus(Statuses.outputFull(ctx.bindingLabel("output")));
                    ctx.sleep(20);
                    return;
                }
                if (in.extractSlot(s, 1, false) != 1) {
                    continue;
                }
                if (r.accepted() > 0) {
                    ctx.energy().consume(r.accepted());
                    charged += r.accepted();
                }
                int put = dest == in ? in.insertSlot(s, r.result(), 1, false) : out.insert(r.result(), 1, false);
                if (put < 1 && (dest == in ? out.insert(r.result(), 1, false) : 0) < 1) {
                    ctx.spill(r.result(), 1);
                }
                ctx.setStatus(r.accepted() > 0 ? Statuses.working(r.accepted() + " RF/t") : Statuses.idle());
                return;
            }
            ctx.setStatus(in.isEmpty() ? Statuses.noInput(ctx.bindingLabel("input")) : Statuses.invalidRecipe(ctx.bindingLabel("input")));
            ctx.sleep(20);
        }

        @Override
        public String detail() {
            return charged + " RF charged";
        }

        @Override
        public FfData save() {
            return new FfData().putLong("charged", charged);
        }

        @Override
        public void load(FfData d) {
            charged = Math.max(0, d.getLong("charged", 0));
        }
    }

    // ------------------------------------------------------------------ brewer

    /**
     * Brewer: applies one ingredient to up to three identical base potions from the input lane using the game's
     * brewing recipes, like a brewing stand without blaze powder (it uses RF instead).
     */
    public static final class Brewer implements ModuleBehavior {
        private long brewed;

        @Override
        public void tick(ModuleContext ctx) {
            ItemStore in = ctx.items("input");
            ItemStore out = ctx.items("output");
            if (in == null || out == null) {
                ctx.setStatus(Statuses.UNBOUND);
                return;
            }
            if (!ctx.enabledBy("enable")) {
                ctx.setStatus(Statuses.signalDisabled(ctx.bindingLabel("enable")));
                return;
            }
            long cost = Math.round(ctx.tunable("rf_per_brew"));
            for (int b = 0; b < in.slots(); b++) {
                ItemVariant base = in.variant(b);
                if (base == null) {
                    continue;
                }
                for (int i = 0; i < in.slots(); i++) {
                    ItemVariant ing = in.variant(i);
                    if (i == b || ing == null || ing.sameAs(base)) {
                        continue;
                    }
                    ItemVariant result = ctx.world().brew(base, ing);
                    if (result == null) {
                        continue;
                    }
                    int n = (int) Math.min(3, in.countOf(base));
                    if (out.insert(result, n, true) < n) {
                        ctx.setStatus(Statuses.outputFull(ctx.bindingLabel("output")));
                        ctx.sleep(20);
                        return;
                    }
                    if (cost > 0 && !ctx.energy().consume(cost)) {
                        ctx.setStatus(Statuses.noEnergy(cost));
                        ctx.sleep(20);
                        return;
                    }
                    in.extractSlot(i, 1, false);
                    int took = in.extract(base, n, false);
                    int put = out.insert(result, took, false);
                    if (put < took) {
                        ctx.spill(result, took - put);
                    }
                    brewed += took;
                    ctx.setStatus(Statuses.working(took + " " + result.id().path()));
                    ctx.sleep((int) Math.max(1, Math.round(ctx.tunable("interval"))));
                    return;
                }
            }
            ctx.setStatus(in.isEmpty() ? Statuses.noInput(ctx.bindingLabel("input")) : Statuses.invalidRecipe(ctx.bindingLabel("input")));
            ctx.sleep(40);
        }

        @Override
        public String detail() {
            return brewed + " brewed";
        }

        @Override
        public FfData save() {
            return new FfData().putLong("brewed", brewed);
        }

        @Override
        public void load(FfData d) {
            brewed = Math.max(0, d.getLong("brewed", 0));
        }
    }

    // ------------------------------------------------------------------ enchanter

    /**
     * Enchanter: enchants one item at a time like an enchanting table at {@code level}, paid with experience fluid
     * ({@code mb_per_level} x level, fluids in {@code #fieldframe:experience_fluids}) and RF.
     */
    public static final class Enchanter implements ModuleBehavior {
        private long enchanted;

        @Override
        public void tick(ModuleContext ctx) {
            ItemStore in = ctx.items("input");
            ItemStore out = ctx.items("output");
            FluidStore xp = ctx.fluids("experience");
            if (in == null || out == null || xp == null) {
                ctx.setStatus(Statuses.UNBOUND);
                return;
            }
            if (!ctx.enabledBy("enable")) {
                ctx.setStatus(Statuses.signalDisabled(ctx.bindingLabel("enable")));
                return;
            }
            int level = (int) Math.max(1, Math.min(30, Math.round(ctx.tunable("level"))));
            long mb = Math.round(level * ctx.tunable("mb_per_level"));
            FluidVariant f = xp.drainableFluid();
            if (f == null || !ctx.world().tags().fluidInTag(f, EXPERIENCE_FLUIDS) || xp.amountOf(f) < mb) {
                ctx.setStatus(Statuses.noFluid(ctx.bindingLabel("experience")));
                ctx.sleep(40);
                return;
            }
            for (int s = 0; s < in.slots(); s++) {
                ItemVariant v = in.variant(s);
                if (v == null) {
                    continue;
                }
                ItemVariant result = ctx.world().enchant(v, level, ctx.nextSeed());
                if (result == null) {
                    continue;
                }
                if (out.insert(result, 1, true) < 1) {
                    ctx.setStatus(Statuses.outputFull(ctx.bindingLabel("output")));
                    ctx.sleep(20);
                    return;
                }
                long cost = Math.round(ctx.tunable("rf_per_enchant"));
                if (cost > 0 && !ctx.energy().consume(cost)) {
                    ctx.setStatus(Statuses.noEnergy(cost));
                    ctx.sleep(20);
                    return;
                }
                if (in.extractSlot(s, 1, false) != 1) {
                    continue;
                }
                xp.drain(f, mb, false);
                if (out.insert(result, 1, false) < 1) {
                    ctx.spill(result, 1);
                }
                enchanted++;
                ctx.setStatus(Statuses.working(result.id().path() + " @" + level));
                ctx.sleep((int) Math.max(1, Math.round(ctx.tunable("interval"))));
                return;
            }
            ctx.setStatus(in.isEmpty() ? Statuses.noInput(ctx.bindingLabel("input")) : Statuses.invalidRecipe(ctx.bindingLabel("input")));
            ctx.sleep(40);
        }

        @Override
        public String detail() {
            return enchanted + " enchanted";
        }

        @Override
        public FfData save() {
            return new FfData().putLong("enchanted", enchanted);
        }

        @Override
        public void load(FfData d) {
            enchanted = Math.max(0, d.getLong("enchanted", 0));
        }
    }

    // ------------------------------------------------------------------ stone extruder

    /**
     * Stone Extruder: with lava and water in its reservoirs (each at least {@code min_mb}) makes cobblestone or
     * stone without using them up, or obsidian for {@code obsidian_lava_mb} of lava. One block per {@code interval}.
     */
    public static final class StoneExtruder implements ModuleBehavior {
        private long made;

        @Override
        public void tick(ModuleContext ctx) {
            FluidStore lava = ctx.fluids("lava");
            FluidStore water = ctx.fluids("water");
            ItemStore out = ctx.items("output");
            if (lava == null || water == null || out == null) {
                ctx.setStatus(Statuses.UNBOUND);
                return;
            }
            if (!ctx.enabledBy("enable")) {
                ctx.setStatus(Statuses.signalDisabled(ctx.bindingLabel("enable")));
                return;
            }
            long min = Math.round(ctx.tunable("min_mb"));
            FluidVariant lv = ctx.world().fluidVariant(FfId.parse("minecraft:lava"));
            FluidVariant wv = ctx.world().fluidVariant(FfId.parse("minecraft:water"));
            if (lv == null || wv == null || lava.amountOf(lv) < min || water.amountOf(wv) < min) {
                ctx.setStatus(Statuses.noFluid(ctx.bindingLabel(lava.amountOf(lv) < min ? "lava" : "water")));
                ctx.sleep(40);
                return;
            }
            String product = ctx.config().choice("product");
            long lavaCost = product.equals("obsidian") ? Math.round(ctx.tunable("obsidian_lava_mb")) : 0;
            if (lavaCost > 0 && lava.amountOf(lv) < lavaCost) {
                ctx.setStatus(Statuses.noFluid(ctx.bindingLabel("lava")));
                ctx.sleep(40);
                return;
            }
            ItemVariant item = ctx.world().itemVariant(FfId.parse("minecraft:" + product));
            if (item == null) {
                ctx.setStatus(Statuses.fault("product"));
                ctx.sleep(100);
                return;
            }
            if (out.insert(item, 1, true) < 1) {
                ctx.setStatus(Statuses.outputFull(ctx.bindingLabel("output")));
                ctx.sleep(20);
                return;
            }
            long rf = Math.round(ctx.tunable(product.equals("stone") ? "rf_stone" : "rf_per_block"));
            if (rf > 0 && !ctx.energy().consume(rf)) {
                ctx.setStatus(Statuses.noEnergy(rf));
                ctx.sleep(20);
                return;
            }
            if (lavaCost > 0) {
                lava.drain(lv, lavaCost, false);
            }
            out.insert(item, 1, false);
            made++;
            ctx.setStatus(Statuses.working(product));
            ctx.sleep((int) Math.max(1, Math.round(ctx.tunable("interval"))));
        }

        @Override
        public String detail() {
            return made + " made";
        }

        @Override
        public FfData save() {
            return new FfData().putLong("made", made);
        }

        @Override
        public void load(FfData d) {
            made = Math.max(0, d.getLong("made", 0));
        }
    }

    // ------------------------------------------------------------------ nullifier

    /**
     * Nullifier: destroys items matching its filter from the bound lane and, if a reservoir is bound, fluid from it.
     * Deliberate deletion is its whole purpose: it does nothing until its {@code armed} setting is on, and traces what it
     * destroyed.
     */
    public static final class Nullifier implements ModuleBehavior {
        private long items;
        private long mb;

        @Override
        public void tick(ModuleContext ctx) {
            ItemStore lane = ctx.items("target");
            if (lane == null) {
                ctx.setStatus(Statuses.UNBOUND);
                return;
            }
            if (!ctx.config().bool("armed")) {
                ctx.setStatus(Statuses.PASSIVE);
                ctx.sleep(40);
                return;
            }
            if (!ctx.enabledBy("enable")) {
                ctx.setStatus(Statuses.signalDisabled(ctx.bindingLabel("enable")));
                return;
            }
            ItemFilter filter = ctx.config().itemFilter("filter");
            int budget = (int) Math.max(1, Math.round(ctx.tunable("items_per_tick")));
            int gone = 0;
            for (int s = 0; s < lane.slots() && gone < budget; s++) {
                ItemVariant v = lane.variant(s);
                if (v != null && filter.test(v, ctx.world().tags())) {
                    gone += lane.extractSlot(s, budget - gone, false);
                }
            }
            items += gone;
            long drained = 0;
            FluidStore tank = ctx.fluids("fluid");
            if (tank != null) {
                FluidVariant f = tank.drainableFluid();
                if (f != null) {
                    drained = tank.drain(f, Math.max(1, Math.round(ctx.tunable("mb_per_tick"))), false);
                    mb += drained;
                }
            }
            if (gone == 0 && drained == 0) {
                ctx.setStatus(Statuses.NOTHING_TO_DO);
                ctx.sleep(20);
                return;
            }
            ctx.trace("voided " + gone + " items, " + drained + " mB");
            ctx.setStatus(Statuses.working(gone + " items, " + drained + " mB"));
        }

        @Override
        public String detail() {
            return items + " items, " + mb + " mB voided";
        }

        @Override
        public FfData save() {
            return new FfData().putLong("items", items).putLong("mb", mb);
        }

        @Override
        public void load(FfData d) {
            items = Math.max(0, d.getLong("items", 0));
            mb = Math.max(0, d.getLong("mb", 0));
        }
    }
}
