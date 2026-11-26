package dev.fieldframe.core.chassis;

import dev.fieldframe.api.FieldframeRegistry;
import dev.fieldframe.api.chassis.ChassisSpec;
import dev.fieldframe.api.chassis.UpgradeDefinition;
import dev.fieldframe.api.data.FfData;
import dev.fieldframe.api.event.FieldframeEvent;
import dev.fieldframe.api.event.FieldframeEvents;
import dev.fieldframe.api.face.LocalFace;
import dev.fieldframe.api.face.Orientation;
import dev.fieldframe.api.face.WorldDir;
import dev.fieldframe.api.id.FfId;
import dev.fieldframe.api.module.BindingSpec;
import dev.fieldframe.api.module.ChassisWorld;
import dev.fieldframe.api.module.ConfigView;
import dev.fieldframe.api.module.Exposure;
import dev.fieldframe.api.module.ModuleContext;
import dev.fieldframe.api.module.ModuleDefinition;
import dev.fieldframe.api.module.TickPhase;
import dev.fieldframe.api.resource.EnergyStore;
import dev.fieldframe.api.resource.FluidStore;
import dev.fieldframe.api.resource.FluidVariant;
import dev.fieldframe.api.resource.ItemStore;
import dev.fieldframe.api.resource.ItemVariant;
import dev.fieldframe.api.resource.VariantCodec;
import dev.fieldframe.api.status.Status;
import dev.fieldframe.api.status.Statuses;
import dev.fieldframe.core.control.ControlBus;
import dev.fieldframe.core.storage.EnergyBuffer;
import dev.fieldframe.core.storage.FluidReservoir;
import dev.fieldframe.core.storage.ItemLane;
import dev.fieldframe.core.storage.RateBudget;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.LongSupplier;

/**
 * The single authoritative state of one chassis: six faces, three item lanes, three fluid reservoirs, one RF
 * buffer, eight control channels, service bays and shared per-tick budgets. Loader-independent; the platform
 * block entity owns exactly one instance and forwards ticks, capability queries and persistence to it.
 */
public final class ChassisCore {
    public static final int SCHEMA_VERSION = 1;
    public static final int TRACE_LIMIT = 32;
    public static final int SPILL_LIMIT = 64;

    private final ChassisSpec spec;
    private final VariantCodec codec;
    private LongSupplier clock;
    private Function<FfId, ModuleDefinition> definitions = FieldframeRegistry::module;
    private String address = "detached";
    /** Owner identity (platform UUID string + last known name); empty when unknown. Never copied by blueprints. */
    private String ownerId = "";
    private String ownerName = "";

    private Orientation orientation = Orientation.DEFAULT;
    // Not final: a Bolted Chassis part uses the lead's stores (see BoltGroup).
    private ItemLane[] lanes = new ItemLane[ChassisSpec.ITEM_LANES];
    private FluidReservoir[] tanks = new FluidReservoir[ChassisSpec.FLUID_LANES];
    private EnergyBuffer energy;
    private ControlBus control = new ControlBus();
    private BoltGroup group;
    /** The stores belong to someone else (a bolted machine's lead or a frame hub): saved empty here. */
    private boolean borrowedStores;
    /** Saved as part of a Bolted Chassis and not linked yet: paused, exposes nothing. */
    private boolean awaitingLink;
    /** Platform data about the other parts of a Bolted Chassis (their offsets); saved, opaque to the core. */
    private FfData boltInfo = new FfData();
    private final InstalledModule[] faces = new InstalledModule[6];
    private final FfId[] upgrades;
    private dev.fieldframe.api.chassis.ChassisTier tier = BASE_TIER;

    /** The tier of a chassis when no tiers are registered (tests, or a pack that removed them). */
    public static final dev.fieldframe.api.chassis.ChassisTier BASE_TIER =
            new dev.fieldframe.api.chassis.ChassisTier(FfId.ff("basic"), 0, 1.0, 0, 0, 0, 0, 0);
    private final RateBudget itemBudget;
    private final RateBudget fluidBudget;
    private final RateBudget energyInBudget;
    private final RateBudget energyOutBudget;

    private int cursor;
    private long seed = 0x9E3779B97F4A7C15L;
    private long nextOperationId = 1;
    private int installGeneration;
    private long revision;
    private boolean dirty;
    private boolean redstoneChanged;
    private boolean paused;
    private boolean quiet;
    private String comparatorSource = DEFAULT_COMPARATOR;
    private boolean simulation;
    private final Deque<String> trace = new ArrayDeque<>();
    private final List<Object[]> pendingSpill = new ArrayList<>();
    private ChassisWorld currentWorld;
    /** Overclock from the installed upgrades (0..MAX_OVERCLOCK) and the heat it builds up (0..MAX_HEAT). */
    private double overclock;
    private int heat;
    private boolean overheated;
    private double cooling = BASE_COOLING;
    private long nextCoolingCheck;
    public static final double MAX_OVERCLOCK = 2.0;
    public static final int MAX_HEAT = 1_000;
    public static final int RECOVERED_HEAT = 300;
    static final double BASE_COOLING = 2.0;
    static final double HEAT_PER_OVERCLOCK = 8.0;

    public ChassisCore(ChassisSpec spec, VariantCodec codec, LongSupplier clock) {
        this.spec = spec;
        this.codec = codec;
        this.clock = clock;
        for (int i = 0; i < lanes.length; i++) {
            lanes[i] = new ItemLane(String.valueOf((char) ('A' + i)), spec.slotsPerLane());
            lanes[i].setOnChange(this::onContentsChanged);
        }
        for (int i = 0; i < tanks.length; i++) {
            tanks[i] = new FluidReservoir(String.valueOf((char) ('A' + i)), spec.fluidCapacity());
            tanks[i].setOnChange(this::onContentsChanged);
        }
        energy = new EnergyBuffer(spec.energyCapacity());
        energy.setOnChange(() -> dirty = true);
        // Room for the most bays any tier can open; serviceBays() is the usable count.
        upgrades = new FfId[6];
        if (FieldframeRegistry.tierAt(0) != null) {
            tier = FieldframeRegistry.tierAt(0);
        }
        itemBudget = new RateBudget(spec.itemsPerTick());
        fluidBudget = new RateBudget(spec.fluidPerTick());
        energyInBudget = new RateBudget(spec.energyInPerTick());
        energyOutBudget = new RateBudget(spec.energyOutPerTick());
    }

    // ------------------------------------------------------------------ accessors

    public ChassisSpec spec() {
        return spec;
    }

    public void setClock(LongSupplier clock) {
        this.clock = clock;
    }

    public void setDefinitionLookup(Function<FfId, ModuleDefinition> lookup) {
        this.definitions = lookup;
    }

    public void setOwner(String id, String name) {
        ownerId = id == null ? "" : id;
        ownerName = name == null ? "" : name.length() > 32 ? name.substring(0, 32) : name;
        dirty = true;
    }

    public String ownerId() {
        return ownerId;
    }

    public String ownerName() {
        return ownerName;
    }

    /**
     * Combined view of the reservoirs a player may fill or empty through the access panel on {@code face}
     * (bucket interaction). Null if that face has no panel granting tanks. Not budgeted: it is a player action.
     */
    public FluidStore panelTanks(LocalFace face) {
        if (awaitingLink) {
            return null;
        }
        InstalledModule m = faces[face.ordinal()];
        if (m == null || m.isMissing() || !m.definition.exposes(Exposure.PLAYER_ACCESS)) {
            return null;
        }
        int[] sel = m.behavior.playerAccessibleTanks(new Ctx(face, m, currentWorld));
        List<FluidStore> stores = new ArrayList<>();
        for (int t : sel) {
            if (t >= 0 && t < tanks.length) {
                stores.add(tanks[t]);
            }
        }
        return stores.isEmpty() ? null : new dev.fieldframe.core.util.CombinedFluidStore(stores);
    }

    public void setAddress(String address) {
        this.address = address;
    }

    public String address() {
        return address;
    }

    public Orientation orientation() {
        return orientation;
    }

    public void setOrientation(Orientation o) {
        this.orientation = o;
        markChanged();
    }

    public ItemLane lane(int i) {
        return lanes[i];
    }

