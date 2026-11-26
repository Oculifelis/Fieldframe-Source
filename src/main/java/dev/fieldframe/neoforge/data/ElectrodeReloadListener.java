package dev.fieldframe.neoforge.data;

import com.google.gson.JsonElement;
import dev.fieldframe.core.material.Electrodes;
import dev.fieldframe.neoforge.Fieldframe;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Loads {@code data/<ns>/fieldframe/electrodes/*.json} into {@link Electrodes} (format documented there). */
public final class ElectrodeReloadListener extends SimpleJsonResourceReloadListener<JsonElement> {
    public ElectrodeReloadListener() {
        super(net.minecraft.util.ExtraCodecs.JSON, net.minecraft.resources.FileToIdConverter.json("fieldframe/electrodes"));
    }

    @Override
    protected void apply(Map<Identifier, JsonElement> files, ResourceManager rm, ProfilerFiller profiler) {
        Map<String, String> raw = new TreeMap<>();
        files.forEach((id, json) -> raw.put(id.toString(), json.toString()));
        List<String> errors = Electrodes.load(raw);
        errors.forEach(e -> Fieldframe.LOG.error("Fieldframe electrodes: {} (file skipped)", e));
        Fieldframe.LOG.info("Fieldframe electrodes: {} data entries from {} files", files.size() - errors.size(), files.size());
    }
}
