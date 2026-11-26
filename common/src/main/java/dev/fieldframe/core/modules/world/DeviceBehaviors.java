package dev.fieldframe.core.modules.world;

import dev.fieldframe.api.data.FfData;
import dev.fieldframe.api.id.FfId;
import dev.fieldframe.api.module.ModuleContext;
import dev.fieldframe.api.resource.FluidStore;
import dev.fieldframe.api.resource.FluidVariant;
import dev.fieldframe.api.resource.ItemStore;
import dev.fieldframe.api.resource.ItemVariant;
import dev.fieldframe.api.status.Statuses;
import dev.fieldframe.api.world.WorkTarget;
import dev.fieldframe.api.world.WorldActions;

/**
 * Thermal-style devices that work on the world in front of their face: Water Collector (aqueous accumulator),
 * Fisher, Tree Tapper and Experience Collector. Each goes through {@link WorldWorker}, so actions are guarded by
 * protection and pack rules, rate-limited by {@code interval}, and report NO_WORLD_ACCESS where there is no world.
 */
public final class DeviceBehaviors {
    private DeviceBehaviors() {
    }

    /** Logs a Tree Tapper draws tree sap from (data tag). */
    public static final FfId TAPPABLE_LOGS = FfId.ff("tappable_logs");
    /** Logs a Tree Tapper draws latex from instead (data tag; empty by default, for packs with rubber trees). */
    public static final FfId LATEX_LOGS = FfId.ff("latex_logs");
    /** Items in a Fisher's bait lane that are used up to halve its interval (data tag). */
    public static final FfId FISHER_BAIT = FfId.ff("fisher_bait");

    /** Counts water source blocks in the 3x3 plane directly in front of the face. */
    static int waterSources(ModuleContext ctx, WorldActions a) {
        int n = 0;
        for (int r = -1; r <= 1; r++) {
            for (int u = -1; u <= 1; u++) {
                WorldActions.BlockInfo info = a.probe(new WorkTarget(ctx.face(), 1, r, u));
                if (info.access().ok() && info.fluidSource() && info.fluid() != null && info.fluid().toString().equals("minecraft:water")) {
                    n++;
                }
            }
        }
        return n;
    }

    /** Fills {@code amount} of the fluid into the bound reservoir; sets a status and returns false when it cannot. */
    static boolean fill(ModuleContext ctx, String binding, FfId fluid, long amount) {
        FluidStore tank = ctx.fluids(binding);
        if (tank == null) {
            ctx.setStatus(Statuses.UNBOUND);
            return false;
        }
        FluidVariant v = ctx.world().fluidVariant(fluid);
        if (v == null) {
            ctx.setStatus(Statuses.missingDefinition(fluid.toString()));
            ctx.sleep(200);
            return false;
        }
        if (tank.fill(v, amount, true) < amount) {
            ctx.setStatus(Statuses.outputFull(ctx.bindingLabel(binding)));
            ctx.sleep(20);
            return false;
        }
        tank.fill(v, amount, false);
        return true;
    }

    /** Water Collector: needs at least {@code min_sources} water sources in front; makes water without draining them. */
    public static final class WaterCollector extends WorldWorker {
        @Override
        protected String outputBinding() {
            return null;
        }

        @Override
        protected void work(ModuleContext ctx, WorldActions a) {
            int sources = waterSources(ctx, a);
            if (sources < Math.round(ctx.tunable("min_sources"))) {
                ctx.setStatus(Statuses.NOTHING_TO_DO);
                cooldown(ctx, 100);
                return;
            }
            long mb = Math.round(sources * ctx.tunable("mb_per_source"));
            if (fill(ctx, "water", FfId.parse("minecraft:water"), mb)) {
                actions++;
                ctx.setStatus(Statuses.working(mb + " mB, " + sources + " sources"));
                interval(ctx);
            }
        }
    }

