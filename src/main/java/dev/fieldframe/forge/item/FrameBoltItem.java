package dev.fieldframe.forge.item;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

/**
 * Frame Bolt: use on the side of a chassis that touches another chassis to bolt them into one machine (shared
 * storages, tanks and RF); sneak-use to take a chassis out again. The chassis block handles the use
 * (see {@code BoltLinks}).
 */
public final class FrameBoltItem extends Item {
    public FrameBoltItem(Properties props) {
        super(props);
    }

    @Override
    public void appendHoverText(ItemStack stack, net.minecraft.world.level.Level level, java.util.List<Component> tip, TooltipFlag flag) {
        tip.add(Component.translatable("item.fieldframe.frame_bolt.desc1").withStyle(ChatFormatting.GRAY));
        tip.add(Component.translatable("item.fieldframe.frame_bolt.desc2").withStyle(ChatFormatting.DARK_GRAY));
    }
}
