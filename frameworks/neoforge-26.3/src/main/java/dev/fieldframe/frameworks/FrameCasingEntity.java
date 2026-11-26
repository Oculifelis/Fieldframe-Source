package dev.fieldframe.frameworks;

import dev.fieldframe.neoforge.api.frame.ModuleSocketBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/** A Frame Casing's block entity: a module socket while its frame is formed, dormant otherwise. */
public final class FrameCasingEntity extends ModuleSocketBlockEntity {
    public FrameCasingEntity(BlockPos pos, BlockState state) {
        super(FwContent.CASING_BE.get(), pos, state);
    }
}
