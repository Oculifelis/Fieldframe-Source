package dev.fieldframe.neoforge.item;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.api.distmarker.Dist;

/** Opens the native Engineering Field Manual (client only; no server state involved). */
public final class ManualItem extends Item {
    public ManualItem(Properties props) {
        super(props);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        if (level.isClientSide) {
            if (net.neoforged.fml.loading.FMLEnvironment.dist == Dist.CLIENT) {
                dev.fieldframe.neoforge.client.FfClient.openManual(null);
            }
        }
        return InteractionResultHolder.sidedSuccess(player.getItemInHand(hand), level.isClientSide);
    }
}
