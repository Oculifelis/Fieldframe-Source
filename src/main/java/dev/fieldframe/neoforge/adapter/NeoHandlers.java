package dev.fieldframe.neoforge.adapter;

import dev.fieldframe.api.resource.EnergyStore;
import dev.fieldframe.api.resource.FluidStore;
import dev.fieldframe.api.resource.FluidVariant;
import dev.fieldframe.api.resource.ItemStore;
import dev.fieldframe.api.resource.ItemVariant;
import dev.fieldframe.core.chassis.ChassisCore;
import dev.fieldframe.core.util.Transfers;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.transfer.ResourceHandler;
import net.neoforged.neoforge.transfer.energy.EnergyHandler;
import net.neoforged.neoforge.transfer.fluid.FluidResource;
import net.neoforged.neoforge.transfer.item.ItemResource;
import net.neoforged.neoforge.transfer.transaction.SnapshotJournal;
import net.neoforged.neoforge.transfer.transaction.Transaction;
import net.neoforged.neoforge.transfer.transaction.TransactionContext;

import java.util.function.Supplier;

/**
 * Adapters between Fieldframe stores and NeoForge 26.1 transfer handlers.
 * <ul>
 *   <li>{@code Exposed*}: our faces as transactional {@code ResourceHandler}/{@code EnergyHandler}. Every mutation first
 *       registers a {@link ChassisJournal} snapshot of the chassis' resources and budgets, so an aborted transaction
 *       restores contents and accounting exactly.</li>
 *   <li>{@code External*}: neighbour handlers as Fieldframe stores. Each call opens a short root transaction that is
 *       committed (execute) or aborted (simulate) before returning; no transaction is ever held across ticks.</li>
 * </ul>
 */
public final class NeoHandlers {
    private NeoHandlers() {
    }

    /** Snapshot journal over one chassis' resource state. */
    public static final class ChassisJournal extends SnapshotJournal<Object> {
        private final java.util.function.Supplier<Object> snapshot;
        private final java.util.function.Consumer<Object> restore;

        public ChassisJournal(ChassisCore core) {
            this(core::snapshotResources, core::restoreResources);
        }

        /** Journal over any resource state (pads, Ender Tank): snapshot and restore functions. */
        public ChassisJournal(java.util.function.Supplier<Object> snapshot, java.util.function.Consumer<Object> restore) {
            this.snapshot = snapshot;
            this.restore = restore;
        }

        @Override
        protected Object createSnapshot() {
            return snapshot.get();
        }

        @Override
        protected void revertToSnapshot(Object s) {
            restore.accept(s);
        }
    }

    static ItemResource resource(ItemVariant v) {
        return v instanceof McItemVariant m ? ItemResource.of(m.toStack(1)) : ItemResource.EMPTY;
    }

    static McItemVariant toVariant(ItemResource r) {
        return r == null || r.isEmpty() ? null : McItemVariant.of(r.toStack(1));
    }

    static McFluidVariant toVariant(FluidResource r) {
        return r == null || r.isEmpty() ? null : McFluidVariant.of(r.toStack(1));
    }

    static FluidResource resource(FluidVariant v) {
        return v instanceof McFluidVariant m ? FluidResource.of(m.toStack(1)) : FluidResource.EMPTY;
    }

    // ------------------------------------------------------------------ exposed

    public static final class ExposedItems implements ResourceHandler<ItemResource> {
        private final Supplier<ItemStore> store;
        private final ChassisJournal journal;

        public ExposedItems(Supplier<ItemStore> store, ChassisJournal journal) {
            this.store = store;
            this.journal = journal;
        }

        @Override
        public int size() {
            ItemStore s = store.get();
            return s == null ? 0 : s.slots();
        }

        @Override
        public ItemResource getResource(int index) {
            ItemStore s = store.get();
            return s == null ? ItemResource.EMPTY : resource(s.variant(index));
        }

        @Override
        public long getAmountAsLong(int index) {
            ItemStore s = store.get();
            return s == null || !(s.variant(index) instanceof McItemVariant) ? 0 : s.count(index);
        }

        @Override
        public long getCapacityAsLong(int index, ItemResource resource) {
            return resource.isEmpty() ? 64 : resource.getMaxStackSize();
        }

        @Override
        public boolean isValid(int index, ItemResource resource) {
            ItemStore s = store.get();
            McItemVariant v = toVariant(resource);
            return s != null && v != null && s.insertSlot(index, v, 1, true) > 0;
        }

        @Override
        public int insert(int index, ItemResource resource, int amount, TransactionContext tx) {
            ItemStore s = store.get();
            McItemVariant v = toVariant(resource);
            if (s == null || v == null || amount <= 0) {
                return 0;
            }
            if (s.insertSlot(index, v, amount, true) <= 0) {
                return 0;
            }
            journal.updateSnapshots(tx);
            return Transfers.clamp(s.insertSlot(index, v, amount, false), amount);
        }

