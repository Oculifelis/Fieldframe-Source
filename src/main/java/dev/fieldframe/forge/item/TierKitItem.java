package dev.fieldframe.forge.item;

import dev.fieldframe.api.FieldframeRegistry;
import dev.fieldframe.api.chassis.ChassisTier;
import dev.fieldframe.api.id.FfId;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

import java.util.List;

/** Chassis upgrade kit: use on a chassis one tier below to raise it (see {@link ChassisTier}). */
public final class TierKitItem extends Item {
    private final FfId tierId;

    public TierKitItem(FfId tierId, Properties props) {
        super(props);
        this.tierId = tierId;
    }

    public FfId tierId() {
        return tierId;
    }

    public static Component tierName(ChassisTier t) {
        return Component.translatable(t.translationKey());
    }

    @Override
    public void appendHoverText(ItemStack stack, net.minecraft.world.level.Level level, List<Component> tip, TooltipFlag flag) {
        ChassisTier t = FieldframeRegistry.tier(tierId);
        ChassisTier from = t == null ? null : FieldframeRegistry.tierAt(t.level() - 1);
        if (t == null || from == null) {
            return;
        }
        tip.add(Component.translatable("fieldframe.tier.kit", tierName(from), tierName(t)).withStyle(ChatFormatting.GRAY));
        tip.add(Component.translatable("fieldframe.tier.speed", String.valueOf(t.speed())).withStyle(ChatFormatting.DARK_GRAY));
        tip.add(Component.translatable("fieldframe.tier.extras", t.extraServiceBays(), t.extraSlotsPerLane(), t.extraFluidCapacity(),
                String.format("%,d", t.extraEnergyCapacity())).withStyle(ChatFormatting.DARK_GRAY));
    }
}
