package dev.fieldframe.api.manual;

import dev.fieldframe.api.FieldframeRegistry;
import dev.fieldframe.api.data.FfData;
import dev.fieldframe.api.id.FfId;
import dev.fieldframe.api.module.ModuleDefinition;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The effective manual: built from resource content, Java contributors and script contributors, validated,
 * then swapped in as one immutable {@link Book}. Precedence: resources (pack order) &lt; Java contributors &lt;
 * scripts. Replacing an existing entry id requires {@code replace=true}; accidental duplicates are reported
 * and the first definition is kept.
 */
public final class ManualRegistry {
    private ManualRegistry() {
    }

    public static final Pattern LINK = Pattern.compile("\\[\\[([a-z0-9_.-]+:[a-z0-9_./-]+)(?:\\|([^\\]]{1,64}))?]]");

    /** Immutable effective manual. */
    public record Book(Map<FfId, ManualModel.Category> categories, Map<FfId, ManualModel.Entry> entries, List<String> problems, long generation) {
        public List<ManualModel.Entry> entriesIn(FfId category) {
            List<ManualModel.Entry> out = new ArrayList<>();
            for (ManualModel.Entry e : entries.values()) {
                if (e.category().equals(category)) {
                    out.add(e);
                }
            }
            out.sort(Comparator.comparingInt(ManualModel.Entry::sort).thenComparing(ManualModel.Entry::title));
            return out;
        }

        public List<ManualModel.Category> sortedCategories() {
            List<ManualModel.Category> c = new ArrayList<>(categories.values());
            c.sort(Comparator.comparingInt(ManualModel.Category::sort).thenComparing(ManualModel.Category::title));
            return c;
        }

        /** Case-insensitive search over titles and page text. */
        public List<ManualModel.Entry> search(String query) {
            String q = query.toLowerCase(Locale.ROOT).trim();
            List<ManualModel.Entry> out = new ArrayList<>();
            if (q.isEmpty()) {
                return out;
            }
            for (ManualModel.Entry e : entries.values()) {
                if (e.title().toLowerCase(Locale.ROOT).contains(q) || e.id().toString().contains(q)) {
                    out.add(e);
                    continue;
                }
                for (ManualModel.Page p : e.pages()) {
                    if (p.data().getString("text", "").toLowerCase(Locale.ROOT).contains(q)) {
                        out.add(e);
                        break;
                    }
                }
            }
            return out;
        }
    }

    /** A staged build. Not thread safe; build on one thread then publish. */
    public static final class Builder {
        private final Map<FfId, ManualModel.Category> categories = new LinkedHashMap<>();
        private final Map<FfId, ManualModel.Entry> entries = new LinkedHashMap<>();
        private final List<String> problems = new ArrayList<>();

        public Builder category(ManualModel.Category c) {
            categories.put(c.id(), c);
            return this;
        }

        public Builder entry(ManualModel.Entry e, boolean replace) {
            ManualModel.Entry old = entries.get(e.id());
            if (old != null && !replace) {
                problems.add("Duplicate manual entry " + e.id() + " from " + e.source() + " (kept " + old.source() + "; use replace to override)");
                return this;
            }
            entries.put(e.id(), e);
            return this;
        }

        public Builder remove(FfId id) {
            entries.remove(id);
            return this;
        }

        public void problem(String p) {
            problems.add(p);
        }

        public Map<FfId, ManualModel.Entry> entries() {
            return entries;
        }
    }

    private static final List<Consumer<Builder>> JAVA_CONTRIBUTORS = new ArrayList<>();
    private static final List<Consumer<Builder>> SCRIPT_CONTRIBUTORS = new ArrayList<>();
    private static volatile Book current = new Book(Map.of(), Map.of(), List.of(), 0);

    /** Java addons contribute entries here (client side, at setup). Applied on every manual rebuild. */
    public static synchronized void addContributor(Consumer<Builder> c) {
        JAVA_CONTRIBUTORS.add(c);
    }

