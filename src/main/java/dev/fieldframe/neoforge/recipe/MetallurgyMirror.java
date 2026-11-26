package dev.fieldframe.neoforge.recipe;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import dev.fieldframe.core.material.Auras;
import dev.fieldframe.neoforge.Fieldframe;
import dev.fieldframe.neoforge.registry.FfContent;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeType;

import java.util.ArrayList;
import java.util.List;

/**
 * Reads Metallurgy's own processing recipes ({@code metallurgy:processing}) from the loaded recipes, so Fieldframe always
 * runs what Metallurgy currently defines: alloyer to the Alloy Smelter, crusher to the Crusher, chamber to the Aura
 * Chamber. Each recipe is encoded with its own codec and converted, so Metallurgy's load conditions and any pack edits
 * to its recipes apply automatically; nothing needs regenerating when Metallurgy changes.
 *
 * <p>Ids are the ones Forge 1.20.1 ships as data: {@code fieldframe:alloying/metallurgy/<name>},
 * {@code fieldframe:crushing/metallurgy/<name>} and aura {@code fieldframe:metallurgy_<name>}. A pack recipe with that id
 * (datapack or KubeJS) replaces the one read from Metallurgy; {@code fieldframe/recipe_filters} removes it.
 */
public final class MetallurgyMirror {
    public static final ResourceLocation TYPE = ResourceLocation.fromNamespaceAndPath("metallurgy", "processing");

    public record Result(List<RecipeHolder<FfProcessRecipe>> processes, List<Auras.Entry> auras) {
        static final Result EMPTY = new Result(List.of(), List.of());
    }

    private MetallurgyMirror() {
    }

    /** Metallurgy's processing recipe type, or null when Metallurgy is not installed. */
    public static RecipeType<?> type() {
        return BuiltInRegistries.RECIPE_TYPE.getOptional(TYPE).orElse(null);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    public static Result read(RecipeManager map, HolderLookup.Provider registries) {
        RecipeType type = type();
        if (type == null || map == null) {
            return Result.EMPTY;
        }
        var ops = registries.createSerializationContext(JsonOps.INSTANCE);
        List<RecipeHolder<FfProcessRecipe>> processes = new ArrayList<>();
        List<Auras.Entry> auras = new ArrayList<>();
        int skipped = 0;
        for (Object raw : map.getAllRecipesFor(type)) {
            RecipeHolder<?> holder = (RecipeHolder<?>) raw;
            String id = holder.id().toString();
            try {
                JsonObject o = Recipe.CODEC.encodeStart(ops, holder.value()).getOrThrow().getAsJsonObject();
                String kind = o.has("kind") ? o.get("kind").getAsString() : "";
                JsonArray ingredients = o.has("ingredients") ? o.getAsJsonArray("ingredients") : new JsonArray();
                JsonArray amounts = o.has("amounts") ? o.getAsJsonArray("amounts") : new JsonArray();
                String name = holder.id().getPath();
                name = name.substring(name.lastIndexOf('/') + 1);
                if (kind.equals("chamber") && !ingredients.isEmpty()) {
                    auras.add(new Auras.Entry(ingredientKey(ingredients.get(0)), amounts.isEmpty() ? 1 : amounts.get(0).getAsInt(),
                            dev.fieldframe.api.id.FfId.parse(o.get("effect").getAsString()),
                            o.has("duration") ? o.get("duration").getAsInt() : 600, o.has("amplifier") ? o.get("amplifier").getAsInt() : 0,
                            Fieldframe.MOD_ID + ":metallurgy_" + name));
                    continue;
                }
                String family = kind.equals("alloyer") ? "process" : kind.equals("crusher") ? "crushing" : null;
                if (family == null || ingredients.isEmpty() || !o.has("result")) {
                    continue;
                }
                String dir = kind.equals("alloyer") ? "alloying" : "crushing";
                ResourceLocation ffId = ResourceLocation.fromNamespaceAndPath(Fieldframe.MOD_ID, dir + "/metallurgy/" + name);
                if (overridden(map, ffId) || dev.fieldframe.core.material.RecipeFilters.removed(
                        dev.fieldframe.api.id.FfId.of(ffId.getNamespace(), ffId.getPath()),
                        dev.fieldframe.api.id.FfId.of(Fieldframe.MOD_ID, family.equals("process") ? "alloying" : "crushing"))) {
                    continue;
                }
                JsonObject ff = new JsonObject();
                if (family.equals("process")) {
                    ff.addProperty("family", "fieldframe:alloying");
                }
                JsonArray ins = new JsonArray();
                for (int i = 0; i < ingredients.size(); i++) {
                    JsonObject in = new JsonObject();
                    in.add("ingredient", ingredients.get(i));
                    in.addProperty("count", i < amounts.size() ? amounts.get(i).getAsInt() : 1);
                    ins.add(in);
                }
                ff.add("inputs", ins);
                JsonArray results = new JsonArray();
                results.add(o.get("result"));
                ff.add("results", results);
                ff.addProperty("duration", family.equals("process") ? 160 : 120);
                ff.addProperty("energy", family.equals("process") ? 32 : 24);
                FfProcessRecipe r = FfContent.PROCESS_SERIALIZERS.get(family).get().codec().codec().parse(ops, ff).getOrThrow();
                processes.add(new RecipeHolder<>(ffId, r));
            } catch (RuntimeException e) {
                skipped++;
                Fieldframe.LOG.debug("Fieldframe: Metallurgy recipe {} not mirrored: {}", id, e.getMessage());
            }
        }
        if (skipped > 0) {
            Fieldframe.LOG.info("Fieldframe: {} Metallurgy recipes could not be mirrored", skipped);
        }
        return new Result(List.copyOf(processes), List.copyOf(auras));
    }

    /** A real recipe with this id (datapack or KubeJS) replaces the one read from Metallurgy. */
    private static boolean overridden(RecipeManager map, ResourceLocation id) {
        return map.byKey(id).isPresent();
    }

    /** An encoded ingredient as an aura key: "#tag" or an item id (the first of a list). */
    static String ingredientKey(JsonElement e) {
        if (e.isJsonPrimitive()) {
            return e.getAsString();
        }
        if (e.isJsonArray() && !e.getAsJsonArray().isEmpty()) {
            return ingredientKey(e.getAsJsonArray().get(0));
        }
        JsonObject o = e.getAsJsonObject();
        return o.has("tag") ? "#" + o.get("tag").getAsString() : o.get("item").getAsString();
    }
}
