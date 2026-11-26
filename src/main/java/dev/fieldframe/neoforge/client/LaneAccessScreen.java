package dev.fieldframe.neoforge.client;

import dev.fieldframe.api.chassis.ChassisSpec;
import dev.fieldframe.neoforge.menu.LaneAccessMenu;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;

/** Lane access panel GUI: each permitted lane is a labelled bank of slots on schematic paper. */
public final class LaneAccessScreen extends AbstractContainerScreen<LaneAccessMenu> {
    public LaneAccessScreen(LaneAccessMenu menu, Inventory inv, Component title) {
        super(menu, inv, title, 176, lanesHeight(menu) + 4 + 76 + 6);
        this.inventoryLabelY = lanesHeight(menu) - 6;
    }

    private static int lanesHeight(LaneAccessMenu menu) {
        int y = 18;
        for (int lane = 0; lane < ChassisSpec.ITEM_LANES; lane++) {
            if ((menu.mask() & (1 << lane)) != 0) {
                y += ((menu.laneSlots(lane) + 8) / 9) * 18 + 12;
            }
        }
        return y;
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mx, int my, float partial) {
        super.extractBackground(g, mx, my, partial);
        Schematic.panel(g, leftPos, topPos, imageWidth, imageHeight);
        for (Slot s : menu.slots) {
            Schematic.slot(g, leftPos + s.x, topPos + s.y);
        }
        int y = 18;
        for (int lane = 0; lane < ChassisSpec.ITEM_LANES; lane++) {
            if ((menu.mask() & (1 << lane)) == 0) {
                continue;
            }
            String label = net.minecraft.client.resources.language.I18n.get("fieldframe.lane." + lane);
            int colour = ConfiguratorScreen.LANE_COLOURS[lane];
            g.fill(leftPos + 4, topPos + y - 9, leftPos + 6, topPos + y - 2, colour);
            g.text(font, label, leftPos + 8, topPos + y - 9, colour, false);
            y += ((menu.laneSlots(lane) + 8) / 9) * 18 + 12;
        }
    }

    @Override
    protected void extractLabels(GuiGraphicsExtractor g, int mx, int my) {
        g.text(font, title, titleLabelX, 5, Schematic.INK, false);
        g.text(font, playerInventoryTitle, inventoryLabelX, inventoryLabelY, Schematic.INK_DIM, false);
    }

}
