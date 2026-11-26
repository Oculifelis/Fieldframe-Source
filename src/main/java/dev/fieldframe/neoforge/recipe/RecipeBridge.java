package dev.fieldframe.neoforge.recipe;

import dev.fieldframe.api.id.FfId;
import dev.fieldframe.api.process.ProcessFamilies;
import dev.fieldframe.api.process.ProcessRecipe;
import dev.fieldframe.api.process.RecipeSource;
import dev.fieldframe.api.resource.FluidVariant;
import dev.fieldframe.neoforge.Fieldframe;
import dev.fieldframe.neoforge.adapter.McFluidVariant;
import dev.fieldframe.neoforge.adapter.McItemVariant;
import dev.fieldframe.neoforge.registry.FfContent;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeMap;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.item.crafting.SmeltingRecipe;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.fluids.FluidStack;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Effective domain recipes for 26.1. Recipes live only on the server ({@link RecipeMap}); the client receives
 * Fieldframe's types and smelting through NeoForge's recipe sync (see {@code Fieldframe#onDatapackSync}) and
 * {@link #setClientRecipes} so the manual shows the same effective data. Cached per map instance.
 */
public final class RecipeBridge implements RecipeSource {
    public static final int HEATER_RF_PER_TICK = 20;

    private static WeakReference<RecipeMap> cachedFor = new WeakReference<>(null);
    private static RecipeBridge cached;
    private static long generation = 1;
    private static volatile RecipeMap clientRecipes;

    private final Map<FfId, List<ProcessRecipe>> byFamily;
    private final long gen;

    private RecipeBridge(Map<FfId, List<ProcessRecipe>> byFamily, long gen) {
        this.byFamily = byFamily;
        this.gen = gen;
    }

    public static synchronized void invalidate() {
        cachedFor = new WeakReference<>(null);
        cached = null;
        generation++;
    }

    public static void setClientRecipes(RecipeMap map) {
        clientRecipes = map;
    }

    /** Recipes the server synced to this client (Fieldframe types and smelting), or null before the first sync. */
    public static RecipeMap clientRecipes() {
        return clientRecipes;
    }

    public static synchronized RecipeBridge forLevel(Level level) {
        RecipeMap map = level.getServer() != null && !level.isClientSide() ? level.getServer().getRecipeManager().recipeMap() : clientRecipes;
        if (map == null) {
            return new RecipeBridge(Map.of(), 0);
        }
        if (cached == null || cachedFor.get() != map) {
            boolean server = level.getServer() != null && !level.isClientSide();
            cached = build(map, level.registryAccess(), server);
            cachedFor = new WeakReference<>(map);
        }
        return cached;
    }

    @Override
    public List<ProcessRecipe> recipes(FfId family) {
        return byFamily.getOrDefault(family, List.of());
    }

    @Override
    public long generation() {
        return gen;
    }

    private static FfId id(ResourceKey<Recipe<?>> key) {
        return FfId.of(key.identifier().getNamespace(), key.identifier().getPath());
    }

    private static RecipeBridge build(RecipeMap rm, net.minecraft.core.HolderLookup.Provider registries, boolean server) {
        Map<FfId, List<ProcessRecipe>> map = new HashMap<>();
        int skipped = 0;
        for (RecipeHolder<SmeltingRecipe> holder : rm.byType(RecipeType.SMELTING)) {
            SmeltingRecipe r = holder.value();
            try {
                Ingredient ing = r.input();
                Holder<Item> example = ing.items().findFirst().orElse(null);
                if (example == null) {
                    continue;
                }
                ItemStack out = r.assemble(new SingleRecipeInput(new ItemStack(example)));
                McItemVariant v = McItemVariant.of(out);
                if (v == null) {
                    continue;
                }
                if (dev.fieldframe.core.material.RecipeFilters.removed(id(holder.id()), ProcessFamilies.HEATING)) {
                    continue;
                }
                map.computeIfAbsent(ProcessFamilies.HEATING, k -> new ArrayList<>()).add(new ProcessRecipe(id(holder.id()), ProcessFamilies.HEATING,
                        List.of(input(ing, 1, true)), null, heatingResults(v, out.getCount(), new ItemStack(example)), null,
                        Math.max(1, r.cookingTime() / 2), HEATER_RF_PER_TICK));
            } catch (RuntimeException e) {
                skipped++;
            }
        }
        for (var entry : FfContent.PROCESS_TYPES.entrySet()) {
            for (RecipeHolder<FfProcessRecipe> holder : rm.byType(entry.getValue().get())) {
                FfProcessRecipe r = holder.value();
                if (dev.fieldframe.core.material.RecipeFilters.removed(id(holder.id()), r.familyId())) {
                    continue;
                }
                try {
                    map.computeIfAbsent(r.familyId(), k -> new ArrayList<>()).add(convert(id(holder.id()), r.familyId(), r));
                } catch (RuntimeException e) {
                    skipped++;
                    Fieldframe.LOG.warn("Fieldframe recipe {} skipped: {}", holder.id(), e.getMessage());
                }
            }
        }
        // Metallurgy's alloyer / crusher / chamber recipes, read live (see MetallurgyMirror).
        MetallurgyMirror.Result mirror = MetallurgyMirror.read(rm, registries);
        for (RecipeHolder<FfProcessRecipe> holder : mirror.processes()) {
            try {
                map.computeIfAbsent(holder.value().familyId(), k -> new ArrayList<>()).add(convert(id(holder.id()), holder.value().familyId(), holder.value()));
            } catch (RuntimeException e) {
                skipped++;
            }
        }
        if (server) {
            dev.fieldframe.core.material.Auras.setMirrored(mirror.auras());
        }
        if (skipped > 0) {
            Fieldframe.LOG.info("Fieldframe recipe bridge skipped {} recipes that could not be represented", skipped);
        }
        map.replaceAll((k, v) -> List.copyOf(v));
        return new RecipeBridge(Map.copyOf(map), ++generation);
    }

    static ProcessRecipe.ItemInput input(Ingredient ing, int count, boolean consumed) {
        List<dev.fieldframe.api.resource.ItemVariant> examples = new ArrayList<>();
        ing.items().limit(8).forEach(h -> {
            McItemVariant v = McItemVariant.of(new ItemStack(h));
            if (v != null) {
                examples.add(v);
            }
        });
        String display = examples.isEmpty() ? "?" : McItemVariant.toStack(examples.get(0), 1).getHoverName().getString();
        return new ProcessRecipe.ItemInput((v, tags) -> v instanceof McItemVariant m && ing.test(m.toStack(1)), count, consumed, display, examples);
    }

    public static ProcessRecipe convert(FfId id, FfId family, FfProcessRecipe r) {
        List<ProcessRecipe.ItemInput> ins = new ArrayList<>();
        for (FfProcessRecipe.Input i : r.inputs()) {
            ins.add(input(i.ingredient(), i.count(), !i.catalyst()));
        }
        ProcessRecipe.FluidInput fin = null;
        if (r.fluidInput().isPresent()) {
            FfProcessRecipe.FluidIn fi = r.fluidInput().get();
            fin = new ProcessRecipe.FluidInput((FluidVariant v, dev.fieldframe.api.resource.TagLookup t) ->
                    v instanceof McFluidVariant m && fi.matches(m.toStack(1).getFluid()), fi.amount(), fi.display());
        }
        List<ProcessRecipe.ItemResult> res = new ArrayList<>();
        for (FfProcessRecipe.Result x : r.results()) {
            ItemStack st = x.stack();
            McItemVariant v = McItemVariant.of(st);
            if (v != null) {
                res.add(new ProcessRecipe.ItemResult(v, st.getCount(), x.chance()));
            }
        }
        ProcessRecipe.FluidResult fres = null;
        FluidStack fs = r.fluidResult();
        if (!fs.isEmpty()) {
            fres = new ProcessRecipe.FluidResult(McFluidVariant.of(fs), fs.getAmount());
        }
        return new ProcessRecipe(id, family, ins, fin, res, fres, r.duration(), r.energy());
    }

    /** The Heater's result for a furnace recipe. Smelting an ore or a raw ore also gives Slag, like blasting ore. */
    private static List<ProcessRecipe.ItemResult> heatingResults(McItemVariant v, int count, ItemStack in) {
        List<ProcessRecipe.ItemResult> res = new ArrayList<>();
        res.add(new ProcessRecipe.ItemResult(v, count, 1.0));
        if (!in.isEmpty() && (in.is(net.neoforged.neoforge.common.Tags.Items.ORES) || in.is(net.neoforged.neoforge.common.Tags.Items.RAW_MATERIALS))) {
            McItemVariant slag = McItemVariant.of(new ItemStack(net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(net.minecraft.resources.Identifier.fromNamespaceAndPath("fieldframe", "slag"))));
            if (slag != null) {
                res.add(new ProcessRecipe.ItemResult(slag, 1, dev.fieldframe.core.material.Materials.ORE_SLAG_CHANCE));
            }
        }
        return List.copyOf(res);
    }
}
