package dev.fieldframe.forge.chassis;

import dev.fieldframe.api.FieldframeRegistry;
import dev.fieldframe.api.chassis.ChassisSpec;
import dev.fieldframe.api.face.LocalFace;
import dev.fieldframe.api.face.Orientation;
import dev.fieldframe.api.face.WorldDir;
import dev.fieldframe.api.id.FfId;
import dev.fieldframe.api.resource.FluidVariant;
import dev.fieldframe.api.resource.ItemVariant;
import dev.fieldframe.core.chassis.ChassisCore;
import dev.fieldframe.core.chassis.InstalledModule;
import dev.fieldframe.core.storage.ItemLane;
import dev.fieldframe.forge.Fieldframe;
import dev.fieldframe.forge.adapter.Dirs;
import dev.fieldframe.forge.adapter.ForgeHandlers;
import dev.fieldframe.forge.adapter.McItemVariant;
import dev.fieldframe.forge.adapter.McVariantCodec;
import dev.fieldframe.forge.adapter.NbtBridge;
import dev.fieldframe.forge.item.ModuleItem;
import dev.fieldframe.forge.registry.FfContent;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.client.model.data.ModelData;
import net.minecraftforge.client.model.data.ModelProperty;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.util.LazyOptional;

import java.util.ArrayList;
import java.util.List;

/**
 * Owns exactly one {@link ChassisCore}. Forwards ticks, capability queries and persistence; never exposes
 * anything on a side-unspecified query or on a face whose module does not declare the matching exposure.
 */
public class ChassisBlockEntity extends BlockEntity {
    public static final ModelProperty<FaceRender> FACES = new ModelProperty<>();

    /** Client render data: module per local face, orientation and a status lamp per face. */
    /**
     * What the client model draws per side: the module, its status, its storage marks (see FaceMarks) and, as a bit
     * per local face, whether it is working (lit faceplate; ChassisCore#workingMask), and the level each face shows
     * in the world (FaceGauge; -1 for none).
     */
    public record FaceRender(FfId[] modules, int[] severity, WorldDir front, byte[][] marks, int working, int[] gauges) {
    }

    private final ChassisCore core;
    private long lastCapRevision = -1;
    @SuppressWarnings("unchecked")
    private final LazyOptional<?>[][] caps = new LazyOptional[3][6];

    // Client-side mirror.
    private FfId[] clientModules = new FfId[6];
    private int[] clientSeverity = new int[6];
    private byte[][] clientMarks = new byte[6][];
    private int clientWorking;
    private int[] clientGauges = {-1, -1, -1, -1, -1, -1};
    private boolean clientQuiet;
    /** Server: the working mask clients last got, and the comparator level neighbours last saw. */
    private int sentWorking = -1;
    /**
     * Server: the gauge levels (FaceGauge#key) and status lamps clients last got, and when; a change resends at most
     * every 4 ticks.
     */
    private long sentGauges;
    private long gaugeSentAt;
    private int lastComparator = -1;
    private boolean lastAnySignal;

    public ChassisBlockEntity(BlockPos pos, BlockState state) {
        this(FfContent.CHASSIS_BE.get(), pos, state);
    }

    /** For blocks that host modules with their own block entity type (see the frame API's module sockets). */
    protected ChassisBlockEntity(net.minecraft.world.level.block.entity.BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
        this.core = new ChassisCore(ChassisSpec.STANDARD, McVariantCodec.INSTANCE, () -> level == null ? 0 : level.getGameTime());
        core.setAddress(pos.toShortString());
        if (state.hasProperty(ChassisBlock.FACING)) {
            core.setOrientation(new Orientation(Dirs.toWorld(state.getValue(ChassisBlock.FACING))));
        }
    }

    public ChassisCore core() {
        return core;
    }

    @Override
    public void setLevel(Level level) {
        super.setLevel(level);
        core.setAddress(level.dimension().location() + "@" + worldPosition.toShortString());
    }

    public ForgeChassisWorld world() {
        return new ForgeChassisWorld(level, worldPosition, core.orientation(), core.ownerId(), core.ownerName());
    }

    // ------------------------------------------------------------------ ticking