    /** Script bridge contributions; cleared and re-added when scripts reload. */
    public static synchronized void setScriptContributors(List<Consumer<Builder>> cs) {
        SCRIPT_CONTRIBUTORS.clear();
        SCRIPT_CONTRIBUTORS.addAll(cs);
    }

    public static Book current() {
        return current;
    }

    /**
     * Builds the effective book from resource content plus contributors, generating reference entries for any
     * registered module lacking one, and validating links, page types and categories.
     *
     * @param modLoaded predicate used to mark entries whose required mod is absent
     */
    public static synchronized Book rebuild(Consumer<Builder> resources, Predicate<String> modLoaded) {
        Builder b = new Builder();
        resources.accept(b);
        for (Consumer<Builder> c : JAVA_CONTRIBUTORS) {
            safe(b, c, "java contributor");
        }
        for (Consumer<Builder> c : SCRIPT_CONTRIBUTORS) {
            safe(b, c, "script contributor");
        }
        // Every registered module gets a reference entry, even ones added by scripts or addons.
        if (!b.categories.containsKey(FfId.ff("modules"))) {
            b.category(new ManualModel.Category(FfId.ff("modules"), "Module Reference", "fieldframe:chassis", 900, ""));
        }
        for (ModuleDefinition def : FieldframeRegistry.modules()) {
            if (!b.entries.containsKey(def.manualEntry())) {
                FfData spec = new FfData().putString("module", def.id().toString());
                List<ManualModel.Page> pages = new ArrayList<>();
                pages.add(new ManualModel.Page(ManualPageTypes.MODULE_SPEC, spec));
                if (def.processFamily() != null) {
                    pages.add(new ManualModel.Page(ManualPageTypes.RECIPES, new FfData().putString("family", def.processFamily().toString())));
                }
                b.entries.put(def.manualEntry(), new ManualModel.Entry(def.manualEntry(), FfId.ff("modules"), "@" + def.translationKey(),
                        def.id().toString(), 500, pages, List.of(), "", false, "generated"));
            }
        }
        // Validation.
        for (ManualModel.Entry e : b.entries.values()) {
            if (!b.categories.containsKey(e.category())) {
                b.problems.add(e.id() + ": unknown category " + e.category());
            }
            if (!e.requiresMod().isEmpty() && !modLoaded.test(e.requiresMod())) {
                continue;
            }
            for (int i = 0; i < e.pages().size(); i++) {
                ManualModel.Page p = e.pages().get(i);
                for (String prob : ManualPageTypes.validate(p)) {
                    b.problems.add(e.id() + " page " + (i + 1) + ": " + prob);
                }
                Matcher m = LINK.matcher(p.data().getString("text", ""));
                while (m.find()) {
                    FfId target = FfId.tryParse(m.group(1));
                    if (target == null || !b.entries.containsKey(target)) {
                        b.problems.add(e.id() + " page " + (i + 1) + ": broken link to " + m.group(1));
                    }
                }
                if (p.type().equals(ManualPageTypes.MODULE_SPEC)) {
                    FfId mod = FfId.tryParse(p.data().getString("module", ""));
                    if (mod != null && FieldframeRegistry.module(mod) == null) {
                        b.problems.add(e.id() + " page " + (i + 1) + ": references unknown module " + mod);
                    }
                }
            }
            for (FfId r : e.related()) {
                if (!b.entries.containsKey(r)) {
                    b.problems.add(e.id() + ": related entry " + r + " does not exist");
                }
            }
        }
        Book book = new Book(Map.copyOf(b.categories), java.util.Collections.unmodifiableMap(new LinkedHashMap<>(b.entries)),
                List.copyOf(b.problems), current.generation() + 1);
        current = book;
        return book;
    }

    private static void safe(Builder b, Consumer<Builder> c, String what) {
        try {
            c.accept(b);
        } catch (RuntimeException e) {
            b.problems.add(what + " failed: " + e.getMessage());
        }
    }
}
