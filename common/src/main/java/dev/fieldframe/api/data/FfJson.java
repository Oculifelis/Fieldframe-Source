package dev.fieldframe.api.data;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.google.gson.stream.JsonReader;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Bounded JSON &lt;-&gt; {@link FfData} conversion. Used for blueprints, controller exports and data-driven
 * definitions. Rejects oversized input, excessive nesting and non-finite numbers.
 */
public final class FfJson {
    public static final int DEFAULT_MAX_CHARS = 256 * 1024;
    public static final int MAX_ELEMENTS = 20_000;

    private FfJson() {
    }

    public static final class LimitException extends IllegalArgumentException {
        public LimitException(String msg) {
            super(msg);
        }
    }

    public static FfData parse(String json) {
        return parse(json, DEFAULT_MAX_CHARS, FfData.MAX_DEPTH);
    }

    public static FfData parse(String json, int maxChars, int maxDepth) {
        if (json.length() > maxChars) {
            throw new LimitException("Input is " + json.length() + " characters; limit is " + maxChars);
        }
        JsonElement el;
        try {
            JsonReader reader = new JsonReader(new StringReader(json));
            el = JsonParser.parseReader(reader);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Malformed JSON: " + e.getMessage());
        }
        if (!el.isJsonObject()) {
            throw new IllegalArgumentException("Top-level JSON value must be an object");
        }
        int[] budget = {MAX_ELEMENTS};
        return fromJson(el.getAsJsonObject(), 1, maxDepth, budget);
    }

    public static FfData fromJson(JsonObject obj) {
        return fromJson(obj, 1, FfData.MAX_DEPTH, new int[]{MAX_ELEMENTS});
    }

    private static FfData fromJson(JsonObject obj, int depth, int maxDepth, int[] budget) {
        if (depth > maxDepth) {
            throw new LimitException("Nesting deeper than " + maxDepth);
        }
        FfData d = new FfData();
        for (Map.Entry<String, JsonElement> e : obj.entrySet()) {
            if (--budget[0] < 0) {
                throw new LimitException("Too many elements");
            }
            if (e.getKey().length() > 256) {
                throw new LimitException("Key too long");
            }
            Object v = convert(e.getValue(), depth, maxDepth, budget);
            if (v != null) {
                d.putAny(e.getKey(), v);
            }
        }
        return d;
    }

    private static Object convert(JsonElement el, int depth, int maxDepth, int[] budget) {
        if (el == null || el.isJsonNull()) {
            return null;
        }
        if (el.isJsonObject()) {
            return fromJson(el.getAsJsonObject(), depth + 1, maxDepth, budget);
        }
        if (el.isJsonArray()) {
            if (depth + 1 > maxDepth) {
                throw new LimitException("Nesting deeper than " + maxDepth);
            }
            List<Object> list = new ArrayList<>();
            for (JsonElement c : el.getAsJsonArray()) {
                if (--budget[0] < 0) {
                    throw new LimitException("Too many elements");
                }
                Object v = convert(c, depth + 1, maxDepth, budget);
                if (v != null) {
                    list.add(v);
                }
            }
            return list;
        }
        JsonPrimitive p = el.getAsJsonPrimitive();
        if (p.isBoolean()) {
            return p.getAsBoolean();
        }
        if (p.isNumber()) {
            String raw = p.getAsString();
            if (raw.contains(".") || raw.contains("e") || raw.contains("E")) {
                double dv = p.getAsDouble();
                if (!Double.isFinite(dv)) {
                    throw new IllegalArgumentException("Non-finite number");
                }
                return dv;
            }
            try {
                return Long.parseLong(raw);
            } catch (NumberFormatException ex) {
                throw new IllegalArgumentException("Integer out of 64-bit range: " + raw);
            }
        }
        String s = p.getAsString();
        if (s.length() > FfData.MAX_STRING) {
            throw new LimitException("String too long");
        }
        return s;
    }

    public static JsonObject toJson(FfData d) {
        JsonObject o = new JsonObject();
        for (Map.Entry<String, Object> e : d.asMap().entrySet()) {
            o.add(e.getKey(), toJsonValue(e.getValue()));
        }
        return o;
    }

    private static JsonElement toJsonValue(Object v) {
        if (v instanceof FfData d) {
            return toJson(d);
        }
        if (v instanceof List<?> l) {
            JsonArray a = new JsonArray();
            for (Object o : l) {
                a.add(toJsonValue(o));
            }
            return a;
        }
        if (v instanceof Boolean b) {
            return new JsonPrimitive(b);
        }
        if (v instanceof Number n) {
            return new JsonPrimitive(n);
        }
        return new JsonPrimitive(String.valueOf(v));
    }

    public static String toJsonString(FfData d) {
        return toJson(d).toString();
    }
}
