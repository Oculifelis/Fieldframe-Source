package dev.fieldframe.frameworks;

import dev.fieldframe.api.data.FfData;
import dev.fieldframe.api.face.LocalFace;
import dev.fieldframe.api.frame.MachineHub;
import dev.fieldframe.api.resource.FluidVariant;
import dev.fieldframe.api.resource.ItemStore;
import dev.fieldframe.api.resource.ItemVariant;
import dev.fieldframe.api.world.WorkTarget;
import dev.fieldframe.api.world.WorldActions;
import dev.fieldframe.neoforge.api.FieldframeForgeApi;
import dev.fieldframe.neoforge.api.frame.ModuleSocketBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;

/**
 * Gantry Controller block entity. The controller is a chassis-like block with its own storage; together with a
 * closed rectangle of Gantry Rails on the same level (3 to 64 blocks a side) it drives a Gantry Head that travels one
 * block above the rails, over the area inside them. The module on the controller's top picks the program:
 * <ul>
 *   <li>Block Breaker: <b>Quarry</b>, layer by layer from the rail level down to {@link #quarryDepth()} below, drops into
 *       Green storage, with the first tool in Purple storage (its wear is kept);</li>
 *   <li>Crop Tender: <b>Farm</b>, harvests mature crops below the head into Green storage and replants;</li>
 *   <li>World Fluid Pump: <b>Pump</b>, drains fluid sources layer by layer into the Orange tank.</li>
 * </ul>
 * Every block action goes through Fieldframe's world actions as the controller's owner (protection mods apply) and
 * costs RF from the controller's storage. The head carries nothing: what it gathers arrives at once.
 */
public final class GantryControllerEntity extends ModuleSocketBlockEntity {
    /** Tuning {@code gantry.max_side}. */
    public static int maxSide() {
        return FwTuning.getInt("gantry.max_side");
    }

    /** Tuning {@code gantry.min_side}. */
    public static int minSide() {
        return FwTuning.getInt("gantry.min_side");
    }

    /** Tuning {@code gantry.quarry_depth}. */
    public static int quarryDepth() {
        return FwTuning.getInt("gantry.quarry_depth");
    }

    /** The controller's own storage: like an Advanced chassis. */
    public static int slots() {
        return FwTuning.getInt("gantry.slots");
    }

    /** Tuning {@code gantry.mb_per_tank}. */
    public static long mbPerTank() {
        return FwTuning.getLong("gantry.mb_per_tank");
    }

    /** Tuning {@code gantry.rf_capacity}. */
    public static long rfCapacity() {
        return FwTuning.getLong("gantry.rf_capacity");
    }

    /** Tuning {@code gantry.rf_per_step}. */
    public static long rfPerStep() {
        return FwTuning.getLong("gantry.rf_per_step");
    }

    /** Tuning {@code gantry.rf_per_break}. */
    public static long rfPerBreak() {
        return FwTuning.getLong("gantry.rf_per_break");
    }

    /** Tuning {@code gantry.rf_per_hardness}. */
    public static long rfPerHardness() {
        return FwTuning.getLong("gantry.rf_per_hardness");
    }

    /** Tuning {@code gantry.rf_per_pump}. */
    public static long rfPerPump() {
        return FwTuning.getLong("gantry.rf_per_pump");
    }

    /** Tuning {@code gantry.rf_per_harvest}. */
    public static long rfPerHarvest() {
        return FwTuning.getLong("gantry.rf_per_harvest");
    }

    /** Tuning {@code gantry.farm_depth}. */
    public static int farmDepth() {
        return FwTuning.getInt("gantry.farm_depth");
    }

    /** Empty columns looked at (without moving the head) per action. */
    public static int skipsPerAction() {
        return FwTuning.getInt("gantry.skips_per_action");
    }

    public enum Program { NONE, QUARRY, FARM, PUMP }

    private final MachineHub hub = FieldframeForgeApi.newMachineHub();
    /** Why the ring is not formed; only meaningful while the status is RING_PROBLEM. */
    private String problem = "not_checked";
    private int minX, maxX, minZ, maxZ, railY;
    private BlockPos head;
    private int layer = Integer.MIN_VALUE;
    private int index;
    private long nextCheck;
    private long nextAction;
    private Program program = Program.NONE;
    private GantryStatus status = GantryStatus.UNCHECKED;
    /** Tuning values the storage was sized with. */
    private int tuningRevision = FwTuning.revision();

