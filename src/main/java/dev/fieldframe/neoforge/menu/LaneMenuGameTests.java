package dev.fieldframe.neoforge.menu;

import dev.fieldframe.core.storage.ItemLane;
import dev.fieldframe.neoforge.adapter.McItemVariant;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.transfer.item.ItemResource;
import net.neoforged.neoforge.transfer.item.ResourceHandlerSlot;
import net.neoforged.neoforge.transfer.transaction.Transaction;

/** Transaction rollback and vanilla slot writes over a real logical lane. */
public final class LaneMenuGameTests {
    private LaneMenuGameTests() {}

    public static void transactionalSlots(GameTestHelper helper) {
        ItemLane lane = new ItemLane("menu", 2);
        lane.setSlot(0, McItemVariant.of(new ItemStack(Items.STONE)), 8);
        var handler = new LaneAccessMenu.LaneHandler(lane);
        try (var tx = Transaction.openRoot()) {
            helper.assertTrue(handler.extract(0, ItemResource.of(Items.STONE), 5, tx) == 5, "simulate extraction");
        }
        helper.assertTrue(lane.count(0) == 8, "aborted extraction restores the lane");
        try (var tx = Transaction.openRoot()) {
            helper.assertTrue(handler.insert(1, ItemResource.of(Items.DIAMOND), 3, tx) == 3, "transactional insertion");
            tx.commit();
        }
        helper.assertTrue(lane.count(1) == 3, "committed insertion remains");
        var slot = new ResourceHandlerSlot(handler, handler::set, 0, 0, 0);
        slot.set(new ItemStack(Items.STONE, 6));
        helper.assertTrue(lane.count(0) == 6, "slot replacement writes through");
        var carried = slot.getItem();
        carried.shrink(2);
        slot.setChanged();
        helper.assertTrue(lane.count(0) == 4, "slot changes conserve the remaining stack");
        helper.assertTrue(handler.insertItem(0, new ItemStack(Items.STONE, 7), true).isEmpty(), "simulated insert fits");
        helper.assertTrue(lane.count(0) == 4, "simulated quick move leaves contents unchanged");
        handler.insertItem(0, new ItemStack(Items.STONE, 7), false);
        helper.assertTrue(lane.count(0) == 11, "executed quick move writes once");
        helper.succeed();
    }
}
