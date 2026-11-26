package dev.fieldframe.neoforge.menu;

import dev.fieldframe.api.chassis.ChassisSpec;
import dev.fieldframe.api.resource.ItemVariant;
import dev.fieldframe.core.storage.ItemLane;
import dev.fieldframe.neoforge.adapter.McItemVariant;
import dev.fieldframe.neoforge.chassis.ChassisBlockEntity;
import dev.fieldframe.neoforge.registry.FfContent;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemStackHandler;
import net.neoforged.neoforge.items.SlotItemHandler;

/**
 * Player access to the item lanes permitted by installed Inventory Access Panels — and only those. The menu
 * closes as soon as the permitting panel is removed or reconfigured. Server-side slots write straight into the
 * chassis lane (conserving); the client uses placeholder handlers synchronized by the vanilla menu protocol.
 * A Field Kit opens all its lanes the same way (see {@link #openKit}).
 */
public final class LaneAccessMenu extends AbstractContainerMenu {
    private final ChassisBlockEntity be;
    private final dev.fieldframe.neoforge.chassis.FieldKitRuntime.Kit kit;
    private final Player viewer;
    private final BlockPos pos;
    private final int mask;
    private final int[] laneSlots = new int[ChassisSpec.ITEM_LANES];
    private final int laneSlotCount;

    public static MenuProvider provider(ChassisBlockEntity be) {
        return new MenuProvider() {
            @Override
            public Component getDisplayName() {
                return Component.translatable("fieldframe.gui.lane_access");
            }

            @Override
            public AbstractContainerMenu createMenu(int id, Inventory inv, Player player) {
                return new LaneAccessMenu(id, inv, be, null, be.getBlockPos(), mask(be), sizes(be));
            }
        };
    }

    /** Server: opens every lane of a carried Field Kit. */
    public static void openKit(net.minecraft.server.level.ServerPlayer player, dev.fieldframe.neoforge.chassis.FieldKitRuntime.Kit kit) {
        int[] sizes = sizes(kit.core);
        player.openMenu(new MenuProvider() {
            @Override
            public Component getDisplayName() {
                return Component.translatable("item.fieldframe.field_kit");
            }

            @Override
            public AbstractContainerMenu createMenu(int id, Inventory inv, Player p) {
                return new LaneAccessMenu(id, inv, null, kit, dev.fieldframe.neoforge.item.FieldKitItem.KIT_POS, 7, sizes);
            }
        }, buf -> {
            buf.writeBlockPos(dev.fieldframe.neoforge.item.FieldKitItem.KIT_POS);
            buf.writeVarInt(7);
            for (int s : sizes) {
                buf.writeVarInt(s);
            }
        });
    }

    private static int mask(ChassisBlockEntity be) {
        boolean[] p = be.core().playerAccessibleLanes();
        int m = 0;
        for (int i = 0; i < p.length; i++) {
            if (p[i]) {
                m |= 1 << i;
            }
        }
        return m;
    }

    private static int[] sizes(ChassisBlockEntity be) {
        return sizes(be.core());
    }

    private static int[] sizes(dev.fieldframe.core.chassis.ChassisCore core) {
        int[] s = new int[ChassisSpec.ITEM_LANES];
        for (int i = 0; i < s.length; i++) {
            s[i] = core.lane(i).slots();
        }
        return s;
    }

    public static void writeOpenData(RegistryFriendlyByteBuf buf, ChassisBlockEntity be) {
        buf.writeBlockPos(be.getBlockPos());
        buf.writeVarInt(mask(be));
        for (int s : sizes(be)) {
            buf.writeVarInt(s);
        }
    }

    public static LaneAccessMenu fromNetwork(int id, Inventory inv, RegistryFriendlyByteBuf buf) {
        BlockPos pos = buf.readBlockPos();
        int mask = buf.readVarInt() & 7;
        int[] sizes = new int[ChassisSpec.ITEM_LANES];
        for (int i = 0; i < sizes.length; i++) {
            sizes[i] = Math.max(1, Math.min(ItemLane.MAX_SLOTS, buf.readVarInt()));
        }
        return new LaneAccessMenu(id, inv, null, null, pos, mask, sizes);
    }

