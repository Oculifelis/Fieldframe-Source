package dev.fieldframe.forge.menu;

import dev.fieldframe.api.chassis.ChassisSpec;
import dev.fieldframe.api.face.LocalFace;
import dev.fieldframe.api.module.BindingSpec;
import dev.fieldframe.core.chassis.InstalledModule;
import dev.fieldframe.core.gui.WorkshopLayout;
import dev.fieldframe.core.storage.ItemLane;
import dev.fieldframe.forge.chassis.ChassisBlockEntity;
import dev.fieldframe.forge.registry.FfContent;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.DataSlot;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.IItemHandlerModifiable;
import net.minecraftforge.items.ItemStackHandler;
import net.minecraftforge.items.SlotItemHandler;

/**
 * The Workshop machine screen's container: the chosen side's module with its lanes as real slots (2 x 2 input,
 * 2 x 2 result, one fuel/tool slot) above the player's inventory. Lane slots follow the same rule as
 * {@link LaneAccessMenu}: they can be changed only while an Inventory Access Panel opens that lane; otherwise they
 * only show what is inside. Everything else on the screen goes through the validated {@code FfNetwork.Action} path.
 */
public final class WorkshopMenu extends AbstractContainerMenu {
    /** Per lane: 4 input-grid slots, 4 result-grid slots, 1 fuel slot. */
    public static final int PER_LANE = 9, LANE_SLOTS = ChassisSpec.ITEM_LANES * PER_LANE;

    private final ChassisBlockEntity be;
    private final BlockPos pos;
    private int face;
    private int mask;
    private final int[] sizes = new int[ChassisSpec.ITEM_LANES];
    /** Client: which lane each role shows (-1 none), set by the screen from the snapshot. Index: Role ordinal. */
    private final int[] roleLane = {-1, -1, -1};

    public static MenuProvider provider(ChassisBlockEntity be, LocalFace face) {
        return new MenuProvider() {
            @Override
            public Component getDisplayName() {
                return Component.translatable("fieldframe.gui.machine");
            }

            @Override
            public AbstractContainerMenu createMenu(int id, Inventory inv, Player player) {
                return new WorkshopMenu(id, inv, be, be.getBlockPos(), face.ordinal());
            }
        };
    }

    public static void writeOpenData(FriendlyByteBuf buf, ChassisBlockEntity be, LocalFace face) {
        buf.writeBlockPos(be.getBlockPos());
        buf.writeVarInt(face.ordinal());
    }

    public static WorkshopMenu fromNetwork(int id, Inventory inv, FriendlyByteBuf buf) {
        BlockPos pos = buf.readBlockPos();
        int face = Math.max(0, Math.min(5, buf.readVarInt()));
        return new WorkshopMenu(id, inv, null, pos, face);
    }

    private WorkshopMenu(int id, Inventory inv, ChassisBlockEntity be, BlockPos pos, int face) {
        super(FfContent.WORKSHOP_MENU.get(), id);
        this.be = be;
        this.pos = pos;
        this.face = face;
        if (be != null) {
            mask = liveMask();
            for (int i = 0; i < sizes.length; i++) {
                sizes[i] = be.core().lane(i).slots();
            }
        }
        for (int lane = 0; lane < ChassisSpec.ITEM_LANES; lane++) {
            IItemHandlerModifiable handler = be != null ? new Bounded(new LaneAccessMenu.LaneHandler(be.core().lane(lane)))
                    : new ItemStackHandler(WorkshopLayout.GRID_SLOTS);
            for (int k = 0; k < PER_LANE; k++) {
                int block = k < 4 ? 0 : k < 8 ? 1 : 2;
                int cell = k < 8 ? k % 4 : 0;
                int x = block == 0 ? WorkshopLayout.gridX(WorkshopLayout.INPUT_X, cell)
                        : block == 1 ? WorkshopLayout.gridX(WorkshopLayout.RESULT_X, cell) : WorkshopLayout.FUEL_X;
                int y = block == 2 ? WorkshopLayout.FUEL_Y : WorkshopLayout.gridY(cell);
                addSlot(new LaneSlot(handler, lane, cell, block, x, y));
            }
        }
        for (int r = 0; r < 3; r++) {
            for (int c = 0; c < 9; c++) {
                addSlot(new Slot(inv, c + r * 9 + 9, 8 + c * 18, WorkshopLayout.INV_Y + r * 18));
            }
        }
        for (int c = 0; c < 9; c++) {
            addSlot(new Slot(inv, c, 8 + c * 18, WorkshopLayout.HOTBAR_Y));
        }
        addDataSlot(new DataSlot() {
            @Override
            public int get() {
                return WorkshopMenu.this.face;
            }

            @Override
            public void set(int v) {
                WorkshopMenu.this.face = Math.max(0, Math.min(5, v));
            }
        });
        addDataSlot(new DataSlot() {
            @Override
            public int get() {
                return be != null ? liveMask() : mask;
            }

            @Override
            public void set(int v) {
                mask = v & 7;
            }
        });
        for (int i = 0; i < sizes.length; i++) {
            int lane = i;
            addDataSlot(new DataSlot() {
                @Override
                public int get() {
                    return be != null ? be.core().lane(lane).slots() : sizes[lane];
                }

                @Override
                public void set(int v) {
                    sizes[lane] = Math.max(0, Math.min(ItemLane.MAX_SLOTS, v));
                }
            });
        }
    }

