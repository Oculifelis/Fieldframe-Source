package dev.fieldframe.core.material;

import dev.fieldframe.api.data.FfData;
import dev.fieldframe.api.data.FfJson;
import dev.fieldframe.api.id.FfId;
import dev.fieldframe.api.process.MachineData;
import dev.fieldframe.api.resource.ItemVariant;
import dev.fieldframe.api.resource.TagLookup;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Consumer;

/**
 * Aura Chamber recipes: a batch of items (usually metal blocks) gives nearby players an effect for a while. Layers,
 * each overriding the one before by id: Metallurgy's chamber recipes read live on NeoForge ({@link #setMirrored},
 * ids {@code fieldframe:metallurgy_<name>}, the same ids Forge 1.20.1 ships as data), datapacks
 * ({@code data/<ns>/fieldframe/aura/*.json}), then Java and KubeJS contributors ({@link MachineData}). One entry,
 * {@code {"auras": [...]}} or {@code {"remove": [ids]}} per file:
 * <pre>{ "ingredient": "metallurgy:adamantine_block", "count": 3, "effect": "minecraft:health_boost",
 *   "duration": 18000, "amplifier": 0 }</pre>
 * An entry's id is its {@code "id"}, else the file id (a list entry: file id + "/" + index). {@code ingredient} takes an id or a
 * {@code #tag}. An entry {@code {"id": ..., "remove": true}} removes that id from every layer.
 */
public final class Auras {
    /** {@code source} is the entry id. */
    public record Entry(String ingredient, int count, FfId effect, int duration, int amplifier, String source) {
        boolean tag() {
            return ingredient.startsWith("#");
        }

        public boolean matches(ItemVariant v, TagLookup tags) {
            return tag() ? tags.itemInTag(v, FfId.parse(ingredient.substring(1))) : v.id().toString().equals(ingredient);
        }
    }

    private static volatile Map<String, Entry> data = Map.of();
    private static volatile Set<String> removed = Set.of();
    private static volatile List<Entry> mirrored = List.of();
    private static volatile List<Entry> effective = List.of();

    private Auras() {
    }

    static final class Table implements MachineData.AuraSink {
        final Map<String, Entry> byId = new LinkedHashMap<>();
        final Set<String> removed = new HashSet<>();

        void put(Entry e) {
            byId.remove(e.source());
            byId.put(e.source(), e);
            removed.remove(e.source());
        }

        @Override
        public void add(MachineData.Aura a) {
            put(new Entry(a.ingredient(), a.count(), a.effect(), a.duration(), a.amplifier(), a.id().toString()));
        }

        @Override
        public void remove(FfId id) {
            byId.remove(id.toString());
            removed.add(id.toString());
        }
    }

    /** Parses datapack files (id -> JSON), runs the contributors, installs the result; returns errors. */
    public static List<String> load(Map<String, String> files) {
        return load(files, MachineData.auraContributors());
    }

    public static List<String> load(Map<String, String> files, List<Consumer<MachineData.AuraSink>> contributors) {
        Table t = new Table();
        List<String> errors = new ArrayList<>();
        new TreeMap<>(files).forEach((id, json) -> {
            try {
                Table staged = new Table();
                staged.byId.putAll(t.byId);
                staged.removed.addAll(t.removed);
                apply(staged, FfJson.parse(json), id);
                t.byId.clear();
                t.byId.putAll(staged.byId);
                t.removed.clear();
                t.removed.addAll(staged.removed);
            } catch (RuntimeException ex) {
                errors.add(id + ": " + ex.getMessage());
            }
        });
        for (Consumer<MachineData.AuraSink> c : contributors) {
            try {
                c.accept(t);
            } catch (RuntimeException ex) {
                errors.add("contributor: " + ex.getMessage());
            }
        }
        data = Map.copyOf(t.byId);
        removed = Set.copyOf(t.removed);
        rebuild();
        return errors;
    }

    private static void apply(Table t, FfData root, String fileId) {
        if (root.has("remove") && !root.has("ingredient") && !root.has("id")) {
            for (Object o : root.getList("remove")) {
                t.remove(FfId.parse(String.valueOf(o)));
            }
            return;
        }
        if (root.has("auras")) {
            List<Object> list = root.getList("auras");
            for (int i = 0; i < list.size(); i++) {
                FfData d = (FfData) list.get(i);
                entry(t, d, d.getString("id", fileId + "/" + i));
            }
        } else {
            entry(t, root, root.getString("id", fileId));
        }
    }

    private static void entry(Table t, FfData d, String id) {
        FfId fid = FfId.tryParse(id);
        if (fid == null) {
            throw new IllegalArgumentException("bad id '" + id + "'");
        }
        if (d.getBool("remove", false)) {
            t.remove(fid);
            return;
        }
        t.put(parse(d, fid.toString()));
    }

    public static Entry parse(FfData d, String source) {
        String ing = d.getString("ingredient", "");
        if (FfId.tryParse(ing.startsWith("#") ? ing.substring(1) : ing) == null) {
            throw new IllegalArgumentException("bad ingredient '" + ing + "'");
        }
        FfId effect = FfId.tryParse(d.getString("effect", ""));
        if (effect == null) {
            throw new IllegalArgumentException("bad effect");
        }
        int count = d.getIntClamped("count", 1, 1, 64);
        int duration = d.getIntClamped("duration", 600, 20, 1_000_000);
        int amp = d.getIntClamped("amplifier", 0, 0, 9);
        return new Entry(ing, count, effect, duration, amp, source);
    }

    /** Entries mirrored from Metallurgy's loaded chamber recipes (NeoForge editions; replaced on every recipe reload). */
    public static void setMirrored(List<Entry> entries) {
        mirrored = List.copyOf(entries);
        rebuild();
    }

    private static synchronized void rebuild() {
        Map<String, Entry> out = new LinkedHashMap<>();
        for (Entry e : mirrored) {
            out.put(e.source(), e);
        }
        out.putAll(data);
        removed.forEach(out::remove);
        effective = List.copyOf(out.values());
        List<MachineData.Aura> pub = new ArrayList<>();
        for (Entry e : effective) {
            FfId id = FfId.tryParse(e.source());
            if (id != null) {
                pub.add(new MachineData.Aura(id, e.ingredient(), e.count(), e.effect(), e.duration(), e.amplifier()));
            }
        }
        MachineData.publish(null, pub);
    }

    public static List<Entry> all() {
        return effective;
    }

    public static String toJson() {
        List<FfData> list = new ArrayList<>();
        for (Entry e : all()) {
            list.add(new FfData().putString("id", e.source()).putString("ingredient", e.ingredient()).putInt("count", e.count())
                    .putString("effect", e.effect().toString()).putInt("duration", e.duration()).putInt("amplifier", e.amplifier()));
        }
        return FfJson.toJsonString(new FfData().putList("auras", list));
    }

    /** Client side: replaces every layer with what the server sent (see {@link #toJson}); contributors do not run. */
    public static void acceptSync(String json) {
        try {
            Table t = new Table();
            apply(t, FfJson.parse(json), "server:sync");
            data = Map.copyOf(t.byId);
            removed = Set.of();
            mirrored = List.of();
            rebuild();
        } catch (RuntimeException e) {
            // keep the old table
        }
    }
}
