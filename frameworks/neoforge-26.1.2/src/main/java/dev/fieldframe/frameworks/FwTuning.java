package dev.fieldframe.frameworks;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Every number of the Frameworks multiblocks, as data. Packs change any of them in
 * {@code data/<ns>/fieldframe_frameworks/tuning/*.json}:
 * <pre>{ "foundry": { "heat_rf": 60 }, "starcore": { "ignition_rf": 2000000, "field_per_coil": 80 } }</pre>
 * Keys are {@code section.name} (see {@link #DEFAULTS}); later files override earlier ones, then Java contributors
 * ({@link #addContributor}) run. Values outside a key's range, unknown keys and inconsistent sets (a minimum above its
 * maximum) are reported and the whole file is skipped. Frames and Gantry Controllers resize their storage after a
 * reload that changes their capacities, spilling what no longer fits.
 */
public final class FwTuning {
    private FwTuning() {
    }

    /** A tunable number: its default and the range data may set. */
    public record Spec(double def, double min, double max, String what) {
    }

    /** Java changes after the data files. */
    public interface Sink {
        void set(String key, double value);
    }

    public static final Map<String, Spec> DEFAULTS;

    static {
        Map<String, Spec> d = new LinkedHashMap<>();
        d.put("grand_frame.min_size", new Spec(5, 3, 32, "smallest frame side, blocks"));
        d.put("grand_frame.max_size", new Spec(9, 3, 32, "largest frame side, blocks"));
        d.put("grand_frame.base_slots", new Spec(4, 1, 27, "slots per storage before components"));
        d.put("grand_frame.base_mb", new Spec(4_000, 0, 10_000_000, "mB per tank before components"));
        d.put("grand_frame.base_rf", new Spec(16_000, 0, 2_000_000_000, "RF buffer before components"));

        d.put("foundry.mb_per_crucible", new Spec(8_000, 1, 10_000_000, "pool size each crucible adds, mB"));
        d.put("foundry.heat_rf", new Spec(40, 0, 1_000_000, "RF a tick per crucible while heating"));
        d.put("foundry.heat_up", new Spec(5, 0.01, 1_000, "K a tick it heats"));
        d.put("foundry.cool_down", new Spec(3, 0, 1_000, "K a tick it cools without power"));
        d.put("foundry.ambient", new Spec(300, 1, 10_000, "temperature it cools to, K"));
        d.put("foundry.max_k", new Spec(2_000, 1, 10_000, "hottest it gets, K"));
        d.put("foundry.melt_cap", new Spec(1_600, 1, 10_000, "melting point used for metals without one, K"));
        d.put("foundry.melt_rf_per_mb", new Spec(14, 0, 1_000_000, "RF per mB melted"));
        d.put("foundry.pour_per_tick", new Spec(144, 1, 1_000_000, "mB poured a tick"));
        d.put("cooling_tower.mb_per_fin", new Spec(40, 0, 1_000_000, "steam turned back into water a tick per Cooling Fin, mB"));
        d.put("foundry.ore_bonus_mb", new Spec(288, 0, 1_000_000, "extra mB from an ore"));
        d.put("arc_furnace.interval", new Spec(20, 1, 12_000, "ticks between arcs"));
        d.put("arc_furnace.arc_rf", new Spec(40_000, 0, 2_000_000_000, "RF one arc takes per Arc Electrode (a Storm Coil strike is 200,000)"));
        d.put("arc_furnace.items_per_electrode", new Spec(16, 1, 4_096, "input items each Arc Electrode works an arc"));

        d.put("gantry.min_side", new Spec(3, 3, 256, "smallest rail ring side, blocks"));
        d.put("gantry.max_side", new Spec(64, 3, 256, "largest rail ring side, blocks"));
        d.put("gantry.quarry_depth", new Spec(48, 1, 512, "layers the quarry digs below the rails"));
        d.put("gantry.farm_depth", new Spec(6, 1, 64, "layers the farm head looks down"));
        d.put("gantry.slots", new Spec(9, 1, 27, "slots per storage of the controller"));
        d.put("gantry.mb_per_tank", new Spec(16_000, 0, 10_000_000, "mB per tank of the controller"));
        d.put("gantry.rf_capacity", new Spec(200_000, 0, 2_000_000_000, "RF buffer of the controller"));
        d.put("gantry.rf_per_step", new Spec(10, 0, 1_000_000, "RF per head move"));
        d.put("gantry.rf_per_break", new Spec(120, 0, 1_000_000, "RF per block quarried"));
        d.put("gantry.rf_per_hardness", new Spec(60, 0, 1_000_000, "extra RF per point of hardness"));
        d.put("gantry.rf_per_pump", new Spec(80, 0, 1_000_000, "RF per fluid block pumped"));
        d.put("gantry.rf_per_harvest", new Spec(40, 0, 1_000_000, "RF per crop harvested"));
        d.put("gantry.skips_per_action", new Spec(64, 1, 4_096, "empty columns looked at per action"));

        d.put("starcore.loss", new Spec(0.01, 0, 1, "share of its temperature the plasma loses a tick"));
        d.put("starcore.critical_fuel", new Spec(100, 1, 1_000_000, "fuel (mB) where fusion heat balances the loss"));
        d.put("starcore.burn", new Spec(0.005, 0, 1, "share of the fuel that fuses a tick"));
        d.put("starcore.pressure_div", new Spec(100, 0.001, 1_000_000, "pressure = fuel x MK / this"));
        d.put("starcore.rf_per_unit", new Spec(1.33, 0, 1_000_000, "RF a tick per mB of fuel per MK"));
        d.put("starcore.ignition_mk", new Spec(50, 1, 100_000, "temperature on lighting, MK"));
        d.put("starcore.min_mk", new Spec(10, 0, 100_000, "the plasma fades below this, MK"));
        d.put("starcore.max_mk", new Spec(1_000, 1, 100_000, "hottest the plasma gets, MK"));
        d.put("starcore.ignition_fuel", new Spec(20, 0, 1_000_000, "fuel gathered before lighting, mB"));
        d.put("starcore.ignition_rf", new Spec(5_000_000, 0, 2_000_000_000, "RF to light it"));
        d.put("starcore.field_per_level", new Spec(2, 0, 1_000_000, "field a Field Magnet makes per signal level"));
        d.put("starcore.rf_per_level", new Spec(40, 0, 1_000_000, "RF a tick a Field Magnet costs per level"));
        d.put("starcore.field_per_coil", new Spec(60, 0, 1_000_000, "most field one coil holds"));
        d.put("starcore.escapes_per_coil", new Spec(3, 1, 1_000, "escapes before a coil breaks"));
        d.put("starcore.fuel_per_dust", new Spec(250, 1, 1_000_000, "fuel an Ender Alloy dust is good for, mB"));
        DEFAULTS = Collections.unmodifiableMap(d);
    }

    private static final List<Consumer<Sink>> CONTRIBUTORS = new CopyOnWriteArrayList<>();
    private static volatile Map<String, Double> values = defaults();
    private static volatile int revision;

    /** Adds a Java change that runs after the data files on every reload. */
    public static void addContributor(Consumer<Sink> c) {
        CONTRIBUTORS.add(c);
    }

    public static double get(String key) {
        Double v = values.get(key);
        if (v == null) {
            throw new IllegalArgumentException("unknown Frameworks tuning key " + key);
        }
        return v;
    }

    public static long getLong(String key) {
        return Math.round(get(key));
    }

    public static int getInt(String key) {
        return (int) Math.round(get(key));
    }

    /** Changes every time the values change (frames and controllers compare it to resize). */
    public static int revision() {
        return revision;
    }

    public static Map<String, Double> values() {
        return values;
    }

    private static Map<String, Double> defaults() {
        Map<String, Double> m = new LinkedHashMap<>();
        DEFAULTS.forEach((k, s) -> m.put(k, s.def()));
        return m;
    }

    /** Loads the data files (id -> JSON text) and the contributors; returns the problems (those files are skipped). */
    public static List<String> load(Map<String, String> files) {
        Map<String, Double> m = defaults();
        List<String> errors = new ArrayList<>();
        new TreeMap<>(files).forEach((id, json) -> {
            try {
                Map<String, Double> staged = new LinkedHashMap<>(m);
                JsonObject root = JsonParser.parseString(json).getAsJsonObject();
                for (Map.Entry<String, JsonElement> section : root.entrySet()) {
                    for (Map.Entry<String, JsonElement> e : section.getValue().getAsJsonObject().entrySet()) {
                        set(staged, section.getKey() + "." + e.getKey(), e.getValue().getAsDouble());
                    }
                }
                check(staged);
                m.clear();
                m.putAll(staged);
            } catch (RuntimeException ex) {
                errors.add(id + ": " + ex.getMessage());
            }
        });
        for (Consumer<Sink> c : CONTRIBUTORS) {
            Map<String, Double> staged = new LinkedHashMap<>(m);
            try {
                c.accept((k, v) -> set(staged, k, v));
                check(staged);
                m.clear();
                m.putAll(staged);
            } catch (RuntimeException ex) {
                errors.add("contributor: " + ex.getMessage());
            }
        }
        install(m);
        return errors;
    }

    /** Replaces every value at once (a server's values on a client, or tests); unknown keys are ignored. */
    public static void install(Map<String, Double> m) {
        Map<String, Double> out = defaults();
        m.forEach((k, v) -> {
            if (out.containsKey(k)) {
                out.put(k, v);
            }
        });
        if (!out.equals(values)) {
            values = Collections.unmodifiableMap(out);
            revision++;
        }
    }

    /** Back to the defaults (tests). */
    public static void reset() {
        install(defaults());
    }

    private static void set(Map<String, Double> m, String key, double v) {
        Spec s = DEFAULTS.get(key);
        if (s == null) {
            throw new IllegalArgumentException("unknown key " + key);
        }
        if (!(v >= s.min() && v <= s.max())) {
            throw new IllegalArgumentException(key + " = " + v + " is outside " + s.min() + " to " + s.max());
        }
        m.put(key, v);
    }

    private static void check(Map<String, Double> m) {
        order(m, "grand_frame.min_size", "grand_frame.max_size");
        order(m, "gantry.min_side", "gantry.max_side");
        order(m, "starcore.min_mk", "starcore.ignition_mk");
        order(m, "starcore.ignition_mk", "starcore.max_mk");
    }

    private static void order(Map<String, Double> m, String low, String high) {
        if (m.get(low) > m.get(high)) {
            throw new IllegalArgumentException(low + " (" + m.get(low) + ") is above " + high + " (" + m.get(high) + ")");
        }
    }

    // ------------------------------------------------------------------ sync to clients

    static String toJson() {
        JsonObject o = new JsonObject();
        values.forEach(o::addProperty);
        return o.toString();
    }

    static void acceptSync(String json) {
        try {
            Map<String, Double> m = new LinkedHashMap<>();
            JsonParser.parseString(json).getAsJsonObject().entrySet().forEach(e -> m.put(e.getKey(), e.getValue().getAsDouble()));
            install(m);
        } catch (RuntimeException e) {
            // keep the old values
        }
    }
}
