package dev.fieldframe.forge.feature;

import dev.fieldframe.api.energy.Energy;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.capabilities.ICapabilityProvider;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.energy.IEnergyStorage;

/**
 * RF stored on an item stack (tag {@code fieldframe.energy}), exposed through the Forge energy capability so any
 * charger (Fieldframe's Charge Pad or another mod's) can fill it. {@code capacity < 0} means creative (infinite).
 */
public final class ItemEnergy {
    private ItemEnergy() {
    }

    /** A copy of the stack's {@code fieldframe} compound (empty if none). */
    public static CompoundTag data(ItemStack s) {
        CompoundTag t = s.getTagElement("fieldframe");
        return t == null ? new CompoundTag() : t.copy();
    }

    public static void editData(ItemStack s, java.util.function.Consumer<CompoundTag> edit) {
        edit.accept(s.getOrCreateTagElement("fieldframe"));
    }

    public static long get(ItemStack s) {
        CompoundTag t = s.getTagElement("fieldframe");
        return t == null ? 0 : Math.max(0, t.getLong("energy"));
    }

    public static void set(ItemStack s, long v) {
        s.getOrCreateTagElement("fieldframe").putLong("energy", Math.max(0, v));
    }

    /** Takes exactly {@code amount} or nothing; creative stacks always pay. */
    public static boolean consume(ItemStack s, long amount, long capacity) {
        if (capacity < 0) {
            return true;
        }
        long have = get(s);
        if (have < amount) {
            return false;
        }
        set(s, have - amount);
        return true;
    }

    public static ICapabilityProvider provider(ItemStack stack, long capacity, long receivePerTick) {
        return provider(stack, () -> capacity, receivePerTick);
    }

    /** As above with a capacity read on each call (the Field Kit's room changes as its saved RF does). */
    public static ICapabilityProvider provider(ItemStack stack, java.util.function.LongSupplier capacityOf, long receivePerTick) {
        IEnergyStorage storage = new IEnergyStorage() {
            @Override
            public int receiveEnergy(int max, boolean simulate) {
                if (capacityOf.getAsLong() < 0 || max <= 0) {
                    return 0;
                }
                long n = Math.min(Math.min(max, receivePerTick), capacityOf.getAsLong() - get(stack));
                if (n <= 0) {
                    return 0;
                }
                if (!simulate) {
                    set(stack, get(stack) + n);
                }
                return (int) n;
            }

            @Override
            public int extractEnergy(int max, boolean simulate) {
                return 0;
            }

            @Override
            public int getEnergyStored() {
                return capacityOf.getAsLong() < 0 ? Integer.MAX_VALUE : Energy.clampToInt(get(stack));
            }

            @Override
            public int getMaxEnergyStored() {
                return capacityOf.getAsLong() < 0 ? Integer.MAX_VALUE : Energy.clampToInt(capacityOf.getAsLong());
            }

            @Override
            public boolean canExtract() {
                return false;
            }

            @Override
            public boolean canReceive() {
                return capacityOf.getAsLong() >= 0;
            }
        };
        LazyOptional<IEnergyStorage> opt = LazyOptional.of(() -> storage);
        return new ICapabilityProvider() {
            @Override
            public <T> LazyOptional<T> getCapability(Capability<T> cap, Direction side) {
                return cap == ForgeCapabilities.ENERGY ? opt.cast() : LazyOptional.empty();
            }
        };
    }
}