    public FluidReservoir tank(int i) {
        return tanks[i];
    }

    public EnergyBuffer energy() {
        return energy;
    }

    public ControlBus control() {
        return control;
    }

    // ------------------------------------------------------------------ bolted chassis (see BoltGroup)

    /** What this chassis brings on its own: slots per lane, mB per tank, RF buffer (spec, tier, service bays). */
    public record Caps(int slots, long fluid, long energy) {
    }

    public BoltGroup group() {
        return group;
    }

    void setGroup(BoltGroup g) {
        group = g;
    }

    public boolean isAwaitingLink() {
        return awaitingLink;
    }

    public void setAwaitingLink(boolean v) {
        awaitingLink = v;
    }

    /** Platform data about the other parts (offsets); a copy. */
    public FfData boltInfo() {
        return boltInfo.copy();
    }

    public void setBoltInfo(FfData d) {
        boltInfo = d == null ? new FfData() : d.copy();
        dirty = true;
    }

    /** Whether this chassis was saved as part of a Bolted Chassis (lead or not). */
    public boolean boltedOnDisk() {
        return boltInfo.has("lead") || !boltInfo.getList("parts").isEmpty();
    }

    public Caps ownCapacities() {
        int slots = spec.slotsPerLane() + tier.extraSlotsPerLane();
        long fluidCap = spec.fluidCapacity() + tier.extraFluidCapacity();
        long energyCap = spec.energyCapacity() + tier.extraEnergyCapacity();
        for (FfId id : upgrades) {
            UpgradeDefinition u = id == null ? null : FieldframeRegistry.upgrade(id);
            if (u != null) {
                slots += u.extraSlotsPerLane();
                fluidCap += u.extraFluidCapacity();
                energyCap += u.extraEnergyCapacity();
            }
        }
        return new Caps(Math.min(slots, ItemLane.MAX_SLOTS), fluidCap, energyCap);
    }

    ItemLane[] lanesArray() {
        return lanes;
    }

    FluidReservoir[] tanksArray() {
        return tanks;
    }

    List<Object[]> pendingSpill() {
        return pendingSpill;
    }

    /** True while this chassis works from another chassis' (or a hub's) stores; it saves empty stores then. */
    public boolean storesBorrowed() {
        return borrowedStores;
    }

    void shareStoresOf(ChassisCore lead) {
        borrowedStores = true;
        lanes = lead.lanes;
        tanks = lead.tanks;
        energy = lead.energy;
        control = lead.control;
    }

    /** Fresh, empty stores of this chassis' own size (leaving a machine, or waiting for its lead). */
    void ownStores() {
        Caps caps = ownCapacities();
        lanes = new ItemLane[ChassisSpec.ITEM_LANES];
        tanks = new FluidReservoir[ChassisSpec.FLUID_LANES];
        for (int i = 0; i < lanes.length; i++) {
            lanes[i] = new ItemLane(String.valueOf((char) ('A' + i)), caps.slots());
        }
        for (int i = 0; i < tanks.length; i++) {
            tanks[i] = new FluidReservoir(String.valueOf((char) ('A' + i)), caps.fluid());
        }
        energy = new EnergyBuffer(caps.energy());
        control = new ControlBus();
        rewireOwnStores();
    }

    /** Only the storages, tanks and RF buffer (a frame hub's saved contents). */
    FfData saveStores() {
        FfData d = new FfData();
        List<Object> ls = new ArrayList<>();
        for (ItemLane l : lanes) {
            ls.add(l.save(codec));
        }
        d.putList("lanes", ls);
        List<Object> ts = new ArrayList<>();
        for (FluidReservoir t : tanks) {
            ts.add(t.save(codec));
        }
        d.putList("tanks", ts);
        d.put("energy", energy.save());
        return d;
    }

    List<String> loadStores(FfData d) {
        List<String> problems = new ArrayList<>();
        List<Object> ls = d.getList("lanes");
        for (int i = 0; i < lanes.length && i < ls.size(); i++) {
            if (ls.get(i) instanceof FfData ld) {
                int bad = lanes[i].load(ld, codec);
                if (bad > 0) {
                    problems.add(BindingSpec.laneName(i) + ": " + bad + " unreadable stacks skipped");
                }
            }
        }
        List<Object> ts = d.getList("tanks");
        for (int i = 0; i < tanks.length && i < ts.size(); i++) {
            if (ts.get(i) instanceof FfData td) {
                tanks[i].load(td, codec);
            }
        }
        energy.load(d.getData("energy"));
        return problems;
    }

    void rewireOwnStores() {
        borrowedStores = false;
        for (ItemLane l : lanes) {
            l.setOnChange(this::onContentsChanged);
        }
        for (FluidReservoir t : tanks) {
            t.setOnChange(this::onContentsChanged);
        }
        energy.setOnChange(() -> dirty = true);
    }

    /** Empties the own stores after a move into a machine; returns any items left over (normally none). */
    List<Object[]> drainOwnStores() {
        List<Object[]> out = new ArrayList<>();
        for (ItemLane l : lanes) {
            for (int s = 0; s < l.slots(); s++) {
                ItemVariant v = l.variant(s);
                int n = l.count(s);
                if (v != null && n > 0) {
                    l.extractSlot(s, n, false);
                    out.add(new Object[]{v, n});
                }
            }
        }
        for (FluidReservoir t : tanks) {
            if (t.fluid() != null && t.amount() > 0) {
                t.drain(t.fluid(), t.amount(), false);
            }
        }
        energy.extract(energy.stored(), false);
        return out;
    }

    /**
     * A lead whose parts are all gone: back to its own size. Items that no longer fit are returned (to drop), fluid
     * and RF beyond its own capacity are lost.
     */
    public List<Object[]> shrinkToOwn() {
        List<Object[]> spill = forceFit(lanes, tanks, energy, ownCapacities());
        awaitingLink = false;
        markChanged();
        return spill;
    }

    void reapplyCapacities() {
        Result r = applyCapacities();
        if (!r.ok()) {
            trace("capacity: " + r.detail());
        }
    }

    void onSharedContentsChanged() {
        onContentsChanged();
    }

    void markDirtyOnly() {
        dirty = true;
    }

    void changed() {
        markChanged();
    }

    /** Refusal (naming what to empty) if the contents would not fit {@code caps}; nothing changes. */
    static Result checkFits(ItemLane[] lanes, FluidReservoir[] tanks, EnergyBuffer energy, Caps caps) {
        for (ItemLane l : lanes) {
            if (l.slots() > caps.slots()) {
                ItemLane probe = new ItemLane("probe", l.slots());
                for (int s = 0; s < l.slots(); s++) {
                    if (l.variant(s) != null) {
                        probe.insert(l.variant(s), l.count(s), false);
                    }
                }
                if (probe.occupiedBeyond(caps.slots()) > 0) {
                    return Result.fail("WOULD_TRUNCATE_ITEMS", BindingSpec.laneName(l.name().charAt(0) - 'A') + ": empty it to " + caps.slots() + " stacks first");
                }
            }
        }
        for (FluidReservoir t : tanks) {
            if (t.amount() > caps.fluid()) {
                return Result.fail("WOULD_TRUNCATE_FLUID", BindingSpec.tankName(t.name().charAt(0) - 'A') + ": drain to " + caps.fluid() + " mB first");
            }
        }
        if (energy.stored() > caps.energy()) {
            return Result.fail("WOULD_TRUNCATE_ENERGY", "Drain the RF buffer to " + caps.energy() + " RF first");
        }
        return Result.success();
    }

    /** Resizes the stores to {@code caps}; refuses (changing nothing) when that would truncate. */
    static Result resizeStores(ItemLane[] lanes, FluidReservoir[] tanks, EnergyBuffer energy, Caps caps) {
        Result r = checkFits(lanes, tanks, energy, caps);
        if (!r.ok()) {
            return r;
        }
        for (ItemLane l : lanes) {
            l.resize(caps.slots());
        }
        for (FluidReservoir t : tanks) {
            t.setCapacity(caps.fluid());
        }
        energy.setCapacity(caps.energy());
        return Result.success();
    }

