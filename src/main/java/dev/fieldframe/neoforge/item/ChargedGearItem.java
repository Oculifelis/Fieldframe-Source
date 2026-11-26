package dev.fieldframe.neoforge.item;

import dev.fieldframe.neoforge.feature.ItemEnergy;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

import java.util.List;

/**
 * Fieldframe's RF-powered Fundamentum gear (the electric whips, the Graviton Hook): RF in the {@code fieldframe:energy}
 * component (an Item Charger or charge pad fills it through the item energy capability), shown as a bar and a tooltip
 * line. What the item does is Fundamentum's ({@code compat.fundamentum}); this class touches no Fundamentum code.
 */
public final class ChargedGearItem extends Item {
    /** RF it holds, takes per tick from a charger, and spends per use (a swing; a second hovering) and per chain arc. */
    public record Charge(int capacity, int receivePerTick, int perUse, int perArc) {}

    private final Charge charge;
    private final int enchantability;

    public ChargedGearItem(Properties props, Charge charge) {
        this(props, charge, 0);
    }

    /** {@code enchantability} 0 keeps it off the enchanting table. */
    public ChargedGearItem(Properties props, Charge charge, int enchantability) {
        super(props);
        this.charge = charge;
        this.enchantability = enchantability;
    }

    @Override
    public boolean isEnchantable(ItemStack stack) {
        return enchantability > 0;
    }

    @Override
    public int getEnchantmentValue() {
        return enchantability;
    }

    public Charge charge() {
        return charge;
    }

    /** Takes {@code rf} from the stack if it holds that much. */
    public boolean spend(ItemStack stack, int rf) {
        return ItemEnergy.consume(stack, rf, charge.capacity());
    }

    /** A stack charged full (the creative tab's second copy). */
    public ItemStack charged() {
        ItemStack s = getDefaultInstance();
        ItemEnergy.set(s, charge.capacity());
        return s;
    }

    @Override
    public boolean isBarVisible(ItemStack stack) {
        return true;
    }

    @Override
    public int getBarWidth(ItemStack stack) {
        return (int) Math.round(13.0 * Math.min(charge.capacity(), ItemEnergy.get(stack)) / charge.capacity());
    }

    @Override
    public int getBarColor(ItemStack stack) {
        return 0x6FD6FF;
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> tip, TooltipFlag flag) {
        long rf = ItemEnergy.get(stack);
        tip.add(Component.translatable("fieldframe.charge.energy", String.format("%,d", rf), String.format("%,d", charge.capacity()))
                .withStyle(ChatFormatting.GRAY));
        if (rf < charge.perUse()) {
            tip.add(Component.translatable("fieldframe.charge.empty").withStyle(ChatFormatting.RED));
        }
    }
}
