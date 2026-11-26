package dev.fieldframe.neoforge.data;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import dev.fieldframe.core.flora.FloraPairs;
import dev.fieldframe.neoforge.Fieldframe;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Loads {@code data/<ns>/fieldframe/fluid_armor_pairs/*.json} into {@link FloraPairs} (format documented there). */
public final class FluidArmorPairsReloadListener extends SimpleJsonResourceReloadListener {
    public FluidArmorPairsReloadListener() {
        super(new Gson(), "fieldframe/fluid_armor_pairs");
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> files, ResourceManager rm, ProfilerFiller profiler) {
        Map<String, String> raw = new TreeMap<>();
        files.forEach((id, json) -> raw.put(id.toString(), json.toString()));
        List<String> errors = FloraPairs.load(raw);
        errors.forEach(e -> Fieldframe.LOG.error("Fieldframe fluid armor pairs: {} (file skipped)", e));
        Fieldframe.LOG.info("Fieldframe fluid armor pairs: {} entries from {} files", FloraPairs.all().size(), files.size());
    }
}
