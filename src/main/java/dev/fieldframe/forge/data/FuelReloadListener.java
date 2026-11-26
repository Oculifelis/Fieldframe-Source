package dev.fieldframe.forge.data;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import dev.fieldframe.core.material.Fuels;
import dev.fieldframe.forge.Fieldframe;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Loads {@code data/<ns>/fieldframe/fuels/*.json} into {@link Fuels} (format and precedence documented there). */
public final class FuelReloadListener extends SimpleJsonResourceReloadListener {
    public FuelReloadListener() {
        super(new Gson(), "fieldframe/fuels");
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> files, ResourceManager rm, ProfilerFiller profiler) {
        Map<String, String> raw = new TreeMap<>();
        files.forEach((id, json) -> raw.put(id.toString(), json.toString()));
        List<String> errors = Fuels.load(raw);
        errors.forEach(e -> Fieldframe.LOG.error("Fieldframe fuels: {} (file skipped)", e));
        Fieldframe.LOG.info("Fieldframe fuels: {} entries from {} files", Fuels.all().size(), files.size());
    }
}
