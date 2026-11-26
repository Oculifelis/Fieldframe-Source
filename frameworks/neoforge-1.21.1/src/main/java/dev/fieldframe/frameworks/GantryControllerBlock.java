package dev.fieldframe.frameworks;

import net.minecraft.world.level.block.state.BlockState;
import dev.fieldframe.neoforge.api.frame.ModuleSocketBlock;
import dev.fieldframe.neoforge.api.frame.ModuleSocketBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;

/**
 * Gantry Controller: one block of a ring of Gantry Rails. It works like a chassis (every side takes a module) with
 * its own storage, and drives the Gantry Head over the ring: the module on its top decides the program.
 */
public final class GantryControllerBlock extends ModuleSocketBlock {
    public GantryControllerBlock(Properties props) {
        super(props, FwContent.GANTRY_BE);
    }

    /** Every side takes a module, like a chassis. */
    @Override
    protected boolean usableSide(Level level, BlockPos pos, ModuleSocketBlockEntity be, Direction side) {
        return true;
    }

    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean moving) {
        if (!state.is(newState.getBlock()) && level.getBlockEntity(pos) instanceof GantryControllerEntity be) {
            be.broken(pos, state);
        }
        super.onRemove(state, level, pos, newState, moving);
    }
}