    public GantryControllerEntity(BlockPos pos, BlockState state) {
        super(FwContent.GANTRY_BE.get(), pos, state);
        hub.resize(slots(), mbPerTank(), rfCapacity());
    }

    public MachineHub hub() {
        return hub;
    }

    public boolean formed() {
        return status.formed();
    }

    public BlockPos head() {
        return head;
    }

    public Program program() {
        return program;
    }

    /** Derived from the layer, so a saved finished run stays finished after a reload. */
    public boolean done() {
        return railY != Integer.MIN_VALUE && GantryStatus.layersDone(layer, Math.max(level == null ? Integer.MIN_VALUE : level.getMinBuildHeight() + 1, railY - quarryDepth()));
    }

    public GantryStatus status() {
        return status;
    }

    /** The single transition: every status change goes through here. */
    private void setStatus(GantryStatus wanted) {
        GantryStatus next = GantryStatus.next(status, wanted, wanted.formed() && done());
        if (next != status) {
            status = next;
            setChanged();
        }
    }

    @Override
    protected void afterServerTick() {
        if (!(level instanceof ServerLevel sl)) {
            return;
        }
        if (!isLinked()) {
            link(hub, getBlockState().getValue(HorizontalDirectionalBlock.FACING));
        }
        if (tuningRevision != FwTuning.revision()) {
            // A data reload changed the controller's storage sizes: what no longer fits drops on top.
            tuningRevision = FwTuning.revision();
            for (MachineHub.Spill s : hub.resize(slots(), mbPerTank(), rfCapacity())) {
                net.minecraft.world.Containers.dropItemStack(level, worldPosition.getX() + 0.5, worldPosition.getY() + 1.0,
                        worldPosition.getZ() + 0.5, FieldframeForgeApi.toStack(s.item(), s.count()));
            }
        }
        long now = level.getGameTime();
        if (now >= nextCheck) {
            nextCheck = now + 40;
            checkRing();
            program = programFromTop();
        }
        if (hub.consumeDirty()) {
            setChanged();
        }
        if (!formed() || program == Program.NONE || done() || now < nextAction) {
            if (program == Program.NONE && formed()) {
                setStatus(GantryStatus.NEEDS_TOOL_MODULE);
            }
            return;
        }
        nextAction = now + interval();
        step(sl);
    }

    private int interval() {
        FfData d = FieldframeForgeApi.inspect(level, worldPosition);
        int tier = d == null ? 0 : d.getInt("tier_level", 0);
        return Math.max(2, 10 - 2 * tier);
    }

    private Program programFromTop() {
        FfData d = FieldframeForgeApi.inspect(level, worldPosition);
        FfData top = d == null ? null : d.getData("faces").getDataOrNull("top");
        String id = top == null ? "" : top.getString("module", "");
        return switch (id) {
            case "fieldframe:block_breaker" -> Program.QUARRY;
            case "fieldframe:crop_tender" -> Program.FARM;
            case "fieldframe:world_fluid_pump" -> Program.PUMP;
            default -> Program.NONE;
        };
    }

    // ------------------------------------------------------------------ the ring

    private boolean railAt(BlockPos p) {
        if (!level.isLoaded(p)) {
            return false;
        }
        BlockState s = level.getBlockState(p);
        return s.getBlock() instanceof GantryRailBlock || p.equals(worldPosition);
    }

