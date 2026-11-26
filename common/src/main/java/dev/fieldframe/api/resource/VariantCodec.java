package dev.fieldframe.api.resource;

import dev.fieldframe.api.data.FfData;
import dev.fieldframe.api.id.FfId;

/**
 * Converts resource variants to and from {@link FfData}. Provided by each platform adapter; tests use
 * {@link #SIMPLE}. Decoding never returns null for well-formed data: an item that no longer exists must
 * decode to a recoverable placeholder variant that re-encodes to the same data.
 */
public interface VariantCodec {
    FfData encodeItem(ItemVariant v);

    ItemVariant decodeItem(FfData d);

    FfData encodeFluid(FluidVariant v);

    FluidVariant decodeFluid(FfData d);

    VariantCodec SIMPLE = new VariantCodec() {
        @Override
        public FfData encodeItem(ItemVariant v) {
            FfData d = new FfData().putString("id", v.id().toString()).putInt("max", v.maxStackSize());
            if (!v.componentKey().isEmpty()) {
                d.putString("components", v.componentKey());
            }
            return d;
        }

        @Override
        public ItemVariant decodeItem(FfData d) {
            FfId id = FfId.tryParse(d.getString("id", null));
            if (id == null) {
                return null;
            }
            return new ItemVariant.Simple(id, d.getString("components", ""), d.getIntClamped("max", 64, 1, 99));
        }

        @Override
        public FfData encodeFluid(FluidVariant v) {
            FfData d = new FfData().putString("id", v.id().toString());
            if (!v.componentKey().isEmpty()) {
                d.putString("components", v.componentKey());
            }
            return d;
        }

        @Override
        public FluidVariant decodeFluid(FfData d) {
            FfId id = FfId.tryParse(d.getString("id", null));
            return id == null ? null : new FluidVariant.Simple(id, d.getString("components", ""));
        }
    };
}
