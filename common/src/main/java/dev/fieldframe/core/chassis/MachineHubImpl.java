package dev.fieldframe.core.chassis;

import dev.fieldframe.api.chassis.ChassisSpec;
import dev.fieldframe.api.data.FfData;
import dev.fieldframe.api.frame.MachineHub;
import dev.fieldframe.api.resource.FluidVariant;
import dev.fieldframe.api.resource.ItemVariant;
import dev.fieldframe.api.resource.VariantCodec;
import dev.fieldframe.core.storage.FluidReservoir;
import dev.fieldframe.core.storage.ItemLane;

import java.util.ArrayList;
import java.util.List;

/**
 * {@link MachineHub} implementation: a store-holding {@link ChassisCore} (it never runs modules) whose storages,
 * tanks, RF buffer and control channels the linked sockets share, the same way Bolted Chassis parts share their
 * lead's. Linked sockets save empty stores ({@link ChassisCore#storesBorrowed()}); only the hub saves contents.
 */
public final class MachineHubImpl implements MachineHub {
    private final ChassisCore store;
    private final List<ChassisCore> sockets = new ArrayList<>();
    private boolean dirty;

    public MachineHubImpl(VariantCodec codec) {
        store = new ChassisCore(ChassisSpec.STANDARD, codec, () -> 0);
        wire();
    }

    private void wire() {
        Runnable r = this::contentsChanged;
        for (int i = 0; i < 3; i++) {
            store.lane(i).setOnChange(r);
            store.tank(i).setOnChange(r);
        }
        store.energy().setOnChange(() -> dirty = true);
    }

    private void contentsChanged() {
        dirty = true;
        for (ChassisCore s : sockets) {
            s.onSharedContentsChanged();
        }
    }

    /** Links a socket: its modules now work from the hub's stores (anything in its own stores goes to its spill). */
    public void link(ChassisCore socket) {
        if (sockets.contains(socket)) {
            return;
        }
        socket.pendingSpill().addAll(socket.drainOwnStores());
        socket.shareStoresOf(store);
        sockets.add(socket);
        socket.setAwaitingLink(false);
        socket.changed();
    }

    /** Unlinks a socket: it gets empty stores of its own and stays dormant (paused, exposes nothing). */
    public void unlink(ChassisCore socket) {
        if (!sockets.remove(socket)) {
            return;
        }
        socket.ownStores();
        socket.setAwaitingLink(true);
        socket.changed();
    }

    /** Unlinks every socket (the frame came apart). */
    public void unlinkAll() {
        for (ChassisCore s : List.copyOf(sockets)) {
            unlink(s);
        }
    }

    public boolean isLinked(ChassisCore socket) {
        return sockets.contains(socket);
    }

    @Override
    public List<Spill> resize(int slotsPerLane, long mbPerTank, long rfCapacity) {
        ChassisCore.Caps caps = new ChassisCore.Caps(Math.max(1, Math.min(ItemLane.MAX_SLOTS, slotsPerLane)), Math.max(0, mbPerTank), Math.max(0, rfCapacity));
        List<Spill> out = new ArrayList<>();
        for (Object[] s : ChassisCore.forceFit(store.lanesArray(), store.tanksArray(), store.energy(), caps)) {
            out.add(new Spill((ItemVariant) s[0], (Integer) s[1]));
        }
        ChassisCore.resizeStores(store.lanesArray(), store.tanksArray(), store.energy(), caps);
        dirty = true;
        for (ChassisCore s : sockets) {
            s.onSharedContentsChanged();
        }
        return out;
    }

    @Override
    public int slotsPerLane() {
        return store.lane(0).slots();
    }

    @Override
    public long mbPerTank() {
        return store.tank(0).capacity();
    }

    @Override
    public long rfCapacity() {
        return store.energy().capacity();
    }

    @Override
    public long rfStored() {
        return store.energy().stored();
    }

    @Override
    public long itemCount(int lane) {
        return lane < 0 || lane > 2 ? 0 : store.lane(lane).totalCount();
    }

    @Override
    public FluidVariant fluid(int tank) {
        return tank < 0 || tank > 2 ? null : store.tank(tank).fluid();
    }

    @Override
    public long fluidAmount(int tank) {
        return tank < 0 || tank > 2 ? 0 : store.tank(tank).amount();
    }

    @Override
    public dev.fieldframe.api.resource.ItemStore items(int lane) {
        return store.lane(Math.max(0, Math.min(2, lane)));
    }

    @Override
    public dev.fieldframe.api.resource.FluidStore tank(int tank) {
        return store.tank(Math.max(0, Math.min(2, tank)));
    }

    @Override
    public dev.fieldframe.api.resource.EnergyStore energy() {
        return store.energy();
    }

    @Override
    public int linkedSockets() {
        return sockets.size();
    }

    @Override
    public List<Spill> drainAll() {
        List<Spill> out = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            ItemLane l = store.lane(i);
            for (int s = 0; s < l.slots(); s++) {
                ItemVariant v = l.variant(s);
                int n = l.count(s);
                if (v != null && n > 0) {
                    l.extractSlot(s, n, false);
                    out.add(new Spill(v, n));
                }
            }
            FluidReservoir t = store.tank(i);
            if (t.fluid() != null && t.amount() > 0) {
                t.drain(t.fluid(), t.amount(), false);
            }
        }
        store.energy().extract(store.energy().stored(), false);
        dirty = true;
        return out;
    }

    @Override
    public boolean consumeDirty() {
        boolean d = dirty;
        dirty = false;
        return d;
    }

    @Override
    public FfData save() {
        return store.saveStores();
    }

    @Override
    public List<String> load(FfData d) {
        List<String> problems = store.loadStores(d);
        wire();
        return problems;
    }

    /** The store holder (tests and the platform's screens). */
    public ChassisCore store() {
        return store;
    }
}
