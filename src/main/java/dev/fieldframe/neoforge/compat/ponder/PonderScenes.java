package dev.fieldframe.neoforge.compat.ponder;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.fieldframe.neoforge.Fieldframe;
import net.createmod.ponder.api.client.PonderIndex;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;

import java.io.Reader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Reads the scene and index files on every resource reload. Index files from all packs are merged in load order: an
 * item's list is appended to, and an empty list removes the item's scenes. Afterwards Ponder re-registers, so F3+T
 * applies changed scenes without a restart.
 */
final class PonderScenes extends SimplePreparableReloadListener<PonderScenes.Loaded> {
    record Loaded(Map<Identifier, JsonObject> scenes, Map<Identifier, List<Identifier>> index) {
    }

    private static final String SCENES = "fieldframe_ponder/scenes";
    private static final String INDEX = "fieldframe_ponder/index";

    private static volatile Loaded current = new Loaded(Map.of(), Map.of());

    static Map<Identifier, List<Identifier>> index() {
        return current.index();
    }

    static JsonObject scene(Identifier id) {
        return current.scenes().get(id);
    }

    @Override
    protected Loaded prepare(ResourceManager rm, ProfilerFiller profiler) {
        Map<Identifier, JsonObject> scenes = new TreeMap<>();
        for (Map.Entry<Identifier, Resource> e : rm.listResources(SCENES, rl -> rl.getPath().endsWith(".json")).entrySet()) {
            JsonObject json = read(e.getKey(), e.getValue());
            if (json != null) {
                String path = e.getKey().getPath();
                scenes.put(Identifier.fromNamespaceAndPath(e.getKey().getNamespace(), path.substring(SCENES.length() + 1, path.length() - 5)), json);
            }
        }
        Map<Identifier, List<Identifier>> index = new LinkedHashMap<>();
        for (Map.Entry<Identifier, List<Resource>> e : rm.listResourceStacks(INDEX, rl -> rl.getPath().endsWith(".json")).entrySet()) {
            for (Resource r : e.getValue()) {
                JsonObject json = read(e.getKey(), r);
                if (json == null || !json.has("scenes")) {
                    continue;
                }
                for (Map.Entry<String, JsonElement> item : json.getAsJsonObject("scenes").entrySet()) {
                    Identifier itemId = Identifier.tryParse(item.getKey());
                    if (itemId == null) {
                        Fieldframe.LOG.warn("Fieldframe Ponder: bad item id {} in {}", item.getKey(), e.getKey());
                        continue;
                    }
                    List<Identifier> list = index.computeIfAbsent(itemId, k -> new ArrayList<>());
                    if (item.getValue().getAsJsonArray().isEmpty()) {
                        list.clear();
                    }
                    for (JsonElement s : item.getValue().getAsJsonArray()) {
                        Identifier sid = Identifier.tryParse(s.getAsString());
                        if (sid != null && !list.contains(sid)) {
                            list.add(sid);
                        }
                    }
                }
            }
        }
        return new Loaded(scenes, index);
    }

    private static JsonObject read(Identifier file, Resource r) {
        try (Reader reader = r.openAsReader()) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        } catch (Exception ex) {
            Fieldframe.LOG.warn("Fieldframe Ponder: cannot read {}: {}", file, ex.getMessage());
            return null;
        }
    }

    @Override
    protected void apply(Loaded loaded, ResourceManager rm, ProfilerFiller profiler) {
        current = loaded;
        Fieldframe.LOG.info("Fieldframe Ponder: {} scenes for {} items", loaded.scenes().size(), loaded.index().size());
        if (FieldframePonderPlugin.registered) {
            // Ponder already registered (without these files, or with older ones): register again.
            PonderIndex.reload();
        }
    }
}
