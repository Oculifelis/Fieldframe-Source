package dev.fieldframe.api.manual;

import dev.fieldframe.api.data.FfData;
import dev.fieldframe.api.id.FfId;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Registry of manual page types and their data validators. Rendering is registered separately on the client
 * (platform client API); an entry whose page type has no renderer shows a descriptive fallback instead of
 * crashing. Addons register new types here and a renderer in their client setup.
 */
public final class ManualPageTypes {
    private ManualPageTypes() {
    }

    /** Returns validation problems for a page's data; empty list when valid. */
    public interface Validator extends Function<FfData, List<String>> {
    }

    private static final Map<FfId, Validator> TYPES = new LinkedHashMap<>();

    public static final FfId TEXT = FfId.ff("text");
    public static final FfId MODULE_SPEC = FfId.ff("module_spec");
    public static final FfId RECIPES = FfId.ff("recipes");
    public static final FfId FACE_MAP = FfId.ff("face_map");
    public static final FfId TABLE = FfId.ff("table");
    public static final FfId WARNING = FfId.ff("warning");
    public static final FfId TUTORIAL = FfId.ff("tutorial");
    public static final FfId STATUS_CODES = FfId.ff("status_codes");
    public static final FfId TEST_BENCH = FfId.ff("test_bench");
    public static final FfId BLUEPRINT = FfId.ff("blueprint");

    static {
        register(TEXT, d -> requireText(d, "text"));
        register(WARNING, d -> requireText(d, "text"));
        register(MODULE_SPEC, d -> requireId(d, "module"));
        register(RECIPES, d -> d.has("family") || d.has("module") ? List.of() : List.of("recipes page needs 'family' or 'module'"));
        register(FACE_MAP, d -> d.has("faces") ? List.of() : List.of("face_map needs 'faces'"));
        register(TABLE, d -> d.getList("rows").size() > 64 ? List.of("table has more than 64 rows") : List.of());
        register(TUTORIAL, ManualPageTypes::tutorial);
        register(STATUS_CODES, d -> List.of());
        register(TEST_BENCH, d -> d.has("scenario") ? List.of() : List.of("test_bench needs 'scenario'"));
        register(BLUEPRINT, d -> d.has("blueprint") ? List.of() : List.of("blueprint page needs 'blueprint'"));
    }

    /** Steps are strings or {text, advancement}; a step with an advancement is ticked once the reader has it. */
    private static List<String> tutorial(FfData d) {
        if (d.getList("steps").isEmpty()) {
            return List.of("tutorial needs 'steps'");
        }
        List<String> out = new java.util.ArrayList<>();
        for (Object o : d.getList("steps")) {
            if (o instanceof FfData s && s.has("advancement") && FfId.tryParse(s.getString("advancement", "")) == null) {
                out.add("tutorial step has an invalid advancement id '" + s.getString("advancement", "") + "'");
            }
        }
        return out;
    }

    private static List<String> requireText(FfData d, String key) {
        String t = d.getString(key, null);
        if (t == null) {
            return List.of("missing '" + key + "'");
        }
        return t.length() > ManualModel.MAX_TEXT ? List.of("text longer than " + ManualModel.MAX_TEXT) : List.of();
    }

    private static List<String> requireId(FfData d, String key) {
        return FfId.tryParse(d.getString(key, null)) == null ? List.of("missing or invalid '" + key + "'") : List.of();
    }

    public static synchronized void register(FfId type, Validator validator) {
        if (TYPES.containsKey(type)) {
            throw new IllegalArgumentException("Manual page type " + type + " already registered");
        }
        TYPES.put(type, validator);
    }

    public static synchronized boolean isKnown(FfId type) {
        return TYPES.containsKey(type);
    }

    public static synchronized List<String> validate(ManualModel.Page page) {
        Validator v = TYPES.get(page.type());
        if (v == null) {
            return List.of("unknown page type " + page.type() + " (fallback page will be shown)");
        }
        try {
            return new ArrayList<>(v.apply(page.data()));
        } catch (RuntimeException e) {
            return List.of("validator for " + page.type() + " failed: " + e.getMessage());
        }
    }

    public static synchronized Map<FfId, Validator> all() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(TYPES));
    }
}
