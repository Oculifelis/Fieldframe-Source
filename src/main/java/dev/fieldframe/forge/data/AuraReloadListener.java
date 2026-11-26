package dev.fieldframe.forge.data;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import dev.fieldframe.core.material.Auras;
import dev.fieldframe.forge.Fieldframe;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Loads {@code data/<ns>/fieldframe/aura/*.json} into {@link Auras} (format documented there). */
public final class AuraReloadListener extends SimpleJsonResourceReloadListener {
    public AuraReloadListener() {
        super(new Gson(), "fieldframe/aura");
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> files, ResourceManager rm, ProfilerFiller profiler) {
        Map<String, String> raw = new TreeMap<>();
        files.forEach((id, json) -> raw.put(id.toString(), json.toString()));
        List<String> errors = Auras.load(raw);
        errors.forEach(e -> Fieldframe.LOG.error("Fieldframe auras: {} (file skipped)", e));
        Fieldframe.LOG.info("Fieldframe auras: {} data entries from {} files", files.size() - errors.size(), files.size());
    }
}
