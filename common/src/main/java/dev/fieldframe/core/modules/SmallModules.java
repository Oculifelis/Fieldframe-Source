package dev.fieldframe.core.modules;

import dev.fieldframe.api.FieldframeRegistry;
import dev.fieldframe.api.data.FfData;
import dev.fieldframe.api.id.FfId;
import dev.fieldframe.api.module.BindingSpec;
import dev.fieldframe.api.module.ConfigField;
import dev.fieldframe.api.module.ModuleBehavior;
import dev.fieldframe.api.module.ModuleCategory;
import dev.fieldframe.api.module.ModuleContext;
import dev.fieldframe.api.module.ModuleDefinition;
import dev.fieldframe.api.module.ModuleDefinition.Stage;
import dev.fieldframe.api.module.TickPhase;
import dev.fieldframe.api.module.TunableSpec;
import dev.fieldframe.api.resource.FluidStore;
import dev.fieldframe.api.resource.FluidVariant;
import dev.fieldframe.api.resource.ItemFilter;
import dev.fieldframe.api.resource.ItemStore;
import dev.fieldframe.api.resource.ItemVariant;
import dev.fieldframe.api.status.Status;
import dev.fieldframe.api.status.Statuses;
import dev.fieldframe.api.world.WorldActions;
import dev.fieldframe.core.material.Unification;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Small modules that each do one job inside a machine:
 * <ul>
 *   <li><b>Storage Sorter</b>: moves items matching its filter from one of the machine's storages to another (only
 *       within the machine, never to a neighbour);</li>
 *   <li><b>Tag Unifier</b>: turns items of a common tag ({@code c:ingots/copper}) into the pack's preferred variant;</li>
 *   <li><b>Tool Repairer</b>: mends damaged tools and armour with liquid experience, at the Mending rate;</li>
 *   <li><b>Mob Grinder</b>: attacks mobs in front with the weapon in Purple storage; drops and experience stay in the
 *       machine. Never players; named mobs only when allowed.</li>
 * </ul>
 */
public final class SmallModules {
    private SmallModules() {
    }

    public static final FfId STORAGE_SORTER = FfId.ff("storage_sorter");
    public static final FfId TAG_UNIFIER = FfId.ff("tag_unifier");
    public static final FfId TOOL_REPAIRER = FfId.ff("tool_repairer");
    public static final FfId MOB_GRINDER = FfId.ff("mob_grinder");
    static final FfId LIQUID_XP = FfId.ff("liquid_experience");

    static void register(String src) {
        FieldframeRegistry.registerModule(ModuleDefinition.builder(STORAGE_SORTER)
                .category(ModuleCategory.HANDLING).phase(TickPhase.EXPORT).stage(Stage.WORKSHOP)
                .binding(BindingSpec.item("from", BindingSpec.OUT)).binding(BindingSpec.item("to", BindingSpec.SUPPLY))
                .binding(BindingSpec.signalIn("enable"))
                .config(ConfigField.itemFilter("filter"))
                .tunable(TunableSpec.of("items_per_tick", 1, 64, 8, "items"))
                .behavior(d -> new StorageSorter())
                .build(), src);
        FieldframeRegistry.registerModule(ModuleDefinition.builder(TAG_UNIFIER)
                .category(ModuleCategory.PROCESSING).phase(TickPhase.PROCESS).stage(Stage.AUTOMATION)
                .binding(BindingSpec.item("input", BindingSpec.IN)).binding(BindingSpec.item("output", BindingSpec.OUT))
                .binding(BindingSpec.signalIn("enable"))
                .config(ConfigField.text("preferred", 120, ""))
                .config(ConfigField.bool("pass_others", true))
                .tunable(TunableSpec.of("rf_per_item", 0, 10_000, 20, "RF"))
                .tunable(TunableSpec.of("items_per_tick", 1, 64, 4, "items"))
                .behavior(d -> new TagUnifier())
                .build(), src);
        FieldframeRegistry.registerModule(ModuleDefinition.builder(TOOL_REPAIRER)
                .category(ModuleCategory.PROCESSING).phase(TickPhase.PROCESS).stage(Stage.AUTOMATION)
                .binding(BindingSpec.item("input", BindingSpec.IN)).binding(BindingSpec.item("output", BindingSpec.OUT))
                .binding(BindingSpec.fluid("experience", BindingSpec.IN)).binding(BindingSpec.signalIn("enable"))
                .tunable(TunableSpec.of("points_per_tick", 1, 100, 2, "XP"))
                .tunable(TunableSpec.of("durability_per_point", 1, 20, 2, "uses"))
                .tunable(TunableSpec.of("mb_per_point", 1, 1_000, 20, "mB"))
                .behavior(d -> new ToolRepairer())
                .build(), src);
        FieldframeRegistry.registerModule(ModuleDefinition.builder(MOB_GRINDER)
                .category(ModuleCategory.WORLD).phase(TickPhase.PROCESS).stage(Stage.AUTOMATION)
                .binding(BindingSpec.item("weapon", BindingSpec.SUPPLY)).binding(BindingSpec.item("output", BindingSpec.OUT))
                .binding(BindingSpec.optionalFluid("experience", BindingSpec.IN)).binding(BindingSpec.signalIn("enable"))
                .config(ConfigField.bool("named", false))
                .tunable(TunableSpec.of("range", 1, 3, 2, "blocks"))
                .tunable(TunableSpec.of("interval", 5, 200, 20, "ticks"))
                .tunable(TunableSpec.of("max_targets", 1, 8, 3, "mobs"))
                .tunable(TunableSpec.of("rf_per_attack", 0, 10_000, 60, "RF"))
                .tunable(TunableSpec.of("mb_per_point", 1, 1_000, 20, "mB"))
                .behavior(d -> new MobGrinder())
                .build(), src);
    }

