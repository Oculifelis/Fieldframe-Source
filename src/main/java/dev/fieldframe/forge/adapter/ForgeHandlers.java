package dev.fieldframe.forge.adapter;

import dev.fieldframe.api.energy.Energy;
import dev.fieldframe.api.resource.EnergyStore;
import dev.fieldframe.api.resource.FluidStore;
import dev.fieldframe.api.resource.FluidVariant;
import dev.fieldframe.api.resource.ItemStore;
import dev.fieldframe.api.resource.ItemVariant;
import dev.fieldframe.core.util.Transfers;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.energy.IEnergyStorage;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraftforge.items.IItemHandler;

import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Two-way adapters between Fieldframe stores and Forge 1.20.1 capabilities.
 * <ul>
 *   <li>{@code Exposed*}: Forge handler views over a chassis face (resolved per call so they never go stale).</li>
 *   <li>{@code External*}: Fieldframe stores over a neighbour's Forge handler, clamping every reported amount.</li>
 * </ul>
 * Forge's simulate/execute handlers are not transactional; transfers use the conservative pattern in
 * {@link Transfers} and never credit a movement that did not happen.
 */
public final class ForgeHandlers {
    private ForgeHandlers() {
    }

    // ------------------------------------------------------------------ exposed (our faces -> Forge)

    public static final class ExposedItems implements IItemHandler {
        private final Supplier<ItemStore> store;

        public ExposedItems(Supplier<ItemStore> store) {
            this.store = store;
        }

        @Override
        public int getSlots() {
            ItemStore s = store.get();
            return s == null ? 0 : s.slots();
        }

        @Override
        public ItemStack getStackInSlot(int slot) {
            ItemStore s = store.get();
            if (s == null) {
                return ItemStack.EMPTY;
            }
            ItemVariant v = s.variant(slot);
            return v == null ? ItemStack.EMPTY : McItemVariant.toStack(v, s.count(slot));
        }

        @Override
        public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
            ItemStore s = store.get();
            McItemVariant v = McItemVariant.of(stack);
            if (s == null || v == null) {
                return stack;
            }
            int accepted = Transfers.clamp(s.insertSlot(slot, v, stack.getCount(), simulate), stack.getCount());
            return accepted >= stack.getCount() ? ItemStack.EMPTY : stack.copyWithCount(stack.getCount() - accepted);
        }

        @Override
        public ItemStack extractItem(int slot, int amount, boolean simulate) {
            ItemStore s = store.get();
            if (s == null || amount <= 0) {
                return ItemStack.EMPTY;
            }
            ItemVariant v = s.variant(slot);
            if (!(v instanceof McItemVariant)) {
                return ItemStack.EMPTY; // missing-definition items stay inside
            }
            int got = Transfers.clamp(s.extractSlot(slot, amount, simulate), amount);
            return got <= 0 ? ItemStack.EMPTY : McItemVariant.toStack(v, got);
        }

        @Override
        public int getSlotLimit(int slot) {
            return 64;
        }