    /**
     * Shrinks the stores to {@code caps} no matter what: items that no longer fit are returned (to drop), fluid and RF
     * beyond the new capacity are lost (as when a chassis breaks).
     */
    static List<Object[]> forceFit(ItemLane[] lanes, FluidReservoir[] tanks, EnergyBuffer energy, Caps caps) {
        List<Object[]> spill = new ArrayList<>();
        for (ItemLane l : lanes) {
            if (l.slots() <= caps.slots() || l.occupiedBeyond(caps.slots()) == 0 && l.resize(caps.slots())) {
                continue;
            }
            List<Object[]> all = new ArrayList<>();
            for (int s = 0; s < l.slots(); s++) {
                ItemVariant v = l.variant(s);
                int n = l.count(s);
                if (v != null && n > 0) {
                    l.extractSlot(s, n, false);
                    all.add(new Object[]{v, n});
                }
            }
            l.resize(caps.slots());
            for (Object[] st : all) {
                int put = l.insert((ItemVariant) st[0], (Integer) st[1], false);
                if (put < (Integer) st[1]) {
                    spill.add(new Object[]{st[0], (Integer) st[1] - put});
                }
            }
        }
        for (FluidReservoir t : tanks) {
            if (t.amount() > caps.fluid() && t.fluid() != null) {
                t.drain(t.fluid(), t.amount() - caps.fluid(), false);
            }
            t.setCapacity(caps.fluid());
        }
        if (energy.stored() > caps.energy()) {
            energy.extract(energy.stored() - caps.energy(), false);
        }
        energy.setCapacity(caps.energy());
        return spill;
    }

    public InstalledModule module(LocalFace f) {
        return faces[f.ordinal()];
    }

    /** A player used {@code f} with an empty hand; true when its module took the use (see ModuleBehavior#onPlayerUse). */
    public boolean playerUse(LocalFace f, boolean sneaking) {
        InstalledModule m = faces[f.ordinal()];
        if (m == null || m.isMissing() || FieldframeRegistry.isDisabled(m.moduleId) || !m.behavior().onPlayerUse(sneaking)) {
            return false;
        }
        m.wake = true;
        return true;
    }

    public FfId upgrade(int bay) {
        return upgrades[bay];
    }

    /** Usable service bays: the spec's plus the tier's (at most 6). */
    public int serviceBays() {
        return Math.min(upgrades.length, spec.serviceBays() + tier.extraServiceBays());
    }

    public dev.fieldframe.api.chassis.ChassisTier tier() {
        return tier;
    }

    /**
     * Raises the chassis one tier (an upgrade kit). Refused unless {@code next} is exactly one level above the
     * current tier; capacities only grow, so nothing stored is ever truncated.
     */
    public Result upgradeTier(dev.fieldframe.api.chassis.ChassisTier next) {
        if (waitingForParts()) {
            return Result.fail("PARTS_MISSING", "");
        }
        if (next == null || next.level() != tier.level() + 1) {
            dev.fieldframe.api.chassis.ChassisTier needed = next == null ? null : FieldframeRegistry.tierAt(next.level() - 1);
            return Result.fail("WRONG_TIER", needed == null ? "?" : needed.id().toString());
        }
        dev.fieldframe.api.chassis.ChassisTier old = tier;
        tier = next;
        Result r = applyCapacities();
        if (!r.ok()) {
            tier = old;
            applyCapacities();
            return r;
        }
        markChanged();
        return Result.success();
    }

    /** The kits used to reach the current tier, lowest first (given back when the chassis is broken). */
    public List<FfId> tierKits() {
        List<FfId> out = new ArrayList<>();
        for (int level = 1; level <= tier.level(); level++) {
            dev.fieldframe.api.chassis.ChassisTier t = FieldframeRegistry.tierAt(level);
            if (t != null) {
                out.add(t.kitId());
            }
        }
        return out;
    }

    public long revision() {
        return revision;
    }

    public boolean consumeDirty() {
        boolean d = dirty;
        dirty = false;
        return d;
    }

    public boolean consumeRedstoneChanged() {
        boolean r = redstoneChanged;
        redstoneChanged = false;
        return r;
    }

    /**
     * Marks this core as a side-effect-free simulation (manual test bench, previews): no events are posted to
     * Java or script listeners, so a simulation can never trigger live-world behaviour.
     */
    public void setSimulation(boolean simulation) {
        this.simulation = simulation;
    }

    public boolean isSimulation() {
        return simulation;
    }

    private boolean postEvent(FieldframeEvent e) {
        return simulation || FieldframeEvents.post(e);
    }

    public boolean isPaused() {
        return paused;
    }

    /**
     * Whether the machine waits for a missing part or link: saved as bolted and not linked yet, or bolted with a part
     * absent. The one check every refusal and the tick use. (A part can wait while still on borrowed stores: the
     * remaining parts of a machine whose other part unloaded keep the lead's stores and wait.)
     */
    public boolean waitingForParts() {
        return awaitingLink || group != null && !group.complete();
    }

    /** What the chassis does on a tick, derived from the pause toggle and the link state. */
    public enum RunState { RUNNING, PAUSED, WAITING_FOR_PARTS }

    public RunState runState() {
        if (waitingForParts()) {
            return RunState.WAITING_FOR_PARTS;
        }
        return paused ? RunState.PAUSED : RunState.RUNNING;
    }

    // ------------------------------------------------------------------ comparator, sounds, working looks

    /** What a comparator next to the machine reads: off, a storage, a tank or the RF buffer. */
    public static final List<String> COMPARATOR_SOURCES = List.of("off", "storage_0", "storage_1", "storage_2", "tank_0", "tank_1", "tank_2", "energy");
    /** Default: how full Green storage (results) is, the usual "is the output backing up" question. */
    public static final String DEFAULT_COMPARATOR = "storage_1";
    /** Ticks a module still counts as working (lit faceplate, sound) after it last worked; smooths short idle gaps. */
    public static final int WORK_LINGER = 40;

    public String comparatorSource() {
        return comparatorSource;
    }

    public Result setComparatorSource(String source) {
        if (source == null || !COMPARATOR_SOURCES.contains(source)) {
            return Result.fail("BAD_VALUE", String.valueOf(source));
        }
        comparatorSource = source;
        markChanged();
        return Result.success();
    }

    /**
     * Comparator strength 0..15 for {@link #comparatorSource()}, with the vanilla container rule: 0 when empty,
     * otherwise 1 + 14 x fill (items count as a fraction of each slot's stack size).
     */
    public int comparatorLevel() {
        String s = comparatorSource;
        double fill;
        if (s.startsWith("storage_")) {
            ItemLane l = lanes[s.charAt(8) - '0'];
            double sum = 0;
            for (int i = 0; i < l.slots(); i++) {
                ItemVariant v = l.variant(i);
                if (v != null && l.count(i) > 0) {
                    sum += l.count(i) / (double) Math.max(1, l.slotLimit(v));
                }
            }
            fill = l.slots() == 0 ? 0 : sum / l.slots();
        } else if (s.startsWith("tank_")) {
            FluidReservoir t = tanks[s.charAt(5) - '0'];
            fill = t.capacity() <= 0 ? 0 : t.amount() / (double) t.capacity();
        } else if (s.equals("energy")) {
            fill = energy.capacity() <= 0 ? 0 : energy.stored() / (double) energy.capacity();
        } else {
            return 0;
        }
        if (fill <= 0) {
            return 0;
        }
        return Math.min(15, 1 + (int) Math.floor(Math.min(1, fill) * 14));
    }

    /** Machine sounds switched off from the machine screen (install/remove sounds still play). */
    public boolean isQuiet() {
        return quiet;
    }

    public void setQuiet(boolean q) {
        quiet = q;
        markChanged();
    }

    /** Bit per local face (ordinal): the module there worked within the last {@link #WORK_LINGER} ticks. */
    public int workingMask() {
        long now = clock.getAsLong();
        int mask = 0;
        for (int i = 0; i < 6; i++) {
            InstalledModule m = faces[i];
            if (m != null && !m.isMissing() && now - m.lastWorking <= WORK_LINGER) {
                mask |= 1 << i;
            }
        }
        return mask;
    }

