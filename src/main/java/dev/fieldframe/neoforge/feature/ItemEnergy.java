package dev.fieldframe.neoforge.feature;

import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

import java.util.function.Consumer;

/**
 * Fieldframe data on item stacks (the {@code fieldframe} compound inside {@code minecraft:custom_data}) and RF stored
 * in the {@code fieldframe:energy} component, which NeoForge's ItemAccessEnergyHandler exposes as the item energy
 * capability. {@code capacity < 0} means creative (infinite).
 */
public final class ItemEnergy {
    private ItemEnergy() {
    }

    public static CompoundTag data(ItemStack s) {
        CustomData cd = s.get(DataComponents.CUSTOM_DATA);
        if (cd == null) {
            return new CompoundTag();
        }
        return cd.copyTag().getCompound("fieldframe").orElseGet(CompoundTag::new);
    }

    public static void editData(ItemStack s, Consumer<CompoundTag> edit) {
        CustomData.update(DataComponents.CUSTOM_DATA, s, root -> {
            CompoundTag t = root.getCompound("fieldframe").orElseGet(CompoundTag::new);
            edit.accept(t);
            root.put("fieldframe", t);
        });
    }

    public static long get(ItemStack s) {
        Integer v = s.get(FeatureContent.ENERGY.get());
        return v == null ? 0 : Math.max(0, v);
    }

    public static void set(ItemStack s, long v) {
        s.set(FeatureContent.ENERGY.get(), (int) Math.max(0, Math.min(Integer.MAX_VALUE, v)));
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
}
