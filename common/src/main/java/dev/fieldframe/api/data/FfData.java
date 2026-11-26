package dev.fieldframe.api.data;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A small, explicit, loader-independent structured value tree used for persistent module state,
 * configuration, blueprints and addon state. Allowed values: {@code Long}, {@code Double}, {@code String},
 * {@code Boolean}, nested {@code FfData}, and {@code List} of those. Platform adapters convert this to
 * NBT; blueprint/JSON codecs convert to JSON. Arbitrary Java objects are rejected.
 */
public final class FfData {
    public static final int MAX_DEPTH = 16;
    public static final int MAX_STRING = 32_768;

    private final LinkedHashMap<String, Object> values = new LinkedHashMap<>();

    public boolean has(String key) {
        return values.containsKey(key);
    }

    public Set<String> keys() {
        return Collections.unmodifiableSet(values.keySet());
    }

    public int size() {
        return values.size();
    }

    public boolean isEmpty() {
        return values.isEmpty();
    }

    public Object getRaw(String key) {
        return values.get(key);
    }

    public FfData remove(String key) {
        values.remove(key);
        return this;
    }

    public FfData putLong(String key, long v) {
        values.put(key, v);
        return this;
    }

    public FfData putInt(String key, int v) {
        values.put(key, (long) v);
        return this;
    }

    public FfData putDouble(String key, double v) {
        if (!Double.isFinite(v)) {
            throw new IllegalArgumentException("Non-finite double for " + key);
        }
        values.put(key, v);
        return this;
    }

    public FfData putBool(String key, boolean v) {
        values.put(key, v);
        return this;
    }

    public FfData putString(String key, String v) {
        if (v == null) {
            values.remove(key);
            return this;
        }
        if (v.length() > MAX_STRING) {
            throw new IllegalArgumentException("String too long for " + key);
        }
        values.put(key, v);
        return this;
    }

    public FfData put(String key, FfData v) {
        if (v == this) {
            throw new IllegalArgumentException("self reference");
        }
        values.put(key, v);
        return this;
    }

    public FfData putList(String key, List<?> list) {
        List<Object> copy = new ArrayList<>(list.size());
        for (Object o : list) {
            copy.add(checkValue(o));
        }
        values.put(key, copy);
        return this;
    }

    /** Puts any allowed value type; throws for anything else. */
    public FfData putAny(String key, Object v) {
        values.put(key, checkValue(v));
        return this;
    }

    private static Object checkValue(Object o) {
        if (o instanceof Integer i) {
            return (long) i;
        }
        if (o instanceof Long || o instanceof Boolean || o instanceof FfData) {
            return o;
        }
        if (o instanceof Double d) {
            if (!Double.isFinite(d)) {
                throw new IllegalArgumentException("non-finite");
            }
            return d;
        }
        if (o instanceof String s) {
            if (s.length() > MAX_STRING) {
                throw new IllegalArgumentException("string too long");
            }
            return s;
        }
        if (o instanceof List<?> l) {
            List<Object> copy = new ArrayList<>(l.size());
            for (Object e : l) {
                copy.add(checkValue(e));
            }
            return copy;
        }
        throw new IllegalArgumentException("Unsupported FfData value type: " + (o == null ? "null" : o.getClass().getName()));
    }

    public long getLong(String key, long def) {
        Object o = values.get(key);
        if (o instanceof Long l) {
            return l;
        }
        if (o instanceof Double d) {
            return (long) (double) d;
        }
        return def;
    }

    /** Reads an int, clamping out-of-range stored values instead of wrapping. */
    public int getInt(String key, int def) {
        Object o = values.get(key);
        if (o instanceof Long l) {
            return (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, l));
        }
        if (o instanceof Double d) {
            return (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, d));
        }
        return def;
    }

    public int getIntClamped(String key, int def, int min, int max) {
        return Math.max(min, Math.min(max, getInt(key, def)));
    }

    public double getDouble(String key, double def) {
        Object o = values.get(key);
        if (o instanceof Double d) {
            return d;
        }
        if (o instanceof Long l) {
            return l;
        }
        return def;
    }

    public boolean getBool(String key, boolean def) {
        Object o = values.get(key);
        return o instanceof Boolean b ? b : def;
    }

    public String getString(String key, String def) {
        Object o = values.get(key);
        return o instanceof String s ? s : def;
    }

    /** Returns the child, or an empty detached tree when absent. */
    public FfData getData(String key) {
        Object o = values.get(key);
        return o instanceof FfData d ? d : new FfData();
    }

    public FfData getDataOrNull(String key) {
        Object o = values.get(key);
        return o instanceof FfData d ? d : null;
    }

    /** Returns the list (read-only), or an empty list when absent. */
    @SuppressWarnings("unchecked")
    public List<Object> getList(String key) {
        Object o = values.get(key);
        return o instanceof List<?> l ? Collections.unmodifiableList((List<Object>) l) : List.of();
    }

    public Map<String, Object> asMap() {
        return Collections.unmodifiableMap(values);
    }

    public FfData copy() {
        FfData c = new FfData();
        for (Map.Entry<String, Object> e : values.entrySet()) {
            c.values.put(e.getKey(), deepCopy(e.getValue()));
        }
        return c;
    }

    private static Object deepCopy(Object o) {
        if (o instanceof FfData d) {
            return d.copy();
        }
        if (o instanceof List<?> l) {
            List<Object> c = new ArrayList<>(l.size());
            for (Object e : l) {
                c.add(deepCopy(e));
            }
            return c;
        }
        return o;
    }

    /** Depth of the tree, used by bounded decoders. */
    public int depth() {
        int max = 0;
        for (Object o : values.values()) {
            max = Math.max(max, depthOf(o));
        }
        return 1 + max;
    }

    private static int depthOf(Object o) {
        if (o instanceof FfData d) {
            return d.depth();
        }
        if (o instanceof List<?> l) {
            int m = 0;
            for (Object e : l) {
                m = Math.max(m, depthOf(e));
            }
            return 1 + m;
        }
        return 0;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof FfData d && values.equals(d.values);
    }

    @Override
    public int hashCode() {
        return values.hashCode();
    }

    @Override
    public String toString() {
        return values.toString();
    }
}