    /** Level shown in the world on each local face (ordinal), or {@link FaceGauge#NONE}; see {@link FaceGauge}. */
    public int[] gaugeLevels() {
        int[] out = new int[6];
        for (int i = 0; i < 6; i++) {
            out[i] = FaceGauge.level(faces[i]);
        }
        return out;
    }

    public void setPaused(boolean p) {
        paused = p;
        markChanged();
    }

    public List<String> traceLines() {
        return List.copyOf(trace);
    }

    public RateBudget itemBudget() {
        return itemBudget;
    }

    public RateBudget fluidBudget() {
        return fluidBudget;
    }

    public RateBudget energyInBudget() {
        return energyInBudget;
    }

    public RateBudget energyOutBudget() {
        return energyOutBudget;
    }

    /** Items/fluids that could not be kept (only from removal settlement); the platform drains and drops them. */
    public List<Object[]> drainSpill() {
        List<Object[]> out = new ArrayList<>(pendingSpill);
        pendingSpill.clear();
        return out;
    }

    private void onContentsChanged() {
        dirty = true;
        for (InstalledModule m : faces) {
            if (m != null) {
                m.wake = true;
            }
        }
    }

    /** Called by the platform when a neighbouring block changed, so sleeping ports re-check promptly. */
    public void wakeAll() {
        onContentsChanged();
    }

    private void markChanged() {
        revision++;
        dirty = true;
    }

    public void trace(String line) {
        if (line == null) {
            return;
        }
        if (line.length() > 120) {
            line = line.substring(0, 120);
        }
        trace.addLast(clock.getAsLong() + " " + line);
        while (trace.size() > TRACE_LIMIT) {
            trace.removeFirst();
        }
    }

    // ------------------------------------------------------------------ configuration

    public record Result(boolean ok, String code, String detail) {
        public static Result success() {
            return new Result(true, "OK", "");
        }

        public static Result fail(String code, String detail) {
            return new Result(false, code, detail);
        }
    }

    public Result install(LocalFace face, ModuleDefinition def, FfData config) {
        if (faces[face.ordinal()] != null) {
            return Result.fail("FACE_OCCUPIED", face.key());
        }
        if (!def.allowedFaces().contains(face)) {
            return Result.fail("FACE_NOT_ALLOWED", def.id() + " on " + face.key());
        }
        if (FieldframeRegistry.isDisabled(def.id())) {
            return Result.fail("MODULE_DISABLED", def.id().toString());
        }
        InstalledModule m = new InstalledModule(def, config, ++installGeneration);
        faces[face.ordinal()] = m;
        markChanged();
        Ctx ctx = new Ctx(face, m, currentWorld);
        safeCall(m, () -> m.behavior.onInstalled(ctx));
        postEvent(new FieldframeEvents.ModuleInstalled(ctx.address(), def.id(), face));
        trace("install " + def.id() + " on " + face.key());
        return Result.success();
    }

    /** Removes a module, settling in-flight work. Returns spilled resources the caller must hand out. */
    public List<Object[]> remove(LocalFace face, ChassisWorld world) {
        InstalledModule m = faces[face.ordinal()];
        if (m == null) {
            return List.of();
        }
        List<Object[]> spill = new ArrayList<>();
        if (!m.isMissing()) {
            Ctx ctx = new Ctx(face, m, world);
            ctx.spillTarget = spill;
            safeCall(m, () -> m.behavior.onRemoved(ctx));
        }
        faces[face.ordinal()] = null;
        redstoneChanged = true;
        markChanged();
        postEvent(new FieldframeEvents.ModuleRemoved(address + "/" + face.key(), m.moduleId, face));
        trace("remove " + m.moduleId + " from " + face.key());
        return spill;
    }

    public Result moveModule(LocalFace from, LocalFace to) {
        InstalledModule m = faces[from.ordinal()];
        if (m == null) {
            return Result.fail("FACE_EMPTY", from.key());
        }
        if (faces[to.ordinal()] != null) {
            return Result.fail("FACE_OCCUPIED", to.key());
        }
        if (!m.isMissing() && !m.definition.allowedFaces().contains(to)) {
            return Result.fail("FACE_NOT_ALLOWED", to.key());
        }
        faces[to.ordinal()] = m;
        faces[from.ordinal()] = null;
        redstoneChanged = true;
        markChanged();
        return Result.success();
    }

    public Result setBinding(LocalFace face, String key, int index) {
        InstalledModule m = faces[face.ordinal()];
        if (m == null || m.isMissing()) {
            return Result.fail("FACE_EMPTY", face.key());
        }
        BindingSpec spec = m.definition.binding(key);
        if (spec == null) {
            return Result.fail("NO_SUCH_BINDING", key);
        }
        if (index < -1 || index > spec.kind().maxIndex() || (index == -1 && !spec.optional())) {
            return Result.fail("BINDING_OUT_OF_RANGE", key + "=" + index);
        }
        m.bindings.put(key, index);
        m.wake = true;
        markChanged();
        return Result.success();
    }

    public Result setConfig(LocalFace face, FfData untrusted) {
        InstalledModule m = faces[face.ordinal()];
        if (m == null || m.isMissing()) {
            return Result.fail("FACE_EMPTY", face.key());
        }
        m.setConfig(untrusted);
        m.wake = true;
        markChanged();
        Ctx ctx = new Ctx(face, m, currentWorld);
        safeCall(m, () -> m.behavior.onConfigChanged(ctx));
        postEvent(new FieldframeEvents.ModuleConfigured(ctx.address(), m.moduleId));
        return Result.success();
    }

    // ------------------------------------------------------------------ upgrades

    public Result installUpgrade(int bay, UpgradeDefinition up) {
        if (waitingForParts()) {
            return Result.fail("PARTS_MISSING", "");
        }
        if (bay < 0 || bay >= serviceBays()) {
            return Result.fail("NO_SUCH_BAY", String.valueOf(bay));
        }
        if (upgrades[bay] != null) {
            return Result.fail("BAY_OCCUPIED", String.valueOf(bay));
        }
        upgrades[bay] = up.id();
        Result r = applyCapacities();
        if (!r.ok()) {
            upgrades[bay] = null;
            applyCapacities();
            return r;
        }
        markChanged();
        return Result.success();
    }

    /** Refuses removal when resulting capacities would truncate stored resources. */
    public Result removeUpgrade(int bay) {
        if (waitingForParts()) {
            return Result.fail("PARTS_MISSING", "");
        }
        if (bay < 0 || bay >= upgrades.length || upgrades[bay] == null) {
            return Result.fail("BAY_EMPTY", String.valueOf(bay));
        }
        FfId old = upgrades[bay];
        upgrades[bay] = null;
        Result r = applyCapacities();
        if (!r.ok()) {
            upgrades[bay] = old;
            applyCapacities();
            return r;
        }
        markChanged();
        return Result.success();
    }

    private Result applyCapacities() {
        int slots = spec.slotsPerLane() + tier.extraSlotsPerLane();
        long fluidCap = spec.fluidCapacity() + tier.extraFluidCapacity();
        long energyCap = spec.energyCapacity() + tier.extraEnergyCapacity();
        long items = spec.itemsPerTick();
        long fluid = spec.fluidPerTick();
        long rf = tier.extraEnergyPerTick();
        double oc = 0;
        for (FfId id : upgrades) {
            UpgradeDefinition u = id == null ? null : FieldframeRegistry.upgrade(id);
            if (u != null) {
                oc += u.overclock();
                slots += u.extraSlotsPerLane();
                fluidCap += u.extraFluidCapacity();
                energyCap += u.extraEnergyCapacity();
                items += u.extraItemsPerTick();
                fluid += u.extraFluidPerTick();
                rf += u.extraEnergyPerTick();
            }
        }
        slots = Math.min(slots, ItemLane.MAX_SLOTS);
        // A Bolted Chassis part resizes the shared stores to the sum of every part's capacities.
        Result fit = group != null ? group.resizeShared() : resizeStores(lanes, tanks, energy, new Caps(slots, fluidCap, energyCap));
        if (!fit.ok()) {
            return fit;
        }
        itemBudget.setPerTick(items);
        fluidBudget.setPerTick(fluid);
        energyInBudget.setPerTick(spec.energyInPerTick() + rf);
        energyOutBudget.setPerTick(spec.energyOutPerTick() + rf);
        overclock = Math.min(MAX_OVERCLOCK, oc);
        return Result.success();
    }

