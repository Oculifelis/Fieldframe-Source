package dev.fieldframe.neoforge.adapter;

import dev.fieldframe.api.id.FfId;
import dev.fieldframe.api.resource.FluidVariant;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.registries.BuiltInRegistries;
import net.neoforged.neoforge.fluids.FluidStack;

/** {@link FluidVariant} backed by a FluidStack template (amount ignored). */
public final class McFluidVariant implements FluidVariant {
    private final FluidStack template;
    private final FfId id;
    private String key;

    private McFluidVariant(FluidStack stack) {
        this.template = stack.copyWithAmount(1);
        var rl = BuiltInRegistries.FLUID.getKey(stack.getFluid());
        this.id = FfId.of(rl.getNamespace(), rl.getPath());
    }

    public static McFluidVariant of(FluidStack stack) {
        return stack == null || stack.isEmpty() ? null : new McFluidVariant(stack);
    }

    public FluidStack toStack(int amount) {
        return template.copyWithAmount(amount);
    }

    public static FluidStack toStack(FluidVariant v, long amount) {
        if (v instanceof McFluidVariant m && amount > 0) {
            return m.toStack((int) Math.min(Integer.MAX_VALUE, amount));
        }
        return FluidStack.EMPTY;
    }

    @Override
    public FfId id() {
        return id;
    }

    @Override
    public String componentKey() {
        if (key == null) {
            DataComponentPatch patch = template.getComponentsPatch();
            var ops = RegistryHolder.nbtOps();
            key = patch.isEmpty() ? "" : ops == null ? patch.toString()
                    : DataComponentPatch.CODEC.encodeStart(ops, patch).result().map(Object::toString).orElse(patch.toString());
        }
        return key;
    }

    @Override
    public boolean sameAs(FluidVariant other) {
        if (other instanceof McFluidVariant m) {
            return FluidStack.isSameFluidSameComponents(template, m.template);
        }
        return other != null && id.equals(other.id()) && componentKey().equals(other.componentKey());
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof FluidVariant v && sameAs(v);
    }

    @Override
    public int hashCode() {
        return FluidStack.hashFluidAndComponents(template);
    }

    @Override
    public String toString() {
        return id.toString();
    }
}