        @Override
        public int extract(int index, ItemResource resource, int amount, TransactionContext tx) {
            ItemStore s = store.get();
            if (s == null || amount <= 0 || !(s.variant(index) instanceof McItemVariant cur) || !cur.sameAs(toVariant(resource))) {
                return 0;
            }
            if (s.extractSlot(index, amount, true) <= 0) {
                return 0;
            }
            journal.updateSnapshots(tx);
            return Transfers.clamp(s.extractSlot(index, amount, false), amount);
        }
    }

    public static final class ExposedFluids implements ResourceHandler<FluidResource> {
        private final Supplier<FluidStore> store;
        private final ChassisJournal journal;

        public ExposedFluids(Supplier<FluidStore> store, ChassisJournal journal) {
            this.store = store;
            this.journal = journal;
        }

        @Override
        public int size() {
            FluidStore s = store.get();
            return s == null ? 0 : s.tanks();
        }

        @Override
        public FluidResource getResource(int index) {
            FluidStore s = store.get();
            return s == null ? FluidResource.EMPTY : resource(s.fluid(index));
        }

        @Override
        public long getAmountAsLong(int index) {
            FluidStore s = store.get();
            return s == null ? 0 : s.amount(index);
        }

        @Override
        public long getCapacityAsLong(int index, FluidResource resource) {
            FluidStore s = store.get();
            return s == null ? 0 : s.capacity(index);
        }

        @Override
        public boolean isValid(int index, FluidResource resource) {
            FluidStore s = store.get();
            McFluidVariant v = toVariant(resource);
            return s != null && v != null && s.fill(v, 1, true) > 0;
        }

        @Override
        public int insert(int index, FluidResource resource, int amount, TransactionContext tx) {
            FluidStore s = store.get();
            McFluidVariant v = toVariant(resource);
            if (s == null || v == null || amount <= 0 || s.fill(v, amount, true) <= 0) {
                return 0;
            }
            journal.updateSnapshots(tx);
            return (int) Transfers.clamp(s.fill(v, amount, false), amount);
        }

        @Override
        public int extract(int index, FluidResource resource, int amount, TransactionContext tx) {
            FluidStore s = store.get();
            McFluidVariant v = toVariant(resource);
            if (s == null || v == null || amount <= 0 || s.drain(v, amount, true) <= 0) {
                return 0;
            }
            journal.updateSnapshots(tx);
            return (int) Transfers.clamp(s.drain(v, amount, false), amount);
        }
    }

    public static final class ExposedEnergy implements EnergyHandler {
        private final Supplier<EnergyStore> store;
        private final ChassisJournal journal;

        public ExposedEnergy(Supplier<EnergyStore> store, ChassisJournal journal) {
            this.store = store;
            this.journal = journal;
        }

        @Override
        public long getAmountAsLong() {
            EnergyStore s = store.get();
            return s == null ? 0 : s.stored();
        }

        @Override
        public long getCapacityAsLong() {
            EnergyStore s = store.get();
            return s == null ? 0 : s.capacity();
        }

        @Override
        public int insert(int amount, TransactionContext tx) {
            EnergyStore s = store.get();
            if (s == null || amount <= 0 || s.receive(amount, true) <= 0) {
                return 0;
            }
            journal.updateSnapshots(tx);
            return (int) Transfers.clamp(s.receive(amount, false), amount);
        }

        @Override
        public int extract(int amount, TransactionContext tx) {
            EnergyStore s = store.get();
            if (s == null || amount <= 0 || s.extract(amount, true) <= 0) {
                return 0;
            }
            journal.updateSnapshots(tx);
            return (int) Transfers.clamp(s.extract(amount, false), amount);
        }
    }

    // ------------------------------------------------------------------ external

    public static final class ExternalItems implements ItemStore {
        private final ResourceHandler<ItemResource> handler;

        public ExternalItems(ResourceHandler<ItemResource> handler) {
            this.handler = handler;
        }

        @Override
        public int slots() {
            return handler.size();
        }

        @Override
        public ItemVariant variant(int slot) {
            ItemResource r = handler.getResource(slot);
            return r.isEmpty() || handler.getAmountAsLong(slot) <= 0 ? null : toVariant(r);
        }

        @Override
        public int count(int slot) {
            return (int) Math.max(0, Math.min(Integer.MAX_VALUE, handler.getAmountAsLong(slot)));
        }

