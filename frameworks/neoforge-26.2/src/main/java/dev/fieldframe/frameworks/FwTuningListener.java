package dev.fieldframe.frameworks;

import com.google.gson.JsonElement;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.ExtraCodecs;
import net.minecraft.util.profiling.ProfilerFiller;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Loads {@code data/<ns>/fieldframe_frameworks/tuning/*.json} into {@link FwTuning} on every data reload. */
final class FwTuningListener extends SimpleJsonResourceReloadListener<JsonElement> {
    FwTuningListener() {
        super(ExtraCodecs.JSON, FileToIdConverter.json("fieldframe_frameworks/tuning"));
    }

    @Override
    protected void apply(Map<Identifier, JsonElement> files, ResourceManager rm, ProfilerFiller profiler) {
        Map<String, String> raw = new TreeMap<>();
        files.forEach((id, json) -> raw.put(id.toString(), json.toString()));
        List<String> errors = FwTuning.load(raw);
        errors.forEach(e -> Frameworks.LOG.error("Frameworks tuning: {} (skipped)", e));
        Frameworks.LOG.info("Frameworks tuning: {} files", files.size());
    }
}
