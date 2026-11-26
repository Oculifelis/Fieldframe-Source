package dev.fieldframe.neoforge.adapter;

import dev.fieldframe.api.data.FfData;
import dev.fieldframe.api.id.FfId;
import dev.fieldframe.api.resource.FluidVariant;
import dev.fieldframe.api.resource.ItemVariant;
import dev.fieldframe.api.resource.VariantCodec;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.TagParser;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.fluids.FluidStack;

/**
 * NeoForge 1.21.1 codec: id + component patch (registry-aware NBT string). Unknown items or unreadable components
 * decode to a {@link Missing} placeholder that keeps its raw data and re-encodes identically.
 */
public final class McVariantCodec implements VariantCodec {
    public static final McVariantCodec INSTANCE = new McVariantCodec();

    public record Missing(FfId id, FfData raw) implements ItemVariant {
        @Override
        public String componentKey() {
            return "missing:" + raw.getString("components", "");
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
        ResourceLocation rl = ResourceLocation.fromNamespaceAndPath(id.namespace(), id.path());
        if (!BuiltInRegistries.ITEM.containsKey(rl)) {
            return new Missing(id, d.copy().remove("slot").remove("count"));
        }
        ItemStack stack = new ItemStack(BuiltInRegistries.ITEM.get(rl));
        String comps = d.getString("components", "");
        if (!comps.isEmpty()) {
            var ops = RegistryHolder.nbtOps();
            try {
                if (ops == null) {
                    throw new IllegalStateException("no registry access");
                }
                DataComponentPatch patch = DataComponentPatch.CODEC.parse(ops, TagParser.parseTag(comps)).getOrThrow();
                stack.applyComponents(patch);
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
            d.putString("components", v.componentKey());
        }
        return d;
    }

    @Override
    public FluidVariant decodeFluid(FfData d) {
        FfId id = FfId.tryParse(d.getString("id", null));
        if (id == null) {
            return null;
        }
        ResourceLocation rl = ResourceLocation.fromNamespaceAndPath(id.namespace(), id.path());
        if (!BuiltInRegistries.FLUID.containsKey(rl)) {
            return new FluidVariant.Simple(id, d.getString("components", ""));
        }
        FluidStack fs = new FluidStack(BuiltInRegistries.FLUID.get(rl), 1);
        String comps = d.getString("components", "");
        if (!comps.isEmpty()) {
            var ops = RegistryHolder.nbtOps();
            try {
                if (ops == null) {
                    throw new IllegalStateException("no registry access");
                }
                fs.applyComponents(DataComponentPatch.CODEC.parse(ops, TagParser.parseTag(comps)).getOrThrow());
            } catch (Exception e) {
                return new FluidVariant.Simple(id, comps);
            }
        }
        return McFluidVariant.of(fs);
    }
}