    /**
     * Fisher: fishes from at least {@code min_water} water sources in front, one loot roll per {@code interval};
     * a bait item ({@code #fieldframe:fisher_bait}) from the bait lane halves the wait and adds 1 luck.
     */
    public static final class Fisher extends WorldWorker {
        @Override
        protected void work(ModuleContext ctx, WorldActions a) {
            if (waterSources(ctx, a) < Math.round(ctx.tunable("min_water"))) {
                ctx.setStatus(Statuses.NOTHING_TO_DO);
                cooldown(ctx, 200);
                return;
            }
            long cost = Math.round(ctx.tunable("rf_per_catch"));
            if (!hasEnergy(ctx, cost)) {
                return;
            }
            boolean baited = false;
            ItemStore bait = ctx.items("bait");
            if (bait != null) {
                for (int s = 0; s < bait.slots() && !baited; s++) {
                    ItemVariant v = bait.variant(s);
                    if (v != null && ctx.world().tags().itemInTag(v, FISHER_BAIT) && bait.extractSlot(s, 1, false) == 1) {
                        baited = true;
                    }
                }
            }
            var loot = a.fishingLoot(ctx.face(), baited ? 1 : 0);
            if (loot.isEmpty()) {
                ctx.setStatus(Statuses.NOTHING_TO_DO);
                cooldown(ctx, 100);
                return;
            }
            ctx.energy().consume(cost);
            loot.forEach(d -> pending.add(d.variant(), d.count()));
            pending.flush(ctx.items("output"));
            actions++;
            ctx.setStatus(Statuses.working(loot.get(0).variant().id().path()));
            int wait = (int) Math.max(1, Math.round(ctx.tunable("interval")));
            cooldown(ctx, baited ? Math.max(1, wait / 2) : wait);
        }
    }

    /** Tree Tapper: draws tree sap (or latex, by tag) from the log in front, without harming it. */
    public static final class TreeTapper extends WorldWorker {
        @Override
        protected String outputBinding() {
            return null;
        }

        @Override
        protected void work(ModuleContext ctx, WorldActions a) {
            WorldActions.BlockInfo info = a.probe(new WorkTarget(ctx.face(), 1, 0, 0));
            if (!info.access().ok() || info.block() == null) {
                ctx.setStatus(Statuses.NEIGHBOR_UNLOADED);
                cooldown(ctx, 100);
                return;
            }
            FfId fluid = ctx.world().tags().blockInTag(info.block(), LATEX_LOGS) ? FfId.ff("latex")
                    : ctx.world().tags().blockInTag(info.block(), TAPPABLE_LOGS) ? FfId.ff("tree_sap") : null;
            if (fluid == null) {
                ctx.setStatus(Statuses.NOTHING_TO_DO);
                cooldown(ctx, 100);
                return;
            }
            long mb = Math.round(ctx.tunable("mb_per_tap"));
            if (fill(ctx, "sap", fluid, mb)) {
                actions++;
                ctx.setStatus(Statuses.working(mb + " mB " + fluid.path()));
                interval(ctx);
            }
        }
    }

    /**
     * Aura Chamber (Metallurgy's chamber as a module): takes a batch of items matching an aura recipe from its lane
     * (e.g. 3 adamantine blocks), then for the recipe's duration gives players within {@code range} its effect, paying
     * {@code rf_per_tick} while it runs. Out of RF it pauses (the remaining time is kept). Effects are refreshed for a few
     * seconds at a time, so they fade shortly after the chamber stops. Like the original, auras use amplifier 0 unless
     * {@code use_amplifier} is on.
     */
    public static final class AuraChamber extends WorldWorker {
        private String effect = "";
        private int remaining;
        private int amplifier;

        @Override
        protected String outputBinding() {
            return null;
        }

        @Override
        public double progress() {
            return remaining > 0 ? remaining / (double) Math.max(1, total) : 0;
        }

        private int total;

