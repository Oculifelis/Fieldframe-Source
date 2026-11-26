package dev.fieldframe.neoforge.data;

import com.google.gson.JsonElement;
import dev.fieldframe.core.flora.FluidArmorEffects;
import dev.fieldframe.neoforge.Fieldframe;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.neoforged.fml.ModList;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Loads {@code data/<ns>/fieldframe/fluid_armor_effects/*.json} into {@link FluidArmorEffects} (format documented there). */
public final class FluidArmorReloadListener extends SimpleJsonResourceReloadListener<JsonElement> {
    public FluidArmorReloadListener() {
        super(net.minecraft.util.ExtraCodecs.JSON, net.minecraft.resources.FileToIdConverter.json("fieldframe/fluid_armor_effects"));
    }

    @Override
    protected void apply(Map<Identifier, JsonElement> files, ResourceManager rm, ProfilerFiller profiler) {
        Map<String, String> raw = new TreeMap<>();
        files.forEach((id, json) -> raw.put(id.toString(), json.toString()));
        FluidArmorEffects.setModLoaded(m -> ModList.get().isLoaded(m));
        List<String> errors = FluidArmorEffects.load(raw);
        errors.forEach(e -> Fieldframe.LOG.error("Fieldframe fluid armor effects: {} (file skipped)", e));
        Fieldframe.LOG.info("Fieldframe fluid armor effects: {} entries from {} files", FluidArmorEffects.all().size(), files.size());
    }
}
