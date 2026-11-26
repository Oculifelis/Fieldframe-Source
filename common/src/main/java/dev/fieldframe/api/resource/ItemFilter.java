package dev.fieldframe.api.resource;

import dev.fieldframe.api.data.FfData;
import dev.fieldframe.api.id.FfId;

import java.util.ArrayList;
import java.util.List;

/**
 * An explicit allow/deny list of item ids and tags.
 *
 * <p>Matching policy: an {@code id} entry matches by item id only (components ignored) unless the entry
 * carries a component key, in which case the full identity must match. Tag entries match by tag membership.
 * The same rules are used by ports, sensors and blueprint comparison.
 */
public final class ItemFilter implements ItemMatcher {
    public static final int MAX_ENTRIES = 16;

    public record Entry(boolean tag, FfId id, String componentKey) {
        public Entry {
            componentKey = componentKey == null ? "" : componentKey;
        }

        boolean matches(FfId variantId, String variantComponents) {
            return id.equals(variantId) && (componentKey.isEmpty() || componentKey.equals(variantComponents));
        }
    }

    public static final ItemFilter ANY = new ItemFilter(List.of(), true);

    private final List<Entry> entries;
    private final boolean deny;

    public ItemFilter(List<Entry> entries, boolean deny) {
        if (entries.size() > MAX_ENTRIES) {
            throw new IllegalArgumentException("Filter has more than " + MAX_ENTRIES + " entries");
        }
        this.entries = List.copyOf(entries);
        this.deny = deny;
    }

    public static ItemFilter allowIds(String... ids) {
        List<Entry> e = new ArrayList<>();
        for (String id : ids) {
            e.add(new Entry(false, FfId.parse(id), ""));
        }
        return new ItemFilter(e, false);
    }

    public static ItemFilter allowTag(String tag) {
        return new ItemFilter(List.of(new Entry(true, FfId.parse(tag), "")), false);
    }

    public boolean isAny() {
        return deny && entries.isEmpty();
    }

    public List<Entry> entries() {
        return entries;
    }

    public boolean denyList() {
        return deny;
    }

    @Override
    public boolean test(ItemVariant v, TagLookup tags) {
        boolean hit = false;
        for (Entry e : entries) {
            if (e.tag() ? tags.itemInTag(v, e.id()) : e.matches(v.id(), v.componentKey())) {
                hit = true;
                break;
            }
        }
        return deny != hit;
    }

    public FfData toData() {
        return encode(entries, deny);
    }

    static FfData encode(List<Entry> entries, boolean deny) {
        FfData d = new FfData();
        d.putBool("deny", deny);
        List<Object> list = new ArrayList<>();
        for (Entry e : entries) {
            FfData ed = new FfData();
            ed.putString(e.tag() ? "tag" : "id", e.id().toString());
            if (!e.componentKey().isEmpty()) {
                ed.putString("components", e.componentKey());
            }
            list.add(ed);
        }
        d.putList("entries", list);
        return d;
    }

    /** Bounded, validating decode for untrusted data. Invalid entries are dropped. */
    public static ItemFilter fromData(FfData d) {
        if (d == null) {
            return ANY;
        }
        List<Entry> out = new ArrayList<>();
        for (Object o : d.getList("entries")) {
            if (out.size() >= MAX_ENTRIES) {
                break;
            }
            if (o instanceof FfData ed) {
                boolean tag = ed.has("tag");
                FfId id = FfId.tryParse(ed.getString(tag ? "tag" : "id", null));
                String comp = ed.getString("components", "");
                if (id != null && comp.length() <= 4096) {
                    out.add(new Entry(tag, id, comp));
                }
            }
        }
        return new ItemFilter(out, d.getBool("deny", true));
    }
}
