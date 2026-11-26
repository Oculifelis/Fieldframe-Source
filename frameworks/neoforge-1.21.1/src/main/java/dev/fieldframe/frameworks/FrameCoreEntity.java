package dev.fieldframe.frameworks;

import dev.fieldframe.api.frame.FrameStructure;
import dev.fieldframe.api.frame.MachineHub;
import dev.fieldframe.neoforge.api.FieldframeForgeApi;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.Containers;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

/**
 * The Frame Core's block entity. Every two seconds (and when used) it checks the frame. A valid frame is formed: the
 * shared storage is sized from the components inside, every wall casing becomes a socket linked to it, edge and
 * corner casings are plain shell. A frame that stops being valid comes apart: its sockets go dormant, the contents
 * stay in the core. Breaking the core drops the items stored; fluid and RF are lost, as when a chassis breaks.
 */
public final class FrameCoreEntity extends BlockEntity {
    private final MachineHub hub = FieldframeForgeApi.newMachineHub();
    private final Foundry foundry = new Foundry();
    private final Plasma plasma = new Plasma();
    private final ArcFurnace arc = new ArcFurnace();
    /** Cooling Fins inside (a Cooling Tower when above 0). */
    private int fins;
    private final List<BlockPos> coils = new ArrayList<>();
    private final List<dev.fieldframe.api.world.WorldPos> bound = new ArrayList<>();
    /** Ticks since the frame formed; the plasma waits a few ticks for its modules to report after forming or loading. */
    private int warm;
    private FrameStructure.Result last;
    private final List<BlockPos> sockets = new ArrayList<>();
    private boolean formed;
    private boolean recheck = true;
    /** Tuning values this frame was sized with (a data reload that changes them resizes it). */
    private int tuningRevision = -1;
    private long nextCheck;

    public FrameCoreEntity(BlockPos pos, BlockState state) {
        super(FwContent.CORE_BE.get(), pos, state);
    }

    public MachineHub hub() {
        return hub;
    }

    public boolean formed() {
        return formed;
    }

    public Foundry foundry() {
        return foundry;
    }

    public Plasma plasma() {
        return plasma;
    }

    public ArcFurnace arcFurnace() {
        return arc;
    }

    /** Arc Electrodes inside (an Arc Furnace when above 0). */
    public int arcElectrodes() {
        return formed ? arc.electrodes() : 0;
    }

    public int coolingFins() {
        return formed ? fins : 0;
    }

    /** True when the frame is a Starcore (it has containment coils inside). */
    public boolean isStarcore() {
        return formed && plasma.coils() > 0;
    }

    public FrameStructure.Result lastCheck() {
        return last;
    }

    public void requestCheck() {
        recheck = true;
    }

    static void serverTick(Level level, BlockPos pos, BlockState state, FrameCoreEntity be) {
        if (be.recheck || level.getGameTime() >= be.nextCheck || be.tuningRevision != FwTuning.revision()) {
            be.tuningRevision = FwTuning.revision();
            be.check();
        }
        if (be.formed && be.foundry.crucibles() > 0 && be.foundry.tick(level, be.hub)) {
            be.setChanged();
        }
        if (be.isStarcore()) {
            be.starcoreTick();
        }
        if (be.formed && be.fins > 0) {
            CoolingTower.tick(be.hub, be.fins);
        }
        if (be.formed && be.arc.electrodes() > 0 && be.arc.tick(level, be.hub, be.last)) {
            be.setChanged();
        }
        if (be.hub.consumeDirty()) {
            be.setChanged();
        }
    }

    /** Checks the frame now and forms, re-sizes or takes it apart as needed. */
    public void check() {
        if (level == null || level.isClientSide) {
            return;
        }
        recheck = false;
        nextCheck = level.getGameTime() + 40;
        BlockPos p = worldPosition;
        FrameStructure.Result r = FrameStructure.validate(p.getX(), p.getY(), p.getZ(),
                (x, y, z) -> GrandFrame.part(level, x, y, z), GrandFrame.minSize(), GrandFrame.maxSize());
        last = r;
        if (!r.valid()) {
            if (formed) {
                unform();
            }
            return;
        }
        List<BlockPos> coilsNow = new ArrayList<>();
        long[] cap = GrandFrame.capacity(level, r, coilsNow);
        if (cap[0] != hub.slotsPerLane() || cap[1] != hub.mbPerTank() || cap[2] != hub.rfCapacity()) {
            drop(hub.resize((int) Math.min(Integer.MAX_VALUE, cap[0]), cap[1], cap[2]));
        }
        foundry.setCrucibles((int) cap[3]);
        fins = (int) cap[5];
        arc.setElectrodes((int) cap[6]);
        foundry.trimTo(foundry.capacity());
        coils.clear();
        coils.addAll(coilsNow);
        plasma.setCoils(coils.size());
        List<BlockPos> now = new ArrayList<>();
        for (int x = r.minX(); x <= r.maxX(); x++) {
            for (int y = r.minY(); y <= r.maxY(); y++) {
                for (int z = r.minZ(); z <= r.maxZ(); z++) {
                    if (r.planes(x, y, z) == 0) {
                        continue;
                    }
                    BlockPos q = new BlockPos(x, y, z);
                    if (!(level.getBlockEntity(q) instanceof FrameCasingEntity casing)) {
                        continue;
                    }
                    int[] out = r.outward(x, y, z);
                    if (out == null) {
                        casing.clearSocket();
                    } else {
                        casing.link(hub, Direction.getNearest((double) out[0], out[1], out[2]));
                        now.add(q);
                    }
                }
            }
        }
        for (BlockPos old : sockets) {
            if (!now.contains(old) && level.isLoaded(old) && level.getBlockEntity(old) instanceof FrameCasingEntity casing) {
                casing.clearSocket();
            }
        }
        sockets.clear();
        sockets.addAll(now);
        setFormed(true);
        rebind();
        setChanged();
    }