        @Override
        public int insert(ItemVariant v, int amount, boolean simulate) {
            ItemResource r = resource(v);
            if (r.isEmpty() || amount <= 0) {
                return 0;
            }
            try (Transaction tx = Transaction.openRoot()) {
                int n = Transfers.clamp(handler.insert(r, amount, tx), amount);
                if (!simulate) {
                    tx.commit();
                }
                return n;
            }
        }

        @Override
        public int insertSlot(int slot, ItemVariant v, int amount, boolean simulate) {
            ItemResource r = resource(v);
            if (r.isEmpty() || amount <= 0 || slot < 0 || slot >= handler.size()) {
                return 0;
            }
            try (Transaction tx = Transaction.openRoot()) {
                int n = Transfers.clamp(handler.insert(slot, r, amount, tx), amount);
                if (!simulate) {
                    tx.commit();
                }
                return n;
            }
        }

        @Override
        public int extractSlot(int slot, int amount, boolean simulate) {
            if (amount <= 0 || slot < 0 || slot >= handler.size()) {
                return 0;
            }
            ItemResource r = handler.getResource(slot);
            if (r.isEmpty()) {
                return 0;
            }
            try (Transaction tx = Transaction.openRoot()) {
                int n = Transfers.clamp(handler.extract(slot, r, amount, tx), amount);
                if (!simulate) {
                    tx.commit();
                }
                return n;
            }
        }
    }

    public static final class ExternalFluids implements FluidStore {
        private final ResourceHandler<FluidResource> handler;

        public ExternalFluids(ResourceHandler<FluidResource> handler) {
            this.handler = handler;
        }

        @Override
        public int tanks() {
            return handler.size();
        }

        @Override
        public FluidVariant fluid(int tank) {
            FluidResource r = handler.getResource(tank);
            return r.isEmpty() || handler.getAmountAsLong(tank) <= 0 ? null : toVariant(r);
        }

        @Override
        public long amount(int tank) {
            return Math.max(0, handler.getAmountAsLong(tank));
        }

        @Override
        public long capacity(int tank) {
            return Math.max(0, handler.getCapacityAsLong(tank, handler.getResource(tank)));
        }

        @Override
        public long fill(FluidVariant v, long amount, boolean simulate) {
            FluidResource r = resource(v);
            if (r.isEmpty() || amount <= 0) {
                return 0;
            }
            int req = (int) Math.min(Integer.MAX_VALUE, amount);
            try (Transaction tx = Transaction.openRoot()) {
                int n = Transfers.clamp(handler.insert(r, req, tx), req);
                if (!simulate) {
                    tx.commit();
                }
                return n;
            }
        }

        @Override
        public long drain(FluidVariant v, long amount, boolean simulate) {
            if (amount <= 0) {
                return 0;
            }
            FluidResource r = v == null ? null : resource(v);
            if (r == null) {
                for (int t = 0; t < handler.size() && (r == null || r.isEmpty()); t++) {
                    if (handler.getAmountAsLong(t) > 0) {
                        r = handler.getResource(t);
                    }
                }
            }
            if (r == null || r.isEmpty()) {
                return 0;
            }
            int req = (int) Math.min(Integer.MAX_VALUE, amount);
            try (Transaction tx = Transaction.openRoot()) {
                int n = Transfers.clamp(handler.extract(r, req, tx), req);
                if (!simulate) {
                    tx.commit();
                }
                return n;
            }
        }
    }

    public static final class ExternalEnergy implements EnergyStore {
        private final EnergyHandler handler;

        public ExternalEnergy(EnergyHandler handler) {
            this.handler = handler;
        }

        @Override
        public long stored() {
            return Math.max(0, handler.getAmountAsLong());
        }

        @Override
        public long capacity() {
            return Math.max(0, handler.getCapacityAsLong());
        }

        @Override
        public long receive(long amount, boolean simulate) {
            if (amount <= 0) {
                return 0;
            }
            int req = (int) Math.min(Integer.MAX_VALUE, amount);
            try (Transaction tx = Transaction.openRoot()) {
                int n = Transfers.clamp(handler.insert(req, tx), req);
                if (!simulate) {
                    tx.commit();
                }
                return n;
            }
        }

        @Override
        public long extract(long amount, boolean simulate) {
            if (amount <= 0) {
                return 0;
            }
            int req = (int) Math.min(Integer.MAX_VALUE, amount);
            try (Transaction tx = Transaction.openRoot()) {
                int n = Transfers.clamp(handler.extract(req, tx), req);
                if (!simulate) {
                    tx.commit();
                }
                return n;
            }
        }
    }

    /** Convenience for tests and stacks. */
    public static ItemStack stack(ItemVariant v, int count) {
        return McItemVariant.toStack(v, count);
    }

    public static FluidStack fluid(FluidVariant v, long amount) {
        return McFluidVariant.toStack(v, amount);
    }
}
