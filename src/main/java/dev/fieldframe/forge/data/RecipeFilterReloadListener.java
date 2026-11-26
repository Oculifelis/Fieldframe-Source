package dev.fieldframe.forge.data;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import dev.fieldframe.core.material.RecipeFilters;
import dev.fieldframe.forge.Fieldframe;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Loads {@code data/<ns>/fieldframe/recipe_filters/*.json} into {@link RecipeFilters} (format documented there). */
public final class RecipeFilterReloadListener extends SimpleJsonResourceReloadListener {
    public RecipeFilterReloadListener() {
        super(new Gson(), "fieldframe/recipe_filters");
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> files, ResourceManager rm, ProfilerFiller profiler) {
        Map<String, String> raw = new TreeMap<>();
        files.forEach((id, json) -> raw.put(id.toString(), json.toString()));
        List<String> errors = RecipeFilters.load(raw);
        errors.forEach(e -> Fieldframe.LOG.error("Fieldframe recipe filters: {} (file skipped)", e));
        Fieldframe.LOG.info("Fieldframe recipe filters: {} files", files.size());
    }
}
