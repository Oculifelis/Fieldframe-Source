package dev.fieldframe.neoforge.data;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import dev.fieldframe.core.material.Electrodes;
import dev.fieldframe.neoforge.Fieldframe;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Loads {@code data/<ns>/fieldframe/electrodes/*.json} into {@link Electrodes} (format documented there). */
public final class ElectrodeReloadListener extends SimpleJsonResourceReloadListener {
    public ElectrodeReloadListener() {
        super(new Gson(), "fieldframe/electrodes");
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> files, ResourceManager rm, ProfilerFiller profiler) {
        Map<String, String> raw = new TreeMap<>();
        files.forEach((id, json) -> raw.put(id.toString(), json.toString()));
        List<String> errors = Electrodes.load(raw);
        errors.forEach(e -> Fieldframe.LOG.error("Fieldframe electrodes: {} (file skipped)", e));
        Fieldframe.LOG.info("Fieldframe electrodes: {} data entries from {} files", files.size() - errors.size(), files.size());
    }
}
