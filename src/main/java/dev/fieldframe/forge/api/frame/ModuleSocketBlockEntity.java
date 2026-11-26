package dev.fieldframe.forge.api.frame;

import dev.fieldframe.api.frame.MachineHub;
import dev.fieldframe.core.chassis.MachineHubImpl;
import dev.fieldframe.forge.chassis.ChassisBlock;
import dev.fieldframe.forge.chassis.ChassisBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.nbt.CompoundTag;

/**
 * Public API: the block entity of a module socket, one side of a multiblock (a Grand Frame wall block) that hosts a
 * module like a chassis side does. Everything a chassis does works here (modules, the machine screen, rings, lit
 * faceplates, Jade, ports to the outside), but only the {@link #outward()} side takes a module, and while
 * {@link #link linked} its modules work from the multiblock's {@link MachineHub}. An unlinked socket is dormant: its
 * modules stay installed but pause and expose nothing. A socket never saves or drops the hub's contents.
 */
public class ModuleSocketBlockEntity extends ChassisBlockEntity {
    private Direction outward;
    private MachineHub hub;
    private boolean savedLinked;

    public ModuleSocketBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
        core().setAwaitingLink(true);
    }

    /** The side that takes a module, or null when this block is not a socket right now (unformed, edge, corner). */
    public Direction outward() {
        return outward;
    }

    public boolean isSocket() {
        return outward != null;
    }

    public boolean isLinked() {
        return hub != null;
    }

    public MachineHub hub() {
        return hub;
    }

    /** Makes this block a socket facing {@code outward} whose modules work from {@code hub}. */
    public void link(MachineHub hub, Direction outward) {
        if (this.hub != null && this.hub != hub) {
            unlink();
        }
        this.outward = outward;
        if (level != null && outward.getAxis().isHorizontal() && getBlockState().hasProperty(ChassisBlock.FACING)
                && getBlockState().getValue(ChassisBlock.FACING) != outward) {
            level.setBlock(worldPosition, getBlockState().setValue(ChassisBlock.FACING, outward), 3);
            setFront(outward);
        }
        if (this.hub == null) {
            ((MachineHubImpl) hub).link(core());
            this.hub = hub;
        }
        savedLinked = true;
        setChanged();
    }

    /** The multiblock came apart (or unloaded): modules stay installed but go dormant until linked again. */
    public void unlink() {
        if (hub != null) {
            ((MachineHubImpl) hub).unlink(core());
            hub = null;
        }
        core().setAwaitingLink(true);
        setChanged();
    }

    /** Not a socket at all any more (an edge or corner, or no multiblock). */
    public void clearSocket() {
        unlink();
        outward = null;
        savedLinked = false;
        setChanged();
    }

    /** Runs every server tick after the socket's modules; override to add a multiblock's own work. */
    @Override
    protected void afterServerTick() {
    }

    @Override
    public void onChunkUnloaded() {
        if (hub != null) {
            ((MachineHubImpl) hub).unlink(core());
            hub = null;
        }
        super.onChunkUnloaded();
    }

    @Override
    public void setRemoved() {
        if (hub != null) {
            ((MachineHubImpl) hub).unlink(core());
            hub = null;
        }
        super.setRemoved();
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.putInt("ff_outward", outward == null ? -1 : outward.get3DDataValue());
        tag.putBoolean("ff_linked", savedLinked || hub != null);
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        int o = tag.contains("ff_outward") ? tag.getInt("ff_outward") : -1;
        outward = o < 0 || o > 5 ? null : Direction.from3DDataValue(o);
        savedLinked = tag.getBoolean("ff_linked");
        // Dormant until the multiblock links it again (it saved no contents of its own).
        core().setAwaitingLink(true);
    }
}
