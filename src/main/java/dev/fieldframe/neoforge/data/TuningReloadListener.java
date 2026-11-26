package dev.fieldframe.neoforge.data;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.fieldframe.api.FieldframeRegistry;
import dev.fieldframe.api.id.FfId;
import dev.fieldframe.neoforge.Fieldframe;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Loads {@code data/<ns>/fieldframe/tuning/*.json}, adds script overrides, validates the whole batch and
 * swaps it in atomically. Format:
 * <pre>{ "module": "fieldframe:fuel_engine", "tunables": { "rf_per_tick": 60 }, "disabled": false }</pre>
 * Precedence: built-in defaults &lt; datapacks (pack order; later files override earlier) &lt; scripts.
 */
public final class TuningReloadListener extends SimpleJsonResourceReloadListener {
    private static final Gson GSON = new Gson();
    private static final List<Consumer<Collector>> SCRIPT_CONTRIBUTORS = new ArrayList<>();

    /** Receives overrides from scripts during a reload; contributions are discarded after each reload. */
    public interface Collector {
        void tune(FfId module, String key, double value, String source);

        void disable(FfId module);

        void enable(FfId module);
    }

    public static synchronized void addScriptContributor(Consumer<Collector> c) {
        SCRIPT_CONTRIBUTORS.add(c);
    }

    public TuningReloadListener() {
        super(GSON, "fieldframe/tuning");
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> files, ResourceManager rm, ProfilerFiller profiler) {
        List<FieldframeRegistry.Override> overrides = new ArrayList<>();
        Set<FfId> disabled = new HashSet<>();
        List<String> parseErrors = new ArrayList<>();
        files.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e -> {
            try {
                JsonObject o = e.getValue().getAsJsonObject();
                FfId module = FfId.parse(o.get("module").getAsString());
                if (o.has("tunables")) {
                    for (var t : o.getAsJsonObject("tunables").entrySet()) {
                        overrides.add(new FieldframeRegistry.Override(module, t.getKey(), t.getValue().getAsDouble(), e.getKey().toString()));
                    }
                }
                if (o.has("disabled")) {
                    if (o.get("disabled").getAsBoolean()) {
                        disabled.add(module);
                    } else {
                        disabled.remove(module);
                    }
                }
            } catch (RuntimeException ex) {
                parseErrors.add(e.getKey() + ": " + ex.getMessage());
            }
        });
        Collector collector = new Collector() {
            @Override
            public void tune(FfId module, String key, double value, String source) {
                overrides.add(new FieldframeRegistry.Override(module, key, value, source));
            }

            @Override
            public void disable(FfId module) {
                disabled.add(module);
            }

            @Override
            public void enable(FfId module) {
                disabled.remove(module);
            }
        };
        // Server config: [machines] disabled (config/fieldframe-common.toml).
        disabled.addAll(dev.fieldframe.neoforge.FfConfig.disabledModules());
        List<Consumer<Collector>> contributors;
        synchronized (TuningReloadListener.class) {
            contributors = List.copyOf(SCRIPT_CONTRIBUTORS);
        }
        for (Consumer<Collector> c : contributors) {
            try {
                c.accept(collector);
            } catch (RuntimeException ex) {
                parseErrors.add("script: " + ex.getMessage());
            }
        }
        if (!parseErrors.isEmpty()) {
            parseErrors.forEach(err -> Fieldframe.LOG.error("Fieldframe tuning: {}", err));
            Fieldframe.LOG.error("Fieldframe tuning batch rejected; keeping previous effective values");
            return;
        }
        // Later entries for the same key win: collapse in order.
        java.util.LinkedHashMap<String, FieldframeRegistry.Override> last = new java.util.LinkedHashMap<>();
        for (FieldframeRegistry.Override o : overrides) {
            last.put(o.module() + "#" + o.key(), o);
        }
        List<String> errors = FieldframeRegistry.applyData(new ArrayList<>(last.values()), disabled);
        if (!errors.isEmpty()) {
            errors.forEach(err -> Fieldframe.LOG.error("Fieldframe tuning: {}", err));
            Fieldframe.LOG.error("Fieldframe tuning batch rejected; keeping previous effective values");
        } else {
            Fieldframe.LOG.info("Fieldframe tuning applied: {} overrides, {} disabled modules (generation {})",
                    last.size(), disabled.size(), FieldframeRegistry.dataState().generation());
        }
    }
}