    // ------------------------------------------------------------------ tick

    /** Runs one tick. Bounded: each installed module runs at most once per tick, in fixed phases. */
    public void tick(ChassisWorld world) {
        currentWorld = world;
        long now = world.gameTime();
        resetBudgets(now);
        control.beginTick(now);
        for (InstalledModule m : faces) {
            if (m != null) {
                m.energyUsedLastTick = m.energyUsedThisTick;
                m.energyGeneratedLastTick = m.energyGeneratedThisTick;
                m.energyUsedThisTick = 0;
                m.energyGeneratedThisTick = 0;
            }
        }
        if (runState() != RunState.RUNNING) {
            return;
        }
        for (TickPhase phase : TickPhase.values()) {
            for (int i = 0; i < 6; i++) {
                LocalFace face = LocalFace.ALL[(cursor + i) % 6];
                InstalledModule m = faces[face.ordinal()];
                if (m == null || m.isMissing() || m.definition.phase() != phase) {
                    continue;
                }
                if (FieldframeRegistry.isDisabled(m.moduleId)) {
                    setStatus(m, Status.of("MODULE_DISABLED", Status.Severity.DISABLED, m.moduleId.toString()));
                    continue;
                }
                if (!m.wake && now < m.sleepUntil) {
                    // A sleeping module keeps its signal outputs: re-drive what it emitted last time it ran.
                    for (int ch = 0; ch < m.heldOutputs.length; ch++) {
                        if (m.heldOutputs[ch] > 0) {
                            control.drive(ch, m.heldOutputs[ch]);
                        }
                    }
                    continue;
                }
                m.wake = false;
                m.sleepUntil = 0;
                java.util.Arrays.fill(m.heldOutputs, 0);
                Ctx ctx = new Ctx(face, m, world);
                safeCall(m, () -> m.behavior.tick(ctx));
                int out = m.behavior.redstoneOutput();
                if (out != m.lastRedstone) {
                    m.lastRedstone = out;
                    redstoneChanged = true;
                }
            }
        }
        cursor = (cursor + 1) % 6;
        int workingProcessors = 0;
        for (InstalledModule m : faces) {
            if (m != null && m.status.severity() == Status.Severity.WORKING) {
                m.lastWorking = now;
                if (m.definition.category() == dev.fieldframe.api.module.ModuleCategory.PROCESSING) {
                    workingProcessors++;
                }
            }
        }
        tickHeat(world, now, workingProcessors);
    }

    /**
     * Overclock heat: each working processor adds {@code HEAT_PER_OVERCLOCK x overclock} a tick; the chassis sheds
     * {@code BASE_COOLING} plus whatever cold blocks on its open faces take (1 per 15 K below 290 K). At MAX_HEAT it
     * overheats and runs at normal speed until it is back down to RECOVERED_HEAT.
     */
    private void tickHeat(ChassisWorld world, long now, int workingProcessors) {
        if (overclock <= 0 && heat <= 0) {
            return;
        }
        if (now >= nextCoolingCheck) {
            nextCoolingCheck = now + 40;
            cooling = BASE_COOLING;
            dev.fieldframe.api.world.WorldActions a = world.actions();
            for (LocalFace f : LocalFace.ALL) {
                int k = a == null || faces[f.ordinal()] != null ? -1 : a.temperature(dev.fieldframe.api.world.WorkTarget.adjacent(f));
                if (k > 0 && k < 290) {
                    cooling += (290 - k) / 15.0;
                }
            }
        }
        double gain = overheated ? 0 : workingProcessors * overclock * HEAT_PER_OVERCLOCK;
        int before = heat;
        heat = (int) Math.max(0, Math.min(MAX_HEAT, Math.round(heat + gain - cooling)));
        if (!overheated && heat >= MAX_HEAT) {
            overheated = true;
            trace("overheated");
            dirty = true;
        } else if (overheated && heat <= RECOVERED_HEAT) {
            overheated = false;
            dirty = true;
        }
        if (heat / 50 != before / 50) {
            dirty = true;
        }
    }

    /** Overclock the processors run at now: the upgrades' total, or 0 while overheated. */
    public double overclock() {
        return overheated ? 0 : overclock;
    }

    /** Overclock the upgrades give, whether or not the chassis is overheated. */
    public double installedOverclock() {
        return overclock;
    }

    public int heat() {
        return heat;
    }

    public boolean overheated() {
        return overheated;
    }

    private void resetBudgets(long now) {
        itemBudget.resetFor(now);
        fluidBudget.resetFor(now);
        energyInBudget.resetFor(now);
        energyOutBudget.resetFor(now);
    }

    private void safeCall(InstalledModule m, Runnable r) {
        try {
            r.run();
        } catch (RuntimeException e) {
            setStatus(m, Statuses.fault(e.getClass().getSimpleName()));
            trace("fault " + m.moduleId + ": " + e);
            m.sleepUntil = clock.getAsLong() + 40;
        }
    }

    private void setStatus(InstalledModule m, Status s) {
        if (!s.equals(m.status)) {
            m.status = s;
            dirty = true;
        }
    }

