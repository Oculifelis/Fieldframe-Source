package dev.fieldframe.core.material;

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

/**
 * Temperatures of blocks and fluids in kelvin, read by heat generators (Thermocouple, Boiler, Condenser). Datapacks:
 * {@code data/<ns>/fieldframe/temperatures/*.json}, one entry or {@code {"temperatures": [...]}}:
 * <pre>
 * { "block": "minecraft:magma_block", "kelvin": 1000 }
 * { "block": "#minecraft:campfires", "lit": true, "kelvin": 600 }
 * { "fluid": "minecraft:water", "kelvin": 285 }
 * { "block": "minecraft:ice", "remove": true }
 * </pre>
 * {@code block}/{@code fluid} take an id or a {@code #tag}; {@code lit} entries apply only while the block is lit
 * (furnaces, campfires) and win over unlit ones. Later files override earlier ones for the same key; then Java and
 * KubeJS contributors ({@link MachineData#addTemperatureContributor}) run. A block or fluid not in the table uses the
 * fluid's own temperature (water 300, lava 1300, molten metals as their mod sets), else the ambient temperature of
 * the biome.
 */
public final class Temperatures {
    /** {@code key}: id or #tag; {@code fluid}: a fluid entry; {@code lit}: only while the block is lit. */
    public record Entry(String key, boolean fluid, boolean lit, int kelvin) {
        String mapKey() {
            return (fluid ? "fluid|" : lit ? "lit|" : "block|") + key;
        }

        boolean tag() {
            return key.startsWith("#");
        }
    }

    /** Lowest and highest temperatures the table accepts (absolute zero to well past molten iron). */
    public static final int MIN_K = 1;
    public static final int MAX_K = 10_000;

    private static volatile List<Entry> entries = List.of();

    private Temperatures() {
    }

    static final class Table implements MachineData.TemperatureSink {
        final Map<String, Entry> byKey = new LinkedHashMap<>();

        void put(Entry e) {
            byKey.remove(e.mapKey());
            byKey.put(e.mapKey(), e);
        }

        @Override
        public void block(String blockOrTag, int kelvin) {
            put(validated(blockOrTag, false, false, kelvin));
        }

        @Override
        public void litBlock(String blockOrTag, int kelvin) {
            put(validated(blockOrTag, false, true, kelvin));
        }

        @Override
        public void fluid(String fluidOrTag, int kelvin) {
            put(validated(fluidOrTag, true, false, kelvin));
        }

        @Override
        public void remove(String key) {
            byKey.remove("block|" + key);
            byKey.remove("lit|" + key);
            byKey.remove("fluid|" + key);
        }
    }

    static Entry validated(String key, boolean fluid, boolean lit, int kelvin) {
        if (key == null || FfId.tryParse(key.startsWith("#") ? key.substring(1) : key) == null) {
            throw new IllegalArgumentException("bad " + (fluid ? "fluid" : "block") + " '" + key + "'");
        }
        if (kelvin < MIN_K || kelvin > MAX_K) {
            throw new IllegalArgumentException(key + ": kelvin must be " + MIN_K + ".." + MAX_K);
        }
        return new Entry(key, fluid, lit, kelvin);
    }

    /** Parses the files (id to JSON text, sorted by id), runs the contributors and installs the result. Returns errors. */
    public static List<String> load(Map<String, String> files) {
        return load(files, MachineData.temperatureContributors());
    }

    public static List<String> load(Map<String, String> files, List<Consumer<MachineData.TemperatureSink>> contributors) {
        Table table = new Table();
        List<String> errors = new ArrayList<>();
        new TreeMap<>(files).forEach((id, json) -> {
            try {
                Table staged = new Table();
                staged.byKey.putAll(table.byKey);
                FfData root = FfJson.parse(json);
                if (root.has("temperatures")) {
                    for (Object o : root.getList("temperatures")) {
                        apply(staged, (FfData) o);
                    }
                } else {
                    apply(staged, root);
                }
                table.byKey.clear();
                table.byKey.putAll(staged.byKey);
            } catch (RuntimeException ex) {
                errors.add(id + ": " + ex.getMessage());
            }
        });
        for (Consumer<MachineData.TemperatureSink> c : contributors) {
            try {
                c.accept(table);
            } catch (RuntimeException ex) {
                errors.add("contributor: " + ex.getMessage());
            }
        }
        entries = List.copyOf(table.byKey.values());
        return errors;
    }

    private static void apply(Table t, FfData d) {
        boolean fluid = d.has("fluid");
        String key = d.getString(fluid ? "fluid" : "block", "");
        if (d.getBool("remove", false)) {
            t.remove(key);
            return;
        }
        Entry e = validated(key, fluid, !fluid && d.getBool("lit", false), d.getInt("kelvin", -1));
        t.put(e);
    }

    /** The effective table (read-only). */
    public static List<Entry> entries() {
        return entries;
    }

    /** Replaces the whole table (server sync on clients, and tests). */
    public static void replace(List<Entry> list) {
        entries = List.copyOf(list);
    }

    /**
     * The temperature of what is at a position. {@code block} is the block id ({@code null} for none), {@code lit}
     * whether it is lit, {@code fluid} the fluid there or {@code null}, {@code fluidOwn} that fluid's own temperature
     * (or a value below 1 if unknown) and {@code ambient} the biome's temperature, used when nothing else applies.
     */
    public static int resolve(FfId block, boolean lit, FluidVariant fluid, int fluidOwn, int ambient, TagLookup tags) {
        List<Entry> table = entries;
        if (block != null) {
            if (lit) {
                Integer k = match(table, block, null, true, tags);
                if (k != null) {
                    return k;
                }
            }
            Integer k = match(table, block, null, false, tags);
            if (k != null) {
                return k;
            }
        }
        if (fluid != null) {
            Integer k = match(table, null, fluid, false, tags);
            if (k != null) {
                return k;
            }
            if (fluidOwn >= MIN_K) {
                return Math.min(MAX_K, fluidOwn);
            }
        }
        return ambient;
    }

    private static Integer match(List<Entry> table, FfId block, FluidVariant fluid, boolean lit, TagLookup tags) {
        Integer tagged = null;
        for (Entry e : table) {
            if (e.fluid() != (fluid != null) || (block != null && e.lit() != lit)) {
                continue;
            }
            if (e.tag()) {
                FfId tag = FfId.parse(e.key().substring(1));
                if (tagged == null && (fluid != null ? tags.fluidInTag(fluid, tag) : tags.blockInTag(block, tag))) {
                    tagged = e.kelvin();
                }
            } else if ((fluid != null ? fluid.id().toString() : block.toString()).equals(e.key())) {
                return e.kelvin();
            }
        }
        return tagged;
    }

    /** Ambient temperature for a biome's base temperature (vanilla scale: plains 0.8, desert 2.0, snowy plains 0.0). */
    public static int ambient(float biomeTemperature) {
        return Math.max(200, Math.min(330, Math.round(273 + 20 * biomeTemperature)));
    }
}
