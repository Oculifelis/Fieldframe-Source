package dev.fieldframe.neoforge.feature.endertech;

import dev.fieldframe.core.endertech.TankStructure;
import dev.fieldframe.neoforge.adapter.NeoHandlers;
import dev.fieldframe.neoforge.feature.FeatureContent;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.transfer.energy.EnergyHandler;
import net.neoforged.neoforge.transfer.ResourceHandler;
import net.neoforged.neoforge.transfer.fluid.FluidResource;

/**
 * Valve (fluid in/out, 1 RF per mB) or Energy Input (RF in) of an Ender Tank. Delegates to the linked controller,
 * resolved on every access, so a broken or unformed tank exposes nothing usable.
 */
public final class TankPortEntity extends BlockEntity {
    private BlockPos controllerPos;
    private final ResourceHandler<FluidResource> fluidCap;
    private final EnergyHandler energyCap;

    public TankPortEntity(BlockPos pos, BlockState state) {
        super(FeatureContent.TANK_PORT_BE.get(), pos, state);
        fluidCap = new NeoHandlers.ExposedFluids(() -> {
            TankControllerEntity c = controller();
            return c == null ? null : c.core().valve();
        }, journal());
        energyCap = new NeoHandlers.ExposedEnergy(() -> {
            TankControllerEntity c = controller();
            return c == null ? null : c.core().energyInput(() -> level == null ? 0 : level.getGameTime());
        }, journal());
    }

    /** Transaction journal over whichever controller this port is linked to at snapshot time. */
    private NeoHandlers.ChassisJournal journal() {
        return new NeoHandlers.ChassisJournal(() -> {
            TankControllerEntity c = controller();
            return c == null ? null : new Object[] {c, c.core().snapshot()};
        }, s -> {
            if (s instanceof Object[] a && a[0] instanceof TankControllerEntity c) {
                c.core().restore(a[1]);
            }
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

    public static ResourceHandler<FluidResource> fluidCap(TankPortEntity be, Direction side) {
        return side != null && be.part() == TankStructure.Part.VALVE ? be.fluidCap : null;
    }

    public static EnergyHandler energyCap(TankPortEntity be, Direction side) {
        return side != null && be.part() == TankStructure.Part.ENERGY_INPUT ? be.energyCap : null;
    }

    @Override
    protected void saveAdditional(net.minecraft.world.level.storage.ValueOutput out) {
        super.saveAdditional(out);
        if (controllerPos != null) {
            out.store("controller", BlockPos.CODEC, controllerPos);
        }
    }

    @Override
    protected void loadAdditional(net.minecraft.world.level.storage.ValueInput in) {
        super.loadAdditional(in);
        controllerPos = in.read("controller", BlockPos.CODEC).orElse(null);
    }
}