    public static void serverTick(Level level, BlockPos pos, BlockState state, ChassisBlockEntity be) {
        if (dev.fieldframe.core.util.PerfProbe.timed(be)) {
            long t0 = System.nanoTime();
            tickNow(level, pos, state, be);
            dev.fieldframe.core.util.PerfProbe.record(System.nanoTime() - t0);
        } else {
            tickNow(level, pos, state, be);
        }
    }

    private static void tickNow(Level level, BlockPos pos, BlockState state, ChassisBlockEntity be) {
        // Bolted Chassis: a part waiting for the others (after loading) tries to link once a second.
        if (be.core.boltedOnDisk() && be.core.waitingForParts()
                && (level.getGameTime() + (pos.asLong() & 15)) % 20 == 0) {
            BoltLinks.tryLink(level, be);
        }
        be.core.tick(be.world());
        for (Object[] s : be.core.drainSpill()) {
            be.dropSpill(s);
        }
        boolean redstone = be.core.consumeRedstoneChanged();
        if (be.core.consumeDirty()) {
            be.setChanged();
        }
        int working = be.core.workingMask();
        if (be.core.revision() != be.lastCapRevision) {
            be.lastCapRevision = be.core.revision();
            be.invalidateFaceCaps();
            level.sendBlockUpdated(pos, state, state, 3);
            dev.fieldframe.core.util.MpProbe.CHASSIS_UPDATES.incrementAndGet();
            level.updateNeighborsAt(pos, state.getBlock());
        } else {
            if (redstone) {
                level.updateNeighborsAt(pos, state.getBlock());
            }
            long gauges = dev.fieldframe.core.chassis.FaceGauge.key(be.core.gaugeLevels()) | ((long) be.statusLamps() << 30);
            boolean gaugesDue = gauges != be.sentGauges && level.getGameTime() - be.gaugeSentAt >= 4;
            if (working != be.sentWorking || gaugesDue) {
                // Lit faceplates and gauges: only the client copy changes, so no neighbour updates.
                level.sendBlockUpdated(pos, state, state, 2);
                dev.fieldframe.core.util.MpProbe.CHASSIS_UPDATES.incrementAndGet();
                be.sentGauges = gauges;
                be.gaugeSentAt = level.getGameTime();
            }
        }
        if (be.sentWorking >= 0 && (working & ~be.sentWorking) != 0) {
            // Advancements: a module just started working (it works, self-powered, hands off).
            java.util.List<String> ids = new java.util.ArrayList<>();
            for (LocalFace f : LocalFace.ALL) {
                InstalledModule m = be.core.module(f);
                if ((working & ~be.sentWorking & (1 << f.ordinal())) != 0 && m != null && !m.isMissing()) {
                    ids.addAll(dev.fieldframe.core.chassis.Milestones.whenWorking(m.definition()));
                }
            }
            FfAdvancements.awardOwner(level, be.core, ids);
        }
        be.sentWorking = working;
        boolean anySignal = false;
        for (int ch = 0; ch < 8 && !anySignal; ch++) {
            anySignal = be.core.control().read(ch) > 0;
        }
        if (anySignal && !be.lastAnySignal) {
            FfAdvancements.awardOwner(level, be.core, java.util.List.of(dev.fieldframe.core.chassis.Milestones.SIGNALS));
        }
        be.lastAnySignal = anySignal;
        int comparator = be.core.comparatorLevel();
        if (comparator != be.lastComparator) {
            be.lastComparator = comparator;
            level.updateNeighbourForOutputSignal(pos, state.getBlock());
        }
        be.afterServerTick();
    }

    /** Faces showing a status lamp (bit per local face: BLOCKED or FAULT), so a machine getting stuck reaches clients. */
    private int statusLamps() {
        int lamps = 0;
        for (LocalFace f : LocalFace.ALL) {
            InstalledModule m = core.module(f);
            if (m != null && dev.fieldframe.core.chassis.FaceGauge.statusLamp(m.status().severity().ordinal()) >= 0) {
                lamps |= 1 << f.ordinal();
            }
        }
        return lamps;
    }

    /** Runs after the chassis' own tick on the server; subclasses (multiblock controllers) add their work here. */
    protected void afterServerTick() {
    }

