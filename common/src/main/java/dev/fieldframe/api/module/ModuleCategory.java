package dev.fieldframe.api.module;

/** Functional families, used for manual grouping, face rules and progression. */
public enum ModuleCategory {
    HANDLING, CONTROL, PROCESSING, GENERATION, WORLD, DISPLAY;

    public String key() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }
}
