package dev.fieldframe.frameworks;

import dev.fieldframe.api.data.FfData;
import dev.fieldframe.api.data.FfJson;
import net.minecraft.nbt.CompoundTag;

/** Small helper: the addon's own state (FfData) inside block entity data, as JSON text. */
final class FwData {
    private FwData() {
    }

    static CompoundTag toTag(FfData d) {
        CompoundTag t = new CompoundTag();
        t.putString("json", FfJson.toJsonString(d));
        return t;
    }

    static FfData fromTag(CompoundTag t) {
        return FfJson.parse(t.getStringOr("json", "{}"));
    }
}
