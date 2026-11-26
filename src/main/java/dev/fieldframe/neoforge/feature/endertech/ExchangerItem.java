package dev.fieldframe.neoforge.feature.endertech;

import dev.fieldframe.api.face.WorldDir;
import dev.fieldframe.core.endertech.EnderTechRules;
import dev.fieldframe.neoforge.adapter.Dirs;
import dev.fieldframe.neoforge.feature.ItemEnergy;
import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;

/**
 * Exchanger (EnderTech): sneak-use a block to choose the replacement, use a block to swap every connected matching
 * block in growing rings on that face. RF from the tool, replacement blocks from the inventory, drops back into it.
 */
public final class ExchangerItem extends Item {
    private final EnderTechRules.Tier tier;

    public ExchangerItem(EnderTechRules.Tier tier, Properties p) {
        super(p.stacksTo(1));
        this.tier = tier;
    }

    public EnderTechRules.Tier tier() {
        return tier;
    }

    public long capacity() {
        return tier.creative() ? -1 : tier.capacity;
    }

    public static BlockState source(ItemStack stack) {
        net.minecraft.nbt.CompoundTag t = ItemEnergy.data(stack);
        if (!t.contains("source")) {
            return null;
        }
        return NbtUtils.readBlockState(net.minecraft.core.registries.BuiltInRegistries.BLOCK, t.getCompoundOrEmpty("source"));
    }

    public static int radius(ItemStack stack) {
        net.minecraft.nbt.CompoundTag t = ItemEnergy.data(stack);
        return EnderTechRules.clampRadius(!t.contains("radius") ? EnderTechRules.EXCHANGER_DEFAULT_RADIUS : t.getIntOr("radius", EnderTechRules.EXCHANGER_DEFAULT_RADIUS));
    }

    /** Radius key actions from the client: 10 = +1, 11 = -1, 12 = max, 13 = min. Returns the new radius. */
    public static int adjustRadius(ItemStack stack, int action) {
        int r = radius(stack);
        int next = switch (action) {
            case 10 -> r + 1;
            case 11 -> r - 1;
            case 12 -> EnderTechRules.EXCHANGER_MAX_RADIUS;
            case 13 -> 1;
            default -> r;
        };
        setRadius(stack, next);
        return radius(stack);
    }

    public static void setRadius(ItemStack stack, int r) {
        ItemEnergy.editData(stack, t -> t.putInt("radius", EnderTechRules.clampRadius(r)));
    }

    @Override
    public InteractionResult useOn(UseOnContext ctx) {
        Level level = ctx.getLevel();
        if (level.isClientSide() || !(ctx.getPlayer() instanceof ServerPlayer player)) {
            return InteractionResult.SUCCESS;
        }
        ItemStack stack = ctx.getItemInHand();
        BlockState clicked = level.getBlockState(ctx.getClickedPos());
        if (player.isShiftKeyDown()) {
            if (ExchangerService.selectable(level, ctx.getClickedPos(), clicked, tier)) {
                ItemEnergy.editData(stack, t -> t.put("source", NbtUtils.writeBlockState(clicked)));
                player.sendOverlayMessage(Component.translatable("fieldframe.exchanger.source_set", clicked.getBlock().getName()));
            } else {
                player.sendOverlayMessage(Component.translatable("fieldframe.exchanger.not_selectable"));
            }
            return InteractionResult.CONSUME;
        }
        BlockState source = source(stack);
        if (source == null) {
            player.sendOverlayMessage(Component.translatable("fieldframe.exchanger.no_source"));
            return InteractionResult.FAIL;
        }
        WorldDir normal = Dirs.toWorld(ctx.getClickedFace());
        ExchangerService.queue(player, ctx.getHand(), ctx.getClickedPos(), normal, clicked, source, radius(stack));
        return InteractionResult.CONSUME;
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext ctx, net.minecraft.world.item.component.TooltipDisplay display,
                                java.util.function.Consumer<Component> tip, TooltipFlag flag) {
        tip.accept(tier.creative()
                ? Component.translatable("fieldframe.energy.infinite").withStyle(ChatFormatting.GRAY)
                : Component.translatable("fieldframe.energy.stored", ItemEnergy.get(stack), tier.capacity).withStyle(ChatFormatting.GRAY));
        BlockState src = source(stack);
        tip.accept(Component.translatable("fieldframe.exchanger.source",
                src == null ? Component.translatable("fieldframe.exchanger.none") : src.getBlock().getName()).withStyle(ChatFormatting.GREEN));
        tip.accept(Component.translatable("fieldframe.exchanger.radius", radius(stack)).withStyle(ChatFormatting.GREEN));
        tip.accept(Component.translatable("fieldframe.exchanger.hint").withStyle(ChatFormatting.DARK_AQUA, ChatFormatting.ITALIC));
    }

    @Override
    public boolean isBarVisible(ItemStack stack) {
        return !tier.creative();
    }

    @Override
    public int getBarWidth(ItemStack stack) {
        return (int) Math.round(13.0 * ItemEnergy.get(stack) / tier.capacity);
    }

    @Override
    public int getBarColor(ItemStack stack) {
        return 0x2FB9A6;
    }
}