    private void checkRing() {
        Set<BlockPos> seen = new HashSet<>();
        Deque<BlockPos> q = new ArrayDeque<>();
        q.add(worldPosition);
        seen.add(worldPosition);
        int x0 = worldPosition.getX(), x1 = x0, z0 = worldPosition.getZ(), z1 = z0;
        while (!q.isEmpty()) {
            BlockPos p = q.poll();
            x0 = Math.min(x0, p.getX());
            x1 = Math.max(x1, p.getX());
            z0 = Math.min(z0, p.getZ());
            z1 = Math.max(z1, p.getZ());
            if (x1 - x0 >= maxSide() || z1 - z0 >= maxSide() || seen.size() > 4 * maxSide()) {
                fail("too_big");
                return;
            }
            for (Direction d : Direction.Plane.HORIZONTAL) {
                BlockPos n = p.relative(d);
                if (!seen.contains(n) && railAt(n)) {
                    seen.add(n);
                    q.add(n);
                }
            }
        }
        if (x1 - x0 + 1 < minSide() || z1 - z0 + 1 < minSide()) {
            fail("too_small");
            return;
        }
        int y = worldPosition.getY();
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                boolean edge = x == x0 || x == x1 || z == z0 || z == z1;
                if (edge && !railAt(new BlockPos(x, y, z))) {
                    fail("ring_open");
                    return;
                }
            }
        }
        boolean sameRing = x0 == minX && x1 == maxX && z0 == minZ && z1 == maxZ && y == railY;
        minX = x0;
        maxX = x1;
        minZ = z0;
        maxZ = z1;
        railY = y;
        if (!sameRing || layer == Integer.MIN_VALUE) {
            layer = railY;
            index = 0;
        }
        problem = null;
        if (!status.formed()) {
            setStatus(GantryStatus.IDLE);
        }
        if (head == null || !level.getBlockState(head).is(FwContent.GANTRY_HEAD.get())) {
            BlockPos start = new BlockPos(minX + 1, railY + 1, minZ + 1);
            if (level.getBlockState(start).isAir()) {
                level.setBlock(start, FwContent.GANTRY_HEAD.get().defaultBlockState(), 3);
                head = start;
            }
        }
    }

    private void fail(String why) {
        if (formed() && head != null && level.getBlockState(head).is(FwContent.GANTRY_HEAD.get())) {
            level.removeBlock(head, false);
        }
        head = null;
        problem = why;
        setStatus(GantryStatus.RING_PROBLEM);
    }

    // ------------------------------------------------------------------ the program

    private int columns() {
        return (maxX - minX - 1) * (maxZ - minZ - 1);
    }

    /** Serpentine order over the area inside the ring. */
    private int[] column(int i) {
        int w = maxX - minX - 1;
        int row = i / w;
        int col = i % w;
        if (row % 2 == 1) {
            col = w - 1 - col;
        }
        return new int[]{minX + 1 + col, minZ + 1 + row};
    }

    private void step(ServerLevel sl) {
        if (head == null) {
            setStatus(GantryStatus.HEAD_MISSING);
            return;
        }
        String[] owner = FieldframeForgeApi.owner(level, worldPosition);
        String ownerId = owner == null || owner[0] == null ? "" : owner[0];
        String ownerName = owner == null || owner[1] == null ? "" : owner[1];
        // Columns with nothing to do are passed over without moving the head, a few rows at a time.
        for (int tries = 0; tries < skipsPerAction() && !done(); tries++) {
            int[] c = column(index);
            BlockPos want = new BlockPos(c[0], railY + 1, c[1]);
            WorldActions a = FieldframeForgeApi.worldActions(sl, want, Direction.NORTH, ownerId, ownerName);
            WorkTarget t = target(a);
            if (t == null) {
                boolean wrapped = advance(program != Program.FARM);
                if (wrapped && program == Program.FARM) {
                    setStatus(GantryStatus.FARMING);
                    return;
                }
                continue;
            }
            if (!head.equals(want)) {
                moveHead(want);
                return;
            }
            switch (program) {
                case QUARRY -> quarry(a, t);
                case FARM -> farm(a, t);
                case PUMP -> pump(a, t);
                default -> {
                }
            }
            return;
        }
    }

    /** The block the program works on in the current column, or null when there is nothing to do there. */
    private WorkTarget target(WorldActions a) {
        if (program == Program.FARM) {
            for (int depth = 1; depth <= farmDepth(); depth++) {
                WorkTarget t = new WorkTarget(LocalFace.BOTTOM, depth, 0, 0);
                WorldActions.BlockInfo info = a.probe(t);
                if (!info.access().ok()) {
                    return null;
                }
                if (!info.air()) {
                    return info.crop() && info.mature() ? t : null;
                }
            }
            return null;
        }
        int depth = railY + 1 - layer;
        if (depth < 1 || depth > WorkTarget.MAX_DEPTH) {
            return null;
        }
        WorkTarget t = new WorkTarget(LocalFace.BOTTOM, depth, 0, 0);
        WorldActions.BlockInfo info = a.probe(t);
        if (!info.access().ok()) {
            return null;
        }
        if (program == Program.QUARRY) {
            return info.air() || info.fluid() != null || info.unbreakable() ? null : t;
        }
        return info.fluid() != null && info.fluidSource() ? t : null;
    }

    private void moveHead(BlockPos want) {
        BlockPos next = head.getX() != want.getX() ? head.relative(want.getX() > head.getX() ? Direction.EAST : Direction.WEST)
                : head.relative(want.getZ() > head.getZ() ? Direction.SOUTH : Direction.NORTH);
        if (!level.getBlockState(next).isAir()) {
            setStatus(GantryStatus.HEAD_BLOCKED);
            return;
        }
        if (hub.energy().extract(rfPerStep(), false) < rfPerStep()) {
            setStatus(GantryStatus.NO_POWER);
            return;
        }
        level.removeBlock(head, false);
        level.setBlock(next, FwContent.GANTRY_HEAD.get().defaultBlockState(), 3);
        head = next;
        setStatus(GantryStatus.MOVING);
        setChanged();
    }

    /** Next column; returns true when a pass over the area just ended (and, by layer, the next layer begins). */
    private boolean advance(boolean byLayer) {
        index++;
        boolean wrapped = index >= columns();
        if (wrapped) {
            index = 0;
            if (byLayer) {
                layer--;
                if (done()) {
                    setStatus(GantryStatus.DONE);
                }
            }
        }
        setChanged();
        return wrapped;
    }

    private void quarry(WorldActions a, WorkTarget t) {
        WorldActions.BlockInfo info = a.probe(t);
        ItemStore green = hub.items(1);
        if (!hasEmptySlot(green)) {
            setStatus(GantryStatus.OUTPUT_FULL);
            return;
        }
        long rf = rfPerBreak() + Math.round(rfPerHardness() * Math.max(0, info.hardness()));
        if (hub.energy().extract(rf, true) < rf) {
            setStatus(GantryStatus.NO_POWER);
            return;
        }
        ItemStore purple = hub.items(2);
        int toolSlot = -1;
        ItemVariant tool = null;
        for (int s = 0; s < purple.slots(); s++) {
            if (purple.variant(s) != null) {
                toolSlot = s;
                tool = purple.variant(s);
                break;
            }
        }
        WorldActions.BreakResult r = a.breakBlock(t, tool, false);
        if (r.outcome().ok()) {
            hub.energy().extract(rf, false);
            for (WorldActions.Drop d : r.drops()) {
                int left = d.count() - green.insert(d.variant(), d.count(), false);
                if (left > 0) {
                    a.dropItem(LocalFace.BOTTOM, d.variant(), left);
                }
            }
            if (toolSlot >= 0 && (r.toolAfter() == null || !r.toolAfter().equals(tool))) {
                purple.extractSlot(toolSlot, 1, false);
                if (r.toolAfter() != null) {
                    purple.insert(r.toolAfter(), 1, false);
                }
            }
            setStatus(GantryStatus.QUARRYING);
        }
        advance(true);
    }

    private void farm(WorldActions a, WorkTarget t) {
        if (hub.energy().extract(rfPerHarvest(), true) < rfPerHarvest()) {
            setStatus(GantryStatus.NO_POWER);
            return;
        }
        WorldActions.BlockInfo info = a.probe(t);
        WorldActions.BreakResult r = a.breakBlock(t, null, false);
        if (r.outcome().ok()) {
            hub.energy().extract(rfPerHarvest(), false);
            ItemVariant seed = info.plantItem();
            boolean replant = false;
            for (WorldActions.Drop d : r.drops()) {
                int count = d.count();
                if (!replant && seed != null && d.variant().equals(seed)) {
                    replant = a.place(t, seed).ok();
                    count -= replant ? 1 : 0;
                }
                if (count > 0) {
                    int left = count - hub.items(1).insert(d.variant(), count, false);
                    if (left > 0) {
                        a.dropItem(LocalFace.BOTTOM, d.variant(), left);
                    }
                }
            }
            setStatus(GantryStatus.FARMING);
        }
        advance(false);
    }

    private void pump(WorldActions a, WorkTarget t) {
        FluidVariant f = a.drainSource(t, true);
        if (f == null) {
            advance(true);
            return;
        }
        if (hub.tank(0).fill(f, 1000, true) < 1000) {
            setStatus(GantryStatus.OUTPUT_FULL);
            return;
        }
        if (hub.energy().extract(rfPerPump(), false) < rfPerPump()) {
            setStatus(GantryStatus.NO_POWER);
            return;
        }
        FluidVariant got = a.drainSource(t, false);
        if (got != null) {
            hub.tank(0).fill(got, 1000, false);
            setStatus(GantryStatus.PUMPING);
        }
        advance(true);
    }

    /** Chat lines: the ring, the program and what the head is doing. */
    public void describe(Player p) {
        if (!formed()) {
            p.sendSystemMessage(Component.translatable("fieldframe_frameworks.gantry.not_formed",
                    Component.translatable("fieldframe_frameworks.gantry.problem." + (problem == null ? "not_checked" : problem))));
            return;
        }
        p.sendSystemMessage(Component.translatable("fieldframe_frameworks.gantry.formed", maxX - minX + 1, maxZ - minZ + 1,
                Component.translatable("fieldframe_frameworks.gantry.program." + program.name().toLowerCase(java.util.Locale.ROOT))));
        p.sendSystemMessage(Component.translatable("fieldframe_frameworks.gantry.status." + status.key(), layer));
    }

    // ------------------------------------------------------------------ persistence and removal

    /** The block was broken or replaced (called from the block's onRemove). */
    public void broken(BlockPos pos, BlockState state) {
        if (level != null && !level.isClientSide) {
            if (head != null && level.getBlockState(head).is(FwContent.GANTRY_HEAD.get())) {
                level.removeBlock(head, false);
            }
            for (MachineHub.Spill s : hub.drainAll()) {
                net.minecraft.world.Containers.dropItemStack(level, pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5,
                        FieldframeForgeApi.toStack(s.item(), s.count()));
            }
        }
    }

    @Override
    protected void saveAdditional(CompoundTag nbt, net.minecraft.core.HolderLookup.Provider registries) {
        super.saveAdditional(nbt, registries);
        if (level != null) {
            nbt.put("hub", FieldframeForgeApi.hubToTag(hub, level.registryAccess()));
        }
        FfData d = new FfData().putInt("layer", layer).putInt("index", index).putString("status", status.key())
                .putInt("minX", minX).putInt("maxX", maxX).putInt("minZ", minZ).putInt("maxZ", maxZ).putInt("railY", railY);
        if (head != null) {
            d.putInt("hx", head.getX()).putInt("hy", head.getY()).putInt("hz", head.getZ());
        }
        nbt.put("gantry", FwData.toTag(d));
    }

    @Override
    protected void loadAdditional(CompoundTag nbt, net.minecraft.core.HolderLookup.Provider registries) {
        super.loadAdditional(nbt, registries);
        java.util.Optional.ofNullable(nbt.contains("hub") ? nbt.getCompound("hub") : null).ifPresent(tag -> FieldframeForgeApi.hubFromTag(hub, tag, registries));
        java.util.Optional.ofNullable(nbt.contains("gantry") ? nbt.getCompound("gantry") : null).ifPresent(tag -> {
            FfData d = FwData.fromTag(tag);
            layer = d.getInt("layer", Integer.MIN_VALUE);
            index = Math.max(0, d.getInt("index", 0));
            status = GantryStatus.fromKey(d.getString("status", ""));
            minX = d.getInt("minX", 0);
            maxX = d.getInt("maxX", 0);
            minZ = d.getInt("minZ", 0);
            maxZ = d.getInt("maxZ", 0);
            railY = d.getInt("railY", Integer.MIN_VALUE);
            head = d.has("hx") ? new BlockPos(d.getInt("hx", 0), d.getInt("hy", 0), d.getInt("hz", 0)) : null;
        });
        // Re-checked on the first tick; keep the saved progress (and a finished run) if the ring is the same.
        // Old saves had only "done"; that is re-derived from the saved layer.
        nextCheck = 0;
    }

    private static boolean hasEmptySlot(ItemStore s) {
        for (int i = 0; i < s.slots(); i++) {
            if (s.variant(i) == null) {
                return true;
            }
        }
        return false;
    }
}
