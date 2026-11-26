package dev.fieldframe.neoforge.data;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import dev.fieldframe.core.material.FrameComponentData;
import dev.fieldframe.neoforge.Fieldframe;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Loads {@code data/<ns>/fieldframe/frame_components/*.json} into the frame components (format in FrameComponentData). */
public final class FrameComponentReloadListener extends SimpleJsonResourceReloadListener {
    public FrameComponentReloadListener() {
        super(new Gson(), "fieldframe/frame_components");
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> files, ResourceManager rm, ProfilerFiller profiler) {
        Map<String, String> raw = new TreeMap<>();
        files.forEach((id, json) -> raw.put(id.toString(), json.toString()));
        List<String> errors = FrameComponentData.load(raw);
        errors.forEach(e -> Fieldframe.LOG.error("Fieldframe frame components: {} (file skipped)", e));
        Fieldframe.LOG.info("Fieldframe frame components: {} changes from {} files",
                dev.fieldframe.api.frame.FrameComponents.overrides().size(), files.size());
    }
}
