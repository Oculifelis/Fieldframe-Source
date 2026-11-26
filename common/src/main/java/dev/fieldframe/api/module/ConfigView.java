package dev.fieldframe.api.module;

import dev.fieldframe.api.data.FfData;
import dev.fieldframe.api.resource.FluidFilter;
import dev.fieldframe.api.resource.ItemFilter;

/** Read-only, schema-validated view of an installed module's configuration. */
public final class ConfigView {
    private final FfData data;

    public ConfigView(FfData sanitized) {
        this.data = sanitized;
    }

    public boolean bool(String key) {
        return data.getBool(key, false);
    }

    public long number(String key) {
        return data.getLong(key, 0);
    }

    public int integer(String key) {
        return data.getInt(key, 0);
    }

    public String choice(String key) {
        return data.getString(key, "");
    }

    public String text(String key) {
        return data.getString(key, "");
    }

    public ItemFilter itemFilter(String key) {
        return ItemFilter.fromData(data.getDataOrNull(key));
    }

    public FluidFilter fluidFilter(String key) {
        return FluidFilter.fromData(data.getDataOrNull(key));
    }

    public FfData copy() {
        return data.copy();
    }
}
