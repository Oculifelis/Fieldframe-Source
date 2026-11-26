package dev.fieldframe.forge.feature.endertech;

import dev.fieldframe.core.endertech.TankCore;
import dev.fieldframe.core.endertech.TankStructure;
import dev.fieldframe.forge.adapter.McVariantCodec;
import dev.fieldframe.forge.adapter.NbtBridge;
import dev.fieldframe.forge.feature.FeatureContent;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

/** Ender Tank controller: owns the {@link TankCore}, validates the structure and links valves and energy inputs. */
public final class TankControllerEntity extends BlockEntity {
    private final TankCore core = new TankCore();
    private TankStructure.Result last;
    private boolean recheck = true;
    private long nextCheck;

    public TankControllerEntity(BlockPos pos, BlockState state) {
        super(FeatureContent.TANK_CONTROLLER_BE.get(), pos, state);
        core.setOnChange(this::setChanged);
    }

    public TankCore core() {
        return core;
    }

    public boolean hasContents() {
        return core.fluid().amount() > 0 || core.energy().stored() > 0;
    }

    /** Marks controllers within the maximum tank size of {@code pos} for re-validation (bounded scan of loaded BEs). */
    public static void requestRecheck(Level level, BlockPos pos) {
        if (level.isClientSide) {
            return;
        }
        int r = 10;
        for (BlockPos p : BlockPos.betweenClosed(pos.offset(-r, -r, -r), pos.offset(r, r, r))) {
            if (level.isLoaded(p) && level.getBlockEntity(p) instanceof TankControllerEntity c) {
                c.recheck = true;
            }
        }
    }

    void serverTick() {
        if (recheck || level.getGameTime() >= nextCheck) {
            recheck();
        }
    }

    public void recheck() {
        recheck = false;
        nextCheck = level.getGameTime() + 100;
        last = TankStructure.validate(worldPosition.getX(), worldPosition.getY(), worldPosition.getZ(),
                (x, y, z) -> TankPartBlock.partAt(level, new BlockPos(x, y, z)));
        core.applyStructure(last);
        if (last.valid()) {
            for (BlockPos p : BlockPos.betweenClosed(last.minX(), last.minY(), last.minZ(), last.maxX(), last.maxY(), last.maxZ())) {
                if (level.getBlockEntity(p) instanceof TankPortEntity port) {
                    port.link(worldPosition);
                }
            }
        }
    }

    public boolean formed() {
        return last != null && last.valid();
    }

    public List<Component> describe() {
        List<Component> out = new ArrayList<>();
        if (last != null && last.valid()) {
            out.add(Component.translatable("fieldframe.tank.formed", last.sizeX(), last.sizeY(), last.sizeZ()));
        } else {
            String p = last == null || last.problem() == null ? "not_checked" : last.problem();
            Object[] args = last == null ? new Object[0] : java.util.Arrays.stream(last.args()).boxed().toArray();
            out.add(Component.translatable("fieldframe.tank.problem." + p, args));
        }
        String fluid = core.fluid().fluid() == null ? "-" : core.fluid().fluid().id().toString();
        out.add(Component.translatable("fieldframe.tank.contents", fluid, core.fluid().amount(), core.fluid().capacity()));
        out.add(Component.translatable("fieldframe.energy.stored", core.energy().stored(), core.energy().capacity()));
        return out;
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.put("tank", NbtBridge.toTag(core.save(McVariantCodec.INSTANCE)));
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        if (tag.contains("tank")) {
            core.load(NbtBridge.fromTag(tag.getCompound("tank")), McVariantCodec.INSTANCE);
        }
        recheck = true;
    }
}
