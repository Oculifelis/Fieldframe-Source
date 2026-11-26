package dev.fieldframe.api.module;

/**
 * A pack-tunable numeric parameter of a module definition (e.g. {@code energy_per_tick}). Datapacks and
 * KubeJS may override the value within [{@code min}, {@code max}], which is the server-controlled safety bound.
 */
public record TunableSpec(String key, double min, double max, double defaultValue, String unit) {
    public TunableSpec {
        if (key == null || !key.matches("[a-z][a-z0-9_]{0,47}")) {
            throw new IllegalArgumentException("tunable key: " + key);
        }
        if (!(min <= defaultValue && defaultValue <= max) || !Double.isFinite(min) || !Double.isFinite(max)) {
            throw new IllegalArgumentException("tunable bounds for " + key);
        }
        unit = unit == null ? "" : unit;
    }

    public static TunableSpec of(String key, double min, double max, double def, String unit) {
        return new TunableSpec(key, min, max, def, unit);
    }

    public double clamp(double v) {
        if (!Double.isFinite(v)) {
            return defaultValue;
        }
        return Math.max(min, Math.min(max, v));
    }
}
