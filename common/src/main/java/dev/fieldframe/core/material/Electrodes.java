package dev.fieldframe.core.material;

import dev.fieldframe.api.data.FfData;
import dev.fieldframe.api.data.FfJson;
import dev.fieldframe.api.id.FfId;
import dev.fieldframe.api.process.MachineData;
import dev.fieldframe.api.resource.ItemVariant;
import dev.fieldframe.api.resource.TagLookup;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;

/**
 * Electrode potentials in volts for the Galvanic Cell: the lower metal is the anode (it is used up), the higher the
 * cathode. Real metals use their standard reduction potentials; Metallurgy's fantasy metals get invented ones.
 * Datapacks: {@code data/<ns>/fieldframe/electrodes/*.json}, one entry or {@code {"electrodes": [...]}}:
 * <pre>
 * { "item": "#c:ingots/zinc", "volts": -0.76 }
 * { "item": "minecraft:gold_ingot", "volts": 1.50 }
 * { "item": "#c:ingots/tin", "remove": true }
 * </pre>
 * Later files override earlier ones for the same key; then Java and KubeJS contributors
 * ({@link MachineData#addElectrodeContributor}) run. An item id entry wins over a tag entry.
 */
public final class Electrodes {
    public record Entry(String key, double volts) {
        boolean tag() {
            return key.startsWith("#");
        }
    }

    public static final double MIN_V = -5;
    public static final double MAX_V = 5;

    private static volatile List<Entry> entries = List.of();

    private Electrodes() {
    }

    static final class Table implements MachineData.ElectrodeSink {
        final Map<String, Entry> byKey = new LinkedHashMap<>();

        @Override
        public void item(String itemOrTag, double volts) {
            if (itemOrTag == null || FfId.tryParse(itemOrTag.startsWith("#") ? itemOrTag.substring(1) : itemOrTag) == null) {
                throw new IllegalArgumentException("bad item '" + itemOrTag + "'");
            }
            if (!(volts >= MIN_V && volts <= MAX_V)) {
                throw new IllegalArgumentException(itemOrTag + ": volts must be " + MIN_V + ".." + MAX_V);
            }
            byKey.remove(itemOrTag);
            byKey.put(itemOrTag, new Entry(itemOrTag, volts));
        }

        @Override
        public void remove(String key) {
            byKey.remove(key);
        }
    }

    public static List<String> load(Map<String, String> files) {
        return load(files, MachineData.electrodeContributors());
    }

    public static List<String> load(Map<String, String> files, List<Consumer<MachineData.ElectrodeSink>> contributors) {
        Table table = new Table();
        List<String> errors = new ArrayList<>();
        new TreeMap<>(files).forEach((id, json) -> {
            try {
                Table staged = new Table();
                staged.byKey.putAll(table.byKey);
                FfData root = FfJson.parse(json);
                if (root.has("electrodes")) {
                    for (Object o : root.getList("electrodes")) {
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
        for (Consumer<MachineData.ElectrodeSink> c : contributors) {
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
        String key = d.getString("item", "");
        if (d.getBool("remove", false)) {
            t.remove(key);
            return;
        }
        t.item(key, d.getDouble("volts", Double.NaN));
    }

    public static List<Entry> entries() {
        return entries;
    }

    /** Replaces the whole table (server sync on clients, and tests). */
    public static void replace(List<Entry> list) {
        entries = List.copyOf(list);
    }

    /** The item's electrode potential, or null when it is not an electrode. */
    public static Double volts(ItemVariant v, TagLookup tags) {
        if (v == null) {
            return null;
        }
        String id = v.id().toString();
        Double tagged = null;
        for (Entry e : entries) {
            if (e.tag()) {
                if (tagged == null && tags.itemInTag(v, FfId.parse(e.key().substring(1)))) {
                    tagged = e.volts();
                }
            } else if (e.key().equals(id)) {
                return e.volts();
            }
        }
        return tagged;
    }
}
