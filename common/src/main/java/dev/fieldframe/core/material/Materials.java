package dev.fieldframe.core.material;

import dev.fieldframe.api.data.FfData;
import dev.fieldframe.api.data.FfJson;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Fieldframe's own materials (its alloys, extra items, extra fluids; common metals come from Metallurgy), read from the bundled
 * table {@code /fieldframe_tables/materials.json}. Each edition registers exactly what this lists, and the resource
 * generator builds models, tags, recipes and worldgen from the same file, so the two cannot drift apart.
 */
public final class Materials {
    /** Chance of one Slag when the Heater smelts an ore or raw ore. */
    public static final double ORE_SLAG_CHANCE = 0.1;

    /** Forms one of Fieldframe's own metals can have (plates and gears are not defined yet). */
    public enum Form {
        INGOT, NUGGET, DUST, BLOCK;

        public final String key = name().toLowerCase(Locale.ROOT);

        public boolean isBlock() {
            return this == BLOCK;
        }

        /** Registry path for a metal in this form, e.g. signal_alloy_ingot, signal_alloy_block. */
        public String path(String metal) {
            return metal + "_" + key;
        }
    }

    public record Metal(String name, int color, Set<Form> forms, String existingIngot) {
        public boolean has(Form f) {
            return forms.contains(f);
        }
    }

    public record Item(String name, int color, String tag, int burnTicks) {
    }

    public record Fluid(String name, int color, int temperature, int density, int light) {
    }

    /**
     * Press-made parts (plates and gears) of one metal. {@code ingotTag} is the convention tag path of its ingots
     * ({@code ingots/<metal>}); {@code metallurgy} marks metals that come from Metallurgy (they also melt and cast).
     */
    public record Part(String metal, int color, String ingotTag, boolean metallurgy) {
        public String path(String form) {
            return metal + "_" + form;
        }
    }

    public record Table(List<Metal> metals, List<Item> items, List<Fluid> fluids, List<String> partForms, List<Part> parts) {
        /** Defensive copies: the table is shared by every registration step. */
        public Table {
            metals = List.copyOf(metals);
            items = List.copyOf(items);
            fluids = List.copyOf(fluids);
            partForms = List.copyOf(partForms);
            parts = List.copyOf(parts);
        }
    }

    private static Table table;

    private Materials() {
    }

    public static synchronized Table get() {
        if (table == null) {
            try (InputStream in = Materials.class.getResourceAsStream("/fieldframe_tables/materials.json")) {
                if (in == null) {
                    throw new IllegalStateException("fieldframe_tables/materials.json missing from the jar");
                }
                String metallurgy;
                try (InputStream mm = Materials.class.getResourceAsStream("/fieldframe_tables/metallurgy_metals.json")) {
                    metallurgy = mm == null ? null : new String(mm.readAllBytes(), StandardCharsets.UTF_8);
                }
                table = parse(new String(in.readAllBytes(), StandardCharsets.UTF_8), metallurgy);
            } catch (IOException e) {
                throw new IllegalStateException("cannot read materials table", e);
            }
        }
        return table;
    }

    public static Table parse(String json) {
        return parse(json, null);
    }

    /**
     * Parses and validates the table; throws with the offending entry named. {@code metallurgyJson} (may be null) is
     * tools/import_metallurgy.py's metal list: each Metallurgy metal gets plates and gears too.
     */
    public static Table parse(String json, String metallurgyJson) {
        FfData root = FfJson.parse(json);
        List<Metal> metals = new ArrayList<>();
        for (Object o : root.getList("metals")) {
            FfData m = (FfData) o;
            String name = name(m);
            java.util.EnumSet<Form> forms = java.util.EnumSet.noneOf(Form.class);
            for (Object f : m.getList("forms")) {
                try {
                    forms.add(Form.valueOf(String.valueOf(f).toUpperCase(Locale.ROOT)));
                } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException("metal " + name + ": unknown form " + f);
                }
            }
            if (m.has("ore")) {
                throw new IllegalArgumentException("metal " + name + ": ores come from Metallurgy, not this table");
            }
            metals.add(new Metal(name, color(m), Collections.unmodifiableSet(forms), m.getString("existing_ingot", null)));
        }
        List<Item> items = new ArrayList<>();
        for (Object o : root.getList("items")) {
            FfData d = (FfData) o;
            items.add(new Item(name(d), color(d), d.getString("tag", ""), d.getIntClamped("burn_ticks", 0, 0, 1_000_000)));
        }
        List<Fluid> fluids = new ArrayList<>();
        for (Object o : root.getList("fluids")) {
            FfData d = (FfData) o;
            fluids.add(new Fluid(name(d), color(d), d.getIntClamped("temperature", 300, 0, 10_000),
                    d.getIntClamped("density", 1000, -100_000, 100_000), d.getIntClamped("light", 0, 0, 15)));
        }
        List<String> forms = new ArrayList<>();
        List<Part> parts = new ArrayList<>();
        FfData pd = root.getDataOrNull("parts");
        if (pd != null) {
            for (Object f : pd.getList("forms")) {
                String form = String.valueOf(f);
                if (!form.equals("plate") && !form.equals("gear")) {
                    throw new IllegalArgumentException("unknown part form " + form);
                }
                forms.add(form);
            }
            for (Object o : pd.getList("metals")) {
                FfData m = (FfData) o;
                parts.add(new Part(name(m), color(m), m.getString("ingot_tag", "ingots/" + name(m)), false));
            }
        }
        if (metallurgyJson != null) {
            for (Object o : FfJson.parse(metallurgyJson).getList("metals")) {
                FfData m = (FfData) o;
                String n = name(m);
                if (parts.stream().noneMatch(p -> p.metal().equals(n))) {
                    parts.add(new Part(n, color(m), "ingots/" + n, true));
                }
            }
        }
        return new Table(metals, items, fluids, forms, parts);
    }

    private static String name(FfData d) {
        String n = d.getString("name", "");
        if (!n.matches("[a-z0-9_]+")) {
            throw new IllegalArgumentException("bad material name '" + n + "'");
        }
        return n;
    }

    private static int color(FfData d) {
        return 0xFF000000 | Integer.parseInt(d.getString("color", "FFFFFF"), 16);
    }

    /** All item registry paths this table adds (block items included), in registration order. */
    public static List<String> itemPaths(Table t) {
        List<String> out = new ArrayList<>();
        for (Metal m : t.metals()) {
            for (Form f : m.forms()) {
                out.add(f.path(m.name()));
            }
        }
        t.items().forEach(i -> out.add(i.name()));
        for (Part p : t.parts()) {
            t.partForms().forEach(f -> out.add(p.path(f)));
        }
        t.fluids().forEach(f -> out.add(f.name() + "_bucket"));
        return out;
    }
}
