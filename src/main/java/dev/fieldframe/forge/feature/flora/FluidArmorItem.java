package dev.fieldframe.forge.feature.flora;

import dev.fieldframe.core.flora.Flora;
import dev.fieldframe.core.flora.FluidArmorEffects;
import dev.fieldframe.forge.adapter.NbtBridge;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ArmorMaterial;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.Level;

import java.util.List;
import java.util.Map;

/**
 * Fluid armor (FLORA). Holds up to the tier capacity of effect fluids (the seven FLORA fluids and any fluid with a fluid
 * armor effect), stored under {@code fieldframe.flora_charge};
 * unbreakable like the original. Filled by the Armor Infuser chassis module.
 */
public final class FluidArmorItem extends ArmorItem {
    private final Flora.Tier tier;

    public FluidArmorItem(Flora.Tier tier, Type type, Properties p) {
        super(material(tier), type, p.stacksTo(1));
        this.tier = tier;
    }

    public Flora.Tier floraTier() {
        return tier;
    }

    public static Flora.Charge charge(ItemStack s) {
        if (!(s.getItem() instanceof FluidArmorItem a)) {
            return null;
        }
        CompoundTag t = s.getTagElement("fieldframe");
        return Flora.Charge.load(t == null || !t.contains(Flora.Charge.DATA_KEY) ? null : NbtBridge.fromTag(t.getCompound(Flora.Charge.DATA_KEY)),
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
        };
    }

    private static final Map<Flora.Tier, ArmorMaterial> MATERIALS = new java.util.EnumMap<>(Flora.Tier.class);

    static ArmorMaterial material(Flora.Tier tier) {
        return MATERIALS.computeIfAbsent(tier, t -> new ArmorMaterial() {
            @Override
            public int getDurabilityForType(Type type) {
                return 0;
            }

            @Override
            public int getDefenseForType(Type type) {
                return defense(t, type);
            }

            @Override
            public int getEnchantmentValue() {
                return 10;
            }

            @Override
            public SoundEvent getEquipSound() {
                return SoundEvents.ARMOR_EQUIP_IRON;
            }

            @Override
            public Ingredient getRepairIngredient() {
                return Ingredient.EMPTY;
            }

            @Override
            public String getName() {
                return "fieldframe:" + t.key + "_fluid";
            }

            @Override
            public float getToughness() {
                return t == Flora.Tier.ENDER_ALLOY ? 2f : 0f;
            }

            @Override
            public float getKnockbackResistance() {
                return 0f;
            }
        });
    }

    @Override
    public void appendHoverText(ItemStack stack, Level level, List<Component> tip, TooltipFlag flag) {
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
                for (var e : FluidArmorEffects.forFluid(en.getKey(), dev.fieldframe.forge.adapter.McTagLookup.INSTANCE)) {
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
        return net.minecraft.core.registries.BuiltInRegistries.FLUID.getOptional(new ResourceLocation(id.namespace(), id.path()))
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
        return mobEffect(e).map(h -> (Component) Component.translatable(h.getDescriptionId())).orElse(Component.literal(e.effect().toString()));
    }

    /** "I", or "I–III" when it levels up; empty for Cleanse. */
    public static String level(FluidArmorEffects.Entry e) {
        if (e.effect().equals(FluidArmorEffects.CLEANSE)) {
            return "";
        }
        return roman(e.amplifier()) + (e.maxAmplifier() > e.amplifier() ? "–" + roman(e.maxAmplifier()) : "");
    }

    public static boolean harmful(FluidArmorEffects.Entry e) {
        return mobEffect(e).map(h -> h.getCategory() == net.minecraft.world.effect.MobEffectCategory.HARMFUL).orElse(false);
    }

    public static java.util.Optional<net.minecraft.world.effect.MobEffect> mobEffect(FluidArmorEffects.Entry e) {
        return net.minecraft.core.registries.BuiltInRegistries.MOB_EFFECT.getOptional(new ResourceLocation(e.effect().namespace(), e.effect().path()));
    }

    private static String roman(int amp) {
        String[] r = {"I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X"};
        return r[Math.max(0, Math.min(9, amp))];
    }
}