    private void dropSpill(Object[] s) {
        if (s[0] instanceof ItemVariant v && s[1] instanceof Integer n) {
            ItemStack stack = McItemVariant.toStack(v, n);
            if (!stack.isEmpty()) {
                net.minecraft.world.Containers.dropItemStack(level, worldPosition.getX() + 0.5, worldPosition.getY() + 1.0, worldPosition.getZ() + 0.5, stack);
            }
        } else if (s[0] instanceof FluidVariant f) {
            Fieldframe.LOG.info("Fieldframe chassis at {} discarded {} mB of {} that had nowhere to go", worldPosition, s[1], f.id());
        }
    }

    /** Called when a neighbour changes: wake sleeping ports and re-check redstone. */
    public void onNeighborChanged() {
        core.wakeAll();
    }

    public void setFront(Direction facing) {
        core.setOrientation(new Orientation(Dirs.toWorld(facing)));
        setChanged();
    }

    // ------------------------------------------------------------------ capabilities

    @Override
    public <T> LazyOptional<T> getCapability(Capability<T> cap, Direction side) {
        if (side == null || level == null || level.isClientSide) {
            return super.getCapability(cap, side);
        }
        int kind = cap == ForgeCapabilities.ITEM_HANDLER ? 0 : cap == ForgeCapabilities.FLUID_HANDLER ? 1 : cap == ForgeCapabilities.ENERGY ? 2 : -1;
        if (kind < 0) {
            return super.getCapability(cap, side);
        }
        WorldDir dir = Dirs.toWorld(side);
        boolean present = switch (kind) {
            case 0 -> core.exposedItems(dir) != null;
            case 1 -> core.exposedFluids(dir) != null;
            default -> core.exposedEnergy(dir) != null;
        };
        if (!present) {
            return LazyOptional.empty();
        }
        int i = side.get3DDataValue();
        LazyOptional<?> lo = caps[kind][i];
        if (lo == null || !lo.isPresent()) {
            lo = switch (kind) {
                case 0 -> LazyOptional.of(() -> new ForgeHandlers.ExposedItems(() -> core.exposedItems(dir)));
                case 1 -> LazyOptional.of(() -> new ForgeHandlers.ExposedFluids(() -> core.exposedFluids(dir)));
                default -> LazyOptional.of(() -> new ForgeHandlers.ExposedEnergy(() -> core.exposedEnergy(dir)));
            };
            caps[kind][i] = lo;
        }
        return lo.cast();
    }

    private void invalidateFaceCaps() {
        for (LazyOptional<?>[] row : caps) {
            for (int i = 0; i < row.length; i++) {
                if (row[i] != null) {
                    row[i].invalidate();
                    row[i] = null;
                }
            }
        }
    }

    @Override
    public void invalidateCaps() {
        super.invalidateCaps();
        invalidateFaceCaps();
    }

    // ------------------------------------------------------------------ contents release

    /**
     * Releases everything exactly once (module items, upgrade items, lane contents and settlement spill) and
     * empties the core. Stored fluid and RF are intentionally discarded on destruction (documented).
     */
    public List<ItemStack> releaseContents() {
        List<ItemStack> out = new ArrayList<>();
        var w = level == null ? null : world();
        for (LocalFace f : LocalFace.ALL) {
            InstalledModule m = core.module(f);
            if (m == null) {
                continue;
            }
            for (Object[] s : core.remove(f, w)) {
                if (s[0] instanceof ItemVariant v && s[1] instanceof Integer n) {
                    out.add(McItemVariant.toStack(v, n));
                }
            }
            out.add(ModuleItem.stackFor(m));
        }
        for (int bay = 0; bay < core.serviceBays(); bay++) {
            FfId up = core.upgrade(bay);
            if (up != null && FfContent.upgradeItem(up) != null) {
                out.add(new ItemStack(FfContent.upgradeItem(up)));
            }
        }
        // Tier kits are given back (the chassis item itself is always the base tier).
        for (FfId kit : core.tierKits()) {
            var t = dev.fieldframe.api.FieldframeRegistry.tiers().stream().filter(x -> x.kitId().equals(kit)).findFirst().orElse(null);
            if (t != null && FfContent.tierKit(t.id()) != null) {
                out.add(new ItemStack(FfContent.tierKit(t.id())));
            }
        }
        // Stores borrowed from a bolted machine's lead or a frame hub belong to that owner: never dropped here.
        for (int l = 0; l < ChassisSpec.ITEM_LANES && !core.storesBorrowed(); l++) {
            ItemLane lane = core.lane(l);
            for (int s = 0; s < lane.slots(); s++) {
                ItemVariant v = lane.variant(s);
                if (v != null) {
                    int n = lane.count(s);
                    lane.extractSlot(s, n, false);
                    out.add(McItemVariant.toStack(v, n));
                }
            }
        }
        out.removeIf(ItemStack::isEmpty);
        return out;
    }

