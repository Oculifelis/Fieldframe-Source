package dev.fieldframe.neoforge.adapter;

import dev.fieldframe.api.id.FfId;
import dev.fieldframe.api.resource.ItemVariant;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;

/**
 * An {@link ItemVariant} backed by a one-item {@link ItemStack} template (1.21 data components). Identity checks
 * between two Minecraft variants use {@link ItemStack#isSameItemSameComponents}; the component key is a canonical
 * NBT string of the component patch, encoded with registry access, used for persistence and filters.
 */
public final class McItemVariant implements ItemVariant {
    private final ItemStack template;
    private final FfId id;
    private String key;

    private McItemVariant(ItemStack stack) {
        this.template = stack.copyWithCount(1);
        var rl = BuiltInRegistries.ITEM.getKey(stack.getItem());
        this.id = FfId.of(rl.getNamespace(), rl.getPath());
    }

    public static McItemVariant of(ItemStack stack) {
        return stack == null || stack.isEmpty() ? null : new McItemVariant(stack);
    }

    public ItemStack toStack(int count) {
        return template.copyWithCount(count);
    }

    public static ItemStack toStack(ItemVariant v, int count) {
        return v instanceof McItemVariant m ? m.toStack(count) : ItemStack.EMPTY;
    }

    @Override
    public FfId id() {
        return id;
    }

    @Override
    public String componentKey() {
        if (key == null) {
            DataComponentPatch patch = template.getComponentsPatch();
            if (patch.isEmpty()) {
                key = "";
            } else {
                var ops = RegistryHolder.nbtOps();
                key = ops == null ? patch.toString()
                        : DataComponentPatch.CODEC.encodeStart(ops, patch).result().map(Object::toString).orElse(patch.toString());
            }
        }
        return key;
    }

    @Override
    public int maxStackSize() {
        return Math.max(1, Math.min(99, template.getMaxStackSize()));
    }

    @Override
    public boolean sameAs(ItemVariant other) {
        if (other instanceof McItemVariant m) {
            return ItemStack.isSameItemSameComponents(template, m.template);
        }
        return other != null && id.equals(other.id()) && componentKey().equals(other.componentKey());
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof ItemVariant v && sameAs(v);
    }

    @Override
    public int hashCode() {
        return ItemStack.hashItemAndComponents(template);
    }

    @Override
    public String toString() {
        return id.toString();
    }
}
