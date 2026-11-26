package dev.fieldframe.forge.adapter;

import dev.fieldframe.api.id.FfId;
import dev.fieldframe.api.resource.FluidVariant;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.registries.ForgeRegistries;

/** {@link FluidVariant} backed by a FluidStack template (amount ignored). */
public final class McFluidVariant implements FluidVariant {
    private final FluidStack template;
    private final FfId id;
    private final String key;

    private McFluidVariant(FluidStack stack) {
        this.template = new FluidStack(stack, 1);
        var rl = ForgeRegistries.FLUIDS.getKey(stack.getFluid());
        this.id = rl == null ? FfId.of("minecraft", "empty") : FfId.of(rl.getNamespace(), rl.getPath());
        this.key = stack.hasTag() ? stack.getTag().toString() : "";
    }

    public static McFluidVariant of(FluidStack stack) {
        return stack == null || stack.isEmpty() ? null : new McFluidVariant(stack);
    }

    public FluidStack toStack(int amount) {
        return new FluidStack(template, amount);
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
        return key;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof FluidVariant v && sameAs(v);
    }

    @Override
    public int hashCode() {
        return id.hashCode() * 31 + key.hashCode();
    }

    @Override
    public String toString() {
        return id.toString();
    }
}
