package dev.fieldframe.frameworks;

import dev.fieldframe.api.data.FfData;
import dev.fieldframe.api.frame.MachineHub;
import dev.fieldframe.api.id.FfId;
import dev.fieldframe.api.process.ProcessRecipe;
import dev.fieldframe.api.resource.FluidVariant;
import dev.fieldframe.api.resource.ItemStore;
import dev.fieldframe.api.resource.ItemVariant;
import dev.fieldframe.forge.api.FieldframeForgeApi;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.Fluid;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Foundry: a Grand Frame with Frame Crucibles inside. It melts what is in the frame's Orange storage into one molten
 * pool (several fluids at once), combines metals present in an alloy's ratio ({@link FoundryAlloys}), and pours
 * finished metal into the frame's Orange tank, where a Blast Chiller with a cast on the frame turns it into ingots.
 * <ul>
 *   <li>Heat: while it can draw {@value #heatRf()} RF a tick per crucible the pool heats {@value #heatUp()} K a tick up
 *       to {@value #maxK()} K; without power it cools. A metal melts only once the pool is as hot as the metal's fluid
 *       (capped at {@value #meltCap()} K, so every metal melts at full heat).</li>
 *   <li>Melting: Fieldframe's melting recipes (Metallurgy's metals included), one item per crucible every half
 *       second, {@value #meltRfPerMb()} RF per mB. Ores of a metal with a molten form melt to two ingots' worth.</li>
 *   <li>Pouring: a metal waits in the pool while an alloy it belongs to has all its other metals present (in the
 *       pool, or still unmelted in the Orange storage), so alloys get the chance to form; anything else pours, densest
 *       first, up to an ingot a tick. Nothing waits while the pool is too full to melt more.</li>
 * </ul>
 */
public final class Foundry {
    /** Tuning {@code foundry.mb_per_crucible}. */
    public static long mbPerCrucible() {
        return FwTuning.getLong("foundry.mb_per_crucible");
    }

    /** Tuning {@code foundry.heat_rf}. */
    public static long heatRf() {
        return FwTuning.getLong("foundry.heat_rf");
    }

    /** Tuning {@code foundry.heat_up}. */
    public static double heatUp() {
        return FwTuning.get("foundry.heat_up");
    }

    /** Tuning {@code foundry.cool_down}. */
    public static double coolDown() {
        return FwTuning.get("foundry.cool_down");
    }

    /** Tuning {@code foundry.ambient}. */
    public static double ambient() {
        return FwTuning.get("foundry.ambient");
    }

    /** Tuning {@code foundry.max_k}. */
    public static double maxK() {
        return FwTuning.get("foundry.max_k");
    }

    /** Tuning {@code foundry.melt_cap}. */
    public static double meltCap() {
        return FwTuning.get("foundry.melt_cap");
    }

    /** Tuning {@code foundry.melt_rf_per_mb}. */
    public static long meltRfPerMb() {
        return FwTuning.getLong("foundry.melt_rf_per_mb");
    }

    /** Tuning {@code foundry.pour_per_tick}. */
    public static long pourPerTick() {
        return FwTuning.getLong("foundry.pour_per_tick");
    }

    /** Tuning {@code foundry.ore_bonus_mb}. */
    public static long oreBonusMb() {
        return FwTuning.getLong("foundry.ore_bonus_mb");
    }

    private final Map<FfId, Long> pool = new LinkedHashMap<>();
    /** Fluids that items still in the Orange storage melt to (refreshed with every melting pass). */
    private final java.util.Set<FfId> upcoming = new java.util.HashSet<>();
    /** The last melting pass found no room in the pool. */
    private boolean crowded;
    private double temperature = ambient();
    private int crucibles;

    public int crucibles() {
        return crucibles;
    }

    void setCrucibles(int n) {
        crucibles = Math.max(0, n);
    }

    public long capacity() {
        return crucibles * mbPerCrucible();
    }

    public long used() {
        long u = 0;
        for (long v : pool.values()) {
            u += v;
        }
        return u;
    }

    public double temperature() {
        return temperature;
    }

    public Map<FfId, Long> pool() {
        return Map.copyOf(pool);
    }

    /** One tick of a formed Foundry. Returns true when something changed (the core marks itself for saving). */
    boolean tick(Level level, MachineHub hub) {
        if (crucibles <= 0) {
            return false;
        }
        boolean changed = false;
        long heat = heatRf() * crucibles;
        if (hub.energy().extract(heat, true) == heat) {
            hub.energy().extract(heat, false);
            temperature = Math.min(maxK(), temperature + heatUp());
        } else {
            temperature = Math.max(ambient(), temperature - coolDown());
        }
        if (level.getGameTime() % 10 == 0) {
            changed |= melt(level, hub);
        }
        changed |= alloy();
        changed |= pour(hub);
        return changed;
    }

    // ------------------------------------------------------------------ melting

    private boolean melt(Level level, MachineHub hub) {
        ItemStore in = hub.items(0);
        List<ProcessRecipe> recipes = FieldframeForgeApi.processRecipes(level, FfId.of("fieldframe", "melting"));
        int budget = crucibles;
        boolean changed = false;
        upcoming.clear();
        crowded = false;
        for (int s = 0; s < in.slots(); s++) {
            ItemVariant v = in.variant(s);
            if (v == null) {
                continue;
            }
            Melt m = meltOf(v, recipes);
            if (m == null) {
                continue;
            }
            upcoming.add(m.fluid());
            if (budget <= 0 || in.count(s) < m.count() || temperature < Math.min(meltCap(), meltingPoint(m.fluid()))) {
                continue;
            }
            if (used() + m.amount() > capacity()) {
                crowded = true;
                continue;
            }
            long rf = meltRfPerMb() * m.amount();
            if (hub.energy().extract(rf, true) < rf) {
                break;
            }
            if (in.extractSlot(s, m.count(), false) != m.count()) {
                continue;
            }
            hub.energy().extract(rf, false);
            pool.merge(m.fluid(), m.amount(), Long::sum);
            budget--;
            changed = true;
        }
        return changed;
    }

    record Melt(int count, FfId fluid, long amount) {
    }

    static Melt meltOf(ItemVariant v, List<ProcessRecipe> recipes) {
        for (ProcessRecipe r : recipes) {
            if (r.fluidResult() == null || r.itemInputs().size() != 1 || !r.itemResults().isEmpty()) {
                continue;
            }
            ProcessRecipe.ItemInput in = r.itemInputs().get(0);
            if (in.consumed() && in.matcher().test(v, FieldframeForgeApi.tags())) {
                return new Melt(in.count(), r.fluidResult().fluid().id(), r.fluidResult().amount());
            }
        }
        // Ores: two ingots' worth of the metal's molten form, when one exists.
        ItemStack stack = FieldframeForgeApi.toStack(v, 1);
        for (TagKey<net.minecraft.world.item.Item> tag : stack.getTags().toList()) {
            String path = tag.location().getPath();
            String ns = tag.location().getNamespace();
            if ((ns.equals("c") || ns.equals("forge")) && path.startsWith("ores/")) {
                String metal = path.substring(5);
                for (String fns : new String[]{"metallurgy", "fieldframe"}) {
                    FfId fluid = FfId.of(fns, fns.equals("fieldframe") ? "molten_" + metal : metal);
                    if (BuiltInRegistries.FLUID.containsKey(new net.minecraft.resources.ResourceLocation(fluid.namespace(), fluid.path()))) {
                        return new Melt(1, fluid, oreBonusMb());
                    }
                }
            }
        }
        return null;
    }

    static Fluid fluid(FfId id) {
        return BuiltInRegistries.FLUID.get(new net.minecraft.resources.ResourceLocation(id.namespace(), id.path()));
    }

    static double meltingPoint(FfId id) {
        Fluid f = fluid(id);
        return f == null ? meltCap() : f.getFluidType().getTemperature();
    }

    // ------------------------------------------------------------------ alloying and pouring

    private boolean alloy() {
        boolean changed = false;
        for (FoundryAlloys.Alloy a : FoundryAlloys.all()) {
            long units = Long.MAX_VALUE;
            for (FoundryAlloys.Part p : a.inputs()) {
                units = Math.min(units, pool.getOrDefault(p.fluid(), 0L) / p.amount());
            }
            if (units <= 0) {
                continue;
            }
            long k = Math.min(units, 4);
            for (FoundryAlloys.Part p : a.inputs()) {
                long left = pool.get(p.fluid()) - k * p.amount();
                if (left <= 0) {
                    pool.remove(p.fluid());
                } else {
                    pool.put(p.fluid(), left);
                }
            }
            pool.merge(a.result().fluid(), k * a.result().amount(), Long::sum);
            changed = true;
        }
        return changed;
    }

    /** A metal waits while some alloy it belongs to has every other metal in the pool or still to melt. */
    boolean waiting(FfId fluid) {
        if (crowded) {
            return false;
        }
        for (FoundryAlloys.Alloy a : FoundryAlloys.all()) {
            boolean member = false;
            boolean othersPresent = true;
            for (FoundryAlloys.Part p : a.inputs()) {
                if (p.fluid().equals(fluid)) {
                    member = true;
                } else if (pool.getOrDefault(p.fluid(), 0L) <= 0 && !upcoming.contains(p.fluid())) {
                    othersPresent = false;
                }
            }
            if (member && othersPresent) {
                return true;
            }
        }
        return false;
    }

    private boolean pour(MachineHub hub) {
        FfId best = null;
        int bestDensity = Integer.MIN_VALUE;
        for (FfId f : pool.keySet()) {
            if (waiting(f)) {
                continue;
            }
            Fluid fl = fluid(f);
            int density = fl == null ? 0 : fl.getFluidType().getDensity();
            if (best == null || density > bestDensity) {
                best = f;
                bestDensity = density;
            }
        }
        if (best == null) {
            return false;
        }
        Fluid fl = fluid(best);
        if (fl == null) {
            pool.remove(best);
            return true;
        }
        FluidVariant fv = FieldframeForgeApi.variantOf(fl);
        long have = pool.get(best);
        long moved = hub.tank(0).fill(fv, Math.min(pourPerTick(), have), false);
        if (moved <= 0) {
            return false;
        }
        if (have - moved <= 0) {
            pool.remove(best);
        } else {
            pool.put(best, have - moved);
        }
        return true;
    }

    // ------------------------------------------------------------------ persistence

    FfData save() {
        List<Object> list = new ArrayList<>();
        pool.forEach((f, a) -> list.add(new FfData().putString("fluid", f.toString()).putLong("amount", a)));
        return new FfData().putList("pool", list).putDouble("temperature", temperature);
    }

    void load(FfData d) {
        pool.clear();
        for (Object o : d.getList("pool")) {
            if (o instanceof FfData e) {
                FfId f = FfId.tryParse(e.getString("fluid", ""));
                long a = e.getLong("amount", 0);
                if (f != null && a > 0) {
                    pool.merge(f, a, Long::sum);
                }
            }
        }
        temperature = Math.max(ambient(), Math.min(maxK(), d.getDouble("temperature", ambient())));
    }

    /** What is lost when the crucibles are gone: the pool beyond the remaining capacity (fluid, like a broken tank). */
    void trimTo(long cap) {
        long over = used() - cap;
        for (FfId f : new ArrayList<>(pool.keySet())) {
            if (over <= 0) {
                break;
            }
            long a = pool.get(f);
            long cut = Math.min(a, over);
            over -= cut;
            if (a - cut <= 0) {
                pool.remove(f);
            } else {
                pool.put(f, a - cut);
            }
        }
    }
}
