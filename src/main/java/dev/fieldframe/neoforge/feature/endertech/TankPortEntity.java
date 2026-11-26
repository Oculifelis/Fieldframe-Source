package dev.fieldframe.neoforge.feature.endertech;

import dev.fieldframe.core.endertech.TankStructure;
import dev.fieldframe.neoforge.adapter.NeoHandlers;
import dev.fieldframe.neoforge.feature.FeatureContent;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.energy.IEnergyStorage;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;

/**
 * Valve (fluid in/out, 1 RF per mB) or Energy Input (RF in) of an Ender Tank. Delegates to the linked controller,
 * resolved on every access, so a broken or unformed tank exposes nothing usable.
 */
public final class TankPortEntity extends BlockEntity {
    private BlockPos controllerPos;
    private final IFluidHandler fluidCap;
    private final IEnergyStorage energyCap;

    public TankPortEntity(BlockPos pos, BlockState state) {
        super(FeatureContent.TANK_PORT_BE.get(), pos, state);
        fluidCap = new NeoHandlers.ExposedFluids(() -> {
            TankControllerEntity c = controller();
            return c == null ? null : c.core().valve();
        });
        energyCap = new NeoHandlers.ExposedEnergy(() -> {
            TankControllerEntity c = controller();
            return c == null ? null : c.core().energyInput(() -> level == null ? 0 : level.getGameTime());
        });
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

    public static IFluidHandler fluidCap(TankPortEntity be, Direction side) {
        return side != null && be.part() == TankStructure.Part.VALVE ? be.fluidCap : null;
    }

    public static IEnergyStorage energyCap(TankPortEntity be, Direction side) {
        return side != null && be.part() == TankStructure.Part.ENERGY_INPUT ? be.energyCap : null;
    }

    @Override
    protected void saveAdditional(CompoundTag tag, net.minecraft.core.HolderLookup.Provider regs) {
        super.saveAdditional(tag, regs);
        if (controllerPos != null) {
            tag.put("controller", NbtUtils.writeBlockPos(controllerPos));
        }
    }

    @Override
    protected void loadAdditional(CompoundTag tag, net.minecraft.core.HolderLookup.Provider regs) {
        super.loadAdditional(tag, regs);
        controllerPos = NbtUtils.readBlockPos(tag, "controller").orElse(null);
    }
}
