package dev.fieldframe.neoforge.item;

import dev.fieldframe.api.FieldframeRegistry;
import dev.fieldframe.api.chassis.UpgradeDefinition;
import dev.fieldframe.api.id.FfId;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

import java.util.List;

/** Service-bay upgrade item. */
public final class UpgradeItem extends Item {
    private final FfId upgradeId;

    public UpgradeItem(FfId upgradeId, Properties props) {
        super(props);
        this.upgradeId = upgradeId;
    }

    public FfId upgradeId() {
        return upgradeId;
    }

    @Override
    public String getDescriptionId() {
        UpgradeDefinition u = FieldframeRegistry.upgrade(upgradeId);
        return u != null ? u.translationKey() : super.getDescriptionId();
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> tip, TooltipFlag flag) {
        UpgradeDefinition u = FieldframeRegistry.upgrade(upgradeId);
        if (u == null) {
            return;
        }
        if (u.extraSlotsPerLane() > 0) {
            tip.add(Component.translatable("fieldframe.upgrade.slots", u.extraSlotsPerLane()).withStyle(ChatFormatting.GRAY));
        }
        if (u.extraFluidCapacity() > 0) {
            tip.add(Component.translatable("fieldframe.upgrade.fluid", u.extraFluidCapacity()).withStyle(ChatFormatting.GRAY));
        }
        if (u.extraEnergyCapacity() > 0) {
            tip.add(Component.translatable("fieldframe.upgrade.energy", u.extraEnergyCapacity()).withStyle(ChatFormatting.GRAY));
        }
        if (u.extraItemsPerTick() > 0 || u.extraFluidPerTick() > 0 || u.extraEnergyPerTick() > 0) {
            tip.add(Component.translatable("fieldframe.upgrade.throughput", u.extraItemsPerTick(), u.extraFluidPerTick(), u.extraEnergyPerTick()).withStyle(ChatFormatting.GRAY));
        }
        if (u.overclock() > 0) {
            tip.add(Component.translatable("fieldframe.upgrade.overclock", Math.round(u.overclock() * 100)).withStyle(ChatFormatting.GOLD));
            tip.add(Component.translatable("fieldframe.upgrade.overclock_heat").withStyle(ChatFormatting.GRAY));
        }
    }
}
