package dev.fieldframe.forge.recipe;

import dev.fieldframe.api.id.FfId;
import dev.fieldframe.api.process.ProcessFamilies;
import dev.fieldframe.api.process.ProcessRecipe;
import dev.fieldframe.api.process.RecipeSource;
import dev.fieldframe.api.resource.FluidVariant;
import dev.fieldframe.forge.Fieldframe;
import dev.fieldframe.forge.adapter.Dirs;
import dev.fieldframe.forge.adapter.McFluidVariant;
import dev.fieldframe.forge.adapter.McItemVariant;
import dev.fieldframe.forge.registry.FfContent;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SmeltingRecipe;
import net.minecraft.world.level.Level;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.registries.ForgeRegistries;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the effective domain recipe set from the live {@link RecipeManager} (after datapacks and KubeJS),
 * cached per manager instance and rebuilt on reload. The Heater family is backed by vanilla smelting recipes
 * (duration halved, 20 RF/t: 2,000 RF per standard 200-tick smelt) plus any {@code fieldframe:heating} recipes.
 * The same view feeds processors, the inspector and the manual's recipe panels.
 */
public final class RecipeBridge implements RecipeSource {
    public static final int HEATER_RF_PER_TICK = 20;

    private static WeakReference<RecipeManager> cachedFor = new WeakReference<>(null);
    private static RecipeBridge cached;
    private static long generation = 1;

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

    public static synchronized RecipeBridge forLevel(Level level) {
        RecipeManager rm = level.getRecipeManager();
        if (cached == null || cachedFor.get() != rm) {
            cached = build(rm, level.registryAccess());
            cachedFor = new WeakReference<>(rm);
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

    private static RecipeBridge build(RecipeManager rm, RegistryAccess access) {
        Map<FfId, List<ProcessRecipe>> map = new HashMap<>();
        int skipped = 0;
        for (SmeltingRecipe r : rm.getAllRecipesFor(RecipeType.SMELTING)) {
            try {
                ItemStack out = r.getResultItem(access);
                McItemVariant v = McItemVariant.of(out);
                if (v == null || r.getIngredients().isEmpty()) {
                    continue;
                }
                Ingredient ing = r.getIngredients().get(0);
                if (dev.fieldframe.core.material.RecipeFilters.removed(Dirs.id(r.getId()), ProcessFamilies.HEATING)) {
                    continue;
                }
                map.computeIfAbsent(ProcessFamilies.HEATING, k -> new ArrayList<>()).add(new ProcessRecipe(Dirs.id(r.getId()), ProcessFamilies.HEATING,
                        List.of(input(ing, 1, true)), null, heatingResults(v, out.getCount(), first(ing)), null,
                        Math.max(1, r.getCookingTime() / 2), HEATER_RF_PER_TICK));
            } catch (RuntimeException e) {
                skipped++;
            }
        }
        for (var entry : FfContent.PROCESS_TYPES.entrySet()) {
            for (FfProcessRecipe r : rm.getAllRecipesFor(entry.getValue().get())) {
                FfId family = r.familyId();
                if (dev.fieldframe.core.material.RecipeFilters.removed(Dirs.id(r.getId()), family)) {
                    continue;
                }
                try {
                    map.computeIfAbsent(family, k -> new ArrayList<>()).add(convert(family, r));
                } catch (RuntimeException e) {
                    skipped++;
                    Fieldframe.LOG.warn("Fieldframe recipe {} skipped: {}", r.getId(), e.getMessage());
                }
            }
        }
        if (skipped > 0) {
            Fieldframe.LOG.info("Fieldframe recipe bridge skipped {} recipes that could not be represented", skipped);
        }
        map.replaceAll((k, v) -> List.copyOf(v));
        return new RecipeBridge(Map.copyOf(map), ++generation);
    }

    static ProcessRecipe.ItemInput input(Ingredient ing, int count, boolean consumed) {
        ItemStack[] shown = ing.getItems();
        String display = shown.length == 0 ? "?" : shown[0].getHoverName().getString();
        List<dev.fieldframe.api.resource.ItemVariant> examples = new ArrayList<>();
        for (int i = 0; i < Math.min(8, shown.length); i++) {
            McItemVariant v = McItemVariant.of(shown[i]);
            if (v != null) {
                examples.add(v);
            }
        }
        return new ProcessRecipe.ItemInput((v, tags) -> v instanceof McItemVariant m && ing.test(m.toStack(1)), count, consumed, display, examples);
    }

    public static ProcessRecipe convert(FfId family, FfProcessRecipe r) {
        List<ProcessRecipe.ItemInput> ins = new ArrayList<>();
        for (FfProcessRecipe.Input i : r.inputs()) {
            ins.add(input(i.ingredient(), i.count(), !i.catalyst()));
        }
        ProcessRecipe.FluidInput fin = null;
        if (r.fluidInput() != null) {
            FfProcessRecipe.FluidIn fi = r.fluidInput();
            String display = fi.fluid() != null ? fi.fluid().toString() : "#" + fi.tag();
            fin = new ProcessRecipe.FluidInput((FluidVariant v, dev.fieldframe.api.resource.TagLookup t) ->
                    v instanceof McFluidVariant m && fi.matches(m.toStack(1).getFluid()), fi.amount(), display);
        }
        List<ProcessRecipe.ItemResult> res = new ArrayList<>();
        for (FfProcessRecipe.Result x : r.results()) {
            McItemVariant v = McItemVariant.of(x.stack());
            if (v != null) {
                res.add(new ProcessRecipe.ItemResult(v, x.stack().getCount(), x.chance()));
            }
        }
        ProcessRecipe.FluidResult fres = null;
        FluidStack fs = r.fluidResult();
        if (!fs.isEmpty()) {
            fres = new ProcessRecipe.FluidResult(McFluidVariant.of(fs), fs.getAmount());
        }
        return new ProcessRecipe(Dirs.id(r.getId()), family, ins, fin, res, fres, r.duration(), r.energy());
    }

    /** Whether a fluid id is known; used by validators. */
    public static boolean fluidExists(net.minecraft.resources.ResourceLocation rl) {
        return ForgeRegistries.FLUIDS.containsKey(rl);
    }

    /** The Heater's result for a furnace recipe. Smelting an ore or a raw ore also gives Slag, like blasting ore. */
    private static List<ProcessRecipe.ItemResult> heatingResults(McItemVariant v, int count, ItemStack in) {
        List<ProcessRecipe.ItemResult> res = new ArrayList<>();
        res.add(new ProcessRecipe.ItemResult(v, count, 1.0));
        if (!in.isEmpty() && (in.is(net.minecraftforge.common.Tags.Items.ORES) || in.is(net.minecraftforge.common.Tags.Items.RAW_MATERIALS))) {
            McItemVariant slag = McItemVariant.of(new ItemStack(ForgeRegistries.ITEMS.getValue(new net.minecraft.resources.ResourceLocation("fieldframe", "slag"))));
            if (slag != null) {
                res.add(new ProcessRecipe.ItemResult(slag, 1, dev.fieldframe.core.material.Materials.ORE_SLAG_CHANCE));
            }
        }
        return List.copyOf(res);
    }

    private static ItemStack first(Ingredient ing) {
        ItemStack[] items = ing.getItems();
        return items.length == 0 ? ItemStack.EMPTY : items[0];
    }
}