    // ------------------------------------------------------------------ Storage Sorter

    public static final class StorageSorter implements ModuleBehavior {
        @Override
        public void tick(ModuleContext ctx) {
            ItemStore from = ctx.items("from");
            ItemStore to = ctx.items("to");
            if (from == null || to == null) {
                ctx.setStatus(Statuses.UNBOUND);
                return;
            }
            if (ctx.bindingIndex("from") == ctx.bindingIndex("to")) {
                ctx.setStatus(Status.of("SORTER_SAME_STORAGE", Status.Severity.FAULT));
                ctx.sleep(40);
                return;
            }
            if (!ctx.enabledBy("enable")) {
                ctx.setStatus(Statuses.signalDisabled(ctx.bindingLabel("enable")));
                ctx.sleep(10);
                return;
            }
            ItemFilter filter = ctx.config().itemFilter("filter");
            int budget = (int) Math.max(1, Math.round(ctx.tunable("items_per_tick")));
            int moved = 0;
            for (int s = 0; s < from.slots() && moved < budget; s++) {
                ItemVariant v = from.variant(s);
                if (v == null || !filter.test(v, ctx.world().tags())) {
                    continue;
                }
                int want = Math.min(budget - moved, from.count(s));
                int fits = to.insert(v, want, true);
                if (fits <= 0) {
                    continue;
                }
                int taken = from.extractSlot(s, fits, false);
                int put = to.insert(v, taken, false);
                if (put < taken) {
                    from.insert(v, taken - put, false);
                }
                moved += put;
            }
            if (moved > 0) {
                ctx.setStatus(Statuses.working(moved + " items to " + ctx.bindingLabel("to")));
            } else {
                ctx.setStatus(Statuses.NOTHING_TO_DO);
                ctx.sleep(20);
            }
        }
    }

    // ------------------------------------------------------------------ Tag Unifier

