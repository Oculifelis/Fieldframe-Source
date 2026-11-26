package dev.fieldframe.neoforge.item;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

import java.util.List;

/** The configurator/wrench. All behaviour is in {@code ChassisBlock#use} so it is validated server-side. */
public final class ConfiguratorItem extends Item {
    public ConfiguratorItem(Properties props) {
        super(props);
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> tip, TooltipFlag flag) {
        tip.add(Component.translatable("item.fieldframe.configurator.desc1").withStyle(ChatFormatting.GRAY));
        tip.add(Component.translatable("item.fieldframe.configurator.desc2").withStyle(ChatFormatting.GRAY));
        tip.add(Component.translatable("item.fieldframe.configurator.desc3").withStyle(ChatFormatting.GRAY));
    }
}
