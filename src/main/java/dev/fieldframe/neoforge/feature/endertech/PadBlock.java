package dev.fieldframe.neoforge.feature.endertech;

import dev.fieldframe.core.endertech.EnderTechRules;
import dev.fieldframe.neoforge.feature.FeatureContent;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DirectionalBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.neoforge.capabilities.Capabilities;

/**
 * Charge Pad / Health Pad (EnderTech). Faces any of six directions and works on the 2-deep box in front of its face;
 * accepts RF on the other five sides. Using it with an RF item moves that item's energy into the pad (the original
 * pad had a charge slot for this); with an empty hand it reports its charge.
 */
public final class PadBlock extends BaseEntityBlock {
    public static final EnumProperty<Direction> FACING = DirectionalBlock.FACING;
    private final boolean health;
    private final EnderTechRules.Tier tier;

    public PadBlock(boolean health, EnderTechRules.Tier tier, Properties p) {
        super(p);
        this.health = health;
        this.tier = tier;
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.UP));
    }

    public boolean health() {
        return health;
    }

    public EnderTechRules.Tier tier() {
        return tier;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> b) {
        b.add(FACING);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext ctx) {
        return defaultBlockState().setValue(FACING, ctx.getNearestLookingDirection().getOpposite());
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new PadBlockEntity(pos, state);
    }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return level.isClientSide() || type != FeatureContent.PAD_BE.get() ? null
                : (l, p, s, be) -> ((PadBlockEntity) be).serverTick();
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        return use(state, level, pos, player, InteractionHand.MAIN_HAND, hit);
    }

    @Override
    protected InteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player,
                                          InteractionHand hand, BlockHitResult hit) {
        InteractionResult r = use(state, level, pos, player, hand, hit);
        return r.consumesAction() ? r : InteractionResult.TRY_WITH_EMPTY_HAND;
    }

    private InteractionResult use(BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        if (!(level.getBlockEntity(pos) instanceof PadBlockEntity be)) {
            return InteractionResult.PASS;
        }
        if (level.isClientSide()) {
            return InteractionResult.SUCCESS;
        }
        ItemStack held = player.getItemInHand(hand);
        var cap = held.isEmpty() ? null : net.neoforged.neoforge.transfer.access.ItemAccess.forPlayerInteraction(player, hand)
                .getCapability(Capabilities.Energy.ITEM);
        if (cap != null && !tier.creative()) {
            int room = (int) Math.min(Integer.MAX_VALUE, be.buffer().capacity() - be.buffer().stored());
            int got;
            try (var tx = net.neoforged.neoforge.transfer.transaction.Transaction.openRoot()) {
                got = cap.extract(Math.min(room, (int) Math.min(Integer.MAX_VALUE, tier.receivePerTick * 20)), tx);
                tx.commit();
            }
            if (got <= 0) {
                player.sendOverlayMessage(Component.translatable("fieldframe.energy.stored", be.buffer().stored(), be.buffer().capacity()));
                return InteractionResult.CONSUME;
            }
            be.buffer().receive(got, false);
            player.sendOverlayMessage(Component.translatable("fieldframe.pad.charged_from_item", got));
            return InteractionResult.CONSUME;
        }
        player.sendOverlayMessage(tier.creative() ? Component.translatable("fieldframe.energy.infinite")
                : Component.translatable("fieldframe.energy.stored", be.buffer().stored(), be.buffer().capacity()));
        return InteractionResult.CONSUME;
    }
}
