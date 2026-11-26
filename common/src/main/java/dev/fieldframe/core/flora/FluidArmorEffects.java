package dev.fieldframe.core.flora;

import dev.fieldframe.api.data.FfData;
import dev.fieldframe.api.data.FfJson;
import dev.fieldframe.api.id.FfId;
import dev.fieldframe.api.process.MachineData;
import dev.fieldframe.api.resource.FluidVariant;
import dev.fieldframe.api.resource.TagLookup;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Fluid armor effects of any fluid, from datapacks: {@code data/<ns>/fieldframe/fluid_armor_effects/*.json}. These come
 * on top of the seven FLORA effect fluids and their pair matrix ({@link Flora}); any fluid listed here can be infused
 * into fluid armor. One file holds one entry or {@code {"effects": [...]}}, optionally with a file-wide
 * {@code requires_mod}:
 * <pre>
 * { "fluid": "minecraft:lava", "effect": "minecraft:fire_resistance" }
 * { "fluid": "minecraft:water", "effect": "minecraft:dolphins_grace", "min_mb": 4000 }
 * { "fluid": "#c:molten_silver", "effect": "minecraft:regeneration", "mb_per_level": 16000, "max_amplifier": 1 }
 * { "fluid": "metallurgy:platinum", "effect": "fieldframe:cleanse", "requires_mod": "metallurgy" }
 * { "fluid": "minecraft:lava", "remove": true }
 * </pre>
 * The worn amount is summed over all four pieces. An effect starts at {@code min_mb} (default 1000) with
 * {@code amplifier} (default 0) and gains a level per {@code mb_per_level} above that (0 = never) up to
 * {@code max_amplifier}. {@code fieldframe:cleanse} removes harmful effects instead of applying one. Entries are keyed
 * by fluid + effect: later files replace earlier ones, then Java and KubeJS contributors run. An exact fluid id wins
 * over tags. A file with an error is skipped and named in the log.
 */
public final class FluidArmorEffects {
    public static final FfId CLEANSE = FfId.ff("cleanse");
    public static final long DEFAULT_MIN_MB = 1000;

    public record Entry(String key, FfId effect, int amplifier, long minMb, long mbPerLevel, int maxAmplifier) {
        public boolean tag() {
            return key.startsWith("#");
        }

        /** The amplifier for this worn amount, or -1 when below {@link #minMb}. */
        public int amplifierFor(long mb) {
            if (mb < minMb) {
                return -1;
            }
            long a = amplifier;
            if (mbPerLevel > 0) {
                a += (mb - minMb) / mbPerLevel;
            }
            return (int) Math.min(a, Math.max(amplifier, maxAmplifier));
        }
    }

    /** One effect that applies now, with the fluid that gives it. */
    public record Active(FfId effect, int amplifier, FfId fluid) {
    }

    private static volatile List<Entry> entries = List.of();
    private static volatile Predicate<String> modLoaded = m -> true;

    private FluidArmorEffects() {
    }

    /** Set by the platform: entries whose {@code requires_mod} is absent are dropped at load. */
    public static void setModLoaded(Predicate<String> p) {
        modLoaded = p == null ? m -> true : p;
    }

    static final class Table implements MachineData.FluidArmorSink {
        final Map<String, Entry> byKey = new LinkedHashMap<>();

        void put(Entry e) {
            byKey.remove(e.key() + "|" + e.effect());
            byKey.put(e.key() + "|" + e.effect(), e);
        }

        @Override
        public void effect(String fluidOrTag, FfId effect, int amplifier, long minMb, long mbPerLevel, int maxAmplifier) {
            put(validated(fluidOrTag, effect, amplifier, minMb, mbPerLevel, maxAmplifier));
        }

        @Override
        public void remove(String fluidOrTag) {
            byKey.values().removeIf(e -> e.key().equals(fluidOrTag));
        }
    }

    public static List<String> load(Map<String, String> files) {
        return load(files, MachineData.fluidArmorContributors());
    }

    /** Parses the files (id → JSON text, sorted by id), runs the contributors and installs the result. Returns errors. */
    public static List<String> load(Map<String, String> files, List<Consumer<MachineData.FluidArmorSink>> contributors) {
        Table table = new Table();
        List<String> errors = new ArrayList<>();
        new TreeMap<>(files).forEach((id, json) -> {
            try {
                apply(table, FfJson.parse(json), true);
            } catch (RuntimeException ex) {
                errors.add(id + ": " + ex.getMessage());
            }
        });
        for (Consumer<MachineData.FluidArmorSink> c : contributors) {
            try {
                c.accept(table);
            } catch (RuntimeException ex) {
                errors.add("contributor: " + ex.getMessage());
            }
        }
        entries = List.copyOf(table.byKey.values());
        return errors;
    }

    private static void apply(Table table, FfData root, boolean checkMods) {
        String fileMod = root.getString("requires_mod", "");
        if (checkMods && !fileMod.isEmpty() && !modLoaded.test(fileMod)) {
            return;
        }
        List<FfData> list = new ArrayList<>();
        if (root.has("effects")) {
            root.getList("effects").forEach(o -> list.add((FfData) o));
        } else {
            list.add(root);
        }
        // Validate the whole file first, so a broken file changes nothing.
        Table staged = new Table();
        staged.byKey.putAll(table.byKey);
        for (FfData d : list) {
            String key = d.getString("fluid", "");
            String mod = d.getString("requires_mod", "");
            if (checkMods && !mod.isEmpty() && !modLoaded.test(mod)) {
                continue;
            }
            if (d.getBool("remove", false)) {
                staged.remove(key);
                continue;
            }
            FfId effect = FfId.tryParse(d.getString("effect", ""));
            if (effect == null) {
                throw new IllegalArgumentException("bad or missing effect for " + key);
            }
            int amp = d.getInt("amplifier", 0);
            staged.put(validated(key, effect, amp, d.getLong("min_mb", DEFAULT_MIN_MB), d.getLong("mb_per_level", 0),
                    d.getInt("max_amplifier", amp)));
        }
        table.byKey.clear();
        table.byKey.putAll(staged.byKey);
    }

    static Entry validated(String key, FfId effect, int amplifier, long minMb, long mbPerLevel, int maxAmplifier) {
        if (key == null || key.isEmpty() || FfId.tryParse(key.startsWith("#") ? key.substring(1) : key) == null) {
            throw new IllegalArgumentException("bad fluid " + key);
        }
        if (effect == null) {
            throw new IllegalArgumentException("missing effect for " + key);
        }
        if (amplifier < 0 || amplifier > 9 || maxAmplifier < 0 || maxAmplifier > 9) {
            throw new IllegalArgumentException("amplifier out of range (0..9) for " + key);
        }
        if (minMb < 1 || minMb > 1_000_000_000L || mbPerLevel < 0) {
            throw new IllegalArgumentException("min_mb/mb_per_level out of range for " + key);
        }
        return new Entry(key, effect, amplifier, minMb, mbPerLevel, Math.max(amplifier, maxAmplifier));
    }

    public static List<Entry> all() {
        return entries;
    }

    /** The entries for this fluid: its exact-id entries if it has any, else those of every tag it is in. */
    public static List<Entry> forFluid(FfId fluid, TagLookup tags) {
        List<Entry> exact = new ArrayList<>();
        List<Entry> tagged = new ArrayList<>();
        String id = fluid.toString();
        FluidVariant v = null;
        for (Entry e : entries) {
            if (!e.tag()) {
                if (e.key().equals(id)) {
                    exact.add(e);
                }
            } else if (exact.isEmpty()) {
                if (v == null) {
                    v = new FluidVariant.Simple(fluid, "");
                }
                if (tags.fluidInTag(v, FfId.parse(e.key().substring(1)))) {
                    tagged.add(e);
                }
            }
        }
        return exact.isEmpty() ? tagged : exact;
    }

    /** Whether fluid armor accepts this fluid: a FLORA effect fluid, or one with an entry here. */
    public static boolean accepts(FfId fluid, TagLookup tags) {
        return Flora.Fluid.byId(fluid) != null || !forFluid(fluid, tags).isEmpty();
    }

    /** The effects worn amounts give now (highest amplifier per effect). {@code worn}: fluid id → total mB. */
    public static List<Active> active(Map<FfId, Long> worn, TagLookup tags) {
        Map<FfId, Active> out = new LinkedHashMap<>();
        worn.forEach((fluid, mb) -> {
            if (mb == null || mb <= 0) {
                return;
            }
            for (Entry e : forFluid(fluid, tags)) {
                int a = e.amplifierFor(mb);
                Active cur = out.get(e.effect());
                if (a >= 0 && (cur == null || cur.amplifier() < a)) {
                    out.put(e.effect(), new Active(e.effect(), a, fluid));
                }
            }
        });
        return List.copyOf(out.values());
    }

    /** Total mB per fluid over the worn pieces (FLORA fluids included). */
    public static Map<FfId, Long> worn(List<Flora.Charge> pieces) {
        Map<FfId, Long> out = new LinkedHashMap<>();
        for (Flora.Charge c : pieces) {
            if (c != null) {
                c.all().forEach((id, mb) -> out.merge(id, mb, Long::sum));
            }
        }
        return out;
    }

    /** The table as one JSON document, for syncing to clients (JEI, tooltips). */
    public static String toJson() {
        List<FfData> list = new ArrayList<>();
        for (Entry e : entries) {
            list.add(new FfData().putString("fluid", e.key()).putString("effect", e.effect().toString()).putInt("amplifier", e.amplifier())
                    .putLong("min_mb", e.minMb()).putLong("mb_per_level", e.mbPerLevel()).putInt("max_amplifier", e.maxAmplifier()));
        }
        return FfJson.toJsonString(new FfData().putList("effects", list));
    }

    /** Replaces the table with one received from the server; bad input leaves it unchanged. */
    public static void acceptSync(String json) {
        try {
            Table t = new Table();
            apply(t, FfJson.parse(json), false);
            entries = List.copyOf(t.byKey.values());
        } catch (RuntimeException e) {
            // keep the old table
        }
    }
}
