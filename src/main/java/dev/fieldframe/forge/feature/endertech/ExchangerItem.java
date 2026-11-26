package dev.fieldframe.forge.feature.endertech;

import dev.fieldframe.api.face.WorldDir;
import dev.fieldframe.core.endertech.EnderTechRules;
import dev.fieldframe.forge.adapter.Dirs;
import dev.fieldframe.forge.feature.ItemEnergy;
import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
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
import net.minecraft.nbt.CompoundTag;
import net.minecraftforge.common.capabilities.ICapabilityProvider;

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

    @Override
    public ICapabilityProvider initCapabilities(ItemStack stack, CompoundTag nbt) {
        return ItemEnergy.provider(stack, capacity(), tier.receivePerTick);
    }

    public static BlockState source(Level level, ItemStack stack) {
        CompoundTag t = stack.getTagElement("fieldframe");
        if (t == null || !t.contains("source")) {
            return null;
        }
        return NbtUtils.readBlockState(level.holderLookup(Registries.BLOCK), t.getCompound("source"));
    }

    public static int radius(ItemStack stack) {
        CompoundTag t = stack.getTagElement("fieldframe");
        return EnderTechRules.clampRadius(t == null || !t.contains("radius") ? EnderTechRules.EXCHANGER_DEFAULT_RADIUS : t.getInt("radius"));
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
        stack.getOrCreateTagElement("fieldframe").putInt("radius", EnderTechRules.clampRadius(r));
    }

    @Override
    public InteractionResult useOn(UseOnContext ctx) {
        Level level = ctx.getLevel();
        if (level.isClientSide || !(ctx.getPlayer() instanceof ServerPlayer player)) {
            return InteractionResult.SUCCESS;
        }
        ItemStack stack = ctx.getItemInHand();
        BlockState clicked = level.getBlockState(ctx.getClickedPos());
        if (player.isShiftKeyDown()) {
            if (ExchangerService.selectable(level, ctx.getClickedPos(), clicked, tier)) {
                stack.getOrCreateTagElement("fieldframe").put("source", NbtUtils.writeBlockState(clicked));
                player.displayClientMessage(Component.translatable("fieldframe.exchanger.source_set", clicked.getBlock().getName()), true);
            } else {
                player.displayClientMessage(Component.translatable("fieldframe.exchanger.not_selectable"), true);
            }
            return InteractionResult.CONSUME;
        }
        BlockState source = source(level, stack);
        if (source == null) {
            player.displayClientMessage(Component.translatable("fieldframe.exchanger.no_source"), true);
            return InteractionResult.FAIL;
        }
        WorldDir normal = Dirs.toWorld(ctx.getClickedFace());
        ExchangerService.queue(player, ctx.getHand(), ctx.getClickedPos(), normal, clicked, source, radius(stack));
        return InteractionResult.CONSUME;
    }

    @Override
    public void appendHoverText(ItemStack stack, Level level, List<Component> tip, TooltipFlag flag) {
        tip.add(tier.creative()
                ? Component.translatable("fieldframe.energy.infinite").withStyle(ChatFormatting.GRAY)
                : Component.translatable("fieldframe.energy.stored", ItemEnergy.get(stack), tier.capacity).withStyle(ChatFormatting.GRAY));
        BlockState src = level == null ? null : source(level, stack);
        tip.add(Component.translatable("fieldframe.exchanger.source",
                src == null ? Component.translatable("fieldframe.exchanger.none") : src.getBlock().getName()).withStyle(ChatFormatting.GREEN));
        tip.add(Component.translatable("fieldframe.exchanger.radius", radius(stack)).withStyle(ChatFormatting.GREEN));
        tip.add(Component.translatable("fieldframe.exchanger.hint").withStyle(ChatFormatting.DARK_AQUA, ChatFormatting.ITALIC));
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