    private LaneAccessMenu(int id, Inventory inv, ChassisBlockEntity be, dev.fieldframe.neoforge.chassis.FieldKitRuntime.Kit kit, BlockPos pos, int mask, int[] sizes) {
        super(FfContent.LANE_MENU.get(), id);
        this.be = be;
        this.kit = kit;
        this.viewer = inv.player;
        this.pos = pos;
        this.mask = mask;
        int y = 18;
        int total = 0;
        for (int lane = 0; lane < ChassisSpec.ITEM_LANES; lane++) {
            if ((mask & (1 << lane)) == 0) {
                continue;
            }
            int n = sizes[lane];
            laneSlots[lane] = n;
            IItemHandler handler = be != null ? new LaneHandler(be.core().lane(lane))
                    : kit != null ? new LaneHandler(kit.core.lane(lane)) : new ItemStackHandler(n);
            for (int s = 0; s < n; s++) {
                addSlot(new SlotItemHandler(handler, s, 8 + (s % 9) * 18, y + (s / 9) * 18) {
                    @Override
                    public boolean mayPlace(ItemStack stack) {
                        // A kit never goes inside a kit.
                        return !(stack.getItem() instanceof dev.fieldframe.neoforge.item.FieldKitItem) && super.mayPlace(stack);
                    }
                });
            }
            total += n;
            y += ((n + 8) / 9) * 18 + 12;
        }
        laneSlotCount = total;
        int invY = y + 4;
        for (int r = 0; r < 3; r++) {
            for (int c = 0; c < 9; c++) {
                addSlot(new Slot(inv, c + r * 9 + 9, 8 + c * 18, invY + r * 18));
            }
        }
        for (int c = 0; c < 9; c++) {
            addSlot(new Slot(inv, c, 8 + c * 18, invY + 58));
        }
    }

    public int mask() {
        return mask;
    }

    public int laneSlots(int lane) {
        return laneSlots[lane];
    }

    public int laneSlotCount() {
        return laneSlotCount;
    }

    @Override
    public boolean stillValid(Player player) {
        if (kit != null) {
            return !dev.fieldframe.neoforge.chassis.FieldKitRuntime.find(player, kit.id).isEmpty()
                    && java.util.Arrays.equals(sizes(kit.core), laneSlots);
        }
        if (be == null) {
            return true;
        }
        BlockEntity live = player.level().getBlockEntity(pos);
        return live == be && !be.isRemoved() && player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) <= 64
                && mask(be) == mask && java.util.Arrays.equals(sizes(be), sizesFor());
    }

    private int[] sizesFor() {
        int[] s = sizes(be);
        for (int i = 0; i < s.length; i++) {
            if ((mask & (1 << i)) != 0) {
                s[i] = laneSlots[i];
            }
        }
        return s;
    }

    /** A kit's lanes changed by hand are written onto the item straight away. */
    @Override
    public void broadcastChanges() {
        super.broadcastChanges();
        if (kit != null && kit.core.consumeDirty()) {
            dev.fieldframe.neoforge.chassis.FieldKitRuntime.saveNow(viewer, kit);
        }
    }

    @Override
    public void removed(Player player) {
        super.removed(player);
        if (kit != null && !player.level().isClientSide()) {
            dev.fieldframe.neoforge.chassis.FieldKitRuntime.saveNow(player, kit);
        }
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        Slot slot = slots.get(index);
        if (!slot.hasItem()) {
            return ItemStack.EMPTY;
        }
        ItemStack stack = slot.getItem();
        ItemStack copy = stack.copy();
        if (index < laneSlotCount) {
            if (!moveItemStackTo(stack, laneSlotCount, slots.size(), true)) {
                return ItemStack.EMPTY;
            }
        } else if (!moveItemStackTo(stack, 0, laneSlotCount, false)) {
            return ItemStack.EMPTY;
        }
        if (stack.isEmpty()) {
            slot.set(ItemStack.EMPTY);
        } else {
            slot.setChanged();
        }
        return copy;
    }

    /** IItemHandlerModifiable over a lane, needed by SlotItemHandler#set. */
    static final class LaneHandler implements net.neoforged.neoforge.items.IItemHandlerModifiable {
        private final ItemLane lane;

        LaneHandler(ItemLane lane) {
            this.lane = lane;
        }

        @Override
        public void setStackInSlot(int slot, ItemStack stack) {
            if (lane.variant(slot) != null && !(lane.variant(slot) instanceof McItemVariant)) {
                return; // never overwrite a recoverable missing-definition item
            }
            McItemVariant v = McItemVariant.of(stack);
            lane.setSlot(slot, v, v == null ? 0 : Math.min(stack.getCount(), v.maxStackSize()));
        }

        @Override
        public int getSlots() {
            return lane.slots();
        }

        @Override
        public ItemStack getStackInSlot(int slot) {
            ItemVariant v = lane.variant(slot);
            return v == null ? ItemStack.EMPTY : McItemVariant.toStack(v, lane.count(slot));
        }

        @Override
        public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
            McItemVariant v = McItemVariant.of(stack);
            if (v == null) {
                return stack;
            }
            int n = lane.insertSlot(slot, v, stack.getCount(), simulate);
            return n >= stack.getCount() ? ItemStack.EMPTY : stack.copyWithCount(stack.getCount() - n);
        }

        @Override
        public ItemStack extractItem(int slot, int amount, boolean simulate) {
            ItemVariant v = lane.variant(slot);
            if (!(v instanceof McItemVariant)) {
                return ItemStack.EMPTY;
            }
            int n = lane.extractSlot(slot, amount, simulate);
            return n <= 0 ? ItemStack.EMPTY : McItemVariant.toStack(v, n);
        }

        @Override
        public int getSlotLimit(int slot) {
            return 64;
        }

        @Override
        public boolean isItemValid(int slot, ItemStack stack) {
            return true;
        }
    }
}
