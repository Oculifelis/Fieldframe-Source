package dev.fieldframe.frameworks;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.fieldframe.api.id.FfId;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Liquid alloys for the Foundry: molten metals in the pool that are present in a recipe's ratio combine into the
 * alloy. From data ({@code data/<ns>/fieldframe_frameworks/alloys/*.json}) and Java ({@link #register}); a data
 * file with the same id replaces a Java one, {@code {"remove": true}} removes it. Recipes naming a fluid that does not
 * exist are skipped (Metallurgy can turn metals off).
 * <pre>{ "inputs": [{"fluid": "metallurgy:copper", "amount": 432}, {"fluid": "metallurgy:tin", "amount": 144}],
 *   "result": {"fluid": "metallurgy:bronze", "amount": 576} }</pre>
 */
public final class FoundryAlloys {
    private FoundryAlloys() {
    }

    public record Part(FfId fluid, long amount) {
    }

    public record Alloy(FfId id, List<Part> inputs, Part result) {
    }

    private static final Map<FfId, Alloy> JAVA = new LinkedHashMap<>();
    private static volatile List<Alloy> effective = List.of();

    /** Adds or replaces a liquid alloy from Java (call during mod construction). */
    public static synchronized void register(Alloy a) {
        JAVA.put(a.id(), a);
    }

    public static List<Alloy> all() {
        return effective;
    }

    /** The usable alloys as JSON, for clients (JEI); see {@link #acceptSync}. */
    static String toJson() {
        JsonArray arr = new JsonArray();
        for (Alloy a : effective) {
            JsonObject o = new JsonObject();
            o.addProperty("id", a.id().toString());
            JsonArray in = new JsonArray();
            a.inputs().forEach(p -> in.add(partJson(p)));
            o.add("inputs", in);
            o.add("result", partJson(a.result()));
            arr.add(o);
        }
        return arr.toString();
    }

    /** Replaces the alloys with the server's (see {@link #toJson}); bad input leaves them unchanged. */
    static void acceptSync(String json) {
        try {
            List<Alloy> out = new ArrayList<>();
            for (JsonElement el : com.google.gson.JsonParser.parseString(json).getAsJsonArray()) {
                JsonObject o = el.getAsJsonObject();
                List<Part> inputs = new ArrayList<>();
                for (JsonElement p : o.getAsJsonArray("inputs")) {
                    inputs.add(part(p.getAsJsonObject()));
                }
                out.add(new Alloy(FfId.parse(o.get("id").getAsString()), List.copyOf(inputs), part(o.getAsJsonObject("result"))));
            }
            effective = List.copyOf(out);
        } catch (RuntimeException e) {
            // keep the old list
        }
    }

    private static JsonObject partJson(Part p) {
        JsonObject o = new JsonObject();
        o.addProperty("fluid", p.fluid().toString());
        o.addProperty("amount", p.amount());
        return o;
    }

    static synchronized void apply(Map<FfId, JsonObject> files) {
        Map<FfId, Alloy> out = new LinkedHashMap<>(JAVA);
        for (Map.Entry<FfId, JsonObject> e : files.entrySet()) {
            JsonObject j = e.getValue();
            if (j.has("remove") && j.get("remove").getAsBoolean()) {
                out.remove(e.getKey());
                continue;
            }
            try {
                List<Part> inputs = new ArrayList<>();
                JsonArray arr = j.getAsJsonArray("inputs");
                for (JsonElement el : arr) {
                    inputs.add(part(el.getAsJsonObject()));
                }
                if (inputs.isEmpty() || inputs.size() > 4) {
                    throw new IllegalArgumentException("1 to 4 inputs");
                }
                out.put(e.getKey(), new Alloy(e.getKey(), List.copyOf(inputs), part(j.getAsJsonObject("result"))));
            } catch (RuntimeException ex) {
                Frameworks.LOG.error("Foundry alloy {}: {} (skipped)", e.getKey(), ex.getMessage());
            }
        }
        List<Alloy> valid = new ArrayList<>();
        for (Alloy a : out.values()) {
            boolean ok = exists(a.result().fluid());
            for (Part p : a.inputs()) {
                ok &= exists(p.fluid());
            }
            if (ok) {
                valid.add(a);
            }
        }
        effective = List.copyOf(valid);
        Frameworks.LOG.info("Foundry alloys: {} usable of {}", valid.size(), out.size());
    }

    private static Part part(JsonObject o) {
        FfId fluid = FfId.parse(o.get("fluid").getAsString());
        long amount = o.get("amount").getAsLong();
        if (amount < 1 || amount > 64_000) {
            throw new IllegalArgumentException("amount 1..64000");
        }
        return new Part(fluid, amount);
    }

    private static boolean exists(FfId fluid) {
        return BuiltInRegistries.FLUID.containsKey(new net.minecraft.resources.ResourceLocation(fluid.namespace(), fluid.path()));
    }

    /** Loads the data files on every data reload. */
    static final class Listener extends SimpleJsonResourceReloadListener {
        Listener() {
            super(new com.google.gson.Gson(), "fieldframe_frameworks/alloys");
        }

        @Override
        protected void apply(Map<ResourceLocation, JsonElement> files, ResourceManager rm, ProfilerFiller profiler) {
            Map<FfId, JsonObject> parsed = new TreeMap<>((a, b) -> a.toString().compareTo(b.toString()));
            files.forEach((id, json) -> {
                if (json.isJsonObject()) {
                    parsed.put(FfId.of(id.getNamespace(), id.getPath()), json.getAsJsonObject());
                }
            });
            FoundryAlloys.apply(parsed);
        }
    }
}
