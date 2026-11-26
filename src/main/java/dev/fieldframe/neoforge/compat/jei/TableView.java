package dev.fieldframe.neoforge.compat.jei;

import dev.fieldframe.core.material.Electrodes;
import dev.fieldframe.core.material.Temperatures;
import dev.fieldframe.core.material.TreadmillMobs;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.SpawnEggItem;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * One row of a data table shown in JEI (Treadmill animals, Galvanic electrodes, temperatures): what it is (items or
 * fluids, tags expanded), an optional second item (an animal's food) and two lines of text.
 */
record TableView(List<ItemStack> items, List<Fluid> fluids, List<ItemStack> second, Component line1, Component line2) {
    /** Speed used to show what a speed-scaled treadmill animal gives (a horse ranges 0.11 to 0.34). */
    static final double SHOWN_SPEED = 0.25;

    static List<TableView> treadmill() {
        List<TableView> out = new ArrayList<>();
        for (TreadmillMobs.Entry e : TreadmillMobs.entries().values()) {
            var type = BuiltInRegistries.ENTITY_TYPE.getOptional(Identifier.parse(e.entity().toString())).orElse(null);
            if (type == null) {
                continue;
            }
            List<ItemStack> egg = new ArrayList<>();
            SpawnEggItem.byId(type).ifPresent(h -> egg.add(new ItemStack(h.value())));
            Component rf = e.rfPerSpeed() > 0
                    ? Component.translatable("fieldframe.jei.treadmill_speed", fmt(e.rf(SHOWN_SPEED)), fmt(SHOWN_SPEED))
                    : Component.translatable("fieldframe.jei.treadmill_rf", fmt(e.rfPerTick()));
            out.add(new TableView(egg, List.of(), items(e.food()), type.getDescription(), rf));
        }
        out.sort(Comparator.comparing(v -> v.line1().getString()));
        return out;
    }

    static List<TableView> electrodes() {
        List<TableView> out = new ArrayList<>();
        List<Electrodes.Entry> entries = new ArrayList<>(Electrodes.entries());
        entries.sort(Comparator.comparingDouble(Electrodes.Entry::volts).reversed());
        for (Electrodes.Entry e : entries) {
            List<ItemStack> st = items(e.key());
            if (!st.isEmpty()) {
                out.add(new TableView(st, List.of(), List.of(), label(e.key(), st, List.of()),
                        Component.translatable("fieldframe.jei.electrode_volts", String.format(java.util.Locale.ROOT, "%+.2f", e.volts()))));
            }
        }
        return out;
    }

    static List<TableView> temperatures() {
        List<TableView> out = new ArrayList<>();
        List<Temperatures.Entry> entries = new ArrayList<>(Temperatures.entries());
        entries.sort(Comparator.comparingInt(Temperatures.Entry::kelvin).reversed());
        for (Temperatures.Entry e : entries) {
            List<ItemStack> st = e.fluid() ? List.of() : blockItems(e.key());
            List<Fluid> fl = e.fluid() ? fluids(e.key()) : st.isEmpty() ? fluids(e.key()) : List.of();
            if (st.isEmpty() && fl.isEmpty()) {
                continue;
            }
            Component k = Component.translatable("fieldframe.jei.temperature", e.kelvin(), e.kelvin() - 273);
            out.add(new TableView(st, fl, List.of(), label(e.key(), st, fl),
                    e.lit() ? Component.translatable("fieldframe.jei.temperature_lit", k) : k));
        }
        return out;
    }

    /** A row's name: a #tag as written, otherwise the item's or fluid's own name. */
    static Component label(String key, List<ItemStack> items, List<Fluid> fluids) {
        if (key.startsWith("#")) {
            return Component.literal(key);
        }
        if (!items.isEmpty()) {
            return items.get(0).getHoverName();
        }
        if (!fluids.isEmpty()) {
            return fluids.get(0).getFluidType().getDescription();
        }
        return Component.literal(key);
    }

    /** Items for an id or #tag. */
    static List<ItemStack> items(String key) {
        List<ItemStack> st = new ArrayList<>();
        if (key == null || key.isEmpty()) {
            return st;
        }
        boolean tag = key.startsWith("#");
        Identifier id = Identifier.tryParse(tag ? key.substring(1) : key);
        if (id == null) {
            return st;
        }
        if (tag) {
            TagKey<Item> t = TagKey.create(Registries.ITEM, id);
            BuiltInRegistries.ITEM.stream().filter(i -> i != Items.AIR && new ItemStack(i).is(t)).forEach(i -> st.add(new ItemStack(i)));
        } else {
            BuiltInRegistries.ITEM.getOptional(id).filter(i -> i != Items.AIR).ifPresent(i -> st.add(new ItemStack(i)));
        }
        return st;
    }

    /** Block items for a block id or #tag (blocks without an item are left out). */
    static List<ItemStack> blockItems(String key) {
        List<ItemStack> st = new ArrayList<>();
        boolean tag = key.startsWith("#");
        Identifier id = Identifier.tryParse(tag ? key.substring(1) : key);
        if (id == null) {
            return st;
        }
        List<Block> blocks = new ArrayList<>();
        if (tag) {
            TagKey<Block> t = TagKey.create(Registries.BLOCK, id);
            BuiltInRegistries.BLOCK.stream().filter(b -> b.defaultBlockState().is(t)).forEach(blocks::add);
        } else {
            BuiltInRegistries.BLOCK.getOptional(id).ifPresent(blocks::add);
        }
        for (Block b : blocks) {
            Item it = b.asItem();
            if (it != Items.AIR) {
                st.add(new ItemStack(it));
            }
        }
        return st;
    }

    /** Source fluids for a fluid id or #tag. */
    static List<Fluid> fluids(String key) {
        List<Fluid> fl = new ArrayList<>();
        boolean tag = key.startsWith("#");
        Identifier id = Identifier.tryParse(tag ? key.substring(1) : key);
        if (id == null) {
            return fl;
        }
        if (tag) {
            TagKey<Fluid> t = TagKey.create(Registries.FLUID, id);
            BuiltInRegistries.FLUID.stream().filter(f -> f != Fluids.EMPTY && f.isSource(f.defaultFluidState())
                    && f.defaultFluidState().is(t)).forEach(fl::add);
        } else {
            BuiltInRegistries.FLUID.getOptional(id).filter(f -> f != Fluids.EMPTY).ifPresent(fl::add);
        }
        return fl;
    }

    private static String fmt(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.format(java.util.Locale.ROOT, "%.1f", v);
    }
}
