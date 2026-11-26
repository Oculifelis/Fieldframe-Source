package dev.fieldframe.core.flora;

import dev.fieldframe.api.data.FfData;
import dev.fieldframe.api.data.FfJson;
import dev.fieldframe.api.id.FfId;
import dev.fieldframe.core.flora.Flora.Effect;
import dev.fieldframe.core.flora.Flora.Fluid;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Which pair of the seven FLORA effect fluids gives which effect, from datapacks:
 * {@code data/<ns>/fieldframe/fluid_armor_pairs/*.json}. Fieldframe ships the original 28 in
 * {@code data/fieldframe/fieldframe/fluid_armor_pairs/flora.json}. One file holds one entry or {@code {"pairs": [...]}}:
 * <pre>
 * { "fluids": ["fieldframe:liquid_coal", "fieldframe:liquid_coal"], "effect": "fireball" }
 * { "fluids": ["fieldframe:blazing_fluid", "fieldframe:arcane_fluid"], "effect": "speed", "strength": 0.5 }
 * { "fluids": ["fieldframe:liquid_redstone", "fieldframe:liquid_glowstone"], "remove": true }
 * { "effect": "self_combustion", "remove": true }
 * </pre>
 * {@code effect} is one of the 28 built-in effect keys ({@link #KEYS}); the same effect may sit on several pairs, and
 * the strongest one counts. {@code strength} (default 1, 0..100) scales the pair's matrix value, so 0.5 needs four times
 * the fluid for the same result. {@code negative} (default: the effect's own) only changes how it is shown. Entries are
 * keyed by pair: a later file replaces an earlier file's entry for that pair. A file with an error is skipped and named
 * in the log. Fluids may be given without the {@code fieldframe:} namespace.
 */
public final class FloraPairs {
    /** One pair entry: the effect it gives and its strength multiplier. */
    public record Entry(Effect effect, float strength) {
    }

    /** The 28 built-in effect keys. */
    public static final Set<String> KEYS = Flora.EFFECTS.stream().map(Effect::key).collect(Collectors.toUnmodifiableSet());

    private static final List<Entry> DEFAULTS = Flora.EFFECTS.stream().map(e -> new Entry(e, 1f)).toList();
    private static volatile List<Entry> entries = DEFAULTS;
    private static volatile Map<String, List<Entry>> byKey = index(DEFAULTS);

    private FloraPairs() {
    }

    /** Current entries, in load order. Built-in defaults until a datapack load or server sync replaces them. */
    public static List<Entry> all() {
        return entries;
    }

    /** Current pair effects (for tooltips and recipe viewers). */
    public static List<Effect> effects() {
        return entries.stream().map(Entry::effect).toList();
    }

    /** Puts the built-in 28 back (tests). */
    public static void reset() {
        install(DEFAULTS);
    }

    /** The intensity of an effect for this matrix: the strongest of its pairs, each scaled by its strength; 0 = off. */
    public static float intensity(float[][] mx, String key) {
        List<Entry> list = byKey.get(key);
        if (list == null) {
            return 0;
        }
        float best = 0;
        for (Entry e : list) {
            best = Math.max(best, Flora.m(mx, e.effect().a(), e.effect().b()) * e.strength());
        }
        return best;
    }

    /** Parses the files (id to JSON text, applied in id order) and installs the result. Returns errors. */
    public static List<String> load(Map<String, String> files) {
        Map<String, Entry> table = new LinkedHashMap<>();
        List<String> errors = new ArrayList<>();
        new TreeMap<>(files).forEach((id, json) -> {
            try {
                apply(table, FfJson.parse(json));
            } catch (RuntimeException ex) {
                errors.add(id + ": " + ex.getMessage());
            }
        });
        install(List.copyOf(table.values()));
        return errors;
    }

    private static void apply(Map<String, Entry> table, FfData root) {
        List<FfData> list = new ArrayList<>();
        if (root.has("pairs")) {
            for (Object o : root.getList("pairs")) {
                if (!(o instanceof FfData d)) {
                    throw new IllegalArgumentException("pairs must hold objects");
                }
                list.add(d);
            }
        } else {
            list.add(root);
        }
        // Validate the whole file first, so a broken file changes nothing.
        Map<String, Entry> staged = new LinkedHashMap<>(table);
        for (FfData d : list) {
            String effect = d.getString("effect", "");
            boolean remove = d.getBool("remove", false);
            if (!d.has("fluids")) {
                if (remove && KEYS.contains(effect)) {
                    staged.values().removeIf(e -> e.effect().key().equals(effect));
                    continue;
                }
                throw new IllegalArgumentException("missing fluids");
            }
            List<Object> fl = d.getList("fluids");
            if (fl.size() != 2) {
                throw new IllegalArgumentException("fluids must name exactly two fluids");
            }
            Fluid a = fluid(String.valueOf(fl.get(0)));
            Fluid b = fluid(String.valueOf(fl.get(1)));
            if (a.ordinal() > b.ordinal()) {
                Fluid t = a;
                a = b;
                b = t;
            }
            String pair = a.path + "+" + b.path;
            if (remove) {
                staged.remove(pair);
                continue;
            }
            if (!KEYS.contains(effect)) {
                throw new IllegalArgumentException("unknown effect '" + effect + "' for " + pair);
            }
            double strength = d.getDouble("strength", 1);
            if (!(strength >= 0 && strength <= 100)) {
                throw new IllegalArgumentException("strength out of range (0..100) for " + pair);
            }
            boolean negative = d.getBool("negative", builtinNegative(effect));
            staged.remove(pair);
            staged.put(pair, new Entry(new Effect(a, b, effect, negative), (float) strength));
        }
        table.clear();
        table.putAll(staged);
    }

    private static Fluid fluid(String s) {
        FfId id = FfId.tryParse(s.indexOf(':') < 0 ? "fieldframe:" + s : s);
        Fluid f = Fluid.byId(id);
        if (f == null) {
            throw new IllegalArgumentException("not a FLORA effect fluid: " + s);
        }
        return f;
    }

    private static boolean builtinNegative(String key) {
        for (Effect e : Flora.EFFECTS) {
            if (e.key().equals(key)) {
                return e.negative();
            }
        }
        return false;
    }

    private static void install(List<Entry> list) {
        entries = list;
        byKey = index(list);
    }

    private static Map<String, List<Entry>> index(List<Entry> list) {
        Map<String, List<Entry>> m = new LinkedHashMap<>();
        for (Entry e : list) {
            m.computeIfAbsent(e.effect().key(), k -> new ArrayList<>()).add(e);
        }
        m.replaceAll((k, v) -> List.copyOf(v));
        return Map.copyOf(m);
    }

    /** The table as one JSON document, for syncing to clients. */
    public static String toJson() {
        List<FfData> list = new ArrayList<>();
        for (Entry e : entries) {
            list.add(new FfData().putList("fluids", List.of(e.effect().a().id().toString(), e.effect().b().id().toString()))
                    .putString("effect", e.effect().key()).putDouble("strength", e.strength())
                    .putBool("negative", e.effect().negative()));
        }
        return FfJson.toJsonString(new FfData().putList("pairs", list));
    }

    /** Replaces the table with one received from the server; bad input leaves it unchanged. */
    public static void acceptSync(String json) {
        try {
            Map<String, Entry> t = new LinkedHashMap<>();
            apply(t, FfJson.parse(json));
            install(List.copyOf(t.values()));
        } catch (RuntimeException e) {
            // keep the old table
        }
    }
}
