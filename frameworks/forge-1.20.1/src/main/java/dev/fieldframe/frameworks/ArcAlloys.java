package dev.fieldframe.frameworks;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.fieldframe.api.id.FfId;
import dev.fieldframe.api.resource.ItemVariant;
import dev.fieldframe.api.resource.TagLookup;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Predicate;

/**
 * Arc alloys: solid recipes only the Arc Furnace makes, from items in a Grand Frame's Orange storage. From data
 * ({@code data/<ns>/fieldframe_frameworks/arc_alloys/*.json}) and Java ({@link #register}); a data file with the same id
 * replaces a Java one, {@code {"remove": true}} removes it. Inputs are an item id or {@code #tag}; recipes whose result
 * item does not exist are skipped (Metallurgy can turn metals off).
 * <pre>{ "inputs": [{"item": "#c:ingots/iron", "count": 2}, {"item": "#minecraft:coals", "count": 1}],
 *   "result": {"item": "metallurgy:steel_ingot", "count": 2} }</pre>
 * Loader-independent (unit tested); {@link ArcAlloysListener} feeds it the data files.
 */
public final class ArcAlloys {
    private ArcAlloys() {
    }

    /** An item ({@code tag == false}) or an item tag ({@code tag == true}), and how many. */
    public record Part(FfId id, boolean tag, int count) {
        public Part {
            if (count < 1 || count > 64) {
                throw new IllegalArgumentException("count 1..64");
            }
        }

        public boolean matches(ItemVariant v, TagLookup tags) {
            return v != null && (tag ? tags.itemInTag(v, id) : v.id().equals(id));
        }

        String text() {
            return (tag ? "#" : "") + id;
        }
    }

    /** {@code result} is always an item, never a tag. */
    public record Alloy(FfId id, List<Part> inputs, Part result) {
        /** Input items one batch takes. */
        public int inputCount() {
            int n = 0;
            for (Part p : inputs) {
                n += p.count();
            }
            return n;
        }
    }

    private static final Map<FfId, Alloy> JAVA = new LinkedHashMap<>();
    private static volatile List<Alloy> effective = List.of();

    /** Adds or replaces an arc alloy from Java (call during mod construction). */
    public static synchronized void register(Alloy a) {
        JAVA.put(a.id(), a);
    }

    public static List<Alloy> all() {
        return effective;
    }

    /** Parses one data file's alloy (no remove handling). */
    static Alloy parse(FfId id, JsonObject j) {
        List<Part> inputs = new ArrayList<>();
        for (JsonElement el : j.getAsJsonArray("inputs")) {
            inputs.add(part(el.getAsJsonObject()));
        }
        if (inputs.isEmpty() || inputs.size() > 4) {
            throw new IllegalArgumentException("1 to 4 inputs");
        }
        Part result = part(j.getAsJsonObject("result"));
        if (result.tag()) {
            throw new IllegalArgumentException("the result must be an item, not a tag");
        }
        return new Alloy(id, List.copyOf(inputs), result);
    }

    private static Part part(JsonObject o) {
        String text = o.get("item").getAsString();
        boolean tag = text.startsWith("#");
        int count = o.has("count") ? o.get("count").getAsInt() : 1;
        return new Part(FfId.parse(tag ? text.substring(1) : text), tag, count);
    }

    /**
     * Loads the data files (id -> JSON text) over the Java alloys; keeps those whose result item exists. Returns the
     * problems (those files are skipped).
     */
    static synchronized List<String> load(Map<FfId, String> files, Predicate<FfId> itemExists) {
        Map<FfId, Alloy> out = new LinkedHashMap<>(JAVA);
        List<String> errors = new ArrayList<>();
        new TreeMap<>(files).forEach((id, text) -> {
            try {
                JsonObject j = JsonParser.parseString(text).getAsJsonObject();
                if (j.has("remove") && j.get("remove").getAsBoolean()) {
                    out.remove(id);
                } else {
                    out.put(id, parse(id, j));
                }
            } catch (RuntimeException ex) {
                errors.add(id + ": " + ex.getMessage());
            }
        });
        List<Alloy> valid = new ArrayList<>();
        for (Alloy a : out.values()) {
            boolean ok = itemExists.test(a.result().id());
            for (Part p : a.inputs()) {
                ok &= p.tag() || itemExists.test(p.id());
            }
            if (ok) {
                valid.add(a);
            }
        }
        effective = List.copyOf(valid);
        return errors;
    }

    /** The usable alloys as JSON, for clients; see {@link #acceptSync}. */
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
            for (JsonElement el : JsonParser.parseString(json).getAsJsonArray()) {
                JsonObject o = el.getAsJsonObject();
                out.add(parse(FfId.parse(o.get("id").getAsString()), o));
            }
            effective = List.copyOf(out);
        } catch (RuntimeException e) {
            // keep the old list
        }
    }

    private static JsonObject partJson(Part p) {
        JsonObject o = new JsonObject();
        o.addProperty("item", p.text());
        o.addProperty("count", p.count());
        return o;
    }
}
