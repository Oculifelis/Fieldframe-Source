package dev.fieldframe.neoforge.feature;

import dev.fieldframe.api.energy.Energy;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.neoforged.neoforge.energy.IEnergyStorage;

import java.util.function.Consumer;

/**
 * Fieldframe data on item stacks (the {@code fieldframe} compound inside {@code minecraft:custom_data}) and RF stored
 * there, exposed through the NeoForge item energy capability. {@code capacity < 0} means creative (infinite).
 */
public final class ItemEnergy {
    private ItemEnergy() {
    }

    /** Copy of the stack's {@code fieldframe} compound (empty if none). */
    public static CompoundTag data(ItemStack s) {
        CustomData cd = s.get(DataComponents.CUSTOM_DATA);
        if (cd == null) {
            return new CompoundTag();
        }
        CompoundTag root = cd.copyTag();
        return root.contains("fieldframe") ? root.getCompound("fieldframe") : new CompoundTag();
    }

    public static void editData(ItemStack s, Consumer<CompoundTag> edit) {
        CustomData.update(DataComponents.CUSTOM_DATA, s, root -> {
            CompoundTag t = root.contains("fieldframe") ? root.getCompound("fieldframe") : new CompoundTag();
            edit.accept(t);
            root.put("fieldframe", t);
        });
    }

    public static long get(ItemStack s) {
        return Math.max(0, data(s).getLong("energy"));
    }

    public static void set(ItemStack s, long v) {
        editData(s, t -> t.putLong("energy", Math.max(0, v)));
    }

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

    public static IEnergyStorage storage(ItemStack stack, long capacity, long receivePerTick) {
        return new IEnergyStorage() {
            @Override
            public int receiveEnergy(int max, boolean simulate) {
                if (capacity < 0 || max <= 0) {
                    return 0;
                }
                long n = Math.min(Math.min(max, receivePerTick), capacity - get(stack));
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
                return capacity < 0 ? Integer.MAX_VALUE : Energy.clampToInt(get(stack));
            }

            @Override
            public int getMaxEnergyStored() {
                return capacity < 0 ? Integer.MAX_VALUE : Energy.clampToInt(capacity);
            }

            @Override
            public boolean canExtract() {
                return false;
            }

            @Override
            public boolean canReceive() {
                return capacity >= 0;
            }
        };
    }
}
