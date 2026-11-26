package dev.fieldframe.frameworks;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;

/** Gantry Rail: the ring the Gantry Head runs on. Right-click to hear from its controller. */
public final class GantryRailBlock extends Block {
    public GantryRailBlock(Properties props) {
        super(props);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (level.isClientSide) {
            return InteractionResult.SUCCESS;
        }
        GantryControllerEntity c = findController(level, pos);
        if (c == null) {
            player.sendSystemMessage(net.minecraft.network.chat.Component.translatable("fieldframe_frameworks.gantry.no_controller"));
        } else {
            c.describe(player);
        }
        return InteractionResult.SUCCESS;
    }

    /** Walks the ring (same level, up to 256 rails) to its controller. */
    static GantryControllerEntity findController(Level level, BlockPos start) {
        Set<BlockPos> seen = new HashSet<>();
        Deque<BlockPos> q = new ArrayDeque<>();
        q.add(start);
        seen.add(start);
        while (!q.isEmpty() && seen.size() < 256) {
            BlockPos p = q.poll();
            if (level.getBlockEntity(p) instanceof GantryControllerEntity c) {
                return c;
            }
            for (Direction d : Direction.Plane.HORIZONTAL) {
                BlockPos n = p.relative(d);
                if (!seen.contains(n) && level.isLoaded(n) && (level.getBlockState(n).getBlock() instanceof GantryRailBlock
                        || level.getBlockEntity(n) instanceof GantryControllerEntity)) {
                    seen.add(n);
                    q.add(n);
                }
            }
        }
        return null;
    }
}
