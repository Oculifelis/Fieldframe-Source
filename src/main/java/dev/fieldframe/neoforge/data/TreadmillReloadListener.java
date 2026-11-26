package dev.fieldframe.neoforge.data;

import com.google.gson.JsonElement;
import dev.fieldframe.core.material.TreadmillMobs;
import dev.fieldframe.neoforge.Fieldframe;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Loads {@code data/<ns>/fieldframe/treadmill/*.json} into {@link TreadmillMobs} (format documented there). */
public final class TreadmillReloadListener extends SimpleJsonResourceReloadListener<JsonElement> {
    public TreadmillReloadListener() {
        super(net.minecraft.util.ExtraCodecs.JSON, net.minecraft.resources.FileToIdConverter.json("fieldframe/treadmill"));
    }

    @Override
    protected void apply(Map<Identifier, JsonElement> files, ResourceManager rm, ProfilerFiller profiler) {
        Map<String, String> raw = new TreeMap<>();
        files.forEach((id, json) -> raw.put(id.toString(), json.toString()));
        List<String> errors = TreadmillMobs.load(raw);
        // Machine data was reloaded: scripts' unifier preferences run again on next use.
        dev.fieldframe.core.material.Unification.invalidate();
        errors.forEach(e -> Fieldframe.LOG.error("Fieldframe treadmill animals: {} (file skipped)", e));
        Fieldframe.LOG.info("Fieldframe treadmill animals: {} data entries from {} files", files.size() - errors.size(), files.size());
    }
}
