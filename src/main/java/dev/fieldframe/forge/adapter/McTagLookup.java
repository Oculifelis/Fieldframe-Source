package dev.fieldframe.forge.adapter;

import dev.fieldframe.api.id.FfId;
import dev.fieldframe.api.resource.FluidVariant;
import dev.fieldframe.api.resource.ItemVariant;
import dev.fieldframe.api.resource.TagLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;

/** Tag membership via the current (server-synchronized) tag bindings. */
public final class McTagLookup implements TagLookup {
    public static final McTagLookup INSTANCE = new McTagLookup();

    @Override
    public boolean itemInTag(ItemVariant item, FfId tag) {
        if (!(item instanceof McItemVariant m)) {
            return false;
        }
        return m.toStack(1).is(TagKey.create(Registries.ITEM, new ResourceLocation(tag.namespace(), tag.path())));
    }

    @Override
    public boolean blockInTag(FfId block, FfId tag) {
        var b = net.minecraftforge.registries.ForgeRegistries.BLOCKS.getValue(new ResourceLocation(block.namespace(), block.path()));
        return b != null && b.defaultBlockState().is(TagKey.create(Registries.BLOCK, new ResourceLocation(tag.namespace(), tag.path())));
    }

    @Override
    public boolean fluidInTag(FluidVariant fluid, FfId tag) {
        TagKey<net.minecraft.world.level.material.Fluid> key = TagKey.create(Registries.FLUID, new ResourceLocation(tag.namespace(), tag.path()));
        if (!(fluid instanceof McFluidVariant m)) {
            // A plain id (e.g. a fluid stored on fluid armor): look it up.
            var f = net.minecraft.core.registries.BuiltInRegistries.FLUID.getOptional(new ResourceLocation(fluid.id().namespace(), fluid.id().path()));
            return f.isPresent() && f.get().defaultFluidState().is(key);
        }
        return m.toStack(1).getFluid().is(key);
    }
}
