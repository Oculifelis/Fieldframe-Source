package dev.fieldframe.core.material;

import dev.fieldframe.api.data.FfData;
import dev.fieldframe.api.data.FfJson;
import dev.fieldframe.api.id.FfId;
import dev.fieldframe.api.process.MachineData;
import dev.fieldframe.api.resource.FluidVariant;
import dev.fieldframe.api.resource.ItemVariant;
import dev.fieldframe.api.resource.TagLookup;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;

/**
 * Engine fuels from datapacks: {@code data/<ns>/fieldframe/fuels/*.json}. One file holds one entry or
 * {@code {"fuels": [...]}}:
 * <pre>
 * { "engine": "fieldframe:fluid_engine", "fluid": "minecraft:lava", "rf_per_mb": 60 }
 * { "engine": "fieldframe:gem_engine", "item": "#c:gems/diamond", "rf": 400000 }
 * { "engine": "fieldframe:fluid_engine", "fluid": "minecraft:lava", "remove": true }
 * { "engine": "fieldframe:gem_engine", "clear": true }
 * </pre>
 * {@code fluid}/{@code item} take an id or a {@code #tag}. Later files override earlier ones for the same engine and
 * key; then Java and KubeJS contributors ({@link MachineData}) run. A reload swaps the whole table atomically; a file
 * with an error is skipped and named in the log.
 */
public final class Fuels {
    public record Entry(FfId engine, String key, boolean fluid, long rf) {
        boolean tag() {
            return key.startsWith("#");
        }
    }

    private static volatile List<Entry> entries = List.of();

    private Fuels() {
    }

    /** The table being built during one load: data files, then contributors. */
    static final class Table implements MachineData.FuelSink {
        final Map<String, Entry> byKey = new LinkedHashMap<>();

        void put(Entry e) {
            byKey.remove(e.engine() + "|" + e.key());
            byKey.put(e.engine() + "|" + e.key(), e);
        }

        @Override
        public void fluid(FfId engine, String fluidOrTag, long rfPerMb) {
            put(validated(engine, fluidOrTag, true, rfPerMb));
        }

        @Override
        public void item(FfId engine, String itemOrTag, long rf) {
            put(validated(engine, itemOrTag, false, rf));
        }

        @Override
        public void remove(FfId engine, String key) {
            byKey.remove(engine + "|" + key);
        }

        @Override
        public void clear(FfId engine) {
            byKey.values().removeIf(e -> e.engine().equals(engine));
        }
    }

    /** Parses the files (id → JSON text, sorted by id), runs the contributors and installs the result. Returns errors. */
    public static List<String> load(Map<String, String> files) {
        return load(files, MachineData.fuelContributors());
    }

    public static List<String> load(Map<String, String> files, List<Consumer<MachineData.FuelSink>> contributors) {
        Table table = new Table();
        List<String> errors = new ArrayList<>();
        new TreeMap<>(files).forEach((id, json) -> {
            try {
                apply(table, FfJson.parse(json));
            } catch (RuntimeException ex) {
                errors.add(id + ": " + ex.getMessage());
            }
        });
        for (Consumer<MachineData.FuelSink> c : contributors) {
            try {
                c.accept(table);
            } catch (RuntimeException ex) {
                errors.add("contributor: " + ex.getMessage());
            }
        }
        install(List.copyOf(table.byKey.values()));
        return errors;
    }

    private static void apply(Table table, FfData root) {
        List<FfData> list = new ArrayList<>();
        if (root.has("fuels")) {
            root.getList("fuels").forEach(o -> list.add((FfData) o));
        } else {
            list.add(root);
        }
        // Validate the whole file first, so a broken file changes nothing.
        Table staged = new Table();
        staged.byKey.putAll(table.byKey);
        for (FfData d : list) {
            FfId engine = FfId.parse(d.getString("engine", ""));
            if (d.getBool("clear", false)) {
                staged.clear(engine);
                continue;
            }
            boolean fluid = d.has("fluid");
            String key = fluid ? d.getString("fluid", "") : d.getString("item", "");
            if (d.getBool("remove", false)) {
                staged.remove(engine, key);
                continue;
            }
            staged.put(validated(engine, key, fluid, fluid ? d.getLong("rf_per_mb", 0) : d.getLong("rf", 0)));
        }
        table.byKey.clear();
        table.byKey.putAll(staged.byKey);
    }

    static Entry validated(FfId engine, String key, boolean fluid, long rf) {
        if (key == null || key.isEmpty()) {
            throw new IllegalArgumentException("fuel needs \"fluid\" or \"item\"");
        }
        if (FfId.tryParse(key.startsWith("#") ? key.substring(1) : key) == null) {
            throw new IllegalArgumentException("bad id " + key);
        }
        if (rf <= 0 || rf > 100_000_000L) {
            throw new IllegalArgumentException("rf out of range for " + key);
        }
        return new Entry(engine, key, fluid, rf);
    }

    private static void install(List<Entry> list) {
        entries = list;
        List<MachineData.Fuel> pub = new ArrayList<>();
        for (Entry e : list) {
            pub.add(new MachineData.Fuel(e.engine(), e.key(), e.fluid(), e.rf()));
        }
        MachineData.publish(pub, null);
    }

    public static List<Entry> all() {
        return entries;
    }

    /** The current table as one JSON document ({@code {"fuels": [...]}}), for syncing to clients (JEI, manual). */
    public static String toJson() {
        List<FfData> list = new ArrayList<>();
        for (Entry e : entries) {
            FfData d = new FfData().putString("engine", e.engine().toString());
            if (e.fluid()) {
                d.putString("fluid", e.key()).putLong("rf_per_mb", e.rf());
            } else {
                d.putString("item", e.key()).putLong("rf", e.rf());
            }
            list.add(d);
        }
        return FfJson.toJsonString(new FfData().putList("fuels", list));
    }

    /** Replaces the table with one received from the server (see {@link #toJson}); bad input leaves it unchanged. */
    public static void acceptSync(String json) {
        try {
            Table t = new Table();
            apply(t, FfJson.parse(json));
            install(List.copyOf(t.byKey.values()));
        } catch (RuntimeException e) {
            // keep the old table
        }
    }

    /** RF per mB of this fluid for the engine, 0 when it is not a fuel there. Exact ids win over tags. */
    public static long fluidValue(FfId engine, FluidVariant f, TagLookup tags) {
        long tagged = 0;
        for (Entry e : entries) {
            if (!e.fluid() || !e.engine().equals(engine)) {
                continue;
            }
            if (!e.tag() && e.key().equals(f.id().toString())) {
                return e.rf();
            }
            if (e.tag() && tagged == 0 && tags.fluidInTag(f, FfId.parse(e.key().substring(1)))) {
                tagged = e.rf();
            }
        }
        return tagged;
    }

    /** RF for one item in this engine, 0 when it is not a fuel there. Exact ids win over tags. */
    public static long itemValue(FfId engine, ItemVariant v, TagLookup tags) {
        long tagged = 0;
        for (Entry e : entries) {
            if (e.fluid() || !e.engine().equals(engine)) {
                continue;
            }
            if (!e.tag() && e.key().equals(v.id().toString())) {
                return e.rf();
            }
            if (e.tag() && tagged == 0 && tags.itemInTag(v, FfId.parse(e.key().substring(1)))) {
                tagged = e.rf();
            }
        }
        return tagged;
    }
}
