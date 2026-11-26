package dev.fieldframe.neoforge.adapter;

import dev.fieldframe.api.face.WorldDir;
import dev.fieldframe.api.id.FfId;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;

/** Conversions between Fieldframe and Minecraft value types. WorldDir mirrors Direction's ordinal order. */
public final class Dirs {
    private Dirs() {
    }

    public static WorldDir toWorld(Direction d) {
        return d == null ? null : WorldDir.values()[d.get3DDataValue()];
    }

    public static Direction toMc(WorldDir d) {
        return Direction.from3DDataValue(d.ordinal());
    }

    public static ResourceLocation rl(FfId id) {
        return ResourceLocation.fromNamespaceAndPath(id.namespace(), id.path());
    }

    public static FfId id(ResourceLocation rl) {
        return FfId.of(rl.getNamespace(), rl.getPath());
    }
}