        @Override
        public boolean isItemValid(int slot, ItemStack stack) {
            ItemStore s = store.get();
            McItemVariant v = McItemVariant.of(stack);
            return s != null && v != null && s.insertSlot(slot, v, 1, true) > 0;
        }
    }

    public static final class ExposedFluids implements IFluidHandler {
        private final Supplier<FluidStore> store;

        public ExposedFluids(Supplier<FluidStore> store) {
            this.store = store;
        }

        @Override
        public int getTanks() {
            FluidStore s = store.get();
            return s == null ? 0 : s.tanks();
        }

        @Override
        public FluidStack getFluidInTank(int tank) {
            FluidStore s = store.get();
            return s == null ? FluidStack.EMPTY : McFluidVariant.toStack(s.fluid(tank), s.amount(tank));
        }

        @Override
        public int getTankCapacity(int tank) {
            FluidStore s = store.get();
            return s == null ? 0 : Energy.clampToInt(s.capacity(tank));
        }

        @Override
        public boolean isFluidValid(int tank, FluidStack stack) {
            FluidStore s = store.get();
            McFluidVariant v = McFluidVariant.of(stack);
            return s != null && v != null && s.fill(v, 1, true) > 0;
        }

        @Override
        public int fill(FluidStack resource, FluidAction action) {
            FluidStore s = store.get();
            McFluidVariant v = McFluidVariant.of(resource);
            if (s == null || v == null) {
                return 0;
            }
            return (int) Transfers.clamp(s.fill(v, resource.getAmount(), action.simulate()), resource.getAmount());
        }

        @Override
        public FluidStack drain(FluidStack resource, FluidAction action) {
            FluidStore s = store.get();
            McFluidVariant v = McFluidVariant.of(resource);
            if (s == null || v == null) {
                return FluidStack.EMPTY;
            }
            long got = Transfers.clamp(s.drain(v, resource.getAmount(), action.simulate()), resource.getAmount());
            return got <= 0 ? FluidStack.EMPTY : v.toStack((int) got);
        }

        @Override
        public FluidStack drain(int maxDrain, FluidAction action) {
            FluidStore s = store.get();
            if (s == null || maxDrain <= 0) {
                return FluidStack.EMPTY;
            }
            FluidVariant present = s.drainableFluid();
            if (!(present instanceof McFluidVariant m)) {
                return FluidStack.EMPTY;
            }
            long got = Transfers.clamp(s.drain(present, maxDrain, action.simulate()), maxDrain);
            return got <= 0 ? FluidStack.EMPTY : m.toStack((int) got);
        }
    }

    public static final class ExposedEnergy implements IEnergyStorage {
        private final Supplier<EnergyStore> store;

        public ExposedEnergy(Supplier<EnergyStore> store) {
            this.store = store;
        }

        @Override
        public int receiveEnergy(int maxReceive, boolean simulate) {
            EnergyStore s = store.get();
            return s == null || maxReceive <= 0 ? 0 : Energy.clampToInt(Transfers.clamp(s.receive(maxReceive, simulate), maxReceive));
        }

        @Override
        public int extractEnergy(int maxExtract, boolean simulate) {
            EnergyStore s = store.get();
            return s == null || maxExtract <= 0 ? 0 : Energy.clampToInt(Transfers.clamp(s.extract(maxExtract, simulate), maxExtract));
        }

        @Override
        public int getEnergyStored() {
            EnergyStore s = store.get();
            return s == null ? 0 : Energy.clampToInt(s.stored());
        }

        @Override
        public int getMaxEnergyStored() {
            EnergyStore s = store.get();
            return s == null ? 0 : Energy.clampToInt(s.capacity());
        }

        @Override
        public boolean canExtract() {
            EnergyStore s = store.get();
            return s != null && s.canExtract();
        }

        @Override
        public boolean canReceive() {
            EnergyStore s = store.get();
            return s != null && s.canReceive();
        }
    }

    // ------------------------------------------------------------------ external (Forge neighbours -> our stores)

    public static final class ExternalItems implements ItemStore {
        private final IItemHandler handler;
        private final Consumer<ItemStack> stray;

        /** {@code stray} receives anything a misbehaving handler hands back that is not what was asked for. */
        public ExternalItems(IItemHandler handler, Consumer<ItemStack> stray) {
            this.handler = handler;
            this.stray = stray;
        }

        @Override
        public int slots() {
            return handler.getSlots();
        }

        @Override
        public ItemVariant variant(int slot) {
            return McItemVariant.of(handler.getStackInSlot(slot));
        }

        @Override
        public int count(int slot) {
            return Math.max(0, handler.getStackInSlot(slot).getCount());
        }

        @Override
        public int insert(ItemVariant v, int amount, boolean simulate) {
            if (!(v instanceof McItemVariant m) || amount <= 0) {
                return 0;
            }
            ItemStack remaining = m.toStack(amount);
            for (int s = 0; s < handler.getSlots() && !remaining.isEmpty(); s++) {
                remaining = handler.insertItem(s, remaining, simulate);
            }
            return Transfers.clamp(amount - remaining.getCount(), amount);
        }

        @Override
        public int insertSlot(int slot, ItemVariant v, int amount, boolean simulate) {
            if (!(v instanceof McItemVariant m) || amount <= 0 || slot < 0 || slot >= handler.getSlots()) {
                return 0;
            }
            ItemStack rest = handler.insertItem(slot, m.toStack(amount), simulate);
            return Transfers.clamp(amount - rest.getCount(), amount);
        }

        @Override
        public int extractSlot(int slot, int amount, boolean simulate) {
            if (amount <= 0) {
                return 0;
            }
            ItemStack expected = handler.getStackInSlot(slot).copy(); // snapshot: handlers may mutate the live stack
            ItemStack got = handler.extractItem(slot, amount, simulate);
            if (got.isEmpty()) {
                return 0;
            }
            if (!ItemStack.isSameItemSameTags(expected, got)) {
                // Handler returned something else: never lose it.
                if (!simulate) {
                    ItemStack back = got;
                    for (int s = 0; s < handler.getSlots() && !back.isEmpty(); s++) {
                        back = handler.insertItem(s, back, false);
                    }
                    if (!back.isEmpty()) {
                        stray.accept(back);
                    }
                }
                return 0;
            }
            int n = Transfers.clamp(got.getCount(), amount);
            if (!simulate && got.getCount() > n) {
                stray.accept(got.copyWithCount(got.getCount() - n));
            }
            return n;
        }
    }

    public static final class ExternalFluids implements FluidStore {
        private final IFluidHandler handler;

        public ExternalFluids(IFluidHandler handler) {
            this.handler = handler;
        }

        @Override
        public int tanks() {
            return handler.getTanks();
        }

        @Override
        public FluidVariant fluid(int tank) {
            return McFluidVariant.of(handler.getFluidInTank(tank));
        }

        @Override
        public long amount(int tank) {
            return Math.max(0, handler.getFluidInTank(tank).getAmount());
        }

        @Override
        public long capacity(int tank) {
            return Math.max(0, handler.getTankCapacity(tank));
        }

        @Override
        public long fill(FluidVariant v, long amount, boolean simulate) {
            if (!(v instanceof McFluidVariant m) || amount <= 0) {
                return 0;
            }
            int req = Energy.clampToInt(amount);
            return Transfers.clamp(handler.fill(m.toStack(req), simulate ? IFluidHandler.FluidAction.SIMULATE : IFluidHandler.FluidAction.EXECUTE), req);
        }

        @Override
        public long drain(FluidVariant v, long amount, boolean simulate) {
            if (amount <= 0) {
                return 0;
            }
            int req = Energy.clampToInt(amount);
            IFluidHandler.FluidAction a = simulate ? IFluidHandler.FluidAction.SIMULATE : IFluidHandler.FluidAction.EXECUTE;
            FluidStack got;
            if (v instanceof McFluidVariant m) {
                got = handler.drain(m.toStack(req), a);
                if (!got.isEmpty() && !got.isFluidEqual(m.toStack(1))) {
                    return 0; // wrong fluid reported; refuse to count it
                }
            } else if (v == null) {
                got = handler.drain(req, a);
            } else {
                return 0;
            }
            return Transfers.clamp(got.getAmount(), req);
        }
    }

    public static final class ExternalEnergy implements EnergyStore {
        private final IEnergyStorage handler;

        public ExternalEnergy(IEnergyStorage handler) {
            this.handler = handler;
        }

        @Override
        public long stored() {
            return Math.max(0, handler.getEnergyStored());
        }

        @Override
        public long capacity() {
            return Math.max(0, handler.getMaxEnergyStored());
        }

        @Override
        public long receive(long amount, boolean simulate) {
            if (amount <= 0 || !handler.canReceive()) {
                return 0;
            }
            int req = Energy.clampToInt(amount);
            return Transfers.clamp(handler.receiveEnergy(req, simulate), req);
        }

        @Override
        public long extract(long amount, boolean simulate) {
            if (amount <= 0 || !handler.canExtract()) {
                return 0;
            }
            int req = Energy.clampToInt(amount);
            return Transfers.clamp(handler.extractEnergy(req, simulate), req);
        }

        @Override
        public boolean canReceive() {
            return handler.canReceive();
        }

        @Override
        public boolean canExtract() {
            return handler.canExtract();
        }
    }
}
