package dev.fieldframe.neoforge.feature.endertech;

import dev.fieldframe.core.endertech.TankStructure;
import dev.fieldframe.neoforge.feature.FeatureContent;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.BlockHitResult;

import java.util.List;

/**
 * One Ender Tank part. Frames and glass are plain blocks; the controller, valves and energy inputs carry block
 * entities. Any part change makes nearby controllers re-check their structure. A broken controller keeps its fluid
 * and RF in the dropped item (block entity data), so contents are never voided.
 */
public class TankPartBlock extends BaseEntityBlock {
    private final TankStructure.Part part;

    public TankPartBlock(TankStructure.Part part, Properties p) {
        super(p);
        this.part = part;
    }

    public TankStructure.Part part() {
        return part;
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return switch (part) {
            case CONTROLLER -> new TankControllerEntity(pos, state);
            case VALVE, ENERGY_INPUT -> new TankPortEntity(pos, state);
            default -> null;
        };
    }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return level.isClientSide() || part != TankStructure.Part.CONTROLLER || type != FeatureContent.TANK_CONTROLLER_BE.get() ? null
                : (l, p, s, be) -> ((TankControllerEntity) be).serverTick();
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState old, boolean moving) {
        super.onPlace(state, level, pos, old, moving);
        TankControllerEntity.requestRecheck(level, pos);
    }

    @Override
    protected void affectNeighborsAfterRemoval(BlockState state, net.minecraft.server.level.ServerLevel level, BlockPos pos, boolean moving) {
        super.affectNeighborsAfterRemoval(state, level, pos, moving);
        TankControllerEntity.requestRecheck(level, pos);
    }

    @Override
    @SuppressWarnings("deprecation")
    protected List<ItemStack> getDrops(BlockState state, LootParams.Builder params) {
        List<ItemStack> drops = super.getDrops(state, params);
        if (part == TankStructure.Part.CONTROLLER && params.getOptionalParameter(LootContextParams.BLOCK_ENTITY) instanceof TankControllerEntity be
                && be.hasContents()) {
            for (ItemStack s : drops) {
                if (s.is(asItem())) {
                    CompoundTag tag = be.saveCustomOnly(be.getLevel().registryAccess());
                    s.set(net.minecraft.core.component.DataComponents.BLOCK_ENTITY_DATA,
                            net.minecraft.world.item.component.TypedEntityData.of(be.getType(), tag));
                }
            }
        }
        return drops;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (level.isClientSide()) {
            return InteractionResult.SUCCESS;
        }
        TankControllerEntity c = level.getBlockEntity(pos) instanceof TankControllerEntity tc ? tc
                : level.getBlockEntity(pos) instanceof TankPortEntity port ? port.controller() : null;
        if (c == null) {
            player.sendOverlayMessage(Component.translatable("fieldframe.tank.not_connected"));
            return InteractionResult.CONSUME;
        }
        c.recheck();
        if (c.formed()) {
            dev.fieldframe.neoforge.chassis.FfAdvancements.award(player, dev.fieldframe.core.chassis.Milestones.ENDER_TANK);
        }
        for (Component line : c.describe()) {
            player.sendOverlayMessage(line);
        }
        return InteractionResult.CONSUME;
    }

    /** Part kind at a position, used by structure validation (unloaded = OTHER). */
    public static TankStructure.Part partAt(BlockGetter level, BlockPos pos) {
        if (level instanceof Level l && !l.isLoaded(pos)) {
            return TankStructure.Part.OTHER;
        }
        BlockState s = level.getBlockState(pos);
        if (s.getBlock() instanceof TankPartBlock b) {
            return b.part();
        }
        return s.isAir() ? TankStructure.Part.AIR : TankStructure.Part.OTHER;
    }
}
