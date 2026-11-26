package dev.fieldframe.forge.api.frame;

import dev.fieldframe.forge.chassis.ChassisBlock;
import dev.fieldframe.forge.chassis.ChassisBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

import java.util.function.Supplier;

/**
 * Public API: a block that hosts one module on its outward side, as part of a multiblock (see
 * {@link ModuleSocketBlockEntity}). Install, configure and remove work only on the outward side of a socket; the
 * block cannot be rotated with the Configurator. Register its block entity type, call
 * {@code ModuleHostClientApi.composeModules} on the client so faceplates are drawn.
 */
public class ModuleSocketBlock extends ChassisBlock {
    private final Supplier<? extends BlockEntityType<? extends ModuleSocketBlockEntity>> type;

    public ModuleSocketBlock(Properties props, Supplier<? extends BlockEntityType<? extends ModuleSocketBlockEntity>> type) {
        super(props);
        this.type = type;
    }

    @Override
    protected BlockEntityType<?> blockEntityType() {
        return type.get();
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return type.get().create(pos, state);
    }

    @Override
    protected final boolean usableFace(Level level, BlockPos pos, ChassisBlockEntity be, Direction side) {
        return be instanceof ModuleSocketBlockEntity s && usableSide(level, pos, s, side);
    }

    /** Which sides players may use (install, configure, remove). Default: only the outward side of a socket. */
    protected boolean usableSide(Level level, BlockPos pos, ModuleSocketBlockEntity be, Direction side) {
        return be.isSocket() && be.outward() == side;
    }

    @Override
    protected boolean rotatable() {
        return false;
    }
}