    /** Starcore modules on the sockets find this plasma by their address. */
    private void rebind() {
        Starcores.unbind(bound, plasma);
        bound.clear();
        if (plasma.coils() > 0) {
            String dim = level.dimension().location().toString();
            for (BlockPos s : sockets) {
                bound.add(new dev.fieldframe.api.world.WorldPos(dim, s.getX(), s.getY(), s.getZ()));
            }
            Starcores.bind(bound, plasma);
        }
    }

    private void unbind() {
        Starcores.unbind(bound, plasma);
        bound.clear();
    }

    private void starcoreTick() {
        if (warm < 5) {
            warm++;
            plasma.hold();
            return;
        }
        boolean wasLit = plasma.lit();
        Plasma.Event ev = plasma.tick(hub.energy());
        if (ev == Plasma.Event.ESCAPED) {
            escaped();
        }
        if (ev != Plasma.Event.NONE || wasLit != plasma.lit() || plasma.lit() && level.getGameTime() % 20 == 0) {
            setChanged();
        }
    }

    /** The plasma got out: the coils wear and every third escape breaks one; explosions only when the server allows. */
    private void escaped() {
        double x = worldPosition.getX() + 0.5;
        double y = worldPosition.getY() + 0.5;
        double z = worldPosition.getZ() + 0.5;
        level.playSound(null, worldPosition, net.minecraft.sounds.SoundEvents.GENERIC_EXPLODE.value(), net.minecraft.sounds.SoundSource.BLOCKS, 1.5f, 0.6f);
        if (level instanceof net.minecraft.server.level.ServerLevel sl) {
            sl.sendParticles(net.minecraft.core.particles.ParticleTypes.EXPLOSION, x, y, z, 1, 0, 0, 0, 0);
            sl.sendParticles(net.minecraft.core.particles.ParticleTypes.END_ROD, x, y, z, 40, 1.5, 1.5, 1.5, 0.2);
        }
        if (plasma.wear() >= Plasma.escapesPerCoil() && !coils.isEmpty()) {
            BlockPos coil = coils.remove(coils.size() - 1);
            level.destroyBlock(coil, false);
            plasma.coilBroke();
            recheck = true;
        }
        if (FwConfig.STARCORE_EXPLODES.get()) {
            level.explode(null, x, y, z, FwConfig.STARCORE_EXPLOSION_POWER.get().floatValue(), net.minecraft.world.level.Level.ExplosionInteraction.BLOCK);
        }
    }

    private void unform() {
        unbind();
        if (plasma.lit()) {
            // Taking a lit Starcore apart lets the plasma out.
            plasma.breach();
            escaped();
        }
        for (BlockPos s : sockets) {
            if (level.isLoaded(s) && level.getBlockEntity(s) instanceof FrameCasingEntity casing) {
                casing.clearSocket();
            }
        }
        sockets.clear();
        setFormed(false);
        setChanged();
    }

    /** The one formed/unformed transition: the plasma warm-up restarts whenever the frame forms or comes apart. */
    private void setFormed(boolean now) {
        if (formed != now) {
            warm = 0;
        }
        formed = now;
    }

    private void drop(List<MachineHub.Spill> spill) {
        for (MachineHub.Spill s : spill) {
            ItemStack st = FieldframeForgeApi.toStack(s.item(), s.count());
            if (!st.isEmpty()) {
                Containers.dropItemStack(level, worldPosition.getX() + 0.5, worldPosition.getY() + 1.0, worldPosition.getZ() + 0.5, st);
            }
        }
    }

