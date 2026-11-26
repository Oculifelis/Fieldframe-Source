package dev.fieldframe.core.modules.world;

import dev.fieldframe.api.data.FfData;
import dev.fieldframe.api.module.ModuleContext;
import dev.fieldframe.api.resource.ItemStore;
import dev.fieldframe.api.resource.ItemVariant;
import dev.fieldframe.api.resource.VariantCodec;
import dev.fieldframe.api.resource.VariantCodecs;
import dev.fieldframe.api.world.WorldActions.Drop;

import java.util.ArrayList;
import java.util.List;

/**
 * Items a world module has already taken out of the world but not yet stored (output lane full). Persisted with
 * the module so a reload never loses or repeats them. Modules must not start new world work while this is
 * non-empty, which keeps it bounded to the drops of one action.
 */
public final class PendingItems {
    /** Hard cap on stored entries; one block or use never yields more. */
    public static final int MAX_ENTRIES = 64;

    private final List<Drop> items = new ArrayList<>();

    public boolean isEmpty() {
        return items.isEmpty();
    }

    public int total() {
        int n = 0;
        for (Drop d : items) {
            n += d.count();
        }
        return n;
    }

    public void add(ItemVariant v, int count) {
        if (v == null || count <= 0) {
            return;
        }
        for (int i = 0; i < items.size(); i++) {
            Drop d = items.get(i);
            if (d.variant().sameAs(v)) {
                items.set(i, new Drop(v, d.count() + count));
                return;
            }
        }
        items.add(new Drop(v, count));
    }

    public void addAll(List<Drop> drops) {
        for (Drop d : drops) {
            add(d.variant(), d.count());
        }
    }

    /** Takes up to {@code n} of {@code v} out of the pending list; returns how many were taken. */
    public int take(ItemVariant v, int n) {
        for (int i = 0; i < items.size(); i++) {
            Drop d = items.get(i);
            if (d.variant().sameAs(v)) {
                int got = Math.min(n, d.count());
                if (got == d.count()) {
                    items.remove(i);
                } else {
                    items.set(i, new Drop(v, d.count() - got));
                }
                return got;
            }
        }
        return 0;
    }

    /** Moves as much as fits into {@code lane}. Returns true when nothing remains pending. */
    public boolean flush(ItemStore lane) {
        if (lane == null) {
            return items.isEmpty();
        }
        for (int i = items.size() - 1; i >= 0; i--) {
            Drop d = items.get(i);
            int in = Math.max(0, Math.min(d.count(), lane.insert(d.variant(), d.count(), false)));
            if (in >= d.count()) {
                items.remove(i);
            } else if (in > 0) {
                items.set(i, new Drop(d.variant(), d.count() - in));
            }
        }
        return items.isEmpty();
    }

    /** Hands everything to the platform (player or ground). Used on module removal. */
    public void spillAll(ModuleContext ctx) {
        for (Drop d : items) {
            ctx.spill(d.variant(), d.count());
        }
        items.clear();
    }

    public List<Object> save() {
        VariantCodec c = VariantCodecs.active();
        List<Object> l = new ArrayList<>();
        for (Drop d : items) {
            l.add(c.encodeItem(d.variant()).copy().putInt("count", d.count()));
        }
        return l;
    }

    public void load(List<Object> in) {
        items.clear();
        VariantCodec c = VariantCodecs.active();
        for (Object e : in) {
            if (e instanceof FfData sd && items.size() < MAX_ENTRIES) {
                ItemVariant v = c.decodeItem(sd);
                int n = sd.getIntClamped("count", 0, 0, 64 * 64);
                if (v != null && n > 0) {
                    add(v, n);
                }
            }
        }
    }
}
