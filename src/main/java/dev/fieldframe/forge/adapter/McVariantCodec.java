package dev.fieldframe.forge.adapter;

import dev.fieldframe.api.data.FfData;
import dev.fieldframe.api.id.FfId;
import dev.fieldframe.api.resource.FluidVariant;
import dev.fieldframe.api.resource.ItemVariant;
import dev.fieldframe.api.resource.VariantCodec;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.material.Fluid;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * Forge 1.20.1 codec. Items whose id no longer exists decode to a {@link Missing} placeholder that keeps the raw
 * data and re-encodes identically, so removing a content mod never silently deletes stored items.
 */
public final class McVariantCodec implements VariantCodec {
    public static final McVariantCodec INSTANCE = new McVariantCodec();

    /** A stored item whose definition is currently unavailable. It cannot leave the chassis until restored. */
    public record Missing(FfId id, FfData raw) implements ItemVariant {
        @Override
        public String componentKey() {
            return "missing:" + raw.getString("nbt", "");
        }

        @Override
        public int maxStackSize() {
            return 64;
        }
    }

    @Override
    public FfData encodeItem(ItemVariant v) {
        if (v instanceof Missing m) {
            return m.raw().copy();
        }
        FfData d = new FfData().putString("id", v.id().toString());
        if (!v.componentKey().isEmpty()) {
            d.putString("nbt", v.componentKey());
        }
        return d;
    }

    @Override
    public ItemVariant decodeItem(FfData d) {
        FfId id = FfId.tryParse(d.getString("id", null));
        if (id == null) {
            return null;
        }
        ResourceLocation rl = new ResourceLocation(id.namespace(), id.path());
        Item item = ForgeRegistries.ITEMS.containsKey(rl) ? ForgeRegistries.ITEMS.getValue(rl) : null;
        if (item == null) {
            return new Missing(id, d.copy().remove("slot").remove("count"));
        }
        ItemStack stack = new ItemStack(item);
        String nbt = d.getString("nbt", "");
        if (!nbt.isEmpty()) {
            try {
                CompoundTag tag = TagParser.parseTag(nbt);
                stack.setTag(tag);
            } catch (Exception e) {
                return new Missing(id, d.copy().remove("slot").remove("count"));
            }
        }
        return McItemVariant.of(stack);
    }

    @Override
    public FfData encodeFluid(FluidVariant v) {
        FfData d = new FfData().putString("id", v.id().toString());
        if (!v.componentKey().isEmpty()) {
            d.putString("nbt", v.componentKey());
        }
        return d;
    }

    @Override
    public FluidVariant decodeFluid(FfData d) {
        FfId id = FfId.tryParse(d.getString("id", null));
        if (id == null) {
            return null;
        }
        ResourceLocation rl = new ResourceLocation(id.namespace(), id.path());
        Fluid fluid = ForgeRegistries.FLUIDS.containsKey(rl) ? ForgeRegistries.FLUIDS.getValue(rl) : null;
        if (fluid == null) {
            // Unknown fluids keep their identity as a simple variant; they can be read but not exported.
            return new FluidVariant.Simple(id, d.getString("nbt", ""));
        }
        FluidStack fs = new FluidStack(fluid, 1);
        String nbt = d.getString("nbt", "");
        if (!nbt.isEmpty()) {
            try {
                fs.setTag(TagParser.parseTag(nbt));
            } catch (Exception e) {
                return new FluidVariant.Simple(id, nbt);
            }
        }
        return McFluidVariant.of(fs);
    }
}
