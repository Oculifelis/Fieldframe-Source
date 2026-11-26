package dev.fieldframe.forge.adapter;

import dev.fieldframe.api.id.FfId;
import dev.fieldframe.api.resource.ItemVariant;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * An {@link ItemVariant} backed by a one-item {@link ItemStack} template. The component key is the canonical
 * SNBT of the stack's tag (CompoundTag string output sorts keys), so equality matches {@code ItemStack.isSameItemSameTags}.
 */
public final class McItemVariant implements ItemVariant {
    private final ItemStack template;
    private final FfId id;
    private final String key;

    private McItemVariant(ItemStack stack) {
        this.template = stack.copyWithCount(1);
        var rl = ForgeRegistries.ITEMS.getKey(stack.getItem());
        this.id = rl == null ? FfId.of("minecraft", "air") : FfId.of(rl.getNamespace(), rl.getPath());
        this.key = stack.hasTag() ? stack.getTag().toString() : "";
    }

    /** Returns null for an empty stack. */
    public static McItemVariant of(ItemStack stack) {
        return stack == null || stack.isEmpty() ? null : new McItemVariant(stack);
    }

    /** An ItemStack of this variant with the given count (never mutates the template). */
    public ItemStack toStack(int count) {
        return template.copyWithCount(count);
    }

    public static ItemStack toStack(ItemVariant v, int count) {
        if (v instanceof McItemVariant m) {
            return m.toStack(count);
        }
        return ItemStack.EMPTY;
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
    public int maxStackSize() {
        return Math.max(1, Math.min(99, template.getMaxStackSize()));
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof ItemVariant v && sameAs(v);
    }

    @Override
    public int hashCode() {
        return id.hashCode() * 31 + key.hashCode();
    }

    @Override
    public String toString() {
        return id + (key.isEmpty() ? "" : key);
    }
}
