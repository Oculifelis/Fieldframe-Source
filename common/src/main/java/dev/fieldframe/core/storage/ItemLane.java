package dev.fieldframe.core.storage;

import dev.fieldframe.api.data.FfData;
import dev.fieldframe.api.resource.ItemStore;
import dev.fieldframe.api.resource.ItemVariant;
import dev.fieldframe.api.resource.VariantCodec;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** One named internal item lane (A/B/C): a small slotted inventory owned by a single chassis. */
public final class ItemLane implements ItemStore {
    public static final int MAX_SLOTS = 27;

    private final String name;
    private ItemVariant[] variants;
    private int[] counts;
    private Runnable onChange = () -> { };

    public ItemLane(String name, int slots) {
        if (slots < 1 || slots > MAX_SLOTS) {
            throw new IllegalArgumentException("slots");
        }
        this.name = name;
        this.variants = new ItemVariant[slots];
        this.counts = new int[slots];
    }

    public String name() {
        return name;
    }

    public void setOnChange(Runnable r) {
        this.onChange = r == null ? () -> { } : r;
    }

    @Override
    public int slots() {
        return variants.length;
    }

    @Override
    public ItemVariant variant(int slot) {
        return slot >= 0 && slot < variants.length ? variants[slot] : null;
    }

    @Override
    public int count(int slot) {
        return slot >= 0 && slot < counts.length ? counts[slot] : 0;
    }

    public int slotLimit(ItemVariant v) {
        return v.maxStackSize();
    }

    @Override
    public int insert(ItemVariant v, int amount, boolean simulate) {
        if (v == null || amount <= 0) {
            return 0;
        }
        int left = amount;
        // Pass 1: stack onto matching slots. Pass 2: empty slots.
        for (int pass = 0; pass < 2 && left > 0; pass++) {
            for (int s = 0; s < variants.length && left > 0; s++) {
                boolean match = pass == 0 ? variants[s] != null && variants[s].sameAs(v) : variants[s] == null;
                if (!match) {
                    continue;
                }
                int room = slotLimit(v) - counts[s];
                int moved = Math.min(room, left);
                if (moved <= 0) {
                    continue;
                }
                if (!simulate) {
                    if (variants[s] == null) {
                        variants[s] = v;
                    }
                    counts[s] += moved;
                }
                left -= moved;
            }
        }
        int done = amount - left;
        if (!simulate && done > 0) {
            onChange.run();
        }
        return done;
    }

    @Override
    public int insertSlot(int slot, ItemVariant v, int amount, boolean simulate) {
        if (v == null || amount <= 0 || slot < 0 || slot >= variants.length) {
            return 0;
        }
        if (variants[slot] != null && !variants[slot].sameAs(v)) {
            return 0;
        }
        int moved = Math.min(amount, slotLimit(v) - counts[slot]);
        if (moved <= 0) {
            return 0;
        }
        if (!simulate) {
            variants[slot] = v;
            counts[slot] += moved;
            onChange.run();
        }
        return moved;
    }

    @Override
    public int extractSlot(int slot, int amount, boolean simulate) {
        if (slot < 0 || slot >= variants.length || amount <= 0 || variants[slot] == null) {
            return 0;
        }
        int moved = Math.min(amount, counts[slot]);
        if (!simulate && moved > 0) {
            counts[slot] -= moved;
            if (counts[slot] == 0) {
                variants[slot] = null;
            }
            onChange.run();
        }
        return moved;
    }

    /** Opaque copy of the contents, for platform transaction journals. */
    public Object snapshot() {
        return new Object[]{variants.clone(), counts.clone()};
    }

    /** Restores a {@link #snapshot()} taken from this lane (transaction rollback). */
    public void restore(Object snapshot) {
        Object[] s = (Object[]) snapshot;
        variants = ((ItemVariant[]) s[0]).clone();
        counts = ((int[]) s[1]).clone();
        onChange.run();
    }

    /** Direct slot set used only by trusted platform GUI slot sync; still validates. */
    public void setSlot(int slot, ItemVariant v, int count) {
        if (count < 0 || (v != null && count > slotLimit(v))) {
            throw new IllegalArgumentException("count");
        }
        variants[slot] = count == 0 ? null : v;
        counts[slot] = v == null ? 0 : count;
        onChange.run();
    }

    /** Number of occupied slots that would be lost by shrinking to {@code newSlots}. */
    public int occupiedBeyond(int newSlots) {
        int n = 0;
        for (int s = newSlots; s < variants.length; s++) {
            if (variants[s] != null) {
                n++;
            }
        }
        return n;
    }

    /** Compacts contents and resizes. Refuses (returns false) if contents would be truncated. */
    public boolean resize(int newSlots) {
        if (newSlots < 1 || newSlots > MAX_SLOTS) {
            return false;
        }
        if (newSlots < variants.length) {
            // Try compacting first so a shrink only fails when the lane genuinely holds too many stacks.
            ItemLane tmp = new ItemLane(name, newSlots);
            for (int s = 0; s < variants.length; s++) {
                if (variants[s] != null && tmp.insert(variants[s], counts[s], true) < counts[s]) {
                    return false;
                }
                if (variants[s] != null) {
                    tmp.insert(variants[s], counts[s], false);
                }
            }
            variants = tmp.variants;
            counts = tmp.counts;
        } else {
            variants = Arrays.copyOf(variants, newSlots);
            counts = Arrays.copyOf(counts, newSlots);
        }
        onChange.run();
        return true;
    }

    public FfData save(VariantCodec codec) {
        FfData d = new FfData().putInt("slots", variants.length);
        List<Object> list = new ArrayList<>();
        for (int s = 0; s < variants.length; s++) {
            if (variants[s] != null) {
                FfData e = codec.encodeItem(variants[s]).copy();
                e.putInt("slot", s).putInt("count", counts[s]);
                list.add(e);
            }
        }
        d.putList("items", list);
        return d;
    }

    /** Loads contents; malformed entries are skipped and reported through the returned count. */
    public int load(FfData d, VariantCodec codec) {
        int slots = d.getIntClamped("slots", variants.length, 1, MAX_SLOTS);
        variants = new ItemVariant[slots];
        counts = new int[slots];
        int bad = 0;
        for (Object o : d.getList("items")) {
            if (!(o instanceof FfData e)) {
                bad++;
                continue;
            }
            int s = e.getInt("slot", -1);
            int c = e.getInt("count", 0);
            ItemVariant v = codec.decodeItem(e);
            if (v == null || s < 0 || s >= slots || c <= 0 || variants[s] != null) {
                bad++;
                continue;
            }
            variants[s] = v;
            counts[s] = Math.min(c, slotLimit(v));
        }
        return bad;
    }
}
