package dev.fieldframe.neoforge.feature.flora;

import dev.fieldframe.core.flora.Flora;
import dev.fieldframe.core.flora.FluidArmorEffects;
import dev.fieldframe.neoforge.adapter.NbtBridge;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ArmorMaterial;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

import java.util.List;

/**
 * Fluid armor (FLORA). Holds up to the tier capacity of effect fluids (the seven FLORA fluids and any fluid with a fluid
 * armor effect), stored under {@code fieldframe.flora_charge};
 * unbreakable like the original. Filled by the Armor Infuser chassis module.
 */
public final class FluidArmorItem extends ArmorItem {
    private final Flora.Tier tier;

    public FluidArmorItem(Flora.Tier tier, Holder<ArmorMaterial> material, Type type, Properties p) {
        super(material, type, p.stacksTo(1));
        this.tier = tier;
    }

    public Flora.Tier floraTier() {
        return tier;
    }

    public static Flora.Charge charge(ItemStack s) {
        if (!(s.getItem() instanceof FluidArmorItem a)) {
            return null;
        }
        CompoundTag t = dev.fieldframe.neoforge.feature.ItemEnergy.data(s);
        return Flora.Charge.load(!t.contains(Flora.Charge.DATA_KEY) ? null : NbtBridge.fromTag(t.getCompound(Flora.Charge.DATA_KEY)),
                a.tier.capacity);
    }

    /** Defense in vanilla armor points: the original per-piece protection value, spread like vanilla armor. */
    static int defense(Flora.Tier tier, Type type) {
        int p = tier.protection;
        return switch (type) {
            case HELMET -> p - 2;
            case CHESTPLATE -> p;
            case LEGGINGS -> p - 1;
            case BOOTS -> p - 3;
            case BODY -> 0;
        };
    }

    /** Armor material for a tier (registered in the armor material registry by FeatureContent). */
    public static ArmorMaterial material(Flora.Tier t) {
        java.util.EnumMap<Type, Integer> def = new java.util.EnumMap<>(Type.class);
        for (Type type : new Type[] {Type.HELMET, Type.CHESTPLATE, Type.LEGGINGS, Type.BOOTS}) {
            def.put(type, defense(t, type));
        }
        def.put(Type.BODY, 0);
        return new ArmorMaterial(def, 10, net.minecraft.sounds.SoundEvents.ARMOR_EQUIP_IRON, () -> net.minecraft.world.item.crafting.Ingredient.of(net.minecraft.tags.TagKey.create(net.minecraft.core.registries.Registries.ITEM, net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("fieldframe", "repairs_fluid_armor"))),
                List.of(new ArmorMaterial.Layer(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("fieldframe", t.key + "_fluid"))),
                t == Flora.Tier.ENDER_ALLOY ? 2f : 0f, 0f);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext ctx, List<Component> tip, TooltipFlag flag) {
        Flora.Charge c = charge(stack);
        tip.add(Component.translatable("fieldframe.flora.capacity", c == null ? 0 : c.total(), tier.capacity).withStyle(ChatFormatting.DARK_BLUE));
        if (c != null) {
            for (Flora.Fluid f : Flora.Fluid.values()) {
                if (c.amount(f) > 0) {
                    tip.add(Component.translatable("fluid.fieldframe." + f.path).append(": " + c.amount(f) + " mB").withStyle(ChatFormatting.DARK_AQUA));
                }
            }
            float[][] m = Flora.matrix(List.of(c));
            for (Flora.Effect e : dev.fieldframe.core.flora.FloraPairs.effects()) {
                if (Flora.m(m, e.a(), e.b()) > 0) {
                    tip.add(Component.translatable("fieldframe.flora.effect." + e.key()).withStyle(e.negative() ? ChatFormatting.RED : ChatFormatting.DARK_GREEN));
                }
            }
            boolean any = false;
            for (var en : c.all().entrySet()) {
                if (Flora.Fluid.byId(en.getKey()) != null) {
                    continue;
                }
                tip.add(fluidName(en.getKey()).copy().append(": " + en.getValue() + " mB").withStyle(ChatFormatting.DARK_AQUA));
                for (var e : FluidArmorEffects.forFluid(en.getKey(), dev.fieldframe.neoforge.adapter.McTagLookup.INSTANCE)) {
                    tip.add(Component.literal("  ").append(effectLine(e)));
                    any = true;
                }
            }
            if (any) {
                tip.add(Component.translatable("fieldframe.flora.worn_total").withStyle(ChatFormatting.GRAY));
            }
        }
    }

    public static Component fluidName(dev.fieldframe.api.id.FfId id) {
        return net.minecraft.core.registries.BuiltInRegistries.FLUID.getOptional(ResourceLocation.fromNamespaceAndPath(id.namespace(), id.path()))
                .map(f -> f.getFluidType().getDescription()).orElse(Component.literal(id.toString()));
    }

    /** "Fire Resistance I from 1000 mB worn" (with levels: "Speed I–III from 1000 mB worn, +1 level per 16000 mB"), red if harmful. */
    public static Component effectLine(FluidArmorEffects.Entry e) {
        Component line = e.mbPerLevel() > 0
                ? Component.translatable("fieldframe.flora.fluid_effect_levels", effectName(e), level(e), e.minMb(), e.mbPerLevel())
                : Component.translatable("fieldframe.flora.fluid_effect", effectName(e), level(e), e.minMb());
        return line.copy().withStyle(harmful(e) ? ChatFormatting.RED : ChatFormatting.DARK_GREEN);
    }

    public static Component effectName(FluidArmorEffects.Entry e) {
        if (e.effect().equals(FluidArmorEffects.CLEANSE)) {
            return Component.translatable("fieldframe.effect.cleanse");
        }
        return mobEffect(e).map(h -> (Component) Component.translatable(h.value().getDescriptionId())).orElse(Component.literal(e.effect().toString()));
    }

    /** "I", or "I–III" when it levels up; empty for Cleanse. */
    public static String level(FluidArmorEffects.Entry e) {
        if (e.effect().equals(FluidArmorEffects.CLEANSE)) {
            return "";
        }
        return roman(e.amplifier()) + (e.maxAmplifier() > e.amplifier() ? "–" + roman(e.maxAmplifier()) : "");
    }

    public static boolean harmful(FluidArmorEffects.Entry e) {
        return mobEffect(e).map(h -> h.value().getCategory() == net.minecraft.world.effect.MobEffectCategory.HARMFUL).orElse(false);
    }

    public static java.util.Optional<Holder.Reference<net.minecraft.world.effect.MobEffect>> mobEffect(FluidArmorEffects.Entry e) {
        return net.minecraft.core.registries.BuiltInRegistries.MOB_EFFECT.getHolder(ResourceLocation.fromNamespaceAndPath(e.effect().namespace(), e.effect().path()));
    }

    private static String roman(int amp) {
        String[] r = {"I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X"};
        return r[Math.max(0, Math.min(9, amp))];
    }
}
