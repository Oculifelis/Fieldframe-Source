package dev.fieldframe.neoforge.data;

import com.google.gson.JsonElement;
import dev.fieldframe.core.material.Auras;
import dev.fieldframe.neoforge.Fieldframe;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Loads {@code data/<ns>/fieldframe/aura/*.json} into {@link Auras} (format documented there). */
public final class AuraReloadListener extends SimpleJsonResourceReloadListener<JsonElement> {
    public AuraReloadListener() {
        super(net.minecraft.util.ExtraCodecs.JSON, net.minecraft.resources.FileToIdConverter.json("fieldframe/aura"));
    }

    @Override
    protected void apply(Map<Identifier, JsonElement> files, ResourceManager rm, ProfilerFiller profiler) {
        Map<String, String> raw = new TreeMap<>();
        files.forEach((id, json) -> raw.put(id.toString(), json.toString()));
        List<String> errors = Auras.load(raw);
        errors.forEach(e -> Fieldframe.LOG.error("Fieldframe auras: {} (file skipped)", e));
        Fieldframe.LOG.info("Fieldframe auras: {} data entries from {} files", files.size() - errors.size(), files.size());
    }
}
