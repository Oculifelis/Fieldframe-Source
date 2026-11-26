package dev.fieldframe.forge.feature.endertech;

import dev.fieldframe.core.endertech.TankStructure;
import dev.fieldframe.forge.adapter.ForgeHandlers;
import dev.fieldframe.forge.feature.FeatureContent;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.energy.IEnergyStorage;
import net.minecraftforge.fluids.capability.IFluidHandler;

/**
 * Valve (fluid in/out, 1 RF per mB) or Energy Input (RF in) of an Ender Tank. Delegates to the linked controller,
 * resolved on every access, so a broken or unformed tank exposes nothing usable.
 */
public final class TankPortEntity extends BlockEntity {
    private BlockPos controllerPos;
    private final LazyOptional<IFluidHandler> fluidCap;
    private final LazyOptional<IEnergyStorage> energyCap;

    public TankPortEntity(BlockPos pos, BlockState state) {
        super(FeatureContent.TANK_PORT_BE.get(), pos, state);
        fluidCap = LazyOptional.of(() -> new ForgeHandlers.ExposedFluids(() -> {
            TankControllerEntity c = controller();
            return c == null ? null : c.core().valve();
        }));
        energyCap = LazyOptional.of(() -> new ForgeHandlers.ExposedEnergy(() -> {
            TankControllerEntity c = controller();
            return c == null ? null : c.core().energyInput(() -> level == null ? 0 : level.getGameTime());
        }));
    }

    private TankStructure.Part part() {
        return ((TankPartBlock) getBlockState().getBlock()).part();
    }

    void link(BlockPos controller) {
        if (!controller.equals(controllerPos)) {
            controllerPos = controller.immutable();
            setChanged();
        }
    }

    public TankControllerEntity controller() {
        if (controllerPos == null || level == null || !level.isLoaded(controllerPos)) {
            return null;
        }
        return level.getBlockEntity(controllerPos) instanceof TankControllerEntity c && c.core().formed() ? c : null;
    }

    @Override
    public <T> LazyOptional<T> getCapability(Capability<T> cap, Direction side) {
        if (side != null && cap == ForgeCapabilities.FLUID_HANDLER && part() == TankStructure.Part.VALVE) {
            return fluidCap.cast();
        }
        if (side != null && cap == ForgeCapabilities.ENERGY && part() == TankStructure.Part.ENERGY_INPUT) {
            return energyCap.cast();
        }
        return super.getCapability(cap, side);
    }

    @Override
    public void invalidateCaps() {
        super.invalidateCaps();
        fluidCap.invalidate();
        energyCap.invalidate();
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        if (controllerPos != null) {
            tag.put("controller", NbtUtils.writeBlockPos(controllerPos));
        }
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        controllerPos = tag.contains("controller") ? NbtUtils.readBlockPos(tag.getCompound("controller")) : null;
    }
}
