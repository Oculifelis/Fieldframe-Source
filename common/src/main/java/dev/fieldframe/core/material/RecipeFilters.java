package dev.fieldframe.core.material;

import dev.fieldframe.api.data.FfData;
import dev.fieldframe.api.data.FfJson;
import dev.fieldframe.api.id.FfId;
import dev.fieldframe.api.process.MachineData;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Consumer;

/**
 * Recipe removals for Fieldframe machines: {@code data/<ns>/fieldframe/recipe_filters/*.json} plus Java and KubeJS
 * contributors ({@link MachineData}). Applies to every recipe a machine, JEI or the manual sees: Fieldframe's own,
 * smelting used by the Heater, and Metallurgy's recipes read on NeoForge.
 * <pre>{ "remove": ["fieldframe:alloying/metallurgy/bronze", "fieldframe:pressing/*"],
 *   "remove_families": ["fieldframe:heating"] }</pre>
 * A pattern ending in {@code *} matches every id that starts with the rest. To replace a recipe instead, ship one with
 * the same id (on NeoForge a real recipe with a Metallurgy recipe's Fieldframe id replaces the one read from Metallurgy).
 */
public final class RecipeFilters {
    private static volatile Set<String> ids = Set.of();
    private static volatile List<String> prefixes = List.of();
    private static volatile Set<String> families = Set.of();

    private RecipeFilters() {
    }

    static final class Table implements MachineData.RecipeFilterSink {
        final Set<String> ids = new LinkedHashSet<>();
        final Set<String> prefixes = new LinkedHashSet<>();
        final Set<String> families = new LinkedHashSet<>();

        @Override
        public void remove(String idOrPattern) {
            String p = idOrPattern == null ? "" : idOrPattern.trim();
            if (p.endsWith("*")) {
                String prefix = p.substring(0, p.length() - 1);
                if (prefix.isEmpty()) {
                    throw new IllegalArgumentException("pattern '*' would remove everything; use remove_families");
                }
                prefixes.add(prefix);
            } else if (FfId.tryParse(p) != null) {
                ids.add(FfId.parse(p).toString());
            } else {
                throw new IllegalArgumentException("bad recipe id '" + idOrPattern + "'");
            }
        }

        @Override
        public void removeFamily(FfId family) {
            families.add(family.toString());
        }
    }

    public static List<String> load(Map<String, String> files) {
        return load(files, MachineData.recipeFilterContributors());
    }

    public static List<String> load(Map<String, String> files, List<Consumer<MachineData.RecipeFilterSink>> contributors) {
        Table t = new Table();
        List<String> errors = new ArrayList<>();
        new TreeMap<>(files).forEach((id, json) -> {
            try {
                Table staged = new Table();
                apply(staged, FfJson.parse(json));
                t.ids.addAll(staged.ids);
                t.prefixes.addAll(staged.prefixes);
                t.families.addAll(staged.families);
            } catch (RuntimeException ex) {
                errors.add(id + ": " + ex.getMessage());
            }
        });
        for (Consumer<MachineData.RecipeFilterSink> c : contributors) {
            try {
                c.accept(t);
            } catch (RuntimeException ex) {
                errors.add("contributor: " + ex.getMessage());
            }
        }
        install(t);
        return errors;
    }

    private static void apply(Table t, FfData root) {
        for (Object o : root.has("remove") ? root.getList("remove") : List.of()) {
            t.remove(String.valueOf(o));
        }
        for (Object o : root.has("remove_families") ? root.getList("remove_families") : List.of()) {
            t.removeFamily(FfId.parse(String.valueOf(o)));
        }
    }

    private static void install(Table t) {
        ids = Set.copyOf(t.ids);
        prefixes = List.copyOf(t.prefixes);
        families = Set.copyOf(t.families);
    }

    /** True when packs removed this recipe from Fieldframe machines. */
    public static boolean removed(FfId recipe, FfId family) {
        if (family != null && families.contains(family.toString())) {
            return true;
        }
        String id = recipe.toString();
        if (ids.contains(id)) {
            return true;
        }
        for (String p : prefixes) {
            if (id.startsWith(p)) {
                return true;
            }
        }
        return false;
    }

    /** The current filters, for code (tests) that swaps them out and must put them back with {@link #restore}. */
    public record State(Set<String> ids, List<String> prefixes, Set<String> families) {
    }

    public static State state() {
        return new State(ids, prefixes, families);
    }

    public static void restore(State s) {
        ids = s.ids();
        prefixes = s.prefixes();
        families = s.families();
    }

    public static boolean isEmpty() {
        return ids.isEmpty() && prefixes.isEmpty() && families.isEmpty();
    }

    public static String toJson() {
        List<Object> remove = new ArrayList<>(ids);
        prefixes.forEach(p -> remove.add(p + "*"));
        return FfJson.toJsonString(new FfData().putList("remove", remove).putList("remove_families", new ArrayList<>(families)));
    }

    public static void acceptSync(String json) {
        try {
            Table t = new Table();
            apply(t, FfJson.parse(json));
            install(t);
        } catch (RuntimeException e) {
            // keep the old filters
        }
    }
}
