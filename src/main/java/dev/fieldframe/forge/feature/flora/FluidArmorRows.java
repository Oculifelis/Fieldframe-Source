package dev.fieldframe.forge.feature.flora;

import dev.fieldframe.api.id.FfId;
import dev.fieldframe.core.flora.Flora;
import dev.fieldframe.core.flora.FluidArmorEffects;
import dev.fieldframe.forge.adapter.NbtBridge;
import dev.fieldframe.forge.adapter.McTagLookup;
import dev.fieldframe.forge.feature.FeatureContent;
import dev.fieldframe.forge.feature.ItemEnergy;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/**
 * What fluid armor can be filled with, one row per fluid: the seven FLORA fluids with their pair effects, then every
 * fluid of the loaded fluid armor effects (tags expanded, missing fluids left out). Used by the JEI page and the
 * GameTests; no JEI classes here.
 */
public final class FluidArmorRows {
    private FluidArmorRows() {
    }

    /** {@code effects} for table fluids, {@code pairs} for FLORA fluids (the pair effects it takes part in). */
    public record Row(FfId id, Fluid fluid, List<FluidArmorEffects.Entry> effects, List<Flora.Effect> pairs) {
        public boolean flora() {
            return !pairs.isEmpty();
        }

        /** Mb of this fluid to show: the largest threshold (so every effect is reached), at least one bucket. */
        public long showMb() {
            long mb = 1000;
            for (FluidArmorEffects.Entry e : effects) {
                mb = Math.max(mb, e.minMb());
            }
            return mb;
        }
    }

    public static List<Row> all() {
        List<Row> out = new ArrayList<>();
        for (Flora.Fluid f : Flora.Fluid.values()) {
            Fluid mc = fluid(f.id());
            if (mc == null) {
                continue;
            }
            List<Flora.Effect> pairs = new ArrayList<>();
            for (Flora.Effect e : dev.fieldframe.core.flora.FloraPairs.effects()) {
                if (e.a() == f || e.b() == f) {
                    pairs.add(e);
                }
            }
            out.add(new Row(f.id(), mc, List.of(), pairs));
        }
        Set<FfId> seen = new LinkedHashSet<>();
        for (FluidArmorEffects.Entry e : FluidArmorEffects.all()) {
            if (e.tag()) {
                TagKey<Fluid> key = TagKey.create(Registries.FLUID, new ResourceLocation(e.key().substring(1)));
                BuiltInRegistries.FLUID.stream().filter(fl -> fl != Fluids.EMPTY && fl.isSource(fl.defaultFluidState())
                        && fl.defaultFluidState().is(key)).forEach(fl -> seen.add(id(fl)));
            } else if (fluid(FfId.parse(e.key())) != null) {
                seen.add(FfId.parse(e.key()));
            }
        }
        for (FfId id : seen) {
            if (Flora.Fluid.byId(id) != null) {
                continue;
            }
            List<FluidArmorEffects.Entry> effects = FluidArmorEffects.forFluid(id, McTagLookup.INSTANCE);
            if (!effects.isEmpty()) {
                out.add(new Row(id, fluid(id), effects, List.of()));
            }
        }
        return out;
    }

    /** Every fluid armor piece, Copper helmet first. */
    public static List<ItemStack> armor() {
        List<ItemStack> out = new ArrayList<>();
        for (Supplier<net.minecraft.world.item.Item> s : FeatureContent.ARMOR.values()) {
            out.add(new ItemStack(s.get()));
        }
        return out;
    }

    /** The armor pieces filled with {@code mb} of the row's fluid (as much as fits per tier). */
    public static List<ItemStack> filled(Row r, long mb) {
        List<ItemStack> out = new ArrayList<>();
        for (ItemStack s : armor()) {
            if (s.getItem() instanceof FluidArmorItem a) {
                Flora.Charge c = new Flora.Charge();
                c.fill(r.id(), mb, a.floraTier().capacity, false);
                ItemEnergy.editData(s, t -> t.put(Flora.Charge.DATA_KEY, NbtBridge.toTag(c.save())));
                out.add(s);
            }
        }
        return out;
    }

    public static Fluid fluid(FfId id) {
        return BuiltInRegistries.FLUID.getOptional(new ResourceLocation(id.namespace(), id.path()))
                .filter(f -> f != Fluids.EMPTY).orElse(null);
    }

    static FfId id(Fluid f) {
        ResourceLocation i = BuiltInRegistries.FLUID.getKey(f);
        return FfId.of(i.getNamespace(), i.getPath());
    }
}
