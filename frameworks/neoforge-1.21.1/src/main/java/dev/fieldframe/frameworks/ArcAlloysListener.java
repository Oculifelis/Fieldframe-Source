package dev.fieldframe.frameworks;

import com.google.gson.JsonElement;
import dev.fieldframe.api.id.FfId;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Loads {@code data/<ns>/fieldframe_frameworks/arc_alloys/*.json} into {@link ArcAlloys} on every data reload. */
final class ArcAlloysListener extends SimpleJsonResourceReloadListener {
    ArcAlloysListener() {
        super(new com.google.gson.Gson(), "fieldframe_frameworks/arc_alloys");
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> files, ResourceManager rm, ProfilerFiller profiler) {
        Map<FfId, String> raw = new TreeMap<>();
        files.forEach((id, json) -> raw.put(FfId.of(id.getNamespace(), id.getPath()), json.toString()));
        List<String> errors = ArcAlloys.load(raw, item -> BuiltInRegistries.ITEM.containsKey(ResourceLocation.fromNamespaceAndPath(item.namespace(), item.path())));
        errors.forEach(e -> Frameworks.LOG.error("Arc alloy {} (skipped)", e));
        Frameworks.LOG.info("Arc alloys: {} usable", ArcAlloys.all().size());
    }
}
