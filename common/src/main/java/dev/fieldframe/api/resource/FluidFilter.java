package dev.fieldframe.api.resource;

import dev.fieldframe.api.data.FfData;
import dev.fieldframe.api.id.FfId;

import java.util.ArrayList;
import java.util.List;

/** Fluid counterpart of {@link ItemFilter}, with the same matching policy. */
public final class FluidFilter implements FluidMatcher {
    public static final FluidFilter ANY = new FluidFilter(List.of(), true);

    private final List<ItemFilter.Entry> entries;
    private final boolean deny;

    public FluidFilter(List<ItemFilter.Entry> entries, boolean deny) {
        if (entries.size() > ItemFilter.MAX_ENTRIES) {
            throw new IllegalArgumentException("too many entries");
        }
        this.entries = List.copyOf(entries);
        this.deny = deny;
    }

    public static FluidFilter allowIds(String... ids) {
        List<ItemFilter.Entry> e = new ArrayList<>();
        for (String id : ids) {
            e.add(new ItemFilter.Entry(false, FfId.parse(id), ""));
        }
        return new FluidFilter(e, false);
    }

    public boolean isAny() {
        return deny && entries.isEmpty();
    }

    @Override
    public boolean test(FluidVariant v, TagLookup tags) {
        boolean hit = false;
        for (ItemFilter.Entry e : entries) {
            if (e.tag() ? tags.fluidInTag(v, e.id()) : e.matches(v.id(), v.componentKey())) {
                hit = true;
                break;
            }
        }
        return deny != hit;
    }

    public FfData toData() {
        return ItemFilter.encode(entries, deny);
    }

    public static FluidFilter fromData(FfData d) {
        ItemFilter f = ItemFilter.fromData(d);
        return new FluidFilter(f.entries(), f.denyList());
    }

    public List<ItemFilter.Entry> entries() {
        return entries;
    }
}
