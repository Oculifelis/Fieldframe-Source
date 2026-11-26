package dev.fieldframe.api.module;

import dev.fieldframe.api.data.FfData;
import dev.fieldframe.api.resource.FluidFilter;
import dev.fieldframe.api.resource.ItemFilter;

import java.util.List;

/**
 * One player-editable configuration field. All client edits are validated against this schema on the
 * server; out-of-range values are clamped or rejected, never trusted.
 */
public record ConfigField(String key, Type type, long min, long max, long defaultNumber, List<String> options, String defaultText) {
    public enum Type {
        BOOL, INT, ENUM, ITEM_FILTER, FLUID_FILTER, TEXT
    }

    public static final int MAX_TEXT = 4096;

    public ConfigField {
        if (key == null || !key.matches("[a-z][a-z0-9_]{0,31}")) {
            throw new IllegalArgumentException("config key: " + key);
        }
        options = options == null ? List.of() : List.copyOf(options);
        if (type == Type.ENUM && options.isEmpty()) {
            throw new IllegalArgumentException("enum field needs options: " + key);
        }
        if (min > max) {
            throw new IllegalArgumentException("min > max for " + key);
        }
        defaultText = defaultText == null ? "" : defaultText;
    }

    public static ConfigField bool(String key, boolean def) {
        return new ConfigField(key, Type.BOOL, 0, 1, def ? 1 : 0, null, null);
    }

    public static ConfigField integer(String key, long min, long max, long def) {
        return new ConfigField(key, Type.INT, min, max, def, null, null);
    }

    public static ConfigField choice(String key, String def, String... options) {
        List<String> o = List.of(options);
        int idx = o.indexOf(def);
        if (idx < 0) {
            throw new IllegalArgumentException("default not in options: " + key);
        }
        return new ConfigField(key, Type.ENUM, 0, o.size() - 1, idx, o, def);
    }

    public static ConfigField itemFilter(String key) {
        return new ConfigField(key, Type.ITEM_FILTER, 0, 0, 0, null, null);
    }

    public static ConfigField fluidFilter(String key) {
        return new ConfigField(key, Type.FLUID_FILTER, 0, 0, 0, null, null);
    }

    public static ConfigField text(String key, int maxLen, String def) {
        return new ConfigField(key, Type.TEXT, 0, Math.min(maxLen, MAX_TEXT), 0, null, def);
    }

    /** Writes the default value into {@code out}. */
    public void writeDefault(FfData out) {
        switch (type) {
            case BOOL -> out.putBool(key, defaultNumber != 0);
            case INT -> out.putLong(key, defaultNumber);
            case ENUM -> out.putString(key, defaultText);
            case ITEM_FILTER -> out.put(key, ItemFilter.ANY.toData());
            case FLUID_FILTER -> out.put(key, FluidFilter.ANY.toData());
            case TEXT -> out.putString(key, defaultText);
        }
    }

    /**
     * Copies a validated value from untrusted {@code in} to {@code out}. Returns false if the value was
     * missing or invalid (the default is written instead).
     */
    public boolean sanitize(FfData in, FfData out) {
        if (!in.has(key)) {
            writeDefault(out);
            return false;
        }
        Object raw = in.getRaw(key);
        switch (type) {
            case BOOL -> {
                if (raw instanceof Boolean b) {
                    out.putBool(key, b);
                    return true;
                }
            }
            case INT -> {
                if (raw instanceof Long || raw instanceof Double) {
                    long v = in.getLong(key, defaultNumber);
                    out.putLong(key, Math.max(min, Math.min(max, v)));
                    return v >= min && v <= max;
                }
            }
            case ENUM -> {
                if (raw instanceof String s && options.contains(s)) {
                    out.putString(key, s);
                    return true;
                }
            }
            case ITEM_FILTER -> {
                if (raw instanceof FfData d) {
                    out.put(key, ItemFilter.fromData(d).toData());
                    return true;
                }
            }
            case FLUID_FILTER -> {
                if (raw instanceof FfData d) {
                    out.put(key, FluidFilter.fromData(d).toData());
                    return true;
                }
            }
            case TEXT -> {
                if (raw instanceof String s && s.length() <= max) {
                    out.putString(key, s);
                    return true;
                }
            }
        }
        writeDefault(out);
        return false;
    }
}
