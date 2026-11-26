package dev.fieldframe.forge.compat.jei;

import dev.fieldframe.api.id.FfId;
import dev.fieldframe.core.material.Fuels;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** One engine fuel from the fuels data, resolved to the items or fluids it matches (tags expanded). */
record FuelView(FfId engine, boolean fluid, List<ItemStack> items, List<Fluid> fluids, long rf) {
    static List<FuelView> all() {
        List<FuelView> out = new ArrayList<>();
        for (Fuels.Entry e : Fuels.all()) {
            boolean tag = e.key().startsWith("#");
            ResourceLocation id = new ResourceLocation(tag ? e.key().substring(1) : e.key());
            if (e.fluid()) {
                List<Fluid> fl = new ArrayList<>();
                if (tag) {
                    TagKey<Fluid> key = TagKey.create(Registries.FLUID, id);
                    BuiltInRegistries.FLUID.stream().filter(f -> f != Fluids.EMPTY && f.isSource(f.defaultFluidState())
                            && f.defaultFluidState().is(key)).forEach(fl::add);
                } else {
                    BuiltInRegistries.FLUID.getOptional(id).ifPresent(fl::add);
                }
                if (!fl.isEmpty()) {
                    out.add(new FuelView(e.engine(), true, List.of(), fl, e.rf()));
                }
            } else {
                List<ItemStack> st = new ArrayList<>();
                if (tag) {
                    TagKey<Item> key = TagKey.create(Registries.ITEM, id);
                    BuiltInRegistries.ITEM.stream().filter(i -> i != Items.AIR && new ItemStack(i).is(key)).forEach(i -> st.add(new ItemStack(i)));
                } else {
                    BuiltInRegistries.ITEM.getOptional(id).filter(i -> i != Items.AIR).ifPresent(i -> st.add(new ItemStack(i)));
                }
                if (!st.isEmpty()) {
                    out.add(new FuelView(e.engine(), false, st, List.of(), e.rf()));
                }
            }
        }
        return out;
    }

    static Set<FfId> engines() {
        Set<FfId> out = new LinkedHashSet<>();
        out.add(dev.fieldframe.core.modules.BuiltinModules.FLUID_ENGINE);
        out.add(dev.fieldframe.core.modules.BuiltinModules.GEM_ENGINE);
        out.add(dev.fieldframe.core.modules.BuiltinModules.REDSTONE_ENGINE);
        Fuels.all().forEach(e -> out.add(e.engine()));
        return out;
    }
}
