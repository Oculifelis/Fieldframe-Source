package dev.fieldframe.forge.feature.endertech;

import dev.fieldframe.core.endertech.TankStructure;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.PipeBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Ender Tank glass. Six connection properties record which neighbours are tank glass too; the multipart blockstate
 * hides the steel border on each edge that meets more glass, so a wall of glass reads as one pane. Faces between two
 * glass blocks are not drawn, like vanilla glass.
 */
public class TankGlassBlock extends TankPartBlock {
    public TankGlassBlock(Properties p) {
        super(TankStructure.Part.GLASS, p);
        BlockState s = stateDefinition.any();
        for (BooleanProperty prop : PipeBlock.PROPERTY_BY_DIRECTION.values()) {
            s = s.setValue(prop, false);
        }
        registerDefaultState(s);
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(PipeBlock.NORTH, PipeBlock.EAST, PipeBlock.SOUTH, PipeBlock.WEST, PipeBlock.UP, PipeBlock.DOWN);
    }

    /** Connection state for a position, read from all six neighbours. */
    public BlockState connected(BlockState state, BlockGetter level, BlockPos pos) {
        for (Direction d : Direction.values()) {
            state = state.setValue(PipeBlock.PROPERTY_BY_DIRECTION.get(d), level.getBlockState(pos.relative(d)).is(this));
        }
        return state;
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext ctx) {
        return connected(defaultBlockState(), ctx.getLevel(), ctx.getClickedPos());
    }

    @Override
    public BlockState updateShape(BlockState state, Direction dir, BlockState neighbourState, LevelAccessor level, BlockPos pos,
                                  BlockPos neighbourPos) {
        return state.setValue(PipeBlock.PROPERTY_BY_DIRECTION.get(dir), neighbourState.is(this));
    }

    @Override
    public void onPlace(BlockState state, Level level, BlockPos pos, BlockState old, boolean moving) {
        super.onPlace(state, level, pos, old, moving);
        // Placed without a placement context (commands, structures, other mods): work out the connections now.
        if (!old.is(this)) {
            BlockState fixed = connected(state, level, pos);
            if (fixed != state) {
                level.setBlock(pos, fixed, Block.UPDATE_CLIENTS);
            }
        }
    }

    @Override
    public boolean skipRendering(BlockState state, BlockState neighbour, Direction dir) {
        return neighbour.is(this) || super.skipRendering(state, neighbour, dir);
    }

    @Override
    public VoxelShape getVisualShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        return Shapes.empty();
    }

    @Override
    public float getShadeBrightness(BlockState state, BlockGetter level, BlockPos pos) {
        return 1.0F;
    }

    @Override
    public boolean propagatesSkylightDown(BlockState state, BlockGetter level, BlockPos pos) {
        return true;
    }
}
