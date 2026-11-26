package dev.fieldframe.api.resource;

import dev.fieldframe.api.id.FfId;

/** Platform-provided tag membership. */
public interface TagLookup {
    boolean itemInTag(ItemVariant item, FfId tag);

    boolean fluidInTag(FluidVariant fluid, FfId tag);

    /** Whether the block with this id is in the block tag. Default false (platforms override). */
    default boolean blockInTag(FfId block, FfId tag) {
        return false;
    }

    TagLookup NONE = new TagLookup() {
        @Override
        public boolean itemInTag(ItemVariant item, FfId tag) {
            return false;
        }

        @Override
        public boolean fluidInTag(FluidVariant fluid, FfId tag) {
            return false;
        }
    };
}