        @Override
        protected void work(ModuleContext ctx, WorldActions a) {
            int step = (int) Math.max(1, Math.round(ctx.tunable("interval")));
            if (remaining <= 0 && !start(ctx)) {
                cooldown(ctx, 40);
                return;
            }
            long cost = Math.round(ctx.tunable("rf_per_tick")) * step;
            if (cost > 0 && !ctx.energy().consume(cost)) {
                ctx.setStatus(Statuses.noEnergy(cost));
                cooldown(ctx, 20);
                return;
            }
            FfId id = FfId.tryParse(effect);
            int amp = ctx.tunable("use_amplifier") > 0 ? amplifier : 0;
            int n = id == null ? 0 : a.applyEffect((int) Math.round(ctx.tunable("range")), id, step + 100, amp);
            remaining = Math.max(0, remaining - step);
            actions++;
            ctx.setStatus(Statuses.working(id == null ? effect : id.path() + ", " + n + " players, " + remaining / 20 + "s"));
            if (remaining == 0) {
                effect = "";
            }
            cooldown(ctx, step);
        }

        private boolean start(ModuleContext ctx) {
            ItemStore lane = ctx.items("blocks");
            if (lane == null) {
                ctx.setStatus(Statuses.UNBOUND);
                return false;
            }
            for (dev.fieldframe.core.material.Auras.Entry e : dev.fieldframe.core.material.Auras.all()) {
                for (int s = 0; s < lane.slots(); s++) {
                    ItemVariant v = lane.variant(s);
                    if (v != null && e.matches(v, ctx.world().tags()) && lane.countOf(v) >= e.count()) {
                        lane.extract(v, e.count(), false);
                        effect = e.effect().toString();
                        total = remaining = e.duration();
                        amplifier = e.amplifier();
                        ctx.trace("aura " + effect + " for " + remaining + "t from " + e.count() + " " + v.id());
                        return true;
                    }
                }
            }
            ctx.setStatus(lane.isEmpty() ? Statuses.noInput(ctx.bindingLabel("blocks")) : Statuses.invalidRecipe(ctx.bindingLabel("blocks")));
            return false;
        }

        @Override
        protected void saveExtra(FfData d) {
            d.putString("effect", effect).putInt("remaining", remaining).putInt("total", total).putInt("amplifier", amplifier);
        }

        @Override
        protected void loadExtra(FfData d) {
            effect = d.getString("effect", "");
            total = d.getIntClamped("total", 0, 0, 1_000_000);
            remaining = d.getIntClamped("remaining", 0, 0, total);
            amplifier = d.getIntClamped("amplifier", 0, 0, 9);
        }
    }

    /** Experience Collector: absorbs experience orbs in front into liquid experience ({@code mb_per_point}). */
    public static final class XpCollector extends WorldWorker {
        @Override
        protected String outputBinding() {
            return null;
        }

        @Override
        protected void work(ModuleContext ctx, WorldActions a) {
            FluidStore tank = ctx.fluids("experience");
            FluidVariant xp = ctx.world().fluidVariant(FfId.ff("liquid_experience"));
            if (tank == null || xp == null) {
                ctx.setStatus(tank == null ? Statuses.UNBOUND : Statuses.missingDefinition("fieldframe:liquid_experience"));
                ctx.sleep(100);
                return;
            }
            long perPoint = Math.max(1, Math.round(ctx.tunable("mb_per_point")));
            int room = (int) Math.min(Integer.MAX_VALUE, tank.fill(xp, Integer.MAX_VALUE, true) / perPoint);
            if (room <= 0) {
                ctx.setStatus(Statuses.outputFull(ctx.bindingLabel("experience")));
                ctx.sleep(40);
                return;
            }
            int range = (int) Math.max(1, Math.round(ctx.tunable("range")));
            int points = a.collectExperience(ctx.face(), range, room);
            if (points <= 0) {
                ctx.setStatus(Statuses.NOTHING_TO_DO);
                interval(ctx);
                return;
            }
            tank.fill(xp, points * perPoint, false);
            actions++;
            ctx.setStatus(Statuses.working(points + " XP"));
            interval(ctx);
        }
    }
}