    // ------------------------------------------------------------------ persistence & sync

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.put("fieldframe", NbtBridge.toTag(core.save()));
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        if (tag.contains("fieldframe")) {
            List<String> problems = core.load(NbtBridge.fromTag(tag.getCompound("fieldframe")));
            for (String p : problems) {
                Fieldframe.LOG.warn("Fieldframe chassis at {}: {}", worldPosition, p);
            }
        }
        if (tag.contains("client")) {
            readClient(tag.getCompound("client"));
        }
    }

    private CompoundTag writeClient() {
        CompoundTag c = new CompoundTag();
        c.putString("front", core.orientation().front().name());
        for (LocalFace f : LocalFace.ALL) {
            InstalledModule m = core.module(f);
            if (m != null) {
                c.putString(f.key(), m.moduleId().toString());
                c.putByte(f.key() + "_s", (byte) m.status().severity().ordinal());
                c.putByteArray(f.key() + "_m", dev.fieldframe.core.chassis.FaceMarks.of(m));
                int g = dev.fieldframe.core.chassis.FaceGauge.level(m);
                if (g >= 0) {
                    c.putByte(f.key() + "_g", (byte) g);
                }
            }
        }
        c.putInt("work", core.workingMask());
        if (core.isQuiet()) {
            c.putBoolean("quiet", true);
        }
        return c;
    }

    private void readClient(CompoundTag c) {
        for (LocalFace f : LocalFace.ALL) {
            clientModules[f.ordinal()] = c.contains(f.key()) ? FfId.tryParse(c.getString(f.key())) : null;
            clientSeverity[f.ordinal()] = c.getByte(f.key() + "_s");
            clientMarks[f.ordinal()] = c.getByteArray(f.key() + "_m");
            clientGauges[f.ordinal()] = c.contains(f.key() + "_g") ? c.getByte(f.key() + "_g") : -1;
        }
        clientWorking = c.getInt("work");
        clientQuiet = c.getBoolean("quiet");
        requestModelDataUpdate();
        if (level != null && level.isClientSide) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 8);
        }
    }

    @Override
    public CompoundTag getUpdateTag() {
        CompoundTag t = new CompoundTag();
        t.put("client", writeClient());
        return t;
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public ModelData getModelData() {
        WorldDir front = getBlockState().hasProperty(ChassisBlock.FACING) ? Dirs.toWorld(getBlockState().getValue(ChassisBlock.FACING)) : WorldDir.NORTH;
        return ModelData.builder().with(FACES, new FaceRender(clientModules.clone(), clientSeverity.clone(), front, clientMarks.clone(), clientWorking, clientGauges.clone())).build();
    }

    /** Bolted Chassis: a part unloading pauses the machine until it is back. */
    @Override
    public void onChunkUnloaded() {
        super.onChunkUnloaded();
        if (level != null && !level.isClientSide) {
            BoltLinks.onUnload(this);
        }
    }

    public FfId clientModule(LocalFace f) {
        return clientModules[f.ordinal()];
    }

    /** Client: bit per local face, set while that module works. */
    public int clientWorking() {
        return clientWorking;
    }

    /** Client: machine sounds switched off on the machine screen. */
    public boolean clientQuiet() {
        return clientQuiet;
    }

    public boolean isKnownModule(FfId id) {
        return FieldframeRegistry.module(id) != null;
    }
}