    /** Chat lines describing the frame: its size and storage, or what is wrong. */
    public void describe(Player player) {
        FrameStructure.Result r = last;
        if (r == null || !r.valid()) {
            String problem = r == null || r.problem() == null ? "no_core" : r.problem();
            Object[] args = r == null ? new Object[0] : java.util.Arrays.stream(r.args()).boxed().toArray();
            player.sendSystemMessage(Component.translatable("fieldframe_frameworks.frame.not_formed",
                    Component.translatable("fieldframe_frameworks.frame.problem." + problem, args)));
            return;
        }
        player.sendSystemMessage(Component.translatable("fieldframe_frameworks.frame.formed", r.sizeX(), r.sizeY(), r.sizeZ(), sockets.size()));
        player.sendSystemMessage(Component.translatable("fieldframe_frameworks.frame.storage", hub.slotsPerLane(),
                String.format("%,d", hub.mbPerTank()), String.format("%,d", hub.rfCapacity()), String.format("%,d", hub.rfStored())));
        if (plasma.coils() > 0) {
            if (plasma.lit()) {
                int share = plasma.field() <= 0 ? 100 : (int) Math.round(plasma.pressure() / plasma.field() * 100);
                player.sendSystemMessage(Component.translatable("fieldframe_frameworks.starcore.lit", Math.round(plasma.temperature()),
                        share, String.format("%,d", plasma.lastPower()), String.format("%.0f", plasma.fuel())));
            } else {
                player.sendSystemMessage(Component.translatable("fieldframe_frameworks.starcore.out", String.format("%.0f", plasma.fuel()),
                        String.format("%.0f", Plasma.ignitionFuel()), String.format("%,d", Plasma.ignitionRf())));
            }
            player.sendSystemMessage(Component.translatable("fieldframe_frameworks.starcore.coils", plasma.coils(),
                    Math.round(plasma.coils() * Plasma.fieldPerCoil()), plasma.wear(), Plasma.escapesPerCoil()));
        }
        if (arc.electrodes() > 0) {
            ArcWork.Result a = arc.last();
            player.sendSystemMessage(Component.translatable("fieldframe_frameworks.arc_furnace.status", arc.electrodes(),
                    String.format("%,d", arc.rfPerArc()), String.format("%,d", ArcFurnace.itemsPerElectrode() * arc.electrodes()),
                    a.batches(), a.smelted()));
        }
        if (foundry.crucibles() > 0) {
            player.sendSystemMessage(Component.translatable("fieldframe_frameworks.foundry.status", foundry.crucibles(),
                    Math.round(foundry.temperature()), String.format("%,d", foundry.used()), String.format("%,d", foundry.capacity())));
            foundry.pool().forEach((f, a) -> {
                net.minecraft.world.level.material.Fluid fl = Foundry.fluid(f);
                Component name = fl == null ? Component.literal(f.toString()) : fl.getFluidType().getDescription();
                player.sendSystemMessage(Component.translatable("fieldframe_frameworks.foundry.pool_line", name, String.format("%,d", a),
                        foundry.waiting(f) ? Component.translatable("fieldframe_frameworks.foundry.waiting") : Component.literal("")));
            });
        }
    }

    @Override
    public void onChunkUnloaded() {
        // The shared contents live here: sockets in other chunks go dormant until the frame is checked again.
        if (level != null && !level.isClientSide) {
            unbind();
            for (BlockPos s : sockets) {
                if (level.isLoaded(s) && level.getBlockEntity(s) instanceof FrameCasingEntity casing) {
                    casing.unlink();
                }
            }
        }
        super.onChunkUnloaded();
    }

    /** The block was broken or replaced (called from the block's onRemove). */
    public void broken(BlockPos pos, BlockState state) {
        if (level != null && !level.isClientSide) {
            unform();
            drop(hub.drainAll());
        }
    }

    @Override
    protected void saveAdditional(CompoundTag nbt, net.minecraft.core.HolderLookup.Provider registries) {
        super.saveAdditional(nbt, registries);
        if (level != null) {
            nbt.put("hub", FieldframeForgeApi.hubToTag(hub, level.registryAccess()));
            nbt.put("foundry", FwData.toTag(foundry.save()));
            nbt.put("plasma", FwData.toTag(plasma.save()));
        }
    }

    @Override
    protected void loadAdditional(CompoundTag nbt, net.minecraft.core.HolderLookup.Provider registries) {
        super.loadAdditional(nbt, registries);
        java.util.Optional.ofNullable(nbt.contains("hub") ? nbt.getCompound("hub") : null).ifPresent(tag -> {
            for (String problem : FieldframeForgeApi.hubFromTag(hub, tag, registries)) {
                Frameworks.LOG.warn("Frame core at {}: {}", worldPosition, problem);
            }
        });
        java.util.Optional.ofNullable(nbt.contains("foundry") ? nbt.getCompound("foundry") : null).ifPresent(tag -> foundry.load(FwData.fromTag(tag)));
        java.util.Optional.ofNullable(nbt.contains("plasma") ? nbt.getCompound("plasma") : null).ifPresent(tag -> plasma.load(FwData.fromTag(tag)));
        warm = 0;
        recheck = true;
    }
}