    private long nextSeed() {
        // SplitMix64 step; persisted so reloads do not replay identical rolls.
        long z = (seed += 0x9E3779B97F4A7C15L);
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    public long allocateOperationId() {
        return nextOperationId++;
    }

    // ------------------------------------------------------------------ external access (platform capabilities)

    /** Context for a face, for use by the platform when servicing a capability query. */
    public ModuleContext contextFor(LocalFace face) {
        InstalledModule m = faces[face.ordinal()];
        return m == null || m.isMissing() ? null : new Ctx(face, m, currentWorld);
    }

    /** Budget-limited item store exposed on a world side, or null. Side-unspecified (null) queries get nothing. */
    public ItemStore exposedItems(WorldDir side) {
        if (side == null || awaitingLink) {
            return null;
        }
        LocalFace face = orientation.toLocal(side);
        InstalledModule m = faces[face.ordinal()];
        if (m == null || m.isMissing() || !m.definition.exposes(Exposure.ITEM)) {
            return null;
        }
        ItemStore inner = m.behavior.exposeItems(new Ctx(face, m, currentWorld));
        return inner == null ? null : new BudgetedItems(inner, itemBudget, clock);
    }

    public FluidStore exposedFluids(WorldDir side) {
        if (side == null || awaitingLink) {
            return null;
        }
        LocalFace face = orientation.toLocal(side);
        InstalledModule m = faces[face.ordinal()];
        if (m == null || m.isMissing() || !m.definition.exposes(Exposure.FLUID)) {
            return null;
        }
        FluidStore inner = m.behavior.exposeFluids(new Ctx(face, m, currentWorld));
        return inner == null ? null : new BudgetedFluids(inner, fluidBudget, clock);
    }

    public EnergyStore exposedEnergy(WorldDir side) {
        if (side == null || awaitingLink) {
            return null;
        }
        LocalFace face = orientation.toLocal(side);
        InstalledModule m = faces[face.ordinal()];
        if (m == null || m.isMissing() || !m.definition.exposes(Exposure.ENERGY)) {
            return null;
        }
        Ctx ctx = new Ctx(face, m, currentWorld);
        boolean in = m.behavior.exposeEnergyReceive(ctx);
        boolean out = m.behavior.exposeEnergyExtract(ctx);
        if (!in && !out) {
            return null;
        }
        return new BudgetedEnergy(energy, in, out, energyInBudget, energyOutBudget, clock);
    }

    public int redstoneOutput(WorldDir side) {
        LocalFace face = orientation.toLocal(side);
        InstalledModule m = faces[face.ordinal()];
        if (m == null || m.isMissing() || !m.definition.exposes(Exposure.REDSTONE_OUT)) {
            return 0;
        }
        return Math.max(0, Math.min(15, m.behavior.redstoneOutput()));
    }

    /** Whether any face accepts redstone input (platform uses this for connectivity hints). */
    public boolean acceptsRedstone(WorldDir side) {
        LocalFace face = orientation.toLocal(side);
        InstalledModule m = faces[face.ordinal()];
        return m != null && !m.isMissing() && (m.definition.exposes(Exposure.REDSTONE_IN) || m.definition.exposes(Exposure.REDSTONE_OUT));
    }

    /** Item lanes reachable by players through access panels (union over all panel faces). */
    public boolean[] playerAccessibleLanes() {
        boolean[] out = new boolean[ChassisSpec.ITEM_LANES];
        for (LocalFace f : LocalFace.ALL) {
            InstalledModule m = faces[f.ordinal()];
            if (m != null && !m.isMissing() && m.definition.exposes(Exposure.PLAYER_ACCESS)) {
                for (int lane : m.behavior.playerAccessibleLanes(new Ctx(f, m, currentWorld))) {
                    if (lane >= 0 && lane < out.length) {
                        out[lane] = true;
                    }
                }
            }
        }
        return out;
    }

    public boolean[] playerAccessibleTanks() {
        boolean[] out = new boolean[ChassisSpec.FLUID_LANES];
        for (LocalFace f : LocalFace.ALL) {
            InstalledModule m = faces[f.ordinal()];
            if (m != null && !m.isMissing() && m.definition.exposes(Exposure.PLAYER_ACCESS)) {
                for (int t : m.behavior.playerAccessibleTanks(new Ctx(f, m, currentWorld))) {
                    if (t >= 0 && t < out.length) {
                        out[t] = true;
                    }
                }
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ transactional snapshots

    /**
     * Copies all resource state reachable through exposed faces (lanes, reservoirs, RF buffer and the shared
     * budgets) so a platform transaction journal can roll back both contents and accounting on abort.
     */
    public Object snapshotResources() {
        Object[] s = new Object[11];
        for (int i = 0; i < 3; i++) {
            s[i] = lanes[i].snapshot();
            s[3 + i] = tanks[i].snapshot();
        }
        s[6] = energy.snapshot();
        s[7] = itemBudget.snapshot();
        s[8] = fluidBudget.snapshot();
        s[9] = energyInBudget.snapshot();
        s[10] = energyOutBudget.snapshot();
        return s;
    }

    public void restoreResources(Object snapshot) {
        Object[] s = (Object[]) snapshot;
        for (int i = 0; i < 3; i++) {
            lanes[i].restore(s[i]);
            tanks[i].restore(s[3 + i]);
        }
        energy.restore((long[]) s[6]);
        itemBudget.restore((long[]) s[7]);
        fluidBudget.restore((long[]) s[8]);
        energyInBudget.restore((long[]) s[9]);
        energyOutBudget.restore((long[]) s[10]);
    }

    // ------------------------------------------------------------------ persistence

    public FfData save() {
        FfData d = new FfData().putInt("schema", SCHEMA_VERSION);
        d.putString("spec", spec.id().toString());
        d.putString("front", orientation.front().name().toLowerCase(java.util.Locale.ROOT));
        boolean borrowed = borrowedStores;
        Caps own = borrowed ? ownCapacities() : null;
        List<Object> ls = new ArrayList<>();
        for (ItemLane l : lanes) {
            ls.add(borrowed ? new ItemLane(l.name(), own.slots()).save(codec) : l.save(codec));
        }
        d.putList("lanes", ls);
        List<Object> ts = new ArrayList<>();
        for (FluidReservoir t : tanks) {
            ts.add(borrowed ? new FluidReservoir(t.name(), own.fluid()).save(codec) : t.save(codec));
        }
        d.putList("tanks", ts);
        d.put("energy", borrowed ? new EnergyBuffer(own.energy()).save() : energy.save());
        d.put("control", borrowed ? new ControlBus().save() : control.save());
        if (!boltInfo.isEmpty()) {
            d.put("bolt", boltInfo.copy());
        }
        d.putInt("cursor", cursor).putLong("seed", seed).putLong("nextOp", nextOperationId).putInt("gen", installGeneration);
        d.putBool("paused", paused);
        if (quiet) {
            d.putBool("quiet", true);
        }
        if (!comparatorSource.equals(DEFAULT_COMPARATOR)) {
            d.putString("comparator", comparatorSource);
        }
        if (!ownerId.isEmpty()) {
            d.put("owner", new FfData().putString("id", ownerId).putString("name", ownerName));
        }
        List<Object> ups = new ArrayList<>();
        for (FfId u : upgrades) {
            ups.add(u == null ? "" : u.toString());
        }
        d.putList("upgrades", ups);
        d.putString("tier", tier.id().toString());
        if (heat > 0) {
            d.putInt("heat", heat);
            d.putBool("overheated", overheated);
        }
        FfData fd = new FfData();
        for (LocalFace f : LocalFace.ALL) {
            InstalledModule m = faces[f.ordinal()];
            if (m != null) {
                fd.put(f.key(), saveModule(m));
            }
        }
        d.put("faces", fd);
        return d;
    }

    private FfData saveModule(InstalledModule m) {
        if (m.isMissing()) {
            return m.rawForMissing.copy();
        }
        FfData md = new FfData().putString("module", m.moduleId.toString()).putInt("gen", m.generation);
        md.put("config", m.config.copy());
        FfData b = new FfData();
        m.bindings.forEach(b::putInt);
        md.put("bindings", b);
        md.put("state", m.behavior.save());
        md.putLong("sleepUntil", m.sleepUntil);
        return md;
    }

    /** Loads state. Never throws for malformed data; problems are reported in the returned list. */
    public List<String> load(FfData d) {
        List<String> problems = new ArrayList<>();
        int schema = d.getInt("schema", 1);
        if (schema > SCHEMA_VERSION) {
            problems.add("Saved with newer schema " + schema + "; loading what is understood");
        }
        try {
            orientation = new Orientation(WorldDir.valueOf(d.getString("front", "north").toUpperCase(java.util.Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            problems.add("bad orientation");
        }
        FfData owner = d.getDataOrNull("owner");
        ownerId = owner == null ? "" : owner.getString("id", "");
        ownerName = owner == null ? "" : owner.getString("name", "");
        heat = Math.max(0, Math.min(MAX_HEAT, d.getInt("heat", 0)));
        overheated = d.getBool("overheated", false);
        FfId tierId = FfId.tryParse(d.getString("tier", ""));
        dev.fieldframe.api.chassis.ChassisTier savedTier = tierId == null ? null : FieldframeRegistry.tier(tierId);
        if (savedTier != null) {
            tier = savedTier;
        } else if (tierId != null && !tierId.equals(tier.id())) {
            problems.add("unknown tier " + tierId + "; kept " + tier.id());
        }
        List<Object> ups = d.getList("upgrades");
        for (int i = 0; i < upgrades.length; i++) {
            upgrades[i] = i < ups.size() && ups.get(i) instanceof String s && !s.isEmpty() ? FfId.tryParse(s) : null;
        }
        // Capacities first so lane loads do not clip contents.
        applyCapacitiesForLoad();
        List<Object> ls = d.getList("lanes");
        for (int i = 0; i < lanes.length && i < ls.size(); i++) {
            if (ls.get(i) instanceof FfData ld) {
                int bad = lanes[i].load(ld, codec);
                if (bad > 0) {
                    problems.add(BindingSpec.laneName(i) + ": " + bad + " unreadable stacks skipped");
                }
            }
        }
        List<Object> ts = d.getList("tanks");
        for (int i = 0; i < tanks.length && i < ts.size(); i++) {
            if (ts.get(i) instanceof FfData td) {
                tanks[i].load(td, codec);
            }
        }
        energy.load(d.getData("energy"));
        control.load(d.getData("control"));
        cursor = Math.floorMod(d.getInt("cursor", 0), 6);
        seed = d.getLong("seed", seed);
        nextOperationId = Math.max(1, d.getLong("nextOp", 1));
        installGeneration = Math.max(0, d.getInt("gen", 0));
        paused = d.getBool("paused", false);
        FfData bolt = d.getDataOrNull("bolt");
        boltInfo = bolt == null ? new FfData() : bolt.copy();
        awaitingLink = boltedOnDisk();
        quiet = d.getBool("quiet", false);
        String cmp = d.getString("comparator", DEFAULT_COMPARATOR);
        comparatorSource = COMPARATOR_SOURCES.contains(cmp) ? cmp : DEFAULT_COMPARATOR;
        FfData fd = d.getData("faces");
        for (LocalFace f : LocalFace.ALL) {
            faces[f.ordinal()] = null;
            FfData md = fd.getDataOrNull(f.key());
            if (md == null) {
                continue;
            }
            FfId id = FfId.tryParse(md.getString("module", null));
            if (id == null) {
                problems.add(f.key() + ": unreadable module id; data kept");
                faces[f.ordinal()] = new InstalledModule(FfId.ff("unknown"), md, ++installGeneration);
                continue;
            }
            ModuleDefinition def = definitions.apply(id);
            if (def == null) {
                problems.add(f.key() + ": module " + id + " is not defined; kept as recoverable placeholder");
                faces[f.ordinal()] = new InstalledModule(id, md, md.getInt("gen", ++installGeneration));
                continue;
            }
            InstalledModule m = new InstalledModule(def, md.getData("config"), md.getInt("gen", ++installGeneration));
            FfData b = md.getData("bindings");
            for (BindingSpec bs : def.bindings()) {
                if (b.has(bs.key())) {
                    int idx = b.getInt(bs.key(), bs.defaultIndex());
                    if (idx >= -1 && idx <= bs.kind().maxIndex() && (idx >= 0 || bs.optional())) {
                        m.bindings.put(bs.key(), idx);
                    }
                }
            }
            try {
                m.behavior.load(md.getData("state"));
            } catch (RuntimeException e) {
                problems.add(f.key() + ": state of " + id + " unreadable (" + e.getMessage() + "); reset");
            }
            m.sleepUntil = md.getLong("sleepUntil", 0);
            m.wake = true;
            faces[f.ordinal()] = m;
        }
        return problems;
    }

    private void applyCapacitiesForLoad() {
        Result r = applyCapacities();
        if (!r.ok()) {
            trace("capacity apply on load: " + r.detail());
        }
    }

    // ------------------------------------------------------------------ fuel routing

    /**
     * Where an item arriving through a port into storage {@code lane} should go: the fuel storage of an engine on this
     * chassis that burns it, unless something taking from {@code lane} can use it (a machine with a recipe for it, or an
     * engine that burns from {@code lane}). Otherwise {@code lane} itself.
     */
    public int routeIncoming(dev.fieldframe.api.resource.ItemVariant v, int lane) {
        ChassisWorld w = currentWorld;
        if (w == null || v == null) {
            return lane;
        }
        int target = -1;
        for (InstalledModule m : faces) {
            if (m == null || m.isMissing()) {
                continue;
            }
            BindingSpec fuel = m.definition.binding("fuel");
            int f = fuel == null || fuel.kind() != BindingSpec.Kind.ITEM_LANE ? -1 : m.binding("fuel");
            if (f < 0) {
                continue;
            }
            boolean burns;
            try {
                burns = m.behavior.burns(v, w);
            } catch (RuntimeException e) {
                burns = false;
            }
            if (!burns) {
                continue;
            }
            if (f == lane) {
                return lane;
            }
            if (target < 0) {
                target = f;
            }
        }
        if (target < 0) {
            return lane;
        }
        for (InstalledModule m : faces) {
            if (m == null || m.isMissing() || m.definition.processFamily() == null || m.binding("input") != lane) {
                continue;
            }
            for (dev.fieldframe.api.process.ProcessRecipe r : w.recipes().recipes(m.definition.processFamily())) {
                for (dev.fieldframe.api.process.ProcessRecipe.ItemInput in : r.itemInputs()) {
                    if (in.matcher().test(v, w.tags())) {
                        return lane;
                    }
                }
            }
        }
        return target;
    }

    /** A storage seen from a port: inserts are routed with {@link #routeIncoming}; everything else is the storage itself. */
    private final class IncomingLane implements ItemStore {
        private final int index;

        IncomingLane(int index) {
            this.index = index;
        }

        @Override
        public int slots() {
            return lanes[index].slots();
        }

        @Override
        public dev.fieldframe.api.resource.ItemVariant variant(int slot) {
            return lanes[index].variant(slot);
        }

        @Override
        public int count(int slot) {
            return lanes[index].count(slot);
        }

        @Override
        public int insert(dev.fieldframe.api.resource.ItemVariant v, int amount, boolean simulate) {
            return lanes[routeIncoming(v, index)].insert(v, amount, simulate);
        }

        @Override
        public int insertSlot(int slot, dev.fieldframe.api.resource.ItemVariant v, int amount, boolean simulate) {
            int target = routeIncoming(v, index);
            return target == index ? lanes[index].insertSlot(slot, v, amount, simulate) : lanes[target].insert(v, amount, simulate);
        }

        @Override
        public int extractSlot(int slot, int amount, boolean simulate) {
            return lanes[index].extractSlot(slot, amount, simulate);
        }
    }

    // ------------------------------------------------------------------ context

    private final class Ctx implements ModuleContext, ModuleContext.Energy {
        private final LocalFace face;
        private final InstalledModule m;
        private final ChassisWorld world;
        List<Object[]> spillTarget;

        Ctx(LocalFace face, InstalledModule m, ChassisWorld world) {
            this.face = face;
            this.m = m;
            this.world = world;
        }

        @Override
        public ModuleDefinition definition() {
            return m.definition;
        }

        @Override
        public LocalFace face() {
            return face;
        }

        @Override
        public WorldDir worldDirection() {
            return orientation.toWorld(face);
        }

        @Override
        public ConfigView config() {
            return m.configView;
        }

        @Override
        public double tunable(String key) {
            return FieldframeRegistry.tunable(m.definition, key);
        }

        @Override
        public double chassisSpeed() {
            return tier.speed();
        }

        @Override
        public double overclock() {
            return ChassisCore.this.overclock();
        }

        @Override
        public int chassisTier() {
            return tier.level();
        }

        private int index(String key, BindingSpec.Kind kind) {
            BindingSpec s = m.definition.binding(key);
            if (s == null || s.kind() != kind) {
                return -1;
            }
            return m.binding(key);
        }

        @Override
        public ItemStore items(String key) {
            int i = index(key, BindingSpec.Kind.ITEM_LANE);
            return i < 0 ? null : lanes[i];
        }

        @Override
        public ItemStore itemsIn(String key) {
            int i = index(key, BindingSpec.Kind.ITEM_LANE);
            return i < 0 ? null : new IncomingLane(i);
        }

        @Override
        public FluidStore fluids(String key) {
            int i = index(key, BindingSpec.Kind.FLUID_LANE);
            return i < 0 ? null : tanks[i];
        }

        @Override
        public String bindingLabel(String key) {
            BindingSpec s = m.definition.binding(key);
            return s == null ? "?" : BindingSpec.label(s.kind(), m.binding(key));
        }

        @Override
        public int bindingIndex(String key) {
            return m.binding(key);
        }

        @Override
        public ModuleContext.Energy energy() {
            return this;
        }

        @Override
        public int signal(String key) {
            int i = index(key, BindingSpec.Kind.SIGNAL_IN);
            return i < 0 ? -1 : control.read(i);
        }

        @Override
        public void emit(String key, int value) {
            int i = index(key, BindingSpec.Kind.SIGNAL_OUT);
            if (i >= 0) {
                control.drive(i, value);
                m.heldOutputs[i] = Math.max(m.heldOutputs[i], Math.max(0, Math.min(15, value)));
            }
        }

        @Override
        public int channel(int index) {
            return control.read(index);
        }

        @Override
        public void driveChannel(int index, int value) {
            control.drive(index, value);
            if (index >= 0 && index < m.heldOutputs.length) {
                m.heldOutputs[index] = Math.max(m.heldOutputs[index], Math.max(0, Math.min(15, value)));
            }
        }

        @Override
        public ChassisView chassis() {
            return new ChassisView() {
                @Override
                public long itemCount(int lane) {
                    return lane < 0 || lane > 2 ? 0 : lanes[lane].totalCount();
                }

                @Override
                public long itemCapacity(int lane) {
                    if (lane < 0 || lane > 2) {
                        return 0;
                    }
                    long cap = 0;
                    for (int s = 0; s < lanes[lane].slots(); s++) {
                        cap += lanes[lane].variant(s) == null ? 64 : lanes[lane].variant(s).maxStackSize();
                    }
                    return cap;
                }

                @Override
                public long fluidAmount(int tank) {
                    return tank < 0 || tank > 2 ? 0 : tanks[tank].amount();
                }

                @Override
                public long fluidCapacity(int tank) {
                    return tank < 0 || tank > 2 ? 0 : tanks[tank].capacity();
                }

                @Override
                public List<Peer> peers() {
                    long now = clock.getAsLong();
                    List<Peer> out = new ArrayList<>();
                    for (LocalFace f : LocalFace.ALL) {
                        InstalledModule p = faces[f.ordinal()];
                        if (p != null && !p.isMissing()) {
                            out.add(new Peer(f, p.definition, now - p.lastWorking <= WORK_LINGER));
                        }
                    }
                    return out;
                }
            };
        }

        @Override
        public void setStatus(Status status) {
            ChassisCore.this.setStatus(m, status);
        }

        @Override
        public void sleep(int ticks) {
            m.sleepUntil = clock.getAsLong() + Math.max(1, Math.min(ticks, 1200));
        }

        @Override
        public long gameTime() {
            return clock.getAsLong();
        }

        @Override
        public long nextSeed() {
            return ChassisCore.this.nextSeed();
        }

        @Override
        public ChassisWorld world() {
            return world;
        }

        @Override
        public Budget itemBudget() {
            return budgetView(itemBudget);
        }

        @Override
        public Budget fluidBudget() {
            return budgetView(fluidBudget);
        }

        @Override
        public EnergyStore terminalEnergy(boolean allowReceive, boolean allowExtract) {
            return new BudgetedEnergy(energy, allowReceive, allowExtract, energyInBudget, energyOutBudget, clock);
        }

        private Budget budgetView(RateBudget b) {
            b.resetFor(clock.getAsLong());
            return new Budget() {
                @Override
                public long remaining() {
                    return b.remaining();
                }

                @Override
                public void spend(long actual) {
                    b.spend(actual);
                }
            };
        }

        @Override
        public void spill(ItemVariant v, int count) {
            if (v == null || count <= 0) {
                return;
            }
            List<Object[]> target = spillTarget != null ? spillTarget : pendingSpill;
            target.add(new Object[]{v, count});
            trace("spill " + count + "x " + v.id());
        }

        @Override
        public void spillFluid(FluidVariant v, long amount) {
            if (v == null || amount <= 0) {
                return;
            }
            List<Object[]> target = spillTarget != null ? spillTarget : pendingSpill;
            target.add(new Object[]{v, amount});
            trace("fluid lost/spilled " + amount + " mB " + v.id());
        }

        @Override
        public void trace(String message) {
            ChassisCore.this.trace(face.letter + " " + message);
        }

        @Override
        public boolean post(FieldframeEvent event) {
            return postEvent(event);
        }

        @Override
        public String address() {
            return ChassisCore.this.address + "/" + face.key() + "#" + m.generation;
        }

        // Energy
        @Override
        public long stored() {
            return energy.stored();
        }

        @Override
        public long capacity() {
            return energy.capacity();
        }

        @Override
        public boolean consume(long amount) {
            if (energy.consumeExactly(amount)) {
                m.energyUsedThisTick += amount;
                return true;
            }
            return false;
        }

        @Override
        public long generate(long amount) {
            long r = energy.receive(amount, false);
            m.energyGeneratedThisTick += r;
            return r;
        }
    }

    // ------------------------------------------------------------------ budget wrappers

    static final class BudgetedItems implements ItemStore {
        private final ItemStore inner;
        private final RateBudget budget;
        private final LongSupplier clock;

        BudgetedItems(ItemStore inner, RateBudget budget, LongSupplier clock) {
            this.inner = inner;
            this.budget = budget;
            this.clock = clock;
        }

        @Override
        public int slots() {
            return inner.slots();
        }

        @Override
        public ItemVariant variant(int slot) {
            return inner.variant(slot);
        }

        @Override
        public int count(int slot) {
            return inner.count(slot);
        }

        @Override
        public int insert(ItemVariant v, int amount, boolean simulate) {
            budget.resetFor(clock.getAsLong());
            int allowed = (int) budget.allow(amount);
            int r = inner.insert(v, allowed, simulate);
            if (!simulate) {
                budget.spend(r);
            }
            return r;
        }

        @Override
        public int insertSlot(int slot, ItemVariant v, int amount, boolean simulate) {
            budget.resetFor(clock.getAsLong());
            int allowed = (int) budget.allow(amount);
            int r = inner.insertSlot(slot, v, allowed, simulate);
            if (!simulate) {
                budget.spend(r);
            }
            return r;
        }

        @Override
        public int extractSlot(int slot, int amount, boolean simulate) {
            budget.resetFor(clock.getAsLong());
            int allowed = (int) budget.allow(amount);
            int r = inner.extractSlot(slot, allowed, simulate);
            if (!simulate) {
                budget.spend(r);
            }
            return r;
        }
    }

    static final class BudgetedFluids implements FluidStore {
        private final FluidStore inner;
        private final RateBudget budget;
        private final LongSupplier clock;

        BudgetedFluids(FluidStore inner, RateBudget budget, LongSupplier clock) {
            this.inner = inner;
            this.budget = budget;
            this.clock = clock;
        }

        @Override
        public int tanks() {
            return inner.tanks();
        }

        @Override
        public FluidVariant fluid(int tank) {
            return inner.fluid(tank);
        }

        @Override
        public long amount(int tank) {
            return inner.amount(tank);
        }

        @Override
        public long capacity(int tank) {
            return inner.capacity(tank);
        }

        @Override
        public long fill(FluidVariant v, long amount, boolean simulate) {
            budget.resetFor(clock.getAsLong());
            long r = inner.fill(v, budget.allow(amount), simulate);
            if (!simulate) {
                budget.spend(r);
            }
            return r;
        }

        @Override
        public long drain(FluidVariant v, long amount, boolean simulate) {
            budget.resetFor(clock.getAsLong());
            long r = inner.drain(v, budget.allow(amount), simulate);
            if (!simulate) {
                budget.spend(r);
            }
            return r;
        }
    }

    /** All power terminals share one directional budget pair and the single buffer. */
    static final class BudgetedEnergy implements EnergyStore {
        private final EnergyBuffer buffer;
        private final boolean in, out;
        private final RateBudget inBudget, outBudget;
        private final LongSupplier clock;

        BudgetedEnergy(EnergyBuffer buffer, boolean in, boolean out, RateBudget inBudget, RateBudget outBudget, LongSupplier clock) {
            this.buffer = buffer;
            this.in = in;
            this.out = out;
            this.inBudget = inBudget;
            this.outBudget = outBudget;
            this.clock = clock;
        }

        @Override
        public long stored() {
            return buffer.stored();
        }

        @Override
        public long capacity() {
            return buffer.capacity();
        }

        @Override
        public long receive(long amount, boolean simulate) {
            if (!in) {
                return 0;
            }
            inBudget.resetFor(clock.getAsLong());
            long r = buffer.receive(inBudget.allow(amount), simulate);
            if (!simulate) {
                inBudget.spend(r);
            }
            return r;
        }

        @Override
        public long extract(long amount, boolean simulate) {
            if (!out) {
                return 0;
            }
            outBudget.resetFor(clock.getAsLong());
            long r = buffer.extract(outBudget.allow(amount), simulate);
            if (!simulate) {
                outBudget.spend(r);
            }
            return r;
        }

        @Override
        public boolean canReceive() {
            return in;
        }

        @Override
        public boolean canExtract() {
            return out;
        }
    }

    /** Snapshot for diagnostics: per-face status lines. */
    public Map<LocalFace, Status> statuses() {
        Map<LocalFace, Status> out = new java.util.EnumMap<>(LocalFace.class);
        for (LocalFace f : LocalFace.ALL) {
            InstalledModule m = faces[f.ordinal()];
            if (m != null) {
                out.put(f, m.status);
            }
        }
        return out;
    }
}
