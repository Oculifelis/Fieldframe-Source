package dev.fieldframe.neoforge.compat.jei;

import dev.fieldframe.api.id.FfId;
import dev.fieldframe.core.material.Auras;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;

/** One Aura Chamber recipe (datapack or mirrored from Metallurgy), resolved to the item stacks it accepts. */
record AuraView(List<ItemStack> items, FfId effect, int duration, int amplifier) {
    static List<AuraView> all() {
        List<AuraView> out = new ArrayList<>();
        for (Auras.Entry e : Auras.all()) {
            boolean tag = e.ingredient().startsWith("#");
            ResourceLocation id = ResourceLocation.parse(tag ? e.ingredient().substring(1) : e.ingredient());
            List<ItemStack> st = new ArrayList<>();
            if (tag) {
                TagKey<Item> key = TagKey.create(Registries.ITEM, id);
                BuiltInRegistries.ITEM.stream().filter(i -> i != Items.AIR && new ItemStack(i).is(key)).forEach(i -> st.add(new ItemStack(i, e.count())));
            } else {
                BuiltInRegistries.ITEM.getOptional(id).filter(i -> i != Items.AIR).ifPresent(i -> st.add(new ItemStack(i, e.count())));
            }
            if (!st.isEmpty()) {
                out.add(new AuraView(st, e.effect(), e.duration(), e.amplifier()));
            }
        }
        return out;
    }
}