    private int liveMask() {
        boolean[] p = be.core().playerAccessibleLanes();
        int m = 0;
        for (int i = 0; i < p.length; i++) {
            if (p[i]) {
                m |= 1 << i;
            }
        }
        return m;
    }

    public BlockPos pos() {
        return pos;
    }

    public int face() {
        return face;
    }

    /** Server: the player picked another side (face tab, or the select_face action). */
    public void setFace(int face) {
        this.face = Math.max(0, Math.min(5, face));
    }

    public int mask() {
        return be != null ? liveMask() : mask;
    }

    public boolean laneOpen(int lane) {
        return (mask() & (1 << lane)) != 0;
    }

    public int laneSize(int lane) {
        return sizes[lane];
    }

    /** Client: show {@code lane} (or -1 for nothing) in the block for {@code role}. */
    public void showLane(WorkshopLayout.Role role, int lane) {
        roleLane[role.ordinal()] = lane;
    }

    public int shownLane(WorkshopLayout.Role role) {
        return roleLane[role.ordinal()];
    }

    @Override
    public boolean clickMenuButton(Player player, int id) {
        if (id >= 0 && id < 6) {
            setFace(id);
            return true;
        }
        return false;
    }

    @Override
    public boolean stillValid(Player player) {
        if (be == null) {
            return true;
        }
        BlockEntity live = player.level().getBlockEntity(pos);
        return live == be && !be.isRemoved() && player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) <= 64;
    }

    /** Lane slot -> inventory; inventory -> the current side's input lane (when that lane is open to players). */
    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        Slot slot = slots.get(index);
        if (!slot.hasItem()) {
            return ItemStack.EMPTY;
        }
        ItemStack stack = slot.getItem();
        ItemStack copy = stack.copy();
        if (index < LANE_SLOTS) {
            if (!slot.mayPickup(player) || !moveItemStackTo(stack, LANE_SLOTS, slots.size(), true)) {
                return ItemStack.EMPTY;
            }
        } else {
            int lane = inputLane();
            if (lane < 0 || !laneOpen(lane) || stack.getItem() instanceof dev.fieldframe.forge.item.FieldKitItem) {
                return ItemStack.EMPTY;
            }
            IItemHandler h = new LaneAccessMenu.LaneHandler(be.core().lane(lane));
            ItemStack rest = stack;
            for (int s = 0; s < h.getSlots() && !rest.isEmpty(); s++) {
                rest = h.insertItem(s, rest, false);
            }
            if (rest.getCount() == stack.getCount()) {
                return ItemStack.EMPTY;
            }
            stack.setCount(rest.getCount());
            be.setChanged();
        }
        if (stack.isEmpty()) {
            slot.set(ItemStack.EMPTY);
        } else {
            slot.setChanged();
        }
        return copy;
    }

    /** Server: the item lane bound to the current side's input, or -1. */
    private int inputLane() {
        if (be == null) {
            return -1;
        }
        InstalledModule m = be.core().module(LocalFace.ALL[face]);
        if (m == null || m.isMissing()) {
            return -1;
        }
        for (BindingSpec b : m.definition().bindings()) {
            if (b.kind() == BindingSpec.Kind.ITEM_LANE && WorkshopLayout.role(b.key(), b.defaultIndex()) == WorkshopLayout.Role.INPUT) {
                int lane = m.binding(b.key());
                return lane >= 0 && lane < ChassisSpec.ITEM_LANES ? lane : -1;
            }
        }
        return -1;
    }

    private final class LaneSlot extends SlotItemHandler {
        final int lane, cell, block;

        LaneSlot(IItemHandler handler, int lane, int cell, int block, int x, int y) {
            super(handler, cell, x, y);
            this.lane = lane;
            this.cell = cell;
            this.block = block;
        }

        @Override
        public boolean isActive() {
            if (be != null) {
                return true;
            }
            return roleLane[block] == lane && cell < sizes[lane];
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return laneOpen(lane) && cell < sizes[lane] && !(stack.getItem() instanceof dev.fieldframe.forge.item.FieldKitItem)
                    && super.mayPlace(stack);
        }

        @Override
        public boolean mayPickup(Player player) {
            return laneOpen(lane) && cell < sizes[lane] && super.mayPickup(player);
        }
    }

    /** Guards a lane handler against slot numbers past the lane's current size (lanes can shrink). */
    private static final class Bounded implements IItemHandlerModifiable {
        private final IItemHandlerModifiable in;

        Bounded(IItemHandlerModifiable in) {
            this.in = in;
        }

        private boolean ok(int slot) {
            return slot >= 0 && slot < in.getSlots();
        }

        @Override
        public void setStackInSlot(int slot, ItemStack stack) {
            if (ok(slot)) {
                in.setStackInSlot(slot, stack);
            }
        }

        @Override
        public int getSlots() {
            return WorkshopLayout.GRID_SLOTS;
        }

        @Override
        public ItemStack getStackInSlot(int slot) {
            return ok(slot) ? in.getStackInSlot(slot) : ItemStack.EMPTY;
        }

        @Override
        public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
            return ok(slot) ? in.insertItem(slot, stack, simulate) : stack;
        }

        @Override
        public ItemStack extractItem(int slot, int amount, boolean simulate) {
            return ok(slot) ? in.extractItem(slot, amount, simulate) : ItemStack.EMPTY;
        }

        @Override
        public int getSlotLimit(int slot) {
            return ok(slot) ? in.getSlotLimit(slot) : 0;
        }

        @Override
        public boolean isItemValid(int slot, ItemStack stack) {
            return ok(slot) && in.isItemValid(slot, stack);
        }
    }
}
