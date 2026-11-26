package dev.fieldframe.api.manual;

import dev.fieldframe.api.data.FfData;
import dev.fieldframe.api.id.FfId;

import java.util.ArrayList;
import java.util.List;

/**
 * The Engineering Field Manual's structured document model. Content is data: categories, entries and pages,
 * where each page names a registered {@link ManualPageTypes page type} and carries inert {@link FfData}.
 * The shipped manual is authored entirely with this model.
 */
public final class ManualModel {
    private ManualModel() {
    }

    public static final int FORMAT_VERSION = 1;
    public static final int MAX_PAGES = 32;
    public static final int MAX_TEXT = 8192;

    public record Category(FfId id, String title, String icon, int sort, String description) {
    }

    public record Page(FfId type, FfData data) {
        public Page {
            data = data == null ? new FfData() : data.copy();
        }
    }

    /**
     * @param requiresMod optional mod id; when absent the entry is shown as unavailable instead of breaking links
     * @param advanced    organizational flag only: essential operating instructions are never hidden
     */
    public record Entry(FfId id, FfId category, String title, String icon, int sort, List<Page> pages,
                        List<FfId> related, String requiresMod, boolean advanced, String source) {
        public Entry {
            pages = List.copyOf(pages);
            related = List.copyOf(related);
            requiresMod = requiresMod == null ? "" : requiresMod;
            source = source == null ? "" : source;
            if (pages.size() > MAX_PAGES) {
                throw new IllegalArgumentException(id + ": more than " + MAX_PAGES + " pages");
            }
        }
    }

    /** Parses an entry definition (from resource JSON or script) with bounded validation. */
    public static Entry parseEntry(FfId id, FfData d, String source) {
        int version = d.getInt("format", FORMAT_VERSION);
        if (version > FORMAT_VERSION) {
            throw new IllegalArgumentException(id + ": manual format " + version + " is newer than supported " + FORMAT_VERSION);
        }
        FfId cat = FfId.tryParse(d.getString("category", "fieldframe:basics"));
        if (cat == null) {
            throw new IllegalArgumentException(id + ": bad category");
        }
        List<Page> pages = new ArrayList<>();
        for (Object o : d.getList("pages")) {
            if (o instanceof FfData pd) {
                FfId type = FfId.tryParse(pd.getString("type", "fieldframe:text"));
                if (type == null) {
                    throw new IllegalArgumentException(id + ": bad page type");
                }
                pages.add(new Page(type, pd));
            }
        }
        List<FfId> related = new ArrayList<>();
        for (Object o : d.getList("related")) {
            if (o instanceof String s && FfId.tryParse(s) != null) {
                related.add(FfId.parse(s));
            }
        }
        String title = d.getString("title", id.toString());
        if (title.length() > 128) {
            throw new IllegalArgumentException(id + ": title too long");
        }
        return new Entry(id, cat, title, d.getString("icon", ""), d.getInt("sort", 100), pages, related,
                d.getString("requires_mod", ""), d.getBool("advanced", false), source);
    }

    public static Category parseCategory(FfId id, FfData d) {
        return new Category(id, d.getString("title", id.toString()), d.getString("icon", ""), d.getInt("sort", 100), d.getString("description", ""));
    }
}
