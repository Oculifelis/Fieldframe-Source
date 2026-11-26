package dev.fieldframe.neoforge.adapter;

import dev.fieldframe.api.data.FfData;
import net.minecraft.nbt.ByteTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongTag;
import net.minecraft.nbt.NumericTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;

import java.util.ArrayList;
import java.util.List;

/**
 * Lossless FfData &lt;-&gt; NBT conversion. Booleans use ByteTag (FfData never stores bytes otherwise); list elements
 * that are not compounds are wrapped as {@code {"$v": value}} so ListTag stays homogeneous.
 */
public final class NbtBridge {
    private static final String WRAP = "$v";

    private NbtBridge() {
    }

    public static CompoundTag toTag(FfData d) {
        CompoundTag t = new CompoundTag();
        for (var e : d.asMap().entrySet()) {
            t.put(e.getKey(), toTagValue(e.getValue()));
        }
        return t;
    }

    private static Tag toTagValue(Object v) {
        if (v instanceof FfData d) {
            return toTag(d);
        }
        if (v instanceof Long l) {
            return LongTag.valueOf(l);
        }
        if (v instanceof Double dv) {
            return DoubleTag.valueOf(dv);
        }
        if (v instanceof Boolean b) {
            return ByteTag.valueOf(b);
        }
        if (v instanceof String s) {
            return StringTag.valueOf(s);
        }
        if (v instanceof List<?> l) {
            ListTag lt = new ListTag();
            for (Object o : l) {
                if (o instanceof FfData d) {
                    lt.add(toTag(d));
                } else {
                    CompoundTag w = new CompoundTag();
                    w.put(WRAP, toTagValue(o));
                    lt.add(w);
                }
            }
            return lt;
        }
        throw new IllegalArgumentException("unsupported " + v);
    }

    /** Bounded decode; depth beyond FfData.MAX_DEPTH is dropped. */
    public static FfData fromTag(CompoundTag t) {
        return fromTag(t, 0);
    }

    private static FfData fromTag(CompoundTag t, int depth) {
        FfData d = new FfData();
        if (depth > FfData.MAX_DEPTH) {
            return d;
        }
        for (String k : t.getAllKeys()) {
            Object v = fromTagValue(t.get(k), depth);
            if (v != null) {
                d.putAny(k, v);
            }
        }
        return d;
    }

    private static Object fromTagValue(Tag tag, int depth) {
        if (tag instanceof CompoundTag c) {
            return fromTag(c, depth + 1);
        }
        if (tag instanceof ByteTag b) {
            return b.getAsByte() != 0;
        }
        if (tag instanceof DoubleTag dt) {
            return Double.isFinite(dt.getAsDouble()) ? dt.getAsDouble() : null;
        }
        if (tag instanceof LongTag || tag instanceof IntTag || tag instanceof NumericTag) {
            NumericTag n = (NumericTag) tag;
            if (tag instanceof net.minecraft.nbt.FloatTag) {
                return (double) n.getAsFloat();
            }
            return n.getAsLong();
        }
        if (tag instanceof StringTag s) {
            String str = s.getAsString();
            return str.length() > FfData.MAX_STRING ? null : str;
        }
        if (tag instanceof ListTag lt) {
            List<Object> out = new ArrayList<>();
            for (Tag e : lt) {
                if (e instanceof CompoundTag c && c.size() == 1 && c.contains(WRAP)) {
                    Object v = fromTagValue(c.get(WRAP), depth + 1);
                    if (v != null) {
                        out.add(v);
                    }
                } else {
                    Object v = fromTagValue(e, depth + 1);
                    if (v != null) {
                        out.add(v);
                    }
                }
            }
            return out;
        }
        return null;
    }
}