    public static final class TagUnifier implements ModuleBehavior {
        private long unified;

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
                ctx.sleep(10);
                return;
            }
            List<String> preferred = preferred(ctx.config().text("preferred"));
            boolean pass = ctx.config().bool("pass_others");
            long rf = Math.round(ctx.tunable("rf_per_item"));
            int budget = (int) Math.max(1, Math.round(ctx.tunable("items_per_tick")));
            int done = 0;
            boolean blocked = false;
            for (int s = 0; s < in.slots() && done < budget; s++) {
                ItemVariant v = in.variant(s);
                if (v == null) {
                    continue;
                }
                ItemVariant target = ctx.world().unified(v, preferred);
                boolean change = target != null && !target.equals(v);
                if (!change && !pass) {
                    continue;
                }
                ItemVariant result = change ? target : v;
                int n = Math.min(budget - done, in.count(s));
                n = Math.min(n, out.insert(result, n, true));
                if (change && rf > 0) {
                    n = (int) Math.min(n, ctx.energy().stored() / rf);
                }
                if (n <= 0) {
                    blocked = true;
                    continue;
                }
                int taken = in.extractSlot(s, n, false);
                if (change && rf > 0 && !ctx.energy().consume(rf * taken)) {
                    in.insert(v, taken, false);
                    blocked = true;
                    continue;
                }
                int put = out.insert(result, taken, false);
                if (put < taken) {
                    in.insert(v, taken - put, false);
                }
                done += put;
                if (change) {
                    unified += put;
                }
            }
            if (done > 0) {
                ctx.setStatus(Statuses.working(done + " items"));
            } else if (blocked) {
                ctx.setStatus(Statuses.outputFull(ctx.bindingLabel("output")));
                ctx.sleep(20);
            } else {
                ctx.setStatus(Statuses.NOTHING_TO_DO);
                ctx.sleep(20);
            }
        }

        static List<String> preferred(String text) {
            List<String> out = new ArrayList<>();
            for (String p : (text == null ? "" : text).split("[,\\s]+")) {
                if (!p.isBlank()) {
                    out.add(p.trim().toLowerCase(Locale.ROOT));
                }
            }
            return out.isEmpty() ? Unification.preferred() : out;
        }

        @Override
        public String detail() {
            return unified + " unified";
        }

        @Override
        public FfData save() {
            return new FfData().putLong("unified", unified);
        }

        @Override
        public void load(FfData d) {
            unified = Math.max(0, d.getLong("unified", 0));
        }
    }

    // ------------------------------------------------------------------ Tool Repairer

    public static final class ToolRepairer implements ModuleBehavior {
        private int owed;

        @Override
        public void tick(ModuleContext ctx) {
            ItemStore in = ctx.items("input");
            ItemStore out = ctx.items("output");
            FluidStore tank = ctx.fluids("experience");
            if (in == null || out == null || tank == null) {
                ctx.setStatus(Statuses.UNBOUND);
                return;
            }
            if (!ctx.enabledBy("enable")) {
                ctx.setStatus(Statuses.signalDisabled(ctx.bindingLabel("enable")));
                ctx.sleep(10);
                return;
            }
            for (int s = 0; s < in.slots(); s++) {
                ItemVariant v = in.variant(s);
                if (v == null) {
                    continue;
                }
                int[] dur = ctx.world().durability(v);
                if (dur == null || dur[0] <= 0) {
                    // Nothing to mend: pass it on.
                    if (out.insert(v, 1, true) == 1 && in.extractSlot(s, 1, false) == 1) {
                        out.insert(v, 1, false);
                    }
                    continue;
                }
                repair(ctx, in, out, tank, s, v, dur);
                return;
            }
            ctx.setStatus(Statuses.NOTHING_TO_DO);
            ctx.sleep(20);
        }

        private void repair(ModuleContext ctx, ItemStore in, ItemStore out, FluidStore tank, int slot, ItemVariant v, int[] dur) {
            FluidVariant xp = ctx.world().fluidVariant(LIQUID_XP);
            long perPoint = Math.max(1, Math.round(ctx.tunable("mb_per_point")));
            int perUse = (int) Math.max(1, Math.round(ctx.tunable("durability_per_point")));
            int points = (int) Math.max(1, Math.round(ctx.tunable("points_per_tick")));
            points = Math.min(points, (dur[0] + perUse - 1) / perUse);
            long have = xp == null ? 0 : tank.drain(xp, points * perPoint, true);
            points = (int) Math.min(points, have / perPoint);
            if (points <= 0) {
                ctx.setStatus(Status.of("NO_EXPERIENCE", Status.Severity.IDLE, ctx.bindingLabel("experience")));
                ctx.sleep(20);
                return;
            }
            int damage = Math.max(0, dur[0] - points * perUse);
            ItemVariant mended = ctx.world().withDamage(v, damage);
            if (mended == null) {
                ctx.setStatus(Statuses.NO_WORLD_ACCESS);
                ctx.sleep(40);
                return;
            }
            ItemStore target = damage == 0 ? out : in;
            if (target.insert(mended, 1, true) != 1 && !(damage > 0 && in.count(slot) == 1)) {
                ctx.setStatus(Statuses.outputFull(ctx.bindingLabel("output")));
                ctx.sleep(20);
                return;
            }
            if (in.extractSlot(slot, 1, false) != 1) {
                return;
            }
            if (target.insert(mended, 1, false) != 1) {
                in.insert(v, 1, false);
                return;
            }
            tank.drain(xp, points * perPoint, false);
            ctx.setStatus(Statuses.working(damage == 0 ? "mended" : (dur[1] - damage) + " / " + dur[1]));
        }
    }

    // ------------------------------------------------------------------ Mob Grinder

    public static final class MobGrinder implements ModuleBehavior {
        private long nextAttack;
        private long kills;

        @Override
        public void tick(ModuleContext ctx) {
            WorldActions a = ctx.world().actions();
            ItemStore weapons = ctx.items("weapon");
            ItemStore out = ctx.items("output");
            if (a == null) {
                ctx.setStatus(Statuses.NO_WORLD_ACCESS);
                ctx.sleep(40);
                return;
            }
            if (weapons == null || out == null) {
                ctx.setStatus(Statuses.UNBOUND);
                return;
            }
            int range = (int) Math.max(1, Math.round(ctx.tunable("range")));
            // Drops and experience from earlier kills are picked up every tick.
            a.collectItems(ctx.face(), range + 1, out, 64);
            FluidStore tank = ctx.fluids("experience");
            FluidVariant xp = ctx.world().fluidVariant(LIQUID_XP);
            if (tank != null && xp != null) {
                long perPoint = Math.max(1, Math.round(ctx.tunable("mb_per_point")));
                int room = (int) Math.min(Integer.MAX_VALUE, tank.fill(xp, Integer.MAX_VALUE, true) / perPoint);
                if (room > 0) {
                    int points = a.collectExperience(ctx.face(), range + 1, room);
                    if (points > 0) {
                        tank.fill(xp, points * perPoint, false);
                    }
                }
            }
            if (!ctx.enabledBy("enable")) {
                ctx.setStatus(Statuses.signalDisabled(ctx.bindingLabel("enable")));
                return;
            }
            long now = ctx.gameTime();
            if (now < nextAttack) {
                return;
            }
            nextAttack = now + Math.max(1, Math.round(ctx.tunable("interval") / Math.max(0.25, ctx.chassisSpeed())));
            int slot = -1;
            ItemVariant weapon = null;
            for (int s = 0; s < weapons.slots(); s++) {
                if (weapons.variant(s) != null) {
                    slot = s;
                    weapon = weapons.variant(s);
                    break;
                }
            }
            long rf = Math.round(ctx.tunable("rf_per_attack"));
            if (rf > 0 && ctx.energy().stored() < rf) {
                ctx.setStatus(Statuses.noEnergy(rf));
                return;
            }
            WorldActions.AttackResult r = a.attackMobs(ctx.face(), range, weapon, ctx.config().bool("named"),
                    (int) Math.max(1, Math.round(ctx.tunable("max_targets"))));
            if (!r.outcome().ok()) {
                ctx.setStatus(r.outcome() == WorldActions.Outcome.PROTECTED ? Statuses.protectedTarget(ctx.face().key())
                        : Status.of("NO_MOBS", Status.Severity.IDLE));
                return;
            }
            if (rf > 0) {
                ctx.energy().consume(rf);
            }
            kills += r.kills();
            if (slot >= 0 && weapon != null && (r.weaponAfter() == null || !r.weaponAfter().equals(weapon))) {
                weapons.extractSlot(slot, 1, false);
                if (r.weaponAfter() != null) {
                    weapons.insert(r.weaponAfter(), 1, false);
                }
            }
            ctx.setStatus(Statuses.working(r.hits() + " hit, " + kills + " so far"));
        }

        @Override
        public String detail() {
            return kills + " kills";
        }

        @Override
        public FfData save() {
            return new FfData().putLong("kills", kills);
        }

        @Override
        public void load(FfData d) {
            kills = Math.max(0, d.getLong("kills", 0));
        }
    }
}
