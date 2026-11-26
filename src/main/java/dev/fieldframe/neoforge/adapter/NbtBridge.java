package dev.fieldframe.neoforge.adapter;

import dev.fieldframe.api.data.FfData;
import net.minecraft.nbt.ByteTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongTag;
import net.minecraft.nbt.NumericTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;

import java.util.ArrayList;
import java.util.List;

/**
 * Lossless FfData &lt;-&gt; NBT conversion for 26.1 (record tag types). Booleans use ByteTag; non-compound list
 * elements are wrapped as {@code {"$v": value}} so ListTag stays homogeneous.
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

    public static FfData fromTag(CompoundTag t) {
        return fromTag(t, 0);
    }

    private static FfData fromTag(CompoundTag t, int depth) {
        FfData d = new FfData();
        if (depth > FfData.MAX_DEPTH) {
            return d;
        }
        for (String k : t.keySet()) {
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
            return b.value() != 0;
        }
        if (tag instanceof DoubleTag dt) {
            return Double.isFinite(dt.value()) ? dt.value() : null;
        }
        if (tag instanceof FloatTag ft) {
            return Float.isFinite(ft.value()) ? (double) ft.value() : null;
        }
        if (tag instanceof NumericTag n) {
            return n.longValue();
        }
        if (tag instanceof StringTag s) {
            return s.value().length() > FfData.MAX_STRING ? null : s.value();
        }
        if (tag instanceof ListTag lt) {
            List<Object> out = new ArrayList<>();
            for (Tag e : lt) {
                Object v;
                if (e instanceof CompoundTag c && c.size() == 1 && c.contains(WRAP)) {
                    v = fromTagValue(c.get(WRAP), depth + 1);
                } else {
                    v = fromTagValue(e, depth + 1);
                }
                if (v != null) {
                    out.add(v);
                }
            }
            return out;
        }
        return null;
    }
}
