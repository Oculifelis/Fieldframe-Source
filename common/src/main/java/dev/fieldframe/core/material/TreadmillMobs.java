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
 * Animals the Treadmill accepts: what each makes and what it eats. Datapacks:
 * {@code data/<ns>/fieldframe/treadmill/*.json}, one entry or {@code {"mobs": [...]}}:
 * <pre>
 * { "entity": "minecraft:cow", "rf_per_tick": 6, "food": "minecraft:wheat" }
 * { "entity": "minecraft:horse", "rf_per_speed": 40, "food": "#c:foods/golden" }
 * { "entity": "minecraft:pig", "remove": true }
 * </pre>
 * {@code rf_per_speed} scales with the animal's movement speed (a horse's speed is 0.1125 to 0.3375). Later files
 * override earlier ones; then Java and KubeJS contributors ({@link MachineData#addTreadmillContributor}) run.
 */
public final class TreadmillMobs {
    public record Entry(FfId entity, double rfPerTick, double rfPerSpeed, String food) {
        /** RF a tick for an animal of this kind with the given movement speed. */
        public double rf(double speed) {
            return rfPerSpeed > 0 ? rfPerSpeed * Math.max(0, speed) : rfPerTick;
        }

        /** True when {@code v} is this animal's food (an item id or a {@code #tag}). */
        public boolean eats(ItemVariant v, TagLookup tags) {
            if (v == null) {
                return false;
            }
            if (food.startsWith("#")) {
                FfId tag = FfId.tryParse(food.substring(1));
                return tag != null && tags != null && tags.itemInTag(v, tag);
            }
            return v.id().toString().equals(food);
        }
    }

    public static final double MAX_RF = 10_000;

    private static volatile Map<FfId, Entry> entries = Map.of();

    private TreadmillMobs() {
    }

    static final class Table implements MachineData.TreadmillSink {
        final Map<FfId, Entry> byKey = new LinkedHashMap<>();

        @Override
        public void mob(String entity, double rfPerTick, double rfPerSpeed, String food) {
            FfId id = FfId.tryParse(entity == null ? "" : entity);
            if (id == null) {
                throw new IllegalArgumentException("bad entity '" + entity + "'");
            }
            if (!(rfPerTick >= 0 && rfPerTick <= MAX_RF) || !(rfPerSpeed >= 0 && rfPerSpeed <= MAX_RF) || rfPerTick + rfPerSpeed <= 0) {
                throw new IllegalArgumentException(entity + ": rf_per_tick or rf_per_speed must be above 0 (at most " + (long) MAX_RF + ")");
            }
            String f = food == null ? "" : food;
            if (FfId.tryParse(f.startsWith("#") ? f.substring(1) : f) == null) {
                throw new IllegalArgumentException(entity + ": bad food '" + food + "'");
            }
            byKey.put(id, new Entry(id, rfPerTick, rfPerSpeed, f));
        }

        @Override
        public void remove(String entity) {
            FfId id = FfId.tryParse(entity == null ? "" : entity);
            if (id != null) {
                byKey.remove(id);
            }
        }
    }

    public static List<String> load(Map<String, String> files) {
        return load(files, MachineData.treadmillContributors());
    }

    public static List<String> load(Map<String, String> files, List<Consumer<MachineData.TreadmillSink>> contributors) {
        Table table = new Table();
        List<String> errors = new ArrayList<>();
        new TreeMap<>(files).forEach((id, json) -> {
            try {
                Table staged = new Table();
                staged.byKey.putAll(table.byKey);
                FfData root = FfJson.parse(json);
                if (root.has("mobs")) {
                    for (Object o : root.getList("mobs")) {
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
        for (Consumer<MachineData.TreadmillSink> c : contributors) {
            try {
                c.accept(table);
            } catch (RuntimeException ex) {
                errors.add("contributor: " + ex.getMessage());
            }
        }
        entries = Map.copyOf(table.byKey);
        return errors;
    }

    private static void apply(Table t, FfData d) {
        String key = d.getString("entity", "");
        if (d.getBool("remove", false)) {
            t.remove(key);
            return;
        }
        t.mob(key, d.getDouble("rf_per_tick", 0), d.getDouble("rf_per_speed", 0), d.getString("food", ""));
    }

    public static Map<FfId, Entry> entries() {
        return entries;
    }

    /** The entry for an entity type, or null. */
    public static Entry of(FfId entity) {
        return entity == null ? null : entries.get(entity);
    }

    /** Replaces the whole table (server sync on clients, and tests). */
    public static void replace(List<Entry> list) {
        Map<FfId, Entry> m = new LinkedHashMap<>();
        for (Entry e : list) {
            m.put(e.entity(), e);
        }
        entries = Map.copyOf(m);
    }
}
