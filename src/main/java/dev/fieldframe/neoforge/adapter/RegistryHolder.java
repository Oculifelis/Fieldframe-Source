package dev.fieldframe.neoforge.adapter;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.RegistryOps;

/**
 * Registry access needed to encode data components (1.21+ item/fluid identity). Set when a server starts and
 * when block entities load (which receive a provider), and on the client when joining a world.
 */
public final class RegistryHolder {
    private static volatile HolderLookup.Provider provider;

    private RegistryHolder() {
    }

    public static void set(HolderLookup.Provider p) {
        if (p != null) {
            provider = p;
        }
    }

    public static HolderLookup.Provider get() {
        return provider;
    }

    public static RegistryOps<Tag> nbtOps() {
        HolderLookup.Provider p = provider;
        return p == null ? null : p.createSerializationContext(NbtOps.INSTANCE);
    }
}
