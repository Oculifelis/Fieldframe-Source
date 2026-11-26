package dev.fieldframe.api.resource;

/**
 * Holder for the platform's active {@link VariantCodec}, used by behaviors that persist resource identities
 * in their own state (e.g. reserved processor inputs). Set once by the platform during common setup.
 */
public final class VariantCodecs {
    private static volatile VariantCodec active = VariantCodec.SIMPLE;

    private VariantCodecs() {
    }

    public static VariantCodec active() {
        return active;
    }

    public static void install(VariantCodec codec) {
        active = codec == null ? VariantCodec.SIMPLE : codec;
    }
}
