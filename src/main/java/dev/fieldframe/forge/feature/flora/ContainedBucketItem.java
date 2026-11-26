package dev.fieldframe.forge.feature.flora;

import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.material.Fluid;

import java.util.function.Supplier;

/**
 * Bucket of a Fieldframe fluid: pours a source block into the world like a water bucket, picks it back up, and fills
 * and empties tanks through the fluid-container capability.
 */
public final class ContainedBucketItem extends BucketItem {
    public ContainedBucketItem(Supplier<? extends Fluid> fluid, Properties p) {
        super(fluid, p);
    }

    @Override
    public net.minecraftforge.common.capabilities.ICapabilityProvider initCapabilities(ItemStack stack, net.minecraft.nbt.CompoundTag nbt) {
        return new net.minecraftforge.fluids.capability.wrappers.FluidBucketWrapper(stack);
    }
}
