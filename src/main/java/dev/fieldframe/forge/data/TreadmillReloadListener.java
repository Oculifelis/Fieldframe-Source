package dev.fieldframe.forge.data;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import dev.fieldframe.core.material.TreadmillMobs;
import dev.fieldframe.forge.Fieldframe;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Loads {@code data/<ns>/fieldframe/treadmill/*.json} into {@link TreadmillMobs} (format documented there). */
public final class TreadmillReloadListener extends SimpleJsonResourceReloadListener {
    public TreadmillReloadListener() {
        super(new Gson(), "fieldframe/treadmill");
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> files, ResourceManager rm, ProfilerFiller profiler) {
        Map<String, String> raw = new TreeMap<>();
        files.forEach((id, json) -> raw.put(id.toString(), json.toString()));
        List<String> errors = TreadmillMobs.load(raw);
        // Machine data was reloaded: scripts' unifier preferences run again on next use.
        dev.fieldframe.core.material.Unification.invalidate();
        errors.forEach(e -> Fieldframe.LOG.error("Fieldframe treadmill animals: {} (file skipped)", e));
        Fieldframe.LOG.info("Fieldframe treadmill animals: {} data entries from {} files", files.size() - errors.size(), files.size());
    }
}
